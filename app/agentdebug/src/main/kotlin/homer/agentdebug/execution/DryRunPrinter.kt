package homer.agentdebug.execution

import homer.app.executors.ClaudeArgvBuilder
import homer.app.executors.llm.ClaudeHarness
import homer.app.executors.llm.CodexArgvBuilder
import homer.app.executors.llm.CodexHarness
import homer.app.infra.SecretRedactor
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmPrompt

object DryRunPrinter {
    private val claudeArgvBuilder = ClaudeArgvBuilder()
    private val codexArgvBuilder = CodexArgvBuilder()
    private val claudeHarness = ClaudeHarness()
    private val codexHarness = CodexHarness()

    fun print(
        command: Command,
        env: Map<String, String> = emptyMap(),
        redactor: SecretRedactor,
    ) {
        // CWE-532: the argv builders resolve {{VAR}}, so a secret value lands in the printed argv.
        val out = { line: String -> println(redactor.redact(line)) }
        command.whenClause?.let { clause ->
            val op = clause.operator.name.lowercase()
            out("[WHEN $op] ${clause.expressions.joinToString(" ; ")}")
        }
        when (command) {
            is Command.Cmd -> {
                out("[DRY ] cmd: ${command.cmd.joinToString(" ")}")
            }

            is Command.Script -> {
                out("[DRY ] shell: ${command.shell}")
                out("[DRY ] stdin:")
                command.content.trimEnd().lines().forEach { line ->
                    out("[DRY ]   $line")
                }
            }

            is Command.Claude -> {
                // Reuse the executor's real argv builder so dry-run never drifts
                // from what actually runs (same flags, same {{VAR}} resolution).
                out("[DRY ] cmd: ${claudeArgvBuilder.build(command.cmd, command.options, env).joinToString(" ")}")
                printPrompt(command.prompt, out)
                printTiers(command.escalate, command, claudeHarness, env, out)
            }

            is Command.Codex -> {
                // Reuse the harness's real argv builder so dry-run never drifts
                // from what actually runs (same flags, same {{VAR}} resolution).
                out("[DRY ] cmd: ${codexArgvBuilder.build(command.cmd, command.options, env).joinToString(" ")}")
                printPrompt(command.prompt, out)
                printTiers(command.escalate, command, codexHarness, env, out)
            }

            is Command.RunAgent -> {
                out("[DRY ] agentName: ${command.agentName}  [SKIPPED]")
            }

            is Command.LangGraph -> {
                out("[DRY ] langgraph module: ${command.module}  (python: ${command.python})  [SKIPPED]")
            }

            is Command.RunnerCmd -> {
                out("[DRY ] runner: ${command.runner}  [SKIPPED]")
            }
        }
    }

    /** The LLM step's stdin: its inline prompt, or which Langfuse prompt it fetches. */
    private fun printPrompt(
        promptSource: LlmPrompt,
        out: (String) -> Unit,
    ) {
        val promptText =
            when (promptSource) {
                is LlmPrompt.Prompt -> promptSource.prompt
                is LlmPrompt.Langfuse -> "<langfuse: ${promptSource.promptName}>"
            }
        out("[DRY ] stdin:")
        promptText.trimEnd().lines().forEach { line ->
            out("[DRY ]   $line")
        }
    }

    /**
     * One `[ESCALATE n]` line per `escalate` tier, using the harness's own
     * [homer.app.executors.llm.LlmHarness.escalated] to build the tier's argv —
     * the same call the executor makes — so a dry run shows every model that
     * will actually run. Predicate expressions are redacted like the step's
     * `when`. See ADR-0079.
     */
    private fun <C> printTiers(
        tiers: List<LlmEscalation>,
        command: C,
        harness: homer.app.executors.llm.LlmHarness<C>,
        env: Map<String, String>,
        out: (String) -> Unit,
    ) where C : Command, C : Command.LlmCommand {
        tiers.forEachIndexed { index, tier ->
            val tiered = harness.escalated(command, tier)
            val args = harness.argv(tiered, env)
            val op =
                tier.whenClause.operator.name
                    .lowercase()
            val exprs = tier.whenClause.expressions.joinToString(" ; ")
            out("[ESCALATE ${index + 1}] model=${tier.model} cmd=${args.joinToString(" ")} when=$op($exprs)")
        }
    }
}
