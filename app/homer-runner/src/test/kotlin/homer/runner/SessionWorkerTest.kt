package homer.runner

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.RunnerOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import ch.qos.logback.classic.Logger as LogbackLogger

class SessionWorkerTest {
    @TempDir
    lateinit var tmp: Path

    private val api = FakeRunnerApi()
    private val claim = ClaimResponse("sess", 7, "demo", listOf("linux"))

    private fun worker(heartbeat: kotlin.time.Duration = 30.seconds) = SessionWorker(api, claim, tmp.resolve("root"), heartbeat)

    private fun <T> live(block: suspend CoroutineScope.() -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(20.seconds) { block() } }

    @Test
    fun `createWorkspace builds the run layout and reports its absolute root, close deletes it`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            val r = api.result()
            assertEquals("cw", r.opId)
            assertEquals(w.sessionRoot.toString(), r.root)
            assertTrue(w.sessionRoot.isAbsolute)
            listOf("workdir", "artifacts", ".homer").forEach { assertTrue(w.sessionRoot.resolve(it).exists(), it) }
            // Owner-only: no other local user can traverse into the run's files (CWE-732).
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(w.sessionRoot)))

            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
            assertFalse(w.sessionRoot.exists())
            assertFalse(w.lost)
        }

    @Test
    fun `the same process id never maps to the same session root (CWE-200)`() {
        val first = worker()
        val second = worker()
        assertTrue(first.sessionRoot != second.sessionRoot, "a repeated process id must not reuse a root")
        assertTrue(
            first.sessionRoot.fileName
                .toString()
                .startsWith("${claim.processId}-"),
        )
    }

    @Test
    fun `createWorkspace refuses a root that already exists instead of inheriting its files (CWE-200)`() =
        live {
            val w = worker()
            w.sessionRoot
                .resolve("workdir")
                .createDirectories()
                .resolve("secret.txt")
                .writeText("left behind")
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            val r = api.result()
            assertFalse(r.ok, "an existing root must fail the op, not be handed to the run")
            assertNull(r.root)
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
            assertTrue(w.sessionRoot.resolve("workdir/secret.txt").exists(), "a refused root is not ours to delete")
        }

    @Test
    fun `cmd streams stdout and stderr in sequence and reports the exit code`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            val cwd = w.sessionRoot.resolve("workdir").toString()
            api.ops.send(
                RunnerOp.Cmd(
                    "c1",
                    listOf("sh", "-c", "echo out; echo err >&2; exit 3"),
                    mapOf("PATH" to "/usr/bin:/bin"),
                    null,
                    30_000,
                    cwd,
                ),
            )
            val r = api.result()
            assertEquals(3, r.exitCode)
            assertFalse(r.timedOut)
            val out = api.output.filter { it.first == "stdout" }
            val err = api.output.filter { it.first == "stderr" }
            assertEquals("out\n", out.joinToString("") { it.third.decodeToString() })
            assertEquals("err\n", err.joinToString("") { it.third.decodeToString() })
            assertEquals(out.indices.map { it.toLong() }, out.map { it.second }, "stdout seq is dense from 0")
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `an output chunk Homer sheds with 429 is retried, while a 409 is dropped`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            val cwd = w.sessionRoot.resolve("workdir").toString()
            api.postOutputFailures += RunnerApiException(429, "transfer in flight")
            api.ops.send(RunnerOp.Cmd("c1", listOf("sh", "-c", "echo out"), mapOf("PATH" to "/usr/bin:/bin"), null, 30_000, cwd))
            assertEquals(0, api.result().exitCode)
            assertEquals("out\n", api.output.filter { it.first == "stdout" }.joinToString("") { it.third.decodeToString() })

            api.output.clear()
            api.postOutputFailures += RunnerApiException(409, "out of order")
            api.ops.send(RunnerOp.Cmd("c2", listOf("sh", "-c", "echo lost"), mapOf("PATH" to "/usr/bin:/bin"), null, 30_000, cwd))
            assertEquals(0, api.result().exitCode)
            assertTrue(api.output.none { it.first == "stdout" }, "a refused chunk is not retried")
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `an output chunk that fails with a 5xx or a connection error is retried until it lands`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            val cwd = w.sessionRoot.resolve("workdir").toString()
            api.postOutputFailures += RunnerApiException(503, "unavailable")
            api.postOutputFailures += java.io.IOException("connection reset")
            api.ops.send(RunnerOp.Cmd("c1", listOf("sh", "-c", "echo out"), mapOf("PATH" to "/usr/bin:/bin"), null, 30_000, cwd))
            assertEquals(0, api.result().exitCode)
            assertEquals("out\n", api.output.filter { it.first == "stdout" }.joinToString("") { it.third.decodeToString() })
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a slow chunk post loses no tail and the result reports the per-stream chunk counts`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            // Every post lags, so the pump is still posting when the process exits: the exit
            // must wait for the drain rather than cut it, or the marker line is lost.
            api.postOutputDelayMs = 100
            api.ops.send(
                RunnerOp.Cmd(
                    "c1",
                    listOf("sh", "-c", "echo one; echo two; echo MARKER"),
                    mapOf("PATH" to "/usr/bin:/bin"),
                    null,
                    30_000,
                    w.sessionRoot.resolve("workdir").toString(),
                ),
            )
            val r = api.result()
            assertEquals(0, r.exitCode)
            val stdout = api.output.filter { it.first == "stdout" }
            assertEquals("one\ntwo\nMARKER\n", stdout.joinToString("") { it.third.decodeToString() })
            assertEquals(stdout.size.toLong(), r.stdoutChunks, "the result reports what the stream posted")
            assertEquals(0L, r.stderrChunks)
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a chunk dropped after its retries is still counted, so the result is short`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            repeat(6) { api.postOutputFailures += IOException("connection reset") }
            api.ops.send(
                RunnerOp.Cmd(
                    "c1",
                    listOf("sh", "-c", "echo lost"),
                    mapOf("PATH" to "/usr/bin:/bin"),
                    null,
                    30_000,
                    w.sessionRoot.resolve("workdir").toString(),
                ),
            )
            // The retry budget is five delays of a second each, so the result lands well past
            // the default wait.
            val r = api.result(timeoutMs = 30_000)
            assertEquals(0, r.exitCode)
            assertEquals(1L, r.stdoutChunks, "a dropped chunk still counts, so Homer sees the stream short")
            assertTrue(api.output.none { it.first == "stdout" }, "the chunk never landed")
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a drain cut with output unsent warns once per stream and never logs chunk bytes`() =
        live {
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val logback = LoggerFactory.getLogger(SessionWorker::class.java) as LogbackLogger
            logback.addAppender(appender)
            try {
                val w = worker()
                val run = async { w.run() }
                api.ops.send(RunnerOp.CreateWorkspace("cw"))
                api.result()
                // Neither post ever returns, so both pumps are still handing their chunk over when
                // the drain bound expires: the tail of both streams is dropped, and the op's own
                // deadline is shorter than that bound.
                api.postOutputStalls = true
                api.ops.send(
                    RunnerOp.Cmd(
                        "c1",
                        listOf("sh", "-c", "echo out; echo SECRET-VALUE >&2"),
                        mapOf("PATH" to "/usr/bin:/bin"),
                        null,
                        2_000,
                        w.sessionRoot.resolve("workdir").toString(),
                    ),
                )
                val r = api.result(timeoutMs = 30_000)

                assertEquals(0, r.exitCode)
                val cut = appender.list.map { it.formattedMessage }.filter { "output cut" in it }
                assertEquals(2, cut.size, "one warning per cut stream, got: $cut")
                assertTrue(cut.any { "stdout" in it }, "$cut")
                assertTrue(cut.any { "stderr" in it }, "$cut")
                cut.forEach { message ->
                    assertTrue("sess" in message && "c1" in message, "session and op are named: $message")
                    assertFalse("SECRET-VALUE" in message, "no chunk bytes may reach the log: $message")
                }
                assertEquals(0L, r.stdoutChunks, "a chunk the sink never took back is not reported as handed over")
                assertEquals(0L, r.stderrChunks)
                api.ops.send(RunnerOp.Close("cl", "done"))
                run.await()
            } finally {
                logback.detachAppender(appender)
            }
        }

    @Test
    fun `stdin reaches the command and the env is exactly the given one`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            api.ops.send(
                RunnerOp.Cmd(
                    "c1",
                    listOf("sh", "-c", "cat; echo \"A=\$A HOME=\${HOME:-unset}\""),
                    mapOf(
                        "PATH" to "/usr/bin:/bin",
                        "A" to "1",
                    ),
                    "from-stdin\n",
                    30_000,
                    w.sessionRoot.toString(),
                ),
            )
            val r = api.result()
            assertEquals(0, r.exitCode)
            assertEquals(
                "from-stdin\nA=1 HOME=unset\n",
                api.output.filter { it.first == "stdout" }.joinToString("") { it.third.decodeToString() },
            )
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `cancel kills the running command group and the result still arrives`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            api.ops.send(
                RunnerOp.Cmd(
                    "c1",
                    listOf("sh", "-c", "sleep 30 & wait"),
                    mapOf("PATH" to "/usr/bin:/bin"),
                    null,
                    60_000,
                    w.sessionRoot.toString(),
                ),
            )
            delay(300)
            val started = System.nanoTime()
            api.ops.send(RunnerOp.Cancel("c1"))
            val r = api.result()
            assertEquals("c1", r.opId)
            assertTrue((System.nanoTime() - started) < 5_000_000_000L, "cancel is prompt")
            assertTrue(r.exitCode != 0)
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a command past its timeout is killed and reported timedOut`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            api.ops.send(RunnerOp.Cmd("c1", listOf("sleep", "30"), mapOf("PATH" to "/usr/bin:/bin"), null, 300, w.sessionRoot.toString()))
            val r = api.result()
            assertTrue(r.timedOut)
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a cwd outside the session root is refused without starting anything`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            api.ops.send(RunnerOp.Cmd("c1", listOf("sh", "-c", "touch escaped"), emptyMap(), null, 1_000, tmp.toString()))
            val r = api.result()
            assertFalse(r.ok)
            assertFalse(tmp.resolve("escaped").exists())
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `upload extracts the fetched tar under remoteRelative and download posts a tar or reports missing`() =
        live {
            val src = tmp.resolve("src").createDirectories()
            src.resolve("in.txt").writeText("payload")
            api.tarToServe = ByteArrayOutputStream().also { TarStreams.pack(it, src) }.toByteArray()

            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()

            api.ops.send(RunnerOp.Upload("u1", "workdir"))
            assertTrue(api.result().ok)
            assertEquals("payload", w.sessionRoot.resolve("workdir/in.txt").readText())

            api.ops.send(RunnerOp.Upload("u2", "../escape"))
            assertFalse(api.result().ok, "an escaping remoteRelative is refused")

            api.ops.send(RunnerOp.Download("d1", "workdir", DownloadMode.MIRROR))
            val d1 = api.result()
            assertTrue(d1.ok && !d1.missing)
            val (kind, bytes) = api.postedTars.single()
            assertEquals("dir", kind)
            val back = tmp.resolve("back").createDirectories()
            TarStreams.extract(
                ByteArrayInputStream(bytes),
                back,
                DownloadMode.OVERWRITE,
                remoteIsDir = true,
                fileName = ".",
                sourceLabel = "test",
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )
            assertEquals("payload", back.resolve("in.txt").readText())

            api.ops.send(RunnerOp.Download("d2", "artifacts/nothing-here", DownloadMode.OVERLAY))
            val d2 = api.result()
            assertTrue(d2.ok && d2.missing)
            assertEquals(1, api.postedTars.size, "nothing posted for a missing path")

            api.ops.send(RunnerOp.CleanWorkspace("cw2"))
            assertTrue(api.result().ok)
            assertFalse(w.sessionRoot.resolve("workdir").exists())

            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `losing Homer for the heartbeat timeout kills the running command and ends the worker`() =
        live {
            val w = worker(heartbeat = 500.milliseconds)
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            api.ops.send(
                RunnerOp.Cmd(
                    "c1",
                    listOf("sh", "-c", "sleep 30; echo survived > $tmp/survived"),
                    mapOf("PATH" to "/usr/bin:/bin"),
                    null,
                    60_000,
                    w.sessionRoot.toString(),
                ),
            )
            delay(300)
            api.nextFailure = { IOException("connection refused") }

            run.await()
            assertTrue(w.lost)
            delay(200)
            assertFalse(tmp.resolve("survived").exists())
            assertNotNull(w.sessionRoot)
            assertNull(api.output.firstOrNull { it.third.decodeToString().contains("survived") })
        }

    @Test
    fun `a lost session deletes its root instead of leaving the run's files behind`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            w.sessionRoot.resolve("workdir/secret.txt").writeText("credentials")
            api.nextFailure = { RunnerApiException(404, "unknown session") }

            run.await()
            assertTrue(w.lost)
            assertFalse(w.sessionRoot.exists(), "a lost session must not leave its root on disk")
        }

    @Test
    fun `cancel for an unknown opId is a no-op and the worker keeps processing the next op`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()

            api.ops.send(RunnerOp.Cancel("does-not-exist"))
            api.ops.send(
                RunnerOp.Cmd("c1", listOf("sh", "-c", "echo ok"), mapOf("PATH" to "/usr/bin:/bin"), null, 5_000, w.sessionRoot.toString()),
            )
            val r = api.result()
            assertEquals(0, r.exitCode)

            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a postTar failure fails the download op with a non-blank error and the worker stays alive`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            w.sessionRoot.resolve("workdir/f.txt").writeText("data")

            api.postTarFailure = { RunnerApiException(413, "payload too large") }
            api.ops.send(RunnerOp.Download("d1", "workdir", DownloadMode.OVERLAY))
            val r = api.result()
            assertFalse(r.ok)
            assertTrue(r.error?.isNotBlank() == true)
            assertTrue(api.postedTars.isEmpty(), "the failed postTar never lands as a completed transfer")

            api.postTarFailure = null
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a cmd and an immediate cancel for the same op race safely regardless of registration order`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()

            val started = System.nanoTime()
            // Sent back-to-back with no delay: both are already queued in FakeRunnerApi
            // before the worker's poller drains either, so dispatchLoop can process the
            // Cancel before runCmd has registered "c1" in `running` — exactly the race
            // fix-round-1 item 3 closes.
            api.ops.send(
                RunnerOp.Cmd("c1", listOf("sleep", "30"), mapOf("PATH" to "/usr/bin:/bin"), null, 60_000, w.sessionRoot.toString()),
            )
            api.ops.send(RunnerOp.Cancel("c1"))
            val r = api.result()
            assertEquals("c1", r.opId)
            assertTrue((System.nanoTime() - started) < 10_000_000_000L, "the race still ends the command well before its 30 s sleep")

            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `non-cmd op results are delivered off the dispatcher so a sequence of them isn't gated by a poll window each`() =
        live {
            // Forces the worst case from the observed defect: every result-carrying `next` call
            // loses its race with the dedicated poller and never gets an op back on that same
            // call, so the fix has to keep the dispatcher moving on already-queued ops regardless.
            api.resultsNeverCarryOps = true
            api.nextPollMs = 2_000L
            val src = tmp.resolve("src").createDirectories()
            src.resolve("in.txt").writeText("payload")
            api.tarToServe = ByteArrayOutputStream().also { TarStreams.pack(it, src) }.toByteArray()

            val w = worker()
            val run = async { w.run() }
            val started = System.nanoTime()

            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            assertEquals("cw", api.result().opId)

            api.ops.send(RunnerOp.Upload("u1", "workdir"))
            assertTrue(api.result().ok)

            api.ops.send(RunnerOp.Close("cl", "done"))
            // Measures how fast the dispatcher itself progresses through the queued ops down to
            // Close, not how long run() takes to fully return: run() is a structured parent of the
            // (deliberately never-resolving-fast, here) background delivery jobs for the two ops
            // above, and per the fix waits for them like any other child on a graceful finish —
            // that wait is bounded by the fake server's behavior, not by the dispatcher's own
            // pace, which is exactly what this regression covers. Bounded so a regression hangs
            // instead of stalling the suite.
            withTimeout(1_500) {
                while (w.sessionRoot.exists()) delay(5)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 1_500, "the sequence should not cost a poll window per op, took ${elapsedMs}ms")

            run.await()
        }

    @Test
    fun `after the worker is lost, next is never called again even racing a late deliver`() =
        live {
            val w = worker(heartbeat = 500.milliseconds)
            val run = async { w.run() }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            api.result()
            // Still running when heartbeat is lost: killAll() kills it, and its deliver()
            // call (posting c1's result) races lost()'s ops.close() — exactly the window
            // fix-round-2 item covers (a "late deliver" trying to hand off after the local
            // queue is already closed must not throw and must not re-post to Homer).
            api.ops.send(
                RunnerOp.Cmd("c1", listOf("sleep", "30"), mapOf("PATH" to "/usr/bin:/bin"), null, 60_000, w.sessionRoot.toString()),
            )
            delay(300)
            api.nextFailure = { IOException("connection refused") }

            run.await()
            assertTrue(w.lost)
            val callsWhenDone = api.nextCallCount.get()
            assertTrue(callsWhenDone > 0, "sanity: next() was actually called during the run")
            delay(500)
            assertEquals(callsWhenDone, api.nextCallCount.get(), "no next() call happens after run() has returned")
        }

    @Test
    fun `a result Homer refuses with a client error is dropped without a retry`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.resultFailures += RunnerApiException(409, "stale op")
            api.ops.send(RunnerOp.CreateWorkspace("cw"))
            delay(1_500) // longer than one retry delay: a retry would have shown up by now

            assertEquals(1, api.resultAttempts.get())
            assertNull(api.results.tryReceive().getOrNull(), "a refused result is never recorded")
            assertFalse(w.lost)
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a result delivery that fails transiently is retried until it lands`() =
        live {
            val w = worker()
            val run = async { w.run() }
            api.resultFailures += RunnerApiException(503, "proxy hiccup")
            api.resultFailures += IOException("connection reset")
            api.ops.send(RunnerOp.CreateWorkspace("cw"))

            assertEquals("cw", api.result().opId)
            assertEquals(3, api.resultAttempts.get())
            assertFalse(w.lost)
            api.ops.send(RunnerOp.Close("cl", "done"))
            run.await()
        }

    @Test
    fun `a result that cannot be delivered within the retry budget drops the session`() =
        live {
            val w = worker()
            val run = async { w.run() }
            repeat(6) { api.resultFailures += IOException("connection refused") }
            api.ops.send(RunnerOp.CreateWorkspace("cw"))

            run.await()
            assertTrue(w.lost)
            assertEquals(6, api.resultAttempts.get(), "one attempt plus five retries")
            assertNull(api.results.tryReceive().getOrNull())
        }
}
