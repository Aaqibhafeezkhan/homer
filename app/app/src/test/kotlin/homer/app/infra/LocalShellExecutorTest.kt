package homer.app.infra

import homer.app.exceptions.OutputCapExceededException
import homer.app.models.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for LocalShellExecutor
 * Tests command execution, timeout handling, and environment merging
 */
class LocalShellExecutorTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var workDir: Path
    private lateinit var stdOutFile: Path
    private lateinit var stdErrFile: Path
    private lateinit var shellExecutor: LocalShellExecutor

    @BeforeEach
    fun setup() {
        workDir = tempDir.resolve("workdir")
        Files.createDirectories(workDir)
        stdOutFile = tempDir.resolve("stdout.txt")
        stdErrFile = tempDir.resolve("stderr.txt")
        shellExecutor = LocalShellExecutor(workDir)
    }

    @AfterEach
    fun teardown() {
        // Cleanup handled by @TempDir
    }

    // ========== Basic Command Execution Tests ==========

    @Test
    fun `cmd should execute simple echo command`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("echo", "hello"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            assertEquals(stdOutFile, result.stdOut)
            assertEquals(stdErrFile, result.stdErr)
            assertTrue(Files.readString(stdOutFile).contains("hello"))
        }

    @Test
    fun `cmd should return non-zero exit code for failing command`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("sh", "-c", "exit 42"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(42, result.exitCode)
            assertEquals(stdOutFile, result.stdOut)
            assertEquals(stdErrFile, result.stdErr)
        }

    @Test
    fun `cmd should capture stdout to file`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("echo", "captured output"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val output = Files.readString(stdOutFile)
            assertTrue(output.contains("captured output"), "stdout should contain the echo message")
        }

    @Test
    fun `cmd should capture stderr to file`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("sh", "-c", "echo error message >&2"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val stderr = Files.readString(stdErrFile)
            assertTrue(stderr.contains("error message"), "stderr should contain the error message")
        }

    // ========== Environment Variable Tests ==========

    @Test
    fun `cmd should pass environment variables to command`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("sh", "-c", "echo \$MY_VAR"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = mapOf("MY_VAR" to "test_value"),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val output = Files.readString(stdOutFile)
            assertTrue(
                output.contains("test_value"),
                "Output should contain environment variable value",
            )
        }

    // ========== Stdin Tests ==========

    @Test
    fun `cmd should pass stdin to command`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("cat"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = "stdin content here",
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val output = Files.readString(stdOutFile)
            assertTrue(output.contains("stdin content here"), "Output should contain stdin content")
        }

    @Test
    fun `cmd should handle multiline stdin`() =
        runTest {
            val multilineInput =
                """
                line 1
                line 2
                line 3
                """.trimIndent()

            val result =
                shellExecutor.cmd(
                    command = listOf("cat"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = multilineInput,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val output = Files.readString(stdOutFile)
            assertTrue(output.contains("line 1"), "Output should contain first line")
            assertTrue(output.contains("line 2"), "Output should contain second line")
            assertTrue(output.contains("line 3"), "Output should contain third line")
        }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    fun `large stdin to a non-reading process times out instead of blocking forever`() =
        runBlocking {
            // The child never reads stdin, so a stdin larger than the OS pipe buffer
            // (~64 KiB) would block a synchronous write forever and the timeout monitor
            // would never start. With the write on its own job the TTL kill still fires,
            // breaks the pipe, and cmd() throws instead of hanging. @Timeout fails the
            // test (rather than stalling CI) if the concurrent-write fix regresses.
            val big = "x".repeat(1_000_000)
            var thrown: Throwable? = null
            try {
                shellExecutor.cmd(
                    command = listOf("sleep", "30"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = big,
                    env = emptyMap(),
                    timeout = 1.seconds,
                )
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue(
                thrown is CancellationException,
                "cmd must time out, not block, on a full stdin pipe (got: $thrown)",
            )
        }

    // ========== Working Directory Tests ==========

    @Test
    fun `cmd should execute in working directory`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("pwd"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val output = Files.readString(stdOutFile).trim()
            assertTrue(output.endsWith("workdir"), "Should execute in work directory, but was: $output")
        }

    // ========== Timeout Tests ==========

    @Test
    fun `cmd should complete within timeout`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("echo", "fast"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 5.seconds,
                )

            assertEquals(0, result.exitCode)
        }

    @Test
    fun `cmd should handle timeout for long-running command`() =
        runTest {
            // This test verifies that the executor handles long-running commands
            // The LocalShellExecutor throws CancellationException on timeout but may also
            // return a result if the process was killed successfully
            var exceptionThrown = false
            var result: CommandResult? = null

            try {
                result =
                    shellExecutor.cmd(
                        command = listOf("sleep", "30"),
                        stdOut = stdOutFile,
                        stdErr = stdErrFile,
                        stdIn = null,
                        env = emptyMap(),
                        timeout = 1.seconds,
                    )
            } catch (e: Exception) {
                exceptionThrown = true
                // Timeout or cancellation is expected
            }

            // Either an exception was thrown OR the command was killed (non-zero exit)
            assertTrue(
                exceptionThrown || (result != null && result.exitCode != 0),
                "Should either throw exception or return non-zero exit code",
            )
        }

    @Test
    fun `cmd preserves exit code and output when launched under a process group`() =
        runTest {
            // Backward-compat: whether or not setsid wraps the launch, exec-replacement
            // keeps the command's own exit code and stdout intact.
            val result =
                shellExecutor.cmd(
                    command = listOf("sh", "-c", "printf hi; exit 7"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(7, result.exitCode)
            assertEquals("hi", Files.readString(stdOutFile))
        }

    @Test
    fun `timeout reaps a reparented grandchild via the process group`() =
        runTest {
            Assumptions.assumeTrue(
                ProcessGroupSupport.setsidPath != null,
                "process-group reaping requires setsid (absent on this host)",
            )
            val pidFile = tempDir.resolve("grandchild.pid")
            // The inner shell backgrounds a grandchild then exits, so the grandchild
            // reparents away from our direct process (escaping descendants()) but stays
            // in the same process group. The outer shell stays alive past the timeout so
            // the kill path runs; only a group kill can reap the reparented grandchild.
            val script = "sh -c 'sleep 30 & echo \$! > \"$pidFile\"' ; sleep 30"
            var threw = false
            try {
                shellExecutor.cmd(
                    command = listOf("sh", "-c", script),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 1.seconds,
                )
            } catch (_: Exception) {
                threw = true
            }
            assertTrue(threw, "the timed-out command should have thrown")

            val pid = pidFile.readText().trim().toLong()
            assertTrue(
                waitForExit(pid),
                "backgrounded grandchild (pid $pid) should have been reaped with the process group",
            )
        }

    @Test
    fun `cmd should append TTL message to stderr when killed by timeout`() =
        runTest {
            try {
                shellExecutor.cmd(
                    command = listOf("sleep", "30"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 1.seconds,
                )
            } catch (_: Exception) {
            }

            val stderr = if (Files.exists(stdErrFile)) Files.readString(stdErrFile) else ""
            assertTrue(stderr.contains("[HOMER]"), "stderr should contain HOMER TTL marker")
            assertTrue(stderr.contains("1s"), "stderr should include the configured timeout duration")
        }

    @Test
    fun `cmd should not append TTL message to stderr when killed by coroutine cancellation`() =
        runTest {
            val job =
                launch {
                    try {
                        shellExecutor.cmd(
                            command = listOf("sleep", "30"),
                            stdOut = stdOutFile,
                            stdErr = stdErrFile,
                            stdIn = null,
                            env = emptyMap(),
                            timeout = 60.seconds,
                        )
                    } catch (_: Exception) {
                    }
                }
            delay(200)
            job.cancelAndJoin()

            val stderr = if (Files.exists(stdErrFile)) Files.readString(stdErrFile) else ""
            assertFalse(stderr.contains("[HOMER]"), "stderr should NOT contain HOMER TTL marker for user kill")
        }

    @Test
    fun `cmd should throw CancellationException on timeout instead of returning a result (audit #8)`() =
        runBlocking {
            // Without an external cancel, LocalShellExecutor used to swallow the timeout
            // CancellationException and return a CommandResult, so the agent's
            // CmdExecutor would happily proceed to the next command after a TTL kill.
            // The audit requires cancellation to propagate so the caller can fail fast.
            var thrown: Throwable? = null
            var result: CommandResult? = null
            try {
                result =
                    shellExecutor.cmd(
                        command = listOf("sleep", "30"),
                        stdOut = stdOutFile,
                        stdErr = stdErrFile,
                        stdIn = null,
                        env = emptyMap(),
                        timeout = 1.seconds,
                    )
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue(
                thrown is CancellationException,
                "cmd must throw CancellationException on timeout (got result=$result, thrown=$thrown)",
            )
        }

    @Test
    fun `cmd should propagate CancellationException and destroy the spawned process within 5s (audit #8)`() =
        runBlocking {
            val pidFile = tempDir.resolve("pid.txt")
            var thrown: Throwable? = null
            val job =
                launch {
                    try {
                        shellExecutor.cmd(
                            command =
                                listOf(
                                    "sh",
                                    "-c",
                                    "echo \$\$ > '$pidFile' ; sleep 30",
                                ),
                            stdOut = stdOutFile,
                            stdErr = stdErrFile,
                            stdIn = null,
                            env = emptyMap(),
                            timeout = 60.seconds,
                        )
                    } catch (e: Throwable) {
                        thrown = e
                    }
                }

            // Wait for the child to write its PID.
            val pidDeadline = System.currentTimeMillis() + 5_000
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0L) &&
                System.currentTimeMillis() < pidDeadline
            ) {
                delay(50)
            }
            assertTrue(Files.exists(pidFile), "child shell must write its PID before cancel")
            val pid = Files.readString(pidFile).trim().toLong()
            val handle =
                ProcessHandle.of(pid).orElseThrow {
                    AssertionError("ProcessHandle for $pid not found")
                }
            assertTrue(handle.isAlive, "process $pid should be alive before cancel")

            job.cancelAndJoin()

            // Audit #8: process.exitValue() must become available within 5s of cancel.
            val deadline = System.currentTimeMillis() + 5_000
            while (handle.isAlive && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            assertFalse(handle.isAlive, "process $pid should be destroyed within 5s of cancellation")
            assertTrue(
                thrown is CancellationException,
                "cmd() must propagate CancellationException to its caller (got: $thrown)",
            )
        }

    // ========== Command with Arguments Tests ==========

    @Test
    fun `cmd should handle command with multiple arguments`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("echo", "arg1", "arg2", "arg3"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val output = Files.readString(stdOutFile)
            assertTrue(output.contains("arg1 arg2 arg3"), "Output should contain all arguments")
        }

    @Test
    fun `cmd should handle complex shell script`() =
        runTest {
            val script = "for i in 1 2 3; do echo number \$i; done"

            val result =
                shellExecutor.cmd(
                    command = listOf("sh", "-c", script),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val output = Files.readString(stdOutFile)
            assertTrue(output.contains("number 1"), "Output should contain number 1")
            assertTrue(output.contains("number 2"), "Output should contain number 2")
            assertTrue(output.contains("number 3"), "Output should contain number 3")
        }

    // ========== Edge Cases ==========

    @Test
    fun `cmd should handle empty stdout`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("true"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            assertTrue(Files.exists(stdOutFile), "stdout file should exist")
        }

    @Test
    fun `cmd should handle command that writes to both stdout and stderr`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("sh", "-c", "echo stdout && echo stderr >&2"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            assertTrue(Files.readString(stdOutFile).contains("stdout"))
            assertTrue(Files.readString(stdErrFile).contains("stderr"))
        }

    @Test
    fun `cmd should handle special characters in arguments`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("echo", "hello 'world'", "with \"quotes\""),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            val output = Files.readString(stdOutFile)
            assertTrue(output.contains("hello 'world'"), "Output should preserve single quotes")
        }

    @Test
    fun `cmd should return correct stdOut and stdErr paths in result`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("echo", "test"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                )

            assertEquals(stdOutFile, result.stdOut, "Result should contain correct stdOut path")
            assertEquals(stdErrFile, result.stdErr, "Result should contain correct stdErr path")
            assertEquals(0, result.exitCode)
        }

    @Test
    fun `cmd should throw OutputCapExceededException when stdout exceeds maxOutputBytes (audit P1 #17)`(
        @TempDir tmp: Path,
    ) = runTest {
        val workDir = tmp
        val stdOut = tmp.resolve("cmd_0.out")
        val stdErr = tmp.resolve("cmd_0.err")
        val cap = 1024L

        val executor = LocalShellExecutor(workDir, maxOutputBytes = cap)

        // Write 4 KiB of 'A' to stdout then sleep 30 s so the process is still
        // alive when the 1-second poll fires and sees the cap exceeded.
        val script = "head -c ${cap * 4} /dev/zero | tr '\\0' 'A'; sleep 30"

        assertFailsWith<OutputCapExceededException> {
            executor.cmd(
                command = listOf("bash", "-c", script),
                stdOut = stdOut,
                stdErr = stdErr,
                stdIn = null,
                env = emptyMap(),
                timeout = 30.seconds,
            )
        }

        // The kill-cleanup block must have appended the [HOMER] marker to
        // stderr, paralleling the existing TTL kill marker.
        val stderrContent = stdErr.toFile().readText()
        assertTrue(
            stderrContent.contains("[HOMER] Output cap exceeded"),
            "expected stderr to contain '[HOMER] Output cap exceeded' marker; got: $stderrContent",
        )
    }

    @Test
    fun `cmd fails when a fast-exiting command overshoots the output cap between polls`(
        @TempDir tmp: Path,
    ) = runTest {
        val workDir = tmp
        val stdOut = tmp.resolve("cmd_0.out")
        val stdErr = tmp.resolve("cmd_0.err")
        val cap = 1024L

        val executor = LocalShellExecutor(workDir, maxOutputBytes = cap)

        // Write 4 KiB then exit immediately — no sleep. The process is already
        // finished by the first poll, so the mid-run cap check is skipped; only the
        // post-completion check can catch the overshoot. Without it the oversized
        // file would be accepted as a clean exit-0 result.
        val script = "head -c ${cap * 4} /dev/zero | tr '\\0' 'A'"

        assertFailsWith<OutputCapExceededException> {
            executor.cmd(
                command = listOf("bash", "-c", script),
                stdOut = stdOut,
                stdErr = stdErr,
                stdIn = null,
                env = emptyMap(),
                timeout = 30.seconds,
            )
        }

        val stderrContent = stdErr.toFile().readText()
        assertTrue(
            stderrContent.contains("[HOMER] Output cap exceeded"),
            "expected stderr to contain '[HOMER] Output cap exceeded' marker; got: $stderrContent",
        )
    }

    @Test
    fun `cmd should throw OutputCapExceededException when stderr exceeds maxOutputBytes (audit P1 #17)`(
        @TempDir tmp: Path,
    ) = runTest {
        val workDir = tmp
        val stdOut = tmp.resolve("cmd_0.out")
        val stdErr = tmp.resolve("cmd_0.err")
        val cap = 1024L

        val executor = LocalShellExecutor(workDir, maxOutputBytes = cap)

        // Write 4 KiB of 'A' to stderr then sleep 30 s so the process is still
        // alive when the 1-second poll fires and sees the cap exceeded.
        val script = "head -c ${cap * 4} /dev/zero | tr '\\0' 'A' >&2; sleep 30"

        assertFailsWith<OutputCapExceededException> {
            executor.cmd(
                command = listOf("bash", "-c", script),
                stdOut = stdOut,
                stdErr = stdErr,
                stdIn = null,
                env = emptyMap(),
                timeout = 30.seconds,
            )
        }

        val stderrContent = stdErr.toFile().readText()
        assertTrue(
            stderrContent.contains("[HOMER] Output cap exceeded"),
            "expected stderr to contain '[HOMER] Output cap exceeded' marker; got: $stderrContent",
        )
    }

    /** Polls (up to ~3s) for [pid] to no longer be a live process. */
    private fun waitForExit(pid: Long): Boolean {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            // Read the /proc state, not ProcessHandle.isAlive: the reaped orphan
            // dies as a zombie (state Z) until its reparent target (in a CI
            // container: a PID 1 that may never get around to it) wait()s on it,
            // and ProcessHandle counts an unreaped zombie as alive. Z means the
            // group kill worked. Only called under the setsid assumption, so
            // /proc exists. (This test runs on Linux only.)
            val stat = runCatching { Path.of("/proc/$pid/stat").readText() }.getOrNull() ?: return true
            val state =
                stat
                    .substringAfterLast(") ")
                    .trim()
                    .split(" ")[0]
            if (state == "Z" || state == "X") return true
            Thread.sleep(50)
        }
        return ProcessHandle
            .of(pid)
            .map { it.isAlive }
            .orElse(false)
            .not()
    }

    // ========== stdout filter (ADR-0063) ==========

    @Test
    fun `stdOutFilter drops lines before they reach the stdout file`() =
        runTest {
            val result =
                shellExecutor.cmd(
                    command = listOf("printf", "keep 1\\ndrop me\\nkeep 2\\n"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 10.seconds,
                    stdOutFilter = OutputLineFilter { !it.contains("drop") },
                )
            assertEquals(0, result.exitCode)
            assertEquals("keep 1\nkeep 2\n", stdOutFile.readText())
        }

    @Test
    fun `stdOutFilter leaves stderr untouched`() =
        runTest {
            shellExecutor.cmd(
                command = listOf("bash", "-c", "echo 'drop out'; echo 'drop err' >&2"),
                stdOut = stdOutFile,
                stdErr = stdErrFile,
                stdIn = null,
                env = emptyMap(),
                timeout = 10.seconds,
                stdOutFilter = OutputLineFilter { !it.contains("drop") },
            )
            assertEquals("", stdOutFile.readText())
            assertEquals("drop err\n", stdErrFile.readText())
        }

    @Test
    fun `output cap still trips on kept output when a filter is present`(
        @TempDir tmp: Path,
    ) = runTest {
        val cap = 1024L
        val executor = LocalShellExecutor(tmp, maxOutputBytes = cap)
        // 4 KiB of 'A' on one line (kept), then stay alive so the poll sees the file.
        val script = "head -c ${cap * 4} /dev/zero | tr '\\0' 'A'; echo; sleep 30"
        assertFailsWith<OutputCapExceededException> {
            executor.cmd(
                command = listOf("bash", "-c", script),
                stdOut = tmp.resolve("cmd_0.out"),
                stdErr = tmp.resolve("cmd_0.err"),
                stdIn = null,
                env = emptyMap(),
                timeout = 30.seconds,
                stdOutFilter = OutputLineFilter { true },
            )
        }
        assertTrue(tmp.resolve("cmd_0.err").readText().contains("[HOMER] Output cap exceeded"))
    }

    @Test
    fun `output cap does not count dropped lines`(
        @TempDir tmp: Path,
    ) = runTest {
        val cap = 1024L
        val executor = LocalShellExecutor(tmp, maxOutputBytes = cap)
        // 200 lines of ~40 bytes (8 KiB) that are all dropped, plus one kept line.
        val script = "for i in \$(seq 1 200); do echo \"drop line number \$i padding padding\"; done; echo kept"
        val result =
            executor.cmd(
                command = listOf("bash", "-c", script),
                stdOut = tmp.resolve("cmd_0.out"),
                stdErr = tmp.resolve("cmd_0.err"),
                stdIn = null,
                env = emptyMap(),
                timeout = 30.seconds,
                stdOutFilter = OutputLineFilter { !it.startsWith("drop") },
            )
        assertEquals(0, result.exitCode)
        assertEquals("kept\n", tmp.resolve("cmd_0.out").readText())
    }

    @Test
    fun `a background child holding stdout open does not block cmd past the pump join bound`() =
        runTest {
            // bash exits at once; `sleep` inherits the stdout pipe and keeps it open.
            val started = System.currentTimeMillis()
            val result =
                shellExecutor.cmd(
                    command = listOf("bash", "-c", "sleep 20 & echo hi"),
                    stdOut = stdOutFile,
                    stdErr = stdErrFile,
                    stdIn = null,
                    env = emptyMap(),
                    timeout = 30.seconds,
                    stdOutFilter = OutputLineFilter { true },
                )
            val elapsedMs = System.currentTimeMillis() - started
            assertEquals(0, result.exitCode)
            assertEquals("hi\n", stdOutFile.readText())
            assertTrue(elapsedMs < 15_000, "cmd should return after the 5 s pump join bound, took $elapsedMs ms")
        }

    @Test
    fun `a background child writing after the pump is shed cannot grow the stdout file`(
        @TempDir tmp: Path,
    ) = runTest {
        val executor = LocalShellExecutor(tmp, pumpJoinMs = 200)
        val out = tmp.resolve("cmd_0.out")
        // bash exits at once; the subshell inherits the stdout pipe and writes after
        // the pump has been shed. 20 KB in one go: a read that fills the pump's whole
        // 8 KiB buffer is handed over before the closed-pipe check, so closing only
        // the pipe would still let one chunk through.
        val result =
            executor.cmd(
                command = listOf("bash", "-c", "(sleep 1; head -c 20000 /dev/zero | tr '\\0' x; echo) & echo hi"),
                stdOut = out,
                stdErr = tmp.resolve("cmd_0.err"),
                stdIn = null,
                env = emptyMap(),
                timeout = 30.seconds,
                stdOutFilter = OutputLineFilter { true },
            )
        assertEquals(0, result.exitCode)
        assertEquals("hi\n", out.readText())
        // Real time: the child writes ~1 s after cmd returned.
        Thread.sleep(2_000)
        assertEquals("hi\n", out.readText(), "a write after the pump was shed must not reach the artifact")
    }

    @Test
    fun `a pump write failure ends the child instead of blocking it until the timeout`(
        @TempDir tmp: Path,
    ) = runTest {
        // stdout points into a directory that does not exist, so the pump cannot open
        // its target; the child writes far more than a pipe buffer holds.
        val missingDir = tmp.resolve("no-such-dir")
        val started = System.currentTimeMillis()
        val result =
            LocalShellExecutor(tmp).cmd(
                command = listOf("bash", "-c", "head -c 1000000 /dev/zero"),
                stdOut = missingDir.resolve("cmd_0.out"),
                stdErr = tmp.resolve("cmd_0.err"),
                stdIn = null,
                env = emptyMap(),
                timeout = 20.seconds,
                stdOutFilter = OutputLineFilter { true },
            )
        val elapsedMs = System.currentTimeMillis() - started
        assertTrue(elapsedMs < 10_000, "child should die of EPIPE quickly, took $elapsedMs ms")
        assertTrue(result.exitCode != 0, "a child killed by EPIPE must not report success; got ${result.exitCode}")
    }
}
