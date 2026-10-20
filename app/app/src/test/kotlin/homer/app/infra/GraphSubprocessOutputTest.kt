package homer.app.infra

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphSubprocessOutputTest {
    private fun fileOf(bytes: Int) =
        Files.createTempFile("graph-out", ".txt").also { Files.write(it, ByteArray(bytes) { 'x'.code.toByte() }) }

    private fun <T> withFile(
        file: Path,
        block: (Path) -> T,
    ): T =
        try {
            block(file)
        } finally {
            Files.deleteIfExists(file)
        }

    @Test
    fun `readWhole refuses a file above the limit`() {
        withFile(fileOf(11)) { file ->
            Files.newInputStream(file).use { assertNull(readWhole(it, maxBytes = 10)) }
        }
    }

    @Test
    fun `readWhole returns a file exactly at the limit`() {
        withFile(fileOf(10)) { file ->
            Files.newInputStream(file).use { assertEquals("x".repeat(10), readWhole(it, maxBytes = 10)) }
        }
    }

    @Test
    fun `readWhole decodes invalid UTF-8 leniently`() {
        withFile(Files.createTempFile("graph-out", ".txt")) { file ->
            Files.write(file, byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))
            Files.newInputStream(file).use { assertEquals("a�b", readWhole(it, maxBytes = 10)) }
        }
    }

    @Test
    fun `readHead truncates and marks a long error`() {
        withFile(fileOf(100)) { file ->
            val text = Files.newInputStream(file).use { readHead(it, maxBytes = 10) }
            assertTrue(text.startsWith("x".repeat(10)))
            assertTrue(text.endsWith("(truncated)"))
            assertEquals(10, text.count { it == 'x' })
        }
    }

    @Test
    fun `readHead returns a short error unchanged`() {
        withFile(fileOf(5)) { file ->
            Files.newInputStream(file).use { assertEquals("xxxxx", readHead(it, maxBytes = 10)) }
        }
    }

    @Test
    fun `a handle opened before the path is swapped reads the original file`() {
        withFile(Files.createTempFile("graph-out", ".txt")) { file ->
            Files.newInputStream(file).use { handle ->
                // The subprocess writes through its own descriptor on the original file.
                Files.writeString(file, "original")
                Files.delete(file)
                Files.writeString(file, "swapped")
                assertEquals("original", readWhole(handle, maxBytes = 100))
            }
        }
    }

    @Test
    fun `limits are the documented values`() {
        assertEquals(2L * 1024 * 1024, MAX_GRAPH_STDOUT_BYTES)
        assertEquals(16 * 1024, MAX_GRAPH_STDERR_BYTES)
    }
}
