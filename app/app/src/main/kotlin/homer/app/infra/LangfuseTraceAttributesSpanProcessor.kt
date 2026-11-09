package homer.app.infra

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * Copies [LANGFUSE_TRACE_NAME] and [LANGFUSE_TRACE_TAGS] from a span's parent onto the span as it
 * starts, so every observation of a run carries the run's trace name and tags — command steps,
 * LLM generations and `!<agent>` hops included. Langfuse v4 has no separate trace record and reads
 * both off each observation, so without this only the root span is findable by name or tag.
 *
 * Registered **before** the `BatchSpanProcessor`, and it is the only processor that needs
 * `onStart`. See ADR-0050.
 *
 * Constraints, each a denial path:
 * - **Exactly the two keys are copied.** Nothing else on the parent — inputs, env, `request.source`
 *   extras — rides along, so a parent attribute can never become an egress Homer did not choose.
 * - **The parent's values win.** The child's own attributes are never read, so an agent or a step
 *   cannot retitle the trace or rewrite its tags from below.
 * - **A parent that carries neither key passes neither on**, and a parent that is not a recording
 *   SDK span (a `telemetry.export: OFF` wrapper, an invalid or remote context) is no parent at all.
 * - **It never throws.** A span-start hook that failed would fail the run that was starting; an
 *   unreadable parent just means this span carries no trace name or tags.
 */
internal class LangfuseTraceAttributesSpanProcessor : SpanProcessor {
    override fun isStartRequired(): Boolean = true

    override fun isEndRequired(): Boolean = false

    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        try {
            val parent = Span.fromContext(parentContext) as? ReadableSpan ?: return
            parent.getAttribute(LANGFUSE_TRACE_NAME)?.let { span.setAttribute(LANGFUSE_TRACE_NAME, it) }
            parent.getAttribute(LANGFUSE_TRACE_TAGS)?.let { span.setAttribute(LANGFUSE_TRACE_TAGS, it) }
        } catch (_: Exception) {
            // Swallowed on purpose: this runs inside the SDK's span-start path, so a throw here
            // would surface in whichever caller happened to start a span — a run, a request.
        }
    }

    override fun onEnd(span: ReadableSpan) = Unit

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
