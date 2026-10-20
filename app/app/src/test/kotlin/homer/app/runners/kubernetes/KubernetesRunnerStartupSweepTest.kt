package homer.app.runners.kubernetes

import homer.app.fixtures.PersistenceTestSupport
import homer.app.models.ResourceSpec
import homer.app.models.RunnerConfig
import homer.app.runners.RunnerRegistry
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KubernetesRunnerStartupSweepTest {
    private val config =
        RunnerConfig.Kubernetes(
            namespace = "homer",
            image = "ubuntu:24.04",
            resources = ResourceSpec(),
        )

    @Test
    fun `sweep deletes orphan pods and skips active ones`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val pm = PersistenceTestSupport.processManager(logger = LoggerFactory.getLogger("pm-test"))
            val activePid = pm.obtainProcessId("demo") // CREATED → counts as active
            stubList(
                api,
                listOf(
                    pod("homer-$activePid-abc", processId = activePid),
                    pod("homer-99-def", processId = 99),
                ),
            )
            val deletedNames = mutableListOf<String>()
            stubDelete(api, deletedNames)

            val runner = KubernetesRunner("k8s", config, api, dummyExec())
            val registry = RunnerRegistry(listOf(runner))
            val status = SweepStatus()

            // Milliseconds from the clock are truncated to epoch seconds for `ranAt`.
            KubernetesRunnerStartupSweep(registry, pm, status, clock = { 42_000L }).sweep()

            assertEquals(listOf("homer-99-def"), deletedNames)
            val snap = status.get()!!
            assertEquals(42L, snap.ranAt)
            assertEquals(2, snap.scanned)
            assertEquals(1, snap.deleted.size)
            assertEquals("homer-99-def", snap.deleted.first().name)
            assertEquals(99, snap.deleted.first().processId)
            assertTrue(snap.errors.isEmpty())
        }

    @Test
    fun `sweep deletes pod with missing process-id label`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubList(api, listOf(pod("homer-stray", processId = null)))
            val deleted = mutableListOf<String>()
            stubDelete(api, deleted)

            val runner = KubernetesRunner("k8s", config, api, dummyExec())
            val registry = RunnerRegistry(listOf(runner))
            val pm = PersistenceTestSupport.processManager(logger = LoggerFactory.getLogger("pm-test"))
            val status = SweepStatus()

            KubernetesRunnerStartupSweep(registry, pm, status).sweep()

            assertEquals(listOf("homer-stray"), deleted)
        }

    @Test
    fun `sweep records errors but continues on list failure`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val listReq =
                mockk<CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true) {
                    every { labelSelector(any()) } returns this
                    every { execute() } throws ApiException(500, "boom")
                }
            every { api.listNamespacedPod(any()) } returns listReq

            val runner = KubernetesRunner("k8s", config, api, dummyExec())
            val registry = RunnerRegistry(listOf(runner))
            val pm = PersistenceTestSupport.processManager(logger = LoggerFactory.getLogger("pm-test"))
            val status = SweepStatus()

            KubernetesRunnerStartupSweep(registry, pm, status).sweep()

            val snap = status.get()!!
            assertEquals(0, snap.scanned)
            assertTrue(snap.errors.isNotEmpty())
            assertTrue(snap.errors.first().contains("list pods failed"))
        }

    @Test
    fun `sweep is a no-op when no kubernetes runners are registered`() =
        runTest {
            val registry =
                RunnerRegistry(
                    listOf(
                        homer.app.runners.local
                            .LocalRunner(),
                    ),
                )
            val pm = PersistenceTestSupport.processManager(logger = LoggerFactory.getLogger("pm-test"))
            val status = SweepStatus()

            KubernetesRunnerStartupSweep(registry, pm, status).sweep()

            val snap = status.get()!!
            assertEquals(0, snap.scanned)
            assertTrue(snap.deleted.isEmpty())
        }

    @Test
    fun `sweep ignores 404 deletes without error`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubList(api, listOf(pod("homer-gone", processId = 99)))
            val deleteReq =
                mockk<CoreV1Api.APIdeleteNamespacedPodRequest>(relaxed = true) {
                    every { gracePeriodSeconds(any()) } returns this
                    every { execute() } throws ApiException(404, "not found")
                }
            every { api.deleteNamespacedPod(any(), any()) } returns deleteReq

            val runner = KubernetesRunner("k8s", config, api, dummyExec())
            val registry = RunnerRegistry(listOf(runner))
            val pm = PersistenceTestSupport.processManager(logger = LoggerFactory.getLogger("pm-test"))
            val status = SweepStatus()

            KubernetesRunnerStartupSweep(registry, pm, status).sweep()

            val snap = status.get()!!
            assertTrue(snap.errors.isEmpty())
            assertTrue(snap.deleted.isEmpty())
        }

    @Test
    fun `sweep skips a kept-for-debug pod even when its process is not active`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            stubList(api, listOf(pod("homer-kept-abc", processId = 99, kept = true)))
            val deleted = mutableListOf<String>()
            stubDelete(api, deleted)

            val runner = KubernetesRunner("k8s", config, api, dummyExec())
            val registry = RunnerRegistry(listOf(runner))
            val pm = PersistenceTestSupport.processManager(logger = LoggerFactory.getLogger("pm-test"))
            val status = SweepStatus()

            KubernetesRunnerStartupSweep(registry, pm, status).sweep()

            assertTrue(deleted.isEmpty())
            val snap = status.get()!!
            assertEquals(1, snap.scanned)
            assertTrue(snap.deleted.isEmpty())
            assertTrue(snap.errors.isEmpty())
        }

    @Test
    fun `sweep scopes the list selector to this instance when instanceId is set`() =
        runTest {
            val api = mockk<CoreV1Api>(relaxed = true)
            val selector = slot<String>()
            val listReq =
                mockk<CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true) {
                    every { labelSelector(capture(selector)) } returns this
                    every { execute() } returns V1PodList().items(emptyList())
                }
            every { api.listNamespacedPod(any()) } returns listReq

            val runner = KubernetesRunner("k8s", config, api, dummyExec(), instanceId = "inst-xyz")
            val registry = RunnerRegistry(listOf(runner))
            val pm = PersistenceTestSupport.processManager(logger = LoggerFactory.getLogger("pm-test"))
            val status = SweepStatus()

            KubernetesRunnerStartupSweep(registry, pm, status).sweep()

            // Scoped to this instance: another instance's pods (different id) never match.
            assertTrue(selector.captured.contains("${PodLabels.MANAGED_BY}=${PodLabels.MANAGED_BY_VALUE}"))
            assertTrue(selector.captured.contains("${PodLabels.INSTANCE}=inst-xyz"))
        }

    private fun dummyExec(): KubernetesExecClient = KubernetesExecClient { _, _, _, _ -> mockk(relaxed = true) }

    private fun pod(
        name: String,
        processId: Int?,
        kept: Boolean = false,
    ): V1Pod =
        V1Pod().metadata(
            V1ObjectMeta()
                .name(name)
                .labels(
                    buildMap {
                        put(PodLabels.MANAGED_BY, PodLabels.MANAGED_BY_VALUE)
                        put(PodLabels.RUNNER, "k8s")
                        if (processId != null) put(PodLabels.PROCESS_ID, processId.toString())
                        if (kept) put(PodLabels.KEPT, "true")
                    },
                ),
        )

    private fun stubList(
        api: CoreV1Api,
        pods: List<V1Pod>,
    ) {
        val list = V1PodList().items(pods)
        val listReq =
            mockk<CoreV1Api.APIlistNamespacedPodRequest>(relaxed = true) {
                every { labelSelector(any()) } returns this
                every { execute() } returns list
            }
        every { api.listNamespacedPod(any()) } returns listReq
    }

    private fun stubDelete(
        api: CoreV1Api,
        captured: MutableList<String>,
    ) {
        val nameSlot = slot<String>()
        val deleteReq =
            mockk<CoreV1Api.APIdeleteNamespacedPodRequest>(relaxed = true) {
                every { gracePeriodSeconds(any()) } returns this
                every { execute() } returns V1Pod()
            }
        every { api.deleteNamespacedPod(capture(nameSlot), any()) } answers {
            captured.add(nameSlot.captured)
            deleteReq
        }
    }
}
