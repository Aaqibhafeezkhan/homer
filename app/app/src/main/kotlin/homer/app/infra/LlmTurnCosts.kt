package homer.app.infra

import homer.app.models.LlmEvent
import homer.app.models.TurnStatus

/**
 * Splits an attempt's charge across its turn ends for `LlmTelemetryService`, so the
 * generations it emits sum to what the ledger charged. See ADR-0074.
 */
internal object LlmTurnCosts {
    /**
     * Each turn end's priced share, in stream order: [turnCosts] of [resolvedCostUsd],
     * moved off the turn ends that emit no priced generation by [onSuccessfulTurnEnds].
     */
    fun allocate(
        turnEnds: List<LlmEvent.TurnEnd>,
        resolvedCostUsd: Double?,
    ): List<Double?> = onSuccessfulTurnEnds(turnEnds, turnCosts(turnEnds, resolvedCostUsd))

    /**
     * Each turn end's share of the attempt's cost, in stream order: its increment of the
     * cumulative cost, scaled to [resolvedCostUsd] when there is one. When the increments cannot
     * be read or their total is zero, the last turn end carries the whole cost and the earlier
     * ones an explicit zero.
     * A negative reported cost counts as none. See ADR-0074.
     */
    private fun turnCosts(
        turnEnds: List<LlmEvent.TurnEnd>,
        resolvedCostUsd: Double?,
    ): List<Double?> {
        if (turnEnds.size <= 1) return turnEnds.map { resolvedCostUsd ?: it.costUsd?.takeIf { c -> c >= 0.0 } }
        val reported = turnEnds.mapNotNull { it.costUsd }
        val total = resolvedCostUsd ?: reported.filter { it >= 0.0 }.maxOrNull() ?: return turnEnds.map { null }
        val cumulative =
            reported.takeIf { it.size == turnEnds.size && it.first() >= 0.0 && it.zipWithNext().all { (a, b) -> b >= a } }
        if (cumulative == null || cumulative.last() <= 0.0) {
            return turnEnds.indices.map { if (it == turnEnds.lastIndex) total else 0.0 }
        }
        val scale = total / cumulative.last()
        val scaled = cumulative.map { it * scale }
        // The last turn end closes on `total` itself rather than on its scaled value, which may round off it.
        return scaled.indices.map { i ->
            val upTo = if (i == scaled.lastIndex) total else scaled[i]
            maxOf(0.0, upTo - (scaled.getOrNull(i - 1) ?: 0.0))
        }
    }

    /**
     * Moves the share of a turn end that emits no priced generation — a failed or stopped one —
     * onto the next successful turn end, or the last one before it, so the generations still sum
     * to the attempt's charge (CWE-840). With no successful turn end,
     * the last turn end carries the whole charge. See ADR-0074.
     */
    private fun onSuccessfulTurnEnds(
        turnEnds: List<LlmEvent.TurnEnd>,
        shares: List<Double?>,
    ): List<Double?> {
        val out = shares.toMutableList()
        val carried = carryToNextSuccess(turnEnds, shares, out)
        placeTrailingCarry(turnEnds, shares, out, carried)
        return out
    }

    /** Moves each failed turn end's share in [out] onto the next successful one; returns what no later success took. */
    private fun carryToNextSuccess(
        turnEnds: List<LlmEvent.TurnEnd>,
        shares: List<Double?>,
        out: MutableList<Double?>,
    ): Double {
        var carried = 0.0
        for (i in turnEnds.indices) {
            if (turnEnds[i].status == TurnStatus.Success) {
                if (carried > 0.0) out[i] = (shares[i] ?: 0.0) + carried
                carried = 0.0
            } else {
                carried += shares[i] ?: 0.0
                out[i] = null
            }
        }
        return carried
    }

    /** Puts the [carried] trailing share on the last successful turn end, or everything on the last one when none succeeded. */
    private fun placeTrailingCarry(
        turnEnds: List<LlmEvent.TurnEnd>,
        shares: List<Double?>,
        out: MutableList<Double?>,
        carried: Double,
    ) {
        val lastSuccess = turnEnds.indexOfLast { it.status == TurnStatus.Success }
        if (lastSuccess >= 0) {
            if (carried > 0.0) out[lastSuccess] = (out[lastSuccess] ?: 0.0) + carried
        } else if (turnEnds.isNotEmpty()) {
            out[turnEnds.lastIndex] = shares.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
        }
    }
}
