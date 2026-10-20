package homer.app.executors

import homer.app.models.OnKeyCollisionStrategy

interface LockManager {
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
