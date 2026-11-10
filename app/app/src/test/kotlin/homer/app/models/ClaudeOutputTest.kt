package homer.app.models

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The `result` event carries the fields Homer judges a turn by, so the model holds every one of
 * them and defaults the ones a CLI omits. See ADR-0057.
 */
class ClaudeOutputTest {
    @Test
    fun `a result holds the CLI's error diagnosis fields`() {
        val result =
            ClaudeEvent.Result(
                type = "result",
                subtype = "success",
                isError = true,
                stopReason = JsonPrimitive("refusal"),
                terminalReason = JsonPrimitive("api_error"),
                apiErrorStatus = JsonPrimitive(529),
                durationMs = 1,
                durationApiMs = 2,
                numTurns = 3,
                result = "text",
                totalCostUsd = 0.5,
                usage = Usage(),
                modelUsage = JsonObject(emptyMap()),
            )

        assertEquals("refusal", result.stopReason?.jsonPrimitive?.content)
        assertEquals("api_error", result.terminalReason?.jsonPrimitive?.content)
        assertEquals("529", result.apiErrorStatus?.jsonPrimitive?.content)
    }

    @Test
    fun `a result omitting the diagnosis fields defaults them`() {
        val result = ClaudeEvent.Result()

        assertFalse(result.isError)
        assertNull(result.stopReason)
        assertNull(result.terminalReason)
        assertNull(result.apiErrorStatus)
    }
}
