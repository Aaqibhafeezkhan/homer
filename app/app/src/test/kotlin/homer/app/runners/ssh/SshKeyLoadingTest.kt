package homer.app.runners.ssh

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class SshKeyLoadingTest {
    private lateinit var server: EmbeddedSshServer
    private lateinit var client: SshClient
    private lateinit var session: ClientSession

    @BeforeEach
    fun setup() {
        server = EmbeddedSshServer()
        val port = server.start()
        client = SshClient.setUpDefaultClient().also { it.start() }
        // Connected but not authenticated: addKeyIdentity only registers an
        // identity, it does not need a completed handshake.
        session = client.connect("test", server.host, port).verify(5, TimeUnit.SECONDS).session
    }

    @AfterEach
    fun teardown() {
        runCatching { session.close() }
        client.stop()
        server.close()
    }

    private fun writeKey(
        path: Path,
        keyPair: KeyPair,
        passphrase: String?,
    ) {
        val encryption =
            passphrase?.let {
                OpenSSHKeyEncryptionContext().apply {
                    password = it
                    cipherName = OpenSSHKeyEncryptionContext.AES
                    cipherType = "256"
                }
            }
        Files.newOutputStream(path).use { out ->
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "test-key", encryption, out)
        }
    }

    private fun registeredKeyPair(): KeyPair? =
        session.registeredIdentities
            .loadIdentities(session)
            .filterIsInstance<KeyPair>()
            .singleOrNull()

    @Test
    fun `addKeyIdentity registers an unencrypted key pair on the session`(
        @TempDir tmp: Path,
    ) {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val keyFile = tmp.resolve("id_rsa")
        writeKey(keyFile, keyPair, passphrase = null)

        SshKeyLoading.addKeyIdentity(session, keyFile.toString(), passphrase = null)

        val loaded = registeredKeyPair()
        assertTrue(loaded != null && loaded.public.encoded.contentEquals(keyPair.public.encoded))
    }

    @Test
    fun `addKeyIdentity registers a passphrase-encrypted key pair on the session`(
        @TempDir tmp: Path,
    ) {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val keyFile = tmp.resolve("id_rsa_enc")
        writeKey(keyFile, keyPair, passphrase = "s3cret")

        SshKeyLoading.addKeyIdentity(session, keyFile.toString(), passphrase = "s3cret")

        val loaded = registeredKeyPair()
        assertTrue(loaded != null && loaded.public.encoded.contentEquals(keyPair.public.encoded))
    }
}
