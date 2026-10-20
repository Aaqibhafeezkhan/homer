package homer.app.models

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmEventTest {
    @Test
    fun `events serialize with a type discriminator and explicit nulls`() {
        val json =
            LlmEventJson.encodeToJsonElement(
                LlmEvent.serializer(),
                LlmEvent.SessionStart(sessionId = null, model = "m", tools = listOf("Bash")),
            )
        assertEquals("session_start", json.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("null", json.jsonObject["sessionId"].toString())
    }

    @Test
    fun `turn end round-trips its status and usage`() {
        val event =
            LlmEvent.TurnEnd(
                status = TurnStatus.Failed("boom"),
                usage = LlmUsage(input = 1, output = 2, cacheRead = 3, cacheWrite = 4, reasoning = 5),
                costUsd = null,
            )
        val text = LlmEventJson.encodeToString(LlmEvent.serializer(), event)
        assertEquals(event, LlmEventJson.decodeFromString(LlmEvent.serializer(), text))
    }

    @Test
    fun `tool call input is carried verbatim`() {
        val event = LlmEvent.ToolCall(id = "t1", name = "Bash", input = JsonPrimitive("ls"), messageId = "m1")
        assertEquals("\"ls\"", LlmEventJson.encodeToJsonElement(LlmEvent.serializer(), event).jsonObject["input"].toString())
    }

    @Test
    fun `gateSessionId rejects ids outside the shared charset`() {
        assertEquals("abc-123_X", gateSessionId("abc-123_X"))
        assertNull(gateSessionId("../etc"))
        assertNull(gateSessionId("x".repeat(129)))
        assertNull(gateSessionId(null))
    }

    @Test
    fun `cost source none has wire form none`() {
        assertEquals("none", CostSource.NONE.wire)
    }

    @Test
    fun `usage adds field by field`() {
        assertEquals(
            LlmUsage(input = 11, output = 22, cacheRead = 33, cacheWrite = 44, reasoning = 55),
            LlmUsage(1, 2, 3, 4, 5) + LlmUsage(10, 20, 30, 40, 50),
        )
    }

    @Test
    fun `CWE-345 nonNegative clamps every negative field and keeps the rest`() {
        assertEquals(LlmUsage(), LlmUsage(-1, -2, -3, -4, -5).nonNegative())
        assertEquals(LlmUsage(1, 0, 3, 0, 5), LlmUsage(1, -2, 3, -4, 5).nonNegative())
    }

    @Test
    fun `CWE-190 a token sum saturates instead of wrapping`() {
        val max = Long.MAX_VALUE
        assertEquals(
            LlmUsage(max, max, max, max, max),
            LlmUsage(max, max, max, max, max) + LlmUsage(1, 1, 1, 1, 1),
        )
    }
}
