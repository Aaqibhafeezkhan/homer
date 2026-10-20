package homer.app.services

import homer.app.infra.SecretRedactor
import homer.app.models.LlmUsage
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.Logger
import java.time.Instant

/**
 * Writes the OpenTelemetry spans [LlmTelemetryService] derives from one harness stream:
 * the priced generation observation and the unpriced per-message and per-tool children.
 * Every attribute that can carry agent output goes through the run's [SecretRedactor].
 */
internal class LlmSpanEmitter(
    /** Read at every emit, as the service's own `tracer` always was. */
    private val tracer: () -> Tracer,
    private val logger: Logger,
) {
    /** One generation observation — the only kind Langfuse prices. A non-null [errorReason] marks it ERROR. */
    fun emitGeneration(
        generation: LlmGeneration,
        output: JsonElement,
        parentSpan: Span,
        redactor: SecretRedactor,
        errorReason: String? = null,
    ) {
        val (name, prompt, metadata, resolved, usage, cost, startTime, endTime) = generation
        val resultSpan =
            tracer()
                .spanBuilder(name)
                .setParent(Context.current().with(parentSpan))
                .setStartTimestamp(startTime)
                .startSpan()

        resultSpan.setAttribute("name", name)
        resultSpan.setAttribute("prompt", redactor.redact(prompt))
        metadata?.let { resultSpan.setAttribute("metadata", it) }
        resultSpan.setAttribute("model", resolved.name)
        resultSpan.setAttribute("usage_details.input_tokens", usage.input)
        resultSpan.setAttribute("usage_details.cache_creation_input_tokens", usage.cacheWrite)
        resultSpan.setAttribute("usage_details.cache_read_input_tokens", usage.cacheRead)
        resultSpan.setAttribute("usage_details.output_tokens", usage.output)
        if (usage.reasoning > 0) {
            resultSpan.setAttribute("usage_details.reasoning_tokens", usage.reasoning)
        }

        // Langfuse's OTLP mapper reads usage and cost ONLY from these attributes (JSON
        // strings); the flat `usage_details.*` above are invisible to it. The flat ones
        // stay for debugging and existing UI metadata filters.
        resultSpan.setAttribute("langfuse.observation.type", "generation")
        resultSpan.setAttribute("langfuse.observation.model.name", resolved.name)
        // Which rung of the ladder answered. A drift that costs the harness-reported
        // model is then visible in the data rather than only in a WARN nobody reads.
        resultSpan.setAttribute("telemetry.llm.model.source", resolved.source)
        resultSpan.setAttribute(
            "langfuse.observation.usage_details",
            buildJsonObject {
                put("input", usage.input)
                put("output", usage.output)
                put("cache_creation_input_tokens", usage.cacheWrite)
                put("cache_read_input_tokens", usage.cacheRead)
                if (usage.reasoning > 0) put("reasoning", usage.reasoning)
            }.toString(),
        )
        // Omitted rather than priced at zero when neither the resolver nor the harness
        // reports a cost: a zero cost_details used to read as "this run was free."
        if (cost != null) {
            resultSpan.setAttribute(
                "langfuse.observation.cost_details",
                buildJsonObject { put("total", cost) }.toString(),
            )
        }
        resultSpan.setAttribute("output", redactor.redact(output.toString()))
        if (errorReason != null) {
            // [information_disclosure / CWE-532] the reason is harness stdout, and a status description is exported too.
            resultSpan.setStatus(StatusCode.ERROR, redactor.redact(errorReason))
        } else {
            resultSpan.setStatus(StatusCode.OK)
        }
        resultSpan.end(endTime)
    }

    suspend fun emitGenerationSpans(
        spans: Map<String, LlmSpanData>,
        parentSpan: Span,
        redactor: SecretRedactor,
    ) {
        spans.values.forEachIndexed { index, spanData ->
            val generationSpan =
                tracer()
                    .spanBuilder("$index. ${spanData.name}")
                    .setParent(Context.current().with(parentSpan))
                    .setStartTimestamp(spanData.completionStartTime)
                    .startSpan()

            logger.info("Adding ${spanData.name}")

            generationSpan.setAttribute("name", spanData.name)
            generationSpan.setAttribute("model", spanData.model)
            // A GENERATION gets priced by Langfuse from its own model table, so the per-message and
            // per-tool children must stay SPANs — otherwise the run is charged twice, here and on
            // `Result`. Invariant: exactly one priced observation per run.
            generationSpan.setAttribute("langfuse.observation.type", "span")
            spanData.input?.let { generationSpan.setAttribute("input", redactor.redact(it.toString())) }
            spanData.output?.let { generationSpan.setAttribute("output", redactor.redact(it.toString())) }
            generationSpan.setAttribute("usage_details.input_tokens", spanData.usageDetails["input_tokens"] as Long)
            generationSpan.setAttribute(
                "usage_details.cache_creation_input_tokens",
                spanData.usageDetails["cache_creation_input_tokens"] as Long,
            )
            generationSpan.setAttribute("usage_details.cache_read_input_tokens", spanData.usageDetails["cache_read_input_tokens"] as Long)
            generationSpan.setAttribute("usage_details.output_tokens", spanData.usageDetails["output_tokens"] as Long)
            spanData.metadata?.let { generationSpan.setAttribute("metadata", redactor.redact(it.toString())) }

            generationSpan.setStatus(StatusCode.OK)
            generationSpan.end(spanData.completionStartTime.plusMillis(SPAN_END_OFFSET_MS))

            // Preserve wall-clock ordering for Langfuse (sorts spans by receive order within a batch).
            delay(SPAN_ORDERING_DELAY_MS)
        }
    }

    private companion object {
        const val SPAN_END_OFFSET_MS = 100L
        const val SPAN_ORDERING_DELAY_MS = 50L
    }
}

/** What one generation observation reports: its [name], what ran ([model]) at what usage and cost, and when. */
internal data class LlmGeneration(
    val name: String,
    val prompt: String,
    val metadata: String?,
    val model: SeenModel,
    val usage: LlmUsage,
    val cost: Double?,
    val startTime: Instant,
    val endTime: Instant,
)

internal fun emptyUsageDetails() =
    mapOf(
        "input_tokens" to 0L,
        "cache_creation_input_tokens" to 0L,
        "cache_read_input_tokens" to 0L,
        "output_tokens" to 0L,
    )

internal data class LlmSpanData(
    val name: String,
    val model: String,
    val completionStartTime: Instant,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val usageDetails: Map<String, Long> = emptyMap(),
    val metadata: JsonElement? = null,
)
