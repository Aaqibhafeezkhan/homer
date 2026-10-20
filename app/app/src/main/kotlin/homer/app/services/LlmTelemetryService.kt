package homer.app.services

import homer.app.executors.llm.LlmStepInfo
import homer.app.executors.llm.LlmStepTelemetry
import homer.app.infra.LlmTurnCosts
import homer.app.infra.SecretRedactor
import homer.app.infra.TelemetryService
import homer.app.infra.TranslatedStream
import homer.app.models.FALLBACK_REASON_NO_RESULT_EVENT
import homer.app.models.LlmEvent
import homer.app.models.LlmEventJson
import homer.app.models.LlmUsage
import homer.app.models.TurnStatus
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.Logger
import java.time.Instant
import java.util.UUID

/**
 * Builds OpenTelemetry spans (exported to Langfuse) from a harness-neutral [TranslatedStream]
 * of [LlmEvent]s. Nothing here reads a harness's own wire dialect — that translation already
 * happened in the adapter ([homer.app.executors.llm.ClaudeHarness],
 * [homer.app.executors.llm.CodexHarness]). See ADR-0070.
 */
class LlmTelemetryService(
    private val telemetryService: TelemetryService,
    private val logger: Logger,
) : LlmStepTelemetry {
    override val tracer: Tracer get() = telemetryService.tracer
    private val spanEmitter = LlmSpanEmitter({ telemetryService.tracer }, logger)

    override suspend fun process(
        stream: TranslatedStream,
        prompt: String,
        parentSpan: Span,
        redactor: SecretRedactor,
        // A caller with no step keeps the historical span name; the one production
        // caller is `LlmHarnessExecutor`, pinned by its own test.
        step: LlmStepInfo,
        // Split across the attempt's turn ends by [LlmTurnCosts.allocate].
        resolvedCostUsd: Double?,
    ) {
        try {
            if (stream.events.isEmpty() && stream.raw.isEmpty()) {
                logger.warn("LLM output is empty, skipping telemetry")
                return
            }
            emitTelemetry(stream, prompt, parentSpan, redactor, step, resolvedCostUsd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            parentSpan.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
            parentSpan.recordException(e)
            logger.error("Failed to process LLM telemetry", e)
        }
    }

    /** The spans and logs of a non-empty [stream]; see [process] for the parameters. */
    private suspend fun emitTelemetry(
        stream: TranslatedStream,
        prompt: String,
        parentSpan: Span,
        redactor: SecretRedactor,
        step: LlmStepInfo,
        resolvedCostUsd: Double?,
    ) {
        // [improper_input_validation / CWE-20] the adapter's preview lines are
        // agent-controlled stdout; redact before they reach app.log. See ADR-0061.
        stream.malformedPreview.forEach {
            logger.warn("Adapter flagged an LLM event as malformed (may indicate a CLI schema change); raw: {}", redactor.redact(it))
        }
        val spans = linkedMapOf<String, LlmSpanData>()
        var completionStart = Instant.now()
        var seen: SeenModel? = null
        // Real Claude streams emit a `system/hook_*` line carrying only a session id
        // BEFORE `init`, and a model-less one again off the result line — both become
        // model-less SessionStarts here. Parent-span metadata and the INFO log come from
        // the FIRST SessionStart that carries a model, exactly once; a model-less one
        // never sets metadata, never logs, and never blocks a later one from doing so.
        var sessionStartSeen = false
        val turnEnds = stream.events.filterIsInstance<LlmEvent.TurnEnd>()
        val turnCosts = LlmTurnCosts.allocate(turnEnds, resolvedCostUsd)
        var turnIndex = 0
        logger.info("Processing ${stream.events.size} LLM events for Langfuse")
        for (event in stream.events) {
            completionStart = completionStart.plusSeconds(1)
            when (event) {
                is LlmEvent.SessionStart -> {
                    sessionStartSeen = onSessionStart(event, sessionStartSeen, parentSpan)
                    seen = seenFromSession(seen, event)
                }

                is LlmEvent.AssistantMessage -> {
                    seen = onAssistantMessage(event, seen, spans, completionStart)
                }

                is LlmEvent.ToolCall -> {
                    spans[event.id] = toolCallSpan(event, seen, completionStart)
                    logger.info("Tool called: id={}, name={}", event.id, event.name)
                }

                is LlmEvent.ToolResult -> {
                    spans[event.id]?.let { spans[event.id] = it.copy(output = event.output) }
                }

                is LlmEvent.TurnEnd -> {
                    handleTurnEnd(event, seen, spans, parentSpan, prompt, completionStart, redactor, step, turnCosts[turnIndex++])
                }

                is LlmEvent.TransientError -> {
                    parentSpan.addEvent("llm.transient_error")
                    logger.warn("LLM transient error: {}", redactor.redact(event.message))
                }
            }
        }
        parentSpan.setAttribute("telemetry.llm.ignored_events", stream.ignored)
        parentSpan.setAttribute("telemetry.llm.malformed_events", stream.malformed)
        // A resolved charge with no turn end to carry it still gets a generation.
        resolvedCostUsd?.takeIf { turnEnds.isEmpty() }?.let { unattributedCost ->
            emitNoResultGeneration(step, prompt, seen, unattributedCost, parentSpan, redactor)
        }
        spanEmitter.emitGenerationSpans(spans, parentSpan, redactor)
    }

    /** The model seen so far, else the one [event] names — the first SessionStart with a model wins. */
    private fun seenFromSession(
        seen: SeenModel?,
        event: LlmEvent.SessionStart,
    ): SeenModel? = seen ?: event.model?.let { SeenModel(it, MODEL_SOURCE_SESSION) }

    /**
     * Sets the parent-span model metadata and logs the session from the first [event] that
     * names a model; returns whether that has now happened.
     */
    private fun onSessionStart(
        event: LlmEvent.SessionStart,
        sessionStartSeen: Boolean,
        parentSpan: Span,
    ): Boolean {
        if (!sessionStartSeen && event.model != null) {
            parentSpan.setAttribute("metadata.model", event.model)
            parentSpan.setAttribute("metadata.tools", event.tools.toString())
            logger.info(
                "LLM session: sessionId={}, model={}, tools={}",
                event.sessionId ?: "<none>",
                event.model,
                event.tools.size,
            )
            return true
        }
        return sessionStartSeen
    }

    /**
     * Adds [event]'s "Message" span and returns the run's model: [seen] if one is already
     * known, else the model this top-level message names (a subagent's never counts).
     */
    private fun onAssistantMessage(
        event: LlmEvent.AssistantMessage,
        seen: SeenModel?,
        spans: MutableMap<String, LlmSpanData>,
        completionStart: Instant,
    ): SeenModel? {
        // A thinking-only message carries no text; it must not create an
        // empty "Message" span, but it can still be the first to name a model.
        if (event.text.isNotBlank()) {
            spans[UUID.randomUUID().toString()] = messageSpan(event, seen, completionStart)
        }
        if (seen != null || event.subagent) return seen
        return event.model?.takeIf { it.isNotBlank() }?.let { SeenModel(it, MODEL_SOURCE_ASSISTANT) }
    }

    private fun toolCallSpan(
        event: LlmEvent.ToolCall,
        seen: SeenModel?,
        completionStart: Instant,
    ) = LlmSpanData(
        name = event.name,
        model = seen?.name ?: UNKNOWN_MODEL,
        completionStartTime = completionStart,
        input = event.input,
        usageDetails = emptyUsageDetails(),
    )

    private fun messageSpan(
        event: LlmEvent.AssistantMessage,
        seen: SeenModel?,
        completionStart: Instant,
    ) = LlmSpanData(
        name = "Message",
        model = seen?.name ?: event.model ?: UNKNOWN_MODEL,
        completionStartTime = completionStart,
        output = JsonPrimitive(event.text),
        usageDetails = emptyUsageDetails(),
    )

    /** A CLI killed before its result line wrote no turn end, yet the ledger charged the attempt. See ADR-0074. */
    private fun emitNoResultGeneration(
        step: LlmStepInfo,
        prompt: String,
        seen: SeenModel?,
        resolvedCostUsd: Double,
        parentSpan: Span,
        redactor: SecretRedactor,
    ) {
        val now = Instant.now()
        val model = resolveModel(seen, turnEndModel = null, step)
        spanEmitter.emitGeneration(
            LlmGeneration(generationName(step), prompt, metadata = null, model, LlmUsage(), resolvedCostUsd, now, now),
            buildJsonObject { put("reason", FALLBACK_REASON_NO_RESULT_EVENT) },
            parentSpan,
            redactor,
            errorReason = FALLBACK_REASON_NO_RESULT_EVENT,
        )
        logger.error("LLM execution ended without a result event: cost={}", resolvedCostUsd)
    }

    private fun handleTurnEnd(
        event: LlmEvent.TurnEnd,
        seen: SeenModel?,
        spans: MutableMap<String, LlmSpanData>,
        parentSpan: Span,
        prompt: String,
        completionStart: Instant,
        redactor: SecretRedactor,
        step: LlmStepInfo,
        cost: Double?,
    ) {
        val name = generationName(step)
        val resolved = resolveModel(seen, event.model, step)
        val usage = event.usage.nonNegative()
        val metadata = redactor.redact(LlmEventJson.encodeToString(LlmEvent.serializer(), event.copy(usage = usage)))
        val endTime = Instant.now()
        val startTime = event.durationMs?.let { endTime.minusMillis(it) } ?: endTime

        val generation = LlmGeneration(name, prompt, metadata, resolved, usage, cost, startTime, endTime)

        if (event.status == TurnStatus.Success) {
            spanEmitter.emitGeneration(generation, successOutput(event, name, resolved.name, usage, cost), parentSpan, redactor)

            logger.info(
                """
                LLM execution completed:
                - Model: ${resolved.name}
                - Duration: ${event.durationMs}ms (API: ${event.durationApiMs}ms)
                - Turns: ${event.numTurns}
                - Tokens: input=${usage.input}, output=${usage.output}, cache_read=${usage.cacheRead}
                - Cost: ${cost ?: "unknown"}
                """.trimIndent(),
            )
        } else {
            val reason = failureReason(event.status)
            if (cost != null) {
                // Priced only as the last turn end of an attempt with no successful one. See ADR-0074.
                val output = buildJsonObject { put("reason", reason) }
                spanEmitter.emitGeneration(generation, output, parentSpan, redactor, reason)
            } else {
                spans[UUID.randomUUID().toString()] =
                    LlmSpanData(
                        name = name,
                        model = resolved.name,
                        completionStartTime = completionStart,
                        usageDetails = emptyUsageDetails(),
                        metadata = buildJsonObject { put("reason", reason) },
                    )
            }
            logger.error("LLM execution failed: reason={}", redactor.redact(reason))
        }
    }

    /** The output a successful turn end's generation carries: its run summary under `metadata`. */
    private fun successOutput(
        event: LlmEvent.TurnEnd,
        name: String,
        model: String,
        usage: LlmUsage,
        cost: Double?,
    ): JsonObject =
        buildJsonObject {
            put(
                "metadata",
                buildJsonObject {
                    put("name", name)
                    put("model", model)
                    put("input", usage.input)
                    put("output", usage.output)
                    put("cache_read_input_tokens", usage.cacheRead)
                    put("duration_ms", event.durationMs ?: 0)
                    put("duration_api_ms", event.durationApiMs ?: 0)
                    put("num_turns", event.numTurns ?: 0)
                    put("result", event.resultText ?: "")
                    put("total_cost", cost)
                },
            )
        }

    /** The reason a non-successful turn end carries. */
    private fun failureReason(status: TurnStatus): String =
        when (status) {
            is TurnStatus.Failed -> status.reason
            is TurnStatus.Stopped -> status.reason
            TurnStatus.Success -> error("unreachable: a successful turn end has no failure reason")
        }

    /**
     * The generation observation is named after the step that produced it — `implement`,
     * `commit-message` — so the cost of one `!<claude>`/`!<codex>` step can be told from
     * another's across the runs of one agent. Falls back to the historical name for a
     * caller with no step.
     */
    private fun generationName(step: LlmStepInfo): String = step.label?.takeIf { it.isNotBlank() } ?: DEFAULT_GENERATION_NAME

    /**
     * Most authoritative first: what the harness reported (session start, then the first
     * main-agent message), then the turn-end event's own model accounting, then what the
     * step asked for. Losing any single event therefore degrades the answer instead of
     * erasing it.
     *
     * The step's configured model is deliberately last — it is what was requested, not what
     * ran: it may be an alias (`sonnet`, `opusplan`), and a `fallbackModel` may have served
     * the run instead.
     */
    private fun resolveModel(
        seen: SeenModel?,
        turnEndModel: String?,
        step: LlmStepInfo,
    ): SeenModel =
        seen
            ?: turnEndModel?.let { SeenModel(it, MODEL_SOURCE_TURN_END) }
            ?: step.configuredModel?.takeIf { it.isNotBlank() }?.let { SeenModel(it, MODEL_SOURCE_CONFIGURED) }
            ?: SeenModel(UNKNOWN_MODEL, MODEL_SOURCE_UNKNOWN)

    companion object {
        private const val DEFAULT_GENERATION_NAME = "Result"
        private const val UNKNOWN_MODEL = "unknown"
        private const val MODEL_SOURCE_SESSION = "session_start"
        private const val MODEL_SOURCE_ASSISTANT = "assistant"
        private const val MODEL_SOURCE_TURN_END = "turn_end"
        private const val MODEL_SOURCE_CONFIGURED = "configured"
        private const val MODEL_SOURCE_UNKNOWN = "unknown"
    }
}

/** The model reported for the run, and which rung of [LlmTelemetryService.resolveModel] reported it. */
internal data class SeenModel(
    val name: String,
    val source: String,
)
