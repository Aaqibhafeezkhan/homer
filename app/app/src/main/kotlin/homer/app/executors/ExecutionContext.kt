package homer.app.executors

import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.FileOperations
import homer.app.infra.SecretRedactor
import homer.app.infra.ShellExecutor
import homer.app.infra.Workspace
import io.opentelemetry.api.trace.Span
import java.nio.file.Path

/**
 * Context for command execution. Carries the session-scoped shell so
 * the runner's backend (local process, k8s pod, ...) is transparent
 * to command executors.
 */
data class ExecutionContext(
    val workspace: Workspace,
    val config: ExecutionConfig,
    val shell: ShellExecutor,
    val stdOut: Path,
    val stdErr: Path,
    // -1 = no associated process (e.g. agentdebug CLI runs); cost reporting becomes a no-op.
    val processId: Int = -1,
    // Empty = no associated agent (e.g. agentdebug); cost-cap checks fall through.
    val agentName: String = "",
    // -1 = no associated command index (e.g. agentdebug ad-hoc runs).
    val cmdIndex: Int = -1,
    // The owning process's owner (grants-based authorization, ADR-0038), so a
    // Command.RunAgent child run can inherit it. Null = no owner (e.g. agentdebug).
    val owner: String? = null,
    // Renders per-attempt stdOut/stdErr paths for LlmHarnessExecutor's retry loop (see
    // Agent.artifactNameTemplate). The default keeps ExecutionContext constructible
    // without every caller building a resolver explicitly — callers that only ever
    // read stdOut/stdErr above (most executors/tests) never touch this field, and
    // production callers (AgentExecutor, DebugAgentRunner) always pass one built
    // from the same artifactsDir/cmdIndex used for stdOut/stdErr.
    val artifactNames: ArtifactNameResolver =
        ArtifactNameResolver(
            template = EnvConstants.DEFAULT_ARTIFACT_NAME_TEMPLATE,
            textCompiler = DefaultTextCompiler(),
            artifactsDir = workspace.config.artifacts,
            cmdIndex = cmdIndex,
            envProvider = { workspace.config.env },
        ),
)

/**
 * Redactor for this run's secret values, built from the workspace env — declared
 * `secret: true` inputs, `@SYSENV:`-sourced globals, and the callback token.
 * Every executor that writes command text or output into a telemetry span must
 * pass values through this first, or a secret echoed into a command / its stdout
 * egresses to Langfuse in cleartext (only the Claude command did this originally).
 */
fun ExecutionContext.secretRedactor(): SecretRedactor = SecretRedactor.from(workspace.config.env, workspace.config.secretEnvNames)

/**
 * Records the command's stdout on this span as `output.value`, passed through [redactor],
 * or a pointer to the file when it reaches `maxOutputSizeForTracing`.
 */
fun Span.setOutputValue(
    context: ExecutionContext,
    fileOperations: FileOperations,
    redactor: SecretRedactor,
) {
    if (fileOperations.fileSize(context.stdOut) < context.config.maxOutputSizeForTracing) {
        setAttribute("output.value", redactor.redact(fileOperations.readText(context.stdOut)))
    } else {
        setAttribute("output.value", "Output too large to display, file path: ${context.stdOut}")
    }
}
