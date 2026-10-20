package homer.runner

import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.OpResult
import homer.app.runners.external.RunnerOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives one claimed session: a poller keeps one `next` long-poll open so
 * `cancel` and `close` arrive while a command runs; the dispatcher executes ops
 * in order and hands each result to a background delivery job rather than
 * posting it inline. Posting is itself a `next` long-poll racing the poller's
 * own for whichever op Homer hands out next — if delivery lost that race and
 * posting ran on the dispatcher's own coroutine, the dispatcher would stay
 * parked inside its now-pointless call for up to a full poll window even
 * though the poller already queued the op locally. Running delivery on its own
 * coroutine keeps the dispatcher free to pick that queued op up at once. Any
 * op a delivery call receives back joins the same queue. Homer unreachable for
 * [heartbeatTimeout] means Homer has dropped this instance: kill what runs,
 * cancel in-flight deliveries, and stop (ADR-0066 "Liveness").
 */
class SessionWorker(
    private val api: RunnerApi,
    private val claim: ClaimResponse,
    root: Path,
    private val heartbeatTimeout: Duration,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Fresh per session, never derived from the process id alone: ids repeat after a Homer
    // database reset, and a --root volume outlives the container, so a reused path would hand
    // one run another's files. CWE-200.
    val sessionRoot: Path = root.toAbsolutePath().normalize().resolve("${claim.processId}-${UUID.randomUUID()}")

    @Volatile
    var lost: Boolean = false
        private set

    @Volatile
    private var createdRoot = false

    // Bounded, not UNLIMITED: Homer answers one op per `next` call and awaits that
    // op's result before handing out more, so a handful of in-flight ops is the
    // normal case; a small bound (instead of an unbounded buffer) is what makes a
    // misbehaving server's flood of ops apply backpressure instead of piling up.
    private val ops = Channel<RunnerOp>(OPS_CAPACITY)
    private val running = ConcurrentHashMap<String, ProcessRunner.Handle>()

    // opIds cancelled before `runCmd` had a chance to register the process in
    // [running]: `dispatchLoop` launches a `cmd` op's handling without waiting for
    // it to start, so a `Cancel` for the same op can be dispatched first. `runCmd`
    // consults this right after registering so that race can't leave the process
    // running to its full timeout. See fix-round-1 item 3.
    private val cancelledOps = ConcurrentHashMap.newKeySet<String>()

    // Delivery jobs (see `deliver`) run on this scope, not on dispatchLoop's own
    // coroutineScope: that keeps a result-carrying `next` call — which can lose its race
    // with the poller and sit parked for a full poll window — from being awaited by
    // dispatchLoop before it can return. lost() cancels these directly; a graceful finish
    // (dispatchLoop returning after Close) waits for them like any other structured child,
    // which is fine because Homer answers a result-carrying `next` for an already-closed
    // session at once instead of holding it open.
    private lateinit var workerScope: CoroutineScope
    private val deliveryJobs = ConcurrentHashMap.newKeySet<Job>()

    @Suppress("SuspendFunInFinallySection") // NonCancellable + Dispatchers.IO, which detekt does not read as NonCancellable
    suspend fun run() =
        coroutineScope {
            workerScope = this
            val poller = launch { pollLoop() }
            try {
                dispatchLoop()
            } finally {
                poller.cancel()
                killAll()
                // Every exit reclaims the root, a lost session included: each session gets a fresh
                // one, so a root left here would never be reused or cleaned (§12 rule 4). Only a
                // root this session created: a refused pre-existing one is not ours to delete.
                if (createdRoot) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { deleteTree(sessionRoot) }
                            .onFailure {
                                logger.warn(
                                    "Session {} (process {}): failed to delete the session root: {}",
                                    claim.sessionId,
                                    claim.processId,
                                    it.message,
                                )
                            }
                    }
                }
            }
        }

    private suspend fun pollLoop() {
        var lastOkAt = clock()
        while (true) {
            val startedAt = clock()
            try {
                val op = api.next(claim.sessionId, null)
                lastOkAt = clock()
                onPolled(op, startedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ClosedSendChannelException) {
                // `ops` was already closed (lost() ran concurrently, from this very failure
                // path or another one): the session is ending regardless, so this must never
                // be routed into the generic retry-then-heartbeat-check path below, which
                // would keep calling `next()` successfully (refreshing lastOkAt so the
                // heartbeat deadline never trips) while endlessly re-failing this send.
                return
            } catch (e: Exception) {
                when (val verdict = pollFailureVerdict(e, claim.sessionId, clock() - lastOkAt, heartbeatTimeout)) {
                    is PollVerdict.Lost -> {
                        return lost(verdict.reason)
                    }

                    is PollVerdict.Retry -> {
                        verdict.warning?.let { logger.warn("Session {}: next failed: {}", claim.sessionId, it) }
                        delay(RETRY_DELAY)
                    }
                }
            }
        }
    }

    /** Queues the [op] a poll started at [startedAt] returned, or paces an empty poll that came back early. */
    private suspend fun onPolled(
        op: RunnerOp?,
        startedAt: Long,
    ) {
        when {
            op != null -> ops.send(op)

            // A 204 that comes back well under the server's long-poll window means
            // Homer isn't actually holding the poll open (misbehaving or misconfigured
            // proxy) — without this, that turns into a tight busy-loop against it.
            clock() - startedAt < FAST_POLL_THRESHOLD.inWholeMilliseconds -> delay(FAST_POLL_DELAY)
        }
    }

    private fun lost(reason: String) {
        logger.error(
            "Session {} (process {}): {}; killing local processes and dropping the session",
            claim.sessionId,
            claim.processId,
            reason,
        )
        lost = true
        // Killed here, not left to run()'s finally: dispatchLoop's coroutineScope won't
        // return while a launched `cmd` job is still awaiting a live process, so closing
        // `ops` alone would strand that job (and this function) until the process exits
        // on its own.
        killAll()
        // Same reasoning for delivery jobs: closing `ops` doesn't touch a job already
        // parked inside `postResult`'s `next` call or its retry `delay`, and run()'s
        // coroutineScope won't return while one is still active. Cancel them directly
        // instead of waiting out a poll window (or the retry budget) against a session
        // that's already gone.
        deliveryJobs.forEach { it.cancel() }
        ops.close()
    }

    private suspend fun dispatchLoop() =
        coroutineScope {
            for (op in ops) {
                when (op) {
                    is RunnerOp.Close -> {
                        logger.info("Session {} (process {}): closed by Homer ({})", claim.sessionId, claim.processId, op.reason)
                        killAll()
                        return@coroutineScope
                    }

                    is RunnerOp.Cancel -> {
                        cancelledOps += op.opId
                        running[op.opId]?.kill()
                    }

                    is RunnerOp.Cmd -> {
                        launch { deliver(runCmd(op)) }
                    }

                    else -> {
                        deliver(execute(op))
                    }
                }
            }
        }

    /**
     * Posts [result] on a new child coroutine of [workerScope] — never on the caller's own
     * coroutine, whether that's `dispatchLoop`'s for-loop (non-`cmd` ops) or a `cmd`'s own
     * launched job — so a `next` call that loses its race with the poller and sits parked
     * for a full poll window never keeps the caller from moving on. The job is tracked in
     * [deliveryJobs] so [lost] can cancel a call that would otherwise time out (or exhaust
     * its retry budget) against a session that's already gone, rather than leaving [run]
     * waiting for it. Skips posting entirely once [lost] is already set: a delivery that
     * only gets scheduled after the session is lost has nothing to report.
     */
    private fun deliver(result: OpResult) {
        if (lost) return
        val job = workerScope.launch { postResult(result)?.let(::handOff) }
        deliveryJobs += job
        job.invokeOnCompletion { deliveryJobs -= job }
    }

    /**
     * Posts [result] to Homer, retrying transient failures; returns the op Homer hands back,
     * or null when there is none, delivery had to give up, or the session was marked lost
     * along the way. Deliberately does not touch [ops] itself — see [handOff] for why that
     * has to be a separate, non-suspending step.
     */
    private suspend fun postResult(result: OpResult): RunnerOp? {
        var attempt = 0
        while (true) {
            try {
                return api.next(claim.sessionId, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RunnerApiException) {
                logger.warn("Session {}: result for op {} refused: {}", claim.sessionId, result.opId, e.message)
                if (e.isClientError || ++attempt > MAX_RETRIES) return null
                delay(RETRY_DELAY)
            } catch (e: Exception) {
                if (++attempt > MAX_RETRIES) {
                    lost("could not deliver the result of op ${result.opId}: ${e.message}")
                    return null
                }
                delay(RETRY_DELAY)
            }
        }
    }

    /**
     * Hands [op] to the dispatcher without ever suspending. Always runs on a delivery job's own
     * coroutine (see [deliver]), never on the dispatcher's — but a suspending `send` here would
     * still be wrong: a full channel is never expected to drain on its own, so blocking on it
     * would just wedge the delivery job. A closed channel means the session is already ending
     * (`lost()` ran concurrently); nothing to do. A full buffer means Homer handed out another op
     * before this one was acknowledged, breaking the one-op-in-flight assumption `ops`'s bound
     * relies on — treated as fatal (drop the session) rather than silently discarding an op.
     */
    private fun handOff(op: RunnerOp) {
        val result = ops.trySend(op)
        if (result.isFailure && !result.isClosed) {
            logger.warn("Session {}: op queue full handing off {}, dropping the session", claim.sessionId, op.opId)
            lost("op queue full handing off ${op.opId}")
        }
    }

    private suspend fun execute(op: RunnerOp): OpResult =
        try {
            withContext(Dispatchers.IO) {
                when (op) {
                    is RunnerOp.CreateWorkspace -> {
                        // createDirectory, not createDirectories: an existing root is refused, never reused.
                        // Owner-only, so another local user cannot read a run's files through a shared
                        // --root; no silent fallback where the mode is unsupported, the op fails instead.
                        // CWE-732.
                        sessionRoot.parent.createDirectories()
                        Files.createDirectory(sessionRoot, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                        createdRoot = true
                        listOf("workdir", "artifacts", ".homer").forEach { sessionRoot.resolve(it).createDirectory() }
                        OpResult(op.opId, root = sessionRoot.toString())
                    }

                    is RunnerOp.Upload -> {
                        upload(op)
                    }

                    is RunnerOp.Download -> {
                        download(op)
                    }

                    is RunnerOp.CleanWorkspace -> {
                        deleteTree(sessionRoot)
                        OpResult(op.opId)
                    }

                    is RunnerOp.Cmd, is RunnerOp.Cancel, is RunnerOp.Close -> {
                        error("${op::class.simpleName} is handled by the dispatcher")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Session {}: op {} failed: {}", claim.sessionId, op.opId, e.message)
            OpResult(op.opId, ok = false, error = e.message ?: e::class.simpleName)
        }

    private suspend fun upload(op: RunnerOp.Upload): OpResult {
        val target = contained(op.remoteRelative)
        if (op.isDir) target.createDirectories() else target.parent.createDirectories()
        api.fetchTar(claim.sessionId, op.opId) { input ->
            TarStreams.extract(
                input,
                target,
                DownloadMode.OVERWRITE,
                remoteIsDir = op.isDir,
                fileName = target.fileName.toString(),
                sourceLabel = "homer",
                // No per-instance config for this; the shared default is the same
                // ceiling Homer's own extraction spends.
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )
        }
        return OpResult(op.opId)
    }

    private suspend fun download(op: RunnerOp.Download): OpResult {
        val source = contained(op.remoteRelative)
        return if (!source.exists()) {
            OpResult(op.opId, missing = true)
        } else {
            val tmp = Files.createTempFile(sessionRoot.parent, ".homer-runner-", ".tgz")
            try {
                tmp.outputStream().use { TarStreams.pack(it, source) }
                api.postTar(claim.sessionId, op.opId, if (source.isDirectory()) "dir" else "file", tmp)
            } finally {
                tmp.deleteIfExists()
            }
            OpResult(op.opId)
        }
    }

    private suspend fun runCmd(op: RunnerOp.Cmd): OpResult {
        val cwd = Path.of(op.cwd).toAbsolutePath().normalize()
        if (!cwd.startsWith(sessionRoot)) return OpResult(op.opId, ok = false, error = "cwd is outside the session root")
        val handle =
            try {
                ProcessRunner.start(op.argv, cwd, op.env, op.stdin)
            } catch (e: Exception) {
                return OpResult(op.opId, ok = false, error = "failed to start: ${e.message}")
            }
        running[op.opId] = handle
        // A Cancel dispatched before this coroutine got to run (dispatchLoop's `launch`
        // for `cmd` doesn't wait for the process to start) lands in cancelledOps instead
        // of finding a handle in `running`; check it immediately after registering so
        // that race can't leave the process running to its full timeout.
        if (op.opId in cancelledOps) handle.kill()
        try {
            val exit = handle.await(op.timeoutMs.milliseconds) { stream, seq, bytes -> postChunk(op.opId, stream, seq, bytes) }
            return OpResult(op.opId, exitCode = exit.code, timedOut = exit.timedOut)
        } finally {
            running.remove(op.opId)
            cancelledOps.remove(op.opId)
        }
    }

    private suspend fun postChunk(
        opId: String,
        stream: String,
        seq: Long,
        bytes: ByteArray,
    ) {
        var attempt = 0
        while (true) {
            try {
                api.postOutput(claim.sessionId, opId, stream, seq, bytes)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRefusal(e)) {
                    // 409 = Homer already has this seq (a retried post), 413 = past the cap, 404 = op gone: nothing to retry.
                    logger.warn("Session {}: output chunk {}#{} refused: {}", claim.sessionId, stream, seq, e.message)
                    return
                }
                // A 5xx is transient like a connection error, not a refusal, and so is a 429:
                // Homer still holds an earlier attempt of this chunk (a post that timed out here).
                // Retrying is safe and necessary — dropping the chunk would desync the per-stream
                // seq and make Homer 409 every later chunk of this stream for the rest of the command.
                if (++attempt > MAX_RETRIES) {
                    logger.warn(
                        "Session {}: dropping output chunk {}#{} after {} attempts: {}",
                        claim.sessionId,
                        stream,
                        seq,
                        attempt,
                        e.message,
                    )
                    return
                }
                delay(RETRY_DELAY)
            }
        }
    }

    /** Whether a failed output post was refused for good: a 4xx other than 429. */
    private fun isRefusal(e: Exception): Boolean = e is RunnerApiException && e.isClientError && e.status != 429

    /**
     * Lexical containment only, no realpath: the commands this worker runs execute as the same
     * UID with code Homer sent, so a symlink resolving outside the session root defends no
     * boundary that code could not cross directly. Accepted risk.
     */
    private fun contained(remoteRelative: String): Path {
        val candidate = sessionRoot.resolve(remoteRelative).normalize()
        require(candidate.startsWith(sessionRoot)) { "path '$remoteRelative' escapes the session root" }
        return candidate
    }

    private fun killAll() = running.values.forEach { it.kill() }

    private fun deleteTree(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        private const val MAX_RETRIES = 5
        private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
        private const val OPS_CAPACITY = 16
        private val RETRY_DELAY = 1.seconds
        private val FAST_POLL_THRESHOLD = 1.seconds
        private val FAST_POLL_DELAY = 250.milliseconds
        private val logger = LoggerFactory.getLogger(SessionWorker::class.java)
    }
}

/** What [SessionWorker]'s poller does after a failed `next`. */
internal sealed interface PollVerdict {
    /** The session is gone: kill what runs and stop, for [reason]. */
    data class Lost(
        val reason: String,
    ) : PollVerdict

    /** Retry after the retry delay, logging [warning] first when it is set. */
    data class Retry(
        val warning: String?,
    ) : PollVerdict
}

/**
 * Judges a failed `next` poll of session [sessionId], [unreachableMs] after the last
 * successful one: a 404 means Homer dropped the session; any other 4xx is retried and
 * logged; a 5xx or a connection error is retried until Homer has been unreachable for
 * longer than [heartbeatTimeout] (ADR-0066 "Liveness").
 */
internal fun pollFailureVerdict(
    e: Exception,
    sessionId: String,
    unreachableMs: Long,
    heartbeatTimeout: Duration,
): PollVerdict =
    when {
        e is RunnerApiException && e.status == 404 -> {
            PollVerdict.Lost("Homer no longer knows session $sessionId: ${e.message}")
        }

        e is RunnerApiException && e.status < 500 -> {
            PollVerdict.Retry(e.message)
        }

        // A persistent 5xx (e.g. a proxy in front of Homer) is exactly the same "Homer is
        // unreachable" condition as a connection error, and must be judged against the same
        // heartbeat deadline — otherwise it retries forever and the worker (and its child
        // process) never stops.
        unreachableMs > heartbeatTimeout.inWholeMilliseconds -> {
            PollVerdict.Lost("Homer unreachable for ${heartbeatTimeout.inWholeSeconds} s (${e.message})")
        }

        else -> {
            PollVerdict.Retry(if (e is RunnerApiException) e.message else null)
        }
    }
