package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import homer.app.fixtures.TestFixtures
import homer.app.models.KubernetesWorkload
import homer.app.models.ResourceSpec
import homer.app.models.RunnerConfig
import homer.app.runners.RunnerSessionParams
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.JSON
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.CoreV1Event
import io.kubernetes.client.openapi.models.CoreV1EventList
import io.kubernetes.client.openapi.models.V1ContainerStatus
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1JobCondition
import io.kubernetes.client.openapi.models.V1JobSpec
import io.kubernetes.client.openapi.models.V1JobStatus
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
import io.opentelemetry.context.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Job-mode startup failures: Job conditions, `FailedCreate` events and the Job delete on every failure (ADR-0100). */
class KubernetesRunnerJobStartupTest {
    private val config =
        RunnerConfig.Kubernetes(
            namespace = "homer",
            image = "ubuntu:24.04",
            resources = ResourceSpec(),
            podStartTimeoutSeconds = 2,
            workload = KubernetesWorkload.Job,
        )

    private val params =
        RunnerSessionParams(
            processId = 7,
            agentName = "demo",
            workspace = TestFixtures.mockWorkspaceConfig(),
            env = mapOf("HMR_WORK_DIR" to "/tmp/test/server/1/workdir"),
            parentContext = Context.current(),
        )

    private val quota = """pods "homer-7-job-x" is forbidden: exceeded quota: agents, requested: cpu=2, used: cpu=8, limited: cpu=8"""

    @Test
    fun `a quota FailedCreate is waited out and the run starts when quota frees`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, "homer-7-job-x1y2z", emptyPolls = 2)
            stubPodStatus(api, readyPod())
            val events = stubJobEvents(api, failedCreate(quota))
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen), config.copy(podStartTimeoutSeconds = 10))

            val session = runner.openSession(params)

            assertEquals("homer-7-job-x1y2z", (session as KubernetesRunnerSession).podName)
            verify(exactly = 2) { api.listNamespacedEvent("homer") }
            assertTrue(events.captured.startsWith("involvedObject.kind=Job,involvedObject.name=homer-7-"), events.captured)
            assertTrue(seen.none { it.method == "DELETE" }, "a started run keeps its Job: ${seen.map { it.method }}")
        }

    @Test
    fun `a quota FailedCreate until the timeout fails with the last quota message`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, podName = null)
            stubJobEvents(api, failedCreate(quota))
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen), config.copy(podStartTimeoutSeconds = 1))

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }

            assertEquals("PodStartTimeout", thrown.reason)
            assertTrue(thrown.rawMessage.endsWith("; last FailedCreate: $quota"), thrown.rawMessage)
            assertTrue(thrown.rawMessage.contains("had no ready pod within 1s"), thrown.rawMessage)
            assertBackgroundJobDelete(seen)
        }

    @Test
    fun `an admission FailedCreate fails startup at once`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, podName = null)
            val forbidden = """pods "x" is forbidden: violates PodSecurity "restricted:latest""""
            stubJobEvents(api, failedCreate(forbidden))
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen))

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }

            assertEquals("JobFailedCreate", thrown.reason)
            assertEquals(forbidden, thrown.rawMessage)
            assertTrue(thrown.events.any { it.contains(forbidden) }, "${thrown.events}")
            verify(exactly = 1) { api.listNamespacedPod("homer") }
            assertBackgroundJobDelete(seen)
        }

    @Test
    fun `a Failed Job before ready fails startup`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, podName = null)
            stubJobEvents(api)
            val seen = mutableListOf<Request>()
            val failed =
                job(
                    V1JobCondition()
                        .type("Failed")
                        .status("True")
                        .reason("DeadlineExceeded")
                        .message("Job was active longer"),
                )
            val runner = runner(api, jobApi(seen, read = failed))

            val thrown = assertThrows<PodStartupException> { runner.openSession(params) }

            assertEquals("JobFailed", thrown.reason)
            assertEquals("Job was active longer", thrown.rawMessage)
            assertBackgroundJobDelete(seen)
        }

    @Test
    fun `a Failed condition that is not True keeps waiting`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, podName = null)
            stubJobEvents(api)
            val notFailed = job(V1JobCondition().type("Failed").status("False").message("not yet"))
            val runner = runner(api, jobApi(mutableListOf(), read = notFailed), config.copy(podStartTimeoutSeconds = 1))

            assertEquals("PodStartTimeout", assertThrows<PodStartupException> { runner.openSession(params) }.reason)
        }

    @Test
    fun `a failed pod in Job mode deletes the pod and the Job`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, "homer-7-job-f4i1d")
            stubPodStatus(api, V1Pod().status(V1PodStatus().phase("Failed").message("init failed")))
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen))

            assertThrows<PodStartupException> { runner.openSession(params) }

            verify { api.deleteNamespacedPod("homer-7-job-f4i1d", "homer") }
            assertBackgroundJobDelete(seen)
        }

    @Test
    fun `a pod kept at a Job-mode startup failure labels its Job kept`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, "homer-7-job-k3p7a")
            stubPodStatus(api, V1Pod().status(V1PodStatus().phase("Failed").message("init failed")))
            val read =
                mockk<CoreV1Api.APIreadNamespacedPodRequest>(relaxed = true) {
                    every { execute() } returns V1Pod().status(V1PodStatus().startTime(OffsetDateTime.now()))
                }
            every { api.readNamespacedPod(any(), any()) } returns read
            every { api.patchNamespacedPod(any(), any(), any()) } returns mockk(relaxed = true)
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen), config.copy(keepPodOnFailure = true))

            assertThrows<PodStartupException> { runner.openSession(params) }

            verify(exactly = 0) { api.deleteNamespacedPod(any(), any()) }
            assertTrue(seen.none { it.method == "DELETE" }, "a kept pod keeps its Job: ${seen.map { it.method }}")
            val patch = seen.single { it.method == "PATCH" }
            assertTrue(patch.url.encodedPath.startsWith("/apis/batch/v1/namespaces/homer/jobs/homer-7-"), patch.url.toString())
        }

    @Test
    fun `Job mode startup failure after the pod exists deletes the Job and the pod`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, "homer-7-job-u7r3d")
            val status =
                mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true) {
                    every { execute() } throws ApiException(403, "pods/status is forbidden")
                }
            every { api.readNamespacedPodStatus(any(), any()) } returns status
            val order = mutableListOf<String>()
            stubPodDelete(api, order)
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen) { order += "job ${it.method}" })

            assertEquals("PodStatusUnreadable", assertThrows<PodStartupException> { runner.openSession(params) }.reason)

            assertBackgroundJobDelete(seen)
            assertEquals(listOf("job POST", "job DELETE", "pod homer-7-job-u7r3d"), order)
        }

    @Test
    fun `a timeout with a found pod that is not ready deletes the Job and the pod`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, "homer-7-job-p3nd1")
            stubPodStatus(api, V1Pod().status(V1PodStatus().phase("Pending")))
            val order = mutableListOf<String>()
            stubPodDelete(api, order)
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen) { order += "job ${it.method}" }, config.copy(podStartTimeoutSeconds = 1))

            assertEquals("PodStartTimeout", assertThrows<PodStartupException> { runner.openSession(params) }.reason)

            assertBackgroundJobDelete(seen)
            assertEquals(listOf("job POST", "job DELETE", "pod homer-7-job-p3nd1"), order)
        }

    @Test
    fun `a kill before the Job has a pod deletes the Job`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val polled = CompletableDeferred<Unit>()
            val list =
                mockk<CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true) {
                    every { labelSelector(any()) } returns this
                    every { execute() } answers { polled.complete(Unit).let { V1PodList() } }
                }
            every { api.listNamespacedPod("homer") } returns list
            stubJobEvents(api)
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen), config.copy(podStartTimeoutSeconds = 30))

            val opening = launch { runner.openSession(params) }
            polled.await()
            opening.cancelAndJoin()

            assertBackgroundJobDelete(seen)
            verify(exactly = 0) { api.deleteNamespacedPod(any(), any()) }
        }

    @Test
    fun `an unexpected error while a Job-mode session starts deletes the Job and its pod`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, "homer-7-job-e7r0r")
            val status =
                mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true) {
                    every { execute() } throws IllegalStateException("garbled pod status")
                }
            every { api.readNamespacedPodStatus(any(), any()) } returns status
            val seen = mutableListOf<Request>()

            assertThrows<IllegalStateException> { runner(api, jobApi(seen)).openSession(params) }

            assertBackgroundJobDelete(seen)
            verify { api.deleteNamespacedPod("homer-7-job-e7r0r", "homer") }
        }

    @Test
    fun `a Job name another Job holds fails startup without reading or deleting that Job`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val seen = mutableListOf<Request>()
            val batch = interceptedBatchV1Api(seen) { 409 to """{"message":"jobs.batch \"homer-7-x\" already exists"}""" }

            val thrown = assertThrows<PodStartupException> { runner(api, batch).openSession(params) }

            assertEquals("PodCreateFailed", thrown.reason)
            assertTrue(thrown.rawMessage.contains("already exists"), thrown.rawMessage)
            assertEquals(emptyList(), thrown.events)
            assertEquals(listOf("POST"), seen.map { it.method })
            verify(exactly = 0) { api.listNamespacedEvent(any()) }
        }

    @Test
    fun `a Job created without a selector or uid is deleted`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val seen = mutableListOf<Request>()
            val batch = interceptedBatchV1Api(seen) { if (it.method == "POST") 201 to JSON.serialize(job()) else 200 to "{}" }

            assertEquals("PodCreateFailed", assertThrows<PodStartupException> { runner(api, batch).openSession(params) }.reason)

            assertBackgroundJobDelete(seen)
        }

    @Test
    fun `a Job created with an answer the client cannot parse is deleted by its generated name`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val seen = mutableListOf<Request>()
            val unparseable = """{"metadata":{"name":"homer-7-job","uid":"uid-6"},"spec":{"template":{},"fieldFromANewerCluster":1}}"""
            val batch = interceptedBatchV1Api(seen) { if (it.method == "POST") 201 to unparseable else 200 to "{}" }

            assertThrows<IllegalArgumentException> { runner(api, batch).openSession(params) }

            assertEquals(listOf("POST", "DELETE"), seen.map { it.method })
            assertBackgroundJobDelete(seen)
            // The name Homer generated, not the "homer-7-job" of the unread answer.
            assertTrue(Regex(".*/jobs/homer-7-[a-z0-9]{6}").matches(seen.last().url.encodedPath), seen.last().url.toString())
        }

    @Test
    fun `a refused Job create still sends a tolerated Job delete`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val seen = mutableListOf<Request>()
            val batch = interceptedBatchV1Api(seen) { if (it.method == "DELETE") 404 to "{}" else 403 to """{"message":"forbidden"}""" }

            assertEquals("PodCreateFailed", assertThrows<PodStartupException> { runner(api, batch).openSession(params) }.reason)

            assertBackgroundJobDelete(seen)
        }

    @Test
    fun `a refused Job pod list deletes the Job`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val list =
                mockk<CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true) {
                    every { labelSelector(any()) } returns this
                    every { execute() } throws ApiException(403, "pods is forbidden")
                }
            every { api.listNamespacedPod(any()) } returns list
            val seen = mutableListOf<Request>()

            val thrown = assertThrows<PodStartupException> { runner(api, jobApi(seen)).openSession(params) }

            assertEquals("PodStatusUnreadable", thrown.reason)
            assertBackgroundJobDelete(seen)
        }

    @Test
    fun `a kill while a Job-mode session starts deletes the Job and its pod`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubJobPods(api, "homer-7-job-c4n3l")
            val polled = CompletableDeferred<Unit>()
            val status =
                mockk<CoreV1Api.APIreadNamespacedPodStatusRequest>(relaxed = true) {
                    every { execute() } answers { polled.complete(Unit).let { V1Pod().status(V1PodStatus().phase("Pending")) } }
                }
            every { api.readNamespacedPodStatus(any(), any()) } returns status
            val seen = mutableListOf<Request>()
            val runner = runner(api, jobApi(seen), config.copy(podStartTimeoutSeconds = 30))

            val opening = launch { runner.openSession(params) }
            polled.await()
            opening.cancelAndJoin()

            assertBackgroundJobDelete(seen)
            verify { api.deleteNamespacedPod("homer-7-job-c4n3l", "homer") }
        }

    private fun runner(
        api: CoreV1Api,
        batch: BatchV1Api,
        cfg: RunnerConfig.Kubernetes = config,
    ) = KubernetesRunner("k8s-dev", cfg, api, { _, _, _, _ -> mockk(relaxed = true) }, clock = { 0L }, batchApi = batch)

    /** A batch API answering the create with a Job carrying a uid, the read with [read], and the delete with `{}`. */
    private fun jobApi(
        seen: MutableList<Request>,
        read: V1Job = job(),
        onRequest: (Request) -> Unit = {},
    ): BatchV1Api =
        interceptedBatchV1Api(seen) {
            onRequest(it)
            when (it.method) {
                "POST" -> 201 to JSON.serialize(job().apply { metadata!!.uid("uid-6") })
                "DELETE" -> 200 to "{}"
                else -> 200 to JSON.serialize(read)
            }
        }

    /** Records each pod delete in [order] as `pod <name>`. */
    private fun stubPodDelete(
        api: CoreV1Api,
        order: MutableList<String>,
    ) {
        val name = slot<String>()
        val req =
            mockk<CoreV1Api.APIdeleteNamespacedPodRequest>(relaxed = true) {
                every { gracePeriodSeconds(any()) } returns this
                every { body(any()) } returns this
                every { execute() } answers {
                    order += "pod ${name.captured}"
                    V1Pod()
                }
            }
        every { api.deleteNamespacedPod(capture(name), "homer") } returns req
    }

    private fun job(vararg conditions: V1JobCondition): V1Job =
        V1Job()
            .metadata(V1ObjectMeta().name("homer-7-job"))
            .spec(V1JobSpec().template(V1PodTemplateSpec()))
            .status(V1JobStatus().conditions(conditions.toList().ifEmpty { null }))

    private fun assertBackgroundJobDelete(seen: List<Request>) {
        val delete = seen.singleOrNull { it.method == "DELETE" } ?: error("expected one Job DELETE, saw ${seen.map { it.method }}")
        assertTrue(delete.url.encodedPath.startsWith("/apis/batch/v1/namespaces/homer/jobs/homer-7-"), delete.url.toString())
        assertEquals("Background", delete.url.queryParameter("propagationPolicy"))
    }

    private fun failedCreate(message: String): CoreV1Event =
        CoreV1Event()
            .type("Warning")
            .reason("FailedCreate")
            .message(message)
            .lastTimestamp(OffsetDateTime.now())

    /** Stubs the event list with [events] on every call; returns the captured field selector. */
    private fun stubJobEvents(
        api: CoreV1Api,
        vararg events: CoreV1Event,
    ): CapturingSlot<String> {
        val selector = slot<String>()
        val req =
            mockk<CoreV1Api.APIlistNamespacedEventRequest>(relaxed = true) {
                every { fieldSelector(capture(selector)) } returns this
                every { execute() } returns CoreV1EventList().items(events.toList())
            }
        every { api.listNamespacedEvent(any()) } returns req
        return selector
    }

    /** Stubs the Job's pod list: [emptyPolls] empty answers, then [podName] (none when null). */
    private fun stubJobPods(
        api: CoreV1Api,
        podName: String?,
        emptyPolls: Int = 0,
    ) {
        val pods = listOfNotNull(podName).map { V1Pod().metadata(V1ObjectMeta().name(it)) }
        val req =
            mockk<CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true) {
                every { labelSelector(any()) } returns this
                every { execute() } returnsMany List(emptyPolls) { V1PodList() } + V1PodList().items(pods)
            }
        every { api.listNamespacedPod("homer") } returns req
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

    private fun readyPod(): V1Pod =
        V1Pod().status(
            V1PodStatus()
                .phase("Running")
                .containerStatuses(listOf(V1ContainerStatus().name(PodSpecBuilder.MAIN_CONTAINER_NAME).ready(true))),
        )
}
