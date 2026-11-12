package homer.app.executors

import homer.app.models.OnKeyCollisionStrategy

/**
 * The outcome of a lock decision: the run now holds the lock, and [displacedProcessId] names the
 * holder the collision displaced, if any. The displaced holder is killed by [LockManager.killDisplaced]
 * — not by the decision itself — so a `KILL_OLD_RUN_CURRENT` kill lands only once the new run can
 * actually run (ADR-0103).
 */
data class LockGrant(
    val displacedProcessId: Int? = null,
)

interface LockManager {
    /**
     * Decides the collision for [lockName] and grants it to [processID], waiting when the strategy
     * says so. [waitGate] reserves the place a `WAIT` run takes in the agent's lock-wait bound just
     * before it is enqueued, and throws when the bound is reached. See ADR-0103.
     */
    suspend fun acquireLock(
        lockName: String,
        onKeyCollisionStrategy: OnKeyCollisionStrategy,
        processID: Int,
        waitGate: LockWaitGate = UnboundedLockWait,
    ): LockGrant

    /** Kills the holder [grant] displaced, if any, naming [processID] as the run taking precedence. */
    suspend fun killDisplaced(
        grant: LockGrant,
        processID: Int,
    )

    /** [acquireLock] followed by the immediate kill, for callers with no slot admission of their own. */
    suspend fun waitForLockOrThrow(
        lockName: String,
        onKeyCollisionStrategy: OnKeyCollisionStrategy,
        processID: Int,
    )

    suspend fun releaseLock(
        lockName: String,
        processID: Int,
    )
}
