package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import homer.app.fixtures.TestFixtures
import homer.app.models.KubernetesWorkload
import homer.app.models.PodPhase
import homer.app.models.ResourceSpec
import homer.app.models.RunnerConfig
import homer.app.models.RunnerState
import homer.app.runners.RunnerSessionParams
import homer.app.services.ProcessManager
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.CoreV1Event
import io.kubernetes.client.openapi.models.CoreV1EventList
import io.kubernetes.client.openapi.models.V1ContainerState
import io.kubernetes.client.openapi.models.V1ContainerStateTerminated
import io.kubernetes.client.openapi.models.V1ContainerStateWaiting
import io.kubernetes.client.openapi.models.V1ContainerStatus
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1JobSpec
import io.kubernetes.client.openapi.models.V1LabelSelector
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.openapi.models.V1PodStatus
import io.kubernetes.client.openapi.models.V1PodTemplateSpec
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KubernetesRunnerTest {
    private val config =
        RunnerConfig.Kubernetes(
            namespace = "homer",
            image = "ubuntu:24.04",
            resources = ResourceSpec(),
            podStartTimeoutSeconds = 2,
        )

    private val params =
        RunnerSessionParams(
            processId = 7,
            agentName = "demo",
            workspace = TestFixtures.mockWorkspaceConfig(),
            env = mapOf("HMR_WORK_DIR" to "/tmp/test/server/1/workdir"),
            parentContext = Context.current(),
        )

    @Test
    fun `openSession returns session when pod reaches ready`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val execClient = KubernetesExecClient { _, _, _, _ -> mockk(relaxed = true) }
            stubCreatePod(api)
            stubPodStatus(api, readyPod())

            val runner = KubernetesRunner("k8s-dev", config, api, execClient, clock = { 0L })
            val session = runner.openSession(params)

            assertTrue(session is KubernetesRunnerSession)
            assertEquals("homer", session.namespace)
        }

    @Test
    fun `openSession session env contains only HMR_API_URL, not runner config env`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val execClient = KubernetesExecClient { _, _, _, _ -> mockk(relaxed = true) }
            stubCreatePod(api)
            stubPodStatus(api, readyPod())

            val runnerCfg = config.copy(homerApiUrl = "http://homer.svc:8080", env = mapOf("MY_RUNNER_VAR" to "from-runner"))
            val runner = KubernetesRunner("k8s-dev", runnerCfg, api, execClient, clock = { 0L })

            val session = runner.openSession(params)

            // config.env is already layered into the workspace env by WorkspaceConfigFactory
            // via runner.runnerEnv; it must NOT appear in session.env a second time or
            // AgentExecutor's appendToEnv would let it override agent env (wrong precedence).
            // Only the backend-derived HMR_API_URL belongs here. See ADR-0065.
            assertFalse("MY_RUNNER_VAR" in session.env, "runner config env must not appear in session.env")
            assertEquals("http://homer.svc:8080", session.env[homer.app.EnvConstants.API_URL])
            assertTrue(
                listOf("HOME", "PATH", "LANG", "TZ").none { it in session.env },
                "session env must not contain parentEnvAllowlist keys; got ${session.env.keys}",
            )
        }

    @Test
    fun `kubernetes runner reports isRemote and exposes its configured env`() {
        val api = mockk<CoreV1Api>(relaxed = true)
        val runnerCfg = config.copy(env = mapOf("MY_RUNNER_VAR" to "from-runner"))
        val runner = KubernetesRunner("k8s-dev", runnerCfg, api, dummyExecClient())

        assertTrue(runner.isRemote, "kubernetes runner must be remote")
        assertEquals("from-runner", runner.runnerEnv["MY_RUNNER_VAR"])
    }

    @Test
    fun `openSession throws PodStartupException with diagnostics on create failure`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val createReq =
                mockk<CoreV1Api.APIcreateNamespacedPodRequest>(relaxed = true) {
                    every { execute() } throws ApiException(409, "conflict")
                }
            every { api.createNamespacedPod(any(), any()) } returns createReq
            stubPodStatus(api, readyPod())
            stubEvents(api, emptyList())

            val runner = KubernetesRunner("k8s-dev", config, api, dummyExecClient(), clock = { 0L })

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }
            assertTrue(thrown.message!!.contains("conflict") || thrown.message!!.contains("PodCreateFailed"))
        }

    @Test
    fun `openSession throws PodStartupException when pod reaches Failed phase`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, failedPod())
            stubEvents(api, listOf("Warning/Failed: init-setup exited 1"))
            stubDeletePod(api)

            val runner = KubernetesRunner("k8s-dev", config, api, dummyExecClient(), clock = { 0L })

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }
            assertTrue(thrown.events.isNotEmpty(), "expected events but got: ${thrown.events}")
            assertTrue(
                thrown.reason == "Error" || thrown.reason == "PodFailed",
                "unexpected reason: ${thrown.reason}",
            )
        }

    @Test
    fun `openSession emits Creating then Ready to state sink on success`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, readyPod())

            val captured = mutableListOf<RunnerState>()
            val sink =
                RunnerStateSink { _, update ->
                    val next = update(captured.lastOrNull())
                    captured.add(next)
                }
            val runner = KubernetesRunner("k8s-dev", config, api, dummyExecClient(), clock = { 1_000L }, stateSink = sink)

            runner.openSession(params)

            val phases = captured.map { it.podPhase }
            assertTrue(phases.contains(PodPhase.CREATING), "missing CREATING, got $phases")
            assertTrue(phases.contains(PodPhase.READY), "missing READY, got $phases")
            val ready = captured.last()
            assertEquals("k8s-dev", ready.alias)
            assertEquals(1L, ready.createdAt)
            assertEquals(1L, ready.readyAt)
        }

    @Test
    fun `openSession emits Failed with startupFailure on pod failure`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, failedPod())
            stubEvents(api, listOf("Warning/Failed: init-setup exited 1"))
            stubDeletePod(api)

            val captured = mutableListOf<RunnerState>()
            val sink =
                RunnerStateSink { _, update ->
                    val next = update(captured.lastOrNull())
                    captured.add(next)
                }
            val runner = KubernetesRunner("k8s-dev", config, api, dummyExecClient(), clock = { 2_000L }, stateSink = sink)

            assertThrows<PodStartupException> { runner.openSession(params) }

            val last = captured.last()
            assertEquals(PodPhase.FAILED, last.podPhase)
            assertEquals(2L, last.endedAt)
            val failure = last.startupFailure
            assertTrue(failure != null, "expected startupFailure")
            assertTrue(failure.events.isNotEmpty())
        }

    @Test
    fun `openSession rebuilds the runner state when the sink holds no previous one`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, readyPod())

            // A sink that never hands back the stored state, as when the process row is gone.
            val captured = mutableListOf<RunnerState>()
            val sink = RunnerStateSink { _, update -> captured.add(update(null)) }
            val runner = KubernetesRunner("k8s-dev", config, api, dummyExecClient(), clock = { 3_000L }, stateSink = sink)

            runner.openSession(params)

            val ready = captured.last()
            assertEquals(PodPhase.READY, ready.podPhase)
            assertEquals("k8s-dev", ready.alias)
            assertTrue(ready.podName != null, "the pod name survives the rebuild")
            assertEquals(3L, ready.createdAt)
            assertEquals(PodPhase.PENDING, captured[1].podPhase)
        }

    @Test
    fun `a pod failure is reported even when the sink holds no previous state`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, failedPod())
            stubEvents(api, listOf("Warning/Failed: init-setup exited 1"))
            stubDeletePod(api)

            val captured = mutableListOf<RunnerState>()
            val sink = RunnerStateSink { _, update -> captured.add(update(null)) }
            val runner = KubernetesRunner("k8s-dev", config, api, dummyExecClient(), clock = { 4_000L }, stateSink = sink)

            assertThrows<PodStartupException> { runner.openSession(params) }

            val last = captured.last()
            assertEquals(PodPhase.FAILED, last.podPhase)
            assertEquals("k8s-dev", last.alias)
            assertEquals(4L, last.endedAt)
            assertTrue(last.startupFailure != null, "expected startupFailure")
        }

    @Test
    fun `openSession throws PodStartupException on timeout`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, pendingPod())
            stubEvents(api, listOf("Normal/Scheduled: assigned node-0"))
            stubDeletePod(api)

            var now = 0L
            val runner =
                KubernetesRunner(
                    alias = "k8s-dev",
                    config = config,
                    api = api,
                    execClient = dummyExecClient(),
                    clock = { now.also { now += 600 } },
                )

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }
            assertTrue(thrown.reason == "PodStartTimeout" || thrown.message!!.contains("PodStartTimeout"))
        }

    @Test
    fun `openSession starts runner_session_open span with alias and pod attributes`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, readyPod())

            val span = mockk<Span>(relaxed = true)
            val builder =
                mockk<SpanBuilder>(relaxed = true) {
                    every { setParent(any()) } returns this
                    every { startSpan() } returns span
                }
            val tracer =
                mockk<Tracer> {
                    every { spanBuilder(any()) } returns builder
                }

            val runner =
                KubernetesRunner(
                    alias = "k8s-dev",
                    config = config,
                    api = api,
                    execClient = dummyExecClient(),
                    clock = { 0L },
                    tracer = tracer,
                )

            runner.openSession(params)

            verify { tracer.spanBuilder("runner.session.open") }
            verify { span.setAttribute("runner.alias", "k8s-dev") }
            verify { span.setAttribute("pod.namespace", "homer") }
            verify { span.setAttribute(eq("pod.name"), match<String> { it.startsWith("homer-7-") }) }
            verify { span.end() }
        }

    @Test
    fun `openSession records exception on pod startup failure span`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, failedPod())
            stubEvents(api, listOf("Warning/Failed: init-setup exited 1"))
            stubDeletePod(api)

            val span = mockk<Span>(relaxed = true)
            val builder =
                mockk<SpanBuilder>(relaxed = true) {
                    every { setParent(any()) } returns this
                    every { startSpan() } returns span
                }
            val tracer =
                mockk<Tracer> {
                    every { spanBuilder(any()) } returns builder
                }

            val runner =
                KubernetesRunner(
                    alias = "k8s-dev",
                    config = config,
                    api = api,
                    execClient = dummyExecClient(),
                    clock = { 0L },
                    tracer = tracer,
                )

            assertThrows<PodStartupException> { runner.openSession(params) }

            verify { span.setAttribute(eq("pod.startup_failure.reason"), any<String>()) }
            verify { span.recordException(any()) }
            verify { span.end() }
        }

    @Test
    fun `openSession cleans up pod and emits Failed when pod status read fails`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            val statusReq =
                mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true) {
                    every { execute() } throws ApiException(403, "pods \"homer-7-x\" is forbidden: cannot get resource \"pods/status\"")
                }
            every { api.readNamespacedPodStatus(any(), any()) } returns statusReq
            stubDeletePod(api)

            val captured = mutableListOf<RunnerState>()
            val sink =
                RunnerStateSink { _, update ->
                    val next = update(captured.lastOrNull())
                    captured.add(next)
                }
            val runner = KubernetesRunner("k8s-dev", config, api, dummyExecClient(), clock = { 0L }, stateSink = sink)

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }

            assertEquals("PodStatusUnreadable", thrown.reason)
            verify { api.deleteNamespacedPod(any(), any()) }
            assertEquals(PodPhase.FAILED, captured.last().podPhase)
            assertTrue(captured.last().startupFailure != null, "expected startupFailure on the sink")
        }

    @Test
    fun `openSession keeps pod with TTL instead of deleting when keepPodOnFailure is set`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, failedPod())
            stubEvents(api, listOf("Warning/Failed: init-setup exited 1"))
            stubReadPod(api, V1Pod().status(V1PodStatus().phase("Failed").startTime(java.time.OffsetDateTime.now())))
            val patchReq =
                mockk<CoreV1Api.APIpatchNamespacedPodRequest>(relaxed = true) {
                    every { execute() } returns V1Pod()
                }
            every { api.patchNamespacedPod(any(), any(), any()) } returns patchReq

            val runner =
                KubernetesRunner("k8s-dev", config.copy(keepPodOnFailure = true), api, dummyExecClient(), clock = { 0L })

            assertThrows<PodStartupException> { runner.openSession(params) }

            verify { api.patchNamespacedPod(any(), any(), any()) }
            verify(exactly = 0) { api.deleteNamespacedPod(any(), any()) }
        }

    @Test
    fun `openSession deletes pod despite keepPodOnFailure when it never reached a node`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, pendingPod())
            stubEvents(api, listOf("Normal/Scheduled: assigned node-0"))
            stubDeletePod(api)
            stubReadPod(api, V1Pod().status(V1PodStatus().phase("Pending")))

            var now = 0L
            val runner =
                KubernetesRunner(
                    alias = "k8s-dev",
                    config = config.copy(keepPodOnFailure = true),
                    api = api,
                    execClient = dummyExecClient(),
                    clock = { now.also { now += 600 } },
                )

            assertThrows<PodStartupException> { runner.openSession(params) }

            verify { api.deleteNamespacedPod(any(), any()) }
            verify(exactly = 0) { api.patchNamespacedPod(any(), any(), any()) }
        }

    @Test
    fun `a pod Kubernetes stops mid-run fails the command and records the termination`(
        @org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path,
    ) = runTest {
        val api = mockk<CoreV1Api>(relaxed = true)
        stubCreatePod(api)
        stubEvents(api, emptyList())
        var stopped = false
        val req =
            mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true) {
                every { execute() } answers { if (stopped) podWithStatus("Failed", "DeadlineExceeded", "too long") else readyPod() }
            }
        every { api.readNamespacedPodStatus(any(), any()) } returns req
        val exec =
            mockk<Process>(relaxed = true) {
                every { inputStream } returns java.io.ByteArrayInputStream(ByteArray(0))
                every { errorStream } returns java.io.ByteArrayInputStream(ByteArray(0))
                every { outputStream } returns java.io.ByteArrayOutputStream()
                every { waitFor(any<Long>(), any<java.util.concurrent.TimeUnit>()) } returns true
                every { exitValue() } returns 137
            }
        val captured = mutableListOf<RunnerState>()
        val sink = RunnerStateSink { _, update -> captured.add(update(captured.lastOrNull())) }
        val runner =
            KubernetesRunner("k8s-dev", config.copy(maxRunSeconds = 60), api, { _, _, _, _ -> exec }, clock = { 5_000L }, stateSink = sink)
        val session = runner.openSession(params)
        stopped = true

        val e =
            assertThrows<homer.app.exceptions.PodFailedDuringExecutionException> {
                session.shell.cmd(listOf("sleep"), tmp.resolve("o"), tmp.resolve("e"), timeout = kotlin.time.Duration.parse("10s"))
            }

        assertEquals("Kubernetes stopped the pod: maxRunSeconds (60 s) exceeded (DeadlineExceeded)", e.message)
        assertEquals(PodPhase.FAILED, captured.last().podPhase)
        assertEquals("DeadlineExceeded", captured.last().termination?.reason)
        assertEquals(5L, captured.last().endedAt)
    }

    @Test
    fun `Job mode creates a Job, finds its pod by the Job selector and reaches ready`(
        @org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path,
    ) = runTest {
        val api = mockk<CoreV1Api>(relaxed = true)
        stubPodStatus(api, readyPod())
        val selector = stubJobPods(api, "homer-7-job-x1y2z")
        val seen = mutableListOf<okhttp3.Request>()
        val batch = interceptedBatchV1Api(seen) { 201 to createdJob(matchLabels = mapOf("controller-uid" to "uid-1")) }
        val execPods = mutableListOf<String>()
        val exec = KubernetesExecClient { _, pod, _, _ -> execPods.add(pod).let { exitingProcess(0) } }
        val captured = mutableListOf<RunnerState>()
        val sink = RunnerStateSink { _, update -> captured.add(update(captured.lastOrNull())) }
        val runner = KubernetesRunner("k8s-dev", jobConfig, api, exec, clock = { 1_000L }, stateSink = sink, batchApi = batch)

        val session = runner.openSession(params)

        val create = seen.single()
        assertEquals("POST", create.method)
        assertEquals("/apis/batch/v1/namespaces/homer/jobs", create.url.encodedPath)
        assertEquals("controller-uid=uid-1", selector.captured)
        assertEquals("homer-7-job-x1y2z", (session as KubernetesRunnerSession).podName)
        session.shell.cmd(listOf("true"), tmp.resolve("o"), tmp.resolve("e"), timeout = kotlin.time.Duration.parse("10s"))
        assertEquals(listOf("homer-7-job-x1y2z"), execPods)
        val jobName = captured.first().jobName!!
        assertTrue(jobName.startsWith("homer-7-"), jobName)
        assertTrue(captured.all { it.jobName == jobName }, "every state carries the Job name: $captured")
        assertEquals(
            listOf(
                PodPhase.CREATING to null,
                PodPhase.PENDING to null,
                PodPhase.PENDING to "homer-7-job-x1y2z",
                PodPhase.READY to "homer-7-job-x1y2z",
            ),
            captured.map { it.podPhase to it.podName },
        )
    }

    @Test
    fun `Job mode falls back to the controller-uid label when the Job has no selector`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubPodStatus(api, readyPod())
            val selector = stubJobPods(api, "homer-7-job-a1b2c")
            val batch = interceptedBatchV1Api(mutableListOf()) { 201 to createdJob(uid = "uid-2") }
            val runner = KubernetesRunner("k8s-dev", jobConfig, api, dummyExecClient(), clock = { 0L }, batchApi = batch)

            runner.openSession(params)

            assertEquals("batch.kubernetes.io/controller-uid=uid-2", selector.captured)
        }

    @Test
    fun `Pod mode never calls the batch API`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubCreatePod(api)
            stubPodStatus(api, readyPod())
            val seen = mutableListOf<okhttp3.Request>()
            val runner =
                KubernetesRunner("k8s-dev", config, api, dummyExecClient(), clock = { 0L }, batchApi = interceptedBatchV1Api(seen))

            runner.openSession(params).close()

            assertTrue(seen.isEmpty(), "Pod mode made batch calls: ${seen.map { it.url }}")
            verify(exactly = 0) { api.listNamespacedPod(any()) }
        }

    @Test
    fun `Job mode fails startup with the API answer when the Job create is refused`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val batch = interceptedBatchV1Api(mutableListOf()) { 403 to """{"message":"jobs.batch is forbidden"}""" }
            val captured = mutableListOf<RunnerState>()
            val sink = RunnerStateSink { _, update -> captured.add(update(captured.lastOrNull())) }
            val runner = KubernetesRunner("k8s-dev", jobConfig, api, dummyExecClient(), clock = { 0L }, stateSink = sink, batchApi = batch)

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }

            assertEquals("PodCreateFailed", thrown.reason)
            assertTrue(thrown.rawMessage.contains("jobs.batch is forbidden"), thrown.rawMessage)
            assertEquals(PodPhase.FAILED, captured.last().podPhase)
            assertTrue(captured.last().jobName != null, "the failed state keeps the Job name")
        }

    @Test
    fun `Job mode fails startup when the created Job names neither a selector nor a uid`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val runner =
                KubernetesRunner(
                    "k8s-dev",
                    jobConfig,
                    api,
                    dummyExecClient(),
                    clock = { 0L },
                    batchApi = interceptedBatchV1Api(mutableListOf()),
                )

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }

            assertEquals("PodCreateFailed", thrown.reason)
            verify(exactly = 0) { api.listNamespacedPod(any()) }
        }

    @Test
    fun `Job mode fails startup when the pod list is refused`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val listReq =
                mockk<CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true) {
                    every { labelSelector(any()) } returns this
                    every { execute() } throws ApiException(403, "pods is forbidden")
                }
            every { api.listNamespacedPod(any()) } returns listReq
            val batch = interceptedBatchV1Api(mutableListOf()) { 201 to createdJob(uid = "uid-3") }
            val runner = KubernetesRunner("k8s-dev", jobConfig, api, dummyExecClient(), clock = { 0L }, batchApi = batch)

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }

            assertEquals("PodStatusUnreadable", thrown.reason)
        }

    @Test
    fun `Job mode times out when the Job never creates a pod, inside the one start budget`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, podName = null)
            val batch = interceptedBatchV1Api(mutableListOf()) { 201 to createdJob(uid = "uid-4") }
            val captured = mutableListOf<RunnerState>()
            val sink = RunnerStateSink { _, update -> captured.add(update(null)) }
            val runner =
                KubernetesRunner(
                    "k8s-dev",
                    jobConfig.copy(podStartTimeoutSeconds = 1),
                    api,
                    dummyExecClient(),
                    clock = { 0L },
                    stateSink = sink,
                    batchApi = batch,
                )

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }

            assertEquals("PodStartTimeout", thrown.reason)
            assertEquals(PodPhase.FAILED, captured.last().podPhase)
            assertTrue(captured.last().jobName != null, "a rebuilt failed state keeps the Job name")
            verify(exactly = 0) { api.deleteNamespacedPod(any(), any()) }
        }

    @Test
    fun `Job mode records a mid-run stop against the found pod and keeps the Job name`(
        @org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path,
    ) = runTest {
        val api = mockk<CoreV1Api>(relaxed = true)
        stubEvents(api, emptyList())
        var stopped = false
        val req =
            mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true) {
                every { execute() } answers { if (stopped) podWithStatus("Failed", "DeadlineExceeded", "too long") else readyPod() }
            }
        every { api.readNamespacedPodStatus(any(), any()) } returns req
        stubJobPods(api, "homer-7-job-q9w8e")
        val batch = interceptedBatchV1Api(mutableListOf()) { 201 to createdJob(uid = "uid-5") }
        val captured = mutableListOf<RunnerState>()
        // A sink that never hands back the stored state: the termination rebuilds it.
        val sink = RunnerStateSink { _, update -> captured.add(update(null)) }
        val runner =
            KubernetesRunner(
                "k8s-dev",
                jobConfig.copy(maxRunSeconds = 60),
                api,
                { _, _, _, _ -> exitingProcess(137) },
                clock = { 5_000L },
                stateSink = sink,
                batchApi = batch,
            )
        val session = runner.openSession(params)
        stopped = true

        assertThrows<homer.app.exceptions.PodFailedDuringExecutionException> {
            session.shell.cmd(listOf("sleep"), tmp.resolve("o"), tmp.resolve("e"), timeout = kotlin.time.Duration.parse("10s"))
        }

        val last = captured.last()
        assertEquals("DeadlineExceeded", last.termination?.reason)
        assertEquals("homer-7-job-q9w8e", last.podName)
        assertTrue(last.jobName!!.startsWith("homer-7-"), "${last.jobName}")
        verify { api.readNamespacedPodStatus("homer-7-job-q9w8e", "homer") }
    }

    @Test
    fun `Job mode requires a BatchV1Api`() {
        val e = assertThrows<IllegalArgumentException> { KubernetesRunner("k8s-dev", jobConfig, mockk(), dummyExecClient()) }
        assertEquals("kubernetes runner 'k8s-dev': workload Job needs a BatchV1Api", e.message)
    }

    private val jobConfig = config.copy(workload = KubernetesWorkload.Job)

    /** The created Job as the API server answers it: name, optional uid and controller selector. */
    private fun createdJob(
        uid: String? = null,
        matchLabels: Map<String, String>? = null,
    ): String =
        io.kubernetes.client.openapi.JSON.serialize(
            V1Job()
                .metadata(V1ObjectMeta().name("homer-7-job").uid(uid))
                .spec(
                    V1JobSpec()
                        .selector(matchLabels?.let { V1LabelSelector().matchLabels(it) })
                        .template(V1PodTemplateSpec()),
                ),
        )

    /** Stubs the Job's pod list with [podName] (none when null); returns the captured label selector. */
    private fun stubJobPods(
        api: CoreV1Api,
        podName: String?,
    ): CapturingSlot<String> {
        val selector = slot<String>()
        val pods = listOfNotNull(podName).map { V1Pod().metadata(V1ObjectMeta().name(it)) }
        val req =
            mockk<CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true) {
                every { labelSelector(capture(selector)) } returns this
                every { execute() } returns V1PodList().items(pods)
            }
        every { api.listNamespacedPod("homer") } returns req
        return selector
    }

    private fun exitingProcess(code: Int): Process =
        mockk(relaxed = true) {
            every { inputStream } returns java.io.ByteArrayInputStream(ByteArray(0))
            every { errorStream } returns java.io.ByteArrayInputStream(ByteArray(0))
            every { outputStream } returns java.io.ByteArrayOutputStream()
            every { waitFor(any<Long>(), any<java.util.concurrent.TimeUnit>()) } returns true
            every { exitValue() } returns code
        }

    private fun dummyExecClient(): KubernetesExecClient = KubernetesExecClient { _, _, _, _ -> mockk(relaxed = true) }

    private fun stubCreatePod(api: CoreV1Api) {
        val req =
            mockk<CoreV1Api.APIcreateNamespacedPodRequest>(relaxed = true) {
                every { execute() } returns V1Pod()
            }
        every { api.createNamespacedPod(any(), any()) } returns req
    }

    private fun stubPodStatus(
        api: CoreV1Api,
        pod: V1Pod,
    ) {
        val req =
            mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true) {
                every { execute() } returns pod
            }
        every { api.readNamespacedPodStatus(any(), any()) } returns req
    }

    private fun stubReadPod(
        api: CoreV1Api,
        pod: V1Pod,
    ) {
        val req =
            mockk<CoreV1Api.APIreadNamespacedPodRequest>(relaxed = true) {
                every { execute() } returns pod
            }
        every { api.readNamespacedPod(any(), any()) } returns req
    }

    private fun stubEvents(
        api: CoreV1Api,
        eventMessages: List<String>,
    ) {
        val events =
            CoreV1EventList().items(
                eventMessages.map {
                    CoreV1Event()
                        .type("Warning")
                        .reason("X")
                        .message(it)
                },
            )
        val req =
            mockk<CoreV1Api.APIlistNamespacedEventRequest>(relaxed = true) {
                every { execute() } returns events
                every { fieldSelector(any()) } returns this
            }
        every { api.listNamespacedEvent(any()) } returns req
    }

    private fun stubDeletePod(api: CoreV1Api) {
        val req =
            mockk<CoreV1Api.APIdeleteNamespacedPodRequest>(relaxed = true) {
                every { execute() } returns V1Pod()
                every { gracePeriodSeconds(any()) } returns this
                every { body(any()) } returns this
            }
        every { api.deleteNamespacedPod(any(), any()) } returns req
    }

    private fun readyPod(): V1Pod =
        V1Pod().status(
            V1PodStatus()
                .phase("Running")
                .containerStatuses(
                    listOf(
                        V1ContainerStatus()
                            .name(PodSpecBuilder.MAIN_CONTAINER_NAME)
                            .ready(true),
                    ),
                ),
        )

    private fun pendingPod(): V1Pod = V1Pod().status(V1PodStatus().phase("Pending"))

    private fun failedPod(): V1Pod =
        V1Pod().status(
            V1PodStatus()
                .phase("Failed")
                .reason("Error")
                .message("init-setup exited 1")
                .initContainerStatuses(
                    listOf(
                        V1ContainerStatus()
                            .name("init")
                            .state(
                                V1ContainerState()
                                    .terminated(
                                        V1ContainerStateTerminated()
                                            .exitCode(1)
                                            .reason("Error")
                                            .message("init-setup exited 1"),
                                    ),
                            ),
                    ),
                ),
        )

    @Suppress("unused")
    private fun waitingPod(): V1Pod =
        V1Pod().status(
            V1PodStatus().phase("Pending").containerStatuses(
                listOf(
                    V1ContainerStatus()
                        .name(PodSpecBuilder.MAIN_CONTAINER_NAME)
                        .state(V1ContainerState().waiting(V1ContainerStateWaiting().reason("ImagePullBackOff"))),
                ),
            ),
        )
}
