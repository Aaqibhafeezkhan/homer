package homer.app.runners.ssh

import homer.app.EnvConstants
import homer.app.exceptions.OutputCapExceededException
import homer.app.infra.ShellExecutor
import homer.app.models.CommandResult
import homer.app.models.ExtraUploadSpec
import homer.app.runners.DownloadMode
import homer.app.runners.RemotePathTranslator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.apache.sshd.client.session.ClientSession
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SshRunnerSessionTest {
    private val clientSession = mockk<ClientSession>(relaxed = true)

    private fun session(
        sync: WorkspaceSync,
        excludes: List<String> = emptyList(),
        extras: List<ExtraUploadSpec> = emptyList(),
        shell: ShellExecutor = mockk(relaxed = true),
        remoteProjectRoot: String = "/remote/p1",
    ): SshRunnerSession =
        SshRunnerSession(
            session = clientSession,
            shell = shell,
            sync = sync,
            remoteWorkspaceRoot = "/remote",
            remoteProjectRoot = remoteProjectRoot,
            remoteWorkdir = "$remoteProjectRoot/workdir",
            translator =
                RemotePathTranslator(
                    serverProjectRoot = "/local/p1",
                    remoteProjectRoot = "/remote/p1",
                    remoteAgentDir = "/remote/p1/.homer/agent",
                    apiUrl = null,
                ),
            initialEnv = mapOf(EnvConstants.WORK_DIR to "/local/p1/workdir"),
            syncExcludes = excludes,
            runnerAlias = "ssh-box",
            extraUploads = extras,
        )

    @Test
    fun `createWorkspace mkdirs remote root and workdir and returns translated overrides`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val overrides = session(sync).createWorkspace()
            coVerify { sync.mkdirs("/remote/p1") }
            coVerify { sync.mkdirs("/remote/p1/workdir") }
            assertEquals("/remote/p1/workdir", overrides[EnvConstants.WORK_DIR])
            assertEquals("/remote/p1/.homer/agent", overrides[EnvConstants.AGENT_DIR])
        }

    @Test
    fun `upload resolves remote-relative path and applies excludes`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            session(sync, excludes = listOf("node_modules")).upload(Path.of("/local/p1"), ".")
            coVerify { sync.upload(Path.of("/local/p1"), "/remote/p1", listOf("node_modules")) }
        }

    @Test
    fun `download resolves remote-relative path with the default OVERLAY mode`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            session(sync).download("artifacts", Path.of("/local/p1/artifacts"))
            coVerify { sync.download("/remote/p1/artifacts", Path.of("/local/p1/artifacts"), mode = DownloadMode.OVERLAY) }
        }

    @Test
    fun `download threads OVERWRITE through to WorkspaceSync`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            session(sync).download("workdir/out.bin", Path.of("/local/p1/workdir/out.bin"), mode = DownloadMode.OVERWRITE)
            coVerify {
                sync.download("/remote/p1/workdir/out.bin", Path.of("/local/p1/workdir/out.bin"), mode = DownloadMode.OVERWRITE)
            }
        }

    @Test
    fun `download threads MIRROR through to WorkspaceSync`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            session(sync).download("workdir", Path.of("/local/p1/workdir"), mode = DownloadMode.MIRROR)
            coVerify { sync.download("/remote/p1/workdir", Path.of("/local/p1/workdir"), mode = DownloadMode.MIRROR) }
        }

    private fun shellExiting(code: Int): ShellExecutor {
        val shell = mockk<ShellExecutor>()
        coEvery { shell.cmd(any(), any(), any(), any(), any(), any(), any()) } answers {
            CommandResult(exitCode = code, stdOut = arg(1), stdErr = arg(2))
        }
        return shell
    }

    @Test
    fun `cleanWorkspace removes the remote root with one rm -rf and skips the SFTP walk`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = shellExiting(0)
            session(sync, shell = shell).cleanWorkspace()
            coVerify { shell.cmd(listOf("rm", "-rf", "--", "/remote/p1"), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { sync.removeRecursively(any()) }
        }

    @Test
    fun `cleanWorkspace falls back to the SFTP walk when rm exits non-zero`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            session(sync, shell = shellExiting(1)).cleanWorkspace()
            coVerify { sync.removeRecursively("/remote/p1") }
        }

    @Test
    fun `cleanWorkspace falls back to the SFTP walk when the exec channel fails`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = mockk<ShellExecutor>()
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any(), any()) } throws IOException("channel closed")
            session(sync, shell = shell).cleanWorkspace()
            coVerify { sync.removeRecursively("/remote/p1") }
        }

    @Test
    fun `cleanWorkspace falls back to the SFTP walk when rm hits the executor timeout`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = mockk<ShellExecutor>()
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any(), any()) } throws
                CancellationException("Timeout reached. 10m.")
            session(sync, shell = shell).cleanWorkspace()
            coVerify { sync.removeRecursively("/remote/p1") }
        }

    @Test
    fun `cleanWorkspace falls back to the SFTP walk when rm exceeds the output cap`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = mockk<ShellExecutor>()
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any(), any()) } throws
                OutputCapExceededException("Output cap exceeded: 10 bytes.")
            session(sync, shell = shell).cleanWorkspace()
            coVerify { sync.removeRecursively("/remote/p1") }
        }

    @Test
    fun `cleanWorkspace refuses an unsafe remote root without running rm or the walk`() =
        runTest {
            // [security_misconfiguration / CWE-732]: a blank or too-shallow root must
            // never reach `rm -rf`, and the fallback must not delete it either.
            for (root in listOf("/", "/remote", "/remote/../p1", "/remote//p1", "remote/p1", "")) {
                val sync = mockk<WorkspaceSync>(relaxed = true)
                val shell = mockk<ShellExecutor>(relaxed = true)
                assertFailsWith<IllegalArgumentException>(root) {
                    session(sync, shell = shell, remoteProjectRoot = root).cleanWorkspace()
                }
                coVerify(exactly = 0) { shell.cmd(any(), any(), any(), any(), any(), any(), any()) }
                coVerify(exactly = 0) { sync.removeRecursively(any()) }
            }
        }

    @Test
    fun `cleanWorkspace lstats every component below the workspace root and refuses a symlink`() =
        runTest {
            // [path_traversal / CWE-59]: a component swapped for a link would steer rm -rf
            // at the link's target; refused before rm and before the walk.
            val sync = mockk<WorkspaceSync>(relaxed = true)
            coEvery { sync.isSymlink("/remote/runs/p1") } returns true
            val shell = mockk<ShellExecutor>(relaxed = true)
            assertFailsWith<SecurityException> {
                session(sync, shell = shell, remoteProjectRoot = "/remote/runs/p1").cleanWorkspace()
            }
            coVerify { sync.isSymlink("/remote/runs") }
            coVerify { sync.isSymlink("/remote/runs/p1") }
            coVerify(exactly = 0) { shell.cmd(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { sync.removeRecursively(any()) }
        }

    @Test
    fun `cleanWorkspace refuses a project root that is not below the workspace root`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = mockk<ShellExecutor>(relaxed = true)
            assertFailsWith<SecurityException> {
                session(sync, shell = shell, remoteProjectRoot = "/elsewhere/p1").cleanWorkspace()
            }
            coVerify(exactly = 0) { shell.cmd(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { sync.removeRecursively(any()) }
        }

    @Test
    fun `close closes the connection once`() {
        val sync = mockk<WorkspaceSync>(relaxed = true)
        val s = session(sync)
        s.close()
        s.close()
        verify(exactly = 1) { clientSession.close() }
    }

    @Test
    fun `createWorkspace repoints extraUploads env vars at the remote copies`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val overrides =
                session(
                    sync,
                    extras =
                        listOf(
                            ExtraUploadSpec("/app/scripts", ".homer/scripts", env = "SCRIPTS_DIR"),
                            ExtraUploadSpec("/app/prompts", ".homer/shared-prompts"),
                        ),
                ).createWorkspace()
            assertEquals("/remote/p1/.homer/scripts", overrides["SCRIPTS_DIR"])
            // entries without env contribute no override; HMR_* translation intact
            assertEquals("/remote/p1/workdir", overrides[EnvConstants.WORK_DIR])
            assertFalse(overrides.values.any { it.endsWith(".homer/shared-prompts") })
        }

    @Test
    fun `session without extraUploads exposes an empty list`() {
        val sync = mockk<WorkspaceSync>(relaxed = true)
        assertEquals(emptyList(), session(sync).extraUploads)
    }
}
