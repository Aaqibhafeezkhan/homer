package homer.app.runners.ssh

import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException

/** Whether [path] exists on the peer; any failure other than no-such-file/path propagates. */
internal fun remoteExists(
    sftp: SftpClient,
    path: String,
): Boolean =
    try {
        sftp.stat(path)
        true
    } catch (e: SftpException) {
        if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE || e.status == SftpConstants.SSH_FX_NO_SUCH_PATH) {
            false
        } else {
            throw e
        }
    }

/** `mkdir -p` over SFTP: a segment that already exists as a directory is fine, any other failure surfaces. */
internal fun SftpClient.mkdirsIdempotent(path: String) {
    val parts = path.trimEnd('/').split("/").filter { it.isNotEmpty() }
    var acc = if (path.startsWith("/")) "" else "."
    for (p in parts) {
        acc = "$acc/$p"
        try {
            mkdir(acc)
        } catch (e: SftpException) {
            // Benign "already exists" if the segment is now a directory; otherwise
            // this was a real failure (permission/quota/etc) and must surface.
            val isDir = runCatching { stat(acc).isDirectory }.getOrDefault(false)
            if (!isDir) throw e
        }
    }
}
