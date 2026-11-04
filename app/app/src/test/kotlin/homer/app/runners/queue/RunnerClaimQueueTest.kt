package homer.app.runners.queue

import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.fixtures.TestFixtures
import homer.app.models.RunnerAffinity
import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RunnerClaimQueueTest {
    private fun params(
        processId: Int,
        rootProcessId: Int? = null,
    ) = RunnerSessionParams(
        processId = processId,
        agentName = "demo",
        workspace = TestFixtures.mockWorkspaceConfig(),
        env = emptyMap(),
        parentContext = Context.current(),
        rootProcessId = rootProcessId,
    )

    private class Consumer(
        override val alias: String,
        override val tags: Set<String> = emptySet(),
        override val concurrency: Int? = null,
        override var inUse: Int = 0,
        override val affinityInstance: String? = null,
        override val draining: Boolean = false,
    ) : ClaimConsumerInfo

    /** Declares no instance identity at all — a nameless external instance (ADR-0099). */
    private class NamelessConsumer(
        override val alias: String,
        override val tags: Set<String>,
    ) : ClaimConsumerInfo {
        override val concurrency: Int? = null
        override val inUse: Int = 0
    }

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
    fun `the alias-free reason names the observation and no runner`() =
        runTest {
            val saturated = RunnerClaimQueue(maxPending = 10)
            saturated.registerConsumer(Consumer("build-host", concurrency = 2, inUse = 2))
            val busy =
                assertThrows<RunnerClaimTimeoutException> {
                    saturated.awaitClaim(params(1), RunnerFilter.Alias("build-host"), 300.seconds)
                }
            assertEquals("every matching runner was at its concurrency limit", busy.publicReason)

            val missing = RunnerClaimQueue(maxPending = 10)
            val absent =
                assertThrows<RunnerClaimTimeoutException> {
                    missing.awaitClaim(params(1), RunnerFilter.Alias("workers"), 30.seconds)
                }
            assertEquals("no registered runner matches it", absent.publicReason)

            val tags = RunnerClaimQueue(maxPending = 10)
            tags.registerConsumer(Consumer("local", tags = setOf("linux")))
            val unmatched =
                assertThrows<RunnerClaimTimeoutException> {
                    tags.awaitClaim(params(1), RunnerFilter.Tags(setOf("linux", "gpu")), 30.seconds)
                }
            assertEquals("no registered runner matches those tags", unmatched.publicReason)
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
    fun `a new request wakes one waiter, the idle consumer that claimed least recently`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val a = Consumer("local").also { queue.registerConsumer(it) }
            val b = Consumer("local").also { queue.registerConsumer(it) }
            val first = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            queue.take(a).complete(mockk(relaxed = true))
            first.await()

            val takeA = async { queue.take(a) }
            runCurrent()
            val takeB = async { queue.take(b) }
            runCurrent()
            val second = async { queue.awaitClaim(params(2), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()

            assertTrue(takeB.isCompleted)
            assertTrue(takeA.isActive)
            assertEquals(2, takeB.await().params.processId)
            takeB.await().complete(mockk(relaxed = true))
            second.await()
            takeA.cancel()
        }

    @Test
    fun `a reservation counts as a claim, so a burst does not go to one consumer's waiters`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val a = Consumer("local").also { queue.registerConsumer(it) }
            val b = Consumer("local").also { queue.registerConsumer(it) }
            val takesA = List(2) { async { queue.take(a) }.also { runCurrent() } }
            val takeB = async { queue.take(b) }
            runCurrent()
            val runs =
                (1..2).map { id ->
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        queue.awaitClaim(params(id), RunnerFilter.Alias("local"), 300.seconds)
                    }
                }
            runCurrent()

            assertEquals(1, takesA.count { it.isCompleted }, "consumer A took both runs of the burst")
            assertTrue(takeB.isCompleted)
            (takesA.filter { it.isCompleted } + takeB).forEach { it.await().complete(mockk(relaxed = true)) }
            runs.forEach { it.join() }
            takesA.forEach { it.cancel() }
        }

    @Test
    fun `a new request wakes at most one parked waiter`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val takeA = async { queue.take(Consumer("local")) }
            runCurrent()
            val takeB = async { queue.take(Consumer("local")) }
            runCurrent()
            val run =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds)
                }

            assertEquals(1, queue.waiterCount(), "the request woke every parked waiter")
            runCurrent()
            takeA.await().complete(mockk(relaxed = true))
            run.join()
            takeB.cancel()
        }

    @Test
    fun `consumers that never claimed are served oldest waiter first`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val takeC = async { queue.take(Consumer("local")) }
            runCurrent()
            val takeD = async { queue.take(Consumer("local")) }
            runCurrent()
            val run = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()

            assertTrue(takeC.isCompleted)
            assertTrue(takeD.isActive)
            takeC.await().complete(mockk(relaxed = true))
            run.await()
            takeD.cancel()
        }

    @Test
    fun `a consumer that fails fast and re-polls at once does not take the next run`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val claims = mutableListOf<String>()

            fun consumerLoop(name: String) =
                Consumer("local").also { queue.registerConsumer(it) }.let { consumer ->
                    launch {
                        while (true) {
                            val request = queue.take(consumer)
                            claims += "$name:${request.params.processId}"
                            request.complete(mockk(relaxed = true))
                        }
                    }
                }
            val loopA = consumerLoop("A")
            runCurrent()
            val loopB = consumerLoop("B")
            runCurrent()

            for (id in 1..3) {
                val run = async { queue.awaitClaim(params(id), RunnerFilter.Alias("local"), 300.seconds) }
                runCurrent()
                run.await()
            }

            assertEquals(listOf("A:1", "B:2", "A:3"), claims)
            loopA.cancel()
            loopB.cancel()
        }

    @Test
    fun `a fresh take skips a request reserved for a parked waiter`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val takeA = async { queue.take(Consumer("local")) }
            runCurrent()
            val run =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds)
                }
            val takeC = async(start = CoroutineStart.UNDISPATCHED) { queue.take(Consumer("local")) }
            runCurrent()

            assertTrue(takeA.isCompleted)
            assertTrue(takeC.isActive)
            takeA.await().complete(mockk(relaxed = true))
            run.await()
            takeC.cancel()
        }

    @Test
    fun `a reserved waiter cancelled before it resumes hands the request on`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val session = mockk<RunnerSession>(relaxed = true)
            val takeA = async { queue.take(Consumer("local")) }
            runCurrent()
            val takeB = async { queue.take(Consumer("local")) }
            runCurrent()
            val run =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds)
                }
            takeA.cancel()
            runCurrent()

            assertTrue(takeA.isCancelled)
            assertTrue(takeB.isCompleted)
            assertTrue(takeB.await().complete(session))
            assertSame(session, run.await())
            assertEquals(0, queue.waiterCount())
        }

    @Test
    fun `a reservation handed on until no waiter is left stays claimable`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val session = mockk<RunnerSession>(relaxed = true)
            val takeA = async { queue.take(Consumer("local")) }
            runCurrent()
            val takeB = async { queue.take(Consumer("local")) }
            runCurrent()
            val run =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds)
                }
            takeA.cancel()
            takeB.cancel()
            runCurrent()

            assertTrue(takeA.isCancelled)
            assertTrue(takeB.isCancelled)
            assertEquals(0, queue.waiterCount())
            assertEquals(1, queue.pendingCount())
            val taken = queue.take(Consumer("local"))
            assertEquals(1, taken.params.processId)
            assertTrue(taken.complete(session))
            assertSame(session, run.await())
        }

    @Test
    fun `a woken consumer that no longer matches passes the request on`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            var draining = false
            val drainingA =
                object : ClaimConsumerInfo {
                    override val alias = "local"
                    override val tags = emptySet<String>()
                    override val concurrency: Int? = null
                    override val inUse = 0

                    override fun matches(filter: RunnerFilter) = !draining && super.matches(filter)
                }
            val takeA = async { queue.take(drainingA) }
            runCurrent()
            val takeB = async { queue.take(Consumer("local")) }
            runCurrent()
            val run =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds)
                }
            draining = true
            runCurrent()

            assertTrue(takeA.isActive)
            assertTrue(takeB.isCompleted)
            takeB.await().complete(mockk(relaxed = true))
            run.await()
            takeA.cancel()
        }

    @Test
    fun `an unregistered consumer leaves no claim record`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val a = Consumer("local")
            queue.registerConsumer(a)
            assertEquals(1, queue.claimRecordCount())
            val first = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            queue.take(a).complete(mockk(relaxed = true))
            first.await()

            queue.unregisterConsumer(a)
            assertEquals(0, queue.claimRecordCount())

            val late = async { queue.awaitClaim(params(2), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            queue.take(a).complete(mockk(relaxed = true))
            late.await()
            assertEquals(0, queue.claimRecordCount())
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

    // ---- runnerAffinity (ADR-0099): the queue clock and the memory clock are the same virtual one.

    private fun TestScope.affinityQueue(memory: RunnerAffinityMemory): RunnerClaimQueue =
        RunnerClaimQueue(maxPending = 10, clock = { testScheduler.currentTime }, affinityMemory = memory)

    private fun TestScope.memory() = RunnerAffinityMemory(clock = { testScheduler.currentTime })

    private fun gpuFilter() = RunnerFilter.Tags(setOf("gpu"))

    @Test
    fun `a preferred instance takes the request that prefers it ahead of an older one`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:X") }
            val queue = affinityQueue(memory)
            val x = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:X").also { queue.registerConsumer(it) }
            queue.registerConsumer(Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:Y"))

            val older = async { queue.awaitClaim(params(10), gpuFilter(), 300.seconds) }
            runCurrent()
            val newer =
                async {
                    queue.awaitClaim(params(11, rootProcessId = 1), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 30))
                }
            runCurrent()

            val taken = queue.take(x)
            assertEquals(11, taken.params.processId)
            assertTrue(taken.complete(mockk(relaxed = true)))
            newer.await()
            older.cancel()
        }

    @Test
    fun `another matching consumer skips a preferred request until waitSec passes`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:X") }
            val queue = affinityQueue(memory)
            queue.registerConsumer(Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:X"))
            val y = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:Y").also { queue.registerConsumer(it) }

            val run =
                async {
                    queue.awaitClaim(params(1, rootProcessId = 1), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 30))
                }
            runCurrent()
            val takeY = async { queue.take(y) }
            runCurrent()
            assertTrue(takeY.isActive, "a matching consumer must pass over the request while its preference is active")

            testScheduler.advanceTimeBy(29_999)
            runCurrent()
            assertTrue(takeY.isActive, "the request opened to another consumer before waitSec passed")

            testScheduler.advanceTimeBy(1)
            runCurrent()
            assertTrue(takeY.isCompleted)
            val taken = takeY.await()
            assertEquals(1, taken.params.processId)
            assertTrue(taken.complete(mockk(relaxed = true)))
            run.await()
        }

    @Test
    fun `a request falls back at once when its preferred instance is unregistered`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:X") }
            val queue = affinityQueue(memory)
            val y = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:Y").also { queue.registerConsumer(it) }

            val run =
                async {
                    queue.awaitClaim(params(1, rootProcessId = 1), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 600))
                }
            runCurrent()
            val taken = queue.take(y)
            assertEquals(1, taken.params.processId)
            assertTrue(taken.complete(mockk(relaxed = true)))
            run.await()
        }

    @Test
    fun `a request falls back at once when its preferred instance is draining`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:X") }
            val queue = affinityQueue(memory)
            queue.registerConsumer(Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:X", draining = true))
            val y = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:Y").also { queue.registerConsumer(it) }

            val run =
                async {
                    queue.awaitClaim(params(1, rootProcessId = 1), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 600))
                }
            runCurrent()
            val taken = queue.take(y)
            assertEquals(1, taken.params.processId)
            assertTrue(taken.complete(mockk(relaxed = true)))
            run.await()
        }

    @Test
    fun `a request falls back at once when its preferred instance no longer matches the filter`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:X") }
            val queue = affinityQueue(memory)
            // The instance holding the name is registered for another tag set: the run's filter
            // never widened, so it is not the instance this run could have used.
            queue.registerConsumer(Consumer("dom", tags = setOf("cpu"), affinityInstance = "dom:X"))
            val y = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:Y").also { queue.registerConsumer(it) }

            val run =
                async {
                    queue.awaitClaim(params(1, rootProcessId = 1), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 600))
                }
            runCurrent()
            val taken = queue.take(y)
            assertEquals(1, taken.params.processId)
            assertTrue(taken.complete(mockk(relaxed = true)))
            run.await()
        }

    @Test
    fun `a request without an affinity preference is never held back`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:X") }
            val queue = affinityQueue(memory)
            queue.registerConsumer(Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:X"))
            val y = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:Y").also { queue.registerConsumer(it) }

            val run = async { queue.awaitClaim(params(1, rootProcessId = 1), gpuFilter(), 300.seconds) }
            runCurrent()
            val taken = queue.take(y)
            assertEquals(1, taken.params.processId)
            assertTrue(taken.complete(mockk(relaxed = true)))
            run.await()
        }

    @Test
    fun `a claim records the serving instance under the flow's affinity key`() =
        runTest {
            val memory = memory()
            val queue = affinityQueue(memory)
            val x = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:X").also { queue.registerConsumer(it) }

            val run =
                async {
                    queue.awaitClaim(params(1, rootProcessId = 7), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 30))
                }
            runCurrent()
            queue.take(x).complete(mockk(relaxed = true))
            run.await()

            assertEquals("dom:X", memory.preferredFor(7, "gpu"))
            assertNull(memory.preferredFor(8, "gpu"), "another flow's key must stay unknown")
        }

    @Test
    fun `a nameless instance records nothing, so a preference never names it`() =
        runTest {
            val memory = memory()
            val queue = affinityQueue(memory)
            val anonymous = NamelessConsumer("dom", setOf("gpu")).also { queue.registerConsumer(it) }

            val run =
                async {
                    queue.awaitClaim(params(1, rootProcessId = 7), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 30))
                }
            runCurrent()
            queue.take(anonymous).complete(mockk(relaxed = true))
            run.await()

            assertEquals(0, memory.size())
            assertNull(memory.preferredFor(7, "gpu"))
        }

    @Test
    fun `an unrecorded preference prefers nobody, so the oldest match still goes first`() =
        runTest {
            val memory = memory()
            val queue = affinityQueue(memory)
            val anonymous = NamelessConsumer("dom", setOf("gpu")).also { queue.registerConsumer(it) }

            val older = async { queue.awaitClaim(params(10), gpuFilter(), 300.seconds) }
            runCurrent()
            val affinityRun =
                async {
                    queue.awaitClaim(params(11, rootProcessId = 7), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 30))
                }
            runCurrent()

            // The flow has served nothing yet, so nobody is preferred: two null identities are not
            // a match, and the oldest request is still the one to take.
            val taken = queue.take(anonymous)
            assertEquals(10, taken.params.processId)
            assertTrue(taken.complete(mockk(relaxed = true)))
            older.await()

            val second = queue.take(anonymous)
            assertEquals(11, second.params.processId)
            assertTrue(second.complete(mockk(relaxed = true)))
            affinityRun.await()
            assertEquals(0, memory.size())
        }

    @Test
    fun `a preferred instance cannot jump past the per-consumer cap`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:X") }
            val queue = affinityQueue(memory)
            val x = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:X").also { queue.registerConsumer(it) }

            // One unpreferred request arrives first, then MAX_JUMPS + 1 that all prefer X: X may
            // take at most MAX_JUMPS of them ahead of the older one before it must serve it.
            val unpreferred = async { queue.awaitClaim(params(100), gpuFilter(), 300.seconds) }
            runCurrent()
            val preferred =
                (1..RunnerClaimQueue.MAX_JUMPS + 1).map { id ->
                    async {
                        queue.awaitClaim(params(id, rootProcessId = 1), gpuFilter(), 300.seconds, RunnerAffinity("gpu", 300))
                    }.also { runCurrent() }
                }

            val taken =
                List(RunnerClaimQueue.MAX_JUMPS + 2) {
                    val request = queue.take(x)
                    request.complete(mockk(relaxed = true))
                    request.params.processId
                }
            assertEquals((1..RunnerClaimQueue.MAX_JUMPS).toList() + 100 + (RunnerClaimQueue.MAX_JUMPS + 1), taken)
            unpreferred.await()
            preferred.forEach { it.await() }
        }

    @Test
    fun `a waitSec past the claim TTL still fails the run at the claim TTL`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:X") }
            val queue = affinityQueue(memory)
            queue.registerConsumer(Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:X"))

            val run =
                async {
                    runCatching {
                        queue.awaitClaim(params(1, rootProcessId = 1), gpuFilter(), 30.seconds, RunnerAffinity("gpu", 600))
                    }
                }
            runCurrent()
            testScheduler.advanceTimeBy(30_000)
            runCurrent()

            assertIs<RunnerClaimTimeoutException>(run.await().exceptionOrNull())
            assertEquals(30_000L, testScheduler.currentTime, "the run waited past its claim TTL")
            assertEquals(0, queue.pendingCount())
        }

    @Test
    fun `expiry text names no instance of the preference`() =
        runTest {
            val memory = memory().also { it.record(1, "gpu", "dom:secret-name") }
            val queue = affinityQueue(memory)
            queue.registerConsumer(
                Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:secret-name", concurrency = 1, inUse = 1),
            )

            val ex =
                assertThrows<RunnerClaimTimeoutException> {
                    queue.awaitClaim(params(1, rootProcessId = 1), gpuFilter(), 30.seconds, RunnerAffinity("gpu", 600))
                }
            // A run-visible reason is served to every caller who can see the run, and an instance
            // name is admin-only there (ADR-0077). CWE-209
            assertFalse(ex.message!!.contains("secret-name"), ex.message)
            assertFalse(ex.publicReason.contains("secret-name"), ex.publicReason)
        }
}
