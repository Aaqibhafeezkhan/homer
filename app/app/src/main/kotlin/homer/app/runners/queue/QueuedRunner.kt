package homer.app.runners.queue

import homer.app.models.RunnerFilter
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import kotlin.time.Duration

/**
 * The Runner every run sees: openSession() waits in the claim queue for a
 * consumer matching [filter]. For an alias filter the backend is known up front
 * and the env-shaping facets are its own; for a tags filter they are derived
 * from every runner that could match (remote if any is, secret names unioned,
 * no runnerEnv: the serving runner's env arrives via RunnerSession.env). See ADR-0066.
 */
class QueuedRunner(
    private val filter: RunnerFilter,
    private val queue: RunnerClaimQueue,
    candidates: List<Runner>,
    val claimTimeout: Duration,
) : Runner {
    init {
        if (filter is RunnerFilter.Alias) require(candidates.size == 1) { "alias filter needs exactly one candidate" }
    }

    override val alias: String =
        when (filter) {
            is RunnerFilter.Alias -> filter.alias
            is RunnerFilter.Tags -> "tags:" + filter.tags.sorted().joinToString(",")
        }

    override val isRemote: Boolean = candidates.any { it.isRemote }

    override val runnerEnv: Map<String, String> =
        if (filter is RunnerFilter.Alias) candidates.single().runnerEnv else emptyMap()

    override val sysEnvBackedEnvNames: Set<String> = candidates.flatMapTo(mutableSetOf()) { it.sysEnvBackedEnvNames }

    override suspend fun openSession(params: RunnerSessionParams): RunnerSession = queue.awaitClaim(params, filter, claimTimeout)
}
