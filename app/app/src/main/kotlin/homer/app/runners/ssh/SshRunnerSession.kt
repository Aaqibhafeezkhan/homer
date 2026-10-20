package homer.app.runners.ssh

import homer.app.exceptions.OutputCapExceededException
import homer.app.infra.ShellExecutor
import homer.app.models.ExtraUploadSpec
import homer.app.models.assertSafeRemoteRoot
import homer.app.runners.DownloadMode
import homer.app.runners.RemotePathTranslator
import homer.app.runners.RunnerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.sshd.client.session.ClientSession
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/**
 * SSH mechanism primitives over an authenticated [session]: SFTP transfer
 * via [WorkspaceSync], remote paths resolved against [remoteProjectRoot].
 * Sync policy (what/when) lives in AgentExecutor — see ADR-0033.
 */
class SshRunnerSession(
    private val session: ClientSession,
    override val shell: ShellExecutor,
    private val sync: WorkspaceSync,
    private val remoteWorkspaceRoot: String,
    private val remoteProjectRoot: String,
    private val remoteWorkdir: String,
    private val translator: RemotePathTranslator,
    private val initialEnv: Map<String, String>,
    private val syncExcludes: List<String>,
    override val runnerAlias: String,
    override val extraUploads: List<ExtraUploadSpec> = emptyList(),
) : RunnerSession {
    private val closed = AtomicBoolean(false)

    override suspend fun createWorkspace(): Map<String, String> =
        withContext(Dispatchers.IO) {
            sync.mkdirs(remoteProjectRoot)
            // The session shell cd's into remoteWorkdir on every command; a
            // runnerCmd check may run before anything is uploaded, so the
            // directory must exist as part of workspace creation.
            sync.mkdirs(remoteWorkdir)
            translator.overrides(initialEnv) +
                extraUploads.mapNotNull { spec -> spec.env?.let { it to "$remoteProjectRoot/${spec.remote}" } }
        }

    override suspend fun upload(
        local: Path,
        remoteRelative: String,
        excludes: List<String>,
    ) = withContext(Dispatchers.IO) {
        sync.upload(local, remotePath(remoteRelative), syncExcludes + excludes)
    }

    override suspend fun download(
        remoteRelative: String,
        local: Path,
        mode: DownloadMode,
    ) = withContext(Dispatchers.IO) {
        sync.download(remotePath(remoteRelative), local, mode = mode)
    }

    /**
     * One `rm -rf` over the exec channel; the SFTP walk (one readDir/remove round
     * trip per file, serialized on the session) only as fallback on a non-zero
     * exit, a transport error, the command's own timeout or an output-cap
     * abort. The shell cd's into
     * [remoteWorkdir], inside the tree — removing a running process's cwd is
     * fine on POSIX. See ADR-0067.
     */
    override suspend fun cleanWorkspace() =
        withContext(Dispatchers.IO) {
            // [security_misconfiguration / CWE-732] The composed root is the one string
            // that reaches `rm -rf`; a value that is not an absolute, normalized path at
            // least two segments deep is refused outright — no rm, no walk (ADR-0067).
            assertSafeRemoteRoot("remote project root", remoteProjectRoot, minDepth = 2)
            assertNoSymlinkComponentsBelowRoot()
            if (!removeViaShell()) sync.removeRecursively(remoteProjectRoot)
        }

    /**
     * [path_traversal / CWE-59] `rm -rf` resolves every component of its argument,
     * so a symlinked component under [remoteWorkspaceRoot] would steer the sweep
     * at the link's target; each component below the root is `lstat`ed and a link
     * refuses the sweep outright. See ADR-0067 for the scope and accepted race.
     */
    private suspend fun assertNoSymlinkComponentsBelowRoot() {
        val root = remoteWorkspaceRoot.trimEnd('/')
        if (!remoteProjectRoot.startsWith("$root/")) {
            throw SecurityException("remote project root '$remoteProjectRoot' is not below remoteWorkspaceRoot '$root'")
        }
        var current = root
        for (segment in remoteProjectRoot.removePrefix("$root/").split('/')) {
            current = "$current/$segment"
            if (sync.isSymlink(current)) {
                throw SecurityException("remote path component '$current' is a symlink; refusing to remove '$remoteProjectRoot'")
            }
        }
    }

    @Suppress("SuspendFunSwallowedCancellation") // rethrows a caller's cancellation; the shell's own timeout falls back
    private suspend fun removeViaShell(): Boolean {
        val out = Files.createTempFile("homer-ssh-clean", ".out")
        val err = Files.createTempFile("homer-ssh-clean", ".err")
        return try {
            val result =
                shell.cmd(
                    command = listOf("rm", "-rf", "--", remoteProjectRoot),
                    stdOut = out,
                    stdErr = err,
                    timeout = CLEAN_TIMEOUT,
                )
            if (result.exitCode != 0) {
                logger.warn(
                    "rm -rf {} exited {} — falling back to the SFTP walk: {}",
                    remoteProjectRoot,
                    result.exitCode,
                    runCatching { Files.readString(err).trim().take(500) }.getOrDefault(""),
                )
            }
            result.exitCode == 0
        } catch (e: OutputCapExceededException) {
            logger.warn("rm -rf {} exceeded the output cap — falling back to the SFTP walk: {}", remoteProjectRoot, e.message)
            false
        } catch (e: CancellationException) {
            // SshShellExecutor signals its own timeout with a plain CancellationException;
            // only a cancelled caller propagates. Cleanup runs NonCancellable, so in
            // practice this is the timeout, and the walk is the documented fallback.
            if (!currentCoroutineContext().isActive) throw e
            logger.warn("rm -rf {} timed out after {} — falling back to the SFTP walk", remoteProjectRoot, CLEAN_TIMEOUT)
            false
        } catch (e: Exception) {
            logger.warn("rm -rf {} failed — falling back to the SFTP walk: {}", remoteProjectRoot, e.message)
            false
        } finally {
            runCatching { Files.deleteIfExists(out) }
            runCatching { Files.deleteIfExists(err) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { session.close() }
    }

    private fun remotePath(relative: String): String =
        if (relative == "." || relative.isEmpty()) remoteProjectRoot else "$remoteProjectRoot/${relative.trimStart('/')}"

    companion object {
        private val logger = LoggerFactory.getLogger(SshRunnerSession::class.java)

        /** Generous for an `rm -rf`; the fallback walk shares one transfer-deadline window (ADR-0064). */
        private val CLEAN_TIMEOUT = 10.minutes
    }
}
