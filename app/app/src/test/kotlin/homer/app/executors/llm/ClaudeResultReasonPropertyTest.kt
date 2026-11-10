package homer.app.executors.llm

import homer.app.models.FAILURE_REASON_TRUNCATION_MARKER
import homer.app.models.LlmEvent
import homer.app.models.MAX_FAILURE_REASON_LENGTH
import homer.app.models.Translation
import homer.app.models.TurnStatus
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `is_error` reason is built from CLI-written fields, so the examples above are mirrored by
 * properties over what a CLI could actually write: control characters, oversized text and shapes
 * Homer does not expect. The reason must stay one bounded line, and a result that is not an error
 * must never fail (no over-denial). See ADR-0047.
 */
class ClaudeResultReasonPropertyTest {
    private val harness = ClaudeHarness()

    /** Arbitrary CLI text with the control characters an error echo or a terminal escape brings. */
    private val cliText: Arb<String> = Arb.string(0..1_500).map { it + "\u0000\u001b\n\t\r" }

    /** [text] as one of the shapes a CLI could write into a diagnosis field. */
    private fun shape(
        text: String,
        pick: Int,
    ): JsonElement =
        when (pick.mod(5)) {
            0 -> JsonPrimitive(text)
            1 -> JsonPrimitive(text.length)
            2 -> JsonNull
            3 -> JsonObject(mapOf("why" to JsonPrimitive(text)))
            else -> JsonArray(listOf(JsonPrimitive(text)))
        }

    /** One `result` line with the three diagnosis fields set to [shapes], translated. */
    private fun turnEnd(
        shapes: List<JsonElement>,
        isError: Boolean?,
    ): LlmEvent.TurnEnd {
        val line =
            buildJsonObject {
                put("type", "result")
                put("subtype", "success")
                isError?.let { put("is_error", it) }
                put("stop_reason", shapes[0])
                put("terminal_reason", shapes[1])
                put("api_error_status", shapes[2])
                put("total_cost_usd", 0.25)
            }
        val events = (harness.translate(line) as Translation.Events).events
        return events.filterIsInstance<LlmEvent.TurnEnd>().single()
    }

    private fun reasonFor(shapes: List<JsonElement>): String {
        val status = turnEnd(shapes, isError = true).status
        assertIs<TurnStatus.Failed>(status, "an is_error result must fail the turn")
        return status.reason
    }

    @Test
    fun `an is_error reason is one bounded line whatever the CLI wrote`() =
        runTest {
            checkAll(cliText, cliText, cliText, Arb.int()) { a, b, c, pick ->
                val reason = reasonFor(listOf(shape(a, pick), shape(b, pick + 1), shape(c, pick + 2)))

                assertTrue(reason.startsWith("is_error"), "reason lost its marker: $reason")
                assertTrue(reason.length <= MAX_FAILURE_REASON_LENGTH, "over cap (${reason.length}): $reason")
                assertTrue(reason.none { it.isISOControl() }, "control character in: $reason")
                if (reason.endsWith(FAILURE_REASON_TRUNCATION_MARKER)) {
                    assertEquals(MAX_FAILURE_REASON_LENGTH, reason.length, "a cut reason must fill the cap: $reason")
                }
            }
        }

    @Test
    fun `an over-long diagnosis is cut to the cap with the truncation marker`() {
        val reason = reasonFor(listOf(JsonPrimitive("x".repeat(2_000)), JsonNull, JsonNull))

        assertEquals(MAX_FAILURE_REASON_LENGTH, reason.length)
        assertTrue(reason.endsWith(FAILURE_REASON_TRUNCATION_MARKER), "no truncation marker in: $reason")
    }

    @Test
    fun `a result that is not an error stays successful whatever the diagnosis holds`() =
        runTest {
            checkAll(cliText, cliText, cliText, Arb.int()) { a, b, c, pick ->
                val shapes = listOf(shape(a, pick), shape(b, pick + 1), shape(c, pick + 2))

                // `is_error: false`, and an older CLI's result with no `is_error` at all.
                assertEquals(TurnStatus.Success, turnEnd(shapes, isError = false).status)
                assertEquals(TurnStatus.Success, turnEnd(shapes, isError = null).status)
            }
        }
}
