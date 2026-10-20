package homer.app.runners.ssh

import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.models.SshAuth
import org.apache.sshd.client.SshClient
import org.apache.sshd.core.CoreModuleProperties
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SshClientTimingTest {
    private fun sshConfig(
        idleTimeoutSeconds: Long? = null,
        heartbeatIntervalSeconds: Long? = 30,
    ) = RunnerConfig.Ssh(
        host = "h",
        user = "u",
        auth = SshAuth.Agent,
        remoteWorkspaceRoot = "/ws",
        idleTimeoutSeconds = idleTimeoutSeconds,
        heartbeatIntervalSeconds = heartbeatIntervalSeconds,
    )

    private fun appConfig(cfg: RunnerConfig.Ssh) =
        AppConfig(
            agentsSearchPath = "a",
            workspaceRootPath = "w",
            databasePath = "d",
            runners = mapOf("box" to cfg),
        )

    @Test
    fun `default config disables the idle timeout and sends 30s keepalives`() {
        SshClient.setUpDefaultClient().use { client ->
            applySshClientTiming(client, sshConfig())
            val idle: Duration = CoreModuleProperties.IDLE_TIMEOUT.get(client).orElseThrow()
            val heartbeat: Duration = CoreModuleProperties.HEARTBEAT_INTERVAL.get(client).orElseThrow()
            assertEquals(Duration.ZERO, idle)
            assertEquals(Duration.ofSeconds(30), heartbeat)
        }
    }

    @Test
    fun `explicit non-null values are applied verbatim`() {
        SshClient.setUpDefaultClient().use { client ->
            applySshClientTiming(client, sshConfig(idleTimeoutSeconds = 120, heartbeatIntervalSeconds = 5))
            val idle: Duration = CoreModuleProperties.IDLE_TIMEOUT.get(client).orElseThrow()
            val heartbeat: Duration = CoreModuleProperties.HEARTBEAT_INTERVAL.get(client).orElseThrow()
            assertEquals(Duration.ofSeconds(120), idle)
            assertEquals(Duration.ofSeconds(5), heartbeat)
        }
    }

    @Test
    fun `null heartbeat interval disables keepalives`() {
        SshClient.setUpDefaultClient().use { client ->
            applySshClientTiming(client, sshConfig(heartbeatIntervalSeconds = null))
            val heartbeat: Duration = CoreModuleProperties.HEARTBEAT_INTERVAL.get(client).orElseThrow()
            assertEquals(Duration.ZERO, heartbeat)
        }
    }

    @Test
    fun `config defaults leave the timing fields on their documented values`() {
        val ssh = sshConfig()
        assertEquals(null, ssh.idleTimeoutSeconds)
        assertEquals(30L, ssh.heartbeatIntervalSeconds)
        appConfig(ssh) // parse-time fields must also pass AppConfig validation
    }

    @Test
    fun `non-positive idle timeout is rejected naming the runner`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                appConfig(sshConfig(idleTimeoutSeconds = 0))
            }
        assertEquals(true, ex.message!!.contains("box"))
        assertEquals(true, ex.message!!.contains("idleTimeoutSeconds"))
    }

    @Test
    fun `non-positive heartbeat interval is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            appConfig(sshConfig(heartbeatIntervalSeconds = 0))
        }
    }

    @Test
    fun `keepalive no-reply bound is applied`() {
        SshClient.setUpDefaultClient().use { client ->
            applySshClientTiming(client, sshConfig())
            val noReplyMax: Int = CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.get(client).orElseThrow()
            assertEquals(3, noReplyMax)
        }
    }
}
