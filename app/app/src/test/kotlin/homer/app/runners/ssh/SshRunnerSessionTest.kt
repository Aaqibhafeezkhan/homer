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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /** Like [shellExiting], but leaves [stderr] in the stdErr file the session passes in, as a real `rm` would. */
    private fun shellExitingWithStderr(
        code: Int,
        stderr: String,
    ): ShellExecutor {
        val shell = mockk<ShellExecutor>()
        coEvery { shell.cmd(any(), any(), any(), any(), any(), any(), any()) } answers {
            Files.writeString(arg(2), stderr)
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
    fun `cleanWorkspace warns with the root and the cap message when rm exceeds the output cap`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = mockk<ShellExecutor>()
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any(), any()) } throws
                OutputCapExceededException("Output cap exceeded: 10 bytes.")

            val warnings = captureWarnings(SshRunnerSession::class.java) { session(sync, shell = shell).cleanWorkspace() }

            coVerify { sync.removeRecursively("/remote/p1") }
            val warn = warnings.single { it.formattedMessage.contains("falling back to the SFTP walk") }
            assertTrue(warn.formattedMessage.contains("/remote/p1"), "the warning must name the root: ${warn.formattedMessage}")
            assertTrue(
                warn.formattedMessage.contains("Output cap exceeded: 10 bytes."),
                "the warning must carry the cap message: ${warn.formattedMessage}",
            )
        }

    @Test
    fun `cleanWorkspace warns with the root and the timeout when rm hits the executor timeout`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = mockk<ShellExecutor>()
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any(), any()) } throws
                CancellationException("Timeout reached. 10m.")

            val warnings = captureWarnings(SshRunnerSession::class.java) { session(sync, shell = shell).cleanWorkspace() }

            coVerify { sync.removeRecursively("/remote/p1") }
            val warn = warnings.single { it.formattedMessage.contains("falling back to the SFTP walk") }
            assertTrue(warn.formattedMessage.contains("/remote/p1"), "the warning must name the root: ${warn.formattedMessage}")
            assertTrue(
                warn.formattedMessage.contains("timed out after 10m"),
                "the warning must carry the timeout detail: ${warn.formattedMessage}",
            )
        }

    @Test
    fun `cleanWorkspace warns with the root and the failure when the exec channel dies`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = mockk<ShellExecutor>()
            coEvery { shell.cmd(any(), any(), any(), any(), any(), any(), any()) } throws IOException("channel closed")

            val warnings = captureWarnings(SshRunnerSession::class.java) { session(sync, shell = shell).cleanWorkspace() }

            coVerify { sync.removeRecursively("/remote/p1") }
            val warn = warnings.single { it.formattedMessage.contains("falling back to the SFTP walk") }
            assertTrue(warn.formattedMessage.contains("/remote/p1"), "the warning must name the root: ${warn.formattedMessage}")
            assertTrue(
                warn.formattedMessage.contains("failed — falling back") && warn.formattedMessage.contains("channel closed"),
                "the warning must carry the transport failure: ${warn.formattedMessage}",
            )
        }

    @Test
    fun `cleanWorkspace warns with the root, the exit code and the stderr tail when rm exits non-zero`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = shellExitingWithStderr(2, "rm: cannot remove '/remote/p1/x': Permission denied\n")

            val warnings = captureWarnings(SshRunnerSession::class.java) { session(sync, shell = shell).cleanWorkspace() }

            coVerify { sync.removeRecursively("/remote/p1") }
            val warn = warnings.single { it.formattedMessage.contains("falling back to the SFTP walk") }
            assertTrue(warn.formattedMessage.contains("/remote/p1"), "the warning must name the root: ${warn.formattedMessage}")
            assertTrue(warn.formattedMessage.contains("exited 2"), "the warning must carry the exit code: ${warn.formattedMessage}")
            assertTrue(
                warn.formattedMessage.contains("Permission denied"),
                "the warning must carry the stderr tail: ${warn.formattedMessage}",
            )
        }

    @Test
    fun `cleanWorkspace does not warn about a fallback when rm exits zero`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)

            val warnings = captureWarnings(SshRunnerSession::class.java) { session(sync, shell = shellExiting(0)).cleanWorkspace() }

            assertTrue(
                warnings.none { it.formattedMessage.contains("falling back to the SFTP walk") },
                "a clean rm must not report a fallback; got $warnings",
            )
            coVerify(exactly = 0) { sync.removeRecursively(any()) }
        }

    @Test
    fun `cleanWorkspace cuts the stderr tail in the warning at 500 characters`() =
        runTest {
            val sync = mockk<WorkspaceSync>(relaxed = true)
            val shell = shellExitingWithStderr(1, "e".repeat(600) + "TAIL")

            val warnings = captureWarnings(SshRunnerSession::class.java) { session(sync, shell = shell).cleanWorkspace() }

            val warn = warnings.single { it.formattedMessage.contains("falling back to the SFTP walk") }.formattedMessage
            val tail = warn.substringAfter("falling back to the SFTP walk: ")
            assertEquals("e".repeat(500), tail, "the stderr tail must be the first 500 trimmed characters: '$tail'")
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
