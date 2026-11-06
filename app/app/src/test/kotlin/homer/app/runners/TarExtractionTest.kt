package homer.app.runners

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

class TarExtractionTest {
    /** A plain (uncompressed) tar with [entries], positioned at its first entry. */
    private fun tarOf(vararg entries: TarArchiveEntry): Pair<TarArchiveInputStream, TarArchiveEntry> {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { out ->
            entries.forEach { entry ->
                out.putArchiveEntry(entry)
                if (entry.size > 0) out.write("payload".toByteArray())
                out.closeArchiveEntry()
            }
        }
        val tar = TarArchiveInputStream(ByteArrayInputStream(bytes.toByteArray()))
        return tar to tar.nextEntry
    }

    /** The formatted WARN lines [TarExtraction] logs (through the `TarStreams` logger) while [block] runs. */
    private fun warningsDuring(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(TarStreams::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level.toString() == "WARN" }.map { it.formattedMessage }
    }

    private fun extract(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        local: Path,
    ) = TarExtraction.extractEntry(tar, entry, remoteIsDir = true, local, "", DownloadMode.OVERWRITE, null, "test", null)

    @Test
    fun `a regular entry is written inside the target with its mode normalized`(
        @TempDir local: Path,
    ) {
        val (tar, entry) =
            tarOf(
                TarArchiveEntry("dir/run.sh").apply {
                    size = 7L
                    mode = "100777".toInt(8)
                },
            )

        extract(tar, entry, local)

        val written = local.resolve("dir/run.sh")
        assertEquals("payload", written.readText())
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), written.getPosixFilePermissions())
    }

    @Test
    fun `a link entry is skipped and an escaping name is refused`(
        @TempDir local: Path,
    ) {
        val (linkTar, link) = tarOf(TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" })
        extract(linkTar, link, local)
        assertFalse(Files.exists(local.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS))

        val (escapeTar, escape) = tarOf(TarArchiveEntry("../escape").apply { size = 7L })
        assertThrows<SecurityException> { extract(escapeTar, escape, local) }
    }

    private fun executableEntry(name: String) =
        TarArchiveEntry(name).apply {
            size = 7L
            mode = "100755".toInt(8)
        }

    @Test
    fun `a failed mode normalization removes both the landing file and the dangling link it followed`(
        @TempDir local: Path,
    ) {
        val link = Files.createSymbolicLink(local.resolve("link"), Path.of("landing.txt"))
        val failingView = mockk<PosixFileAttributeView> { every { setPermissions(any()) } throws IOException("chmod refused") }
        val (tar, entry) = tarOf(executableEntry("link"))

        lateinit var denied: SecurityException
        val warnings =
            warningsDuring {
                denied =
                    assertThrows<SecurityException> {
                        TarExtraction.extractEntry(
                            tar,
                            entry,
                            true,
                            local,
                            "",
                            DownloadMode.OVERWRITE,
                            null,
                            "test",
                        ) { _, _ -> failingView }
                    }
            }

        assertEquals(emptyList(), warnings, "a clean removal logs no removal failure")
        assertFalse(Files.exists(local.resolve("landing.txt"), LinkOption.NOFOLLOW_LINKS), "the landing file must be removed")
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link must be removed")
        assertTrue("Could not normalize the mode" in denied.message.orEmpty())
        assertFalse("left behind" in denied.message.orEmpty(), "a clean removal must not claim leftovers: ${denied.message}")
    }

    @Test
    fun `a failed mode normalization of a plain file removes it`(
        @TempDir local: Path,
    ) {
        val failingView = mockk<PosixFileAttributeView> { every { setPermissions(any()) } throws IOException("chmod refused") }
        val (tar, entry) = tarOf(executableEntry("plain.sh"))

        assertThrows<SecurityException> {
            TarExtraction.extractEntry(tar, entry, true, local, "", DownloadMode.OVERWRITE, null, "test") { _, _ -> failingView }
        }

        assertFalse(Files.exists(local.resolve("plain.sh"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a directory download normalizes the mode of the file a contained link leads to`(
        @TempDir local: Path,
    ) {
        Files.createSymbolicLink(local.resolve("link"), Path.of("landing.txt"))
        val (tar, entry) = tarOf(executableEntry("link"))

        extract(tar, entry, local)

        assertEquals("payload", local.resolve("landing.txt").readText())
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), local.resolve("landing.txt").getPosixFilePermissions())
    }

    @Test
    fun `a single-file download normalizes the mode of the file itself`(
        @TempDir parent: Path,
    ) {
        val local = parent.resolve("run.sh")
        val (tar, entry) = tarOf(executableEntry("run.sh"))

        TarExtraction.extractEntry(tar, entry, false, local, "run.sh", DownloadMode.OVERWRITE, null, "test", null)

        assertEquals("payload", local.readText())
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), local.getPosixFilePermissions())
    }

    /** Runs a directory extraction whose mode normalization fails after [beforeFailure] ran, and returns the refusal. */
    private fun extractWithFailingChmod(
        local: Path,
        entryName: String,
        beforeFailure: () -> Unit,
    ): SecurityException {
        val failingView =
            mockk<PosixFileAttributeView> {
                every { setPermissions(any()) } answers
                    {
                        beforeFailure()
                        throw IOException("chmod refused")
                    }
            }
        val (tar, entry) = tarOf(executableEntry(entryName))
        return assertThrows<SecurityException> {
            TarExtraction.extractEntry(tar, entry, true, local, "", DownloadMode.OVERWRITE, null, "test") { _, _ -> failingView }
        }
    }

    @Test
    fun `the refusal says content was left behind when the landing file cannot be removed`(
        @TempDir local: Path,
    ) {
        val sub = Files.createDirectories(local.resolve("sub"))
        val link = Files.createSymbolicLink(local.resolve("link"), Path.of("sub/landing.txt"))
        try {
            lateinit var denied: SecurityException
            val warnings = warningsDuring { denied = extractWithFailingChmod(local, "link") { sub.toFile().setWritable(false) } }

            assertEquals(1, warnings.size, "only the landing path's removal failed: $warnings")
            assertTrue(warnings.single().startsWith("Could not remove the landing path '"), warnings.single())
            assertTrue("content was left behind at the link's destination" in denied.message.orEmpty(), denied.message)
            assertTrue(Files.exists(sub.resolve("landing.txt")), "the landing file could not be removed")
            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link itself must still be removed")
        } finally {
            sub.toFile().setWritable(true)
        }
    }

    @Test
    fun `the refusal says the target could not be removed when the link cannot be removed`(
        @TempDir local: Path,
    ) {
        val ro = Files.createDirectories(local.resolve("ro"))
        Files.createSymbolicLink(ro.resolve("link"), Path.of("../landing.txt"))
        try {
            lateinit var denied: SecurityException
            val warnings = warningsDuring { denied = extractWithFailingChmod(local, "ro/link") { ro.toFile().setWritable(false) } }

            assertEquals(1, warnings.size, "only the link's removal failed: $warnings")
            assertTrue(
                warnings.single().matches(Regex("Could not remove '.*/ro/link' after a failed mode normalization")),
                warnings.single(),
            )
            assertTrue("the target could not be removed" in denied.message.orEmpty(), denied.message)
            assertFalse(Files.exists(local.resolve("landing.txt")), "the landing file must be removed")
            assertTrue(Files.exists(ro.resolve("link"), LinkOption.NOFOLLOW_LINKS), "the link could not be removed")
        } finally {
            ro.toFile().setWritable(true)
        }
    }

    @Test
    fun `each extracted file is closed after it is written`(
        @TempDir local: Path,
    ) {
        val os =
            java.lang.management.ManagementFactory
                .getOperatingSystemMXBean() as com.sun.management.UnixOperatingSystemMXBean
        val before = os.openFileDescriptorCount

        repeat(FD_LEAK_ENTRIES) {
            val (tar, entry) = tarOf(executableEntry("f$it.sh"))
            extract(tar, entry, local)
        }

        // A leaked stream per entry would add FD_LEAK_ENTRIES descriptors; the margin absorbs unrelated ones.
        val opened = os.openFileDescriptorCount - before
        assertTrue(opened < FD_LEAK_ENTRIES / 2, "extraction left $opened file descriptors open")
    }

    private companion object {
        const val FD_LEAK_ENTRIES = 100
    }
}
