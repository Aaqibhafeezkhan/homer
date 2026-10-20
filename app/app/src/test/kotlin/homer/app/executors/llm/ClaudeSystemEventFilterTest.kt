package homer.app.executors.llm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeSystemEventFilterTest {
    private val thinking =
        """{"type":"system","subtype":"thinking_tokens","estimated_tokens":2,"estimated_tokens_delta":2,"session_id":"s","uuid":"u"}"""

    @Test
    fun `drops a system event whose subtype is configured`() {
        val f = ClaudeSystemEventFilter(setOf("thinking_tokens"))
        assertFalse(f.keep(thinking))
    }

    @Test
    fun `keeps system events with other subtypes`() {
        val f = ClaudeSystemEventFilter(setOf("thinking_tokens"))
        assertTrue(f.keep("""{"type":"system","subtype":"init","claude_code_version":"2.1.272"}"""))
    }

    @Test
    fun `keeps a non-system event that mentions the subtype`() {
        val f = ClaudeSystemEventFilter(setOf("thinking_tokens"))
        assertTrue(f.keep("""{"type":"assistant","message":{"content":[{"type":"text","text":"see \"thinking_tokens\" in the log"}]}}"""))
        assertTrue(f.keep("""{"type":"result","subtype":"thinking_tokens"}"""))
    }

    @Test
    fun `keeps lines that are not JSON objects`() {
        val f = ClaudeSystemEventFilter(setOf("thinking_tokens"))
        assertTrue(f.keep("""not json "thinking_tokens""""))
        assertTrue(f.keep("""["thinking_tokens"]"""))
        assertTrue(f.keep(""))
    }

    @Test
    fun `matches the subtype exactly, not as a prefix`() {
        val f = ClaudeSystemEventFilter(setOf("thinking"))
        assertTrue(f.keep(thinking))
    }

    @Test
    fun `counts events and bytes per subtype, newline included`() {
        val f = ClaudeSystemEventFilter(setOf("thinking_tokens", "other"))
        f.keep(thinking)
        f.keep(thinking)
        f.keep("""{"type":"system","subtype":"other"}""")
        f.keep("""{"type":"system","subtype":"init"}""")
        val expectedThinkingBytes = 2L * (thinking.toByteArray(Charsets.UTF_8).size + 1)
        assertEquals(
            listOf(
                DroppedSubtype("thinking_tokens", 2, expectedThinkingBytes),
                DroppedSubtype("other", 1, ("""{"type":"system","subtype":"other"}""".length + 1).toLong()),
            ),
            f.dropped(),
        )
    }

    @Test
    fun `dropped omits configured subtypes that never occurred`() {
        val f = ClaudeSystemEventFilter(setOf("thinking_tokens", "never"))
        f.keep(thinking)
        assertEquals(listOf("thinking_tokens"), f.dropped().map { it.subtype })
    }

    @Test
    fun `an empty set keeps everything`() {
        val f = ClaudeSystemEventFilter(emptySet())
        assertTrue(f.keep(thinking))
        assertTrue(f.dropped().isEmpty())
    }
}
