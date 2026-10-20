package homer.agentdebug.execution

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals

class TailReaderTest {
    private fun capture(block: () -> Unit): List<String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        val text = buffer.toString(Charsets.UTF_8)
        return if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")
    }

    @Test
    fun `consume emits complete lines with the prefix and holds back a trailing partial line`() {
        val reader = TailReader("[OUT]")
        val emitted = capture { reader.consume("first\nsecond\npart") }
        assertEquals(listOf("[OUT] first", "[OUT] second"), emitted)
    }

    @Test
    fun `consume across appends emits each line exactly once`() {
        val reader = TailReader("[OUT]")
        // Simulate a file growing between tail polls.
        val first = capture { reader.consume("alpha\nbeta\n") }
        val second = capture { reader.consume("alpha\nbeta\ngamma\n") }
        assertEquals(listOf("[OUT] alpha", "[OUT] beta"), first)
        assertEquals(listOf("[OUT] gamma"), second)
    }

    @Test
    fun `consume holds a partial line until the newline arrives`() {
        val reader = TailReader("[ERR]")
        val first = capture { reader.consume("partial") }
        val second = capture { reader.consume("partial line done\n") }
        assertEquals(emptyList(), first)
        assertEquals(listOf("[ERR] partial line done"), second)
    }

    @Test
    fun `flushRemaining emits the final unterminated line exactly once`() {
        val reader = TailReader("[OUT]")
        // Tail consumed the complete lines; the trailing partial line was held back.
        capture { reader.consume("done\ntail") }
        val flushed = capture { reader.flushRemaining("done\ntail") }
        assertEquals(listOf("[OUT] tail"), flushed)
    }

    @Test
    fun `flushRemaining emits nothing when everything was already consumed`() {
        val reader = TailReader("[OUT]")
        capture { reader.consume("a\nb\n") }
        val flushed = capture { reader.flushRemaining("a\nb\n") }
        assertEquals(emptyList(), flushed)
    }

    @Test
    fun `consume tracks position correctly across CRLF line endings`() {
        val reader = TailReader("[OUT]")
        val first = capture { reader.consume("a\r\nb\r\n") }
        val second = capture { reader.consume("a\r\nb\r\nc\r\n") }
        assertEquals(listOf("[OUT] a", "[OUT] b"), first)
        assertEquals(listOf("[OUT] c"), second)
    }

    @Test
    fun `tail then flush emits a CRLF-terminated line followed by a partial line exactly once`() {
        val reader = TailReader("[OUT]")
        val content = "one\r\nlast-no-newline"
        val tailed = capture { reader.consume(content) }
        val flushed = capture { reader.flushRemaining(content) }
        assertEquals(listOf("[OUT] one"), tailed)
        assertEquals(listOf("[OUT] last-no-newline"), flushed)
    }

    @Test
    fun `tail then flush emits every line exactly once across the full cycle`() {
        val reader = TailReader("[OUT]")
        val content = "one\ntwo\nthree\nlast-no-newline"
        // Tailer sees the file once before the command exits.
        val tailed = capture { reader.consume(content) }
        // Flush after the tail coroutine is joined.
        val flushed = capture { reader.flushRemaining(content) }
        assertEquals(listOf("[OUT] one", "[OUT] two", "[OUT] three"), tailed)
        assertEquals(listOf("[OUT] last-no-newline"), flushed)
    }
}
