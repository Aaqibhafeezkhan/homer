package homer.app.runners.queue

import homer.app.fixtures.TestFixtures
import homer.app.models.RunnerAffinity
import homer.app.models.RunnerFilter
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.context.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class QueuedRunnerTest {
    private fun stub(
        a: String,
        remote: Boolean = false,
        env: Map<String, String> = emptyMap(),
        secrets: Set<String> = emptySet(),
    ): Runner =
        object : Runner {
            override val alias = a
            override val isRemote = remote
            override val runnerEnv = env
            override val sysEnvBackedEnvNames = secrets

            override suspend fun openSession(params: RunnerSessionParams): RunnerSession =
                mockk<RunnerSession>(relaxed = true) { every { runnerAlias } returns a }
        }

    @Test
    fun `alias filter delegates isRemote, runnerEnv and secret names to the one runner`() {
        val ssh = stub("build-host", remote = true, env = mapOf("JAVA_HOME" to "/j"), secrets = setOf("TOKEN"))
        val runner = QueuedRunner(RunnerFilter.Alias("build-host"), RunnerClaimQueue(10), listOf(ssh), 300.seconds)

        assertEquals("build-host", runner.alias)
        assertTrue(runner.isRemote)
        assertEquals(mapOf("JAVA_HOME" to "/j"), runner.runnerEnv)
        assertEquals(setOf("TOKEN"), runner.sysEnvBackedEnvNames)
    }

    @Test
    fun `tags filter is remote if any candidate is, has no runnerEnv, and unions secret names`() {
        val local = stub("local", secrets = setOf("A"))
        val k8s = stub("k8s", remote = true, env = mapOf("X" to "1"), secrets = setOf("B"))
        val runner = QueuedRunner(RunnerFilter.Tags(setOf("linux")), RunnerClaimQueue(10), listOf(local, k8s), 300.seconds)

        assertEquals("tags:linux", runner.alias)
        assertTrue(runner.isRemote)
        assertEquals(emptyMap(), runner.runnerEnv)
        assertEquals(setOf("A", "B"), runner.sysEnvBackedEnvNames)

        assertFalse(QueuedRunner(RunnerFilter.Tags(setOf("linux")), RunnerClaimQueue(10), listOf(local), 300.seconds).isRemote)
    }

    @Test
    fun `openSession waits in the queue and returns the claimed session`() =
        runTest {
            val queue = RunnerClaimQueue(10)
            val local = stub("local")
            StaticRunnerConsumer(local, emptySet(), null, queue).start(backgroundScope)
            val runner = QueuedRunner(RunnerFilter.Alias("local"), queue, listOf(local), 300.seconds)

            val session =
                runner.openSession(
                    RunnerSessionParams(1, "demo", TestFixtures.mockWorkspaceConfig(), emptyMap(), Context.current()),
                )
            assertEquals("local", session.runnerAlias)
        }

    private class Consumer(
        override val alias: String,
        override val tags: Set<String> = emptySet(),
        override val concurrency: Int? = null,
        override val inUse: Int = 0,
        override val affinityInstance: String? = null,
    ) : ClaimConsumerInfo

    private fun params(
        processId: Int,
        rootProcessId: Int?,
    ) = RunnerSessionParams(
        processId = processId,
        agentName = "demo",
        workspace = TestFixtures.mockWorkspaceConfig(),
        env = emptyMap(),
        parentContext = Context.current(),
        rootProcessId = rootProcessId,
    )

    @Test
    fun `openSession resolves the preference from the run's own flow root`() =
        runTest {
            val memory = RunnerAffinityMemory(clock = { testScheduler.currentTime })
            val queue = RunnerClaimQueue(maxPending = 10, clock = { testScheduler.currentTime }, affinityMemory = memory)
            memory.record(7, "gpu", "dom:X")
            val x = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:X").also { queue.registerConsumer(it) }
            val y = Consumer("dom", tags = setOf("gpu"), affinityInstance = "dom:Y").also { queue.registerConsumer(it) }
            val runner =
                QueuedRunner(
                    RunnerFilter.Tags(setOf("gpu")),
                    queue,
                    listOf(stub("dom", remote = true)),
                    300.seconds,
                    RunnerAffinity("gpu", 30),
                )

            // Flow 7 prefers X, so the instance parked first passes over its request.
            val yTake = async { queue.take(y) }
            runCurrent()
            val preferred = async { runner.openSession(params(7, rootProcessId = 7)) }
            runCurrent()
            assertTrue(yTake.isActive, "the flow's preferred instance is X, so Y must not take its request")
            val taken = queue.take(x)
            assertEquals(7, taken.params.processId)
            assertTrue(taken.complete(mockk<RunnerSession>(relaxed = true)))
            preferred.await()
            yTake.cancel()

            // Another flow has no recorded key, so the very same runner is not held back.
            val other = async { runner.openSession(params(8, rootProcessId = 8)) }
            runCurrent()
            val straight = queue.take(y)
            assertEquals(8, straight.params.processId)
            assertTrue(straight.complete(mockk<RunnerSession>(relaxed = true)))
            other.await()
        }
}
