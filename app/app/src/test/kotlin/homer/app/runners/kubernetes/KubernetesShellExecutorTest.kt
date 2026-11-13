package homer.app.runners.kubernetes

import homer.app.exceptions.OutputCapExceededException
import homer.app.exceptions.PodFailedDuringExecutionException
import homer.app.infra.OutputLineFilter
import homer.app.models.PodTermination
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.CoreV1Event
import io.kubernetes.client.openapi.models.CoreV1EventList
import io.kubernetes.client.openapi.models.V1ContainerState
import io.kubernetes.client.openapi.models.V1ContainerStateTerminated
import io.kubernetes.client.openapi.models.V1ContainerStatus
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KubernetesShellExecutorTest {
    @Test
    fun `cmd prepends env prefix and returns exit code`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = mockk<CoreV1Api>(relaxed = true)
        var capturedArgv: List<String> = emptyList()
        val proc = fakeProcess(stdout = "hello\n", stderr = "", exit = 0)
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                capturedArgv = argv
                proc
            }

        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1")
        val result =
            executor.cmd(
                command = listOf("echo", "hello"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                env = mapOf("FOO" to "bar"),
                timeout = 10.seconds,
            )

        assertEquals(0, result.exitCode)
        assertEquals(listOf("env", "FOO=bar", "echo", "hello"), capturedArgv)
    }

    @Test
    fun `cmd pumps stdout and stderr to files`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = mockk<CoreV1Api>(relaxed = true)
        val proc = fakeProcess(stdout = "hi\n", stderr = "warn\n", exit = 0)
        val client = KubernetesExecClient { _, _, _, _ -> proc }
        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1")

        val out = tmp.resolve("out")
        val err = tmp.resolve("err")
        executor.cmd(listOf("echo"), out, err, timeout = 10.seconds)

        assertEquals("hi\n", out.readText())
        assertEquals("warn\n", err.readText())
    }

    @Test
    fun `stdOutFilter drops stdout lines and leaves stderr alone`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = mockk<CoreV1Api>(relaxed = true)
        val proc = fakeProcess(stdout = "keep 1\ndrop me\nkeep 2\n", stderr = "drop err\n", exit = 0)
        val client = KubernetesExecClient { _, _, _, _ -> proc }
        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1")

        val out = tmp.resolve("out")
        val err = tmp.resolve("err")
        executor.cmd(listOf("claude"), out, err, timeout = 10.seconds, stdOutFilter = OutputLineFilter { !it.contains("drop") })

        assertEquals("keep 1\nkeep 2\n", out.readText())
        assertEquals("drop err\n", err.readText())
    }

    @Test
    fun `cmd kills pod processes and appends TTL message on timeout`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = mockk<CoreV1Api>(relaxed = true)
        val neverFinishes =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } answers { stillRunning(firstArg(), secondArg()) }
                every { exitValue() } returns 137
                every { destroyForcibly() } returns this
            }
        val killProc = fakeProcess(stdout = "", stderr = "", exit = 0)
        var calls = 0
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                calls++
                if (argv.take(3) == listOf("sh", "-c", "kill -KILL -1")) killProc else neverFinishes
            }

        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1")
        val err = tmp.resolve("err")
        // Audit #8: timeout must propagate so callers fail fast.
        var thrown: Throwable? = null
        try {
            executor.cmd(
                command = listOf("sleep", "1000"),
                stdOut = tmp.resolve("out"),
                stdErr = err,
                timeout = 1.seconds,
            )
        } catch (e: Throwable) {
            thrown = e
        }

        assert(thrown is kotlinx.coroutines.CancellationException) {
            "expected CancellationException, got $thrown"
        }
        val errText = err.readText()
        assert(errText.contains("[HOMER] Command killed due to TTL timeout")) { "expected TTL marker in: $errText" }
        assertEquals(2, calls)
    }

    @Test
    fun `cmd raises PodFailedDuringExecutionException when exit code unavailable`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = mockk<CoreV1Api>(relaxed = true)
        every { api.readNamespacedPodStatus(any(), any()) } returns
            mockk(relaxed = true) {
                every { execute() } returns
                    io.kubernetes.client.openapi.models
                        .V1Pod()
            }
        every { api.listNamespacedEvent(any()) } returns
            mockk(relaxed = true) {
                every { fieldSelector(any()) } returns this
                every { execute() } returns
                    io.kubernetes.client.openapi.models
                        .CoreV1EventList()
            }
        val proc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } throws IllegalThreadStateException("channel closed")
            }
        val client = KubernetesExecClient { _, _, _, _ -> proc }

        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1")

        assertThrows<PodFailedDuringExecutionException> {
            executor.cmd(
                command = listOf("true"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                timeout = 10.seconds,
            )
        }
        verify { api.readNamespacedPodStatus(any(), any()) }
    }

    @Test
    fun `cmd should throw OutputCapExceededException when stdout exceeds maxOutputBytes (audit P1 #17)`(
        @TempDir tmp: Path,
    ) = runTest {
        val stdOut = tmp.resolve("cmd_0.out")
        val stdErr = tmp.resolve("cmd_0.err")
        val cap = 1024L

        val neverFinishes =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                // On each waitFor call, write past the cap into stdOut so the
                // cap check fires deterministically on the very next check.
                every { waitFor(any<Long>(), any<TimeUnit>()) } answers {
                    stdOut.toFile().writeBytes(ByteArray((cap * 2).toInt()) { 'A'.code.toByte() })
                    false
                }
            }
        val killProc = fakeProcess(stdout = "", stderr = "", exit = 0)
        var calls = 0
        val api = mockk<CoreV1Api>(relaxed = true)
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                calls++
                if (argv.take(3) == listOf("sh", "-c", "kill -KILL -1")) killProc else neverFinishes
            }

        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1", maxOutputBytes = cap)

        assertFailsWith<OutputCapExceededException> {
            executor.cmd(
                command = listOf("does-not-matter"),
                stdOut = stdOut,
                stdErr = stdErr,
                stdIn = null,
                env = emptyMap(),
                timeout = 5.seconds,
            )
        }

        val stderrContent = stdErr.toFile().readText()
        assertTrue(
            stderrContent.contains("[HOMER] Output cap exceeded"),
            "expected stderr to contain '[HOMER] Output cap exceeded' marker; got: $stderrContent",
        )
    }

    @Test
    fun `the cap marker survives stderr the pod flushes after the kill`(
        @TempDir tmp: Path,
    ) = runTest {
        val stdOut = tmp.resolve("cmd_0.out")
        val stdErr = tmp.resolve("cmd_0.err")
        val cap = 1024L
        val killed = CountDownLatch(1)
        // Delivers its bytes only once the exec is destroyed, and late, so the pump writes them
        // after the cap trip: the marker must land after them, not under them.
        val lateStderr =
            object : InputStream() {
                private var sent = false

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (sent) return -1
                    killed.await(5, TimeUnit.SECONDS)
                    Thread.sleep(50)
                    val tail = "late pod stderr\n".toByteArray()
                    tail.copyInto(b, off)
                    sent = true
                    return tail.size
                }
            }
        val overflowing =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns lateStderr
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } answers {
                    stdOut.toFile().writeBytes(ByteArray((cap * 2).toInt()) { 'A'.code.toByte() })
                    false
                }
                every { destroyForcibly() } answers {
                    killed.countDown()
                    self as Process
                }
            }
        val killProc = fakeProcess(stdout = "", stderr = "", exit = 0)
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                if (argv.take(3) == listOf("sh", "-c", "kill -KILL -1")) killProc else overflowing
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1", maxOutputBytes = cap)

        assertFailsWith<OutputCapExceededException> {
            executor.cmd(command = listOf("x"), stdOut = stdOut, stdErr = stdErr, timeout = 5.seconds)
        }

        val stderrContent = stdErr.readText()
        assertTrue(
            stderrContent.startsWith("late pod stderr\n") && stderrContent.endsWith("[HOMER] Output cap exceeded ($cap bytes)\n"),
            "expected the pod's late stderr followed by the cap marker; got: $stderrContent",
        )
    }

    @Test
    fun `a pump the kill does not unblock is abandoned past its bound and cannot overwrite the marker`(
        @TempDir tmp: Path,
    ) = runTest {
        val stdOut = tmp.resolve("cmd_0.out")
        val stdErr = tmp.resolve("cmd_0.err")
        val cap = 1024L
        val closed = CountDownLatch(1)
        // A wedged exec stream: destroyForcibly does not unblock it, only closing it does, and then
        // it still hands over bytes, as a blocked read whose buffer filled would.
        val wedgedStderr =
            object : InputStream() {
                private var sent = false

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (sent) return -1
                    closed.await()
                    val tail = "late pod stderr\n".toByteArray()
                    tail.copyInto(b, off)
                    sent = true
                    return tail.size
                }

                override fun close() = closed.countDown()
            }
        val overflowing =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns wedgedStderr
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } answers {
                    stdOut.toFile().writeBytes(ByteArray((cap * 2).toInt()) { 'A'.code.toByte() })
                    false
                }
            }
        val killProc = fakeProcess(stdout = "", stderr = "", exit = 0)
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                if (argv.take(3) == listOf("sh", "-c", "kill -KILL -1")) killProc else overflowing
            }
        val executor =
            KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1", maxOutputBytes = cap, pumpJoinMs = 200)

        val started = System.nanoTime()
        assertFailsWith<OutputCapExceededException> {
            executor.cmd(command = listOf("x"), stdOut = stdOut, stdErr = stdErr, timeout = 5.seconds)
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 4_000, "abort waited $tookMs ms on a wedged pump; the join must be bounded")
        assertEquals("\n[HOMER] Output cap exceeded ($cap bytes)\n", stdErr.readText())
    }

    @Test
    fun `an exited command whose exec stream stays open returns within the pump bound`(
        @TempDir tmp: Path,
    ) = runTest {
        // A background child in the pod keeps the stream open past the command's exit.
        val held = blockingUntilClosed()
        val exited =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns held.stream
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 0
            }
        val executor =
            KubernetesShellExecutor({ _, _, _, _ -> exited }, mockk(relaxed = true), "homer", "pod-1", pumpJoinMs = 200)

        val started = System.nanoTime()
        val result = executor.cmd(command = listOf("x"), stdOut = tmp.resolve("out"), stdErr = tmp.resolve("err"), timeout = 5.seconds)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, result.exitCode)
        assertTrue(tookMs < 4_000, "cmd waited $tookMs ms on a held exec stream; the join must be bounded")
        assertTrue(held.closed, "the abandoned pump must close the exec stream")
    }

    @Test
    fun `the stdout tail a late-closing exec stream delivers is in the result when cmd returns`(
        @TempDir tmp: Path,
    ) = runTest {
        // The pod keeps stdout open past the exit and delivers its last line only then, while stderr
        // closed with the command: only joining the stdout pump gets that line into the result file.
        val exited = CountDownLatch(1)
        val lateClosing = lateClosingProcess(exited, lateStdout = "last stdout line\n")
        val executor =
            KubernetesShellExecutor({ _, _, _, _ -> lateClosing }, mockk(relaxed = true), "homer", "pod-1", pumpJoinMs = 2_000)

        val out = tmp.resolve("out")
        val err = tmp.resolve("err")
        val result = executor.cmd(command = listOf("x"), stdOut = out, stdErr = err, timeout = 5.seconds)

        assertEquals(0, result.exitCode)
        assertTrue(out.readText().endsWith("last stdout line\n"), "lost the stdout tail: '${out.readText()}'")
        assertEquals("early stderr\n", err.readText())
    }

    @Test
    fun `the stderr tail a late-closing exec stream delivers is in the result when cmd returns`(
        @TempDir tmp: Path,
    ) = runTest {
        // The same, with stderr the stream the pod holds open: joining the stdout pump alone is not enough.
        val exited = CountDownLatch(1)
        val lateClosing = lateClosingProcess(exited, lateStderr = "last stderr line\n")
        val executor =
            KubernetesShellExecutor({ _, _, _, _ -> lateClosing }, mockk(relaxed = true), "homer", "pod-1", pumpJoinMs = 2_000)

        val out = tmp.resolve("out")
        val err = tmp.resolve("err")
        val result = executor.cmd(command = listOf("x"), stdOut = out, stdErr = err, timeout = 5.seconds)

        assertEquals(0, result.exitCode)
        assertEquals("early stdout\n", out.readText())
        assertTrue(err.readText().endsWith("last stderr line\n"), "lost the stderr tail: '${err.readText()}'")
    }

    @Test
    fun `an exited command whose pod never drained its stdin returns within the bound`(
        @TempDir tmp: Path,
    ) = runTest {
        val closed = CountDownLatch(1)
        // The pod never reads its stdin and a child holds the exec open: every write blocks until close.
        val undrainedStdin =
            object : OutputStream() {
                override fun write(b: Int) {
                    closed.await()
                    throw java.io.IOException("stream closed")
                }

                override fun close() = closed.countDown()
            }
        val exited =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns undrainedStdin
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 0
            }
        val executor =
            KubernetesShellExecutor({ _, _, _, _ -> exited }, mockk(relaxed = true), "homer", "pod-1", pumpJoinMs = 200)

        val started = System.nanoTime()
        val result =
            executor.cmd(
                command = listOf("x"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                stdIn = "x".repeat(1_000_000),
                timeout = 5.seconds,
            )
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, result.exitCode)
        assertTrue(tookMs < 4_000, "cmd waited $tookMs ms on an undrained stdin; the stdin join must be bounded")
        assertEquals(0L, closed.count, "the stdin stream must be closed past the bound")
    }

    @Test
    fun `a pump that cannot write closes the exec stream so the command can finish`(
        @TempDir tmp: Path,
    ) = runTest {
        // The pod command only exits once its stderr is drained or closed, as one blocked on a full pipe would.
        val stderrPipe = blockingUntilClosed()
        val blockedOnStderr =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns stderrPipe.stream
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } answers { stderrPipe.closed }
                every { exitValue() } returns 0
            }
        val executor = KubernetesShellExecutor({ _, _, _, _ -> blockedOnStderr }, mockk(relaxed = true), "homer", "pod-1")

        // The pump cannot open its file: the directory does not exist.
        val result =
            executor.cmd(
                command = listOf("x"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("missing-dir/err"),
                timeout = 3.seconds,
            )

        assertEquals(0, result.exitCode)
    }

    /** An exec stream that blocks every read until it is closed, then ends. */
    private class BlockingStream {
        private val released = CountDownLatch(1)

        val closed get() = released.count == 0L

        val stream =
            object : InputStream() {
                override fun read(): Int {
                    released.await()
                    return -1
                }

                override fun close() = released.countDown()
            }
    }

    /**
     * An exec stream whose [tail] and EOF arrive once [gate] opens — i.e. after the command exited —
     * and late enough that the pump has to be joined for the tail to be written before it is read.
     */
    private class LateTailStream(
        private val gate: CountDownLatch,
        private val tail: String,
    ) : InputStream() {
        private var sent = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (sent) return -1
            gate.await(5, TimeUnit.SECONDS)
            Thread.sleep(100)
            val bytes = tail.toByteArray()
            bytes.copyInto(b, off)
            sent = true
            return bytes.size
        }
    }

    /**
     * An exiting process whose [lateStdout] or [lateStderr] stream stays open past the exit and
     * delivers that tail once [exited] opens; the other stream closed with the command.
     */
    private fun lateClosingProcess(
        exited: CountDownLatch,
        lateStdout: String? = null,
        lateStderr: String? = null,
    ): Process {
        fun stream(
            late: String?,
            early: String,
        ) = if (late == null) ByteArrayInputStream(early.toByteArray()) else LateTailStream(exited, late)
        return mockk(relaxed = true) {
            every { inputStream } returns stream(lateStdout, "early stdout\n")
            every { errorStream } returns stream(lateStderr, "early stderr\n")
            every { outputStream } returns ByteArrayOutputStream()
            every { waitFor(any<Long>(), any<TimeUnit>()) } answers {
                exited.countDown()
                true
            }
            every { exitValue() } returns 0
        }
    }

    private fun blockingUntilClosed() = BlockingStream()

    @Test
    fun `cmd raises ExecChannelLost on the client no-exit-status sentinel`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = runningPodApi()
        // client-java's ExecProcess returns -1975219 from exitValue() when the
        // websocket closed without a status (e.g. the 101 upgrade was refused).
        val proc = fakeProcess(stdout = "", stderr = "", exit = EXEC_NO_EXIT_STATUS_SENTINEL)
        val client = KubernetesExecClient { _, _, _, _ -> proc }
        val seen = mutableListOf<PodTermination>()

        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1", onTermination = { seen += it })

        val thrown =
            assertThrows<PodFailedDuringExecutionException> {
                executor.cmd(
                    command = listOf("true"),
                    stdOut = tmp.resolve("out"),
                    stdErr = tmp.resolve("err"),
                    timeout = 10.seconds,
                )
            }
        assertEquals(EXEC_CHANNEL_LOST, thrown.reason)
        assertTrue(
            thrown.message.orEmpty().contains("exec channel to pod pod-1 was lost while the pod was running"),
            "the step error must name the loss: ${thrown.message}",
        )
        assertTrue(
            thrown.message.orEmpty().contains("closed without an exit status"),
            "the step error must say the channel closed without a status: ${thrown.message}",
        )
        assertEquals(1, seen.size)
        assertEquals(EXEC_CHANNEL_LOST, seen.single().reason)
        // Composed once: the step error is the same self-describing sentence the termination keeps,
        // not a prefixed repeat of it.
        assertEquals(seen.single().message, thrown.message)
    }

    @Test
    fun `a close without a status on a running pod reports the cause`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = runningPodApi("BackOff: restarting the container")
        val closed =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } throws IllegalThreadStateException("channel closed")
            }
        val seen = mutableListOf<PodTermination>()
        val executor =
            KubernetesShellExecutor(
                KubernetesExecClient { _, _, _, _ -> closed },
                api,
                "homer",
                "pod-1",
                onTermination = { seen += it },
            )

        val thrown = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals(EXEC_CHANNEL_LOST, thrown.reason)
        assertTrue(
            thrown.message.orEmpty().contains("IllegalThreadStateException: channel closed"),
            "the step error must carry the websocket cause: ${thrown.message}",
        )
        assertTrue(thrown.message.orEmpty().contains("BackOff: restarting the container"), "and the last pod event: ${thrown.message}")
        assertTrue(thrown.cause is IllegalThreadStateException, "the cause must survive, got ${thrown.cause}")
        assertEquals(EXEC_CHANNEL_LOST, seen.single().reason)
    }

    @Test
    fun `a lost channel kills the leftover command before the step fails`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val proc = fakeProcess(stdout = "", stderr = "", exit = EXEC_NO_EXIT_STATUS_SENTINEL)
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                proc
            }
        var killedBeforeFailing = false
        val executor =
            KubernetesShellExecutor(
                client,
                runningPodApi(),
                "homer",
                "pod-1",
                onTermination = { killedBeforeFailing = started.any { it == KILL_ARGV } },
            )

        assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertTrue(killedBeforeFailing, "the leftover command must be killed before the step fails: $started")
        verify { proc.destroyForcibly() }
    }

    @Test
    fun `a failing kill still fails the step as a lost channel`(
        @TempDir tmp: Path,
    ) = runTest {
        val proc = fakeProcess(stdout = "", stderr = "", exit = EXEC_NO_EXIT_STATUS_SENTINEL)
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                if (argv == KILL_ARGV) throw IllegalStateException("exec refused")
                proc
            }
        val seen = mutableListOf<PodTermination>()
        val executor = KubernetesShellExecutor(client, runningPodApi(), "homer", "pod-1", onTermination = { seen += it })

        val thrown = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals(EXEC_CHANNEL_LOST, thrown.reason)
        assertEquals(EXEC_CHANNEL_LOST, seen.single().reason)
    }

    @Test
    fun `a secret env value in the cause text is redacted`(
        @TempDir tmp: Path,
    ) = runTest {
        val secret = "supersecretvalue"
        val closed =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } throws IllegalThreadStateException("wss://api/exec?command=env+TOKEN=$secret")
            }
        val seen = mutableListOf<PodTermination>()
        val executor =
            KubernetesShellExecutor(
                KubernetesExecClient { _, _, _, _ -> closed },
                runningPodApi(),
                "homer",
                "pod-1",
                onTermination = { seen += it },
            )

        val thrown =
            assertFailsWith<PodFailedDuringExecutionException> {
                executor.cmd(
                    command = listOf("true"),
                    stdOut = tmp.resolve("out"),
                    stdErr = tmp.resolve("err"),
                    env = mapOf("TOKEN" to secret, "SHORT" to "abc"),
                    timeout = 10.seconds,
                )
            }

        assertFalse(thrown.message.orEmpty().contains(secret), "the secret must not reach the step error: ${thrown.message}")
        assertTrue(thrown.message.orEmpty().contains("***"), "the secret must be masked: ${thrown.message}")
        assertFalse(seen.single().message.contains(secret), "the secret must not reach the termination: ${seen.single().message}")
        assertTrue(seen.single().message.contains("***"), "the secret must be masked there too: ${seen.single().message}")
    }

    @Test
    fun `a query-encoded secret in the cause text is redacted`(
        @TempDir tmp: Path,
    ) = runTest {
        // The cause quotes the exec URL, where the argv is query-encoded, so a secret holding a
        // reserved character never appears raw there — its encoded form must be masked too.
        val secret = "a/b+c=d&e"
        val encoded = "a%2Fb%2Bc%3Dd%26e"
        val closed =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } throws IllegalThreadStateException("wss://api/exec?command=env+TOKEN=$encoded")
            }
        val seen = mutableListOf<PodTermination>()
        val executor =
            KubernetesShellExecutor(
                KubernetesExecClient { _, _, _, _ -> closed },
                runningPodApi(),
                "homer",
                "pod-1",
                onTermination = { seen += it },
            )

        val thrown =
            assertFailsWith<PodFailedDuringExecutionException> {
                executor.cmd(
                    command = listOf("true"),
                    stdOut = tmp.resolve("out"),
                    stdErr = tmp.resolve("err"),
                    env = mapOf("TOKEN" to secret, "SHORT" to "abc"),
                    timeout = 10.seconds,
                )
            }

        assertFalse(thrown.message.orEmpty().contains(encoded), "the query-encoded secret must not reach the step error: ${thrown.message}")
        assertTrue(thrown.message.orEmpty().contains("***"), "the secret must be masked: ${thrown.message}")
        assertFalse(seen.single().message.contains(encoded), "the encoded secret must not reach the termination: ${seen.single().message}")
        assertTrue(seen.single().message.contains("***"), "the secret must be masked there too: ${seen.single().message}")
    }

    @Test
    fun `a channel-lost message over the bound is cut`(
        @TempDir tmp: Path,
    ) = runTest {
        val closed =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } throws IllegalThreadStateException("x".repeat(600))
            }
        val seen = mutableListOf<PodTermination>()
        val executor =
            KubernetesShellExecutor(
                KubernetesExecClient { _, _, _, _ -> closed },
                runningPodApi(),
                "homer",
                "pod-1",
                onTermination = { seen += it },
            )

        assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals(512, seen.single().message.length)
    }

    @Test
    fun `cmd feeds stdin to the exec channel`(
        @TempDir tmp: Path,
    ) = runTest {
        val stdin = ByteArrayOutputStream()
        val proc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns stdin
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } returns 0
            }
        var stdinRequested = false
        val client =
            KubernetesExecClient { _, _, _, withStdin ->
                stdinRequested = withStdin
                proc
            }

        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1")
        executor.cmd(listOf("sh", "-s", "-"), tmp.resolve("out"), tmp.resolve("err"), stdIn = "echo hi", timeout = 10.seconds)

        assertTrue(stdinRequested)
        assertEquals("echo hi", stdin.toString(Charsets.UTF_8))
    }

    @Test
    fun `a command that exits before a poll sees the cap still fails once its output is flushed`(
        @TempDir tmp: Path,
    ) = runTest {
        val cap = 1024L
        val proc = fakeProcess(stdout = "A".repeat((cap * 2).toInt()), stderr = "", exit = 0)
        val client = KubernetesExecClient { _, _, _, _ -> proc }
        val stdErr = tmp.resolve("err")

        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1", maxOutputBytes = cap)

        assertFailsWith<OutputCapExceededException> {
            executor.cmd(listOf("yes"), tmp.resolve("out"), stdErr, timeout = 10.seconds)
        }
        assertTrue(stdErr.readText().contains("[HOMER] Output cap exceeded ($cap bytes)"))
    }

    /** A pod Kubernetes did not stop, with [events] as its events for the channel-lost diagnostics. */
    private fun runningPodApi(vararg events: String): CoreV1Api {
        val api = podStatusApi(podWithStatus("Running"))
        every { api.listNamespacedEvent(any()) } returns
            mockk(relaxed = true) {
                every { fieldSelector(any()) } returns this
                every { execute() } returns
                    CoreV1EventList().items(events.map { CoreV1Event().type("Warning").reason("X").message(it) })
            }
        return api
    }

    private fun stoppedPodApi(
        pod: V1Pod = podWithStatus("Failed", "DeadlineExceeded", "Pod was active longer than the deadline"),
    ): CoreV1Api {
        val api = podStatusApi(pod)
        every { api.listNamespacedEvent(any()) } returns
            mockk(relaxed = true) {
                every { fieldSelector(any()) } returns this
                every { execute() } returns CoreV1EventList()
            }
        return api
    }

    private suspend fun run(
        executor: KubernetesShellExecutor,
        tmp: Path,
    ) = executor.cmd(listOf("sleep"), tmp.resolve("out"), tmp.resolve("err"), timeout = 10.seconds)

    @Test
    fun `a command killed by the pod deadline fails with the termination`(
        @TempDir tmp: Path,
    ) = runTest {
        val seen = mutableListOf<PodTermination>()
        val client = KubernetesExecClient { _, _, _, _ -> fakeProcess("", "", 137) }
        val executor =
            KubernetesShellExecutor(client, stoppedPodApi(), "homer", "pod-1", maxRunSeconds = 60, onTermination = { seen += it })

        val e = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals("DeadlineExceeded", e.termination?.reason)
        assertEquals("Kubernetes stopped the pod: maxRunSeconds (60 s) exceeded (DeadlineExceeded)", e.message)
        assertEquals(listOf(e.termination), seen)
    }

    @Test
    fun `a non-zero exit on a running pod stays a command result`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = podStatusApi(podWithStatus("Running"))
        var called = false
        val client = KubernetesExecClient { _, _, _, _ -> fakeProcess("", "", 1) }
        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1", onTermination = { called = true })

        assertEquals(1, run(executor, tmp).exitCode)
        verify(exactly = 1) { api.readNamespacedPodStatus(any(), any()) }
        assertTrue(!called)
    }

    @Test
    fun `a zero exit reads no pod status`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = podStatusApi(podWithStatus("Failed"))
        val client = KubernetesExecClient { _, _, _, _ -> fakeProcess("", "", 0) }

        assertEquals(0, run(KubernetesShellExecutor(client, api, "homer", "pod-1"), tmp).exitCode)
        verify(exactly = 0) { api.readNamespacedPodStatus(any(), any()) }
    }

    @Test
    fun `a channel failure on a deleted pod reports PodDeleted`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = podStatusApi(error = ApiException(404, "gone"))
        every { api.listNamespacedEvent(any()) } returns
            mockk(relaxed = true) {
                every { fieldSelector(any()) } returns this
                every { execute() } returns CoreV1EventList()
            }
        val proc =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
                every { exitValue() } throws IllegalThreadStateException("channel closed")
            }
        var starts = 0
        val executor =
            KubernetesShellExecutor(
                KubernetesExecClient { _, _, _, _ ->
                    starts++
                    proc
                },
                api,
                "homer",
                "pod-1",
            )

        val e = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals("PodDeleted", e.termination?.reason)
        assertEquals(1, starts) // only the command's exec: a pod Kubernetes deleted is never killed pod-wide
    }

    @Test
    fun `a pod-read error that is not a 404 is no stop, and the lost channel still kills the leftover command`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val proc = fakeProcess(stdout = "", stderr = "", exit = EXEC_NO_EXIT_STATUS_SENTINEL)
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                proc
            }
        val api = podStatusApi(error = ApiException(500, "boom"))
        every { api.listNamespacedEvent(any()) } returns
            mockk(relaxed = true) {
                every { fieldSelector(any()) } returns this
                every { execute() } returns CoreV1EventList()
            }
        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1")

        val e = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals(EXEC_CHANNEL_LOST, e.reason)
        assertTrue(KILL_ARGV in started, "the leftover command must still be killed: $started")
    }

    @Test
    fun `an ordinary non-zero exit on an evicted pod fails with the termination`(
        @TempDir tmp: Path,
    ) = runTest {
        val seen = mutableListOf<PodTermination>()
        val api = stoppedPodApi(podWithStatus("Failed", "Evicted", "The node was low on resource: memory."))
        val executor = KubernetesShellExecutor(killedClient(1), api, "homer", "pod-1", onTermination = { seen += it })

        val e = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals("Evicted", e.termination?.reason)
        assertEquals("Kubernetes stopped the pod: The node was low on resource: memory. (Evicted)", e.message)
        assertEquals(listOf(e.termination), seen)
        verify(exactly = 2) { api.readNamespacedPodStatus(any(), any()) } // 1 status read + 1 diagnostics read
    }

    @Test
    fun `a failed exec channel on an OOM-killed pod fails with the termination`(
        @TempDir tmp: Path,
    ) = runTest {
        val seen = mutableListOf<PodTermination>()
        val oomKilled = podWithStatus("Running", mainTerminated = V1ContainerStateTerminated().reason("OOMKilled").exitCode(137))
        var starts = 0
        val client =
            KubernetesExecClient { _, _, _, _ ->
                starts++
                fakeProcess("", "", EXEC_NO_EXIT_STATUS_SENTINEL)
            }
        val executor = KubernetesShellExecutor(client, stoppedPodApi(oomKilled), "homer", "pod-1", onTermination = { seen += it })

        val e = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals("OOMKilled", e.termination?.reason)
        assertEquals(listOf(e.termination), seen)
        assertEquals(1, starts) // only the command's exec: a pod Kubernetes stopped is never killed pod-wide
    }

    @Test
    fun `a failed exec channel never carries a container's agent-written termination log`(
        @TempDir tmp: Path,
    ) = runTest {
        val written = V1ContainerStateTerminated().reason("Error").exitCode(1).message("token=SECRET\nforged line")
        val sidecarFailed =
            V1Pod().status(
                V1PodStatus().phase("Running").containerStatuses(
                    listOf(V1ContainerStatus().name("side").state(V1ContainerState().terminated(written))),
                ),
            )
        val executor =
            KubernetesShellExecutor(
                killedClient(EXEC_NO_EXIT_STATUS_SENTINEL),
                sequencedPodApi(sidecarFailed, sidecarFailed),
                "homer",
                "pod-1",
            )

        val e = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals(EXEC_CHANNEL_LOST, e.reason)
        assertTrue(
            e.message.orEmpty().contains("Container 'side' terminated with exit code 1"),
            "the failed container is named by its exit code: ${e.message}",
        )
        assertFalse(e.message.orEmpty().contains("SECRET"))
    }

    private fun sequencedPodApi(
        vararg pods: V1Pod,
        error: Exception? = null,
    ): CoreV1Api {
        val req = mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>()
        var n = 0
        every { req.execute() } answers {
            val i = n++
            if (i < pods.size) pods[i] else throw (error ?: IllegalStateException("unexpected read #$i"))
        }
        val api = mockk<CoreV1Api> { every { readNamespacedPodStatus(any(), any()) } returns req }
        every { api.listNamespacedEvent(any()) } returns
            mockk(relaxed = true) {
                every { fieldSelector(any()) } returns this
                every { execute() } returns CoreV1EventList()
            }
        return api
    }

    private fun killedClient(exit: Int) = KubernetesExecClient { _, _, _, _ -> fakeProcess("", "", exit) }

    @Test
    fun `a deadline kill whose pod status lags the exec exit still reports DeadlineExceeded`(
        @TempDir tmp: Path,
    ) = runTest {
        val running = podWithStatus("Running")
        val failed = podWithStatus("Failed", "DeadlineExceeded", "Pod was active longer than the deadline")
        val api = sequencedPodApi(running, running, failed, failed)
        val executor =
            KubernetesShellExecutor(
                killedClient(137),
                api,
                "homer",
                "pod-1",
                maxRunSeconds = 60,
                stopReasonPollMs = 10,
                stopReasonBudgetMs = 500,
            )

        val e = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals("DeadlineExceeded", e.termination?.reason)
        verify(exactly = 4) { api.readNamespacedPodStatus(any(), any()) } // 3 status reads + 1 diagnostics read
    }

    @Test
    fun `a signal exit on a pod that stays healthy gives up after the budget and returns the command result`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = podStatusApi(podWithStatus("Running"))
        val executor =
            KubernetesShellExecutor(killedClient(143), api, "homer", "pod-1", stopReasonPollMs = 10, stopReasonBudgetMs = 50)

        assertEquals(143, run(executor, tmp).exitCode)
        verify(exactly = 6) { api.readNamespacedPodStatus(any(), any()) }
    }

    @Test
    fun `an ordinary non-zero exit reads the pod once`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = podStatusApi(podWithStatus("Running"))
        val executor =
            KubernetesShellExecutor(killedClient(1), api, "homer", "pod-1", stopReasonPollMs = 10, stopReasonBudgetMs = 50)

        assertEquals(1, run(executor, tmp).exitCode)
        verify(exactly = 1) { api.readNamespacedPodStatus(any(), any()) }
    }

    @Test
    fun `a 404 during the retry reports PodDeleted at once`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = sequencedPodApi(podWithStatus("Running"), error = ApiException(404, "gone"))
        val executor =
            KubernetesShellExecutor(killedClient(137), api, "homer", "pod-1", stopReasonPollMs = 10, stopReasonBudgetMs = 500)

        val e = assertFailsWith<PodFailedDuringExecutionException> { run(executor, tmp) }

        assertEquals("PodDeleted", e.termination?.reason)
        verify(exactly = 3) { api.readNamespacedPodStatus(any(), any()) } // 2 status reads + 1 diagnostics read
    }

    @Test
    fun `a read error during the retry does not mask the command result`(
        @TempDir tmp: Path,
    ) = runTest {
        val api = sequencedPodApi(podWithStatus("Running"), error = ApiException(500, "boom"))
        val executor =
            KubernetesShellExecutor(killedClient(137), api, "homer", "pod-1", stopReasonPollMs = 10, stopReasonBudgetMs = 50)

        assertEquals(137, run(executor, tmp).exitCode)
    }

    @Test
    fun `cancellation during the retry propagates and stops further reads`(
        @TempDir tmp: Path,
    ) = runTest {
        val running = podWithStatus("Running")
        val req = mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>()
        val api = mockk<CoreV1Api> { every { readNamespacedPodStatus(any(), any()) } returns req }
        lateinit var job: Job
        every { req.execute() } answers {
            job.cancel()
            running
        }
        val executor =
            KubernetesShellExecutor(killedClient(137), api, "homer", "pod-1", stopReasonPollMs = 10, stopReasonBudgetMs = 500)

        job = launch { run(executor, tmp) }
        job.join()

        assertTrue(job.isCancelled)
        verify(exactly = 1) { req.execute() }
    }

    @Test
    fun `a grace launches the command under setsid and records its pgid in the workspace dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                fakeProcess("", "", 0)
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1")

        val result =
            executor.cmd(
                command = listOf("echo", "hi"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                env = mapOf("HMR_WORK_DIR" to "/homer/workspace/workdir"),
                timeout = 10.seconds,
                interruptGrace = 5.seconds,
            )

        assertEquals(0, result.exitCode)
        assertEquals(listOf("setsid", "-w", "true"), started.first()) // the one-shot capability probe
        val argv = started.single { "echo" in it }
        assertEquals(listOf("env", "HMR_WORK_DIR=/homer/workspace/workdir"), argv.take(2))
        assertEquals(listOf("setsid", "-w", "sh", "-c"), argv.drop(2).take(4))
        assertEquals(listOf("echo", "hi"), argv.takeLast(2))
        assertTrue("/homer/workspace/workdir/homer-exec-1.pgid" in argv, "the pgid file must sit in the workspace dir: $argv")
    }

    @Test
    fun `without a workspace env the pgid file is a dot-homer path relative to the cwd`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                fakeProcess("", "", 0)
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1")

        executor.cmd(
            command = listOf("echo"),
            stdOut = tmp.resolve("out"),
            stdErr = tmp.resolve("err"),
            timeout = 10.seconds,
            interruptGrace = 5.seconds,
        )

        assertTrue(".homer/homer-exec-1.pgid" in started.single { "echo" in it }, "expected a .homer pgid path: $started")
    }

    @Test
    fun `without a grace the exec argv and the call count are unchanged`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                fakeProcess("", "", 0)
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1")

        executor.cmd(
            command = listOf("echo", "hi"),
            stdOut = tmp.resolve("out"),
            stdErr = tmp.resolve("err"),
            env = mapOf("HMR_WORK_DIR" to "/homer/workspace/workdir", "FOO" to "bar"),
            timeout = 10.seconds,
        )

        assertEquals(listOf(listOf("env", "HMR_WORK_DIR=/homer/workspace/workdir", "FOO=bar", "echo", "hi")), started)
    }

    @Test
    fun `a graced timeout interrupts the group and runs no pod-wide kill`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                if (isSignalling(argv) || argv.last() == "true") fakeProcess("", "", 0) else neverFinishing()
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1")

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            executor.cmd(
                command = listOf("sleep", "1000"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                env = mapOf("HMR_WORK_DIR" to "/homer/workspace/workdir"),
                timeout = 1.seconds,
                interruptGrace = 1.seconds,
            )
        }

        assertTrue(started.none { it == KILL_ARGV }, "the group interrupt replaces the pod-wide kill: $started")
        val script = started.single { isSignalling(it) }[2]
        val guard = script.indexOf(KubernetesShellExecutor.PGID_GUARD)
        val interrupt = script.indexOf("kill -INT -\"\$p\"")
        val kill = script.indexOf("kill -KILL -\"\$p\"")
        assertTrue(script.contains("cat \"\$1\""), "the pgid must come from the recorded file: $script")
        assertTrue(guard >= 0, "the pgid must be validated: $script")
        assertTrue(interrupt > guard, "the group is signalled only after the pgid is validated: $script")
        assertTrue(kill > interrupt, "SIGKILL follows the interrupt: $script")
        assertFalse(script.contains("kill -INT -1") || script.contains("kill -KILL -1"), "never target -1: $script")
    }

    @Test
    fun `the pgid guard refuses anything that is not a plain integer greater than 1`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("pgid")
        for (content in listOf("", "0", "1", "-1", "abc", "1 2", "-0", "12a", "9999999999999999999999")) {
            file.toFile().writeText(content)
            assertTrue(runGuard(file) != 0, "content '$content' must be refused, not signalled")
        }
        file.toFile().writeText("12345")
        assertEquals(0, runGuard(file), "a plain pgid greater than 1 must pass the guard")
    }

    /** Runs the guard exactly as the signalling exec does, but on its own: no kill can follow it. */
    private fun runGuard(pgidFile: Path): Int =
        ProcessBuilder(
            "sh",
            "-c",
            "p=\$(cat \"\$1\" 2>/dev/null); ${KubernetesShellExecutor.PGID_GUARD}; exit 0",
            "homer-signal",
            pgidFile.toString(),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()

    @Test
    fun `a refused pgid falls back to the pod-wide kill`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                // The signalling exec rejected the pgid: no valid group was interrupted.
                when {
                    argv.last() == "true" -> fakeProcess("", "", 0)
                    isSignalling(argv) -> fakeProcess("", "", KubernetesShellExecutor.SIGNAL_REJECTED)
                    else -> neverFinishing()
                }
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1")

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            executor.cmd(
                command = listOf("sleep", "1000"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                env = mapOf("HMR_WORK_DIR" to "/homer/workspace/workdir"),
                timeout = 1.seconds,
                interruptGrace = 1.seconds,
            )
        }

        assertTrue(KILL_ARGV in started, "the pod-wide kill must run when the pgid is refused: $started")
    }

    @Test
    fun `a signalling exec that fails to start falls back to the pod-wide kill`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                when {
                    argv.last() == "true" -> fakeProcess("", "", 0)
                    isSignalling(argv) -> throw IllegalStateException("exec refused")
                    else -> neverFinishing()
                }
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1")

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            executor.cmd(
                command = listOf("sleep", "1000"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                env = mapOf("HMR_WORK_DIR" to "/homer/workspace/workdir"),
                timeout = 1.seconds,
                interruptGrace = 1.seconds,
            )
        }

        assertTrue(KILL_ARGV in started, "the pod-wide kill must run when the signalling exec cannot start: $started")
    }

    @Test
    fun `a hung signalling exec is abandoned at its bound and the pod-wide kill still runs`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val hungSignal =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<TimeUnit>()) } answers { stillRunning(firstArg(), secondArg()) }
            }
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                when {
                    argv.last() == "true" -> fakeProcess("", "", 0)
                    isSignalling(argv) -> hungSignal
                    else -> neverFinishing()
                }
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1", signalExecSlackMs = 200)

        val startedAt = System.nanoTime()
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            executor.cmd(
                command = listOf("sleep", "1000"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                env = mapOf("HMR_WORK_DIR" to "/homer/workspace/workdir"),
                timeout = 1.seconds,
                interruptGrace = 1.seconds,
            )
        }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(tookMs < 5_000, "the abort waited $tookMs ms on a hung signalling exec; it must be bounded")
        assertTrue(KILL_ARGV in started, "the pod-wide kill must run after a hung signalling exec: $started")
        verify { hungSignal.destroyForcibly() }
    }

    @Test
    fun `a pod without setsid is launched unchanged and killed pod-wide`(
        @TempDir tmp: Path,
    ) = runTest {
        val started = mutableListOf<List<String>>()
        val client =
            KubernetesExecClient { _, _, argv, _ ->
                started += argv
                // No setsid: both probe forms fail, so the command runs exactly as it does today.
                if (argv.last() == "true") throw IllegalStateException("setsid: not found") else neverFinishing()
            }
        val executor = KubernetesShellExecutor(client, mockk(relaxed = true), "homer", "pod-1")

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            executor.cmd(
                command = listOf("sleep", "1000"),
                stdOut = tmp.resolve("out"),
                stdErr = tmp.resolve("err"),
                env = mapOf("HMR_WORK_DIR" to "/homer/workspace/workdir"),
                timeout = 1.seconds,
                interruptGrace = 1.seconds,
            )
        }

        assertEquals(listOf(listOf("setsid", "-w", "true"), listOf("setsid", "true")), started.take(2))
        assertEquals(listOf("env", "HMR_WORK_DIR=/homer/workspace/workdir", "sleep", "1000"), started[2])
        assertTrue(KILL_ARGV in started, "without setsid the kill stays pod-wide: $started")
    }

    /** True when [argv] is the executor's own signalling exec, not the command's. */
    private fun isSignalling(argv: List<String>) = argv.getOrNull(3) == "homer-signal"

    /**
     * `waitFor` of a not-exited process: it waits (capped at [STILL_RUNNING_WAIT_MS]), then answers false.
     * An instant false busy-spins the executor's poll loop for the step's whole timeout.
     */
    private fun stillRunning(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        Thread.sleep(unit.toMillis(timeout).coerceIn(0, STILL_RUNNING_WAIT_MS))
        return false
    }

    private fun neverFinishing(): Process =
        mockk(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { errorStream } returns ByteArrayInputStream(ByteArray(0))
            every { outputStream } returns ByteArrayOutputStream()
            every { waitFor(any<Long>(), any<TimeUnit>()) } answers { stillRunning(firstArg(), secondArg()) }
            every { exitValue() } returns 137
            every { destroyForcibly() } returns this
        }

    private fun fakeProcess(
        stdout: String,
        stderr: String,
        exit: Int,
    ): Process =
        mockk(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(stdout.toByteArray())
            every { errorStream } returns ByteArrayInputStream(stderr.toByteArray())
            every { outputStream } returns ByteArrayOutputStream()
            every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
            every { exitValue() } returns exit
        }

    private companion object {
        /** The pod-wide kill the channel-loss path runs, as the executor's killInsidePod starts it. */
        val KILL_ARGV = listOf("sh", "-c", "kill -KILL -1")

        /** The longest a not-exited fake process waits per `waitFor`: short, so a test's timeout stays its length. */
        const val STILL_RUNNING_WAIT_MS = 50L
    }
}
