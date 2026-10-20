package homer.app.runners.kubernetes

import homer.app.exceptions.OutputCapExceededException
import homer.app.exceptions.PodFailedDuringExecutionException
import homer.app.infra.LineFilteringOutputStream
import homer.app.infra.OutputLineFilter
import homer.app.infra.ShellExecutor
import homer.app.models.CommandResult
import homer.app.runners.KillCause
import homer.app.runners.exceedsOutputCap
import io.kubernetes.client.openapi.apis.CoreV1Api
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.outputStream
import kotlin.time.Duration

/**
 * Runs a command inside an already-started pod by opening a new exec
 * channel per call. Preserves today's argv-list semantics: Cmd runs
 * unshelled via `env KEY=VAL ... argv`; only Script requests a shell,
 * and that is already encoded in the argv it passes us.
 */
class KubernetesShellExecutor(
    private val execClient: KubernetesExecClient,
    private val api: CoreV1Api,
    private val namespace: String,
    private val podName: String,
    private val maxOutputBytes: Long = Long.MAX_VALUE,
    private val pumpJoinMs: Long = PUMP_JOIN_MS,
) : ShellExecutor {
    // ShellExecutor.cmd's override signature (fixed by the interface) and IO hop, as in SshShellExecutor.
    // jscpd:ignore-start
    @Suppress("SuspendFunSwallowedCancellation") // aborts under NonCancellable and rethrows
    override suspend fun cmd(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        stdIn: String?,
        env: Map<String, String>,
        timeout: Duration,
        stdOutFilter: OutputLineFilter?,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            // jscpd:ignore-end
            val argv = envPrefix(env) + command
            val remote = execClient.start(namespace, podName, argv, stdin = stdIn != null)

            val outPump = OutputPump(remote.inputStream, stdOut, stdOutFilter)
            val errPump = OutputPump(remote.errorStream, stdErr, filter = null)

            val stdInJob = stdIn?.let { feedStdin(remote, it) }

            // Audit #8: mirror the LocalShellExecutor contract — cancellation
            // and timeout must propagate to the caller. The previous catch-and-
            // continue let CmdExecutor treat a TTL-killed command as a success.
            val kill = KillCause()
            try {
                awaitExit(remote, stdOut, stdErr, timeout, kill)
            } catch (e: Throwable) {
                withContext(NonCancellable) { abort(remote, stdErr, timeout, kill, stdInJob, listOf(outPump, errPump)) }
                throw e
            }
            stdInJob?.let { joinStdin(remote, it) }
            // Bounded: a background child in the pod can hold the exec stream open past the
            // command's exit. Past the bound the pumps close their files, so the cap check is final.
            outPump.join(pumpJoinMs)
            errPump.join(pumpJoinMs)

            failIfOverCap(stdOut, stdErr)

            CommandResult(exitCode = exitCodeOf(remote), stdOut = stdOut, stdErr = stdErr)
        }

    /**
     * Feeds [stdIn] on a separate job so a container that never drains its
     * stdin can't block the caller's IO thread forever; the monitor runs
     * concurrently and, on a trip, kills the exec, unblocking the write. Failures
     * are swallowed (broken pipe on teardown / early exit is not the command's
     * fault). Mirrors LocalShellExecutor.
     */
    private fun CoroutineScope.feedStdin(
        remote: Process,
        stdIn: String,
    ): Job =
        launch {
            runCatching {
                remote.outputStream.use { it.writeAndClose(stdIn) }
            }
        }

    /**
     * Waits for the stdin writer after the command exited, at most [pumpJoinMs]: a pod that never
     * drained its stdin, with a child still holding the exec open, would block the write, and this
     * wait, forever. Past the bound the stream is closed, failing the blocked write as a kill would.
     */
    private suspend fun joinStdin(
        remote: Process,
        stdInJob: Job,
    ) {
        if (withTimeoutOrNull(pumpJoinMs) { stdInJob.join() } != null) return
        runCatching { remote.outputStream.close() }
        stdInJob.join()
    }

    /** Kills the command after a cancellation, timeout or cap trip, noting [kill]'s cause in [stdErr]. */
    private suspend fun abort(
        remote: Process,
        stdErr: Path,
        timeout: Duration,
        kill: KillCause,
        stdInJob: Job?,
        pumps: List<OutputPump>,
    ) {
        runCatching { killInsidePod() }
        runCatching { remote.destroyForcibly() }
        // Exec is dead; a blocked stdin write has hit a broken pipe.
        stdInJob?.cancelAndJoin()
        // A pump writes from offset 0 of a file it truncated on open, so one still running would
        // overwrite the marker below: drain them first, bounded, as LocalShellExecutor.tearDown does.
        pumps.forEach { it.join(pumpJoinMs) }
        if (kill.byTimeout) {
            stdErr.toFile().appendText("\n[HOMER] Command killed due to TTL timeout ($timeout)\n")
        }
        if (kill.byCap) {
            stdErr.toFile().appendText("\n[HOMER] Output cap exceeded ($maxOutputBytes bytes)\n")
        }
    }

    /**
     * A fast-exiting command can overshoot the cap between the last poll and
     * channel close; the pumps have now flushed all output, so re-check and
     * fail instead of accepting the oversized artifact. Done inline (not via
     * the pod-wide kill) since the command already exited.
     */
    private suspend fun failIfOverCap(
        stdOut: Path,
        stdErr: Path,
    ) {
        if (exceedsOutputCap(stdOut, stdErr, maxOutputBytes)) {
            withContext(NonCancellable) {
                stdErr.toFile().appendText("\n[HOMER] Output cap exceeded ($maxOutputBytes bytes)\n")
            }
            throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
        }
    }

    /**
     * Waits until [remote] exits; throws on cancellation, on [timeout], or when the output passes the
     * cap, setting [kill]'s matching flag first.
     */
    private suspend fun awaitExit(
        remote: Process,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
        kill: KillCause,
    ) {
        val start = System.currentTimeMillis()
        var finished = false
        while (!finished && System.currentTimeMillis() - start < timeout.inWholeMilliseconds) {
            finished = pollFinished(remote, stdOut, stdErr, timeout, kill)
        }
        if (!finished) {
            kill.byTimeout = true
            throw CancellationException("Timeout reached. $timeout.")
        }
    }

    /** One poll of [awaitExit]: whether [remote] exited, throwing on cancellation or a passed output cap. */
    private suspend fun pollFinished(
        remote: Process,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
        kill: KillCause,
    ): Boolean {
        val finished = remote.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Command cancelled after $timeout")
        }
        if (!finished && exceedsOutputCap(stdOut, stdErr, maxOutputBytes)) {
            kill.byCap = true
            throw OutputCapExceededException(
                "Output cap exceeded: $maxOutputBytes bytes.",
            )
        }
        return finished
    }

    /** The exited command's code; a failed exec channel is a [PodFailedDuringExecutionException] with the pod's diagnostics. */
    private fun exitCodeOf(remote: Process): Int {
        val exitCode =
            runCatching { remote.exitValue() }.getOrElse {
                val snap = PodDiagnostics.tryCollect(api, namespace, podName, "ExecChannelFailed")
                throw PodFailedDuringExecutionException(snap.reason, snap.message, snap.events, it)
            }
        // client-java's ExecProcess can RETURN its no-exit-status sentinel instead
        // of throwing when the websocket closed without a v1.Status (e.g. a refused
        // 101 upgrade) — a channel failure, never a real command exit code.
        if (exitCode == EXEC_NO_EXIT_STATUS_SENTINEL) {
            val snap = PodDiagnostics.tryCollect(api, namespace, podName, "ExecChannelFailed")
            throw PodFailedDuringExecutionException(snap.reason, snap.message, snap.events)
        }
        return exitCode
    }

    private fun envPrefix(env: Map<String, String>): List<String> =
        if (env.isEmpty()) {
            emptyList()
        } else {
            buildList {
                add("env")
                env.forEach { (k, v) -> add("$k=$v") }
            }
        }

    /**
     * Copies one exec stream into [target] on a daemon thread. [join] waits at most its bound; past
     * it the pump's file and source are closed, and a pump that has not opened its file yet is kept
     * from opening it, so nothing it reads later reaches the artifact or overwrites a marker written
     * after the join. Mirrors LocalShellExecutor's StdoutPump.
     */
    private class OutputPump(
        private val source: InputStream,
        private val target: Path,
        filter: OutputLineFilter?,
    ) {
        private val lock = Any()
        private var file: OutputStream? = null
        private var abandoned = false

        private val thread =
            Thread {
                try {
                    val raw =
                        synchronized(lock) {
                            if (abandoned) return@Thread
                            target.outputStream().also { file = it }
                        }
                    val out = if (filter != null) LineFilteringOutputStream(raw, filter) else raw
                    out.use { source.copyTo(it) }
                } catch (e: Exception) {
                    logger.warn("Failed to pump pod output to {}", target, e)
                    // Nobody drains the stream any more: close it so the pod command is not left
                    // blocked on a full buffer until its timeout.
                    runCatching { source.close() }
                }
            }.apply {
                isDaemon = true
                name = "homer-pod-output-pump"
                start()
            }

        fun join(ms: Long) {
            thread.join(ms)
            if (!thread.isAlive) return
            logger.warn("pod output pump for {} still running {} ms after the exec ended", target, ms)
            synchronized(lock) {
                abandoned = true
                runCatching { file?.close() }
            }
            runCatching { source.close() }
        }
    }

    private fun OutputStream.writeAndClose(s: String) {
        write(s.toByteArray(Charsets.UTF_8))
        flush()
    }

    /** Kills every process in the pod's main container PID namespace. */
    private fun killInsidePod() {
        // Via the sh builtin: the procps `kill` binary (debian-ish images)
        // misparses the negative target `-1`; the dash/ash builtins accept it.
        val killProc =
            execClient.start(
                namespace,
                podName,
                listOf("sh", "-c", "kill -KILL -1"),
                stdin = false,
            )
        runCatching { killProc.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
        runCatching { killProc.destroyForcibly() }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val KILL_WAIT_MS = 2_000L
        private const val PUMP_JOIN_MS = 5_000L
        private val logger = LoggerFactory.getLogger(KubernetesShellExecutor::class.java)
    }
}
