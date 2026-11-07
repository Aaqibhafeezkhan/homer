package homer.app.di

import homer.app.exceptions.ConfigurationException
import homer.app.fixtures.AgentFixtures
import homer.app.models.AppConfig
import homer.app.models.KubernetesWorkload
import homer.app.models.LocalRunnerConfig
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.SshAuth
import homer.app.runners.kubernetes.KubernetesRunner
import homer.app.runners.kubernetes.RunnerStateSink
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

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

    @Test
    fun `Pod mode builds no BatchV1Api`() {
        val cfg = RunnerConfig.Kubernetes(image = "ubuntu:24.04")

        assertNull(batchApiFor(cfg, ApiClient()))
    }

    @Test
    fun `Job mode builds one on the shared ApiClient`() {
        val cfg = RunnerConfig.Kubernetes(image = "ubuntu:24.04", workload = KubernetesWorkload.Job)
        val apiClient = ApiClient()
        withApiReadTimeout(apiClient)

        assertSame(apiClient, batchApiFor(cfg, apiClient)!!.apiClient)
        // Job mode's client is the runner's client, so it carries the same bound.
        assertEquals(30_000, batchApiFor(cfg, apiClient)!!.apiClient.readTimeout)
    }

    @Test
    fun `bounding the read timeout keeps the client in place, without turning on HTTP logging`() {
        val apiClient = ApiClient().setBasePath("https://k8s.example")
        val authentications = apiClient.authentications
        val interceptors = apiClient.httpClient.interceptors.size

        withApiReadTimeout(apiClient)

        assertEquals(30_000, apiClient.readTimeout)
        assertEquals("https://k8s.example", apiClient.basePath)
        assertSame(authentications, apiClient.authentications)
        assertEquals(interceptors, apiClient.httpClient.interceptors.size)
        assertFalse(apiClient.isDebugging, "the bound must not add HTTP logging, which would log the bearer token")
    }

    @Test
    fun `a kubernetes runner is built from its kubeconfig, in either workload mode`(
        @TempDir dir: Path,
    ) {
        val kubeconfig = Files.writeString(dir.resolve("kubeconfig"), KUBECONFIG).toString()
        val registry =
            buildRunnerRegistry(
                config(
                    mapOf(
                        "pod" to RunnerConfig.Kubernetes(image = "ubuntu:24.04", kubeconfig = kubeconfig),
                        "job" to
                            RunnerConfig.Kubernetes(
                                image = "ubuntu:24.04",
                                workload = KubernetesWorkload.Job,
                                kubeconfig = kubeconfig,
                            ),
                    ),
                ),
                stateSink,
                tracer,
                k8sInstanceId = "instance-1",
            )

        assertEquals(setOf("local", "pod", "job"), registry.aliases())
        val pod = assertIs<KubernetesRunner>(registry.get("pod"))
        val job = assertIs<KubernetesRunner>(registry.get("job"))
        // The bound must reach the client the built runner actually holds, not only the helper:
        // deleting the call in buildKubernetesRunner fails here, so the wiring cannot silently
        // revert to an unbounded read.
        assertEquals(30_000, pod.wiredApiClient().readTimeout)
        assertEquals(30_000, job.wiredApiClient().readTimeout)
    }

    /** The client the runner's REST APIs are built on, read off the private `api` for this wiring test. */
    private fun KubernetesRunner.wiredApiClient(): ApiClient {
        val field = KubernetesRunner::class.java.getDeclaredField("api")
        field.isAccessible = true
        return (field.get(this) as CoreV1Api).apiClient
    }

    private companion object {
        /** Enough of a kubeconfig for client-java to build a client from a file; never dialled. */
        val KUBECONFIG =
            """
            apiVersion: v1
            kind: Config
            clusters:
            - name: homer-test
              cluster:
                server: http://127.0.0.1:1
            contexts:
            - name: homer-test
              context:
                cluster: homer-test
                user: homer-test
            current-context: homer-test
            users:
            - name: homer-test
              user:
                token: test-token
            """.trimIndent()
    }
}
