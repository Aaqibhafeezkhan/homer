package homer.app.services

import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.TelemetryService
import homer.app.models.Agent
import homer.app.models.AgentTelemetry
import homer.app.models.Command
import homer.app.models.ProcessCreationResult
import homer.app.models.TelemetryExport
import io.mockk.coEvery
import io.mockk.mockk
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
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
}
