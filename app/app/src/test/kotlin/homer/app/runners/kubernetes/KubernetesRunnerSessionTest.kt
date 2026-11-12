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
import homer.app.runners.RunDeadline
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KubernetesRunnerSessionTest {
    private fun session(
        sync: KubernetesWorkspaceSync = mockk(relaxed = true),
        extras: List<ExtraUploadSpec> = emptyList(),
        excludes: List<String> = emptyList(),
        api: CoreV1Api = mockk(relaxed = true),
        keepPodOnFailure: Boolean = false,
        maxRunSeconds: Long? = null,
        gracefulStopSeconds: Long? = null,
        podCreatedAtEpochSec: Long? = null,
        tracer: Tracer? = null,
        parentContext: Context = Context.root(),
        jobName: String? = null,
        batchApi: BatchV1Api? = null,
    ) = KubernetesRunnerSession(
        tracer = tracer,
        jobName = jobName,
        batchApi = batchApi,
        parentContext = parentContext,
        podName = "pod-1",
        namespace = "homer",
        api = api,
        shell = mockk(relaxed = true),
        keepPodOnFailure = keepPodOnFailure,
        keepPodTtlSeconds = 3600,
        maxRunSeconds = maxRunSeconds,
        gracefulStopSeconds = gracefulStopSeconds,
        podCreatedAtEpochSec = podCreatedAtEpochSec,
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
    fun `a failed session with maxRunSeconds keeps its pod without raising the deadline`() {
        val seen = mutableListOf<Request>()
        val s = session(api = interceptedCoreV1Api(seen), keepPodOnFailure = true, maxRunSeconds = 60)
        s.markFailed()
        s.close()
        val body = seen.single { it.method == "PATCH" }.let { Buffer().also { b -> it.body!!.writeTo(b) }.readUtf8() }
        assertFalse(body.contains("activeDeadlineSeconds"), body)
        assertTrue(body.contains("homer.app~1kept"), body)
        assertEquals(emptyList(), seen.filter { it.method == "DELETE" })
    }

    @Test
    fun `close without markFailed deletes the pod even with keepPodOnFailure`() {
        val api = mockk<CoreV1Api>(relaxed = true)
        val s = session(api = api, keepPodOnFailure = true)
        s.close()
        verify { api.deleteNamespacedPod("pod-1", "homer") }
    }

    // ========== Job mode: the Job goes with its pod (ADR-0100) ==========

    // ========== The run's soft/hard deadlines (ADR-0105) ==========

    @Test
    fun `runDeadline is computed from the pod startTime when a graceful stop is configured`() {
        val api = podStartApi(OffsetDateTime.ofInstant(Instant.ofEpochSecond(1_000), ZoneOffset.UTC))

        val s = session(api = api, maxRunSeconds = 300, gracefulStopSeconds = 60, podCreatedAtEpochSec = 1_100)

        assertEquals(RunDeadline(softEpochSec = 1_240, hardEpochSec = 1_300), s.runDeadline)
        verify(exactly = 1) { api.readNamespacedPodStatus(any(), any()) }
    }

    @Test
    fun `runDeadline falls back to createdAt when the pod reports no startTime`() {
        val api = podStartApi(null)

        val s = session(api = api, maxRunSeconds = 300, gracefulStopSeconds = 60, podCreatedAtEpochSec = 1_000)

        assertEquals(RunDeadline(softEpochSec = 1_240, hardEpochSec = 1_300), s.runDeadline)
    }

    @Test
    fun `a skewed pod startTime cannot extend the deadline`() {
        // The pod's own report is outside Homer's trust boundary: it may shorten the deadline, never
        // push it past the createdAt Homer recorded when it created the pod (§12 rule 2).
        val api = podStartApi(OffsetDateTime.ofInstant(Instant.ofEpochSecond(9_999), ZoneOffset.UTC))

        val s = session(api = api, maxRunSeconds = 300, gracefulStopSeconds = 60, podCreatedAtEpochSec = 1_000)

        assertEquals(RunDeadline(softEpochSec = 1_240, hardEpochSec = 1_300), s.runDeadline)
    }

    @Test
    fun `runDeadline is null when neither the pod nor Homer reports a start`() {
        val api = podStartApi(null)

        val s = session(api = api, maxRunSeconds = 300, gracefulStopSeconds = 60)

        assertNull(s.runDeadline)
    }

    @Test
    fun `without gracefulStopSeconds there is no extra Kubernetes read`() {
        val api = mockk<CoreV1Api>(relaxed = true)

        val s = session(api = api, maxRunSeconds = 300)

        assertNull(s.runDeadline)
        verify(exactly = 0) { api.readNamespacedPodStatus(any(), any()) }
    }

    /** An api whose `readNamespacedPodStatus` reports [startTime] as the pod's start. */
    private fun podStartApi(startTime: OffsetDateTime?): CoreV1Api {
        val req = mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>()
        every { req.execute() } returns V1Pod().status(V1PodStatus().startTime(startTime))
        return mockk { every { readNamespacedPodStatus(any(), any()) } returns req }
    }

    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    /** A Job session whose core and batch calls land, in order, in [seen]; [podPatch] answers the pod PATCH. */
    private fun jobSession(
        seen: MutableList<Request>,
        keepPodOnFailure: Boolean = false,
        podPatch: Int = 200,
    ) = session(
        api = interceptedCoreV1Api(seen) { if (it.method == "PATCH") podPatch else 200 },
        keepPodOnFailure = keepPodOnFailure,
        jobName = "homer-7-job",
        batchApi = interceptedBatchV1Api(seen),
    )

    @Test
    fun `Job session close deletes the Job with Background propagation, then the pod with grace 0`() {
        val seen = mutableListOf<Request>()

        jobSession(seen).close()

        assertEquals(
            listOf("DELETE /apis/batch/v1/namespaces/homer/jobs/homer-7-job", "DELETE /api/v1/namespaces/homer/pods/pod-1"),
            seen.map { "${it.method} ${it.url.encodedPath}" },
        )
        assertEquals("Background", seen[0].url.queryParameter("propagationPolicy"))
        assertEquals("0", seen[1].url.queryParameter("gracePeriodSeconds"))
    }

    @Test
    fun `Job session keep-on-failure labels the pod and the Job`() {
        val seen = mutableListOf<Request>()
        val s = jobSession(seen, keepPodOnFailure = true)

        s.markFailed()
        s.close()

        assertEquals(
            listOf("PATCH /api/v1/namespaces/homer/pods/pod-1", "PATCH /apis/batch/v1/namespaces/homer/jobs/homer-7-job"),
            seen.map { "${it.method} ${it.url.encodedPath}" },
        )
        val jobPatch = seen[1]
        assertEquals(V1Patch.PATCH_FORMAT_JSON_PATCH, jobPatch.body?.contentType()?.toString())
        assertEquals("""[{"op":"add","path":"/metadata/labels/homer.app~1kept","value":"true"}]""", jobPatch.bodyText())
    }

    @Test
    fun `a failed pod keep also deletes the Job`() {
        val seen = mutableListOf<Request>()
        val s = jobSession(seen, keepPodOnFailure = true, podPatch = 500)

        s.markFailed()
        s.close()

        assertEquals(
            listOf(
                "PATCH /api/v1/namespaces/homer/pods/pod-1",
                "DELETE /api/v1/namespaces/homer/pods/pod-1",
                "DELETE /apis/batch/v1/namespaces/homer/jobs/homer-7-job",
            ),
            seen.map { "${it.method} ${it.url.encodedPath}" },
        )
        assertEquals("Background", seen[2].url.queryParameter("propagationPolicy"))
    }

    @Test
    fun `a Pod session close makes no batch call`() {
        for (failed in listOf(false, true)) {
            val core = mutableListOf<Request>()
            val batch = mutableListOf<Request>()
            val s = session(api = interceptedCoreV1Api(core), keepPodOnFailure = true, batchApi = interceptedBatchV1Api(batch))
            if (failed) s.markFailed()

            s.close()

            assertEquals(emptyList(), batch, "failed=$failed")
            val expected = if (failed) "PATCH" else "DELETE"
            assertEquals(listOf("$expected /api/v1/namespaces/homer/pods/pod-1"), core.map { "${it.method} ${it.url.encodedPath}" })
            if (!failed) assertEquals("0", core.single().url.queryParameter("gracePeriodSeconds"))
            assertEquals(mapOf("pod.name" to "pod-1"), s.metadata)
        }
    }

    @Test
    fun `a Job session names its Job in metadata and on the close span`() {
        val exporter = CollectingExporter()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        val s =
            session(
                api = interceptedCoreV1Api(mutableListOf()),
                tracer = provider.get("test"),
                jobName = "homer-7-job",
                batchApi = interceptedBatchV1Api(mutableListOf()),
            )

        assertEquals(mapOf("pod.name" to "pod-1", "job.name" to "homer-7-job"), s.metadata)
        s.close()
        provider.forceFlush().join(5, TimeUnit.SECONDS)

        val closeSpan = exporter.spans.single { it.name == "runner.session.close" }
        assertEquals("homer-7-job", closeSpan.attributes.get(AttributeKey.stringKey("job.name")))
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
