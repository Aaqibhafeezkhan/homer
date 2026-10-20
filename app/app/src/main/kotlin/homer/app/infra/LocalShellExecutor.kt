package homer.app.infra

import homer.app.exceptions.OutputCapExceededException
import homer.app.models.CommandResult
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
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.outputStream
import kotlin.time.Duration

class LocalShellExecutor(
    private val workDir: Path,
    private val maxOutputBytes: Long = Long.MAX_VALUE,
    private val pumpJoinMs: Long = PUMP_JOIN_MS,
) : ShellExecutor {
    // ShellExecutor.cmd's override signature (fixed by the interface) and IO hop, as in SshShellExecutor.
    // jscpd:ignore-start
    @Suppress("SuspendFunSwallowedCancellation") // tears down under NonCancellable and rethrows
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
            val process = startProcess(command, stdOut, stdErr, env, stdOutFilter)
            val outPump = stdOutFilter?.let { StdoutPump(process.inputStream, stdOut, it) }

            // Feed stdin on a separate job so a child that never drains its stdin
            // (a full OS pipe buffer, ~64 KiB) cannot block this coroutine's IO
            // thread forever: the timeout / output-cap monitor below runs
            // concurrently and, on a trip, kills the process, which breaks the pipe
            // and unblocks the write. Write failures are swallowed — once we tear
            // the process down a broken-pipe write is a consequence, not a fault,
            // and a child that legitimately exits before reading all stdin still
            // succeeded.
            val stdInJob = launchStdinWriter(process, stdIn)

            // Audit #8: any throw past this point must (a) tear down the native
            // process and its descendants and (b) propagate to the caller — the
            // previous implementation swallowed the timeout/cancel exception and
            // returned a CommandResult, so callers (CmdExecutor, LlmHarnessExecutor,
            // ScriptExecutor) treated a killed command as a successful one.
            var stopped = Wait.EXITED
            try {
                stopped = awaitCompletion(process, stdOut, stdErr, timeout, outPump)
                when (stopped) {
                    Wait.EXITED -> {}

                    Wait.OUTPUT_CAP -> {
                        throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
                    }

                    Wait.TIMED_OUT -> {
                        throw CancellationException("Timeout reached. $timeout.")
                    }
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { tearDown(process, stdInJob, outPump, stdErr, timeout, stopped) }
                throw e
            }

            // Process finished on its own; let the stdin writer settle (it either
            // flushed everything or hit EOF when the child exited early) before we
            // report the result.
            stdInJob?.join()

            CommandResult(
                exitCode = process.exitValue(),
                stdOut = stdOut,
                stdErr = stdErr,
            )
        }

    /**
     * [awaitExit], then — once the process exited on its own — the post-exit cap re-check: how
     * the command stopped. A fast-exiting command can overshoot the cap between the last poll
     * and exit; the file already holds all output (OS redirect), so the re-check fails it
     * instead of accepting the oversized artifact as a clean result.
     */
    private suspend fun awaitCompletion(
        process: java.lang.Process,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
        outPump: StdoutPump?,
    ): Wait {
        val wait = awaitExit(process, stdOut, stdErr, timeout)
        if (wait != Wait.EXITED) return wait
        // With a pump, wait for it (bounded: a background child that inherited
        // stdout can hold the pipe open past the main process's exit). Past the
        // bound the pump closes the file, so the size check below is final.
        outPump?.join(pumpJoinMs)
        return if (outputExceedsCap(stdOut, stdErr)) Wait.OUTPUT_CAP else Wait.EXITED
    }

    /**
     * Tears [process] down after a failed or cancelled wait, releases the stdin writer and the
     * pump, and appends the marker for a [stopped] TTL or output-cap kill to [stdErr]. For a
     * process that already exited the kill is a no-op and only the marker is appended.
     */
    private suspend fun tearDown(
        process: java.lang.Process,
        stdInJob: Job?,
        outPump: StdoutPump?,
        stdErr: Path,
        timeout: Duration,
        stopped: Wait,
    ) {
        if (process.isAlive) {
            val descendants = process.descendants()
            // Group kill first (reaps detached/reparented children the
            // descendants snapshot misses), then the direct process and its
            // known descendants as a belt-and-suspenders fallback. The pgid
            // MUST be read here, at kill time, not at launch: right after
            // start() the child may not have run setsid(2) yet (posix_spawn
            // resumes the parent at the setsid exec, before its main runs),
            // so a launch-time read races the leader transition and loses
            // under CPU contention — null pgid, no group kill.
            ProcessGroupSupport.ownProcessGroupId(process)?.let { ProcessGroupSupport.killGroup(it) }
            process.destroyForcibly()
            descendants.forEach { it.destroyForcibly() }
            // Wait briefly so process.exitValue() is observable and we
            // do not leak a zombie back to the caller.
            process.waitFor(KILL_GRACE_SEC, TimeUnit.SECONDS)
        }
        // The process is now dead, so a still-blocked stdin write has hit
        // a broken pipe and returned; release its thread before propagating.
        stdInJob?.cancelAndJoin()
        outPump?.join(pumpJoinMs)
        if (stopped == Wait.TIMED_OUT) {
            stdErr.toFile().appendText("\n[HOMER] Command killed due to TTL timeout ($timeout)\n")
        }
        if (stopped == Wait.OUTPUT_CAP) {
            stdErr.toFile().appendText("\n[HOMER] Output cap exceeded ($maxOutputBytes bytes)\n")
        }
    }

    /** Starts [command] in [workDir] with exactly [env]; stdout goes to [stdOut] unless a [stdOutFilter] pumps it. */
    private fun startProcess(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        env: Map<String, String>,
        stdOutFilter: OutputLineFilter?,
    ): java.lang.Process {
        val processBuilder =
            ProcessBuilder()
                // Launch under setsid (when available) so the command leads its own
                // process group and detached/late-spawned children can be reaped as a
                // group on kill/timeout. Falls back to the raw argv where setsid is
                // absent. See ProcessGroupSupport / ADR-0041.
                .command(ProcessGroupSupport.launchArgv(command))
                .directory(workDir.toFile())
                .redirectError(ProcessBuilder.Redirect.to(stdErr.toFile()))
        // With a filter, stdout stays a pipe and a pump thread writes the kept lines;
        // without one, the OS redirect below is byte-for-byte today's path. See ADR-0063.
        if (stdOutFilter == null) {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.to(stdOut.toFile()))
        }

        // ProcessBuilder.environment() is pre-populated from System.getenv()
        // by the JDK; if we just `putAll` we silently inherit every host env
        // var. Clear it first so the child sees ONLY what
        // WorkspaceConfigFactory composed (allowlisted host env + globalEnv +
        // agent.env + queryParams + HMR_*). Audit P0 #2.
        val childEnv = processBuilder.environment()
        childEnv.clear()
        childEnv.putAll(env)
        return processBuilder.start()
    }

    /** Writes [stdIn] to [process] on its own job; null when there is no stdin to feed. */
    private fun CoroutineScope.launchStdinWriter(
        process: java.lang.Process,
        stdIn: String?,
    ): Job? =
        if (stdIn != null) {
            launch {
                runCatching {
                    process.outputStream.bufferedWriter().use {
                        it.write(stdIn)
                        it.flush()
                    }
                }
            }
        } else {
            null
        }

    /** How [awaitExit] stopped waiting. */
    private enum class Wait { EXITED, OUTPUT_CAP, TIMED_OUT }

    /**
     * Polls [process] until it exits, its output passes the cap, or [timeout] elapses; throws
     * when the calling coroutine is cancelled meanwhile.
     */
    private suspend fun awaitExit(
        process: java.lang.Process,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
    ): Wait {
        val start = (System.currentTimeMillis() / 1000).toInt()
        while ((System.currentTimeMillis() / 1000).toInt() - start < timeout.inWholeSeconds) {
            // Poll sub-second so a burst writer can exceed the output cap by less
            // before it trips. The timeout is independent (integer-second math).
            val finished = process.waitFor(OUTPUT_CAP_POLL_MS, TimeUnit.MILLISECONDS)
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Command cancelled while waiting for completion")
            }
            if (finished) return Wait.EXITED
            if (outputExceedsCap(stdOut, stdErr)) return Wait.OUTPUT_CAP
        }
        return Wait.TIMED_OUT
    }

    private fun outputExceedsCap(
        stdOut: Path,
        stdErr: Path,
    ): Boolean = stdOut.toFile().length() > maxOutputBytes || stdErr.toFile().length() > maxOutputBytes

    /**
     * Drains the child's stdout pipe through [filter] into [target] on a daemon thread.
     * [join] waits at most the given bound; a pump still blocked past it (a background
     * child holds the pipe open) has its file closed, so nothing written afterwards can
     * reach the artifact, and its read end closed. See ADR-0063.
     */
    private class StdoutPump(
        private val source: InputStream,
        private val target: Path,
        filter: OutputLineFilter,
    ) {
        @Volatile
        private var file: OutputStream? = null

        private val thread =
            Thread {
                try {
                    val out = target.outputStream()
                    file = out
                    LineFilteringOutputStream(out, filter).use { source.copyTo(it) }
                } catch (e: Exception) {
                    logger.warn("Failed to pump process stdout to {}", target, e)
                    // Nobody drains the pipe any more: close our end so the child gets
                    // EPIPE instead of blocking on a full pipe until its timeout.
                    runCatching { source.close() }
                }
            }.apply {
                isDaemon = true
                name = "homer-stdout-pump"
                start()
            }

        fun join(ms: Long) {
            thread.join(ms)
            if (!thread.isAlive) return
            logger.warn("stdout pump still running {} ms after the command ended; a child may hold stdout open", ms)
            // On Linux a pipe read blocked in another thread is not woken by close(): the
            // thread ends only at the child's next write or exit, and a read that fills
            // its buffer is still handed over. Closing the file first makes that write
            // fail instead of growing the artifact past the cap check.
            runCatching { file?.close() }
            runCatching { source.close() }
        }
    }

    companion object {
        private const val KILL_GRACE_SEC = 5L
        private const val OUTPUT_CAP_POLL_MS = 250L
        private const val PUMP_JOIN_MS = 5_000L
        private val logger = LoggerFactory.getLogger(LocalShellExecutor::class.java)
    }
}
