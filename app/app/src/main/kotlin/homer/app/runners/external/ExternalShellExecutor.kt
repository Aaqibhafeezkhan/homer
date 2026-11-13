package homer.app.runners.external

import homer.app.exceptions.OutputCapExceededException
import homer.app.infra.KillCause
import homer.app.infra.LineFilteringOutputStream
import homer.app.infra.OutputLineFilter
import homer.app.infra.ShellExecutor
import homer.app.infra.appendKillNotices
import homer.app.models.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.outputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs each command as one `cmd` op on the claimed instance. Output arrives as
 * ordered chunks through [OutputSink] into the same stdout/stderr files every
 * other runner writes, stdout through the Claude line filter when one is set, so
 * log tailing and result parsing above the session do not change. Timeout is
 * enforced by the instance (`timedOut` in the result); Homer bounds the wait for
 * that result by `timeoutMs + heartbeatTimeoutSec`, plus the interrupt grace when
 * one is set, because the instance then stops the command gracefully before it
 * answers (ADR-0066, ADR-0108).
 */
class ExternalShellExecutor(
    private val session: ExternalRunnerSession,
    private val maxOutputBytes: Long,
    private val heartbeatTimeout: Duration,
) : ShellExecutor {
    private sealed interface Outcome {
        data class Done(
            val result: OpResult,
        ) : Outcome

        data object CapExceeded : Outcome

        data object Deadline : Outcome
    }

    // ShellExecutor.cmd's override signature (fixed by the interface), as in SshShellExecutor.
    // jscpd:ignore-start
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("SuspendFunSwallowedCancellation") // cancels the remote cmd under NonCancellable and rethrows
    override suspend fun cmd(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        stdIn: String?,
        env: Map<String, String>,
        timeout: Duration,
        stdOutFilter: OutputLineFilter?,
        interruptGrace: Duration?,
    ): CommandResult {
        // jscpd:ignore-end
        // No Dispatchers.IO hop here: opening the two files is one fast syscall each, and
        // everything else below (submit/select/await) is a suspension, not blocking IO. The
        // session must stay deterministic under runTest's virtual time; the real blocking IO
        // of a cmd (chunk writes) happens in the route handlers, not here.
        val sink = openSink(stdOut, stdErr, stdOutFilter)
        val op = cmdOp(command, stdIn, env, timeout, interruptGrace)
        session.registerSink(op.opId, sink)
        val kill = KillCause()
        // With a grace the command's own timeout is a soft deadline: the instance stops it
        // gracefully, so the wait for the result is extended by the grace rather than failing
        // the step the moment the instance has not answered. A grace that is absent leaves the
        // bound exactly as it was.
        val deadline = timeout + (interruptGrace ?: Duration.ZERO) + heartbeatTimeout
        return try {
            val deferred = session.mailbox.submit(op)
            val outcome = awaitOutcome(deferred, sink, op.opId, timeout, interruptGrace, deadline)
            when (outcome) {
                Outcome.CapExceeded -> {
                    kill.byCap = true
                    throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
                }

                Outcome.Deadline -> {
                    throw ExternalRunnerException(
                        "Runner instance '${session.instance.name}' did not report the command result within ${deadline.inWholeSeconds} s",
                    )
                }

                is Outcome.Done -> {
                    doneResult(outcome.result, sink, kill, stdOut, stdErr, timeout)
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                // The instance owns the process; ask it to kill the group. It reports the
                // cmd result afterwards, which nobody awaits any more.
                session.cancelCmd(op.opId)
                session.unregisterSink(op.opId)
                sink.close()
                appendKillNotices(stdErr, kill, timeout, maxOutputBytes)
            }
            throw e
        }.also {
            session.unregisterSink(op.opId)
            sink.close()
        }
    }

    /** The `cmd` op carrying this command to the instance, with the grace the instance stops it after. */
    private fun cmdOp(
        command: List<String>,
        stdIn: String?,
        env: Map<String, String>,
        timeout: Duration,
        interruptGrace: Duration?,
    ): RunnerOp.Cmd =
        RunnerOp.Cmd(
            opId = RunnerProtocol.newId(),
            argv = command,
            env = env,
            stdin = stdIn,
            timeoutMs = timeout.inWholeMilliseconds,
            cwd = session.remoteWorkdir,
            interruptGraceSeconds = interruptGrace?.inWholeSeconds,
        )

    /**
     * Waits for the instance's result, the sink's cap trip or [deadline], whichever settles first;
     * the interrupt Homer sends at [timeout] is best-effort and cancelled once the result settles.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitOutcome(
        deferred: Deferred<OpResult>,
        sink: OutputSink,
        opId: String,
        timeout: Duration,
        interruptGrace: Duration?,
        deadline: Duration,
    ): Outcome =
        coroutineScope {
            // The instance stops the command at its own timeout too, but the interrupt is
            // Homer's to send as well: it is best-effort, and a lost one still ends in the
            // instance's kill path. Cancelled as soon as the result settles either way.
            val softStop =
                interruptGrace?.let {
                    launch {
                        delay(timeout)
                        session.interruptCmd(opId)
                    }
                }
            try {
                select<Outcome> {
                    deferred.onAwait { Outcome.Done(it) }
                    sink.capTripped.onAwait { Outcome.CapExceeded }
                    onTimeout(deadline.inWholeMilliseconds) { Outcome.Deadline }
                }
            } finally {
                softStop?.cancel()
            }
        }

    /** The sink for the command's output; closes stdout again when opening stderr fails. */
    private fun openSink(
        stdOut: Path,
        stdErr: Path,
        stdOutFilter: OutputLineFilter?,
    ): OutputSink {
        val rawOut: OutputStream = stdOut.outputStream()
        val out: OutputStream = if (stdOutFilter != null) LineFilteringOutputStream(rawOut, stdOutFilter) else rawOut
        // stdErr.outputStream() can throw once rawOut is already open; nothing would ever
        // close it then, because the sink that owns both was never constructed.
        return try {
            OutputSink(out, stdErr.outputStream(), maxOutputBytes)
        } catch (e: Throwable) {
            runCatching { rawOut.close() }
            throw e
        }
    }

    /**
     * The command's result from the instance's [result]; throws, setting [kill]'s matching
     * flag, when it is no clean exit, and when the instance reports output Homer never
     * received. Suspends for at most one [INCOMPLETE_OUTPUT_WAIT] while that last chunk
     * arrives.
     */
    private suspend fun doneResult(
        result: OpResult,
        sink: OutputSink,
        kill: KillCause,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
    ): CommandResult {
        // The cap-trip and the result raced select's registration order; select
        // picked Done first, but the sink may already have dropped bytes — don't return
        // a clean result over silently truncated output.
        capError(sink, kill)
        if (result.timedOut) {
            kill.byTimeout = true
            throw CancellationException("Timeout reached. $timeout.")
        }
        val exitCode =
            result.exitCode?.takeIf { result.ok }
                ?: throw ExternalRunnerException(
                    "Command failed on runner instance '${session.instance.name}': " +
                        (result.error?.let { session.sanitizedError(it) } ?: "no exit code reported"),
                )
        requireCompleteOutput(result, sink, kill)
        // The wait above lets chunk posts land, and one of them can be the chunk that
        // crossed the cap — its append consumes the seq, drops the bytes and closes the
        // very gap the wait was for. Re-checked so no clean result passes over them.
        capError(sink, kill)
        return CommandResult(exitCode = exitCode, stdOut = stdOut, stdErr = stdErr)
    }

    /** Fails the step over bytes the sink dropped once its cap has tripped; a no-op before that. */
    private fun capError(
        sink: OutputSink,
        kill: KillCause,
    ) {
        if (!sink.capTripped.isCompleted) return
        kill.byCap = true
        throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
    }

    /**
     * Fails the step when the instance reports more chunks for a stream than Homer holds:
     * the step's output is truncated, and a clean `CommandResult` over it is what let a
     * `!<claude>` step die on a missing `result` event instead of on the real cause. One
     * bounded wait first, never a loop — the instance reports its exit after its last post,
     * but the two ride different connections, so the last chunk can still be in flight
     * (§12 rules 4–5). A cap that trips during the check is the cap error, not a clean or
     * merely incomplete result ([capError]).
     */
    private suspend fun requireCompleteOutput(
        result: OpResult,
        sink: OutputSink,
        kill: KillCause,
    ) {
        val reported = reportedCounts(result) ?: return
        requirePossibleCounts(reported)
        if (missingChunks(reported, sink).isEmpty()) {
            // A cap trip can land between the caller's check and here with no wait in
            // between; a return over it would pass truncated output off as clean.
            capError(sink, kill)
            return
        }
        delay(INCOMPLETE_OUTPUT_WAIT)
        capError(sink, kill)
        val missing = missingChunks(reported, sink)
        if (missing.isEmpty()) return
        throw ExternalRunnerException(
            missing.joinToString("; ") {
                "output incomplete on $it: the instance reported ${reported.getValue(it)} chunk(s), Homer received ${sink.received(it)}"
            },
        )
    }

    /** The counts the instance reported, per stream; null when it reported none (an older runner). */
    private fun reportedCounts(result: OpResult): Map<String, Long>? {
        val counts =
            buildMap {
                result.stdoutChunks?.let { put(RunnerProtocol.STREAM_STDOUT, it) }
                result.stderrChunks?.let { put(RunnerProtocol.STREAM_STDERR, it) }
            }
        return counts.ifEmpty { null }
    }

    /**
     * Refuses a count that cannot be real before any wait is spent on it. Only negativity is
     * impossible: a chunk may be empty (the accept side advances the per-stream sequence for a
     * zero-byte chunk), so no count past [maxOutputBytes] is impossible and an implausibly large
     * but positive one is answered by the completeness check instead, after the same bounded wait.
     */
    private fun requirePossibleCounts(counts: Map<String, Long>) {
        counts.forEach { (stream, count) ->
            if (count < 0) {
                throw ExternalRunnerException("output incomplete on $stream: the instance reported $count chunk(s)")
            }
        }
    }

    /** The streams the instance claims more chunks for than the sink has received. */
    private fun missingChunks(
        counts: Map<String, Long>,
        sink: OutputSink,
    ): List<String> = counts.filter { (stream, count) -> count > sink.received(stream) }.keys.toList()

    companion object {
        /**
         * How long one `cmd` waits for chunks its instance reported but Homer has not received,
         * once, before failing the step as output-incomplete. Seconds, and fixed: the wait is
         * the instance's last post still being in flight, not a retry budget (§12 rule 5).
         */
        internal val INCOMPLETE_OUTPUT_WAIT = 2.seconds
    }
}
