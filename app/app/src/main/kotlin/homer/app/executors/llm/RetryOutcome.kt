package homer.app.executors.llm

import homer.app.exceptions.MalformedLlmOutputException
import homer.app.infra.TranslatedStream
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.LlmRetry
import io.opentelemetry.api.trace.Span
import kotlin.math.min
import kotlin.random.Random

/**
 * The result of a single attempt, as consumed by [LlmHarnessExecutor]'s retry loop after it ends.
 * [Success] short-circuits the loop and is returned; [MalformedFailure] and
 * [OtherFailure] are retried, and the most recent one surfaces once attempts are
 * exhausted — a malformed payload is rethrown, a non-zero exit returns its result.
 */
internal sealed interface RetryOutcome {
    sealed interface Returned : RetryOutcome {
        val result: CommandResult

        /** `HMR_LLM_OUTCOME` for an escalate predicate (ADR-0079). */
        val outcomeName: String
            get() =
                when (this) {
                    is Success -> "success"
                    is OtherFailure -> "failed"
                    is CappedStop -> "capped"
                }
    }

    data class Success(
        override val result: CommandResult,
        val attemptIndex: Int,
        val stream: TranslatedStream,
    ) : Returned

    data class MalformedFailure(
        val exception: MalformedLlmOutputException,
    ) : RetryOutcome

    data class OtherFailure(
        override val result: CommandResult,
    ) : Returned

    /** The harness reported a terminal stop the agent opted into (Claude `maxTurnsExitCode`); not retried. */
    data class CappedStop(
        override val result: CommandResult,
        val stream: TranslatedStream,
    ) : Returned
}

/** The command's retry policy; none configured is a single attempt. */
internal fun Command.LlmCommand.retryPolicy(): LlmRetry = retry ?: LlmRetry(attempts = 1)

/** The retry loop's last outcome as its result, recording the attempts it used on [span]. */
internal fun returnedOrThrow(
    lastOutcome: RetryOutcome?,
    retry: LlmRetry,
    span: Span,
): RetryOutcome.Returned =
    when (val outcome = lastOutcome ?: error("retry loop produced no outcome; should be unreachable")) {
        is RetryOutcome.Success -> {
            span.setAttribute("retry.attempts.used", (outcome.attemptIndex + 1).toLong())
            outcome
        }

        is RetryOutcome.MalformedFailure -> {
            span.setAttribute("retry.attempts.used", retry.attempts.toLong())
            throw outcome.exception
        }

        is RetryOutcome.OtherFailure -> {
            span.setAttribute("retry.attempts.used", retry.attempts.toLong())
            outcome
        }

        is RetryOutcome.CappedStop -> {
            span.setAttribute("retry.attempts.used", 1L)
            outcome
        }
    }

/**
 * Exponential backoff plus full jitter in [0, baseDelayMs) before retry [attemptIndex]:
 * jitter de-correlates concurrent retriers so they don't reconverge on the same
 * wall-clock instant (thundering herd). maxDelayMs stays the hard ceiling. nextLong
 * requires a positive bound, so guard baseDelayMs == 0.
 */
internal fun retryDelayMs(
    retry: LlmRetry,
    attemptIndex: Int,
    random: Random,
): Long {
    val backoff = retry.baseDelayMs shl (attemptIndex - 1).coerceAtMost(BACKOFF_SHIFT_CAP)
    val jitter = if (retry.baseDelayMs > 0) random.nextLong(retry.baseDelayMs) else 0L
    return min(retry.maxDelayMs, backoff + jitter)
}

// Cap the bit-shift used in the exponential-backoff calculation so it cannot
// overflow Long when an operator configures unusually high `attempts`.
private const val BACKOFF_SHIFT_CAP = 30
