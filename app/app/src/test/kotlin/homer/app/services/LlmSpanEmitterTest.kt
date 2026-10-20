package homer.app.services

import homer.app.infra.SecretRedactor
import homer.app.models.LlmUsage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant

class LlmSpanEmitterTest {
    private val span: Span =
        mockk(relaxed = true) {
            every { setAttribute(any<String>(), any<String>()) } returns this
            every { setAttribute(any<String>(), any<Long>()) } returns this
            every { setStatus(any()) } returns this
            every { setStatus(any(), any<String>()) } returns this
            every { end(any<Instant>()) } just Runs
        }
    private val builder: SpanBuilder =
        mockk {
            every { setParent(any()) } returns this
            every { setStartTimestamp(any<Instant>()) } returns this
            every { startSpan() } returns span
        }
    private val tracer: Tracer = mockk { every { spanBuilder(any()) } returns builder }
    private val emitter = LlmSpanEmitter({ tracer }, mockk(relaxed = true))
    private val redactor = SecretRedactor.from(mapOf("TOKEN" to "s3cret"), setOf("TOKEN"))

    @Test
    fun `a generation is priced, redacted and marked ERROR when it carries a reason`() {
        val now = Instant.now()
        emitter.emitGeneration(
            LlmGeneration(
                "implement",
                "prompt with s3cret",
                null,
                SeenModel("claude-x", "session_start"),
                LlmUsage(input = 3, output = 4),
                0.5,
                now,
                now,
            ),
            JsonPrimitive("out s3cret"),
            mockk(relaxed = true),
            redactor,
            errorReason = "failed on s3cret",
        )
        verify { span.setAttribute("langfuse.observation.type", "generation") }
        verify { span.setAttribute("langfuse.observation.cost_details", """{"total":0.5}""") }
        verify { span.setAttribute("prompt", "prompt with ***") }
        verify { span.setStatus(StatusCode.ERROR, "failed on ***") }
    }

    @Test
    fun `a generation without a cost carries no cost details`() {
        val now = Instant.now()
        val generation = LlmGeneration("Result", "p", null, SeenModel("m", "configured"), LlmUsage(), null, now, now)
        emitter.emitGeneration(generation, JsonPrimitive("o"), mockk(relaxed = true), redactor)
        verify(exactly = 0) { span.setAttribute("langfuse.observation.cost_details", any<String>()) }
        verify { span.setStatus(StatusCode.OK) }
    }

    @Test
    fun `child spans stay unpriced spans with redacted output`() =
        runBlocking {
            val child = LlmSpanData("Message", "m", Instant.now(), output = JsonPrimitive("s3cret"), usageDetails = emptyUsageDetails())
            emitter.emitGenerationSpans(mapOf("id" to child), mockk(relaxed = true), redactor)
            verify { tracer.spanBuilder("0. Message") }
            verify { span.setAttribute("langfuse.observation.type", "span") }
            verify { span.setAttribute("output", "\"***\"") }
        }
}
