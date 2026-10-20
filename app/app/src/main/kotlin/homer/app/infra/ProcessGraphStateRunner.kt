package homer.app.infra

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/** Runs the `homer_langgraph state` subprocess for one LangGraph run. */
fun interface GraphStateRunner {
    suspend fun run(
        python: String,
        module: String,
        threadId: String,
        env: Map<String, String>,
        timeout: Duration,
    ): GraphStateResult
}

sealed class GraphStateResult {
    data class Ok(
        val json: String,
    ) : GraphStateResult()

    data class Err(
        val message: String,
    ) : GraphStateResult()
}

/**
 * Host-local implementation: spawns `<python> -m homer_langgraph state --module
 * <module> --thread <threadId>` on the server host and captures stdout (JSON) /
 * stderr (error). Like `ProcessGraphInspectRunner`, this inherits the server
 * process environment so `python` and the `homer_langgraph` package resolve,
 * then overlays the agent's declared `env` (e.g. `PYTHONPATH`,
 * `HOMER_LG_STATE_DIR`) so the agent's own module and checkpoint store resolve
 * exactly as in a real run. This is server-host introspection, not sandboxed
 * agent execution. See ADR-0044.
 */
class ProcessGraphStateRunner : GraphStateRunner {
    override suspend fun run(
        python: String,
        module: String,
        threadId: String,
        env: Map<String, String>,
        timeout: Duration,
    ): GraphStateResult =
        try {
            // CWE-770: the shared helper caps output at write time.
            // See the ADR-0073 amendment.
            outcomeResult(
                runBoundedGraphSubprocess(
                    python = python,
                    args = listOf("state", "--module", module, "--thread", threadId),
                    env = env,
                    timeout = timeout,
                ),
                timeout,
            )
        } catch (e: CancellationException) {
            // The suspend call sits inside this try, so a cancelled caller (client
            // disconnect, shutdown, the kill-vs-CREATED race) must not be turned into
            // a cached per-command failure — let it unwind.
            throw e
        } catch (e: Exception) {
            GraphStateResult.Err(e.message ?: "failed to launch python")
        }

    private fun outcomeResult(
        outcome: GraphSubprocessOutcome,
        timeout: Duration,
    ): GraphStateResult =
        when (outcome) {
            is GraphSubprocessOutcome.Exited -> exitedResult(outcome)
            GraphSubprocessOutcome.TimedOut -> GraphStateResult.Err("state timed out after $timeout")
            GraphSubprocessOutcome.StdoutExceeds -> GraphStateResult.Err("state output exceeds $MAX_GRAPH_STDOUT_BYTES bytes")
            is GraphSubprocessOutcome.LaunchFailed -> GraphStateResult.Err(outcome.message)
        }

    private fun exitedResult(outcome: GraphSubprocessOutcome.Exited): GraphStateResult {
        if (outcome.exitCode == 0) return GraphStateResult.Ok(outcome.stdout.trim())
        val stderr = outcome.stderr.trim()
        val message = stderr.ifBlank { readHead(outcome.stdout.byteInputStream(), MAX_GRAPH_STDERR_BYTES).trim() }
        return GraphStateResult.Err(message.ifBlank { "state exited ${outcome.exitCode}" })
    }
}
