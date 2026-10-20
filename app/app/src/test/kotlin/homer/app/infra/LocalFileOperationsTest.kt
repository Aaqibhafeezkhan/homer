package homer.app.infra

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalFileOperationsTest {
    private val ops = LocalFileOperations()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fileSize returns correct byte count`() {
        val file = tempDir.resolve("test.txt")
        file.writeText("hello")
        assertEquals(5L, ops.fileSize(file))
    }

    @Test
    fun `readText returns file content`() {
        val file = tempDir.resolve("test.txt")
        file.writeText("hello world")
        assertEquals("hello world", ops.readText(file))
    }

    @Test
    fun `fileExists returns true for existing file, false for missing`() {
        val file = tempDir.resolve("exists.txt")
        file.writeText("x")
        assertTrue(ops.fileExists(file))
        assertFalse(ops.fileExists(tempDir.resolve("missing.txt")))
    }

    @Test
    fun `streamFile writes all bytes to output stream`() =
        runTest {
            val content = "stream me"
            val file = tempDir.resolve("stream.txt")
            file.writeText(content)
            val out = ByteArrayOutputStream()
            ops.streamFile(file, out)
            assertEquals(content, out.toString(Charsets.UTF_8.name()))
        }

    @Test
    fun `streamFile surfaces a failed open as the open's own IOException`() =
        runTest {
            val missing = tempDir.resolve("missing.txt")
            val out = ByteArrayOutputStream()
            assertFailsWith<NoSuchFileException> { ops.streamFile(missing, out) }
            assertEquals(0, out.size())
        }

    @Test
    fun `streamFile refuses to follow a symlink at open time`() =
        runTest {
            val outsideDir =
                java.nio.file.Files
                    .createTempDirectory("lfo-outside")
            val target = outsideDir.resolve("secret.txt")
            target.writeText("host secret")
            val link = tempDir.resolve("link.txt")
            java.nio.file.Files
                .createSymbolicLink(link, target)

            val out = ByteArrayOutputStream()
            assertFailsWith<IOException> { ops.streamFile(link, out) }
            assertEquals(0, out.size())

            outsideDir.toFile().deleteRecursively()
        }

    @Test
    fun `streamFile refuses a hard-linked file at open time`() =
        runTest {
            val original = tempDir.resolve("original.txt")
            original.writeText("secret bytes")
            val linked = tempDir.resolve("linked.txt")
            Files.createLink(linked, original)

            val out = ByteArrayOutputStream()
            assertFailsWith<IOException> { ops.streamFile(linked, out) }
            assertEquals(0, out.size())
        }

    @Test
    fun `streamFile refuses a FIFO instead of blocking on open`() =
        runTest {
            assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
            val fifo = tempDir.resolve("pipe")
            assumeTrue(
                runCatching { ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0 }.getOrDefault(false),
                "mkfifo unavailable",
            )
            assertFailsWith<IOException> { ops.streamFile(fifo, ByteArrayOutputStream()) }
        }

    @Test
    fun `listRegularFiles excludes symlinks`() {
        val real = tempDir.resolve("real.txt")
        real.writeText("x")
        val outsideDir =
            java.nio.file.Files
                .createTempDirectory("lfo-outside")
        val outside = outsideDir.resolve("secret.txt")
        outside.writeText("s")
        java.nio.file.Files
            .createSymbolicLink(tempDir.resolve("link.txt"), outside)

        val names = ops.listRegularFiles(tempDir, Int.MAX_VALUE).map { it.fileName.toString() }

        assertTrue(names.contains("real.txt"), "regular files are listed")
        assertFalse(names.contains("link.txt"), "symlinks are not listed with their target's metadata")

        outsideDir.toFile().deleteRecursively()
    }

    @Test
    fun `fileSize does not follow a symlink to report the target size`() {
        val outsideDir = Files.createTempDirectory("lfo-outside")
        try {
            val target = Files.writeString(outsideDir.resolve("secret.bin"), "0123456789") // 10 bytes
            val link = tempDir.resolve("link")
            Files.createSymbolicLink(link, target)
            // NOFOLLOW: the symlink's own size is not the 10-byte target size.
            assertNotEquals(10L, ops.fileSize(link))
        } finally {
            outsideDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `lastModifiedEpochSecond does not follow a symlink`() {
        val outsideDir = Files.createTempDirectory("lfo-outside")
        try {
            val target = Files.writeString(outsideDir.resolve("t.bin"), "x")
            // backdate the target far in the past
            Files.setLastModifiedTime(target, FileTime.fromMillis(0))
            val link = tempDir.resolve("link2")
            Files.createSymbolicLink(link, target)
            // The link was just created, so its own mtime is recent, not epoch 0.
            assertTrue(ops.lastModifiedEpochSecond(link) > 0L)
        } finally {
            outsideDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `listRegularFiles caps the result at limit`() {
        repeat(5) { tempDir.resolve("f$it.txt").writeText("x") }

        val listed = ops.listRegularFiles(tempDir, 3)

        assertEquals(3, listed.size)
    }
}
