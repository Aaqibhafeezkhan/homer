package homer.runner

import homer.app.infra.ProcessGroupSupport
import homer.app.runners.external.RunnerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

    /** How often the post-exit drain re-reads what its pumps are doing (§12 rule 4). */
    private val DRAIN_POLL = 25.milliseconds

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
            val out = Pump(pumps, process.inputStream, RunnerProtocol.STREAM_STDOUT, sink)
            val err = Pump(pumps, process.errorStream, RunnerProtocol.STREAM_STDERR, sink)
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
            return Exit(code, timedOut = !finished, out.chunks.get(), err.chunks.get(), cut)
        }

        /**
         * Waits for both pumps to hand over what they still hold and returns the streams whose
         * drain was abandoned with chunks unsent. [remaining] is what the op's timeout has left.
         *
         * The fixed [PUMP_JOIN] floor comes first, and is what makes the rest decidable: it is
         * what a post already in flight needs, and a read still in flight after it cannot be one
         * the dead process's output is about to end. Such a read is parked on a write end that
         * outlives the step — a grandchild holding the pipe, which nothing wakes — and its pump
         * has nothing left to hand over, so it is shed instead of spending the op's budget on it.
         * A pump still posting gets the rest of that budget. Either way the wait is bounded on
         * every path and a drain that never finishes is shed, not joined (§12 rules 4–5).
         */
        private suspend fun reap(
            finished: Boolean,
            out: Pump,
            err: Pump,
            remaining: Duration,
        ): Set<String> {
            if (!finished) {
                kill()
                process.waitFor(KILL_GRACE_SEC, TimeUnit.SECONDS)
            }
            val joined =
                withTimeoutOrNull(PUMP_JOIN) {
                    out.job.join()
                    err.job.join()
                }
            if (joined == null) {
                awaitSettled(out, err, remaining - PUMP_JOIN)
            }
            // Read before the cancel below: a pump still running here is one whose tail this
            // result does not carry, which is what lets the caller report the loss.
            val cut =
                buildSet {
                    if (out.cut) add(RunnerProtocol.STREAM_STDOUT)
                    if (err.cut) add(RunnerProtocol.STREAM_STDERR)
                }
            // pumps is detached (not a structured child of this call), so on the shed path
            // above a job is still running and would otherwise keep invoking the caller's
            // sink for an op that has already returned.
            pumps.cancel()
            return cut
        }

        /**
         * Waits out what is left of the op's budget for a pump that is still handing a chunk
         * over, and gives up on one that has nothing left to hand over (§12 rule 5).
         */
        private suspend fun awaitSettled(
            out: Pump,
            err: Pump,
            budget: Duration,
        ) {
            withTimeoutOrNull(maxOf(Duration.ZERO, budget)) {
                while (!(out.settled && err.settled)) delay(DRAIN_POLL)
            }
        }

        /** One stream's pump: its job, the chunks it handed the sink, and what its current read is doing. */
        private class Pump(
            scope: CoroutineScope,
            input: InputStream,
            private val stream: String,
            sink: suspend (stream: String, seq: Long, bytes: ByteArray) -> Unit,
        ) {
            val chunks = AtomicLong()

            /**
             * Whether a read is in flight. A pump still reading long after the process exited is
             * parked on a write end that outlives the step — a grandchild holding the pipe — not
             * on the sink: nothing ends such a read, so the drain must not wait on it.
             */
            private val reading = AtomicBoolean()

            val job: Job =
                scope.launch {
                    val buffer = ByteArray(READ_BUFFER)
                    while (true) {
                        val n =
                            try {
                                reading.set(true)
                                runCatching { input.read(buffer) }.getOrElse { -1 }
                            } finally {
                                reading.set(false)
                            }
                        if (n < 0) return@launch
                        if (n == 0) continue
                        val seq = chunks.get()
                        sink(stream, seq, buffer.copyOf(n))
                        // Counted once the sink returns, a post it gave up on included: the count
                        // is what this result claims Homer can hold, so a chunk dropped here must
                        // show up as a stream Homer is short of rather than disappear from the
                        // count too.
                        chunks.set(seq + 1)
                    }
                }

            /** Whether the pump has nothing left to hand over: it finished, or it is parked in a read. */
            val settled: Boolean get() = job.isCompleted || reading.get()

            /** Whether the drain gave up on chunks this pump had not handed over. */
            val cut: Boolean get() = !job.isCompleted && !reading.get()
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
