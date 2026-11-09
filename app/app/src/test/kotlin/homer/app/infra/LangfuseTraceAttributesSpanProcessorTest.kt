package homer.app.infra

import homer.app.fixtures.AgentFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceId
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parent-to-child copy that puts the run's `langfuse.trace.name` / `langfuse.trace.tags` on
 * every observation — the half of Langfuse v4 support that does not live in the start paths.
 *
 * These run against a real [SdkTracerProvider] because what is being tested is the SDK's own
 * parent/child wiring (an ended parent still in the context, a wrapped non-recording parent),
 * which a mocked span would assert away. See ADR-0050.
 */
class LangfuseTraceAttributesSpanProcessorTest {
    private val agent = AgentFixtures.simpleAgent(name = "triage-decision")
    private val exporter = CollectingExporter()

    private fun provider(withProcessor: Boolean): SdkTracerProvider =
        SdkTracerProvider
            .builder()
            .apply { if (withProcessor) addSpanProcessor(LangfuseTraceAttributesSpanProcessor()) }
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()

    private fun SdkTracerProvider.span(
        name: String,
        parent: Span? = null,
    ): Span =
        get("test")
            .spanBuilder(name)
            .apply { parent?.let { setParent(Context.current().with(it)) } }
            .startSpan()

    private fun exported(name: String): SpanData = exporter.spans.single { it.name == name }

    @Test
    fun `a child span inherits the root's trace name and tags`() {
        val provider = provider(withProcessor = true)
        val root = provider.span("agent_request")
        root.stampLangfuseTrace(agent, listOf("mr:4211"))

        val child = provider.span("command_execution_1", parent = root)
        child.end()
        root.end()

        val data = exported("command_execution_1")
        assertEquals("triage-decision", data.attributes.get(LANGFUSE_TRACE_NAME))
        assertEquals(listOf("mr:4211"), data.attributes.get(LANGFUSE_TRACE_TAGS))
    }

    @Test
    fun `a parent carrying neither key gives the child neither`() {
        val provider = provider(withProcessor = true)
        val root = provider.span("agent_request")
        val child = provider.span("command_execution_1", parent = root)
        child.end()
        root.end()

        val data = exported("command_execution_1")
        assertEquals(null, data.attributes.get(LANGFUSE_TRACE_NAME))
        assertEquals(null, data.attributes.get(LANGFUSE_TRACE_TAGS))
    }

    @Test
    fun `no other parent attribute is copied`() {
        // The over-copy denial: the root carries process and request metadata, and a copy that
        // took "the parent's attributes" rather than these two keys would export all of it.
        val provider = provider(withProcessor = true)
        val root = provider.span("agent_request")
        root.setAttribute("process.id", 7L)
        root.setAttribute("request.source", "http")
        root.setAttribute("langfuse.observation.input", "a prompt")
        root.stampLangfuseTrace(agent, listOf("mr:4211"))

        val child = provider.span("command_execution_1", parent = root)
        child.end()
        root.end()

        val data = exported("command_execution_1")
        assertEquals(null, data.attributes.get(AttributeKey.longKey("process.id")))
        assertEquals(null, data.attributes.get(AttributeKey.stringKey("request.source")))
        assertEquals(null, data.attributes.get(AttributeKey.stringKey("langfuse.observation.input")))
        assertEquals("triage-decision", data.attributes.get(LANGFUSE_TRACE_NAME))
    }

    @Test
    fun `a span started after its parent ended still inherits both keys`() {
        // The settlement span is written once a command has already ended, so the parent in the
        // context is a finished span. If reading it stopped working at `end()`, exactly these
        // late observations would drop out of a v4 filter.
        val provider = provider(withProcessor = true)
        val root = provider.span("agent_request")
        root.stampLangfuseTrace(agent, listOf("mr:4211"))
        root.end()

        val late = provider.span("llm_cost_settlement", parent = root)
        late.end()

        val data = exported("llm_cost_settlement")
        assertEquals("triage-decision", data.attributes.get(LANGFUSE_TRACE_NAME))
        assertEquals(listOf("mr:4211"), data.attributes.get(LANGFUSE_TRACE_TAGS))
    }

    @Test
    fun `a wrapped non-recording parent copies nothing`() {
        // What `telemetry.export: OFF` hands the run: a valid context that is not an SDK span.
        val provider = provider(withProcessor = true)
        val wrapped =
            Span.wrap(
                SpanContext.create(
                    TraceId.fromLongs(1L, 2L),
                    SpanId.fromLong(3L),
                    TraceFlags.getSampled(),
                    TraceState.getDefault(),
                ),
            )

        val child = provider.span("agent_execution", parent = wrapped)
        child.end()

        val data = exported("agent_execution")
        assertEquals(null, data.attributes.get(LANGFUSE_TRACE_NAME))
        assertEquals(null, data.attributes.get(LANGFUSE_TRACE_TAGS))
    }

    @Test
    fun `a child's own write changes neither the root nor its siblings`() {
        // The override denial: a step that stamps its own trace name must not retitle the run it
        // belongs to, nor the observations beside it.
        val provider = provider(withProcessor = true)
        val root = provider.span("agent_request")
        root.stampLangfuseTrace(agent, listOf("mr:4211"))

        val first = provider.span("command_execution_1", parent = root)
        first.stampLangfuseTrace(AgentFixtures.simpleAgent(name = "impostor"), listOf("forged:1"))
        first.end()

        val second = provider.span("command_execution_2", parent = root)
        second.end()
        root.end()

        assertEquals("triage-decision", exported("agent_request").attributes.get(LANGFUSE_TRACE_NAME))
        assertEquals(listOf("mr:4211"), exported("command_execution_2").attributes.get(LANGFUSE_TRACE_TAGS))
    }

    @Test
    fun `an unreadable parent neither fails the span start nor blocks the export`() {
        // Availability: this runs inside the SDK's span-start path, so a throw here would surface
        // in whichever caller happened to start a span — a run, a request.
        val provider = provider(withProcessor = false)
        val child = provider.span("command_execution_1")
        val parent =
            mockk<ReadWriteSpan>(relaxed = true) {
                every { getAttribute(any<AttributeKey<String>>()) } throws IllegalStateException("unreadable")
            }

        LangfuseTraceAttributesSpanProcessor().onStart(contextHolding(parent), child as ReadWriteSpan)

        // The parent really was reached through the context and really did throw — otherwise this
        // would pass by never entering the copy at all.
        verify { parent.getAttribute(LANGFUSE_TRACE_NAME) }
        child.end()
        val data = exported("command_execution_1")
        assertEquals(null, data.attributes.get(LANGFUSE_TRACE_NAME))
        assertEquals(null, data.attributes.get(LANGFUSE_TRACE_TAGS))
    }

    /**
     * A context whose span slot holds [parent].
     *
     * `Context.with(span)` stores the span through `Span.storeInContext`, which a mocked span
     * answers with a relaxed `Context` — a mocked parent never reaches `Span.fromContext` that
     * way. Stubbing the two calls is enough here: the real parent/child plumbing is what the
     * cases above exercise against an actual [SdkTracerProvider].
     */
    private fun contextHolding(parent: Span): Context {
        val context = mockk<Context>(relaxed = true)
        every { parent.storeInContext(any()) } returns context
        every { context.get(any<ContextKey<Span>>()) } returns parent
        return Context.current().with(parent)
    }

    @Test
    fun `a parent with no span at all is not an error`() {
        // The root span's own start: the ambient context carries no parent, and the hook runs.
        val processor = LangfuseTraceAttributesSpanProcessor()
        val child = mockk<ReadWriteSpan>(relaxed = true)

        processor.onStart(Context.root(), child)

        assertTrue(processor.isStartRequired())
        assertEquals(false, processor.isEndRequired())
    }

    @Test
    fun `shutdown and flush complete without touching the span pipeline`() {
        // The processor holds no state and no queue, so it must not delay or fail either.
        val processor = LangfuseTraceAttributesSpanProcessor()
        assertTrue(processor.shutdown().isSuccess)
        assertTrue(processor.forceFlush().isSuccess)
    }

    @Test
    fun `the child receives exactly the two copied keys`() {
        // Belt and braces for the over-copy denial: the hook writes those two attributes and
        // nothing else, whatever else the parent happens to carry.
        val provider = provider(withProcessor = true)
        val parent = provider.span("agent_request")
        parent.setAttribute("process.id", 7L)
        parent.stampLangfuseTrace(agent, listOf("mr:4211"))

        val child = provider.span("command_execution_1", parent = parent)
        child.end()
        parent.end()

        val attributes = exported("command_execution_1").attributes
        assertEquals(2, attributes.size(), "copied: ${attributes.asMap()}")
        assertEquals("triage-decision", attributes.get(LANGFUSE_TRACE_NAME))
        assertEquals(listOf("mr:4211"), attributes.get(LANGFUSE_TRACE_TAGS))
    }

    private class CollectingExporter : SpanExporter {
        val spans = mutableListOf<SpanData>()

        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            this.spans.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }
}
