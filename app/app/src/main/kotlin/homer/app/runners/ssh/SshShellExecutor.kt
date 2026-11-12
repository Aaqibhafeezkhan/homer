package homer.app.runners.ssh

import homer.app.exceptions.OutputCapExceededException
import homer.app.exceptions.SshConnectionLostException
import homer.app.infra.KillCause
import homer.app.infra.LineFilteringOutputStream
import homer.app.infra.OutputLineFilter
import homer.app.infra.ShellExecutor
import homer.app.infra.appendKillNotices
import homer.app.models.CommandResult
import homer.app.runners.exceedsOutputCap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.outputStream
import kotlin.time.Duration

/**
 * Runs each command in its own SSH exec channel. Argv semantics are preserved
 * by RemoteCommandBuilder (POSIX-escaped `cd && exec env ... argv`). Mirrors
 * KubernetesShellExecutor's timeout / cancellation / output-cap contract.
 *
 * When [reapProcessGroup] is set (SshRunner's capability probe found `setsid -w`
 * on the target), the command is launched as its own remote process group and,
 * on kill/timeout, the whole group is `kill -KILL`ed over a control channel
 * before the exec channel is closed, so a backgrounded remote child cannot
 * orphan on the target host. See ADR-0041.
 */
class SshShellExecutor(
    private val session: ClientSession,
    private val remoteWorkdir: String,
    private val maxOutputBytes: Long = Long.MAX_VALUE,
    private val reapProcessGroup: Boolean = false,
    private val reapDir: String? = null,
) : ShellExecutor {
    private val reapCounter = AtomicLong(0)

    // ShellExecutor.cmd's override signature (fixed by the interface) and IO hop, as in KubernetesShellExecutor.
    // jscpd:ignore-start
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
            // Non-null exactly when the command reaps its process group.
            val pgidFile = if (reapProcessGroup && reapDir != null) "$reapDir/cmd-${reapCounter.incrementAndGet()}.pgid" else null
            val remoteCmd = remoteCommand(command, env, pgidFile)
            val channel = session.createExecChannel(remoteCmd)
            // channel.use{} closes the exec channel on every path (success, timeout,
            // cancel, exception); the forceful close(true) in the catch block below
            // still runs first for prompt termination, and use{}'s graceful close
            // afterward is idempotent.
            channel.use {
                val rawOut: OutputStream = stdOut.outputStream()
                val filteredOut: OutputStream = if (stdOutFilter != null) LineFilteringOutputStream(rawOut, stdOutFilter) else rawOut
                filteredOut.use { out ->
                    stdErr.outputStream().use { err ->
                        channel.setOut(out)
                        channel.setErr(err)
                        if (stdIn != null) {
                            channel.setIn(ByteArrayInputStream(stdIn.toByteArray(Charsets.UTF_8)))
                        }
                        // OPEN_TIMEOUT_MS bounds the channel-open handshake only, a phase
                        // distinct from command execution, which is bounded by the caller's `timeout`.
                        channel.open().verify(OPEN_TIMEOUT_MS)

                        val kill = KillCause()
                        try {
                            awaitClose(channel, stdOut, stdErr, timeout, kill)
                        } catch (e: Throwable) {
                            withContext(NonCancellable) {
                                // MINA 2.18.0 exposes no client-side SSH signal API (RFC 4254 channel
                                // signal request), so close(true) is the only lever on the exec channel
                                // itself. When reaping is enabled the command leads its own remote
                                // process group; kill that whole group over a control channel FIRST so a
                                // backgrounded/detached child (which close(true) alone would leave to
                                // orphan on the target host) dies too. See ADR-0041. A timeout with a
                                // configured grace interrupts the group before the kill (ADR-0107); an
                                // output-cap kill or a caller cancellation keeps the immediate SIGKILL.
                                reapOnStop(pgidFile, kill, interruptGrace)
                                runCatching { channel.close(true) }
                                appendKillNotices(stdErr, kill, timeout, maxOutputBytes)
                            }
                            throw e
                        }

                        commandResult(channel, stdOut, stdErr)
                    }
                }
            }
        }

    /** The remote argv for [command]: the reaping launcher when the command reports its own group. */
    private fun remoteCommand(
        command: List<String>,
        env: Map<String, String>,
        pgidFile: String?,
    ): String =
        if (pgidFile != null) {
            RemoteCommandBuilder.buildReaping(remoteWorkdir, env, command, pgidFile)
        } else {
            RemoteCommandBuilder.build(remoteWorkdir, env, command)
        }

    /** The channel's exit status as the result; a channel closed without one is a lost connection. */
    private fun commandResult(
        channel: ChannelExec,
        stdOut: Path,
        stdErr: Path,
    ): CommandResult {
        val exit =
            channel.exitStatus
                ?: throw SshConnectionLostException("SSH channel closed without an exit status")
        return CommandResult(exitCode = exit, stdOut = stdOut, stdErr = stdErr)
    }

    /**
     * Interrupts then kills the remote group the command self-reported, when it has one and reaping
     * is on: only a timeout stop carries [interruptGrace] (ADR-0107); a cap kill or a caller
     * cancellation keeps the immediate SIGKILL.
     */
    private fun reapOnStop(
        pgidFile: String?,
        kill: KillCause,
        interruptGrace: Duration?,
    ) {
        if (pgidFile == null) return
        val grace = if (kill.byTimeout) interruptGrace else null
        runCatching { reapRemoteGroup(pgidFile, grace) }
    }

    /**
     * Waits until [channel] closes; throws on cancellation, on [timeout], or when the output passes
     * the cap, setting [kill]'s matching flag first.
     */
    private suspend fun awaitClose(
        channel: ChannelExec,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
        kill: KillCause,
    ) {
        val start = System.currentTimeMillis()
        var closed = false
        while (!closed && System.currentTimeMillis() - start < timeout.inWholeMilliseconds) {
            closed = pollClosed(channel, stdOut, stdErr, timeout, kill)
        }
        if (!closed) {
            kill.byTimeout = true
            throw CancellationException("Timeout reached. $timeout.")
        }
        // A fast-exiting command can overshoot the cap between the
        // last poll and channel close; MINA has flushed all output by
        // CLOSED (with a stdout filter, up to one buffered partial line
        // lands when the stream closes below), so re-check after the
        // loop and fail instead of accepting the oversized artifact. The
        // catch's close(true) is idempotent on the closed channel.
        if (exceedsOutputCap(stdOut, stdErr, maxOutputBytes)) {
            kill.byCap = true
            throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
        }
    }

    /** One poll of [awaitClose]: whether [channel] closed, throwing on cancellation or a passed output cap. */
    private suspend fun pollClosed(
        channel: ChannelExec,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
        kill: KillCause,
    ): Boolean {
        val events =
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), POLL_INTERVAL_MS)
        val closed = events.contains(ClientChannelEvent.CLOSED)
        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Command cancelled after $timeout")
        }
        if (!closed && exceedsOutputCap(stdOut, stdErr, maxOutputBytes)) {
            kill.byCap = true
            throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
        }
        return closed
    }

    /**
     * Reaps the process group the command self-reported to [pgidFile] over a
     * fresh control channel. The pgid is validated to be a plain integer > 1
     * before any signal runs (see [RemoteCommandBuilder.buildReapKill] /
     * [RemoteCommandBuilder.buildReapInterrupt]), so a forged/stale/corrupt
     * side-file can never become `kill -KILL -1` or `kill -INT -1`. When
     * [interruptGrace] is set (the command was stopped by its own timeout and a
     * grace is configured), the group is first SIGINTed and given the grace to
     * exit before the SIGKILL; the channel wait covers that grace so the reaper
     * script runs to completion. Best-effort: an empty/missing/invalid file or a
     * failed kill is a no-op, swallowed by the caller's `runCatching`.
     */
    private fun reapRemoteGroup(
        pgidFile: String,
        interruptGrace: Duration?,
    ) {
        val script =
            if (interruptGrace != null) {
                RemoteCommandBuilder.buildReapInterrupt(pgidFile, interruptGrace.inWholeSeconds)
            } else {
                RemoteCommandBuilder.buildReapKill(pgidFile)
            }
        val kill = session.createExecChannel(script)
        kill.use {
            it.open().verify(OPEN_TIMEOUT_MS)
            it.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), REAP_TIMEOUT_MS + (interruptGrace?.inWholeMilliseconds ?: 0L))
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val REAP_TIMEOUT_MS = 5_000L
    }
}
