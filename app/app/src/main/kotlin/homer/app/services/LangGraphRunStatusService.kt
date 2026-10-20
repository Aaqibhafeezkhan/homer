package homer.app.services

import homer.app.EnvConstants
import homer.app.infra.FileOperations
import homer.app.infra.GraphStateResult
import homer.app.infra.GraphStateRunner
import homer.app.infra.LangGraphTopologyLimits
import homer.app.models.Agent
import homer.app.models.AppConfig
import homer.app.models.Command
import homer.app.models.LangGraphEdgeRef
import homer.app.models.LangGraphRunIdentity
import homer.app.models.LangGraphRunStatus
import homer.app.models.LangGraphSubCommand
import homer.app.models.LangGraphSubProgress
import homer.app.models.LangGraphTopology
import homer.app.models.ProcessInfo
import homer.app.models.SubRunProgress
import homer.app.services.persistence.ContinuationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** JSON shape printed by `homer_langgraph state` (see the runtime's README.md). */
@Serializable
private data class GraphStateOutput(
    val threadId: String,
    val nodes: Map<String, String>,
    val errors: Map<String, String> = emptyMap(),
    // node -> pending interrupt ids parked at that node. Absent on older runtimes
    // (defaults to empty), which keeps `subs` null for them. See ADR-0044.
    val interrupts: Map<String, List<String>> = emptyMap(),
    // node -> the interrupt ids in the thread's history (newest first, capped by
    // the runtime), so a node keeps its fan-out history after the join resolved.
    // Absent on older runtimes, where `interrupts` alone still yields the live subs.
    val interruptsSeen: Map<String, List<String>> = emptyMap(),
    // Nodes whose history hit the runtime's per-node cap, so `interruptsSeen`
    // holds only their newest ids (the live park is never among the dropped).
    val interruptsSeenTruncated: List<String> = emptyList(),
    // The graph's Mermaid source: `state` compiles the graph anyway, so this
    // rides along and saves a second `inspect` subprocess per status request.
    val mermaid: String? = null,
    // Nodes and edges for the console to draw; absent on older runtimes. See ADR-0073.
    val topology: LangGraphTopology? = null,
    // Edges this run's checkpoint history recorded as taken, already filtered to the
    // runtime's own topology; absent on older runtimes or on extraction failure.
    // Re-filtered against the validated topology in status() -- agent-writable
    // checkpoint data, not trusted as-is. See ADR-0075.
    val traversed: List<LangGraphEdgeRef>? = null,
) {
    /**
     * Interrupt ids per node, history included. Falls back to the pending-only map
     * on runtimes that predate `interruptsSeen`; the union (rather than a straight
     * preference) keeps a node's live park visible even if a backend omits the
     * latest snapshot from its history.
     */
    fun nodeInterruptIds(): Map<String, List<String>> =
        (interruptsSeen.keys + interrupts.keys).associateWith { node ->
            ((interruptsSeen[node] ?: emptyList()) + (interrupts[node] ?: emptyList())).distinct().sorted()
        }
}

/** Outcome of one `state` subprocess: parsed output, or the message to report as `error`. */
private sealed interface StateOutcome {
    data class Ok(
        val output: GraphStateOutput,
    ) : StateOutcome

    data class Failed(
        val message: String,
    ) : StateOutcome
}

/**
 * Subprocess input, in full — the interpreter, the graph, the thread, and the env
 * (which carries the owner-scoped state dir), so two principals can never share
 * an entry. Same input, same output, which is what makes it cacheable.
 */
private data class StateKey(
    val python: String,
    val module: String,
    val threadId: String,
    val env: Map<String, String>,
)

private data class CachedState(
    val outcome: StateOutcome,
    val atMillis: Long,
)

/**
 * Per-run LangGraph introspection: reads the run-identity artifact the Python
 * runtime writes to the process's artifacts directory, and (on demand) shells
 * out to the read-only `homer_langgraph state` subcommand for per-node status,
 * whose reading is cached briefly so concurrent pollers share one subprocess.
 * Powers `GET /api/v1/processes/{id}/langgraph`. See ADR-0044.
 */
class LangGraphRunStatusService(
    private val fileOperations: FileOperations,
    private val stateRunner: GraphStateRunner,
    private val appConfig: AppConfig,
    private val continuationRepository: ContinuationRepository,
    private val processManager: ProcessManager,
    private val timeout: Duration = 15.seconds,
    private val stateCacheTtl: Duration = 4.seconds,
    private val maxCachedStates: Int = 64,
    private val maxConcurrentStateRuns: Int = 4,
    private val maxQueueWait: Duration = 5.seconds,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // The cache bounds repeated work; this bounds *simultaneous* work, which is a
    // different thing and the one that can hurt the server. Each `state` run is a
    // Python interpreter with langgraph imported (~75 MiB RSS) whose exit is awaited
    // by blocking a Dispatchers.IO thread — the same pool every SQLite transaction
    // uses. Left ungated, a burst of `?graph=true` requests (up to whatever the
    // per-caller rate limit allows, times the number of principals) would spawn one
    // interpreter each and could starve the database dispatcher along the way, so a
    // read-only status endpoint could degrade the orchestration this server exists
    // for. Callers past the limit wait for a slot, but only for [maxQueueWait] —
    // an unbounded wait just moves the pile-up from interpreters to suspended
    // callers holding connections, so a saturated endpoint sheds load instead.
    private val stateRunSlots = Semaphore(maxConcurrentStateRuns)

    // A `state` subprocess costs ~350ms, almost all of it interpreter start plus
    // the langgraph import, and the console polls every 5s per open panel. The TTL
    // sits just under that interval: a single viewer still gets a fresh reading on
    // every poll, while extra viewers of the same thread ride along for free.
    //
    // Bounded LRU, not a plain map: part of the key comes from an agent-writable
    // artifact, so key cardinality is not ours to trust. [maxCachedStates] caps
    // what a burst of distinct keys can retain (an entry holds one parsed reading,
    // Mermaid source included) no matter what the rate limiter allows, evicts in
    // O(1) so no request pays for a scan, and leaves nothing to linger once
    // traffic stops. Overflowing it only costs cache hits, never correctness.
    // Access-ordered, so eviction drops the least recently *read* thread rather
    // than the one written longest ago.
    private val stateCache: MutableMap<StateKey, CachedState> =
        Collections.synchronizedMap(
            object : LinkedHashMap<StateKey, CachedState>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<StateKey, CachedState>): Boolean = size > maxCachedStates
            },
        )

    /** The run's LangGraph identity, or null when this is not a LangGraph run. */
    fun identity(processInfo: ProcessInfo): LangGraphRunIdentity? {
        val dir = processInfo.artifactsDir ?: return null
        val file = dir.resolve(ARTIFACT_FILENAME)
        // NOFOLLOW: langgraph.json is agent-written; a symlink present here at check
        // time is rejected before fileOperations.fileExists/readText (which both
        // follow symlinks) ever see it — same pre-check LocalFileOperations.openBounded
        // uses, hitting the real FS directly (like ArtifactService's resolve-time gate)
        // so this can't be mocked away. readText has no NOFOLLOW form, so this is
        // check-then-read, not atomic: a same-UID swap between this check and the
        // read below can still win — narrower than before, not closed. Accepted: it's
        // a same-UID residual, not a trust-boundary crossing (§12), and bounded by the
        // swapped-in content having to parse as LangGraphRunIdentity JSON to go anywhere.
        // Security follow-up; §12.
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        if (!fileOperations.fileExists(file)) return null
        return runCatching { json.decodeFromString<LangGraphRunIdentity>(fileOperations.readText(file)) }.getOrNull()
    }

    /**
     * Identity plus, when [includeGraph], the topology and per-node
     * status. Null when the process has no identity artifact (route -> 404).
     */
    suspend fun status(
        processInfo: ProcessInfo,
        agent: Agent,
        includeGraph: Boolean,
    ): LangGraphRunStatus? {
        val identity = identity(processInfo) ?: return null
        val cmd =
            agent.commands.filterIsInstance<Command.LangGraph>().firstOrNull { it.module == identity.module }
                ?: agent.commands.filterIsInstance<Command.LangGraph>().firstOrNull()
        val base =
            LangGraphRunStatus(
                label = cmd?.label ?: identity.module,
                module = identity.module,
                threadId = identity.threadId,
                segment = identity.segment,
            )
        if (cmd == null) return base.copy(error = "agent '${agent.name}' no longer defines a LangGraph command")
        if (!includeGraph) return base

        // Scoped to the process's own owner: the thread id above comes from an
        // agent-writable artifact, so a forged id must not be able to address
        // another principal's checkpoint store. See ADR-0046.
        val env = agent.env + (EnvConstants.LG_STATE_DIR to appConfig.resolveLangGraphStateDir(processInfo.owner))
        // Only the checkpoint reading is cached. `subs` runs every call: it is a DB
        // read, so keeping sub progress live costs nothing worth saving.
        return when (val outcome = cachedState(StateKey(cmd.python, cmd.module, identity.threadId, env))) {
            is StateOutcome.Ok -> {
                val topology = outcome.output.topology?.takeIf { LangGraphTopologyLimits.violation(it) == null }
                base.copy(
                    topology = topology,
                    traversed = traversed(outcome.output.traversed, topology),
                    mermaid = outcome.output.mermaid,
                    nodes = outcome.output.nodes,
                    errors = outcome.output.errors,
                    subs = subs(outcome.output.nodeInterruptIds(), identity.threadId, processInfo.owner),
                    subsTruncated = outcome.output.interruptsSeenTruncated.takeIf { it.isNotEmpty() },
                )
            }

            is StateOutcome.Failed -> {
                base.copy(error = outcome.message)
            }
        }
    }

    /**
     * [runtimeTraversed] kept only where each pair is a real edge of [topology] --
     * the runtime already filters against its own topology, but that output is
     * agent-writable checkpoint data (root CLAUDE.md §12 rule 2), so Homer re-checks
     * it against the topology it is about to serve instead of trusting the runtime's
     * filtering. Null when [topology] was rejected or the runtime omitted the field.
     * See ADR-0075.
     */
    private fun traversed(
        runtimeTraversed: List<LangGraphEdgeRef>?,
        topology: LangGraphTopology?,
    ): List<LangGraphEdgeRef>? {
        if (topology == null || runtimeTraversed == null) return null
        val edges = topology.edges.mapTo(HashSet()) { it.source to it.target }
        return runtimeTraversed.filter { (it.source to it.target) in edges }.distinct()
    }

    /**
     * One `state` reading for [key], reused for [stateCacheTtl] after it was requested.
     * Failures are cached too — a timing-out or missing interpreter is the most expensive
     * outcome there is, and must not be re-attempted once per poll per viewer.
     */
    private suspend fun cachedState(key: StateKey): StateOutcome {
        fresh(key)?.let { return it }
        // `acquired` is set inside the timeout block immediately after acquire()
        // returns, with no suspension point between the two: withTimeoutOrNull can
        // report null even when the permit WAS taken (it cancels at the boundary),
        // and trusting its return value alone would leak that permit permanently.
        var acquired = false
        withTimeoutOrNull(maxQueueWait) {
            stateRunSlots.acquire()
            acquired = true
        }
        // Deliberately NOT cached: caching "busy" would serve it to everyone for the
        // whole TTL, turning a moment of load into a guaranteed outage.
        if (!acquired) return StateOutcome.Failed(BUSY_MESSAGE)
        return try {
            // Checked again now that we hold a slot. This is what collapses a burst:
            // while these callers queued, whoever went first published a reading, so
            // only the few in flight when the entry was cold ever spawn a subprocess
            // -- without this, every queued caller would run its own.
            fresh(key) ?: runAndCache(key)
        } finally {
            stateRunSlots.release()
        }
    }

    /** The cached reading for [key] if it is still within [stateCacheTtl], else null. */
    private fun fresh(key: StateKey): StateOutcome? {
        val ttl = stateCacheTtl.inWholeMilliseconds
        // Read and write are separate map operations on purpose: a subprocess must
        // never run under the map's monitor.
        return stateCache[key]?.takeIf { nowMillis() - it.atMillis < ttl }?.outcome
    }

    /** Spawns one `state` subprocess for [key] and caches whatever it yields. */
    private suspend fun runAndCache(key: StateKey): StateOutcome {
        val startedAt = nowMillis()
        val outcome =
            when (val result = stateRunner.run(key.python, key.module, key.threadId, key.env, timeout)) {
                is GraphStateResult.Ok -> {
                    runCatching { json.decodeFromString<GraphStateOutput>(result.json) }
                        .fold(
                            onSuccess = { StateOutcome.Ok(it) },
                            onFailure = { StateOutcome.Failed("unparseable state output: ${it.message}") },
                        )
                }

                is GraphStateResult.Err -> {
                    StateOutcome.Failed(result.message)
                }
            }
        // Stamped with the pre-run time, so the entry expires a subprocess-duration
        // early rather than late.
        stateCache[key] = CachedState(outcome, startedAt)
        return outcome
    }

    /**
     * Per-sub progress for fan-out nodes: matches each interrupt id the node has parked on —
     * pending now or already resolved — against Homer's continuations for this thread,
     * resolving each watched sub's process status via [processManager]. Continuations of every
     * status are considered, so a node keeps showing its sub runs after the join fired and the
     * graph moved on; restricting either side to what is currently pending is what used to make
     * the history disappear. A fixed number of reads however many subs there are, below the
     * 512-id chunk the batched reads split at: the thread's continuations by index, then every
     * watched sub in one batch. Only [owner]'s continuations count: [threadId] and the interrupt
     * ids come from agent-writable state, so a forged pair must not reach another principal's sub
     * runs (like the checkpoint read, ADR-0046). Null when there is nothing to enrich or
     * the lookup fails — a lookup failure must not break the status response. See ADR-0044.
     */
    private suspend fun subs(
        interrupts: Map<String, List<String>>,
        threadId: String,
        owner: String?,
    ): Map<String, List<LangGraphSubProgress>>? {
        if (interrupts.isEmpty()) return null
        return try {
            val watchedProcessByInterrupt =
                continuationRepository
                    .byThread(threadId, owner)
                    .flatMap { continuation ->
                        when (continuation.kind) {
                            ContinuationKind.JOIN -> {
                                continuation.watches.map { it.interruptId to it.watchedProcessId }
                            }

                            ContinuationKind.SINGLE -> {
                                continuation.params[EnvConstants.LG_INTERRUPT]
                                    ?.let { listOf(it to continuation.watchedProcessId) }
                                    ?: emptyList()
                            }
                        }
                    }.toMap()
            val progress =
                processManager.getSubRunProgress(interrupts.values.flatten().mapNotNull { watchedProcessByInterrupt[it] })
            buildMap {
                for ((node, ids) in interrupts) {
                    val nodeSubs =
                        ids.mapNotNull { interruptId ->
                            val processId = watchedProcessByInterrupt[interruptId] ?: return@mapNotNull null
                            val sub = progress[processId]
                            LangGraphSubProgress(
                                interruptId,
                                processId,
                                sub?.agentName,
                                sub?.status?.name,
                                commands = sub?.let(::subCommands),
                            )
                        }
                    if (nodeSubs.isNotEmpty()) put(node, nodeSubs)
                }
            }
        } catch (e: CancellationException) {
            // The request went away: stop here instead of answering nobody.
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Compact per-command progress of a sub run: finished executions + the running command. */
    private fun subCommands(sub: SubRunProgress): List<LangGraphSubCommand> =
        buildList {
            sub.executions.forEach {
                val state =
                    when {
                        it.skipped -> "skipped"

                        // errMsg is set only on CommandOutcome.Failure; a
                        // finishExitCode/continueIfFail success records its raw
                        // non-zero exit code, so resultCode alone would lie here.
                        it.errMsg == null -> "ok"

                        else -> "failed"
                    }
                add(LangGraphSubCommand(label = it.label, state = state))
            }
            // The step boundary commits the append and the current-command change in one
            // transaction, so currentCommand is never a just-finished step. A next step
            // may share its predecessor's label, and it is genuinely running.
            sub.currentCommand
                ?.let { add(LangGraphSubCommand(label = it, state = "running")) }
        }

    companion object {
        /** Written by the Python runtime before every invoke (runner.py ARTIFACT_FILENAME). */
        const val ARTIFACT_FILENAME = "langgraph.json"

        /** Reported when no run slot came free in time; the caller may simply poll again. */
        const val BUSY_MESSAGE = "graph status is busy (too many concurrent requests) — retry"
    }
}
