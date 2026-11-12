package homer.app.runners.kubernetes

import homer.app.exceptions.OutputCapExceededException
import homer.app.exceptions.PodFailedDuringExecutionException
import homer.app.infra.LineFilteringOutputStream
import homer.app.infra.OutputLineFilter
import homer.app.infra.SecretRedactor
import homer.app.infra.ShellExecutor
import homer.app.models.CommandResult
import homer.app.models.MIN_SCREENED_SECRET_LENGTH
import homer.app.models.PodTermination
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.outputStream
import kotlin.time.Duration

/**
 * Runs a command inside an already-started pod by opening a new exec
 * channel per call. Preserves today's argv-list semantics: Cmd runs
 * unshelled via `env KEY=VAL ... argv`; only Script requests a shell,
 * and that is already encoded in the argv it passes us. With an
 * `interruptGrace` (ADR-0107) the command is additionally wrapped in
 * `setsid`, so its process group can be interrupted before the kill.
 */
class KubernetesShellExecutor(
    private val execClient: KubernetesExecClient,
    private val api: CoreV1Api,
    private val namespace: String,
    private val podName: String,
    private val maxOutputBytes: Long = Long.MAX_VALUE,
    private val pumpJoinMs: Long = PUMP_JOIN_MS,
    private val maxRunSeconds: Long? = null,
    private val stopReasonPollMs: Long = STOP_REASON_POLL_MS,
    private val stopReasonBudgetMs: Long = STOP_REASON_BUDGET_MS,
    /** Slack added to the grace for the signalling exec's own bound. */
    private val signalExecSlackMs: Long = SIGNAL_EXEC_SLACK_MS,
    private val onTermination: suspend (PodTermination) -> Unit = {},
) : ShellExecutor {
    private val podControl = PodProcessControl(execClient, namespace, podName, signalExecSlackMs)

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
        interruptGrace: Duration?,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            // jscpd:ignore-end
            // Null without a grace (or where the pod has no `setsid`): the launch is then exactly today's.
            val pgidFile = podControl.pgidFileFor(env, interruptGrace)
            val argv = envPrefix(env) + (pgidFile?.let { podControl.setsidLaunch(it, command) } ?: command)
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
                withContext(NonCancellable) {
                    abort(remote, stdErr, timeout, kill, stdInJob, listOf(outPump, errPump), interruptGrace, pgidFile)
                }
                throw e
            }
            stdInJob?.let { joinStdin(remote, it) }
            // Bounded: a background child in the pod can hold the exec stream open past the
            // command's exit. Past the bound the pumps close their files, so the cap check is final.
            outPump.join(pumpJoinMs)
            errPump.join(pumpJoinMs)

            failIfOverCap(stdOut, stdErr)

            CommandResult(exitCode = exitCodeOf(remote, env), stdOut = stdOut, stdErr = stdErr)
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
        interruptGrace: Duration?,
        pgidFile: String?,
    ) {
        podControl.killPod(interruptGrace, pgidFile, kill)
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

    /**
     * The exited command's code. After a non-zero exit or a failed exec channel the pod's status is read
     * once (a signal exit 137/143 re-reads it for up to [stopReasonBudgetMs], as Kubernetes records the
     * stop reason a moment after the kill): a pod Kubernetes stopped (deadline, eviction, OOM, deletion) is a
     * [PodFailedDuringExecutionException] carrying the reason; a channel lost while the pod still runs stops the
     * leftover command and fails as [EXEC_CHANNEL_LOST] with the cause.
     */
    private suspend fun exitCodeOf(
        remote: Process,
        env: Map<String, String>,
    ): Int {
        val exit = runCatching { remote.exitValue() }
        // client-java's ExecProcess can RETURN its no-exit-status sentinel instead of throwing when
        // the websocket closed without a v1.Status (e.g. a refused 101 upgrade): a channel failure.
        val code = exit.getOrNull()?.takeIf { it != EXEC_NO_EXIT_STATUS_SENTINEL }
        if (code == 0) return 0
        failIfPodStopped(patient = code in SIGNAL_EXITS)
        if (code != null) return code
        val snap = PodDiagnostics.tryCollect(api, namespace, podName, EXEC_CHANNEL_LOST)
        killLeftoverAfterLostChannel(remote)
        val msg = channelLostMessage(exit.exceptionOrNull(), env, snap)
        onTermination(PodTermination(EXEC_CHANNEL_LOST, msg))
        throw PodFailedDuringExecutionException(EXEC_CHANNEL_LOST, msg, snap.events, exit.exceptionOrNull(), channelLost = true)
    }

    /**
     * The channel died, not the command, so the command may still be running in the pod: stop it pod-wide
     * before the step fails (ADR-0041), under [NonCancellable] so a cancelled caller cannot skip it. A kill
     * that itself fails is only logged — the step must still fail as a lost channel.
     */
    private suspend fun killLeftoverAfterLostChannel(remote: Process) =
        withContext(NonCancellable) {
            runCatching { podControl.killInsidePod() }
                .onFailure { logger.warn("Failed to kill the leftover command in pod {} after a lost exec channel", podName, it) }
            runCatching { remote.destroyForcibly() }
        }

    /**
     * The step error's text for a channel lost on a live pod: what closed the channel and the pod's last event.
     * The exec URL carries the argv, so every env value long enough to be a credential is masked, in its raw
     * form and in the query-encoded form it takes in that URL (ADR-0047 rule 7), and the text is bounded to one
     * line of [TERMINATION_MESSAGE_MAX_CHARS].
     */
    private fun channelLostMessage(
        cause: Throwable?,
        env: Map<String, String>,
        snap: PodDiagnostics.Snapshot,
    ): String {
        val closed = cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "closed without an exit status"
        val text =
            "exec channel to pod $podName was lost while the pod was running: $closed. " +
                (snap.events.lastOrNull() ?: snap.message)
        val redactor =
            SecretRedactor.from(env, env.keys, minNeedleLength = MIN_SCREENED_SECRET_LENGTH) +
                // The cause quotes the exec URL, where the argv (env KEY=VAL) is query-encoded: a secret
                // holding a reserved character appears percent-encoded there, so the raw value alone misses it.
                SecretRedactor.from(
                    env.mapValues { (_, v) -> URLEncoder.encode(v, StandardCharsets.UTF_8) },
                    env.keys,
                    minNeedleLength = MIN_SCREENED_SECRET_LENGTH,
                )
        return redactor.redact(text).toOneLine()
    }

    private suspend fun failIfPodStopped(patient: Boolean) {
        val t = (if (patient) awaitPodTermination() else readPodTermination(api, namespace, podName)) ?: return
        onTermination(t)
        val snap = PodDiagnostics.tryCollect(api, namespace, podName, t.reason)
        throw PodFailedDuringExecutionException(t.reason, t.message, snap.events, null, t, t.summary(maxRunSeconds))
    }

    /** Re-reads the pod every [stopReasonPollMs] up to [stopReasonBudgetMs] until it reports a stop. */
    private suspend fun awaitPodTermination(): PodTermination? {
        var waited = 0L
        var t = readPodTermination(api, namespace, podName)
        while (t == null && waited < stopReasonBudgetMs) {
            delay(stopReasonPollMs)
            waited += stopReasonPollMs
            t = readPodTermination(api, namespace, podName)
        }
        return t
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

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val PUMP_JOIN_MS = 5_000L
        private const val STOP_REASON_POLL_MS = 500L
        private const val STOP_REASON_BUDGET_MS = 5_000L
        private const val SIGNAL_EXEC_SLACK_MS = 5_000L
        internal const val SIGNAL_REJECTED = 3

        /**
         * Refuses `$p` unless it is a plain integer greater than 1. `$p` comes from a file in an
         * agent-writable directory, so it is attacker-controlled and `kill -INT -1`/`-0` must be
         * unreachable (§12 rule 2). Shared with the tests, which run it on its own.
         */
        internal const val PGID_GUARD =
            "case \"\$p\" in ''|*[!0-9]*) exit $SIGNAL_REJECTED;; esac; [ \"\$p\" -gt 1 ] || exit $SIGNAL_REJECTED"

        private val SIGNAL_EXITS = setOf(137, 143)
        private val logger = LoggerFactory.getLogger(KubernetesShellExecutor::class.java)
    }
}
