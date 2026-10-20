package homer.app.services

import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.ValidationException
import homer.app.executors.llm.CostRecorder
import homer.app.models.CostLimits
import homer.app.services.persistence.CostRepository
import org.slf4j.LoggerFactory
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Rolling-window tracker for Claude spend, backed by the durable
 * `cost_entries` SQLite ledger — windows, monthly, and all-time statistics
 * survive restarts. See ADR-0035.
 *
 * Two cap windows are enforced:
 *  - **Global**: sum of all recorded costs in the trailing [GLOBAL_WINDOW_SECONDS] (60 s)
 *    window, capped by [CostLimits.globalUsdPerMin].
 *  - **Per-agent**: an individual agent's costs in the trailing
 *    [AGENT_WINDOW_SECONDS] (24 h) window, capped by [CostLimits.perAgentUsdPerDay].
 *
 * The intended call pattern is fail-fast: [checkCaps] BEFORE invoking Claude
 * (refuses overage), [recordCost] AFTER a successful run (charges the windows).
 */
@OptIn(ExperimentalTime::class)
class CostMeter(
    private val repository: CostRepository,
    private val limits: CostLimits = CostLimits(),
    private val clock: Clock = Clock.System,
) : CostRecorder {
    private data class Overshoot(
        val capName: String,
        val cap: Double,
        val current: Double,
    )

    private val overshootTotal = AtomicLong(0)

    @Volatile
    private var lastOvershootLogAtMs = 0L

    /**
     * Running count of [recordCost] calls that pushed a window past its cap.
     * Because [checkCaps] reserves no budget (see ADR-0007), this is expected
     * to be non-zero under concurrency; it exists so operators can see how
     * often — and by how much — the soft cap is actually exceeded. Counts
     * this process's lifetime only (observability, not durable state).
     */
    val overshootCount: Long
        get() = overshootTotal.get()

    /**
     * Throws [CostCapExceededException] if EITHER the global or the agent's per-day
     * window already exceeds its cap. This is called pre-flight: a passing check
     * does NOT reserve budget — concurrent callers may all pass the check and then
     * collectively push the window over the cap. Acceptable for a soft throttle.
     */
    suspend fun checkCaps(agentName: String) {
        val now = clock.now().epochSeconds
        limits.globalUsdPerMin?.let { cap ->
            val current = repository.sumGlobalSince(now - GLOBAL_WINDOW_SECONDS)
            if (current >= cap) {
                throw CostCapExceededException("globalUsdPerMin", cap, current)
            }
        }
        limits.perAgentUsdPerDay[agentName]?.let { cap ->
            val current = repository.sumAgentSince(agentName, now - AGENT_WINDOW_SECONDS)
            if (current >= cap) {
                throw CostCapExceededException("perAgentUsdPerDay[$agentName]", cap, current)
            }
        }
    }

    /**
     * Records a successful Claude run's cost into the ledger. Zero or negative
     * deltas are no-ops to avoid corrupting the statistics with rounding
     * artifacts from the upstream JSON.
     */
    override suspend fun recordCost(
        agentName: String,
        costUsd: Double,
    ) {
        if (costUsd <= 0.0) return
        val now = clock.now().epochSeconds
        repository.insert(agentName, now, costUsd)
        // Detect a window that has crept past its cap. checkCaps is
        // pre-flight and reserves nothing, so concurrent callers (or a
        // single over-budget run) can push a window strictly over the cap.
        var overshoot: Overshoot? = null
        limits.perAgentUsdPerDay[agentName]?.let { cap ->
            val sum = repository.sumAgentSince(agentName, now - AGENT_WINDOW_SECONDS)
            if (sum > cap) overshoot = Overshoot("perAgentUsdPerDay[$agentName]", cap, sum)
        }
        // Global is the system-wide signal; prefer it when both trip.
        limits.globalUsdPerMin?.let { cap ->
            val sum = repository.sumGlobalSince(now - GLOBAL_WINDOW_SECONDS)
            if (sum > cap) overshoot = Overshoot("globalUsdPerMin", cap, sum)
        }
        overshoot?.let { noteOvershoot(it) }
    }

    private fun noteOvershoot(overshoot: Overshoot) {
        val total = overshootTotal.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastOvershootLogAtMs >= OVERSHOOT_LOG_WINDOW_MS) {
            lastOvershootLogAtMs = now
            val pctOver = ((overshoot.current - overshoot.cap) / overshoot.cap) * 100.0
            log.warn(
                "CostMeter soft cap exceeded: {} window is {} vs cap {} ({}% over, {} overshoot(s) total). " +
                    "checkCaps is pre-flight and reserves no budget, so runs can push a window past its cap; " +
                    "see ADR-0007.",
                overshoot.capName,
                "%.4f".format(Locale.ROOT, overshoot.current),
                "%.4f".format(Locale.ROOT, overshoot.cap),
                "%.1f".format(Locale.ROOT, pctOver),
                total,
            )
        }
    }

    /** Snapshots the current windows for the cost API. */
    suspend fun snapshot(): Snapshot {
        val now = clock.now().epochSeconds
        return Snapshot(
            globalUsdLast60s = repository.sumGlobalSince(now - GLOBAL_WINDOW_SECONDS),
            globalCapUsdPerMin = limits.globalUsdPerMin,
            perAgentUsdLast24h = repository.sumPerAgentSince(now - AGENT_WINDOW_SECONDS),
            perAgentCapUsdPerDay = limits.perAgentUsdPerDay,
            snapshotAtSec = now,
        )
    }

    /**
     * Per-agent spend for one UTC month. [yearMonth] is "YYYY-MM"; null
     * resolves to the current UTC month. Malformed input throws
     * [ValidationException] (mapped to 400 at the HTTP boundary).
     */
    suspend fun monthly(yearMonth: String?): MonthlyCosts {
        val ym = parseOrCurrentMonth(yearMonth)
        val start = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val end =
            ym
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond()
        val perAgent = repository.sumPerAgentBetween(start, end)
        return MonthlyCosts(
            month = ym.toString(),
            perAgentUsd = perAgent,
            totalUsd = perAgent.values.sum(),
            availableMonths = repository.availableMonths(),
        )
    }

    /** All-time per-agent spend over the full ledger. */
    suspend fun totals(): TotalCosts {
        val perAgent = repository.totalPerAgent()
        return TotalCosts(perAgentUsd = perAgent, totalUsd = perAgent.values.sum())
    }

    private fun parseOrCurrentMonth(yearMonth: String?): YearMonth {
        if (yearMonth == null) {
            return YearMonth.from(
                java.time.Instant
                    .ofEpochSecond(clock.now().epochSeconds)
                    .atZone(ZoneOffset.UTC),
            )
        }
        if (!MONTH_FORMAT.matches(yearMonth)) {
            throw ValidationException("expected YYYY-MM", field = "month")
        }
        return try {
            YearMonth.parse(yearMonth)
        } catch (_: java.time.format.DateTimeParseException) {
            throw ValidationException("expected YYYY-MM", field = "month")
        }
    }

    data class Snapshot(
        val globalUsdLast60s: Double,
        val globalCapUsdPerMin: Double?,
        val perAgentUsdLast24h: Map<String, Double>,
        val perAgentCapUsdPerDay: Map<String, Double>,
        val snapshotAtSec: Long,
    )

    data class MonthlyCosts(
        val month: String,
        val perAgentUsd: Map<String, Double>,
        val totalUsd: Double,
        val availableMonths: List<String>,
    )

    data class TotalCosts(
        val perAgentUsd: Map<String, Double>,
        val totalUsd: Double,
    )

    companion object {
        const val GLOBAL_WINDOW_SECONDS: Long = 60
        const val AGENT_WINDOW_SECONDS: Long = 24 * 60 * 60
        private const val OVERSHOOT_LOG_WINDOW_MS = 60_000L
        private val MONTH_FORMAT = Regex("""^\d{4}-\d{2}$""")
        private val log = LoggerFactory.getLogger(CostMeter::class.java)
    }
}
