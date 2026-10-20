package homer.app.services

import homer.app.infra.GraphSubprocessOutcome
import homer.app.infra.MAX_GRAPH_STDERR_BYTES
import homer.app.infra.MAX_GRAPH_STDOUT_BYTES
import homer.app.infra.ProcessGroupSupport
import homer.app.infra.runBoundedGraphSubprocess
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProcessGraphInspectRunnerTest {
    @TempDir
    lateinit var tempDir: Path

    private val usage =
        "usage: homer_langgraph [-h] {run,inspect} ...\n" +
            "homer_langgraph: error: unrecognized arguments: --json"

    @Test
    fun `argparse usage exit with the echoed --json flag is an unsupported flag`() {
        assertTrue(isUnsupportedJsonFlag(2, usage))
    }

    @Test
    fun `a non-usage exit mentioning --json is not an unsupported flag`() {
        assertFalse(isUnsupportedJsonFlag(1, "Traceback: bad value for --json"))
    }

    @Test
    fun `another usage error is not an unsupported flag`() {
        assertFalse(isUnsupportedJsonFlag(2, "homer_langgraph: error: the following arguments are required: --module"))
    }

    @Test
    fun `an old runtime gets the upgrade hint without the interpreter path`() =
        runBlocking {
            val root = Files.createTempDirectory("lg-inspect-runner")
            try {
                val python = root.resolve("opt/secret-venv/bin/python")
                python.parent.createDirectories()
                python.writeText("#!/bin/sh\nprintf '%s\\n' '${usage.replace("\n", "' '")}' >&2\nexit 2\n")
                Files.setPosixFilePermissions(python, PosixFilePermissions.fromString("rwx------"))

                val result = ProcessGraphInspectRunner().run(python.toString(), "pkg.mod", emptyMap(), 10.seconds)

                assertEquals(GraphInspectResult.Err(OLD_RUNTIME_MESSAGE), result)
                assertFalse(python.toString() in (result as GraphInspectResult.Err).message)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `an unlaunchable interpreter is reported as an error`() =
        runBlocking {
            val result =
                ProcessGraphInspectRunner()
                    .run("/nonexistent/homer-python", "pkg.mod", emptyMap(), 10.seconds)

            assertTrue(result is GraphInspectResult.Err, "an unlaunchable interpreter must not crash the call; got $result")
            assertTrue(result.message.isNotBlank(), "the launch failure must carry a message")
        }

    @Test
    fun `a normal topology JSON is returned trimmed`() =
        runBlocking {
            val python = executable("""printf '%s\n' '{"topology":{"nodes":[],"edges":[]}}'""")

            val result = ProcessGraphInspectRunner().run(python.toString(), "pkg.mod", emptyMap(), 10.seconds)

            assertEquals(GraphInspectResult.Ok("""{"topology":{"nodes":[],"edges":[]}}"""), result)
        }

    @Test
    fun `a silent module that outlives the timeout is killed and reported`() =
        runBlocking {
            val python = executable("sleep 60")

            val result = ProcessGraphInspectRunner().run(python.toString(), "pkg.mod", emptyMap(), 1.seconds)

            assertEquals(GraphInspectResult.Err("inspect timed out after 1s"), result)
        }

    @Test
    fun `stdout flood kills the subprocess and returns the output-exceeds error`() =
        runBlocking {
            val before = inspectTempFiles()
            // `yes` floods stdout far past the 2 MiB cap and never exits on its own.
            val python = executable("exec yes xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")

            val started = System.currentTimeMillis()
            val result = ProcessGraphInspectRunner().run(python.toString(), "pkg.mod", emptyMap(), 15.seconds)
            val elapsed = System.currentTimeMillis() - started

            assertEquals(GraphInspectResult.Err("inspect output exceeds $MAX_GRAPH_STDOUT_BYTES bytes"), result)
            assertTrue(elapsed < 10_000, "the overflow must kill the subprocess well before the timeout; took ${elapsed}ms")
            assertEquals(before, inspectTempFiles(), "no homer-lg-inspect* temp file may remain")
        }

    @Test
    fun `stderr flood stays bounded and the call returns`() =
        runBlocking {
            val before = inspectTempFiles()
            // Far past the 16 KiB stderr cap, then a non-zero exit.
            val python =
                executable(
                    "i=0\n" +
                        "while [ \$i -lt 500 ]; do\n" +
                        "  printf '%s\\n' 'error line 0123456789012345678901234567890123456789012345678901234567890123' >&2\n" +
                        "  i=\$((i+1))\n" +
                        "done\n" +
                        "exit 3",
                )

            val result = ProcessGraphInspectRunner().run(python.toString(), "pkg.mod", emptyMap(), 15.seconds)

            val message = (result as GraphInspectResult.Err).message
            assertTrue(message.endsWith("(truncated)"), "the surfaced stderr must be the truncation-marked head; got: $message")
            assertTrue(
                message.length <= MAX_GRAPH_STDERR_BYTES + 20,
                "the surfaced stderr must stay within the cap; was ${message.length} chars",
            )
            assertEquals(before, inspectTempFiles(), "no homer-lg-inspect* temp file may remain")
        }

    @Test
    fun `a detached grandchild that keeps writing is reaped with the process group`() =
        runBlocking {
            Assumptions.assumeTrue(
                ProcessGroupSupport.setsidPath != null,
                "process-group reaping requires setsid (absent on this host)",
            )
            val before = inspectTempFiles()
            val pidFile = tempDir.resolve("grandchild.pid")
            // The middle shell backgrounds a grandchild that starts flooding after a
            // short delay, then exits so the grandchild reparents away from the direct
            // process (escaping descendants()); the outer script stays alive so only the
            // group kill can reach the reparented writer.
            val python =
                executable(
                    "sh -c 'sh -c \"sleep 1; yes grandchild-flood\" & echo \$! > \"$pidFile\"' ; sleep 60",
                )

            val result = ProcessGraphInspectRunner().run(python.toString(), "pkg.mod", emptyMap(), 15.seconds)

            assertTrue(result is GraphInspectResult.Err, "the flood should fail the call, got $result")
            val pid = pidFile.readText().trim().toLong()
            assertTrue(
                waitForExit(pid),
                "detached grandchild (pid $pid) should have been reaped with the process group",
            )
            assertEquals(before, inspectTempFiles(), "no homer-lg-inspect* temp file may remain")
        }

    @Test
    fun `a grandchild that outlives the leader is reaped with the process group`() =
        runBlocking {
            Assumptions.assumeTrue(
                ProcessGroupSupport.setsidPath != null,
                "process-group reaping requires setsid (absent on this host)",
            )
            val pidFile = tempDir.resolve("late-grandchild.pid")
            // The leader backgrounds a grandchild that ignores SIGPIPE and floods after a
            // delay, then exits 0 itself (a real `homer_langgraph` leader lives at least
            // its interpreter startup, so the pgid is readable while it is alive). Once
            // the leader is gone its /proc entry — the only source of the pgid — is gone
            // too; only a pgid captured while it was alive can reap the reparented writer,
            // which would otherwise spin on the closed pipe forever.
            val python =
                executable(
                    "sh -c 'trap \"\" PIPE; sleep 2; while :; do echo late-grandchild-flood; done' & " +
                        "echo \$! > \"$pidFile\"\nsleep 1\nexit 0",
                )

            ProcessGraphInspectRunner().run(python.toString(), "pkg.mod", emptyMap(), 15.seconds)

            val pid = pidFile.readText().trim().toLong()
            assertTrue(
                waitForExit(pid),
                "the reparented grandchild (pid $pid) should have been reaped with the process group",
            )
        }

    @Test
    fun `CWE-772 a caller cancelled during the pgid capture still gets its subprocess reaped`() =
        runBlocking {
            Assumptions.assumeTrue(
                ProcessGroupSupport.setsidPath != null,
                "process-group reaping requires setsid (absent on this host)",
            )
            mockkObject(ProcessGroupSupport)
            try {
                // Keep the pgid capture in its retry loop (every read comes back null
                // until this stub gives up) so the cancellation below lands mid-capture
                // deterministically; later reads call the real thing, so the reap can
                // still group-kill.
                var reads = 0
                every { ProcessGroupSupport.ownProcessGroupId(any()) } answers {
                    // Far more nulls than the capture ever attempts (20 x 10 ms).
                    if (++reads <= 100) null else callOriginal()
                }
                val pidFile = tempDir.resolve("cancelled-child.pid")
                val python = executable("echo \$\$ > \"$pidFile\"\nsleep 60")

                val job =
                    launch(Dispatchers.IO) {
                        ProcessGraphInspectRunner().run(python.toString(), "pkg.mod", emptyMap(), 15.seconds)
                    }
                // Well inside the ~200 ms capture the stub above keeps open.
                delay(50)
                job.cancel()
                job.join()

                val pid = pidFile.readText().trim().toLong()
                assertTrue(
                    waitForExit(pid),
                    "the subprocess (pid $pid) should have been reaped when the caller was cancelled mid-capture",
                )
            } finally {
                unmockkObject(ProcessGroupSupport)
            }
        }

    @Test
    fun `CWE-772 a normal exit leaves all subprocess streams closed`() =
        runBlocking {
            val python = executable("""printf '%s\n' '{"topology":{"nodes":[],"edges":[]}}'""")
            var started: CloseTrackingProcess? = null

            val outcome =
                runBoundedGraphSubprocess(
                    python = python.toString(),
                    args = listOf("inspect", "--module", "pkg.mod", "--json"),
                    env = emptyMap(),
                    timeout = 10.seconds,
                    startProcess = { CloseTrackingProcess(it.start()).also { p -> started = p } },
                )

            val exited = outcome as GraphSubprocessOutcome.Exited
            assertEquals(0, exited.exitCode)
            assertEquals("""{"topology":{"nodes":[],"edges":[]}}""", exited.stdout.trim())
            val process = requireNotNull(started)
            assertStreamClosed(process.inputStream)
            assertStreamClosed(process.errorStream)
            assertStdinClosed(process)
        }

    @Test
    fun `CWE-772 a timeout leaves all subprocess streams closed`() =
        runBlocking {
            val python = executable("sleep 60")
            var started: CloseTrackingProcess? = null

            val outcome =
                runBoundedGraphSubprocess(
                    python = python.toString(),
                    args = listOf("state", "--module", "pkg.mod", "--thread", "thread-1"),
                    env = emptyMap(),
                    timeout = 1.seconds,
                    startProcess = { CloseTrackingProcess(it.start()).also { p -> started = p } },
                )

            assertEquals(GraphSubprocessOutcome.TimedOut, outcome)
            val process = requireNotNull(started)
            assertStreamClosed(process.inputStream)
            assertStreamClosed(process.errorStream)
            assertStdinClosed(process)
        }

    @Test
    fun `CWE-772 an output-overflow kill leaves all subprocess streams closed`() =
        runBlocking {
            // `yes` floods stdout far past the 2 MiB cap and never exits on its own.
            val python = executable("exec yes xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
            var started: CloseTrackingProcess? = null

            val outcome =
                runBoundedGraphSubprocess(
                    python = python.toString(),
                    args = listOf("inspect", "--module", "pkg.mod", "--json"),
                    env = emptyMap(),
                    timeout = 15.seconds,
                    startProcess = { CloseTrackingProcess(it.start()).also { p -> started = p } },
                )

            assertEquals(GraphSubprocessOutcome.StdoutExceeds, outcome)
            val process = requireNotNull(started)
            assertStreamClosed(process.inputStream)
            assertStreamClosed(process.errorStream)
            assertStdinClosed(process)
        }

    @Test
    fun `CWE-772 a cancelled caller leaves all subprocess streams closed`() =
        runBlocking {
            mockkObject(ProcessGroupSupport)
            try {
                // Hold the pgid capture in its retry loop so the cancellation lands
                // mid-capture deterministically; later reads call the real thing.
                var reads = 0
                every { ProcessGroupSupport.ownProcessGroupId(any()) } answers {
                    if (++reads <= 100) null else callOriginal()
                }
                val python = executable("sleep 60")
                val started = CompletableDeferred<CloseTrackingProcess>()

                val job =
                    launch(Dispatchers.IO) {
                        runBoundedGraphSubprocess(
                            python = python.toString(),
                            args = listOf("state", "--module", "pkg.mod", "--thread", "thread-1"),
                            env = emptyMap(),
                            timeout = 5.seconds,
                            startProcess = { CloseTrackingProcess(it.start()).also { p -> started.complete(p) } },
                        )
                    }
                // Bounded so a failed launch surfaces as a timeout instead of hanging the
                // suite; the deferred gives the IO-thread write a happens-before edge.
                val process = withTimeout(10.seconds) { started.await() }
                // Well inside the ~200 ms capture the stub above keeps open.
                delay(50)
                job.cancel()
                job.join()

                assertStreamClosed(process.inputStream)
                assertStreamClosed(process.errorStream)
                assertStdinClosed(process)
            } finally {
                unmockkObject(ProcessGroupSupport)
            }
        }

    /**
     * Asserts [stream] was closed by the time the outcome is decided: reading it
     * throws `IOException("Stream closed")` — a merely drained pipe would return
     * -1 (EOF) instead, and an open one would block. On OpenJDK the process
     * reaper may have closed the stream first, so this proves the caller-closes
     * contract is honoured, not which close won. CWE-772.
     */
    private fun assertStreamClosed(stream: InputStream) {
        val thrown = assertFailsWith<IOException> { stream.read() }
        assertEquals("Stream closed", thrown.message)
    }

    /**
     * Asserts [process]'s stdin write end was explicitly closed by the runner.
     * Tracked on the wrapper the `startProcess` seam handed back, so this fails
     * when the close in `runBoundedGraphSubprocess` is reverted; a post-exit
     * write to the pipe cannot discriminate — the dead child's read end is gone
     * and the JDK's reaper closes the write end, so the write throws either way.
     * The write below only confirms the close reached the real pipe.
     * CWE-772.
     */
    private fun assertStdinClosed(process: CloseTrackingProcess) {
        assertTrue(process.stdinClosed, "the runner must explicitly close the child's stdin write end")
        assertFailsWith<IOException> { process.outputStream.write(ByteArray(8192)) }
    }

    /** Writes an executable `python` stand-in into the per-test temp dir. */
    private fun executable(body: String): Path {
        val script = tempDir.resolve("python")
        script.writeText("#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return script
    }

    /** Names of the legacy temp files the runner used to leave in `java.io.tmpdir`. */
    private fun inspectTempFiles(): Set<String> =
        Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { stream ->
            stream
                .map { it.fileName.toString() }
                .filter { it.startsWith("homer-lg-inspect") }
                .toList()
                .toSet()
        }

    /** Polls (up to ~3s) for [pid] to no longer be a live process. */
    private fun waitForExit(pid: Long): Boolean {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            // A group-killed orphan dies as a zombie (state Z) until its reparent
            // target wait()s on it, and ProcessHandle counts an unreaped zombie as
            // alive; Z means the kill worked. Only called under the setsid assumption,
            // so /proc exists. (This test runs on Linux only.)
            val stat = runCatching { Path.of("/proc/$pid/stat").readText() }.getOrNull() ?: return true
            val state = stat.substringAfterLast(") ").trim().split(" ")[0]
            if (state == "Z" || state == "X") return true
            Thread.sleep(50)
        }
        return false
    }
}

/**
 * A `java.lang.Process` that reports whether the child's stdin write end was
 * closed **on the wrapper** — i.e. by `runBoundedGraphSubprocess` itself, which
 * owns (and closes) the streams of whatever the `startProcess` seam hands back.
 * The JDK's process-completion handler closes the *real* process's streams, so
 * it can never trip [stdinClosed]: this is what makes the stdin half of the
 * stream-closing (CWE-772) tests discriminating. Everything else delegates.
 */
private class CloseTrackingProcess(
    real: java.lang.Process,
) : java.lang.Process() {
    private val delegate: java.lang.Process = real

    @Volatile
    var stdinClosed: Boolean = false
        private set

    private val stdin =
        object : OutputStream() {
            override fun write(b: Int) = delegate.outputStream.write(b)

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = delegate.outputStream.write(b, off, len)

            override fun close() {
                stdinClosed = true
                delegate.outputStream.close()
            }
        }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getErrorStream(): InputStream = delegate.errorStream

    override fun waitFor(): Int = delegate.waitFor()

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = delegate.waitFor(timeout, unit)

    override fun exitValue(): Int = delegate.exitValue()

    override fun pid(): Long = delegate.pid()

    override fun isAlive(): Boolean = delegate.isAlive

    override fun toHandle(): ProcessHandle = delegate.toHandle()

    override fun destroy() = delegate.destroy()

    override fun destroyForcibly(): java.lang.Process = delegate.destroyForcibly()

    override fun supportsNormalTermination(): Boolean = delegate.supportsNormalTermination()
}
