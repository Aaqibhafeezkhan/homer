package homer.app.executors.llm

import homer.app.infra.LlmAccountingScan
import homer.app.infra.LlmCostResolver
import homer.app.infra.scanLlmAccounting
import homer.app.models.CostSource
import homer.app.models.FALLBACK_REASON_NO_RESULT_EVENT
import homer.app.models.FALLBACK_REASON_SESSION_FIGURE_PENDING
import homer.app.models.LlmAccounting
import homer.app.models.ResolvedCost
import io.opentelemetry.api.trace.Span
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.io.path.bufferedReader

/**
 * What one attempt cost, before [LlmAttemptAccounting] charges it: a lenient stdout scan
 * (`scanLlmAccounting`) priced by [llmCostResolver], or the harness's own figure when the
 * remote lookup is skipped. Logs to the executor's [logger]. See ADR-0061, ADR-0070.
 */
internal class LlmAttemptPricing(
    private val harness: LlmHarness<*>,
    private val llmCostResolver: LlmCostResolver,
    private val logger: Logger,
) {
    /** One priced attempt: its [scan], the resolved charge, where the figure came from and why it fell back. */
    data class PricedAttempt(
        val scan: LlmAccountingScan,
        val costUsd: Double,
        val source: CostSource,
        val fallbackReason: String?,
    ) {
        fun toAccounting() = LlmAccounting(scan.sessionId, costUsd, source, fallbackReason, usage = scan.usage)
    }

    /** Whether a configured resolver would look the attempt up; when it would, the lookup can settle in the background. */
    val looksUpSessions: Boolean get() = llmCostResolver.configured

    /** Prices the attempt whose stdout is [stdoutFile]; the parameters are [LlmAttemptAccounting.account]'s. */
    suspend fun price(
        stdoutFile: Path,
        span: Span,
        allowRemoteLookup: Boolean,
        startedAtMs: Long?,
        endedAtMs: Long?,
    ): PricedAttempt = price(scan(stdoutFile, span), allowRemoteLookup, startedAtMs, endedAtMs)

    /** The lenient accounting scan of [stdoutFile]; read inline, because attempts may share and truncate the file. */
    fun scan(
        stdoutFile: Path,
        span: Span,
    ): LlmAccountingScan {
        val scan =
            runCatching { stdoutFile.bufferedReader().useLines { scanLlmAccounting(it, harness::translate) } }
                .getOrElse { LlmAccountingScan(sessionId = null, cliCostUsd = null, resultSeen = false, modelCalls = 0, usage = null) }
        if (harness.reportsCost && scan.resultSeen && scan.cliCostUsd == null) {
            // Same observability hook the strict path had for a missing/unparseable field —
            // gated on resultSeen: a missing result event (kill, crash, timeout) is a normal
            // accounting case with its own "no result event" reason below, not schema drift.
            logger.warn("{} turn end has no parseable cost; this may indicate a CLI schema change", harness.id)
            span.setAttribute("llm.cost.parse_failed", true)
        }
        return scan
    }

    /** Prices a [scan] through the resolver, or from the harness figure alone when [allowRemoteLookup] is false. */
    suspend fun price(
        scan: LlmAccountingScan,
        allowRemoteLookup: Boolean,
        startedAtMs: Long?,
        endedAtMs: Long?,
    ): PricedAttempt {
        val resolved = resolveAttemptCost(scan, allowRemoteLookup, startedAtMs, endedAtMs)
        // A harness that reports no cost priced nothing unless the session endpoint answered.
        val unpriced = !harness.reportsCost && resolved.source == CostSource.CLI
        val source = if (unpriced) CostSource.NONE else resolved.source
        return PricedAttempt(scan, resolved.costUsd, source, fallbackReason(scan, resolved, unpriced, allowRemoteLookup))
    }

    /** The harness figure of a [scan] whose session lookup is still running, marked as pending (ADR-0089). */
    fun preview(scan: LlmAccountingScan): PricedAttempt =
        PricedAttempt(
            scan,
            scan.cliCostUsd ?: 0.0,
            if (harness.reportsCost) CostSource.CLI else CostSource.NONE,
            FALLBACK_REASON_SESSION_FIGURE_PENDING,
        )

    /** The attempt's cost: from [LlmCostResolver], or the CLI figure alone when [allowRemoteLookup] is false. */
    private suspend fun resolveAttemptCost(
        scan: LlmAccountingScan,
        allowRemoteLookup: Boolean,
        startedAtMs: Long?,
        endedAtMs: Long?,
    ): ResolvedCost {
        val cliCostUsd = scan.cliCostUsd ?: 0.0
        if (!allowRemoteLookup) {
            // Mirrors the resolver's own "no reason when unconfigured" rule: only mark a
            // fallback reason when there was a configured rung to skip.
            return ResolvedCost(cliCostUsd, CostSource.CLI, if (llmCostResolver.configured) "run cancelled before lookup" else null)
        }
        // 107AaI5G / CWE-400: bound what reaches the poll loop; only this consumer
        // is capped, the scan figure itself is untouched.
        val pollingCalls = scan.modelCalls.coerceAtMost(MAX_EXPECTED_CALLS_FOR_POLLING).takeIf { harness.countsModelCalls }
        return llmCostResolver.resolve(scan.sessionId, cliCostUsd, pollingCalls, startedAtMs, endedAtMs)
    }

    /** The resolver's own fallback reason, else the one this attempt's scan implies (none for an [unpriced] attempt). */
    private fun fallbackReason(
        scan: LlmAccountingScan,
        resolved: ResolvedCost,
        unpriced: Boolean,
        allowRemoteLookup: Boolean,
    ): String? =
        resolved.fallbackReason
            ?: when {
                unpriced -> {
                    null
                }

                // Cap can let the resolver return Complete on a partial ingestion; mark it. See ADR-0061.
                allowRemoteLookup && harness.countsModelCalls && scan.modelCalls > MAX_EXPECTED_CALLS_FOR_POLLING &&
                    resolved.source == CostSource.SESSION -> {
                    "incomplete: ${scan.modelCalls} session calls, poll count capped at $MAX_EXPECTED_CALLS_FOR_POLLING"
                }

                !scan.resultSeen && resolved.source == CostSource.CLI -> {
                    FALLBACK_REASON_NO_RESULT_EVENT
                }

                else -> {
                    null
                }
            }

    private companion object {
        // CWE-400: agent-stdout must not drive the poll schedule. See ADR-0047 §12 rule 4, ADR-0061.
        const val MAX_EXPECTED_CALLS_FOR_POLLING = 1_000
    }
}

/** An attempt nothing priced passes no cost rather than a zero, which reads as a free run (ADR-0074). */
internal fun telemetryCostUsd(accounting: LlmAccounting?): Double? = accounting?.takeIf { it.source != CostSource.NONE }?.costUsd
