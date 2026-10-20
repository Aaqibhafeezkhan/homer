package homer.app.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Grace given to a killed process group to die before the call returns. */
private const val GRAPH_KILL_GRACE_SEC = 5L

/** How long a reader may stay blocked on a pipe after the subprocess is gone. */
private const val GRAPH_READER_JOIN_MS = 5_000L

/** Attempts at reading the child's own pgid before `setsid(2)` has run. */
private const val GRAPH_PGID_CAPTURE_ATTEMPTS = 20

/** Delay between those attempts. */
private const val GRAPH_PGID_CAPTURE_RETRY_MS = 10L

/** Result of one bounded `homer_langgraph` introspection subprocess. */
internal sealed interface GraphSubprocessOutcome {
    /** The subprocess exited on its own; [stdout] was read within the cap. */
    data class Exited(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) : GraphSubprocessOutcome

    /** The subprocess outlived the timeout and was killed with its process group. */
    data object TimedOut : GraphSubprocessOutcome

    /** Stdout passed [MAX_GRAPH_STDOUT_BYTES]; the subprocess was killed at once. */
    data object StdoutExceeds : GraphSubprocessOutcome

    data class LaunchFailed(
        val message: String,
    ) : GraphSubprocessOutcome
}

/**
 * Runs `<python> -m homer_langgraph [args]` with stdout and stderr piped and
 * drained while it runs, holding at most [MAX_GRAPH_STDOUT_BYTES] +
 * [MAX_GRAPH_STDERR_BYTES] (+1 byte each) in memory whatever it emits, and
 * writing nothing to disk. Stdout past the cap kills the whole process group at
 * once and returns [GraphSubprocessOutcome.StdoutExceeds]; stderr past its cap is
 * drained and discarded so a flooding child never wedges on a full pipe.
 *
 * The agent's [env] is overlaid onto the inherited server environment (so
 * `python`/`homer_langgraph` resolve and the agent's own module imports exactly
 * as in a real run), with `PYTHONWARNINGS` defaulted to `ignore`.
 *
 * CWE-770: the previous implementation redirected both streams
 * to `java.io.tmpdir` files that nothing size-capped while the subprocess ran,
 * so a read-granted caller could fill the filesystem until the timeout. See the
 * ADR-0073 amendment.
 *
 * [startProcess] overrides the launch — a test seam only, defaulting to
 * `ProcessBuilder.start()`.
 */
@Suppress("SuspendFunSwallowedCancellation") // reaps the child (NonCancellable) and rethrows
internal suspend fun runBoundedGraphSubprocess(
    python: String,
    args: List<String>,
    env: Map<String, String>,
    timeout: Duration,
    startProcess: (ProcessBuilder) -> java.lang.Process = { it.start() },
): GraphSubprocessOutcome =
    withContext(Dispatchers.IO) {
        val builder = graphProcessBuilder(python, args, env)
        val process =
            try {
                startProcess(builder)
            } catch (e: Exception) {
                return@withContext GraphSubprocessOutcome.LaunchFailed(e.message ?: "failed to launch python")
            }

        // Readers live in their own scope: one blocked on a pipe that a detached
        // grandchild still holds open can outlive this call, and structured
        // concurrency would then await it forever.
        val readers = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Record the group id while the leader is still alive: the overflow path may
        // have to reap the group after the leader has exited on its own, and then its
        // /proc/<pid>/stat — the only source of the pgid — is already gone. Retried
        // briefly so the freshly-spawned child has run setsid(2).
        //
        // CWE-772: the capture holds the first cancellable
        // suspension after start(), so a caller cancelled in that window (client
        // disconnect, shutdown) would unwind before any reap and leak the child and
        // its group. Run the capture non-cancellable (bounded, at most
        // GRAPH_PGID_CAPTURE_ATTEMPTS * GRAPH_PGID_CAPTURE_RETRY_MS) and reap on any
        // unwind that did not reach the body's own reap paths.
        var groupId: Long? = null
        try {
            groupId = withContext(NonCancellable) { captureOwnProcessGroupId(process) }
            // CWE-770: bounded readers, at most limit+1 bytes each.
            val stdoutJob = readers.readGraphStdout(process, groupId)
            val stderrJob = readers.readGraphStderr(process)
            awaitGraphOutcome(process, groupId, stdoutJob, stderrJob, timeout)
        } catch (e: Throwable) {
            // CWE-772: a cancelled caller (or any other unwind after
            // start()) must not leave the interpreter and its group behind. The
            // captured pgid makes the kill reach a reparented grandchild even when the
            // leader is already gone.
            runCatching { reapGraphProcess(process, groupId) }
            throw e
        } finally {
            readers.cancel()
            closeGraphStreams(process)
        }
    }

/**
 * `<python> -m homer_langgraph [args]` with the agent's [env] overlaid onto the
 * inherited server env, so the agent's PYTHONPATH points the import at its
 * module, as a run does.
 */
private fun graphProcessBuilder(
    python: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessBuilder {
    val builder =
        ProcessBuilder(ProcessGroupSupport.launchArgv(listOf(python, "-m", "homer_langgraph") + args))
    builder.environment().putAll(env)
    // Keep import-time deprecation warnings (e.g. LangChain's) out of the error
    // we surface to the console; warnings are non-fatal here.
    builder.environment().putIfAbsent("PYTHONWARNINGS", "ignore")
    return builder
}

/**
 * Reads [process]'s stdout up to [MAX_GRAPH_STDOUT_BYTES]; null when it passed the cap,
 * in which case the group is killed at once rather than drained to EOF.
 */
private fun CoroutineScope.readGraphStdout(
    process: java.lang.Process,
    groupId: Long?,
): Deferred<String?> =
    async {
        val whole =
            try {
                readWhole(process.inputStream, MAX_GRAPH_STDOUT_BYTES)
            } catch (_: Exception) {
                // The pipe was closed under us (a reader that outlived the
                // join bound): we cannot prove the output fits the cap.
                null
            }
        // Past the cap: kill the group at once rather than draining to EOF.
        if (whole == null) reapGraphProcess(process, groupId)
        whole
    }

/** Reads the head of [process]'s stderr, up to [MAX_GRAPH_STDERR_BYTES], and discards the rest. */
private fun CoroutineScope.readGraphStderr(process: java.lang.Process): Deferred<String> =
    async {
        val head =
            try {
                readHead(process.errorStream, MAX_GRAPH_STDERR_BYTES)
            } catch (_: Exception) {
                ""
            }
        // Drain-and-discard the remainder so a flooding child never wedges
        // on a full stderr pipe; nothing past the head is retained.
        runCatching { process.errorStream.copyTo(OutputStream.nullOutputStream()) }
        head
    }

/** Waits up to [timeout] for [process], reaps its group either way, and reads the outcome off the readers. */
private suspend fun awaitGraphOutcome(
    process: java.lang.Process,
    groupId: Long?,
    stdoutJob: Deferred<String?>,
    stderrJob: Deferred<String>,
    timeout: Duration,
): GraphSubprocessOutcome {
    if (!process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)) {
        reapGraphProcess(process, groupId)
        joinGraphReaders(listOf(stdoutJob, stderrJob), process)
        return GraphSubprocessOutcome.TimedOut
    }

    // The leader exited on its own; reap the group all the same. A grandchild
    // it spawned (a detached writer that ignores SIGPIPE, say) outlives it, and
    // with the leader gone the group kill is the only way to reach it. The pgid
    // captured while the leader was alive is what makes this possible.
    reapGraphProcess(process, groupId)
    joinGraphReaders(listOf(stdoutJob, stderrJob), process)
    val stdout = stdoutJob.getCompletedOrNull() ?: return GraphSubprocessOutcome.StdoutExceeds
    return GraphSubprocessOutcome.Exited(process.exitValue(), stdout, stderrJob.getCompletedOrNull() ?: "")
}

/**
 * CWE-772: honour readWhole/readHead's "caller owns (and closes)
 * input" contract on every exit path instead of relying on the JDK's
 * process-completion handler. The outcome is produced before this runs, so the
 * close cannot race a live reader into a false result.
 */
private fun closeGraphStreams(process: java.lang.Process) {
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    // The child's stdin write end is never written to; close it too so the
    // pipe is released here rather than by the JDK's completion handler, and
    // a child that reads stdin sees EOF instead of blocking forever.
    runCatching { process.outputStream.close() }
}

/**
 * Reads [process]'s own process-group id, retrying briefly so the freshly-spawned
 * child has run `setsid(2)` before its `/proc/<pid>/stat` is read. Called while
 * the leader is still alive so the overflow path can reap the group even after
 * the leader exits — by then [ProcessGroupSupport.ownProcessGroupId] finds no
 * `/proc` entry. Null where `setsid` is unavailable or the leader transition
 * never becomes observable (the process already exited).
 */
private suspend fun captureOwnProcessGroupId(process: java.lang.Process): Long? {
    if (ProcessGroupSupport.setsidPath == null) return null
    repeat(GRAPH_PGID_CAPTURE_ATTEMPTS) {
        ProcessGroupSupport.ownProcessGroupId(process)?.let { return it }
        if (!process.isAlive) return null
        delay(GRAPH_PGID_CAPTURE_RETRY_MS)
    }
    return null
}

/**
 * Kills [process] and, where `setsid` made it a group leader, its whole process
 * group. The pgid is re-read here, at kill time, not at launch: right after
 * `start()` the child may not have run `setsid(2)` yet, so a launch-time read
 * races the leader transition. [capturedGroupId] is the pgid read while the
 * leader was alive, used when the leader has since exited — the group, e.g. a
 * reparented still-writing grandchild holding the stdout pipe, must still die.
 * `descendants()` remains the fallback where setsid is absent. See ADR-0041.
 */
private suspend fun reapGraphProcess(
    process: java.lang.Process,
    capturedGroupId: Long?,
) {
    withContext(NonCancellable) {
        val groupId = ProcessGroupSupport.ownProcessGroupId(process) ?: capturedGroupId
        if (!process.isAlive && groupId == null) return@withContext
        // Snapshot descendants BEFORE the group kill: afterwards they are gone.
        val descendants = if (process.isAlive) process.descendants() else null
        groupId?.let { ProcessGroupSupport.killGroup(it) }
        process.destroyForcibly()
        descendants?.forEach { it.destroyForcibly() }
        process.waitFor(GRAPH_KILL_GRACE_SEC, TimeUnit.SECONDS)
    }
}

/**
 * Waits at most [GRAPH_READER_JOIN_MS] for [jobs]. Past that a reader is still
 * blocked on a pipe a grandchild that escaped the group holds open, so the
 * streams are closed to release it — `withTimeout` alone cannot bound a blocking
 * read (app/CLAUDE.md Gotchas).
 */
private suspend fun joinGraphReaders(
    jobs: List<Job>,
    process: java.lang.Process,
) {
    val joined = withTimeoutOrNull(GRAPH_READER_JOIN_MS) { jobs.forEach { it.join() } }
    if (joined != null) return
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> Deferred<T>.getCompletedOrNull(): T? = if (isCompleted) runCatching { getCompleted() }.getOrNull() else null
