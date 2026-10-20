package homer.runner

import kotlinx.coroutines.Dispatchers
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerTest {
    @TempDir
    lateinit var tmp: Path

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

    /** The effective uid of this process, from `/proc/self/status`. */
    private fun effectiveUid(): Int {
        val stat = File("/proc/self/status").readText()
        return Regex("^Uid:\\s+(\\d+)", RegexOption.MULTILINE).find(stat)!!.groupValues[1].toInt()
    }
}
