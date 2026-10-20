package homer.app.executors.llm

import homer.app.executors.ExecutionContext
import homer.app.executors.StepRecords
import homer.app.infra.LlmAccountingScan
import homer.app.infra.LlmCostResolver
import homer.app.models.LlmAccounting
import homer.app.models.LlmUsage
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.nio.file.Path

/**
 * Charges one attempt with what [pricing] resolves: `addCost` accumulates onto the process
 * record (status API), `recordCost` charges the cap windows; both take the RESOLVED figure so
 * budgets follow the operator's spend source. Inline when the lookup is skipped or not
 * configured; otherwise the attempt gets the harness figure at once and the lookup settles on
 * [settlementScope], which outlives the run. See ADR-0061, ADR-0089.
 */
internal class LlmAttemptAccounting(
    private val pricing: LlmAttemptPricing,
    private val stepRecords: StepRecords,
    private val costRecorder: CostRecorder,
    private val settlementScope: CoroutineScope,
    private val logger: Logger,
) {
    /** An attempt's accounting as handed back, and the background [settlement] that replaces it, if any. */
    data class Accounted(
        val accounting: LlmAccounting,
        val settlement: Deferred<LlmAccounting>?,
    )

    /**
     * Accounts a finished attempt. With a session lookup configured the attempt hands back the
     * harness figure at once, marked pending, and [settle] replaces it in the background so the
     * step does not wait out the resolver's poll schedule; otherwise it is priced and charged
     * inline by [account], and a cancellation during that escapes to the caller.
     */
    suspend fun finished(
        stdoutFile: Path,
        context: ExecutionContext,
        span: Span,
        startedAtMs: Long,
        endedAtMs: Long,
    ): Accounted {
        if (!pricing.looksUpSessions) {
            return Accounted(
                account(stdoutFile, context, span, startedAtMs = startedAtMs, endedAtMs = endedAtMs),
                null,
            )
        }
        val scan = pricing.scan(stdoutFile, span)
        val pending = pricing.preview(scan).also { annotate(span, scan, it) }.toAccounting()
        return Accounted(pending, settlementScope.async { settle(scan, context, pending, startedAtMs, endedAtMs) })
    }

    /**
     * Prices and charges one attempt inline. Runs for every exit code; no-op charge for 0.0.
     *
     * [allowRemoteLookup] false skips [LlmCostResolver.resolve] and charges the CLI figure
     * directly — used when the caller's coroutine was genuinely cancelled: the spend is real and
     * still gets accounted, but a cancelled run must not hold the NonCancellable block open for a
     * bounded-but-real remote HTTP call.
     *
     * [startedAtMs]/[endedAtMs] are the attempt's own wall-clock window, captured around the
     * shell call; the resolver prices a request-window-configured endpoint from the requests
     * inside it rather than the session's cumulative total. See ADR-0061.
     */
    suspend fun account(
        stdoutFile: Path,
        context: ExecutionContext,
        span: Span,
        allowRemoteLookup: Boolean = true,
        startedAtMs: Long? = null,
        endedAtMs: Long? = null,
    ): LlmAccounting {
        val priced = pricing.price(stdoutFile, span, allowRemoteLookup, startedAtMs, endedAtMs)

        // CWE-841: cancellation may escape only before the charge,
        // so the caller's re-account cannot double-charge. See ADR-0061.
        withContext(NonCancellable) {
            annotate(span, priced.scan, priced)
            charge(priced, context)
        }
        return priced.toAccounting()
    }

    /**
     * The background half of [finished]: prices [scan] through the resolver, charges it once and
     * swaps [pending] for the settled figure on the process record. A cancelled settlement (the
     * scope shutting down mid-poll) still charges the harness figure, since the spend is real;
     * any other failure leaves the attempt on its harness figure, logged.
     */
    @Suppress("SuspendFunSwallowedCancellation") // charges under NonCancellable, as the inline path does
    private suspend fun settle(
        scan: LlmAccountingScan,
        context: ExecutionContext,
        pending: LlmAccounting,
        startedAtMs: Long,
        endedAtMs: Long,
    ): LlmAccounting =
        runCatching {
            // A lookup that is cancelled (shutdown) or blows up still leaves a real spend to
            // charge: fall back to the harness figure without touching the network.
            val priced =
                runCatching { pricing.price(scan, allowRemoteLookup = true, startedAtMs, endedAtMs) }
                    .getOrElse { pricing.price(scan, allowRemoteLookup = false, startedAtMs, endedAtMs) }
            withContext(NonCancellable) {
                charge(priced, context)
                val settled = priced.toAccounting()
                if (context.processId >= 0) stepRecords.settleAccounting(context.processId, pending, settled)
                settled
            }
        }.getOrElse {
            logger.warn("cost settlement failed ({}); the attempt keeps its harness figure", it.message)
            pending
        }

    /** Marks [span] with the attempt's session, usage and what [priced] says it cost; a no-op once the span ended. */
    private fun annotate(
        span: Span,
        scan: LlmAccountingScan,
        priced: LlmAttemptPricing.PricedAttempt,
    ) {
        scan.sessionId?.let { span.setAttribute("llm.session_id", it) }
        span.setAttribute("llm.cost.source", priced.source.wire)
        priced.fallbackReason?.let { span.setAttribute("llm.cost.fallback_reason", it) }
        annotateUsage(span, scan.usage)
        if (priced.costUsd > 0.0) span.setAttribute("llm.cost_usd", priced.costUsd)
    }

    /**
     * Marks [span] with what [settled] replaced the preview with (ADR-0089). The command span has
     * ended by the time a settlement answers, so the background hand-off calls this on a live
     * `llm_cost_settlement` child span of it; a clean figure carries no fallback reason there.
     */
    fun annotateSettled(
        span: Span,
        settled: LlmAccounting,
    ) {
        settled.sessionId?.let { span.setAttribute("llm.session_id", it) }
        span.setAttribute("llm.cost.source", settled.source.wire)
        settled.fallbackReason?.let { span.setAttribute("llm.cost.fallback_reason", it) }
        annotateUsage(span, settled.usage)
        if (settled.costUsd > 0.0) span.setAttribute("llm.cost_usd", settled.costUsd)
    }

    private fun annotateUsage(
        span: Span,
        usage: LlmUsage?,
    ) {
        usage?.let {
            span.setAttribute("llm.usage.input", it.input)
            span.setAttribute("llm.usage.output", it.output)
            span.setAttribute("llm.usage.cache_read", it.cacheRead)
            span.setAttribute("llm.usage.cache_write", it.cacheWrite)
            span.setAttribute("llm.usage.reasoning", it.reasoning)
        }
    }

    /** Charges [priced] onto the process record and the agent's cap windows; nothing for 0.0 or an unowned run. */
    private suspend fun charge(
        priced: LlmAttemptPricing.PricedAttempt,
        context: ExecutionContext,
    ) {
        if (priced.costUsd <= 0.0) return
        if (context.processId >= 0) stepRecords.addCost(context.processId, priced.costUsd, priced.source)
        if (context.agentName.isNotBlank()) costRecorder.recordCost(context.agentName, priced.costUsd)
    }
}
