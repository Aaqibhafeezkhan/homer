package homer.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The harness-neutral event model every consumer of LLM harness output reads
 * (accounting, telemetry, the live stream, the console). A harness adapter
 * translates its CLI's own JSONL dialect into these; nothing downstream sees
 * the dialect. Six kinds, deliberately. See ADR-0070.
 */
@Serializable
sealed class LlmEvent {
    @Serializable
    @SerialName("session_start")
    data class SessionStart(
        // Already gated with [gateSessionId]; null when absent or rejected.
        val sessionId: String?,
        val model: String?,
        val tools: List<String> = emptyList(),
    ) : LlmEvent()

    @Serializable
    @SerialName("assistant_message")
    data class AssistantMessage(
        // The CLI's message id; distinct ids count as model calls (ADR-0061).
        val messageId: String?,
        val model: String?,
        val text: String,
        // A subagent's message never reports the run's model.
        val subagent: Boolean = false,
    ) : LlmEvent()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val id: String,
        val name: String,
        val input: JsonElement,
        // Same message id as sibling text blocks, so a tool-only message still counts as a call.
        val messageId: String? = null,
    ) : LlmEvent()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val id: String,
        val output: JsonElement,
        val isError: Boolean = false,
    ) : LlmEvent()

    @Serializable
    @SerialName("turn_end")
    data class TurnEnd(
        val status: TurnStatus,
        val usage: LlmUsage = LlmUsage(),
        // Cumulative for the harness process, while `usage` is this turn's alone (measured on
        // Claude CLI 2.1.287). Null when the harness reports tokens only.
        val costUsd: Double? = null,
        // What the harness's terminal event says served the run (Claude: modelUsage keys).
        val model: String? = null,
        val durationMs: Long? = null,
        val durationApiMs: Long? = null,
        val numTurns: Int? = null,
        val resultText: String? = null,
    ) : LlmEvent()

    @Serializable
    @SerialName("transient_error")
    data class TransientError(
        val message: String,
    ) : LlmEvent()
}

@Serializable
sealed class TurnStatus {
    @Serializable
    @SerialName("success")
    data object Success : TurnStatus()

    @Serializable
    @SerialName("failed")
    data class Failed(
        val reason: String,
    ) : TurnStatus()

    /** A terminal stop the agent may have opted into (Claude `error_max_turns`). */
    @Serializable
    @SerialName("stopped")
    data class Stopped(
        val reason: String,
    ) : TurnStatus()
}

@Serializable
data class LlmUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
) {
    // CWE-190: counts come from agent-written stdout, so the sum saturates rather than wraps.
    operator fun plus(other: LlmUsage): LlmUsage =
        LlmUsage(
            saturatingAdd(input, other.input),
            saturatingAdd(output, other.output),
            saturatingAdd(cacheRead, other.cacheRead),
            saturatingAdd(cacheWrite, other.cacheWrite),
            saturatingAdd(reasoning, other.reasoning),
        )

    fun nonNegative(): LlmUsage =
        LlmUsage(
            input.coerceAtLeast(0),
            output.coerceAtLeast(0),
            cacheRead.coerceAtLeast(0),
            cacheWrite.coerceAtLeast(0),
            reasoning.coerceAtLeast(0),
        )
}

private fun saturatingAdd(
    a: Long,
    b: Long,
): Long =
    try {
        Math.addExact(a, b)
    } catch (_: ArithmeticException) {
        if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
    }

/** Wire form for the SSE stream and span metadata: discriminator `type`, nulls explicit. */
val LlmEventJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
    }

/** Session ids are agent-controlled stdout (§12 rule 2): gate once, where they enter Homer. */
fun gateSessionId(raw: String?): String? = raw?.takeIf { SESSION_ID_PATTERN.matches(it) }
