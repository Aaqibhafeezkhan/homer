package homer.app.services

import homer.app.infra.LangGraphTopologyLimits
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.LangGraphGraphInfo
import homer.app.models.LangGraphTopology
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs the `homer_langgraph inspect` subprocess for one LangGraph command. */
fun interface GraphInspectRunner {
    suspend fun run(
        python: String,
        module: String,
        env: Map<String, String>,
        timeout: Duration,
    ): GraphInspectResult
}

sealed class GraphInspectResult {
    /** stdout of `inspect --json`. */
    data class Ok(
        val json: String,
    ) : GraphInspectResult()

    data class Err(
        val message: String,
    ) : GraphInspectResult()
}

/** JSON printed by `homer_langgraph inspect --json` (see the runtime's README.md). */
@Serializable
private data class InspectOutput(
    val mermaid: String? = null,
    val topology: LangGraphTopology? = null,
)

/**
 * Produces each `Command.LangGraph`'s topology (and deprecated Mermaid source) via
 * `homer_langgraph inspect --json`, the read-only introspection mode. A per-command
 * failure surfaces as [LangGraphGraphInfo.error]; the caller still gets one entry
 * per command.
 *
 * The agent's declared `env` is passed to each inspect subprocess so the agent's
 * `PYTHONPATH` makes its module importable, exactly as a real run does (a run
 * gets that env via the workspace config; introspection has no workspace).
 */
class LangGraphInspectService(
    private val runner: GraphInspectRunner,
    private val timeout: Duration = 15.seconds,
    maxConcurrentInspects: Int = 4,
    private val maxQueueWait: Duration = 5.seconds,
) {
    // One request already serializes its own commands, but concurrent requests were
    // not bounded at all: each `inspect` is a Python interpreter with langgraph
    // imported (~75 MiB RSS) whose exit is awaited by blocking a Dispatchers.IO
    // thread, the same pool SQLite runs on. Callers past the limit wait for a slot,
    // but only for [maxQueueWait] — an unbounded wait just moves the pile-up from
    // interpreters to suspended callers holding connections. Budget is separate from
    // LangGraphRunStatusService's, so the host's worst case is the sum of the two.
    private val inspectSlots = Semaphore(maxConcurrentInspects)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun inspect(agent: Agent): List<LangGraphGraphInfo> {
        val commands = agent.commands.filterIsInstance<Command.LangGraph>()
        if (commands.isEmpty()) return emptyList()
        // One permit for the whole request, not one per command: an agent with N
        // graphs would otherwise queue N times and could wait N * maxQueueWait.
        // Interpreter count is unaffected — a request's commands run in sequence.
        // `acquired` is set inside the timeout block immediately after acquire()
        // returns, with no suspension point between the two: withTimeoutOrNull can
        // report null even when the permit WAS taken (it cancels at the boundary),
        // and trusting its return value alone would leak that permit permanently.
        var acquired = false
        withTimeoutOrNull(maxQueueWait) {
            inspectSlots.acquire()
            acquired = true
        }
        if (!acquired) {
            return commands.map { LangGraphGraphInfo(label = it.label, module = it.module, error = BUSY_MESSAGE) }
        }
        return try {
            commands.map { cmd ->
                when (val result = runner.run(cmd.python, cmd.module, agent.env, timeout)) {
                    is GraphInspectResult.Ok -> {
                        graphInfo(cmd, result.json)
                    }

                    is GraphInspectResult.Err -> {
                        LangGraphGraphInfo(label = cmd.label, module = cmd.module, error = result.message)
                    }
                }
            }
        } finally {
            inspectSlots.release()
        }
    }

    private fun graphInfo(
        cmd: Command.LangGraph,
        raw: String,
    ): LangGraphGraphInfo {
        fun failed(message: String) = LangGraphGraphInfo(label = cmd.label, module = cmd.module, error = message)
        val output =
            runCatching { json.decodeFromString<InspectOutput>(raw) }
                .getOrElse { return failed("unparseable inspect output: ${it.message}") }
        val topology = output.topology ?: return failed("inspect output has no graph topology")
        LangGraphTopologyLimits.violation(topology)?.let { return failed(it) }
        return LangGraphGraphInfo(label = cmd.label, module = cmd.module, topology = topology, mermaid = output.mermaid)
    }

    companion object {
        /** Reported per command when no inspect slot came free in time; retrying is safe. */
        const val BUSY_MESSAGE = "graph inspection is busy (too many concurrent requests) — retry"
    }
}
