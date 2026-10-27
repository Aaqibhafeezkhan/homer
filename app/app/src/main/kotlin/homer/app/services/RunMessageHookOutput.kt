package homer.app.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A Claude Code hook event the take route can format its answer for; [param] is its `?hook=` value. */
enum class HookEvent(
    val param: String,
    val claudeName: String,
) {
    POST_TOOL_USE("post-tool-use", "PostToolUse"),
    STOP("stop", "Stop"),
    ;

    companion object {
        fun fromParam(param: String): HookEvent? = entries.firstOrNull { it.param == param }
    }
}

/**
 * Formats taken operator messages for the model: as plain context text (the `check_messages` MCP
 * tool) or as the JSON a Claude Code hook prints, built server-side so the shell helper parses no
 * JSON. See ADR-0097.
 */
object RunMessageHookOutput {
    fun contextText(messages: List<RunMessage>): String {
        val (what, pronoun) = if (messages.size == 1) "a message" to "it" else "${messages.size} messages" to "them"
        return "The operator sent you $what while you were working. Take $pronoun into account before you continue.\n\n" +
            messages.joinToString("\n\n") { "Message from the operator:\n${it.text}" }
    }

    fun render(
        event: HookEvent,
        messages: List<RunMessage>,
    ): JsonObject =
        when (event) {
            HookEvent.POST_TOOL_USE -> {
                buildJsonObject {
                    putJsonObject("hookSpecificOutput") {
                        put("hookEventName", event.claudeName)
                        put("additionalContext", contextText(messages))
                    }
                }
            }

            HookEvent.STOP -> {
                buildJsonObject {
                    put("decision", "block")
                    put("reason", contextText(messages))
                }
            }
        }
}
