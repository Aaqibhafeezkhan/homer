package homer.app.runners.kubernetes

import homer.app.exceptions.OutputCapExceededException
import homer.app.exceptions.PodFailedDuringExecutionException
import homer.app.infra.OutputLineFilter
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
                every { waitFor(any<Long>(), any<TimeUnit>()) } returns false
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

    private fun blockingUntilClosed() = BlockingStream()

    @Test
    fun `cmd raises PodFailedDuringExecutionException on the client no-exit-status sentinel`(
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
        // client-java's ExecProcess returns -1975219 from exitValue() when the
        // websocket closed without a status (e.g. the 101 upgrade was refused).
        val proc = fakeProcess(stdout = "", stderr = "", exit = -1975219)
        val client = KubernetesExecClient { _, _, _, _ -> proc }

        val executor = KubernetesShellExecutor(client, api, "homer", "pod-1")

        val thrown =
            assertThrows<PodFailedDuringExecutionException> {
                executor.cmd(
                    command = listOf("true"),
                    stdOut = tmp.resolve("out"),
                    stdErr = tmp.resolve("err"),
                    timeout = 10.seconds,
                )
            }
        assertEquals("ExecChannelFailed", thrown.reason)
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
}
