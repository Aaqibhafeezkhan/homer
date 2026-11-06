package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1JobSpec
import io.kubernetes.client.openapi.models.V1LabelSelector
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.openapi.models.V1PodTemplateSpec
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobPodsTest {
    @Test
    fun `the selector is the Job's matchLabels, else the controller-uid label of its uid, else null`() {
        val labels = mapOf("controller-uid" to "u1", "job-name" to "j")
        assertEquals("controller-uid=u1,job-name=j", jobPodSelector(job(uid = "u0", matchLabels = labels)))
        assertEquals("batch.kubernetes.io/controller-uid=u2", jobPodSelector(job(uid = "u2", matchLabels = emptyMap())))
        assertNull(jobPodSelector(job(uid = null, matchLabels = null)))
    }

    @Test
    fun `a matchLabels selector parses back to exactly its labels`() =
        runTest {
            val key = Arb.pattern("[a-z0-9]([a-z0-9.-]{0,20}/)?[a-z0-9]{1,20}")
            checkAll(Arb.map(key, Arb.pattern("[A-Za-z0-9_.-]{0,20}"), minSize = 1, maxSize = 6)) { labels ->
                val parsed =
                    jobPodSelector(job(uid = "u", matchLabels = labels))!!
                        .split(",")
                        .associate { it.substringBefore("=") to it.substringAfter("=") }
                assertEquals(labels, parsed)
            }
        }

    @Test
    fun `findJobPod names the first listed pod, or null while there is none`() {
        assertEquals("p1", findJobPod(apiListing("p1", "p2"), "homer", "s=1"))
        assertNull(findJobPod(apiListing(), "homer", "s=1"))
    }

    @Test
    fun `a refused pod list fails the startup as PodStatusUnreadable`() {
        val api = mockk<CoreV1Api>()
        val req =
            mockk<CoreV1Api.APIlistNamespacedPodRequest> {
                every { labelSelector(any()) } returns this
                every { execute() } throws ApiException(403, "forbidden")
            }
        every { api.listNamespacedPod("homer") } returns req

        val e = assertThrows<PodStartupException> { findJobPod(api, "homer", "s=1") }

        assertEquals("PodStatusUnreadable", e.reason)
    }

    private fun job(
        uid: String?,
        matchLabels: Map<String, String>?,
    ) = V1Job()
        .metadata(V1ObjectMeta().name("j").uid(uid))
        .spec(V1JobSpec().selector(matchLabels?.let { V1LabelSelector().matchLabels(it) }).template(V1PodTemplateSpec()))

    private fun apiListing(vararg names: String): CoreV1Api {
        val api = mockk<CoreV1Api>()
        val req =
            mockk<CoreV1Api.APIlistNamespacedPodRequest> {
                every { labelSelector("s=1") } returns this
                every { execute() } returns V1PodList().items(names.map { V1Pod().metadata(V1ObjectMeta().name(it)) })
            }
        every { api.listNamespacedPod("homer") } returns req
        return api
    }
}
