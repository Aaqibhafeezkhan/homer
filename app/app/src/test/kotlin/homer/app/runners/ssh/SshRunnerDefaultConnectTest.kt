package homer.app.runners.ssh

import homer.app.models.RunnerConfig
import homer.app.models.SshAuth
import org.apache.sshd.client.SshClient
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import kotlin.test.assertTrue

/** [SshRunner.defaultConnect] against an in-process server: key-file identity and the agent fallback. */
class SshRunnerDefaultConnectTest {
    private lateinit var client: SshClient

    @BeforeEach
    fun setup() {
        client = SshClient.setUpDefaultClient().also { it.start() }
    }

    @AfterEach
    fun teardown() {
        client.stop()
    }

    private fun cfg(
        port: Int,
        auth: SshAuth,
    ) = RunnerConfig.Ssh(
        host = "127.0.0.1",
        port = port,
        user = "test",
        auth = auth,
        strictHostKeyChecking = false,
        remoteWorkspaceRoot = "/unused",
        connectTimeoutSeconds = 5,
    )

    @Test
    fun `a key-file identity authenticates the session`(
        @TempDir tmp: Path,
    ) {
        val server =
            SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser"))
                publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
            }
        server.start()
        try {
            val keyFile = tmp.resolve("id_rsa")
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
            Files.newOutputStream(keyFile).use { out ->
                OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "test-key", null, out)
            }

            val session = SshRunner.defaultConnect(client, cfg(server.port, SshAuth.KeyFile(keyFile.toString())))

            session.use { assertTrue(it.isAuthenticated) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `agent auth registers no identity, so a password-only server refuses it`() {
        EmbeddedSshServer().use { server ->
            val port = server.start()

            assertThrows<Exception> { SshRunner.defaultConnect(client, cfg(port, SshAuth.Agent)) }
        }
    }
}
