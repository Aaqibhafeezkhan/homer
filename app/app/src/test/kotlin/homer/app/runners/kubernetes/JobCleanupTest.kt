package homer.app.runners.kubernetes

import io.kubernetes.client.custom.V1Patch
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class JobCleanupTest {
    @Test
    fun `delete sends one DELETE with Background propagation`() {
        val seen = mutableListOf<Request>()

        JobCleanup.delete(interceptedBatchV1Api(seen), "homer", "homer-7-abc123")

        val delete = seen.single()
        assertEquals("DELETE", delete.method)
        assertEquals("/apis/batch/v1/namespaces/homer/jobs/homer-7-abc123", delete.url.encodedPath)
        assertEquals("Background", delete.url.queryParameter("propagationPolicy"))
    }

    @Test
    fun `delete accepts the Job itself as the answer`() {
        val job = """{"apiVersion":"batch/v1","kind":"Job","metadata":{"name":"j"},"spec":{"template":{}}}"""

        JobCleanup.delete(interceptedBatchV1Api(mutableListOf()) { 200 to job }, "homer", "j") // must not throw
    }

    @Test
    fun `delete swallows 404 and a refusal`() {
        JobCleanup.delete(interceptedBatchV1Api(mutableListOf()) { 404 to "{}" }, "homer", "gone")
        JobCleanup.delete(interceptedBatchV1Api(mutableListOf()) { 403 to """{"message":"forbidden"}""" }, "homer", "j")
    }

    @Test
    fun `keep labels the Job kept with a JSON Patch`() {
        val seen = mutableListOf<Request>()

        JobCleanup.keep(interceptedBatchV1Api(seen), "homer", "homer-7-abc123")

        val patch = seen.single()
        assertEquals("PATCH", patch.method)
        assertEquals("/apis/batch/v1/namespaces/homer/jobs/homer-7-abc123", patch.url.encodedPath)
        assertEquals(V1Patch.PATCH_FORMAT_JSON_PATCH, patch.body?.contentType()?.toString())
        val body = Buffer().also { patch.body!!.writeTo(it) }.readUtf8()
        assertEquals("""[{"op":"add","path":"/metadata/labels/homer.app~1kept","value":"true"}]""", body)
    }

    @Test
    fun `keep accepts the Job itself as the answer and swallows a refusal`() {
        val job = """{"apiVersion":"batch/v1","kind":"Job","metadata":{"name":"j"},"spec":{"template":{}},"futureField":1}"""

        JobCleanup.keep(interceptedBatchV1Api(mutableListOf()) { 200 to job }, "homer", "j") // must not throw
        JobCleanup.keep(interceptedBatchV1Api(mutableListOf()) { 404 to "{}" }, "homer", "gone")
        JobCleanup.keep(interceptedBatchV1Api(mutableListOf()) { 403 to """{"message":"forbidden"}""" }, "homer", "j")
    }
}
