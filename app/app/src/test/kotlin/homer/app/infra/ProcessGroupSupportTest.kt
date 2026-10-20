package homer.app.infra

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
