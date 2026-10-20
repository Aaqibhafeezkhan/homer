package homer.app.runners.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.slf4j.Logger
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * [WorkspaceSync]'s deadline on blocking SFTP round trips: opens a client per operation
 * and bounds every call, including the client's own close, by [transferTimeout]
 * (CWE-400). `withTimeout` cannot interrupt a blocked JVM read, so a
 * watchdog closes [session] instead. A null [transferTimeout] leaves calls unbounded.
 * See ADR-0064.
 */
internal class SftpTransferDeadline(
    private val session: ClientSession,
    private val transferTimeout: Duration?,
    private val openSftp: (ClientSession) -> SftpClient,
    private val logger: Logger,
) {
    /** Runs [block] on a fresh client opened under the deadline, released under it afterwards. */
    @Suppress("SuspendFunInFinallySection") // release() is NonCancellable inside
    suspend fun <T> withSftp(block: suspend (SftpClient) -> T): T {
        val sftp = withTransferTimeout { openSftp(session) }
        try {
            return block(sftp)
        } finally {
            release(sftp)
        }
    }

    /**
     * Close [sftp] under the transfer deadline. A failed or timed-out release is
     * swallowed and WARN-logged: cleanup must not mask the block's own outcome
     * (a cancelled run's `NonCancellable` teardown still reaches here).
     *
     * The release itself is non-cancellable (CWE-400): the watchdog is
     * a child of the caller's job, so a caller already cancelled at entry would
     * never let its `delay()` fire and an unbounded close would ride in under the
     * cancellation. See ADR-0064.
     */
    @Suppress("SuspendFunSwallowedCancellation") // NonCancellable inside: a failure here is the close, not a cancel
    private suspend fun release(sftp: SftpClient) {
        try {
            withContext(NonCancellable) { withTransferTimeout { sftp.close() } }
        } catch (e: Exception) {
            // CWE-400: shed the whole session on a failed release — the
            // session's state is unknown, and leaving it open would let every later
            // withSftp in this run reuse it and strand another channel per failure
            // while it holds the workspace lock and run slot (§12.5: shed rather
            // than accumulate). A deadline expiry already closed it via the
            // watchdog; this covers a close that fails outright.
            logger.warn("Failed to close the SFTP client; closing the session so nothing reuses it", e)
            runCatching { session.close(true) }
        }
    }

    // withTimeout cannot interrupt a blocked JVM read; watchdog closes
    // the session instead. Invariants and rejected alternatives: See ADR-0064 (SFTP blocking-I/O timeout via a watchdog coroutine).
    @Suppress("SuspendFunSwallowedCancellation") // rethrows the caller's cancellation; maps only the watchdog close
    suspend fun <T> withTransferTimeout(block: suspend () -> T): T {
        val timeout = transferTimeout ?: return block()
        val timedOut = AtomicBoolean(false)
        try {
            return coroutineScope {
                // Dispatchers.Default, not the inherited context: under runBlocking (tests)
                // or a saturated Dispatchers.IO pool the caller's event loop is busy inside
                // the blocking copy and would never run a same-dispatcher watchdog.
                val watchdog =
                    launch(Dispatchers.Default) {
                        delay(timeout)
                        timedOut.set(true)
                        logger.warn("SFTP transfer exceeded the {} deadline; closing the session to unblock it", timeout)
                        runCatching { session.close(true) }
                    }
                try {
                    block()
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: Throwable) {
            // The caller's own cancellation (a killed run) stays a cancellation past the deadline.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            if (timedOut.get()) throw IOException("SFTP transfer timed out after $timeout (peer stalled?)", e)
            throw e
        }
    }
}
