package homer.app.runners

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.RunnerConfig
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.queue.QueuedRunner
import homer.app.runners.queue.RegisteredRunner
import homer.app.runners.queue.RunnerClaimQueue
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.context.Context
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RunnerRegistryTest {
    private fun stubRunner(a: String): Runner =
        object : Runner {
            override val alias: String = a

            override val isRemote: Boolean = false

            override val runnerEnv: Map<String, String> = emptyMap()

            override val sysEnvBackedEnvNames: Set<String> = emptySet()

            override suspend fun openSession(params: RunnerSessionParams): RunnerSession =
                mockk<RunnerSession>(relaxed = true) { every { runnerAlias } returns a }
        }

    @Test
    fun `get returns runner by alias`() {
        val local = stubRunner("local")
        val k8s = stubRunner("k8s-dev")
        val registry = RunnerRegistry(listOf(local, k8s))

        assertSame(local, registry.get("local"))
        assertSame(k8s, registry.get("k8s-dev"))
    }

    @Test
    fun `get throws on unknown alias`() {
        val registry = RunnerRegistry(listOf(stubRunner("local")))

        val ex = assertThrows<IllegalStateException> { registry.get("missing") }
        assertEquals(true, ex.message!!.contains("Unknown runner 'missing'"))
    }

    @Test
    fun `rejects duplicate aliases`() {
        assertThrows<IllegalArgumentException> {
            RunnerRegistry(listOf(stubRunner("local"), stubRunner("local")))
        }
    }

    @Test
    fun `aliases returns all registered aliases`() {
        val registry = RunnerRegistry(listOf(stubRunner("local"), stubRunner("k8s")))

        assertEquals(setOf("local", "k8s"), registry.aliases())
    }

    @Test
    fun `forAlias returns a queued runner over the aliased backend`() =
        runTest {
            val local = stubRunner("local")
            val registry = RunnerRegistry(listOf(local))
            registry.start(backgroundScope)

            val runner = registry.forAlias("local")
            assertEquals("local", runner.alias)
            val session =
                runner.openSession(
                    RunnerSessionParams(1, "demo", TestFixtures.mockWorkspaceConfig(), emptyMap(), Context.current()),
                )
            assertEquals("local", session.runnerAlias)
        }

    @Test
    fun `forAlias throws on unknown alias`() {
        val registry = RunnerRegistry(listOf(stubRunner("local")))
        assertThrows<IllegalStateException> { registry.forAlias("missing") }
    }

    @Test
    fun `forAgent uses the agent's filter and claim timeout override`() {
        val local = stubRunner("local")
        val k8s = stubRunner("k8s")
        val registry =
            RunnerRegistry(
                listOf(
                    RegisteredRunner(local),
                    RegisteredRunner(k8s, tags = setOf("linux")),
                ),
            )

        val byAlias = registry.forAgent(AgentFixtures.simpleAgent())
        assertEquals("local", byAlias.alias)

        val byTags = registry.forAgent(AgentFixtures.simpleAgent().copy(runnerTags = listOf("linux"), runnerClaimTimeoutSec = 7))
        assertEquals("tags:linux", byTags.alias)
        assertEquals(Duration.parse("7s"), (byTags as QueuedRunner).claimTimeout)
    }

    @Test
    fun `start registers one consumer per entry with its facets`() =
        runTest {
            val registry =
                RunnerRegistry(
                    listOf(
                        RegisteredRunner(stubRunner("local")),
                        RegisteredRunner(stubRunner("k8s"), tags = setOf("linux"), concurrency = 4),
                    ),
                )
            registry.start(backgroundScope)

            val byAlias = registry.queue.consumers().associateBy { it.alias }
            assertEquals(setOf("local", "k8s"), byAlias.keys)
            assertEquals(4, byAlias["k8s"]!!.concurrency)
            assertEquals(setOf("linux"), byAlias["k8s"]!!.tags)
            assertEquals(null, byAlias["local"]!!.concurrency)
        }

    @Test
    fun `start twice throws`() =
        runTest {
            val registry = RunnerRegistry(listOf(stubRunner("local")))
            registry.start(backgroundScope)

            assertThrows<IllegalStateException> { registry.start(backgroundScope) }
        }

    @Test
    fun `stop cancels the consumer jobs`() =
        runTest {
            val registry = RunnerRegistry(listOf(stubRunner("local")))
            val jobs = registry.start(backgroundScope)

            registry.stop()
            runCurrent()

            jobs.forEach { assertEquals(true, it.isCancelled) }
            assertEquals(emptyList(), registry.queue.consumers())
        }

    private fun domain(
        alias: String,
        queue: RunnerClaimQueue,
        allowedTags: List<String>? = null,
        clock: () -> Long = System::currentTimeMillis,
    ) = ExternalRunnerDomain(
        alias,
        RunnerConfig.External(token = "$alias-token", allowedTags = allowedTags, heartbeatTimeoutSec = 1),
        queue,
        clock = clock,
    )

    @Test
    fun `an external domain is an alias and a remote candidate for the tags it could serve`() {
        val queue = RunnerClaimQueue(maxPending = 10)
        val workers = domain("workers", queue, allowedTags = listOf("linux", "gpu"))
        val anything = domain("anything", queue)
        val registry =
            RunnerRegistry(listOf(RegisteredRunner(stubRunner("local"), tags = setOf("linux"))), listOf(workers, anything), queue)

        assertSame(workers, registry.get("workers"))
        assertEquals(setOf("local", "workers", "anything"), registry.aliases())
        assertEquals(true, registry.forAlias("workers").isRemote)

        fun tagged(vararg tags: String) = AgentFixtures.simpleAgent().copy(runnerTags = tags.toList())
        assertEquals(true, (registry.forAgent(tagged("gpu")) as QueuedRunner).isRemote, "workers allows gpu, so a candidate is remote")
        assertEquals(
            true,
            (registry.forAgent(tagged("windows")) as QueuedRunner).isRemote,
            "a domain without allowedTags could serve any tag",
        )

        val bounded = RunnerRegistry(listOf(RegisteredRunner(stubRunner("local"), tags = setOf("linux"))), listOf(workers), queue)
        assertEquals(
            true,
            (bounded.forAgent(tagged("linux")) as QueuedRunner).isRemote,
            "local and workers both could serve it; any remote candidate makes the run remote",
        )
        assertEquals(
            false,
            (bounded.forAgent(tagged("windows")) as QueuedRunner).isRemote,
            "no static runner has it and allowedTags excludes it",
        )
    }

    @Test
    fun `rejects a domain alias that collides with a static runner`() {
        val queue = RunnerClaimQueue(maxPending = 10)
        assertThrows<IllegalArgumentException> {
            RunnerRegistry(listOf(RegisteredRunner(stubRunner("workers"))), listOf(domain("workers", queue)), queue)
        }
    }

    @Test
    fun `start runs the heartbeat sweeper and stop ends it`() =
        runTest {
            var now = 1_000_000L
            val queue = RunnerClaimQueue(maxPending = 10)
            val workers = domain("workers", queue, clock = { now })
            val registry = RunnerRegistry(emptyList(), listOf(workers), queue, sweepInterval = 100.milliseconds)
            val inst = workers.register(RegisterInstanceRequest(name = "quiet"))
            registry.start(backgroundScope)

            now += 1_500
            testScheduler.advanceTimeBy(150)
            runCurrent()
            assertNull(workers.instance(inst.id), "the sweeper dropped the silent instance")

            registry.stop()
            val again = workers.register(RegisterInstanceRequest(name = "quiet-2"))
            now += 1_500
            testScheduler.advanceTimeBy(150)
            runCurrent()
            assertSame(again, workers.instance(again.id), "no sweeps after stop")
        }
}
