package homer.runner

import homer.app.runners.external.RunnerProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerTest {
    @TempDir
    lateinit var tmp: Path

    private val env = mapOf("PATH" to "/usr/bin:/bin")

    @Test
    fun `cancelling await from outside kills the process instead of orphaning it`() =
        runBlocking(Dispatchers.Default) {
            withTimeout(20.seconds) {
                val handle = ProcessRunner.start(listOf("sh", "-c", "sleep 60"), tmp, mapOf("PATH" to "/usr/bin:/bin"), null)
                // The drain's hard stop: the caller's job is cancelled while the command still runs.
                val awaiting = launch { handle.await(10.minutes) { _, _, _ -> } }
                delay(300)
                awaiting.cancelAndJoin()
                assertFalse(handle.isAlive(), "a cancelled await must not leave its process running")
            }
        }

    @Test
    fun `a spawned command runs normally with the composed env while the JVM is non-dumpable`() =
        runBlocking(Dispatchers.Default) {
            withTimeout(20.seconds) {
                // Hardening is process-wide, so this must not change what a run can do:
                // execve resets the child's dumpable flag, and its env is exactly what
                // ProcessRunner composed (clear + putAll).
                Dumpability.harden()
                val handle =
                    ProcessRunner.start(
                        listOf("sh", "-c", "echo ok; echo \"A=\$A HOME=\${HOME:-unset}\""),
                        tmp,
                        mapOf("PATH" to "/usr/bin:/bin", "A" to "1"),
                        null,
                    )
                val out = StringBuilder()
                val exit = handle.await(30.seconds) { _, _, bytes -> out.append(bytes.decodeToString()) }
                assertEquals(0, exit.code)
                assertEquals("ok\nA=1 HOME=unset\n", out.toString())
            }
        }

    @Test
    fun `a run cannot read the non-dumpable JVM's environment (ADR-0047)`() =
        runBlocking(Dispatchers.Default) {
            withTimeout(20.seconds) {
                assumeTrue(System.getProperty("os.name").startsWith("Linux"))
                // A privileged reader (uid 0) bypasses the dumpable restriction —
                // ptrace_may_access succeeds for root regardless — so the denial this
                // test asserts cannot be observed there (e.g. CI's docker executor).
                assumeTrue(effectiveUid() != 0, "the dumpable denial is unobservable for a privileged reader")
                Dumpability.harden()
                val jvmPid = ProcessHandle.current().pid()
                val handle =
                    ProcessRunner.start(
                        listOf("sh", "-c", "cat /proc/$jvmPid/environ >/dev/null 2>&1; echo rc=\$?"),
                        tmp,
                        mapOf("PATH" to "/usr/bin:/bin"),
                        null,
                    )
                val out = StringBuilder()
                handle.await(30.seconds) { _, _, bytes -> out.append(bytes.decodeToString()) }
                assertTrue("rc=0" !in out.toString(), "a run must not read the runner's environ; got: $out")
            }
        }

    @Test
    fun `a spawned child is itself dumpable, so runs behave as before (exec resets the flag)`() =
        runBlocking(Dispatchers.Default) {
            withTimeout(20.seconds) {
                assumeTrue(System.getProperty("os.name").startsWith("Linux"))
                Dumpability.harden()
                // setsid sits between the JVM and the shell, so inspect a child of the
                // shell via /proc instead of guessing at $PPID.
                val handle =
                    ProcessRunner.start(
                        listOf("sh", "-c", "sleep 30 & p=\$!; cat /proc/\$p/status > $tmp/child-status; kill \$p"),
                        tmp,
                        mapOf("PATH" to "/usr/bin:/bin"),
                        null,
                    )
                val exit = handle.await(10.seconds) { _, _, _ -> }
                assertEquals(0, exit.code)
                val status = tmp.resolve("child-status").toFile().readText()
                assertTrue("State:" in status, "expected a live child; got: $status")
                // The kernel omits the "Dumpable:" field before Linux 6.2 and some
                // containers mask it; there the assertion below cannot run, so report
                // the test as skipped rather than pass without verifying anything.
                assumeTrue("Dumpable:" in status, "the kernel does not expose the Dumpable field here")
                assertTrue("Dumpable:\t1" in status, "a spawned child must be dumpable; got: $status")
            }
        }

    @Test
    fun `a sink that outlasts the join still posts the tail inside the op budget`() =
        runBlocking(Dispatchers.Default) {
            withTimeout(30.seconds) {
                val handle = ProcessRunner.start(listOf("sh", "-c", "echo MARKER"), tmp, env, null)
                val out = StringBuilder()
                // The post alone outlasts the fixed 5 s join, so the drain has to spend the op's
                // own budget instead: with the join as the bound the chunk is cut and the step's
                // tail is lost, which is the reported bug.
                val exit =
                    handle.await(15.seconds) { _, _, bytes ->
                        delay(5_200)
                        out.append(bytes.decodeToString())
                    }

                assertEquals(0, exit.code)
                assertTrue("MARKER" in out.toString(), "the last chunk must be posted before the exit is reported; got: $out")
                assertEquals(emptySet(), exit.cutStreams)
                assertEquals(1L, exit.stdoutChunks, "the stdout chunks the sink was handed are reported")
                assertEquals(0L, exit.stderrChunks)
            }
        }

    @Test
    fun `a grandchild holding the pipe open does not stretch await past the op deadline`() =
        runBlocking(Dispatchers.Default) {
            // The shell exits at once, while the sleep it backgrounded still holds the stream's
            // write end; the op's budget is minutes, so a drain that waited on the grandchild —
            // there is no EOF to wait for until it dies — would outlive this test's own bound.
            withTimeout(20.seconds) {
                val handle =
                    ProcessRunner.start(listOf("sh", "-c", "echo MARKER; sleep 30 & echo pid=$!"), tmp, env, null)
                val out = StringBuilder()
                val exit = handle.await(10.minutes) { _, _, bytes -> out.append(bytes.decodeToString()) }
                val grandchild = grandchildOf(out.toString())
                // The premise, checked rather than assumed: the write end is still held by a live
                // grandchild, so the reads the drain shed are parked on it, not finished.
                assertTrue(grandchild.isAlive, "the grandchild must outlive the shell that spawned it")
                grandchild.destroyForcibly()

                assertEquals(0, exit.code)
                assertFalse(exit.timedOut, "the shell exited on its own; a grandchild must not turn that into a timeout")
                assertTrue("MARKER" in out.toString(), "the tail is posted before the exit is reported; got: $out")
                assertEquals(emptySet(), exit.cutStreams, "a parked read has nothing unsent, so no stream is cut")
            }
        }

    @Test
    fun `a stalled sink is shed at the drain bound and both streams are reported cut`() =
        runBlocking(Dispatchers.Default) {
            withTimeout(30.seconds) {
                val handle = ProcessRunner.start(listOf("sh", "-c", "echo out; echo err >&2"), tmp, env, null)
                val started = System.nanoTime()
                val exit = handle.await(2.seconds) { _, _, _ -> awaitCancellation() }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                assertEquals(0, exit.code)
                // A post that never returns is shed at the drain bound — here the 5 s floor, the
                // op's own budget being smaller — so a stalled sink cannot hang the op, and every
                // stream still unsent is named rather than passed off as complete.
                assertTrue(elapsedMs < 8_000, "a stalled post is shed at the drain bound; took ${elapsedMs}ms")
                assertEquals(setOf(RunnerProtocol.STREAM_STDOUT, RunnerProtocol.STREAM_STDERR), exit.cutStreams)
                assertEquals(0L, exit.stdoutChunks, "a chunk the sink never took back is not counted as handed over")
                assertEquals(0L, exit.stderrChunks)
            }
        }

    @Test
    fun `a killed op keeps the bounded join floor and reports its timeout`() =
        runBlocking(Dispatchers.Default) {
            withTimeout(30.seconds) {
                val handle = ProcessRunner.start(listOf("sh", "-c", "sleep 30"), tmp, env, null)
                val started = System.nanoTime()
                val exit = handle.await(500.milliseconds) { _, _, _ -> }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                assertTrue(exit.timedOut)
                assertFalse(handle.isAlive())
                // Kill grace plus the PUMP_JOIN floor: an expired op has no budget left, so the
                // drain falls back to the floor rather than running to the timeout it never got.
                assertTrue(elapsedMs < 11_000, "the kill path stays bounded; took ${elapsedMs}ms")
            }
        }

    /** The shell's backgrounded child, from the pid the shell printed for it. */
    private fun grandchildOf(output: String): ProcessHandle {
        val pid = Regex("pid=(\\d+)").find(output)!!.groupValues[1].toLong()
        return ProcessHandle.of(pid).orElseThrow { AssertionError("the shell reported no grandchild pid: $output") }
    }

    /** The effective uid of this process, from `/proc/self/status`. */
    private fun effectiveUid(): Int {
        val stat = File("/proc/self/status").readText()
        return Regex("^Uid:\\s+(\\d+)", RegexOption.MULTILINE).find(stat)!!.groupValues[1].toInt()
    }
}
