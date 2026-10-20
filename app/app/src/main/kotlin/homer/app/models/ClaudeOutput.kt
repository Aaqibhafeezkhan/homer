package homer.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The Claude Code `--output-format stream-json` events, modelled **only where Homer
 * reads them** (ADR-0057).
 *
 * A field with no reader is deliberately not declared. `ignoreUnknownKeys` tolerates a
 * key the CLI *adds*, not one it *retypes*: a known key of the wrong type fails the whole
 * event, and its handler never runs — so an unread field can delete telemetry that is
 * read. CLI 2.1.x did exactly that twice, on `system.plugins` and
 * `result.permission_denials`, neither of which Homer ever consumed. Every field that is
 * declared carries a default, so a missing one costs an attribute rather than the event.
 *
 * The `system` event has no type here at all: it is read untyped in
 * [homer.app.executors.llm.ClaudeHarness], through accessors that cannot throw. A
 * reader added later has to do the same.
 */
@Serializable
sealed interface ClaudeEvent {
    val type: String

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        override val type: String = "assistant",
        val message: Message = Message(),
        // Set on a subagent's message; only the main agent's messages report the run's model.
        @SerialName("parent_tool_use_id")
        val parentToolUseId: String? = null,
    ) : ClaudeEvent

    @Serializable
    @SerialName("user")
    data class User(
        override val type: String = "user",
        val message: UserMessage = UserMessage(),
    ) : ClaudeEvent

    @Serializable
    @SerialName("result")
    data class Result(
        override val type: String = "result",
        val subtype: String = "",
        @SerialName("duration_ms")
        val durationMs: Long = 0,
        @SerialName("duration_api_ms")
        val durationApiMs: Long = 0,
        @SerialName("num_turns")
        val numTurns: Int = 0,
        val result: String = "",
        @SerialName("total_cost_usd")
        val totalCostUsd: Double? = null,
        val usage: Usage = Usage(),
        // The CLI's own per-model accounting; its keys are the authoritative list of
        // models that served the run.
        @SerialName("modelUsage")
        val modelUsage: JsonObject? = null,
    ) : ClaudeEvent
}

@Serializable
data class Message(
    val id: String? = null,
    val model: String? = null,
    val content: List<ContentBlock> = emptyList(),
)

@Serializable
data class UserMessage(
    val content: List<UserContentBlock> = emptyList(),
)

@Serializable
sealed interface ContentBlock {
    val type: String

    @Serializable
    @SerialName("text")
    data class Text(
        override val type: String = "text",
        val text: String,
    ) : ContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        override val type: String = "tool_use",
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlock

    @Serializable
    @SerialName("thinking")
    data class Thinking(
        override val type: String = "thinking",
        val thinking: String = "",
        val signature: String? = null,
    ) : ContentBlock

    @Serializable
    @SerialName("redacted_thinking")
    data class RedactedThinking(
        override val type: String = "redacted_thinking",
        val data: String = "",
    ) : ContentBlock
}

@Serializable
sealed interface UserContentBlock {
    val type: String

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        override val type: String = "tool_result",
        @SerialName("tool_use_id")
        val toolUseId: String,
        val content: JsonElement,
    ) : UserContentBlock

    @Serializable
    @SerialName("text")
    data class Text(
        override val type: String = "text",
        val text: String = "",
    ) : UserContentBlock
}

@Serializable
data class Usage(
    @SerialName("input_tokens")
    val inputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Int = 0,
    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Int = 0,
    @SerialName("output_tokens")
    val outputTokens: Int = 0,
)
