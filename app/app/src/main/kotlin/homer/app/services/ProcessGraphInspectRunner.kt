package homer.app.services

import homer.app.infra.GraphSubprocessOutcome
import homer.app.infra.MAX_GRAPH_STDERR_BYTES
import homer.app.infra.MAX_GRAPH_STDOUT_BYTES
import homer.app.infra.readHead
import homer.app.infra.runBoundedGraphSubprocess
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/** Reported when the command's runtime predates `inspect --json`; names no interpreter path. */
internal const val OLD_RUNTIME_MESSAGE = "this command's homer_langgraph does not support inspect --json; upgrade the runtime"

/** argparse exits 2 on a usage error in any locale and echoes the untranslated `--json` token. */
internal fun isUnsupportedJsonFlag(
    exitCode: Int,
    stderr: String,
): Boolean = exitCode == 2 && "--json" in stderr

/**
 * Host-local implementation: spawns `<python> -m homer_langgraph inspect
 * --module <module> --json` on the server host and captures stdout (topology +
 * Mermaid JSON) / stderr (error). Unlike LocalShellExecutor this intentionally
 * inherits the server process environment so `python` and the
 * `homer_langgraph` package resolve, then overlays the agent's declared `env`
 * (e.g. `PYTHONPATH`) so the agent's own module resolves exactly as in a real
 * run. This is server-host introspection, not sandboxed agent execution. See
 * ADR-0027.
 */
class ProcessGraphInspectRunner : GraphInspectRunner {
    override suspend fun run(
        python: String,
        module: String,
        env: Map<String, String>,
        timeout: Duration,
    ): GraphInspectResult =
        try {
            // CWE-770: the shared helper caps output at write time.
            // See the ADR-0073 amendment.
            outcomeResult(
                runBoundedGraphSubprocess(
                    python = python,
                    args = listOf("inspect", "--module", module, "--json"),
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
            GraphInspectResult.Err(e.message ?: "failed to launch python")
        }

    private fun outcomeResult(
        outcome: GraphSubprocessOutcome,
        timeout: Duration,
    ): GraphInspectResult =
        when (outcome) {
            is GraphSubprocessOutcome.Exited -> exitedResult(outcome)
            GraphSubprocessOutcome.TimedOut -> GraphInspectResult.Err("inspect timed out after $timeout")
            GraphSubprocessOutcome.StdoutExceeds -> GraphInspectResult.Err("inspect output exceeds $MAX_GRAPH_STDOUT_BYTES bytes")
            is GraphSubprocessOutcome.LaunchFailed -> GraphInspectResult.Err(outcome.message)
        }

    private fun exitedResult(outcome: GraphSubprocessOutcome.Exited): GraphInspectResult {
        if (outcome.exitCode == 0) return GraphInspectResult.Ok(outcome.stdout.trim())
        val stderr = outcome.stderr.trim()
        if (isUnsupportedJsonFlag(outcome.exitCode, stderr)) {
            return GraphInspectResult.Err(OLD_RUNTIME_MESSAGE)
        }
        val message = stderr.ifBlank { readHead(outcome.stdout.byteInputStream(), MAX_GRAPH_STDERR_BYTES).trim() }
        return GraphInspectResult.Err(message.ifBlank { "inspect exited ${outcome.exitCode}" })
    }
}
