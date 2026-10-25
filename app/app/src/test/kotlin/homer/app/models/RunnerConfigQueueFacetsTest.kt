package homer.app.models

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunnerConfigQueueFacetsTest {
    private val yaml = Yaml.default

    private fun ssh(extra: String) =
        yaml.decodeFromString(
            RunnerConfig.serializer(),
            """!<ssh>
host: h
user: u
auth: !<agent> {}
remoteWorkspaceRoot: /ws
$extra""",
        )

    private fun k8s(extra: String) =
        yaml.decodeFromString(
            RunnerConfig.serializer(),
            """!<kubernetes>
image: ubuntu:24.04
$extra""",
        )

    @Test
    fun `ssh and kubernetes default to no tags and unbounded concurrency`() {
        val s = assertIs<RunnerConfig.Ssh>(ssh(""))
        assertEquals(emptyList(), s.tags)
        assertNull(s.concurrency)
        val k = assertIs<RunnerConfig.Kubernetes>(k8s(""))
        assertEquals(emptyList(), k.tags)
        assertNull(k.concurrency)
    }

    @Test
    fun `ssh and kubernetes parse tags and concurrency`() {
        val s = assertIs<RunnerConfig.Ssh>(ssh("tags: [linux, docker]\nconcurrency: 2"))
        assertEquals(listOf("linux", "docker"), s.tags)
        assertEquals(2, s.concurrency)
        val k = assertIs<RunnerConfig.Kubernetes>(k8s("tags: [linux]\nconcurrency: 4"))
        assertEquals(listOf("linux"), k.tags)
        assertEquals(4, k.concurrency)
    }

    @Test
    fun `AppConfig rejects a malformed runner tag naming the alias`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                AppConfig(
                    server = Server(apiAuth = AuthConfig.Unsecured),
                    agentsSearchPath = "/a",
                    workspaceRootPath = "/w",
                    databasePath = "/d",
                    runners = mapOf("k8s-dev" to RunnerConfig.Kubernetes(image = "img", tags = listOf("bad tag"))),
                )
            }
        assertTrue(ex.message!!.contains("runner 'k8s-dev'"), ex.message)
    }

    @Test
    fun `AppConfig rejects concurrency below 1 naming the alias`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                AppConfig(
                    server = Server(apiAuth = AuthConfig.Unsecured),
                    agentsSearchPath = "/a",
                    workspaceRootPath = "/w",
                    databasePath = "/d",
                    runners =
                        mapOf(
                            "build-host" to
                                RunnerConfig.Ssh(
                                    host = "h",
                                    user = "u",
                                    auth = SshAuth.Agent,
                                    remoteWorkspaceRoot = "/ws",
                                    concurrency = 0,
                                ),
                        ),
                )
            }
        assertTrue(ex.message!!.contains("runner 'build-host'"), ex.message)
        assertTrue(ex.message!!.contains("concurrency"), ex.message)
    }

    @Test
    fun `LocalRunnerConfig validates its own tags and concurrency`() {
        assertEquals(emptyList(), LocalRunnerConfig().tags)
        assertNull(LocalRunnerConfig().concurrency)
        val ex = assertThrows<IllegalArgumentException> { LocalRunnerConfig(concurrency = 0) }
        assertTrue(ex.message!!.contains("server.localRunner"), ex.message)
        assertThrows<IllegalArgumentException> { LocalRunnerConfig(tags = listOf("no spaces")) }
    }

    @Test
    fun `the local runner is enabled unless the config turns it off`() {
        assertTrue(LocalRunnerConfig().enabled)
        val off = yaml.decodeFromString(LocalRunnerConfig.serializer(), "enabled: false")
        assertEquals(LocalRunnerConfig(enabled = false), off)
    }

    @Test
    fun `a disabled local runner refuses tags and concurrency it would never use`() {
        val withTags = assertThrows<IllegalArgumentException> { LocalRunnerConfig(enabled = false, tags = listOf("linux")) }
        assertTrue(withTags.message!!.contains("server.localRunner.enabled is false"), withTags.message)
        assertThrows<IllegalArgumentException> { LocalRunnerConfig(enabled = false, concurrency = 2) }
    }
}
