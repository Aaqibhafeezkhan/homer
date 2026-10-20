package homer.app.executors

import homer.app.models.OnMaxParallelRunsStrategy
import kotlinx.coroutines.sync.Semaphore

interface ParallelRunsManager {
    suspend fun acquireSlot(
        agentName: String,
        maxParallelRuns: Int,
        strategy: OnMaxParallelRunsStrategy,
        maxQueuedRuns: Int? = null,
    ): Semaphore

    suspend fun releaseSlot(slot: Semaphore)

    fun onAgentsReloaded(
        updated: Set<String>,
        removed: Set<String>,
    )
}
