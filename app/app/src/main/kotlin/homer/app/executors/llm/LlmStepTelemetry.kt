package homer.app.executors.llm

import homer.app.infra.SecretRedactor
import homer.app.infra.TranslatedStream
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer

/**
 * What [LlmHarnessExecutor] hands off once an attempt's stream is read: the step's spans, exported to
 * Langfuse. Implemented by `LlmTelemetryService`. See ADR-0070 and ADR-0083.
 */
interface LlmStepTelemetry {
    /** Builds the step's own spans — e.g. the `llm_cost_settlement` child span of a background settlement (ADR-0089). */
    val tracer: Tracer

    /**
     * Emits the spans of [stream] under [parentSpan]. [redactor] masks the agent's declared `secret`
     * input values in span attributes (prompt, tool I/O, result) before they egress; [step] names the
     * generation; [resolvedCostUsd] is the cost the executor actually charged, split across the
     * attempt's turn ends — null keeps the events' own cost (ADR-0061).
     */
    suspend fun process(
        stream: TranslatedStream,
        prompt: String,
        parentSpan: Span,
        redactor: SecretRedactor = SecretRedactor.NONE,
        step: LlmStepInfo = LlmStepInfo(),
        resolvedCostUsd: Double? = null,
    )
}

/**
 * What the step DECLARED, as opposed to what the harness reported. [label] names the run's
 * generation observation, so a reader can tell one `!<claude>`/`!<codex>` step of an agent
 * from another; [configuredModel] is the last rung of the telemetry's model ladder. Both are
 * absent for a caller with no step.
 */
data class LlmStepInfo(
    val label: String? = null,
    val configuredModel: String? = null,
)
