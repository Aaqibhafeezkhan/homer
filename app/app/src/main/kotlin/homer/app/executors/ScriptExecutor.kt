package homer.app.executors

import homer.app.infra.FileOperations
import homer.app.models.Command
import homer.app.models.CommandResult
import io.opentelemetry.api.trace.Span
import kotlin.time.Duration.Companion.seconds

/**
 * Executor for Command.Script - shell script execution
 */
class ScriptExecutor(
    private val fileOperations: FileOperations,
) : CommandExecutor<Command.Script> {
    override suspend fun execute(
        command: Command.Script,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val redactor = context.secretRedactor()
        span.setAttribute("input.value", redactor.redact(command.content))

        val result =
            context.shell.cmd(
                command = listOf(command.shell, "-s", "-"),
                stdOut = context.stdOut,
                stdErr = context.stdErr,
                stdIn = command.content,
                env = context.workspace.config.env,
                timeout = command.timeout.seconds,
            )

        span.setOutputValue(context, fileOperations, redactor)

        return result
    }
}
