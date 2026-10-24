package homer.app.di

import homer.app.exceptions.ConfigurationException
import homer.app.fixtures.AgentFixtures
import homer.app.models.AppConfig
import homer.app.models.LocalRunnerConfig
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.SshAuth
import homer.app.runners.kubernetes.RunnerStateSink
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class RunnerWiringTest {
    private val stateSink = RunnerStateSink { _, _ -> }
    private val tracer = OpenTelemetry.noop().getTracer("test")

    private fun config(
        runners: Map<String, RunnerConfig>,
        localRunner: LocalRunnerConfig = LocalRunnerConfig(),
    ) = AppConfig(
        agentsSearchPath = "/agents",
        workspaceRootPath = "/workspace",
        databasePath = "/homer.db",
        server = Server(localRunner = localRunner),
        runners = runners,
        sysEnvBackedRunnerEnvNames = mapOf("workers" to setOf("WORKER_SECRET")),
    )

    @Test
    fun `the registry holds the local runner and one domain per external runner, with its secret env names`() {
        val registry =
            buildRunnerRegistry(
                config(mapOf("workers" to RunnerConfig.External(token = "w".repeat(32)))),
                stateSink,
                tracer,
                k8sInstanceId = "",
            )

        assertEquals(setOf("local", "workers"), registry.aliases())
        assertEquals(listOf("workers"), registry.externalDomains.map { it.alias })
        assertEquals(setOf("WORKER_SECRET"), registry.externalDomains.single().sysEnvBackedEnvNames)
    }

    @Test
    fun `a disabled local runner is not registered and a run routed to it fails before it is queued`() {
        val registry =
            buildRunnerRegistry(
                config(
                    mapOf("workers" to RunnerConfig.External(token = "w".repeat(32), allowedTags = listOf("linux"))),
                    LocalRunnerConfig(enabled = false),
                ),
                stateSink,
                tracer,
                k8sInstanceId = "",
            )

        assertEquals(setOf("workers"), registry.aliases())
        val defaultRouted = assertThrows<ConfigurationException> { registry.forAgent(AgentFixtures.simpleAgent()) }
        assertEquals(
            "Configuration error: runner 'local' is disabled (server.localRunner.enabled: false); route the agent to another runner",
            defaultRouted.message,
        )
        // A runnerCmd step names its runner by alias: same refusal.
        assertThrows<ConfigurationException> { registry.forAlias("local") }
        // Tag-routed runs still reach the other runners.
        assertEquals("tags:linux", registry.forAgent(AgentFixtures.simpleAgent().copy(runnerTags = listOf("linux"))).alias)
    }

    @Test
    fun `an ssh runner with strict host key checking and no known_hosts file is refused`() {
        val ssh =
            RunnerConfig.Ssh(
                host = "build.example",
                user = "homer",
                auth = SshAuth.Agent,
                remoteWorkspaceRoot = "/srv/homer",
            )

        val e = assertThrows<IllegalArgumentException> { buildRunnerRegistry(config(mapOf("box" to ssh)), stateSink, tracer, "") }

        assertEquals("ssh runner 'box': strictHostKeyChecking=true requires knownHostsPath", e.message)
    }
}
