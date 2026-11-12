package homer.app.executors

import homer.app.exceptions.MaxLockWaitersExceededException
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.models.OnMaxParallelRunsStrategy
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for DefaultParallelRunsManager
 * Tests semaphore-based concurrency control for agent parallel runs
 */
class DefaultParallelRunsManagerTest {
    private lateinit var manager: DefaultParallelRunsManager

    @BeforeEach
    fun setup() {
        manager = DefaultParallelRunsManager()
    }

    // ========== REJECT Strategy Tests ==========

    @Test
    fun `acquireSlot REJECT should succeed when under limit`() =
        runTest {
            // Should not throw with 2 slots available and only 1 acquired
            manager.acquireSlot("agent", maxParallelRuns = 2, strategy = OnMaxParallelRunsStrategy.REJECT)

            assertTrue(true)
        }

    @Test
    fun `acquireSlot REJECT should throw MaxParallelRunsExceededException at limit`() =
        runTest {
            val agentName = "heavy-agent"
            val maxRuns = 2

            // Fill all slots
            manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.REJECT)
            manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.REJECT)

            // Third acquisition should fail immediately
            val exception =
                assertFailsWith<MaxParallelRunsExceededException> {
                    manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.REJECT)
                }

            assertEquals(
                "Agent '$agentName' has reached the maximum of $maxRuns parallel runs",
                exception.message,
            )
        }

    @Test
    fun `acquireSlot REJECT should allow acquisition after releaseSlot`() =
        runTest {
            val agentName = "agent"
            val maxRuns = 1

            val slot = manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.REJECT)

            // Should be full now - verify
            assertFailsWith<MaxParallelRunsExceededException> {
                manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.REJECT)
            }

            // Release and re-acquire should succeed
            manager.releaseSlot(slot)
            manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.REJECT)

            assertTrue(true)
        }

    // ========== WAIT Strategy Tests ==========

    @Test
    fun `acquireSlot WAIT should succeed immediately when under limit`() =
        runTest {
            val startTime = System.currentTimeMillis()

            manager.acquireSlot("agent", maxParallelRuns = 5, strategy = OnMaxParallelRunsStrategy.WAIT)

            val elapsed = System.currentTimeMillis() - startTime
            assertTrue(elapsed < 200, "Slot acquisition should be immediate when slots available, took ${elapsed}ms")
        }

    @Test
    fun `acquireSlot WAIT should suspend until slot is released`() =
        runTest {
            val agentName = "throttled-agent"
            val maxRuns = 1
            var secondAcquired = false

            // First acquirer fills all slots
            val slot1 = manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.WAIT)

            // Second acquirer should suspend
            val job =
                launch {
                    val slot2 = manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.WAIT)
                    secondAcquired = true
                    manager.releaseSlot(slot2)
                }

            // Give the job time to start but it should be waiting
            delay(100)
            assertEquals(false, secondAcquired, "Second acquirer should be suspended")

            // Release the first slot — second should wake up
            manager.releaseSlot(slot1)

            delay(200)
            assertEquals(true, secondAcquired, "Second acquirer should have acquired slot after release")

            job.join()
        }

    // ========== WAIT queue bound (maxQueuedRuns) Tests ==========

    @Test
    fun `acquireSlot WAIT with null maxQueuedRuns keeps the queue unbounded`() =
        runTest {
            val agent = "unbounded"
            val held = manager.acquireSlot(agent, maxParallelRuns = 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
            var acquired = 0
            val jobs =
                (1..5).map {
                    launch {
                        val s = manager.acquireSlot(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
                        acquired++
                        manager.releaseSlot(s)
                    }
                }
            delay(100)
            assertEquals(0, acquired, "all five surplus runs should queue, none rejected")

            manager.releaseSlot(held) // drain the queue
            delay(200)
            jobs.forEach { it.join() }
            assertEquals(5, acquired, "every queued run should eventually acquire")
        }

    @Test
    fun `acquireSlot WAIT rejects a run once the wait queue is full`() =
        runTest {
            val agent = "bounded"
            // 1 concurrent slot, at most 1 run allowed to queue.
            val held = manager.acquireSlot(agent, maxParallelRuns = 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 1)

            // One waiter fills the queue (suspends on the semaphore).
            val waiter =
                launch {
                    manager.acquireSlot(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 1)
                }
            delay(100) // let the waiter reach the suspension point

            // The next run would be a second waiter — over the bound — so it fails fast.
            val ex =
                assertFailsWith<MaxQueuedRunsExceededException> {
                    manager.acquireSlot(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 1)
                }
            assertEquals("Agent '$agent' has reached the maximum of 1 queued runs", ex.message)

            waiter.cancelAndJoin()
            manager.releaseSlot(held)
        }

    @Test
    fun `acquireSlot WAIT with maxQueuedRuns 0 rejects when no slot is free but allows a free slot`() =
        runTest {
            val agent = "zero-queue"
            val held = manager.acquireSlot(agent, maxParallelRuns = 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 0)

            // No free permit and zero queue allowed → reject immediately (never wait).
            assertFailsWith<MaxQueuedRunsExceededException> {
                manager.acquireSlot(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 0)
            }

            // Once a permit is free, acquisition succeeds without queuing.
            manager.releaseSlot(held)
            manager.acquireSlot(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 0)
        }

    @Test
    fun `acquireSlot WAIT should be cancelled when coroutine is cancelled`() =
        runTest {
            val agentName = "agent"
            val maxRuns = 1

            // Fill slot
            manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.WAIT)

            // Launch a waiting coroutine
            val job =
                launch {
                    manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.WAIT)
                }

            delay(100)
            job.cancelAndJoin()

            // The slot is still held by the first acquirer
            // A REJECT attempt should fail (slot is still taken)
            assertFailsWith<MaxParallelRunsExceededException> {
                manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.REJECT)
            }
        }

    // ========== Lock-wait bound (enterLockWait) Tests ==========

    @Test
    fun `enterLockWait shares the slot queue's bound when maxQueuedRuns is set`() =
        runTest {
            val agent = "combined"
            // One slot waiter fills half of a queue bounded at 2.
            val held = manager.acquireSlot(agent, maxParallelRuns = 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 2)
            val slotWaiter =
                launch {
                    manager.acquireSlot(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 2)
                }
            runCurrent()

            // One lock waiter fits (1 slot waiter + 1 lock waiter = 2), and it is the same cap.
            val ticket = manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 2)
            val ex =
                assertFailsWith<MaxQueuedRunsExceededException> {
                    manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 2)
                }
            assertEquals("Agent '$agent' has reached the maximum of 2 queued runs", ex.message)

            // Freeing the lock waiter frees the place, not the counter.
            ticket.close()
            manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = 2).close()

            slotWaiter.cancelAndJoin()
            manager.releaseSlot(held)
        }

    @Test
    fun `enterLockWait falls back to maxParallelRuns when the slot queue is unbounded`() =
        runTest {
            val agent = "unbounded-slot-queue"
            val ticket = manager.enterLockWait(agent, maxParallelRuns = 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)

            val ex =
                assertFailsWith<MaxLockWaitersExceededException> {
                    manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
                }
            assertEquals("Agent '$agent' has reached the maximum of 1 runs waiting for a lock", ex.message)

            ticket.close()
            manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null).close()
        }

    @Test
    fun `enterLockWait falls back to maxParallelRuns under REJECT`() =
        runTest {
            val agent = "rejecting-slots"
            // maxQueuedRuns is set but has no effect under REJECT, so it must not cap lock waiters.
            val ticket = manager.enterLockWait(agent, maxParallelRuns = 1, OnMaxParallelRunsStrategy.REJECT, maxQueuedRuns = 5)

            assertFailsWith<MaxLockWaitersExceededException> {
                manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.REJECT, maxQueuedRuns = 5)
            }

            ticket.close()
            manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.REJECT, maxQueuedRuns = 5).close()
        }

    @Test
    fun `enterLockWait keys the fallback bound per agent, so two agents sharing one lock key each get their own cap`() =
        runTest {
            // Two agents declare the same lock key; the fallback counter is keyed on the agent
            // name, so each parks one waiter against the key without consuming the other's place.
            val ticketA = manager.enterLockWait("a", maxParallelRuns = 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
            val ticketB = manager.enterLockWait("b", maxParallelRuns = 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)

            // A second waiter of either agent is over that agent's cap…
            assertFailsWith<MaxLockWaitersExceededException> {
                manager.enterLockWait("a", 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
            }
            val ex =
                assertFailsWith<MaxLockWaitersExceededException> {
                    manager.enterLockWait("b", 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
                }
            assertEquals("Agent 'b' has reached the maximum of 1 runs waiting for a lock", ex.message)

            // …and closing one agent's ticket frees only that agent's place.
            ticketA.close()
            manager.enterLockWait("a", 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null).close()
            assertFailsWith<MaxLockWaitersExceededException> {
                manager.enterLockWait("b", 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
            }

            ticketB.close()
        }

    @Test
    fun `closing a lock-wait ticket twice frees one place, and a reload does not misdirect it`() =
        runTest {
            val agent = "idempotent-close"
            val ticket = manager.enterLockWait(agent, maxParallelRuns = 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
            ticket.close()
            ticket.close()

            // Exactly one place was freed: the cap of 1 is reachable again, no more.
            manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null).close()

            // A reload drops the agent's counters; a ticket captured before it must still free
            // its own counter, not the fresh one a later waiter incremented.
            val held = manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
            manager.onAgentsReloaded(updated = setOf(agent), removed = emptySet())
            held.close()

            val waiter = manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
            assertFailsWith<MaxLockWaitersExceededException> {
                manager.enterLockWait(agent, 1, OnMaxParallelRunsStrategy.WAIT, maxQueuedRuns = null)
            }
            waiter.close()
        }

    // ========== Multiple Agents Tests ==========

    @Test
    fun `different agent names should have independent semaphores`() =
        runTest {
            val maxRuns = 1

            // Fill slot for agent A
            val slotA = manager.acquireSlot("agentA", maxRuns, OnMaxParallelRunsStrategy.REJECT)

            // Agent B should still have its own available slot
            manager.acquireSlot("agentB", maxRuns, OnMaxParallelRunsStrategy.REJECT)

            // agent A is full, agent B is full — both should reject further
            assertFailsWith<MaxParallelRunsExceededException> {
                manager.acquireSlot("agentA", maxRuns, OnMaxParallelRunsStrategy.REJECT)
            }

            assertFailsWith<MaxParallelRunsExceededException> {
                manager.acquireSlot("agentB", maxRuns, OnMaxParallelRunsStrategy.REJECT)
            }

            // Releasing agent A should not affect agent B
            manager.releaseSlot(slotA)
            manager.acquireSlot("agentA", maxRuns, OnMaxParallelRunsStrategy.REJECT)

            assertFailsWith<MaxParallelRunsExceededException> {
                manager.acquireSlot("agentB", maxRuns, OnMaxParallelRunsStrategy.REJECT)
            }
        }

    // ========== Concurrency Tests ==========

    @Test
    fun `concurrent WAIT acquisitions should queue up to maxParallelRuns simultaneous`() =
        runTest {
            val agentName = "limited-agent"
            val maxRuns = 3
            val activeCount = AtomicInteger(0)
            val maxObservedConcurrent = AtomicInteger(0)

            val jobs =
                (1..6).map {
                    launch {
                        val slot = manager.acquireSlot(agentName, maxRuns, OnMaxParallelRunsStrategy.WAIT)
                        val current = activeCount.incrementAndGet()
                        maxObservedConcurrent.getAndUpdate { max -> if (current > max) current else max }
                        delay(150)
                        activeCount.decrementAndGet()
                        manager.releaseSlot(slot)
                    }
                }

            jobs.forEach { it.join() }

            assertTrue(
                maxObservedConcurrent.get() <= maxRuns,
                "Should never exceed maxParallelRuns=$maxRuns, observed max=${maxObservedConcurrent.get()}",
            )
        }

    // ========== Hot Reload Tests ==========

    @Test
    fun `onAgentsReloaded should invalidate semaphore so new maxParallelRuns takes effect`() =
        runTest {
            val agentName = "agent"

            // Establish semaphore at limit=1
            val slot = manager.acquireSlot(agentName, maxParallelRuns = 1, OnMaxParallelRunsStrategy.REJECT)
            assertFailsWith<MaxParallelRunsExceededException> {
                manager.acquireSlot(agentName, maxParallelRuns = 1, OnMaxParallelRunsStrategy.REJECT)
            }
            manager.releaseSlot(slot)

            // Simulate reload: agent updated with maxParallelRuns=2
            manager.onAgentsReloaded(updated = setOf(agentName), removed = emptySet())

            // Should now allow 2 concurrent slots
            manager.acquireSlot(agentName, maxParallelRuns = 2, OnMaxParallelRunsStrategy.REJECT)
            manager.acquireSlot(agentName, maxParallelRuns = 2, OnMaxParallelRunsStrategy.REJECT)
            assertFailsWith<MaxParallelRunsExceededException> {
                manager.acquireSlot(agentName, maxParallelRuns = 2, OnMaxParallelRunsStrategy.REJECT)
            }
        }

    @Test
    fun `onAgentsReloaded should remove semaphore for removed agents`() =
        runTest {
            val agentName = "removed-agent"

            // Establish semaphore at limit=1 (full)
            manager.acquireSlot(agentName, maxParallelRuns = 1, OnMaxParallelRunsStrategy.REJECT)

            // Simulate reload: agent removed
            manager.onAgentsReloaded(updated = emptySet(), removed = setOf(agentName))

            // After removal, new acquire with limit=3 should create fresh semaphore
            manager.acquireSlot(agentName, maxParallelRuns = 3, OnMaxParallelRunsStrategy.REJECT)
            manager.acquireSlot(agentName, maxParallelRuns = 3, OnMaxParallelRunsStrategy.REJECT)
            manager.acquireSlot(agentName, maxParallelRuns = 3, OnMaxParallelRunsStrategy.REJECT)
            assertFailsWith<MaxParallelRunsExceededException> {
                manager.acquireSlot(agentName, maxParallelRuns = 3, OnMaxParallelRunsStrategy.REJECT)
            }
        }
}
