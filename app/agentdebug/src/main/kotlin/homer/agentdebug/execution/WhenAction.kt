package homer.agentdebug.execution

import homer.app.infra.SecretRedactor
import homer.app.infra.WhenEvaluator

/**
 * Maps a [WhenEvaluator.Result] to the action that [DebugAgentRunner]'s
 * loop should take, plus the human-readable line to print before taking
 * it. Pulled out of the runner so the message formatting and the
 * Run/Skip/Abort decision are unit-testable without spinning up the
 * whole agent execution stack.
 */
internal sealed class WhenAction {
    data object Run : WhenAction()

    data class Skip(
        val message: String,
    ) : WhenAction()

    data class Abort(
        val message: String,
    ) : WhenAction()
}

internal fun presentWhenResult(
    result: WhenEvaluator.Result,
    redactor: SecretRedactor = SecretRedactor.NONE,
): WhenAction =
    when (result) {
        is WhenEvaluator.Result.Run -> {
            WhenAction.Run
        }

        is WhenEvaluator.Result.Skip -> {
            WhenAction.Skip("[SKIP when] ${redactor.redact(result.reason)}")
        }

        is WhenEvaluator.Result.EvalError -> {
            WhenAction.Abort(
                "[ERROR] when-predicate evaluation failed for '${redactor.redact(result.expression)}': " +
                    redactor.redact(result.stderrSummary),
            )
        }
    }
