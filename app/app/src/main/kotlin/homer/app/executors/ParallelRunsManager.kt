package homer.app.executors

import homer.app.models.OnMaxParallelRunsStrategy
import kotlinx.coroutines.sync.Semaphore

/**
 * A place reserved in an agent's lock-wait bound. [close] frees it and is idempotent, so a
 * caller can free it on every exit path (grant, cancel) without tracking whether it already did.
 */
fun interface LockWaitTicket {
    fun close()
}

/**
 * Reserves a [LockWaitTicket] at the moment a run is about to suspend on a subject lock, and
 * throws when the agent's lock-wait bound is reached. A run waiting for a lock holds no
 * parallel-run slot (ADR-0103), so this is where the wait stays bounded (§12 rules 4–5).
 */
fun interface LockWaitGate {
    fun enter(): LockWaitTicket
}

/** The gate for a lock taken without the slot limiter, as [LockManager.waitForLockOrThrow] takes it. */
val UnboundedLockWait: LockWaitGate = LockWaitGate { LockWaitTicket {} }

interface ParallelRunsManager {
    suspend fun acquireSlot(
        agentName: String,
        maxParallelRuns: Int,
        strategy: OnMaxParallelRunsStrategy,
        maxQueuedRuns: Int? = null,
    ): Semaphore

    suspend fun releaseSlot(slot: Semaphore)

    /**
     * Reserves a place for a run that is about to wait on a subject lock, refusing the surplus
     * instead of parking it. Lock waiters share the slot wait queue — one combined cap of
     * [maxQueuedRuns] — when that queue exists and is bounded; otherwise they are capped at
     * [maxParallelRuns], which is today's worst case because each lock waiter used to hold a slot.
     */
    fun enterLockWait(
        agentName: String,
        maxParallelRuns: Int,
        strategy: OnMaxParallelRunsStrategy,
        maxQueuedRuns: Int?,
    ): LockWaitTicket

    fun onAgentsReloaded(
        updated: Set<String>,
        removed: Set<String>,
    )
}
