package homer.app.executors

import homer.app.exceptions.AnotherProcessStillActive
import homer.app.models.OnKeyCollisionStrategy
import homer.app.models.OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT
import homer.app.models.OnKeyCollisionStrategy.SKIP_CURRENT
import homer.app.models.OnKeyCollisionStrategy.WAIT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger

class DefaultLockManager(
    private val processManager: RunKiller,
    private val logger: Logger,
) : LockManager {
    private data class LockEntry(
        val holders: MutableSet<Int> = mutableSetOf(),
        val waitQueue: ArrayDeque<Pair<Int, CompletableDeferred<Unit>>> = ArrayDeque(),
    )

    private val locks = mutableMapOf<String, LockEntry>()

    private val mutex = Mutex()

    @Suppress("SuspendFunSwallowedCancellation") // rethrown after a NonCancellable dequeue
    override suspend fun waitForLockOrThrow(
        lockName: String,
        onKeyCollisionStrategy: OnKeyCollisionStrategy,
        processID: Int,
    ) {
        val deferred = CompletableDeferred<Unit>()

        val victim: Int? =
            mutex.withLock {
                val entry = locks[lockName]

                if (entry == null || entry.holders.isEmpty()) {
                    locks.getOrPut(lockName) { LockEntry() }.holders.add(processID)
                    return
                }

                val activeProcessID = entry.holders.first()
                when (onKeyCollisionStrategy) {
                    SKIP_CURRENT -> {
                        throw AnotherProcessStillActive(activeProcessID)
                    }

                    KILL_OLD_RUN_CURRENT -> {
                        entry.holders.add(processID)
                        activeProcessID
                    }

                    WAIT -> {
                        entry.waitQueue.addLast(processID to deferred)
                        null
                    }
                }
            }
        // killProcess writes to the database, so it runs after the mutex is released.
        if (victim != null) {
            processManager.killProcess(victim, "The new process $processID takes precedence")
            return
        }

        try {
            deferred.await()
        } catch (e: CancellationException) {
            // NonCancellable: a cancelled withLock throws when the mutex is contended, which
            // would leave this dead waiter queued for releaseLock to hand the lock to.
            withContext(NonCancellable) {
                mutex.withLock {
                    locks[lockName]?.waitQueue?.removeAll { it.first == processID }
                }
            }
            throw e
        }
    }

    override suspend fun releaseLock(
        lockName: String,
        processID: Int,
    ): Unit =
        withContext(NonCancellable) {
            mutex.withLock {
                val entry = locks[lockName]
                if (entry == null) {
                    logger.warn("releaseLock called for unknown lock '{}' by process {}", lockName, processID)
                    return@withLock
                }
                val removed = entry.holders.remove(processID)
                if (!removed) {
                    logger.warn(
                        "releaseLock called by process {} which is not a holder of lock '{}'",
                        processID,
                        lockName,
                    )
                }

                if (entry.holders.isNotEmpty()) return@withLock

                val next = entry.waitQueue.removeFirstOrNull()
                if (next != null) {
                    entry.holders.add(next.first)
                    next.second.complete(Unit)
                } else {
                    locks.remove(lockName)
                }
            }
        }
}
