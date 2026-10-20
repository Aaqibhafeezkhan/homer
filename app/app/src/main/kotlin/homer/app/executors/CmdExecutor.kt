package homer.app.executors

import homer.app.infra.FileOperations
import homer.app.models.Command
import homer.app.models.CommandResult
import io.opentelemetry.api.trace.Span
import kotlin.time.Duration.Companion.seconds

/**
 * Executor for Command.Cmd - simple command execution
 */
class CmdExecutor(
    private val fileOperations: FileOperations,
) : CommandExecutor<Command.Cmd> {
    override suspend fun execute(
        command: Command.Cmd,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val redactor = context.secretRedactor()
        span.setAttribute("input.value", redactor.redact(command.cmd.joinToString(" ")))

        val result =
            context.shell.cmd(
                command = command.cmd,
                stdOut = context.stdOut,
                stdErr = context.stdErr,
                stdIn = null,
                env = context.workspace.config.env,
                timeout = command.timeout.seconds,
            )

        span.setOutputValue(context, fileOperations, redactor)

        return result
    }
}
