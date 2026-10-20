package homer.app.runners

import homer.app.fixtures.TestFixtures
import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.models.RunnerFilter
import homer.app.models.RunnerFilterView
import homer.app.models.RunnerQueueGroup
import homer.app.models.RunnerQueueStatusResponse
import homer.app.models.SshAuth
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.queue.PendingClaim
import homer.app.runners.queue.RunnerClaimQueue
import homer.app.runners.queue.StaticRunnerConsumer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.context.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class RunnerStatusReportTest {
    private fun params() = RunnerSessionParams(1, "demo", TestFixtures.mockWorkspaceConfig(), emptyMap(), Context.current())

    private fun sshRunnerConfig(
        tags: List<String> = emptyList(),
        concurrency: Int? = null,
    ) = RunnerConfig.Ssh(
        host = "h",
        user = "u",
        auth = SshAuth.Agent,
        remoteWorkspaceRoot = "/remote",
        tags = tags,
        concurrency = concurrency,
    )

    private fun appConfig(runners: Map<String, RunnerConfig>) =
        AppConfig(agentsSearchPath = "unused", workspaceRootPath = "unused", databasePath = "unused", runners = runners)

    @Test
    fun `lists local first then configured runners in config order with kind, sorted tags and concurrency`() {
        val queue = RunnerClaimQueue(maxPending = 10)
        val domain = ExternalRunnerDomain("workers", RunnerConfig.External(token = "t".repeat(32)), queue)
        val config =
            appConfig(
                mapOf(
                    "ssh-a" to sshRunnerConfig(tags = listOf("b", "a"), concurrency = 2),
                    "workers" to RunnerConfig.External(token = "t".repeat(32)),
                ),
            )
        val registry = RunnerRegistry(entries = emptyList(), externalDomains = listOf(domain), queue = queue)

        val status = runnersStatus(config, registry)

        assertEquals(listOf("local", "ssh-a", "workers"), status.runners.map { it.alias })
        assertEquals(listOf("local", "ssh", "external"), status.runners.map { it.kind })
        val sshA = status.runners.first { it.alias == "ssh-a" }
        assertEquals(listOf("a", "b"), sshA.tags)
        assertEquals(2, sshA.concurrency)
        val workers = status.runners.first { it.alias == "workers" }
        assertEquals(null, workers.concurrency)
        assertEquals(emptyList(), workers.tags)
    }

    @Test
    fun `a static runner with no registered consumer reports zero in use`() {
        val queue = RunnerClaimQueue(maxPending = 10)
        val config = appConfig(mapOf("ssh-a" to sshRunnerConfig()))
        val registry = RunnerRegistry(entries = emptyList(), queue = queue)

        val status = runnersStatus(config, registry)

        assertEquals(0, status.runners.first { it.alias == "ssh-a" }.inUse)
    }

    @Test
    fun `a kubernetes runner is listed with its kind, sorted tags, concurrency and consumer inUse`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val runner = mockk<Runner>()
            every { runner.alias } returns "k8s"
            coEvery { runner.openSession(any()) } returns mockk(relaxed = true)
            StaticRunnerConsumer(runner, tags = setOf("linux", "gpu"), concurrency = 3, queue = queue).start(backgroundScope)

            async { queue.awaitClaim(params(), RunnerFilter.Alias("k8s"), 300.seconds) }
            runCurrent()

            val config =
                appConfig(
                    mapOf("k8s" to RunnerConfig.Kubernetes(image = "ubuntu:24.04", tags = listOf("linux", "gpu"), concurrency = 3)),
                )
            val k8s = runnersStatus(config, RunnerRegistry(entries = emptyList(), queue = queue)).runners.first { it.alias == "k8s" }

            assertEquals("kubernetes", k8s.kind)
            assertEquals(listOf("gpu", "linux"), k8s.tags)
            assertEquals(3, k8s.concurrency)
            assertEquals(1, k8s.inUse)
            assertEquals(null, k8s.instances)
        }

    @Test
    fun `static inUse comes from the registered consumer`() =
        runTest {
            val queue = RunnerClaimQueue(maxPending = 10)
            val runner = mockk<Runner>()
            every { runner.alias } returns "ssh-a"
            coEvery { runner.openSession(any()) } returns mockk(relaxed = true)
            StaticRunnerConsumer(runner, tags = emptySet(), concurrency = 1, queue = queue).start(backgroundScope)

            async { queue.awaitClaim(params(), RunnerFilter.Alias("ssh-a"), 300.seconds) }
            runCurrent()

            val config = appConfig(mapOf("ssh-a" to sshRunnerConfig()))
            val registry = RunnerRegistry(entries = emptyList(), queue = queue)

            assertEquals(1, runnersStatus(config, registry).runners.first { it.alias == "ssh-a" }.inUse)
        }

    @Test
    fun `external domain lists its live instances with seconds timestamps`() {
        val now = 1_700_000_123_456L
        val queue = RunnerClaimQueue(maxPending = 10)
        val domain = ExternalRunnerDomain("workers", RunnerConfig.External(token = "t".repeat(32)), queue, clock = { now })
        domain.register(RegisterInstanceRequest(name = "host-1", tags = listOf("gpu", "linux"), concurrency = 2))
        val config = appConfig(mapOf("workers" to RunnerConfig.External(token = "t".repeat(32))))
        val registry = RunnerRegistry(entries = emptyList(), externalDomains = listOf(domain), queue = queue)

        val workers = runnersStatus(config, registry).runners.first { it.alias == "workers" }

        assertEquals(0, workers.inUse)
        assertEquals(1, workers.instances?.size)
        val instance = workers.instances!!.single()
        assertEquals(1_700_000_123L, instance.registeredAt)
        assertEquals(1_700_000_123L, instance.lastSeenAt)
        assertEquals(listOf("gpu", "linux"), instance.tags)
        assertEquals(2, instance.concurrency)
        assertEquals(0, instance.activeSessions)
        assertFalse(instance.draining)
    }

    @Test
    fun `a draining instance with a live session is listed as draining`() {
        val queue = RunnerClaimQueue(maxPending = 10)
        val domain = ExternalRunnerDomain("workers", RunnerConfig.External(token = "t".repeat(32)), queue)
        val instance = domain.register(RegisterInstanceRequest(name = "host-1"))
        instance.sessionOpened()
        domain.deregister(instance.id)
        val config = appConfig(mapOf("workers" to RunnerConfig.External(token = "t".repeat(32))))
        val registry = RunnerRegistry(entries = emptyList(), externalDomains = listOf(domain), queue = queue)

        val workers = runnersStatus(config, registry).runners.first { it.alias == "workers" }

        assertEquals(1, workers.inUse)
        val listed = workers.instances!!.single()
        assertEquals(true, listed.draining)
        assertEquals(1, listed.activeSessions)
    }

    @Test
    fun `runnerType maps every backend and an unconfigured alias`() {
        val config =
            appConfig(
                mapOf(
                    "ssh-a" to sshRunnerConfig(),
                    "k8s" to RunnerConfig.Kubernetes(image = "ubuntu:24.04"),
                    "workers" to RunnerConfig.External(token = "t".repeat(32)),
                ),
            )

        assertEquals("local", runnerType(config, "local"))
        assertEquals("ssh", runnerType(config, "ssh-a"))
        assertEquals("kubernetes", runnerType(config, "k8s"))
        assertEquals("external", runnerType(config, "workers"))
        assertEquals("unknown", runnerType(config, "gone"))
    }

    @Test
    fun `queue status is empty with null oldest age when nothing is pending`() {
        assertEquals(
            RunnerQueueStatusResponse(pending = 0, oldestAgeSec = null, byFilter = emptyList()),
            runnerQueueStatus(emptyList(), 10_000),
        )
    }

    @Test
    fun `queue status groups by filter, oldest group first`() {
        val pending =
            listOf(
                PendingClaim(RunnerFilter.Alias("a"), enqueuedAtMs = 1_000),
                PendingClaim(RunnerFilter.Tags(setOf("gpu", "linux")), enqueuedAtMs = 3_000),
                PendingClaim(RunnerFilter.Alias("a"), enqueuedAtMs = 5_000),
            )

        val status = runnerQueueStatus(pending, nowMs = 11_000)

        assertEquals(3, status.pending)
        assertEquals(10, status.oldestAgeSec)
        assertEquals(
            listOf(
                RunnerQueueGroup(filter = RunnerFilterView(alias = "a"), pending = 2, oldestAgeSec = 10),
                RunnerQueueGroup(filter = RunnerFilterView(tags = listOf("gpu", "linux")), pending = 1, oldestAgeSec = 8),
            ),
            status.byFilter,
        )
    }

    @Test
    fun `ages are never negative`() {
        val pending = listOf(PendingClaim(RunnerFilter.Alias("a"), enqueuedAtMs = 20_000))

        val status = runnerQueueStatus(pending, nowMs = 10_000)

        assertEquals(0, status.oldestAgeSec)
        assertEquals(0, status.byFilter.single().oldestAgeSec)
    }
}
