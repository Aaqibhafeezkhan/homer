package homer.app.runners.kubernetes

import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Workspace transfer into a pod over the exec channel: one gzip'd tar
 * stream per direction (the `kubectl cp` mechanism), so many small files
 * cost one round-trip. Requires `tar` + `gzip` in the image (documented
 * in SERVER.md). Semantics mirror the ssh WorkspaceSync: dir uploads copy
 * contents, excludes match entry names at any depth (uploads only) unless
 * prefixed with `/`, which anchors the exclude to the upload root;
 * missing remote downloads are silent no-ops. Downloads honor a
 * [DownloadMode] (OVERLAY/OVERWRITE/MIRROR) — see ADR-0043 for the
 * transfer mechanism and ADR-0055 for the mirror/prune semantics.
 */
class KubernetesWorkspaceSync(
    private val execClient: KubernetesExecClient,
    private val namespace: String,
    private val podName: String,
) {
    fun mkdirs(remoteDir: String) = runToExit(listOf("mkdir", "-p", remoteDir))

    /**
     * Uploads [local] (file or directory) to [remote] in the pod. Directory uploads copy
     * contents, matched against [excludes] as described in the class doc. [omitDirEntries]
     * holds root-relative directory paths (e.g. `"workdir"`) whose own tar directory entry
     * is skipped while its contents still upload: the pod pre-creates such directories as
     * kubelet-owned subPath mounts, and GNU tar's directory-entry restore (chmod/utime)
     * fails there under `runAsNonRoot`; omitting just the entry lets tar write files into
     * the directory without ever touching the directory itself. See ADR-0054.
     */
    fun upload(
        local: Path,
        remote: String,
        excludes: List<String> = emptyList(),
        omitDirEntries: Set<String> = emptySet(),
    ) {
        val (destDir, entryName) =
            when {
                local.isDirectory() -> remote to null
                local.isRegularFile() -> (remote.substringBeforeLast('/', "").ifEmpty { "." }) to remote.substringAfterLast('/')
                else -> error("upload source does not exist: $local")
            }
        mkdirs(destDir)
        val proc = execClient.start(namespace, podName, listOf("tar", "-xzf", "-", "-C", destDir), stdin = true)
        val stderr = drain(proc.errorStream)
        drain(proc.inputStream) // tar -x emits nothing, but never block on it: stdin is still open
        try {
            TarStreams.pack(proc.outputStream, local, excludes, omitDirEntries, entryName)
        } catch (e: Exception) {
            val exited = proc.waitFor(5, TimeUnit.SECONDS)
            stderr.thread.join(2_000)
            val diag = stderr.output.toString(Charsets.UTF_8).trim()
            val exitPart = if (exited) describeExit(proc.exitValue()) else "still running"
            proc.destroyForcibly()
            throw IllegalStateException(
                "upload to '$destDir' failed while streaming ($exitPart): ${e.message}" +
                    if (diag.isNotEmpty()) "; stderr: $diag" else "",
                e,
            )
        }
        awaitZeroExit(proc, stderr, "upload to '$destDir'")
    }

    fun download(
        remote: String,
        local: Path,
        mode: DownloadMode,
    ) {
        if (!remoteExists(remote)) return
        val isDir = probe(listOf("test", "-d", remote))
        val (dir, name) =
            if (isDir) remote to "." else remote.substringBeforeLast('/').ifEmpty { "/" } to remote.substringAfterLast('/')
        // -h: dereference symlinks so their content (not a link entry) is what gets archived,
        // matching the ssh runner's SFTP behavior (WorkspaceSync also transfers file content).
        val proc = execClient.start(namespace, podName, listOf("tar", "-czhf", "-", "-C", dir, name), stdin = false)
        val stderr = drain(proc.errorStream)
        val materialized =
            try {
                // The pod's tar is attacker-replaceable, and this path has no configurable
                // ceiling of its own, so it spends the shared default (§12 rule 4).
                TarStreams.extract(
                    proc.inputStream,
                    local,
                    mode,
                    isDir,
                    name,
                    "pod $podName",
                    TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
                )
            } catch (e: Exception) {
                proc.destroyForcibly()
                throw e
            }
        awaitZeroExit(proc, stderr, "download of '$remote'")
        if (materialized != null) TarStreams.prune(local, materialized)
    }

    private fun remoteExists(path: String): Boolean = probe(listOf("test", "-e", path))

    /** Runs argv in the pod and throws with captured stderr on non-zero exit. */
    private fun runToExit(argv: List<String>) {
        val proc = execClient.start(namespace, podName, argv, stdin = false)
        val stderr = drain(proc.errorStream)
        proc.inputStream.use { it.readAllBytes() }
        awaitZeroExit(proc, stderr, argv.joinToString(" "))
    }

    /**
     * Runs a POSIX `test`-style probe and distinguishes the answer from a
     * failure: exit 0 -> true, exit 1 -> false, anything else (non-zero/
     * non-one exit, or a channel/timeout failure) throws with stderr. Mirrors
     * the ssh WorkspaceSync's posture of rethrowing anything that isn't a
     * recognized "doesn't exist" signal.
     */
    private fun probe(argv: List<String>): Boolean {
        val proc = execClient.start(namespace, podName, argv, stdin = false)
        val stderr = drain(proc.errorStream)
        proc.inputStream.use { it.readAllBytes() }
        if (!proc.waitFor(EXIT_STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException(
                "${argv.joinToString(" ")}: exec did not report exit status within ${EXIT_STATUS_TIMEOUT_SECONDS}s",
            )
        }
        stderr.thread.join(2_000)
        return when (val exit = proc.exitValue()) {
            0 -> {
                true
            }

            1 -> {
                false
            }

            else -> {
                throw IllegalStateException(
                    "${argv.joinToString(" ")} failed (${describeExit(exit)}): ${stderr.output.toString(Charsets.UTF_8).trim()}",
                )
            }
        }
    }

    private fun awaitZeroExit(
        proc: Process,
        stderr: Drained,
        what: String,
    ) {
        if (!proc.waitFor(EXIT_STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("$what: exec did not report exit status within ${EXIT_STATUS_TIMEOUT_SECONDS}s")
        }
        stderr.thread.join(2_000)
        val exit = proc.exitValue()
        if (exit != 0) {
            throw IllegalStateException("$what failed (${describeExit(exit)}): ${stderr.output.toString(Charsets.UTF_8).trim()}")
        }
    }

    /** Renders an exit code for error messages, naming the client's channel-loss sentinel. */
    private fun describeExit(exit: Int): String =
        if (exit == EXEC_NO_EXIT_STATUS_SENTINEL) "no exit status (exec channel closed — websocket failure)" else "exit $exit"

    /** Concurrently drains stderr so a full pipe can't stall the transfer. */
    private fun drain(source: InputStream): Drained {
        val sink = ByteArrayOutputStream()
        val thread =
            Thread {
                runCatching { source.use { it.copyTo(sink) } }
            }.apply {
                isDaemon = true
                start()
            }
        return Drained(thread, sink)
    }

    /** A stderr/stdout drain thread paired with the buffer it fills; join before reading it. */
    private class Drained(
        val thread: Thread,
        val output: ByteArrayOutputStream,
    )

    companion object {
        /** Bounded wait for the pod exec channel to report an exit status (websocket can wedge). */
        private const val EXIT_STATUS_TIMEOUT_SECONDS = 60L
    }
}
