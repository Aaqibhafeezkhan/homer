package homer.runner

import homer.app.infra.ProcessGroupSupport
import homer.app.runners.external.RunnerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Runs one `cmd` op: the process leads its own group (`setsid` where present,
 * ADR-0041) with exactly the given env, stdout and stderr are pumped as chunks
 * to [Handle.await]'s sink in read order, the timeout and `cancel` kill the
 * whole group. Not `LocalShellExecutor`: that writes files, the protocol streams.
 */
object ProcessRunner {
    private const val READ_BUFFER = 64 * 1024
    private const val KILL_GRACE_SEC = 5L
    private val PUMP_JOIN = 5.seconds

    /**
     * One `cmd` op's outcome. [stdoutChunks] and [stderrChunks] are the chunks each pump
     * handed to the sink — a chunk the sink gave up on included, so a lost one still shows
     * up as a stream Homer holds fewer chunks for than this result claims. [cutStreams]
     * names the streams whose drain was abandoned with chunks still unsent.
     */
    class Exit(
        val code: Int,
        val timedOut: Boolean,
        val stdoutChunks: Long,
        val stderrChunks: Long,
        val cutStreams: Set<String>,
    ) {
        /** The chunks [stream] got as far as handing to the sink. */
        fun chunksFor(stream: String): Long = if (stream == RunnerProtocol.STREAM_STDOUT) stdoutChunks else stderrChunks
    }

    class Handle internal constructor(
        private val process: Process,
    ) {
        // Pumps are not structured children: a grandchild holding the pipe open
        // would otherwise keep await() from returning after the kill.
        private val pumps = CoroutineScope(Dispatchers.IO + SupervisorJob())

        internal fun isAlive(): Boolean = process.isAlive

        fun kill() {
            if (!process.isAlive) return
            ProcessGroupSupport.ownProcessGroupId(process)?.let { ProcessGroupSupport.killGroup(it) }
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }

        @Suppress("SuspendFunInFinallySection") // NonCancellable + Dispatchers.IO, which detekt does not read as NonCancellable
        suspend fun await(
            timeout: Duration,
            sink: suspend (stream: String, seq: Long, bytes: ByteArray) -> Unit,
        ): Exit {
            // The op's deadline, not just this call's: the pumps post the tail after the
            // process is gone, so how long that drain may take is what is left of the timeout.
            val startedAt = TimeSource.Monotonic.markNow()
            val stdoutChunks = AtomicLong()
            val stderrChunks = AtomicLong()
            val out = pumps.launch { pump(process.inputStream, RunnerProtocol.STREAM_STDOUT, sink, stdoutChunks) }
            val err = pumps.launch { pump(process.errorStream, RunnerProtocol.STREAM_STDERR, sink, stderrChunks) }
            var finished = false
            var cut: Set<String> = emptySet()
            try {
                finished = withTimeoutOrNull(timeout) { process.onExit().await() } != null
            } finally {
                // In a finally, not after: withTimeoutOrNull absorbs only its own timeout, so a
                // cancel from outside (the drain's hard stop) would otherwise skip the kill and
                // leave the process running in a root that is about to be deleted.
                // IO: reap blocks in waitFor for up to KILL_GRACE_SEC, which must not pin a Default thread.
                withContext(NonCancellable + Dispatchers.IO) {
                    cut = reap(finished, out, err, timeout - startedAt.elapsedNow())
                }
            }
            val code = if (process.isAlive) 137 else process.exitValue()
            return Exit(code, timedOut = !finished, stdoutChunks.get(), stderrChunks.get(), cut)
        }

        /**
         * Waits for both pumps to finish posting and returns the streams left undrained when
         * that wait expires. [remaining] is what the op's timeout has left; the floor is
         * [PUMP_JOIN], which is all a killed or already-expired op has (§12 rule 4 — the wait
         * is bounded on every path, and a drain that never finishes is shed, not joined).
         */
        private suspend fun reap(
            finished: Boolean,
            out: Job,
            err: Job,
            remaining: Duration,
        ): Set<String> {
            if (!finished) {
                kill()
                process.waitFor(KILL_GRACE_SEC, TimeUnit.SECONDS)
            }
            withTimeoutOrNull(maxOf(PUMP_JOIN, remaining)) {
                out.join()
                err.join()
            } ?: run {
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
            // Read before the cancel below: a job still running here is one whose tail this
            // result does not carry, which is what lets the caller report the loss.
            val cut =
                buildSet {
                    if (!out.isCompleted) add(RunnerProtocol.STREAM_STDOUT)
                    if (!err.isCompleted) add(RunnerProtocol.STREAM_STDERR)
                }
            // pumps is detached (not a structured child of this call), so on the
            // expiry path above the two jobs are still running (a stream close can make
            // read() throw rather than return -1) and would otherwise keep invoking the
            // caller's sink for an op that has already returned.
            pumps.cancel()
            return cut
        }

        private suspend fun pump(
            input: InputStream,
            stream: String,
            sink: suspend (String, Long, ByteArray) -> Unit,
            chunks: AtomicLong,
        ) {
            val buffer = ByteArray(READ_BUFFER)
            while (true) {
                val n = runCatching { input.read(buffer) }.getOrElse { -1 }
                if (n < 0) return
                if (n == 0) continue
                val seq = chunks.get()
                sink(stream, seq, buffer.copyOf(n))
                // Counted once the sink returns, a post it gave up on included: the count is
                // what this result claims Homer can hold, so a chunk dropped here must show up
                // as a stream Homer is short of rather than disappear from the count too.
                chunks.set(seq + 1)
            }
        }
    }

    fun start(
        argv: List<String>,
        cwd: Path,
        env: Map<String, String>,
        stdin: String?,
    ): Handle {
        val builder = ProcessBuilder(ProcessGroupSupport.launchArgv(argv)).directory(cwd.toFile())
        // The JDK pre-fills the child env from this process's env; the run must
        // see only what Homer composed (same rule as LocalShellExecutor).
        builder.environment().apply {
            clear()
            putAll(env)
        }
        val process = builder.start()
        Thread {
            runCatching {
                process.outputStream.bufferedWriter().use { writer ->
                    if (stdin != null) writer.write(stdin)
                }
            }
        }.apply {
            isDaemon = true
            name = "homer-runner-stdin"
            start()
        }
        return Handle(process)
    }
}
