package homer.app.runners.queue

import homer.app.models.RunnerFilter

/**
 * What a claim-queue consumer exposes: enough to match a request and to explain
 * an expiry (`runner 'x' at concurrency 2/2`). Implemented by StaticRunnerConsumer
 * and, later, by registered external runner instances. See ADR-0066.
 */
interface ClaimConsumerInfo {
    val alias: String
    val tags: Set<String>

    /** Permit count; null = unbounded. */
    val concurrency: Int?
    val inUse: Int

    /**
     * The stable identity an affinity preference names: a static runner's alias,
     * or an external instance's alias-qualified registered name. Null = this
     * consumer is never preferred (a nameless external instance). See ADR-0099.
     */
    val affinityInstance: String? get() = null

    /** Whether this consumer is on its way out; a preference never waits for one that is. */
    val draining: Boolean get() = false

    /** Whether the filter alone matches, ignoring capacity and draining. */
    fun matchesFilter(filter: RunnerFilter): Boolean = filterMatches(alias, tags, filter)

    /** Whether this consumer can serve [filter] right now. */
    fun matches(filter: RunnerFilter): Boolean = matchesFilter(filter)
}

/** The filter test behind [ClaimConsumerInfo.matchesFilter]. */
private fun filterMatches(
    alias: String,
    tags: Set<String>,
    filter: RunnerFilter,
): Boolean =
    when (filter) {
        is RunnerFilter.Alias -> filter.alias == alias
        is RunnerFilter.Tags -> tags.containsAll(filter.tags)
    }
