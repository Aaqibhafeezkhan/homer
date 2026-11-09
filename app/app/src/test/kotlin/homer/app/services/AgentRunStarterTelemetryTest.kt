package homer.app.services

import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.TestFixtures
import homer.app.infra.LANGFUSE_TRACE_NAME
import homer.app.infra.LANGFUSE_TRACE_TAGS
import homer.app.infra.LangfuseTraceAttributesSpanProcessor
import homer.app.infra.TelemetryService
import homer.app.models.Agent
import homer.app.models.AgentTelemetry
import homer.app.models.Command
import homer.app.models.Param
import homer.app.models.ProcessCreationResult
import homer.app.models.Tags
import homer.app.models.TelemetryExport
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-agent OTel muting (`telemetry: OFF`).
 *
 * These run against a real [SdkTracerProvider] rather than a mocked tracer on
 * purpose: the whole mechanism is the SDK's default `ParentBased` sampler
 * dropping children of an unsampled parent. A mock would assert the code we
 * wrote, not the behaviour we depend on.
 */
class AgentRunStarterTelemetryTest {
    private class CollectingExporter : SpanExporter {
        val spans = mutableListOf<SpanData>()

        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            this.spans.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush() = CompletableResultCode.ofSuccess()

        override fun shutdown() = CompletableResultCode.ofSuccess()
    }

    private val exporter = CollectingExporter()
    private val tracerProvider: SdkTracerProvider =
        SdkTracerProvider
            .builder()
            // Same order as OtelTelemetryService: the copy runs on span start, ahead of export.
            .addSpanProcessor(LangfuseTraceAttributesSpanProcessor())
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()

    private val telemetry =
        object : TelemetryService {
            override val tracer: Tracer = tracerProvider.get("test")
        }

    private val processExecutor = mockk<ProcessExecutor>()
    private val processFactory = mockk<ProcessFactory>(relaxed = true)

    private fun agent(export: TelemetryExport) =
        Agent(
            name = "watcher",
            commands = listOf(Command.Cmd(cmd = listOf("echo", "hi"))),
            telemetry = AgentTelemetry(export = export),
        )

    /** The tagged agent the inheritance cases run, so the root has a tag list worth copying. */
    private fun taggedAgent(export: TelemetryExport = TelemetryExport.ALL) =
        agent(export).copy(
            inputs = listOf(Param(paramName = "mr", envName = "MR_IID")),
            tags = Tags(fromInputs = listOf("mr")),
        )

    private fun starter() =
        AgentRunStarter(
            serverId = "test-server",
            processExecutor = processExecutor,
            processFactory = processFactory,
            telemetryService = telemetry,
            costMeter = mockk(relaxed = true),
            maxInvocationDepth = 5,
            cooldownGate = mockk(relaxed = true),
            maintenanceGate = mockk(relaxed = true),
            processReads = mockk(relaxed = true),
        )

    /**
     * Stands in for ProcessExecutor: every span Homer creates for a run descends
     * from the context AgentRunStarter hands over, so one child here is enough to
     * show what happens to all of them.
     */
    private fun executorEmittingChildSpan() {
        coEvery { processExecutor.execute(any(), any()) } answers {
            val context = secondArg<Context>()
            telemetry.tracer
                .spanBuilder("agent_execution")
                .setParent(context)
                .startSpan()
                .end()
            ProcessCreationResult(processId = 1)
        }
    }

    @Test
    fun `telemetry ALL exports the run's root span and its descendants`() =
        runTest {
            executorEmittingChildSpan()

            starter().start(agent(TelemetryExport.ALL), emptyList(), "cron") { emptyMap() }

            assertEquals(listOf("agent_execution", "agent_request"), exporter.spans.map { it.name })
        }

    @Test
    fun `telemetry OFF exports nothing at all for the run`() =
        runTest {
            executorEmittingChildSpan()

            starter().start(agent(TelemetryExport.OFF), emptyList(), "cron") { emptyMap() }

            // Not just the root: the descendant is dropped too, which is the point —
            // a no-op tick of a once-a-minute agent must leave no trace behind.
            assertTrue(exporter.spans.isEmpty(), "exported: ${exporter.spans.map { it.name }}")
        }

    @Test
    fun `telemetry OFF still runs the agent`() =
        runTest {
            executorEmittingChildSpan()

            val processId = starter().start(agent(TelemetryExport.OFF), emptyList(), "cron") { emptyMap() }

            assertEquals(1, processId)
        }

    // --- Langfuse trace name and tags on every span of the run (ADR-0050) ---

    /**
     * The run's real span tree, one level deeper than [executorEmittingChildSpan]: the process
     * span, a command step under it and the LLM generation under that — the three levels Langfuse
     * v4 filters on and the ones a copy that only reached direct children would miss.
     */
    private fun executorEmittingNestedSpans() {
        coEvery { processExecutor.execute(any(), any()) } answers {
            val runContext = secondArg<Context>()
            val processSpan =
                telemetry.tracer
                    .spanBuilder("agent_execution")
                    .setParent(runContext)
                    .startSpan()
            val commandSpan =
                telemetry.tracer
                    .spanBuilder("command_execution_1")
                    .setParent(Context.current().with(processSpan))
                    .startSpan()
            telemetry.tracer
                .spanBuilder("review-diff")
                .setParent(Context.current().with(commandSpan))
                .startSpan()
                .end()
            commandSpan.end()
            processSpan.end()
            ProcessCreationResult(processId = 1)
        }
    }

    @Test
    fun `every span of the run carries the root's trace name and tags`() =
        runTest {
            executorEmittingNestedSpans()

            starter().start(taggedAgent(), emptyList(), "http") { mapOf("MR_IID" to "4211") }

            val byName = exporter.spans.associateBy { it.name }
            for (name in listOf("agent_request", "agent_execution", "command_execution_1", "review-diff")) {
                val data = byName.getValue(name)
                assertEquals("watcher", data.attributes.get(LANGFUSE_TRACE_NAME), "name missing on $name")
                assertEquals(
                    listOf("http", "mr:4211"),
                    data.attributes.get(LANGFUSE_TRACE_TAGS),
                    "tags missing on $name",
                )
            }
        }

    @Test
    fun `the root carries both attributes before the run starts`() =
        runBlocking {
            // Call order, not just presence: a child span copies them at its own start, so a
            // stamp that landed after `execute` would leave the run's first spans untagged.
            val span = TestFixtures.mockSpan()
            val processExecutor = mockk<ProcessExecutor>()
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 1)
            val starter =
                AgentRunStarter(
                    serverId = "test-server",
                    processExecutor = processExecutor,
                    processFactory = mockk(relaxed = true),
                    telemetryService = TestFixtures.mockTelemetryService(span),
                    costMeter = mockk(relaxed = true),
                    maxInvocationDepth = 5,
                    cooldownGate = mockk(relaxed = true),
                    maintenanceGate = mockk(relaxed = true),
                    processReads = mockk(relaxed = true),
                )

            starter.start(taggedAgent(), emptyList(), "http") { mapOf("MR_IID" to "4211") }

            coVerifyOrder {
                span.setAttribute(LANGFUSE_TRACE_NAME, "watcher")
                span.setAttribute(LANGFUSE_TRACE_TAGS, listOf("http", "mr:4211"))
                processExecutor.execute(any(), any())
            }
        }

    @Test
    fun `a muted run exports nothing even with the copy processor registered`() =
        runTest {
            executorEmittingNestedSpans()

            starter().start(taggedAgent(TelemetryExport.OFF), emptyList(), "http") { mapOf("MR_IID" to "4211") }

            assertTrue(exporter.spans.isEmpty(), "exported: ${exporter.spans.map { it.name }}")
        }
}
