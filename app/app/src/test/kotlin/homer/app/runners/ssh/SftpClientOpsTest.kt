package homer.app.runners.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SftpClientOpsTest {
    @Test
    fun `a missing path does not exist and any other stat failure propagates`() {
        val sftp = mockk<SftpClient>()
        every { sftp.stat("/here") } returns mockk()
        every { sftp.stat("/gone") } throws SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "no such file")
        every { sftp.stat("/denied") } throws SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "denied")

        assertTrue(remoteExists(sftp, "/here"))
        assertFalse(remoteExists(sftp, "/gone"))
        assertThrows<SftpException> { remoteExists(sftp, "/denied") }
    }

    @Test
    fun `mkdirs tolerates segments that already are directories and surfaces a real failure`() {
        val sftp = mockk<SftpClient>(relaxed = true)
        every { sftp.mkdir("/a") } throws SftpException(SftpConstants.SSH_FX_FAILURE, "exists")
        every { sftp.stat("/a") } returns mockk { every { isDirectory } returns true }

        sftp.mkdirsIdempotent("/a/b/")
        verify { sftp.mkdir("/a/b") }

        every { sftp.mkdir("/x") } throws SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "denied")
        every { sftp.stat("/x") } throws SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "no such file")
        assertThrows<SftpException> { sftp.mkdirsIdempotent("/x/y") }
    }
}
