package homer.app.runners.queue

import homer.app.fixtures.TestFixtures
import homer.app.models.RunnerFilter
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.context.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StaticRunnerConsumerTest {
    private fun params(processId: Int) =
        RunnerSessionParams(processId, "demo", TestFixtures.mockWorkspaceConfig(), emptyMap(), Context.current())

    private class FakeRunner(
        override val alias: String,
        override val runnerEnv: Map<String, String> = emptyMap(),
        private val open: suspend (RunnerSessionParams) -> RunnerSession = { mockk(relaxed = true) },
    ) : Runner {
        override val isRemote = false
        override val sysEnvBackedEnvNames: Set<String> = emptySet()
        var opened = 0

        override suspend fun openSession(params: RunnerSessionParams): RunnerSession {
            opened++
            return open(params)
        }
    }

    @Test
    fun `unbounded consumer serves every request immediately`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val runner = FakeRunner("local")
            StaticRunnerConsumer(runner, tags = emptySet(), concurrency = null, queue = queue).start(backgroundScope)

            val a = async { queue.awaitClaim(params(1), RunnerFilter.Alias("local"), 300.seconds) }
            val b = async { queue.awaitClaim(params(2), RunnerFilter.Alias("local"), 300.seconds) }
            runCurrent()
            assertTrue(a.isCompleted && b.isCompleted)
            assertEquals(2, runner.opened)
        }

    @Test
    fun `concurrency gates claims and closing a session frees the permit`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val runner = FakeRunner("build-host")
            val consumer = StaticRunnerConsumer(runner, tags = emptySet(), concurrency = 1, queue = queue)
            consumer.start(backgroundScope)

            val first = async { queue.awaitClaim(params(1), RunnerFilter.Alias("build-host"), 300.seconds) }
            val second = async { queue.awaitClaim(params(2), RunnerFilter.Alias("build-host"), 300.seconds) }
            runCurrent()
            assertTrue(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(1, consumer.inUse)

            first.await().close()
            runCurrent()
            assertTrue(second.isCompleted)
            assertEquals(1, consumer.inUse)
            second.await().close()
            runCurrent()
            assertEquals(0, consumer.inUse)
        }

    @Test
    fun `tagged consumer takes only matching tag requests`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            StaticRunnerConsumer(FakeRunner("k8s"), tags = setOf("linux"), concurrency = null, queue = queue).start(backgroundScope)

            val linux = async { queue.awaitClaim(params(1), RunnerFilter.Tags(setOf("linux")), 300.seconds) }
            val gpu = async { runCatching { queue.awaitClaim(params(2), RunnerFilter.Tags(setOf("linux", "gpu")), 30.seconds) } }
            runCurrent()
            assertTrue(linux.isCompleted)
            assertFalse(gpu.isCompleted)

            val outcome = gpu.await()
            assertIs<homer.app.exceptions.RunnerClaimTimeoutException>(outcome.exceptionOrNull())
        }

    @Test
    fun `openSession failure fails the run and frees the permit`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val runner = FakeRunner("k8s") { throw IllegalStateException("pod start failed") }
            val consumer = StaticRunnerConsumer(runner, tags = emptySet(), concurrency = 1, queue = queue)
            consumer.start(backgroundScope)

            val run = async { runCatching { queue.awaitClaim(params(1), RunnerFilter.Alias("k8s"), 300.seconds) } }
            runCurrent()
            val failure = run.await().exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertEquals("pod start failed", failure.message)
            assertEquals(0, consumer.inUse)
        }

    @Test
    fun `a session opened for a run that stopped waiting is closed and the permit freed`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val session = mockk<RunnerSession>(relaxed = true)
            var release: (() -> Unit)? = null
            val runner =
                FakeRunner("slow") { _ ->
                    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                    release = { gate.complete(Unit) }
                    gate.await()
                    session
                }
            val consumer = StaticRunnerConsumer(runner, tags = emptySet(), concurrency = 1, queue = queue)
            consumer.start(backgroundScope)

            val run = async { queue.awaitClaim(params(1), RunnerFilter.Alias("slow"), 300.seconds) }
            runCurrent()
            run.cancel()
            runCurrent()
            release!!()
            runCurrent()

            verify { session.close() }
            assertEquals(0, consumer.inUse)
        }

    @Test
    fun `consumer registers itself with the queue`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val consumer = StaticRunnerConsumer(FakeRunner("local"), tags = setOf("linux"), concurrency = 3, queue = queue)
            consumer.start(backgroundScope)
            assertEquals(listOf<ClaimConsumerInfo>(consumer), queue.consumers())
            assertEquals("local", consumer.alias)
            assertEquals(setOf("linux"), consumer.tags)
            assertEquals(3, consumer.concurrency)
        }

    @Test
    fun `tag-filtered claim receives the runner's env through the session`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val session = mockk<RunnerSession>(relaxed = true)
            every { session.env } returns mapOf("HMR_API_URL" to "u")
            val runner = FakeRunner("k8s", open = { session }, runnerEnv = mapOf("TOKEN" to "t"))
            StaticRunnerConsumer(runner, tags = setOf("linux"), concurrency = null, queue = queue).start(backgroundScope)

            val result = async { queue.awaitClaim(params(1), RunnerFilter.Tags(setOf("linux")), 300.seconds) }
            runCurrent()

            assertEquals(mapOf("TOKEN" to "t", "HMR_API_URL" to "u"), result.await().env)
        }

    @Test
    fun `alias-filtered claim does not add the runner's env to the session`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val session = mockk<RunnerSession>(relaxed = true)
            every { session.env } returns mapOf("HMR_API_URL" to "u")
            val runner = FakeRunner("k8s", open = { session }, runnerEnv = mapOf("TOKEN" to "t"))
            StaticRunnerConsumer(runner, tags = emptySet(), concurrency = null, queue = queue).start(backgroundScope)

            val result = async { queue.awaitClaim(params(1), RunnerFilter.Alias("k8s"), 300.seconds) }
            runCurrent()

            assertEquals(mapOf("HMR_API_URL" to "u"), result.await().env)
        }

    @Test
    fun `consumer rejects concurrency below 1`() {
        assertThrows<IllegalArgumentException> {
            StaticRunnerConsumer(FakeRunner("x"), emptySet(), 0, RunnerClaimQueue(10))
        }
    }
}
