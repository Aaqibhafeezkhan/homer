package homer.app.runners.kubernetes

import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.mockk.every
import io.mockk.mockk
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * keepWithTtl tests run against a real CoreV1Api short-circuited at the OkHttp
 * layer (see interceptedCoreV1Api): the patch's Content-Type header is the bug
 * surface (415 from the apiserver), and a mocked CoreV1Api can't see it.
 */
class PodCleanupTest {
    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    @Test
    fun `delete swallows 404`() {
        val api = mockk<CoreV1Api>(relaxed = true)
        val req =
            mockk<CoreV1Api.APIdeleteNamespacedPodRequest>(relaxed = true) {
                every { gracePeriodSeconds(any()) } returns this
                every { body(any()) } returns this
                every { execute() } throws ApiException(404, "not found")
            }
        every { api.deleteNamespacedPod(any(), any()) } returns req

        PodCleanup.delete(api, "homer", "pod-1") // must not throw
    }

    @Test
    fun `keepWithTtl sends the TTL patch as a JSON Patch, not application json`() {
        val seen = mutableListOf<Request>()

        PodCleanup.keepWithTtl(interceptedCoreV1Api(seen), "homer", "pod-1", ttlSeconds = 900)

        assertEquals(listOf("PATCH"), seen.map { it.method })
        val patch = seen.single()
        assertTrue(patch.url.encodedPath.endsWith("/namespaces/homer/pods/pod-1"), patch.url.toString())
        assertEquals(V1Patch.PATCH_FORMAT_JSON_PATCH, patch.body?.contentType()?.toString())
        val body = patch.bodyText()
        assertTrue(body.contains("\"/spec/activeDeadlineSeconds\",\"value\":900"), body)
        assertTrue(body.contains("\"/metadata/labels/homer.app~1kept\",\"value\":\"true\""), body)
        // Both ops must be "add", not "replace": activeDeadlineSeconds is never set on our
        // pods, and a "replace" against a missing field 422s the whole atomic patch, voiding
        // the kept label along with it.
        assertFalse(body.contains("\"op\":\"replace\""), body)
        assertEquals(2, Regex("\"op\":\"add\"").findAll(body).count(), body)
    }

    @Test
    fun `keepWithTtl deletes the pod when the apiserver rejects the patch`() {
        val seen = mutableListOf<Request>()
        val api = interceptedCoreV1Api(seen) { if (it.method == "PATCH") 415 else 200 }

        PodCleanup.keepWithTtl(api, "homer", "pod-1", ttlSeconds = 900) // must not throw

        val delete = seen.single { it.method == "DELETE" }
        assertTrue(delete.url.encodedPath.endsWith("/namespaces/homer/pods/pod-1"), delete.url.toString())
    }

    @Test
    fun `keepWithTtl does not fall back to delete when the patch 404s`() {
        val seen = mutableListOf<Request>()
        val api = interceptedCoreV1Api(seen) { if (it.method == "PATCH") 404 else 200 }

        PodCleanup.keepWithTtl(api, "homer", "pod-1", ttlSeconds = 900) // must not throw

        assertEquals(emptyList(), seen.filter { it.method == "DELETE" })
    }
}
