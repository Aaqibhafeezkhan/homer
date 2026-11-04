package homer.app.runners.queue

import homer.app.models.RunnerFilter
import kotlin.time.Duration

/**
 * Both renderings of a claim's expiry reason: with aliases for the log, run-visible without.
 * A preference that held the whole wait is the cause on top of the states: matching runners
 * shown free passed the run over on purpose (ADR-0099). The run-visible rendering names no
 * instance (ADR-0077).
 */
internal fun claimExpiryReasons(
    consumers: List<ClaimConsumerInfo>,
    filter: RunnerFilter,
    affinity: ClaimAffinity?,
    timeout: Duration,
): Pair<String, String> {
    // The preference is the cause only while it actually blocked someone: if the preferred
    // instance has left (unregistered or draining) or never matched the run's filter, it ordered
    // no consumer at expiry, and the run-visible cause must not send the debug of a failed run
    // after a preference that was not in the way. See ADR-0099.
    val preferenceHeld =
        affinity != null &&
            affinity.heldWholeWait(timeout.inWholeMilliseconds) &&
            consumers.any {
                it.affinityInstance == affinity.preferredInstance && !it.draining && it.matchesFilter(filter)
            }
    if (preferenceHeld) {
        val cause = "its preferred runner did not take it"
        return "$cause; ${matchingStates(consumers, filter)}" to cause
    }
    return expiryReason(consumers, filter, withAliases = true) to expiryReason(consumers, filter, withAliases = false)
}

/** The per-runner states of every consumer matching [filter], oldest claim first. */
private fun matchingStates(
    consumers: List<ClaimConsumerInfo>,
    filter: RunnerFilter,
): String =
    consumers
        .filter { it.matches(filter) }
        .joinToString(", ") { "runner '${it.alias}' at concurrency ${it.inUse}/${it.concurrency ?: "unbounded"}" }

private fun expiryReason(
    consumers: List<ClaimConsumerInfo>,
    filter: RunnerFilter,
    withAliases: Boolean,
): String {
    val matching = consumers.filter { it.matches(filter) }
    if (matching.isEmpty()) {
        return when (filter) {
            is RunnerFilter.Alias -> {
                if (withAliases) {
                    "runner '${filter.alias}' has no registered instances"
                } else {
                    "no registered runner matches it"
                }
            }

            is RunnerFilter.Tags -> {
                if (withAliases) {
                    "no registered runner matches ${filter.describe()}"
                } else {
                    "no registered runner matches those tags"
                }
            }
        }
    }
    // The alias-free rendering names the observation, not the runners: it is stored on the
    // run and served to every caller who can see it, and a runner alias is admin-only there
    // (ADR-0077). Both renderings read the same snapshot, so they cannot disagree.
    if (!withAliases) return "every matching runner was at its concurrency limit"
    return matchingStates(consumers, filter)
}
