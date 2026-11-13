package homer.app.infra

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessGroupSupportTest {
    @Test
    fun `launchArgv reflects setsid availability`() {
        val argv = listOf("echo", "hi")
        val result = ProcessGroupSupport.launchArgv(argv)
        val expected = ProcessGroupSupport.setsidPath?.let { listOf(it) + argv } ?: argv
        assertEquals(expected, result)
    }

    @Test
    fun `ownProcessGroupId returns null for a directly spawned process`() {
        // A process started without setsid shares the JVM's process group and is not
        // its own group leader, so a group kill must never target it. This holds on
        // both hosts with setsid (pgid != pid) and without (setsidPath == null).
        val process = ProcessBuilder("sleep", "5").start()
        try {
            assertNull(ProcessGroupSupport.ownProcessGroupId(process))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `ownProcessGroupId returns null for an exited process`() {
        // Once the child has exited (and the JVM reaped it), its pid must never
        // yield a group id: the /proc entry is gone, or — if the pid were
        // recycled — the ProcessHandle identity check (pid + start time)
        // rejects the stranger. Guards the group kill against PID reuse.
        val process = ProcessBuilder("true").start()
        process.waitFor()
        assertNull(ProcessGroupSupport.ownProcessGroupId(process))
    }

    @Test
    fun `groupLeaderPgid returns the pid of a live process leading its own group`() {
        val process = ProcessBuilder("sleep", "5").start()
        try {
            assertEquals(process.pid(), ProcessGroupSupport.groupLeaderPgid(process) { it })
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `groupLeaderPgid returns null when the process is not its group's leader or the group is unreadable`() {
        val process = ProcessBuilder("sleep", "5").start()
        try {
            assertNull(ProcessGroupSupport.groupLeaderPgid(process) { it + 1 })
            assertNull(ProcessGroupSupport.groupLeaderPgid(process) { null })
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `groupLeaderPgid returns null when the pid now names another process`() {
        // The PID-reuse guard: a pid whose live handle is not this process's handle is a
        // stranger, even when the group read says it leads its group.
        val stranger = ProcessHandle.current()
        val process = mockk<java.lang.Process>()
        every { process.pid() } returns stranger.pid()
        every { process.toHandle() } returns mockk()

        assertNull(ProcessGroupSupport.groupLeaderPgid(process) { it })
    }

    @Test
    fun `interruptGroup delivers SIGINT to a group leader's process group`(
        @TempDir dir: Path,
    ) {
        assumeTrue(ProcessGroupSupport.setsidPath != null, "interruptGroup needs setsid to form a group leader")
        val marker = dir.resolve("interrupted")
        // The leader traps SIGINT, records that it ran and exits, so the marker
        // proves the signal reached the whole group (the command's own child too).
        val process =
            ProcessBuilder(
                ProcessGroupSupport.launchArgv(
                    listOf("sh", "-c", "trap 'echo int > $marker; exit 0' INT; sleep 30"),
                ),
            ).start()
        try {
            val pgid = awaitOwnProcessGroupId(process)
            assertNotNull(pgid, "the setsid leader should report its own group id")
            ProcessGroupSupport.interruptGroup(pgid)
            assertTrue(
                process.waitFor(5, TimeUnit.SECONDS),
                "the SIGINT trap should have exited the command",
            )
            assertTrue(Files.exists(marker), "SIGINT should have reached the process group")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `interruptGroup is never reached with the JVM's own group or a pgid of one or less`() {
        // The guard that keeps interruptGroup off the JVM's own group and off a
        // pgid <= 1 (`kill -INT -1`/`-0` would signal every process the user owns)
        // is ownProcessGroupId: it returns a pgid ONLY when the child leads its own
        // group (pgid == pid), and a JVM child is never pid 1. A process sharing the
        // JVM's group therefore yields null, so `?.let { interruptGroup(it) }` sends
        // no SIGINT at the JVM itself.
        val process = ProcessBuilder("sleep", "5").start()
        try {
            assertNull(ProcessGroupSupport.ownProcessGroupId(process))
            // A claimed pgid of 1 (init's group) or 0 (the caller's group) is refused
            // too: only pgid == pid is ever returned.
            assertNull(ProcessGroupSupport.groupLeaderPgid(process) { 1L })
            assertNull(ProcessGroupSupport.groupLeaderPgid(process) { 0L })
        } finally {
            process.destroyForcibly()
        }
    }

    /** Polls briefly for [process]'s own group id: the setsid leader transition races the launch. */
    private fun awaitOwnProcessGroupId(process: java.lang.Process): Long? {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            ProcessGroupSupport.ownProcessGroupId(process)?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun `firstExecutableOnPath resolves absolutely and ignores relative PATH entries`(
        @TempDir dir: Path,
    ) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val tool = dir.resolve("faketool").also { it.writeText("#!/bin/sh\n") }
        tool.toFile().setExecutable(true)
        val sep = File.pathSeparator

        // Absolute entry containing the tool -> found, absolute path.
        assertEquals(tool.toString(), ProcessGroupSupport.firstExecutableOnPath("faketool", dir.toString()))
        // A relative entry ahead of the absolute one is ignored; result stays the absolute tool.
        assertEquals(tool.toString(), ProcessGroupSupport.firstExecutableOnPath("faketool", "relbin$sep$dir"))
        // Only relative entries -> null: a relative wrapper path is never resolved (would hit the workdir).
        assertNull(ProcessGroupSupport.firstExecutableOnPath("faketool", "relbin$sep./x"))
        // No PATH -> null.
        assertNull(ProcessGroupSupport.firstExecutableOnPath("faketool", null))
    }
}
