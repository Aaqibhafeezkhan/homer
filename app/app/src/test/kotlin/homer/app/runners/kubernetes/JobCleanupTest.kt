package homer.app.runners.kubernetes

import okhttp3.Request
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
}
