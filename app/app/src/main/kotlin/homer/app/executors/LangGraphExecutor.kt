package homer.app.executors

import homer.app.EnvConstants
import homer.app.infra.FileOperations
import homer.app.models.AppConfig
import homer.app.models.Command
import homer.app.models.CommandResult
import io.opentelemetry.api.trace.Span
import kotlin.time.Duration.Companion.seconds

/**
 * Executor for Command.LangGraph - thin launcher for the Python `homer_langgraph`
 * runtime. Homer launches `python -m homer_langgraph run --module <module>`, passes
 * the workspace env through (so resume params arriving in env reach the runtime),
 * and injects the durable state dir resolved from server config. The Python runtime
 * owns all side effects and checkpointing. See ADR-0024.
 */
class LangGraphExecutor(
    private val fileOperations: FileOperations,
    private val appConfig: AppConfig,
) : CommandExecutor<Command.LangGraph> {
    override suspend fun execute(
        command: Command.LangGraph,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val argv =
            listOf(
                command.python,
                "-m",
                "homer_langgraph",
                "run",
                "--module",
                command.module,
                "--input-env",
                command.inputEnv,
            )
        val redactor = context.secretRedactor()
        span.setAttribute("input.value", redactor.redact(argv.joinToString(" ")))

        // The state dir is authoritative: the runtime must open its checkpointer
        // at the server's resolved directory, so it overrides any workspace value.
        // Owner-scoped — see ADR-0046; must match what LangGraphRunStatusService
        // resolves for this process, or status and resume open an empty store.
        val env =
            context.workspace.config.env +
                (EnvConstants.LG_STATE_DIR to appConfig.resolveLangGraphStateDir(context.owner))

        val result =
            context.shell.cmd(
                command = argv,
                stdOut = context.stdOut,
                stdErr = context.stdErr,
                stdIn = null,
                env = env,
                timeout = command.timeout.seconds,
            )

        span.setOutputValue(context, fileOperations, redactor)

        return result
    }
}
