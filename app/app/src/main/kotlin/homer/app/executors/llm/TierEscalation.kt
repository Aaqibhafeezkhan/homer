package homer.app.executors.llm

import homer.app.EnvConstants
import homer.app.exceptions.CommandExecutionException
import homer.app.executors.ExecutionContext
import homer.app.infra.SecretRedactor
import homer.app.infra.WhenEvaluator
import homer.app.models.Command
import io.opentelemetry.api.trace.Span
import org.slf4j.Logger

/**
 * [LlmHarnessExecutor]'s escalation tiers (ADR-0079): each tier's argv, and whether the next
 * tier runs, judged by its `when` against the outcome of the tier that just ran. Logs to the
 * executor's [logger].
 */
internal class TierEscalation<C>(
    private val harness: LlmHarness<C>,
    private val logger: Logger,
) where C : Command, C : Command.LlmCommand {
    /** Tier [tier]'s argv — tier 0 reuses [argv0] — recorded, redacted, on [span]. */
    fun tierArgv(
        tier: Int,
        current: C,
        argv0: List<String>,
        env: Map<String, String>,
        span: Span,
        redactor: SecretRedactor,
    ): List<String> {
        val argv = if (tier == 0) argv0 else harness.argv(current, env)
        if (tier == 0) {
            span.setAttribute("input.argv", redactor.redact(argv.toString()))
        } else {
            span.setAttribute("llm.escalation.tier", tier.toLong())
            span.setAttribute("input.argv.tier_$tier", redactor.redact(argv.toString()))
        }
        return argv
    }

    /**
     * Whether tier [tier] + 1 runs: false when there is none, else its `when` evaluated against the
     * step's env plus the outcome of the tier that just ran. An eval error fails the step, as a
     * command's own `when` does. See ADR-0079.
     */
    suspend fun escalates(
        command: C,
        tier: Int,
        outcome: String,
        exitCode: Int,
        context: ExecutionContext,
        span: Span,
        redactor: SecretRedactor,
    ): Boolean {
        val next = command.escalate.getOrNull(tier) ?: return false
        val env =
            context.workspace.config.env +
                mapOf(EnvConstants.LLM_OUTCOME to outcome, EnvConstants.LLM_EXIT_CODE to exitCode.toString())
        val result =
            WhenEvaluator(context.shell, context.workspace.config.artifacts)
                // The stem names the tier whose `when` this is (tier + 1, the one being judged), not the
                // tier that just ran, so `escalate_<cmdIndex>_1.out` holds tier 1's expressions. See ADR-0079.
                .evaluate(next.whenClause, env, context.cmdIndex, artifactStem = "escalate_${context.cmdIndex}_${tier + 1}")
        return when (result) {
            WhenEvaluator.Result.Run -> {
                logger.info("{} tier {} ended {} (exit {}); escalating to tier {}", harness.id, tier, outcome, exitCode, tier + 1)
                span.addEvent("llm.escalation.tier_${tier + 1}")
                true
            }

            is WhenEvaluator.Result.Skip -> {
                false
            }

            is WhenEvaluator.Result.EvalError -> {
                throw CommandExecutionException(
                    message =
                        "escalate[$tier].when evaluation failed for '${redactor.redact(result.expression)}': " +
                            redactor.redact(result.stderrSummary),
                    exitCode = result.exitCode,
                    command = redactor.redact(command.toString()),
                )
            }
        }
    }
}
