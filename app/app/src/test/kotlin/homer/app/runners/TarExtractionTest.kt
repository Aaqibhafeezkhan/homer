package homer.app.runners

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
