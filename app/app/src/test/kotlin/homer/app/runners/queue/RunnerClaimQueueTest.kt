package homer.app.runners.queue

import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.fixtures.TestFixtures
import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.context.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RunnerClaimQueueTest {
    private fun params(processId: Int) =
        RunnerSessionParams(
            processId = processId,
            agentName = "demo",
            workspace = TestFixtures.mockWorkspaceConfig(),
            env = emptyMap(),
            parentContext = Context.current(),
        )

    private class Consumer(
        override val alias: String,
        override val tags: Set<String> = emptySet(),
        override val concurrency: Int? = null,
        override var inUse: Int = 0,
    ) : ClaimConsumerInfo

    @Test
    fun `a matching consumer takes the request and the run receives the session`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val session = mockk<RunnerSession>(relaxed = true)
            val run = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            val request = queue.take(Consumer("local"))
            assertEquals(1, request.params.processId)
            assertTrue(request.complete(session))
            assertSame(session, run.await())
            assertEquals(0, queue.pendingCount())
        }

    @Test
    fun `alias filter matches only that alias and tags filter needs a superset`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val aliasRun = async { queue.awaitClaim(params(1), RunnerFilter.Alias("k8s"), 300.seconds) }
            val tagRun = async { queue.awaitClaim(params(2), RunnerFilter.Tags(setOf("linux", "gpu")), 300.seconds) }
            runCurrent()

            val gpuBox = Consumer("gpu-box", tags = setOf("linux", "gpu", "a100"))
            val linuxOnly = Consumer("linux-only", tags = setOf("linux"))
            assertFalse(linuxOnly.matches(RunnerFilter.Tags(setOf("linux", "gpu"))))
            assertTrue(gpuBox.matches(RunnerFilter.Tags(setOf("linux", "gpu"))))
            assertFalse(gpuBox.matches(RunnerFilter.Alias("k8s")))

            val taken = queue.take(gpuBox)
            assertEquals(2, taken.params.processId)
            taken.complete(mockk(relaxed = true))
            tagRun.await()

            val k8s = queue.take(Consumer("k8s"))
            assertEquals(1, k8s.params.processId)
            k8s.complete(mockk(relaxed = true))
            aliasRun.await()
        }

    @Test
    fun `oldest matching request is taken first`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val first = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            val second = async { queue.awaitClaim(params(2), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()

            assertEquals(
                1,
                queue
                    .take(Consumer("local"))
                    .also { it.complete(mockk(relaxed = true)) }
                    .params.processId,
            )
            assertEquals(
                2,
                queue
                    .take(Consumer("local"))
                    .also { it.complete(mockk(relaxed = true)) }
                    .params.processId,
            )
            first.await()
            second.await()
        }

    @Test
    fun `expiry fails the run naming a saturated consumer`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            queue.registerConsumer(Consumer("build-host", concurrency = 2, inUse = 2))

            val ex =
                assertThrows<RunnerClaimTimeoutException> {
                    queue.awaitClaim(params(1), RunnerFilter.Alias("build-host"), 300.seconds)
                }
            assertEquals(
                "No runner claimed the run within 300 s (runner 'build-host'): runner 'build-host' at concurrency 2/2",
                ex.message,
            )
            assertEquals(0, queue.pendingCount())
        }

    @Test
    fun `expiry names unmatched tags when no consumer matches`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            queue.registerConsumer(Consumer("local", tags = setOf("linux")))

            val ex =
                assertThrows<RunnerClaimTimeoutException> {
                    queue.awaitClaim(params(1), RunnerFilter.Tags(setOf("linux", "gpu")), 30.seconds)
                }
            assertEquals(
                "No runner claimed the run within 30 s (tags [gpu, linux]): no registered runner matches tags [gpu, linux]",
                ex.message,
            )
        }

    @Test
    fun `expiry names a missing alias as having no registered instances`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val ex =
                assertThrows<RunnerClaimTimeoutException> {
                    queue.awaitClaim(params(1), RunnerFilter.Alias("workers"), 30.seconds)
                }
            assertTrue(ex.message!!.endsWith("runner 'workers' has no registered instances"), ex.message)
        }

    @Test
    fun `a full queue fails the run immediately`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 1)
            val parked = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()

            val ex = assertThrows<ClaimQueueFullException> { queue.awaitClaim(params(2), RunnerFilter.Alias("local"), 300.seconds) }
            assertEquals("Runner claim queue is full (1 pending claims)", ex.message)

            queue.take(Consumer("local")).complete(mockk(relaxed = true))
            parked.await()
        }

    @Test
    fun `cancelling the run withdraws the request and a late claim gets nothing`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val run = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            assertEquals(1, queue.pendingCount())

            run.cancel()
            runCurrent()
            assertEquals(0, queue.pendingCount())

            val later = async { queue.awaitClaim(params(2), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            val taken = queue.take(Consumer("local"))
            assertEquals(2, taken.params.processId)
            taken.complete(mockk(relaxed = true))
            later.await()
        }

    @Test
    fun `completing a request whose run already stopped waiting returns false`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val run = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            val taken = queue.take(Consumer("local"))
            run.cancel()
            runCurrent()

            assertFalse(taken.complete(mockk(relaxed = true)))
        }

    @Test
    fun `a session delivered to a run that is cancelled before it resumes is closed`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val session = mockk<RunnerSession>(relaxed = true)
            val run = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            val taken = queue.take(Consumer("local"))
            assertTrue(taken.complete(session))
            run.cancel()
            runCurrent()

            verify { session.close() }
        }

    @Test
    fun `a session delivered after the second deadline is refused and closed`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val run = async { runCatching { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 30.seconds) } }
            runCurrent()
            val taken = queue.take(Consumer("local"))
            advanceUntilIdle()

            val outcome = run.await()
            val failure = outcome.exceptionOrNull()
            assertIs<RunnerClaimTimeoutException>(failure)
            assertTrue(failure.message!!.endsWith("claimed but no session was delivered"), failure.message)

            val session = mockk<RunnerSession>(relaxed = true)
            assertFalse(taken.complete(session))
        }

    @Test
    fun `a cancelled take drops its own waiter without leaking`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            withTimeoutOrNull(50) { queue.take(Consumer("local")) }
            assertEquals(0, queue.waiterCount())
        }

    @Test
    fun `a consumer waiting on an empty queue wakes when a matching request arrives`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val taker = async { queue.take(Consumer("local")) }
            runCurrent()
            assertFalse(taker.isCompleted)

            val run = async { queue.awaitClaim(params(7), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            assertTrue(taker.isCompleted)
            taker.await().complete(mockk(relaxed = true))
            run.await()
        }

    @Test
    fun `pendingSnapshot lists waiting requests oldest first with their enqueue time`() =
        runTest {
            var now = 1_000L
            val queue = RunnerClaimQueue(maxPending = 10, clock = { now })
            val a = async { withTimeoutOrNull(60.seconds) { queue.awaitClaim(params(1), RunnerFilter.Alias("ssh-a"), 60.seconds) } }
            runCurrent()
            now = 5_000L
            val b = async { withTimeoutOrNull(60.seconds) { queue.awaitClaim(params(2), RunnerFilter.Tags(setOf("gpu")), 60.seconds) } }
            runCurrent()

            assertEquals(
                listOf(
                    PendingClaim(RunnerFilter.Alias("ssh-a"), 1_000L),
                    PendingClaim(RunnerFilter.Tags(setOf("gpu")), 5_000L),
                ),
                queue.pendingSnapshot(),
            )
            a.cancel()
            b.cancel()
        }

    @Test
    fun `pendingSnapshot is empty when nothing waits`() =
        runTest {
            assertEquals(emptyList<PendingClaim>(), RunnerClaimQueue(maxPending = 10).pendingSnapshot())
        }

    @Test
    fun `nowMs reads the same clock that stamps enqueue times`() =
        runTest {
            var now = 42_000L
            val queue = RunnerClaimQueue(maxPending = 10, clock = { now })
            val waiter = async { withTimeoutOrNull(60.seconds) { queue.awaitClaim(params(1), RunnerFilter.Alias("ssh-a"), 60.seconds) } }
            runCurrent()
            now = 45_000L

            assertEquals(42_000L, queue.pendingSnapshot().single().enqueuedAtMs)
            assertEquals(45_000L, queue.nowMs())
            waiter.cancel()
        }
}
