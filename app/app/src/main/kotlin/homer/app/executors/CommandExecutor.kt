package homer.app.executors

import homer.app.models.Command
import homer.app.models.CommandResult
import io.opentelemetry.api.trace.Span

/**
 * Interface for command executors
 * Each command type has its own executor implementation
 */
interface CommandExecutor<T : Command> {
    /**
     * Execute a command
     * @param command Command to execute
     * @param context Execution context with workspace and environment
     * @param span OpenTelemetry span for tracing
     * @return Command result with exit code
     */
    suspend fun execute(
        command: T,
        context: ExecutionContext,
        span: Span,
    ): CommandResult
}
