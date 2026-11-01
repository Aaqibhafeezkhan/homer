package homer.app.executors.llm

import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmUsage
import homer.app.models.Translation
import homer.app.models.TurnStatus
import homer.app.models.gateSessionId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** OpenAI Codex CLI (`codex exec --json`). Every accessor is untyped and total. */
class CodexHarness(
    textCompiler: TextCompiler = DefaultTextCompiler(),
) : LlmHarness<Command.Codex> {
    override val id: String = "codex"
    override val reportsCost: Boolean = false

    // Item ids, not request ids: one response can carry a message and a tool call.
    override val countsModelCalls: Boolean = false
    private val argvBuilder = CodexArgvBuilder(textCompiler)

    override fun argv(
        command: Command.Codex,
        env: Map<String, String>,
    ): List<String> = argvBuilder.build(command.cmd, command.options, env)

    override fun escalated(
        command: Command.Codex,
        tier: LlmEscalation,
    ): Command.Codex = command.copy(options = command.options.copy(model = tier.model, options = tier.options ?: command.options.options))

    override fun translate(line: JsonObject): Translation =
        when (line.nonBlankString("type")) {
            "thread.started" -> {
                Translation.Events(listOf(LlmEvent.SessionStart(gateSessionId(line.nonBlankString("thread_id")), model = null)))
            }

            "item.completed" -> {
                translateCompleted(line)
            }

            "turn.completed" -> {
                Translation.Events(listOf(LlmEvent.TurnEnd(TurnStatus.Success, usage(line["usage"] as? JsonObject))))
            }

            "turn.failed" -> {
                Translation.Events(listOf(LlmEvent.TurnEnd(TurnStatus.Failed(line.errorMessage() ?: "no message"))))
            }

            "error" -> {
                transientError(line)
            }

            else -> {
                Translation.Ignored
            }
        }

    override fun inspect(
        events: List<LlmEvent>,
        raw: List<JsonObject>,
        command: Command.Codex,
    ): Inspection =
        inspectTurns(
            events,
            failedReason = "stream contains a 'turn.failed' event",
            noSuccessReason = "stdout contains no 'turn.completed' event",
        )

    private fun translateCompleted(line: JsonObject): Translation =
        translateItem(line["item"] as? JsonObject ?: return Translation.Malformed("item.completed without an item object"))

    private fun translateItem(item: JsonObject): Translation {
        val id = item.nonBlankString("id") ?: return Translation.Malformed("item without id")
        val type = item.nonBlankString("type") ?: return Translation.Malformed("item without type")
        return when (type) {
            "agent_message" -> {
                agentMessage(id, item)
            }

            "command_execution" -> {
                commandExecution(id, type, item)
            }

            "mcp_tool_call" -> {
                mcpToolCall(id, type, item)
            }

            "file_change" -> {
                pair(id, type, input = item.without("status"), output = JsonPrimitive(item.nonBlankString("status") ?: ""), isError = false)
            }

            else -> {
                Translation.Ignored
            }
        }
    }

    private fun agentMessage(
        id: String,
        item: JsonObject,
    ) = Translation.Events(
        listOf(
            LlmEvent.AssistantMessage(
                messageId = id,
                model = null,
                text =
                    item.nonBlankString("text") ?: "",
            ),
        ),
    )

    private fun commandExecution(
        id: String,
        type: String,
        item: JsonObject,
    ): Translation {
        val exit = (item["exit_code"] as? JsonPrimitive)?.longOrNull
        return pair(
            id,
            type,
            input = item.without("aggregated_output", "exit_code", "status"),
            output =
                item["aggregated_output"] ?: JsonPrimitive(""),
            isError = exit != null && exit != 0L,
        )
    }

    private fun mcpToolCall(
        id: String,
        type: String,
        item: JsonObject,
    ) = pair(
        id,
        type,
        input = item.without("result", "error", "status"),
        output =
            item["result"] ?: item["error"] ?: JsonPrimitive(""),
        isError = item["error"] != null,
    )

    private fun pair(
        id: String,
        name: String,
        input: JsonObject,
        output: JsonElement,
        isError: Boolean,
    ) = Translation.Events(listOf(LlmEvent.ToolCall(id, name, input, messageId = id), LlmEvent.ToolResult(id, output, isError)))

    private fun usage(u: JsonObject?): LlmUsage =
        LlmUsage(
            input = u.long("input_tokens"),
            output = u.long("output_tokens"),
            cacheRead = u.long("cached_input_tokens"),
            cacheWrite = u.long("cache_write_input_tokens"),
            reasoning = u.long("reasoning_output_tokens"),
        )

    private fun JsonObject?.long(key: String): Long = (this?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject.without(vararg keys: String): JsonObject =
        buildJsonObject {
            entries.filter { it.key !in keys }.forEach { put(it.key, it.value) }
        }
}
