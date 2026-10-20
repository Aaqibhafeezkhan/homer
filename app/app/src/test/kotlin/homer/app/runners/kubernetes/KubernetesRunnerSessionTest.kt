package homer.app.runners.kubernetes

import homer.app.EnvConstants
import homer.app.infra.startAgentRootSpan
import homer.app.models.Agent
import homer.app.models.AgentTelemetry
import homer.app.models.Command
import homer.app.models.ExtraUploadSpec
import homer.app.models.TelemetryExport
import homer.app.runners.DownloadMode
import homer.app.runners.RemotePathTranslator
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KubernetesRunnerSessionTest {
    private fun session(
        sync: KubernetesWorkspaceSync = mockk(relaxed = true),
        extras: List<ExtraUploadSpec> = emptyList(),
        excludes: List<String> = emptyList(),
        api: CoreV1Api = mockk(relaxed = true),
        keepPodOnFailure: Boolean = false,
        tracer: Tracer? = null,
        parentContext: Context = Context.root(),
    ) = KubernetesRunnerSession(
        tracer = tracer,
        parentContext = parentContext,
        podName = "pod-1",
        namespace = "homer",
        api = api,
        shell = mockk(relaxed = true),
        keepPodOnFailure = keepPodOnFailure,
        keepPodTtlSeconds = 3600,
        runnerAlias = "k8s-box",
        sync = sync,
        translator =
            RemotePathTranslator(
                serverProjectRoot = "/local/p1",
                remoteProjectRoot = KubernetesRunnerSession.REMOTE_PROJECT_ROOT,
                remoteAgentDir = "${KubernetesRunnerSession.REMOTE_PROJECT_ROOT}/.homer/agent",
                apiUrl = null,
            ),
        initialEnv = mapOf(EnvConstants.WORK_DIR to "/local/p1/workdir"),
        syncExcludes = excludes,
        extraUploads = extras,
    )

    @Test
    fun `createWorkspace makes workdir and repoints env at the pod layout`() =
        runTest {
            val sync = mockk<KubernetesWorkspaceSync>(relaxed = true)
            val overrides =
                session(
                    sync,
                    extras =
                        listOf(
                            ExtraUploadSpec("/app/scripts", ".homer/scripts", env = "SCRIPTS_DIR"),
                            ExtraUploadSpec("/app/prompts", ".homer/shared-prompts"),
                        ),
                ).createWorkspace()
            verify { sync.mkdirs("/homer/workspace/workdir") }
            assertEquals("/homer/workspace/workdir", overrides[EnvConstants.WORK_DIR])
            assertEquals("/homer/workspace/.homer/scripts", overrides["SCRIPTS_DIR"])
            assertFalse(overrides.values.any { it.endsWith(".homer/shared-prompts") })
        }

    @Test
    fun `upload and download resolve relative paths against the pod projectRoot with excludes`() =
        runTest {
            val sync = mockk<KubernetesWorkspaceSync>(relaxed = true)
            val s = session(sync, excludes = listOf(".git"))
            s.upload(Path.of("/local/p1"), ".")
            s.upload(Path.of("/app/scripts"), ".homer/scripts")
            s.download("artifacts", Path.of("/local/p1/artifacts"), mode = DownloadMode.OVERLAY)
            // Project-root upload also omits the reserved workdir directory entry (its
            // contents still upload — kept workspaces need them retained between runs);
            // a non-root upload passes no omissions. See ADR-0054.
            verify { sync.upload(Path.of("/local/p1"), "/homer/workspace", listOf(".git"), setOf("workdir")) }
            verify { sync.upload(Path.of("/app/scripts"), "/homer/workspace/.homer/scripts", listOf(".git"), emptySet()) }
            verify { sync.download("/homer/workspace/artifacts", Path.of("/local/p1/artifacts"), mode = DownloadMode.OVERLAY) }
        }

    @Test
    fun `download threads MIRROR through to KubernetesWorkspaceSync`() =
        runTest {
            val sync = mockk<KubernetesWorkspaceSync>(relaxed = true)
            session(sync).download("workdir", Path.of("/local/p1/workdir"), mode = DownloadMode.MIRROR)
            verify { sync.download("/homer/workspace/workdir", Path.of("/local/p1/workdir"), mode = DownloadMode.MIRROR) }
        }

    @Test
    fun `markFailed with keepPodOnFailure keeps the pod via TTL patch instead of delete`() {
        // Real api over a short-circuited HTTP layer: the keep patch goes through
        // PatchUtils, not the mockable fluent execute(). Patch-content assertions
        // (kept label, add-not-replace ops, Content-Type) live in PodCleanupTest.
        val seen = mutableListOf<Request>()
        val s = session(api = interceptedCoreV1Api(seen), keepPodOnFailure = true)
        s.markFailed()
        s.close()
        assertTrue(seen.any { it.method == "PATCH" })
        assertEquals(emptyList(), seen.filter { it.method == "DELETE" })
    }

    @Test
    fun `close without markFailed deletes the pod even with keepPodOnFailure`() {
        val api = mockk<CoreV1Api>(relaxed = true)
        val s = session(api = api, keepPodOnFailure = true)
        s.close()
        verify { api.deleteNamespacedPod("pod-1", "homer") }
    }

    // ========== Session lifecycle spans follow the run's muting (ADR-0050) ==========

    private class CollectingExporter : SpanExporter {
        val spans = mutableListOf<SpanData>()

        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            this.spans.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush() = CompletableResultCode.ofSuccess()

        override fun shutdown() = CompletableResultCode.ofSuccess()
    }

    private fun agent(export: TelemetryExport) =
        Agent(
            name = "watcher",
            commands = listOf(Command.Cmd(cmd = listOf("echo", "hi"))),
            telemetry = AgentTelemetry(export = export),
        )

    @Test
    fun `close span is dropped when the run is muted`() {
        // Real SdkTracerProvider, not a mock: the mechanism under test is the SDK's
        // default ParentBased sampler dropping children of an unsampled parent, and a
        // mock would only assert the code we wrote. close() runs after the run's scope
        // has unwound, so if it does not carry the run's context explicitly it starts a
        // NEW ROOT — sampled on its own merits, and `telemetry.export: OFF` leaks pod
        // name, namespace and cleanup mode despite the agent being muted.
        val exporter = CollectingExporter()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        val tracer = provider.get("test")
        val root = tracer.startAgentRootSpan(agent(TelemetryExport.OFF), "agent_request")

        session(tracer = tracer, parentContext = Context.root().with(root)).close()
        provider.forceFlush().join(5, TimeUnit.SECONDS)

        assertTrue(exporter.spans.none { it.name == "runner.session.close" }, "a muted run must export no close span")
    }

    @Test
    fun `close span is exported and joins the run's trace when the run is not muted`() {
        // The control: without this the test above would also pass if close() simply
        // stopped emitting a span at all.
        val exporter = CollectingExporter()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        val tracer = provider.get("test")
        val root = tracer.startAgentRootSpan(agent(TelemetryExport.ALL), "agent_request")

        session(tracer = tracer, parentContext = Context.root().with(root)).close()
        provider.forceFlush().join(5, TimeUnit.SECONDS)

        val closeSpan = exporter.spans.single { it.name == "runner.session.close" }
        assertEquals(root.spanContext.traceId, closeSpan.traceId, "the close span belongs to the run's trace")
    }
}
