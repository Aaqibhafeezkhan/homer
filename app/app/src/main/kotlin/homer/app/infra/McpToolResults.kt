package homer.app.infra

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Runs one MCP tool call of `McpToolFactory` or `QuestionsMcpToolFactory`. Domain failures become
 * isError tool results (so the LLM can read and self-correct), never JSON-RPC protocol errors.
 * CancellationException propagates — it is the coroutine machinery, not a domain failure.
 */
internal inline fun runTool(block: () -> CallToolResult): CallToolResult =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        toolError("${e::class.simpleName}: ${e.message}")
    }

/** A successful tool result: [text] for the LLM, [structured] as its structured content. */
internal fun toolResult(
    text: String,
    structured: JsonObject,
): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text)),
        structuredContent = structured,
    )

/** An isError tool result carrying [message]. */
internal fun toolError(message: String?): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(message ?: "Unknown error")),
        isError = true,
    )
