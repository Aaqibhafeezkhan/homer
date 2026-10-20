package homer.app.runners.ssh

import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils
import java.nio.file.Path

/**
 * Loads a private key file (PEM / OpenSSH format, optionally passphrase-encrypted)
 * and registers every key pair it contains on [session] for public-key auth.
 */
object SshKeyLoading {
    fun addKeyIdentity(
        session: ClientSession,
        privateKeyPath: String,
        passphrase: String?,
    ) {
        val passwordProvider = passphrase?.let { FilePasswordProvider.of(it) } ?: FilePasswordProvider.EMPTY
        val keyPairs =
            SecurityUtils
                .getKeyPairResourceParser()
                .loadKeyPairs(session, Path.of(privateKeyPath), passwordProvider)
        keyPairs.forEach { session.addPublicKeyIdentity(it) }
    }
}
