package homer.agentdebug.stubs

import homer.app.executors.CommandExecutor
import homer.app.executors.ExecutionContext
import homer.app.models.Command
import homer.app.models.CommandResult
import io.opentelemetry.api.trace.Span
import kotlin.io.path.writeText

/**
 * No-op executor for a command type the server-less debugger skips: it leaves the command's
 * stdout and stderr empty and reports success. DebugAgentRunner skips these commands before
 * execution; a subclass is the fallback that satisfies the executor-factory contract.
 */
abstract class SkipExecutor<C : Command> : CommandExecutor<C> {
    final override suspend fun execute(
        command: C,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        context.stdOut.writeText("")
        context.stdErr.writeText("")
        return CommandResult(exitCode = 0, stdOut = context.stdOut, stdErr = context.stdErr)
    }
}
