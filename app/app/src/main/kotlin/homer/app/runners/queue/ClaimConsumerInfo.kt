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

    fun matches(filter: RunnerFilter): Boolean =
        when (filter) {
            is RunnerFilter.Alias -> filter.alias == alias
            is RunnerFilter.Tags -> tags.containsAll(filter.tags)
        }
}
