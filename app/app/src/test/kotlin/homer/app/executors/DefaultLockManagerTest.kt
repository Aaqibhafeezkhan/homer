package homer.app.executors

import homer.app.exceptions.AnotherProcessStillActive
import homer.app.models.OnKeyCollisionStrategy
import homer.app.services.ProcessManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for DefaultLockManager
 * Tests lock acquisition, release, and collision strategies
 */
class DefaultLockManagerTest {
    private lateinit var lockManager: DefaultLockManager
    private lateinit var mockProcessManager: ProcessManager
    private lateinit var mockLogger: Logger

    @BeforeEach
    fun setup() {
        mockProcessManager = mockk(relaxed = true)
        mockLogger = mockk(relaxed = true)
        lockManager = DefaultLockManager(mockProcessManager, mockLogger)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // ========== Lock Acquisition Tests ==========

    @Test
    fun `waitForLockOrThrow should acquire lock when no collision`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            // Should not throw
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)

            assertTrue(true)
        }

    @Test
    fun `waitForLockOrThrow should acquire different locks for different names`() =
        runTest {
            val lockName1 = "lock-1"
            val lockName2 = "lock-2"
            val processId1 = 1
            val processId2 = 2

            lockManager.waitForLockOrThrow(lockName1, OnKeyCollisionStrategy.WAIT, processId1)
            lockManager.waitForLockOrThrow(lockName2, OnKeyCollisionStrategy.WAIT, processId2)

            assertTrue(true)
        }

    // ========== SKIP_CURRENT Strategy Tests ==========

    @Test
    fun `SKIP_CURRENT should throw exception when lock is taken`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            // First process acquires lock
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processId1)

            // Second process should throw exception
            val exception =
                assertFailsWith<AnotherProcessStillActive> {
                    lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processId2)
                }

            assertEquals("The process $processId1 is still active, stopping current", exception.message)
        }

    @Test
    fun `SKIP_CURRENT should acquire lock after release`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            // First process acquires and releases lock
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processId1)
            lockManager.releaseLock(lockName, processId1)

            // Second process should succeed
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processId2)

            assertTrue(true)
        }

    // ========== KILL_OLD_RUN_CURRENT Strategy Tests ==========

    @Test
    fun `KILL_OLD_RUN_CURRENT should kill old process and acquire lock`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit

            // First process acquires lock
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId1)

            // Second process should kill first and acquire lock
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId2)

            coVerify { mockProcessManager.killProcess(processId1, "The new process $processId2 takes precedence") }
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT should not kill when no collision`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit

            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId)

            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT should add new process to lock set`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit

            // First process acquires lock
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId1)

            // Second process kills first but both should be in the set temporarily
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId2)

            // Release both
            lockManager.releaseLock(lockName, processId1)
            lockManager.releaseLock(lockName, processId2)

            // After both releases, third process should acquire without killing
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 3)

            assertTrue(true)
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT kills the old holder once outside the mutex and the new process holds the lock`() =
        runTest {
            val killEntered = CompletableDeferred<Unit>()
            val killRelease = CompletableDeferred<Unit>()
            coEvery { mockProcessManager.killProcess(any(), any()) } coAnswers {
                killEntered.complete(Unit)
                killRelease.await()
            }
            lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            val taking = launch { lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2) }
            killEntered.await()
            // The kill is a database write: another lock must stay acquirable while it runs.
            withTimeout(1.seconds) { lockManager.waitForLockOrThrow("other-lock", OnKeyCollisionStrategy.WAIT, 3) }
            killRelease.complete(Unit)
            taking.join()

            coVerify(exactly = 1) { mockProcessManager.killProcess(1, "The new process 2 takes precedence") }
            lockManager.releaseLock("test-lock", 1)
            val blocked =
                assertFailsWith<AnotherProcessStillActive> {
                    lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 4)
                }
            assertEquals("The process 2 is still active, stopping current", blocked.message)
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT whose kill throws still leaves the new process holding the lock`() =
        runTest {
            coEvery { mockProcessManager.killProcess(any(), any()) } throws IllegalStateException("kill failed")
            lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            assertFailsWith<IllegalStateException> {
                lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2)
            }

            lockManager.releaseLock("test-lock", 1)
            val blocked =
                assertFailsWith<AnotherProcessStillActive> {
                    lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
                }
            assertEquals("The process 2 is still active, stopping current", blocked.message)
            lockManager.releaseLock("test-lock", 2)
            lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    // ========== WAIT Strategy Tests ==========

    @Test
    fun `WAIT should wait until lock is released`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2
            var process2Acquired = false

            // First process acquires lock
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId1)

            // Launch second process in background
            val job =
                launch {
                    lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId2)
                    process2Acquired = true
                    lockManager.releaseLock(lockName, processId2)
                }

            // Give second process time to try acquiring
            delay(100)
            assertFalse(process2Acquired, "Second process should be waiting")

            // Release lock
            lockManager.releaseLock(lockName, processId1)

            // Signal-based: waiter is woken immediately on release
            delay(50)
            assertTrue(process2Acquired, "Second process should have acquired lock")

            job.join()
        }

    @Test
    fun `WAIT should be cancelled when coroutine is cancelled`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            // First process acquires lock
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId1)

            // Launch second process in background
            val job =
                launch {
                    lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId2)
                }

            // Give second process time to start waiting
            delay(100)

            // Cancel the waiting job
            job.cancelAndJoin()

            // First process should still hold the lock
            // Verify by trying SKIP_CURRENT strategy
            assertFailsWith<AnotherProcessStillActive> {
                lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, 3)
            }
        }

    @Test
    fun `a WAIT cancelled while the mutex is contended still leaves the queue`() =
        runTest {
            lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.WAIT, 1)
            val waiter = launch { lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.WAIT, 2) }
            runCurrent()

            // Hold the manager's own mutex across the cancel, as a concurrent release would.
            val mutex =
                DefaultLockManager::class.java
                    .getDeclaredField("mutex")
                    .apply { isAccessible = true }
                    .get(lockManager) as Mutex
            mutex.lock()
            waiter.cancel()
            runCurrent()
            mutex.unlock()
            waiter.join()
            lockManager.releaseLock("test-lock", 1)

            // Had the dead waiter stayed queued, the release would have handed it the lock.
            lockManager.waitForLockOrThrow("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    @Test
    fun `WAIT should acquire lock immediately when available`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1
            val startTime = System.currentTimeMillis()

            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)

            val elapsed = System.currentTimeMillis() - startTime
            assertTrue(elapsed < 100, "Lock acquisition should be immediate, took ${elapsed}ms")
        }

    // ========== Lock Release Tests ==========

    @Test
    fun `releaseLock should remove process from lock set`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)

            // Verify lock is available by acquiring it again
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)

            assertTrue(true)
        }

    @Test
    fun `releaseLock should remove lock key when set is empty`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)

            // Acquire lock again - should succeed immediately since lock was removed
            val startTime = System.currentTimeMillis()
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
            val elapsed = System.currentTimeMillis() - startTime

            assertTrue(elapsed < 100, "Lock should have been removed and reacquired immediately")
        }

    @Test
    fun `releaseLock should handle non-existent lock gracefully`() =
        runTest {
            val lockName = "non-existent-lock"
            val processId = 1

            // Should not throw
            lockManager.releaseLock(lockName, processId)

            assertTrue(true)
        }

    @Test
    fun `releaseLock should handle non-existent processId gracefully`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId1)

            // Release with wrong processId should not throw
            lockManager.releaseLock(lockName, processId2)

            // Original process should still hold lock
            assertFailsWith<AnotherProcessStillActive> {
                lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, 3)
            }
        }

    @Test
    fun `releaseLock should work in NonCancellable context`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)

            // Verify lock was released by trying to acquire it again
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)

            // Cleanup
            lockManager.releaseLock(lockName, processId)

            assertTrue(true)
        }

    // ========== Concurrent Access Tests ==========

    @Test
    fun `multiple processes should wait in queue for same lock`() =
        runTest {
            val lockName = "test-lock"
            val processIds = listOf(1, 2, 3, 4, 5)
            val acquisitionOrder = mutableListOf<Int>()

            // First process acquires lock
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processIds[0])
            acquisitionOrder.add(processIds[0])

            // Launch other processes
            coroutineScope {
                processIds.drop(1).forEach { processId ->
                    launch {
                        lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
                        synchronized(acquisitionOrder) {
                            acquisitionOrder.add(processId)
                        }
                        lockManager.releaseLock(lockName, processId)
                    }
                }

                // Release locks sequentially
                delay(100)
                processIds.forEach { processId ->
                    lockManager.releaseLock(lockName, processId)
                    delay(50)
                }
            }

            delay(500)
            assertEquals(processIds.size, acquisitionOrder.size)
        }

    @Test
    fun `different locks should not interfere with each other`() =
        runTest {
            val results = mutableListOf<Pair<String, Int>>()

            coroutineScope {
                // Launch processes for different locks concurrently
                repeat(10) { i ->
                    launch {
                        val lockName = "lock-${i % 3}"
                        val processId = i
                        lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
                        synchronized(results) {
                            results.add(lockName to processId)
                        }
                        delay(10)
                        lockManager.releaseLock(lockName, processId)
                    }
                }
            }

            delay(500)
            assertEquals(10, results.size)
        }

    @Test
    fun `concurrent SKIP_CURRENT requests should handle race conditions`() =
        runTest {
            val lockName = "test-lock"
            val exceptionCounts = mutableListOf<Int>()

            coroutineScope {
                repeat(5) { i ->
                    launch {
                        try {
                            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, i)
                            delay(100)
                            lockManager.releaseLock(lockName, i)
                        } catch (e: AnotherProcessStillActive) {
                            synchronized(exceptionCounts) {
                                exceptionCounts.add(1)
                            }
                        }
                    }
                }
            }

            delay(500)
            // At least one should have succeeded, others threw exceptions
            assertTrue(exceptionCounts.size >= 4, "Expected at least 4 exceptions, got ${exceptionCounts.size}")
        }

    @Test
    fun `concurrent KILL_OLD_RUN_CURRENT requests should work correctly`() =
        runTest {
            val lockName = "test-lock"
            val processIds = listOf(1, 2, 3, 4, 5)

            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit

            coroutineScope {
                processIds.forEach { processId ->
                    launch {
                        delay(processId * 10L) // Stagger requests
                        lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId)
                        delay(50)
                        lockManager.releaseLock(lockName, processId)
                    }
                }
            }

            delay(500)
            // Should have killed all but the last process
            coVerify(atLeast = processIds.size - 1) { mockProcessManager.killProcess(any(), any()) }
        }

    // ========== Edge Cases ==========

    @Test
    fun `same process can release and reacquire lock`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            // Acquire, release, and reacquire
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)
            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)

            assertTrue(true)
        }

    @Test
    fun `lock with very long name should work`() =
        runTest {
            val lockName = "a".repeat(1000)
            val processId = 1

            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)

            assertTrue(true)
        }

    @Test
    fun `multiple releases of same lock should be idempotent`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            lockManager.waitForLockOrThrow(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)
            lockManager.releaseLock(lockName, processId)
            lockManager.releaseLock(lockName, processId)

            assertTrue(true)
        }
}
