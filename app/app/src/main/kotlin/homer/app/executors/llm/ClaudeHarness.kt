package homer.app.executors.llm

import homer.app.exceptions.ClaudeCliVersionMismatchException
import homer.app.exceptions.ValidationException
import homer.app.executors.ClaudeArgvBuilder
import homer.app.executors.PreStartFailureReason
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.LlmOutputJson
import homer.app.infra.TextCompiler
import homer.app.models.ClaudeEvent
import homer.app.models.Command
import homer.app.models.ContentBlock
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmUsage
import homer.app.models.Translation
import homer.app.models.TurnStatus
import homer.app.models.UserContentBlock
import homer.app.models.gateSessionId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/** Claude Code (`--output-format stream-json`). Typed decode where Homer reads a field, untyped elsewhere (ADR-0057). */
class ClaudeHarness(
    private val requireClaudeCodeVersion: String? = null,
    private val dropSystemSubtypes: Set<String> = emptySet(),
    textCompiler: TextCompiler = DefaultTextCompiler(),
) : LlmHarness<Command.Claude> {
    override val id: String = "claude"
    override val reportsCost: Boolean = true
    override val countsModelCalls: Boolean = true
    private val argvBuilder = ClaudeArgvBuilder(textCompiler)

    override fun argv(
        command: Command.Claude,
        env: Map<String, String>,
    ): List<String> {
        val opts = command.options
        if (opts.systemPrompt != null && opts.systemPromptFile != null) {
            throw ValidationException("systemPrompt and systemPromptFile are mutually exclusive")
        }
        return argvBuilder.build(command.cmd, opts, env)
    }

    override fun escalated(
        command: Command.Claude,
        tier: LlmEscalation,
    ): Command.Claude = command.copy(options = command.options.copy(model = tier.model, options = tier.options ?: command.options.options))

    override fun processEnv(command: Command.Claude): Map<String, String> =
        command.options.maxMcpOutputTokens?.let { mapOf("MAX_MCP_OUTPUT_TOKENS" to it.toString()) } ?: emptyMap()

    override fun stdOutFilter(command: Command.Claude): LlmStdOutFilter? =
        dropSystemSubtypes.takeIf { it.isNotEmpty() }?.let { ClaudeSystemEventFilter(it) }

    override fun terminalStopConfigured(command: Command.Claude): Boolean = command.options.maxTurnsExitCode != null

    override fun translate(line: JsonObject): Translation =
        when (line.nonBlankString("type")) {
            "system" -> {
                translateSystem(line)
            }

            "assistant" -> {
                decode<ClaudeEvent.Assistant>(line) { translateAssistant(it) }
            }

            "user" -> {
                decode<ClaudeEvent.User>(line) { translateUser(it) }
            }

            "result" -> {
                // total_cost_usd is read untyped so an unparseable value degrades to null
                // instead of failing the event. See ADR-0070.
                decode<ClaudeEvent.Result>(line.withoutCostField()) {
                    // Any line can carry session_id, not just system/init. See ADR-0070.
                    val sessionStart = line.nonBlankString("session_id")?.let { LlmEvent.SessionStart(gateSessionId(it), model = null) }
                    Translation.Events(listOfNotNull(sessionStart, translateResult(it, line.costUsd())))
                }
            }

            else -> {
                Translation.Ignored
            }
        }

    override fun inspect(
        events: List<LlmEvent>,
        raw: List<JsonObject>,
        command: Command.Claude,
    ): Inspection {
        val end =
            events.firstOrNull { it is LlmEvent.TurnEnd } as LlmEvent.TurnEnd?
                ?: return Inspection.Malformed("stdout contains no 'result' event")
        val verdict =
            when (val s = end.status) {
                TurnStatus.Success -> {
                    Inspection.Success
                }

                is TurnStatus.Stopped -> {
                    command.options.maxTurnsExitCode?.let { Inspection.Terminal(it) }
                        ?: Inspection.Malformed(notSuccess(s.reason))
                }

                is TurnStatus.Failed -> {
                    Inspection.Malformed(notSuccess(s.reason))
                }
            }
        if (verdict is Inspection.Success) checkVersionPin(raw)
        return verdict
    }

    /**
     * The malformed reason for a zero-exit attempt whose result event is not a success: [reason] is
     * the CLI's own — a subtype, or an `is_error` diagnosis — so the message never calls it one.
     */
    private fun notSuccess(reason: String): String = "result event is not a successful turn: $reason"

    private fun checkVersionPin(raw: List<JsonObject>) {
        val pinned = requireClaudeCodeVersion ?: return
        val actual = raw.firstOrNull { it.nonBlankString("type") == "system" }?.nonBlankString("claude_code_version")
        if (actual != pinned) throw ClaudeCliVersionMismatchException(expected = pinned, actual = actual)
    }

    private fun translateSystem(line: JsonObject): Translation {
        val model = line.nonBlankString("model")
        val sessionId = line.nonBlankString("session_id")
        // A model-less system line is still worth a SessionStart if it carries a session id. See ADR-0070.
        if (model == null && sessionId == null) return Translation.Ignored
        val tools = (line["tools"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return Translation.Events(listOf(LlmEvent.SessionStart(gateSessionId(sessionId), model, tools)))
    }

    private fun translateAssistant(event: ClaudeEvent.Assistant): Translation {
        val messageId = event.message.id
        val model = event.message.model?.takeIf { it.isNotBlank() }
        val out =
            event.message.content.mapNotNull { block ->
                when (block) {
                    is ContentBlock.Text -> {
                        LlmEvent.AssistantMessage(
                            messageId,
                            model,
                            block.text,
                            subagent = event.parentToolUseId != null,
                        )
                    }

                    is ContentBlock.ToolUse -> {
                        LlmEvent.ToolCall(block.id, block.name, block.input, messageId)
                    }

                    is ContentBlock.Thinking, is ContentBlock.RedactedThinking -> {
                        null
                    }
                }
            }
        if (out.isNotEmpty()) return Translation.Events(out)
        // A thinking-only message still made a model call if it has an id; keep it in modelCalls. See ADR-0070.
        return messageId?.let {
            Translation.Events(listOf(LlmEvent.AssistantMessage(it, model, text = "", subagent = event.parentToolUseId != null)))
        } ?: Translation.Ignored
    }

    private fun translateUser(event: ClaudeEvent.User): Translation {
        val out =
            event.message.content.filterIsInstance<UserContentBlock.ToolResult>().map {
                LlmEvent.ToolResult(
                    it.toolUseId,
                    it.content,
                )
            }
        return if (out.isEmpty()) Translation.Ignored else Translation.Events(out)
    }

    private fun translateResult(
        r: ClaudeEvent.Result,
        costUsd: Double?,
    ): LlmEvent.TurnEnd =
        LlmEvent.TurnEnd(
            status =
                when (r.subtype) {
                    // `is_error` is the CLI's own verdict that a `success` subtype ended on an
                    // error (a refusal, a missing deferred tool, a terminal API error). See ADR-0070.
                    "success" -> if (r.isError) TurnStatus.Failed(isErrorReason(r)) else TurnStatus.Success

                    "error_max_turns" -> TurnStatus.Stopped(r.subtype)

                    else -> TurnStatus.Failed(r.subtype.ifBlank { "<missing>" })
                },
            usage =
                LlmUsage(
                    input = r.usage.inputTokens.toLong(),
                    output = r.usage.outputTokens.toLong(),
                    cacheRead = r.usage.cacheReadInputTokens.toLong(),
                    cacheWrite = r.usage.cacheCreationInputTokens.toLong(),
                ),
            costUsd = costUsd,
            model =
                r.modelUsage
                    ?.keys
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?.sorted()
                    ?.joinToString("+"),
            durationMs = r.durationMs,
            durationApiMs = r.durationApiMs,
            numTurns = r.numTurns,
            resultText = r.result,
        )

    /**
     * The reason for an `is_error` turn, from the CLI's own diagnosis fields only. The `result`
     * text is an error string the CLI may echo from an API response, so it never enters the
     * reason, the step error or a log line (§12 rule 7); the fields are CLI-written, so the
     * reason is stripped of control characters and capped like any stored one. See ADR-0047.
     */
    private fun isErrorReason(r: ClaudeEvent.Result): String {
        val details =
            listOfNotNull(
                r.stopReason.detail("stop_reason"),
                r.terminalReason.detail("terminal_reason"),
                r.apiErrorStatus.detail("api_error_status"),
            )
        val reason = if (details.isEmpty()) IS_ERROR else "$IS_ERROR (${details.joinToString(", ")})"
        return PreStartFailureReason.sanitize(reason)
    }

    /** `key=value` for a field the CLI wrote as a JSON primitive; any other shape names no value. */
    private fun JsonElement?.detail(key: String): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "$key=$it" }

    private inline fun <reified T> decode(
        line: JsonObject,
        then: (T) -> Translation,
    ): Translation =
        try {
            then(LlmOutputJson.decodeFromJsonElement<T>(line))
        } catch (e: SerializationException) {
            Translation.Malformed(e.message ?: "decode failed")
        } catch (e: IllegalArgumentException) {
            Translation.Malformed(e.message ?: "decode failed")
        }

    /** Strips `total_cost_usd` before the typed decode, so a value it cannot parse (a string, `NaN`) never fails the whole event. */
    private fun JsonObject.withoutCostField(): JsonObject = if (COST_FIELD in this) JsonObject(filterKeys { it != COST_FIELD }) else this

    private fun JsonObject.costUsd(): Double? =
        (this[COST_FIELD] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private companion object {
        const val COST_FIELD = "total_cost_usd"
        const val IS_ERROR = "is_error"
    }
}
