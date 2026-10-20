package homer.app.infra

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpToolResultsTest {
    private fun text(result: CallToolResult): String = (result.content.single() as TextContent).text

    @Test
    fun `runTool returns the block's result`() {
        val ok = toolResult("done", buildJsonObject { put("runId", 1) })

        assertSame(ok, runTool { ok })
    }

    @Test
    fun `runTool turns a domain failure into an isError result naming the exception`() {
        val result = runTool { throw IllegalArgumentException("bad runId") }

        assertEquals(true, result.isError)
        assertEquals("IllegalArgumentException: bad runId", text(result))
    }

    @Test
    fun `runTool lets cancellation propagate`() {
        assertThrows<CancellationException> { runTool { throw CancellationException("gone") } }
    }

    @Test
    fun `toolResult carries the text and the structured content`() {
        val structured = buildJsonObject { put("status", "FINISHED") }

        val result = toolResult("Run 1 is FINISHED.", structured)

        assertEquals("Run 1 is FINISHED.", text(result))
        assertEquals(structured, result.structuredContent)
        assertTrue(result.isError != true)
    }

    @Test
    fun `toolError falls back to a generic message`() {
        val result = toolError(null)

        assertEquals(true, result.isError)
        assertEquals("Unknown error", text(result))
        assertNull(result.structuredContent)
    }
}
