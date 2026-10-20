package homer.app.executors.llm

import homer.app.executors.addBoolOpt
import homer.app.executors.addRepeatableOpt
import homer.app.executors.addStringOpt
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.CodexCmdOptions

/**
 * Builds the Codex CLI argv from a [CodexCmdOptions] block. Shared by [CodexHarness]
 * and the agentdebug dry-run printer, so neither ever drifts from what actually runs.
 *
 * Template resolution (`{{VAR}}`, see AGENT.md) is applied here against [env].
 */
class CodexArgvBuilder(
    private val textCompiler: TextCompiler = DefaultTextCompiler(),
) {
    fun build(
        cmd: String,
        opts: CodexCmdOptions,
        env: Map<String, String>,
    ): List<String> =
        buildList {
            add(cmd)
            add("exec")

            addStringOpt("--model", opts.model)
            addStringOpt("--profile", opts.profile)

            // Sandboxing. `--sandbox` and the bypass flag are mutually
            // exclusive on the CLI; sandbox wins when both are configured
            // (same precedence rule as ClaudeCmdOptions.permissionMode).
            if (opts.sandbox != null) {
                add("--sandbox")
                add(opts.sandbox)
            } else {
                addBoolOpt("--dangerously-bypass-approvals-and-sandbox", opts.dangerouslyBypassApprovalsAndSandbox)
            }

            addStringOpt("--cd", opts.cd)
            addStringOpt("--add-dir", opts.addDir)
            addBoolOpt("--skip-git-repo-check", opts.skipGitRepoCheck)
            addBoolOpt("--ephemeral", opts.ephemeral)
            addBoolOpt("--ignore-user-config", opts.ignoreUserConfig)
            addBoolOpt("--ignore-rules", opts.ignoreRules)
            addStringOpt("--output-schema", opts.outputSchema)
            addStringOpt("--output-last-message", opts.outputLastMessage)
            addRepeatableOpt("--config", opts.config)

            opts.options?.let { addAll(it) }
            add("--json")
            add("--color")
            add("never")
            // Explicit stdin sentinel: without it a piped stdin would be
            // appended as a <stdin> block to an (absent) positional prompt.
            add("-")
        }.map {
            // Resolve {{VAR}} placeholders in option values (see AGENT.md).
            textCompiler.replaceArguments(it, env)
        }
}
