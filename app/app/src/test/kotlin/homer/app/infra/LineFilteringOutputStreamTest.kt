package homer.app.infra

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LineFilteringOutputStreamTest {
    private val dropContainingDrop = OutputLineFilter { line -> !line.contains("drop") }

    private fun filtered(
        input: String,
        filter: OutputLineFilter = dropContainingDrop,
        maxLineBytes: Int = 1_048_576,
        chunkSize: Int = Int.MAX_VALUE,
    ): String {
        val sink = ByteArrayOutputStream()
        LineFilteringOutputStream(sink, filter, maxLineBytes).use { out ->
            val bytes = input.toByteArray(Charsets.UTF_8)
            var i = 0
            while (i < bytes.size) {
                val n = minOf(chunkSize, bytes.size - i)
                out.write(bytes, i, n)
                i += n
            }
        }
        return sink.toString(Charsets.UTF_8)
    }

    @Test
    fun `kept lines are written verbatim and dropped lines vanish`() {
        assertEquals("keep 1\nkeep 2\n", filtered("keep 1\ndrop me\nkeep 2\n"))
    }

    @Test
    fun `a line split across many write calls is still judged as one line`() {
        assertEquals("keep 1\nkeep 2\n", filtered("keep 1\ndrop me\nkeep 2\n", chunkSize = 1))
        assertEquals("keep 1\nkeep 2\n", filtered("keep 1\ndrop me\nkeep 2\n", chunkSize = 3))
    }

    @Test
    fun `a trailing line without newline is filtered and written without newline`() {
        assertEquals("keep", filtered("drop\nkeep"))
        assertEquals("keep\n", filtered("keep\ndrop"))
    }

    @Test
    fun `blank lines are handed to the filter and written when kept`() {
        assertEquals("\n\nkeep\n", filtered("\n\nkeep\n"))
    }

    @Test
    fun `multi-byte UTF-8 content is written byte for byte`() {
        val input = "kept: příliš žluťoučký kůň 🐎\ndrop: úpěl\n"
        assertEquals("kept: příliš žluťoučký kůň 🐎\n", filtered(input))
    }

    @Test
    fun `a line longer than maxLineBytes streams through unfiltered and filtering resumes after it`() {
        val huge = "drop " + "x".repeat(100)
        val out = filtered("$huge\ndrop small\nkeep\n", maxLineBytes = 16, chunkSize = 7)
        assertEquals("$huge\nkeep\n", out)
    }

    @Test
    fun `an over-long line without a trailing newline is flushed on close`() {
        val huge = "drop " + "y".repeat(50)
        assertEquals(huge, filtered(huge, maxLineBytes = 16, chunkSize = 4))
    }

    @Test
    fun `single-byte writes work`() {
        val sink = ByteArrayOutputStream()
        LineFilteringOutputStream(sink, dropContainingDrop).use { out ->
            "a\ndrop\nb\n".toByteArray().forEach { out.write(it.toInt()) }
        }
        assertEquals("a\nb\n", sink.toString(Charsets.UTF_8))
    }

    @Test
    fun `close closes the target`() {
        var closed = false
        val sink =
            object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        LineFilteringOutputStream(sink, dropContainingDrop).close()
        assertEquals(true, closed)
    }

    @Test
    fun `close closes the target even when the trailing write fails`() {
        var closed = false
        val sink =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("disk full")

                override fun close() {
                    closed = true
                }
            }
        val out = LineFilteringOutputStream(sink, dropContainingDrop)
        out.write("partial".toByteArray(Charsets.UTF_8))
        assertFailsWith<IOException> { out.close() }
        assertEquals(true, closed)
    }
}
