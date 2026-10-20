package homer.app.exceptions

import kotlinx.coroutines.CancellationException

/**
 * Raised by `ShellExecutor` implementations when a command's stdout
 * or stderr file exceeds the configured cap
 * (`server.maxOutputBytes`). Extends `CancellationException` so the
 * existing cancel/timeout propagation paths in callers
 * (`CmdExecutor`/`ScriptExecutor`/`LlmHarnessExecutor` → `AgentExecutor`)
 * unwind the same way as a TTL kill. `AgentExecutor` distinguishes
 * this case by type to set `ExecutionInfo.outputTruncated = true`.
 * Audit P1 #17.
 */
class OutputCapExceededException(
    message: String,
) : CancellationException(message)
