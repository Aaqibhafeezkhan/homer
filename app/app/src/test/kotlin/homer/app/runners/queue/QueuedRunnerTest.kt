package homer.app.runners.queue

import homer.app.fixtures.TestFixtures
import homer.app.models.RunnerFilter
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.context.Context
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
}
