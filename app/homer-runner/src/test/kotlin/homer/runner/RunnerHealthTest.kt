package homer.runner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class RunnerHealthTest {
    @Test
    fun `a working host passes the check`(
        @TempDir root: Path,
    ) {
        RunnerHealth.check(root)
    }

    @Test
    fun `a probe that exits non-zero still passes the check`(
        @TempDir root: Path,
    ) {
        RunnerHealth.check(root, probeArgv = listOf("/bin/sh", "-c", "exit 3"))
    }

    @Test
    fun `a program that cannot start fails the check`(
        @TempDir root: Path,
    ) {
        val e = assertThrows<RunnerUnhealthyException> { RunnerHealth.check(root, probeArgv = listOf("/nonexistent/homer-probe")) }
        assertTrue(e.message!!.startsWith("cannot start a process: "))
    }

    @Test
    fun `a root that is not a directory fails the check naming it`(
        @TempDir tmp: Path,
    ) {
        val file = Files.createFile(tmp.resolve("not-a-dir"))
        val e = assertThrows<RunnerUnhealthyException> { RunnerHealth.check(file) }
        assertTrue(e.message!!.startsWith("cannot write the root $file"))
    }

    @Test
    fun `the check leaves no file behind in the root`(
        @TempDir root: Path,
    ) {
        RunnerHealth.check(root)
        assertEquals(emptyList(), Files.list(root).use { it.toList() })
    }

    @Test
    fun `the probe gets an empty environment (CWE-522)`(
        @TempDir root: Path,
    ) {
        val dump = root.resolve("env.txt")
        RunnerHealth.check(root, probeArgv = listOf("/bin/sh", "-c", "env > \"$1\"", "sh", dump.toString()))
        val keys = Files.readAllLines(dump).map { it.substringBefore('=') }
        assertFalse("PATH" in keys, "the probe got a PATH")
        val leaked = keys.filter { it in System.getenv().keys - SHELL_SET }
        assertEquals(emptyList(), leaked, "the JVM's env leaked into the probe")
    }

    @Test
    fun `a probe that does not exit is killed and still counts as started`(
        @TempDir root: Path,
    ) {
        val pidFile = root.resolve("probe.pid")
        val probe = listOf("/bin/sh", "-c", "echo $$ > \"$1\"; exec /bin/sleep 30", "sh", pidFile.toString())
        val took = measureTime { RunnerHealth.check(root, probeArgv = probe, probeWait = 500.milliseconds) }
        assertTrue(took < 5.seconds)

        val pid = Files.readString(pidFile).trim().toLong()
        val deadline = System.nanoTime() + 1.seconds.inWholeNanoseconds
        while (isAlive(pid) && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(isAlive(pid), "the probe $pid is still running")
    }

    private fun isAlive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private companion object {
        /** Variables the shell sets for itself even in an empty environment. */
        val SHELL_SET = setOf("PWD", "SHLVL", "_")
    }
}
