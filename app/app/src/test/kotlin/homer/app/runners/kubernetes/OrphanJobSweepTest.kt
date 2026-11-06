package homer.app.runners.kubernetes

import okhttp3.Request
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrphanJobSweepTest {
    private val pods =
        KubernetesRunner.SweepResult(
            scanned = 1,
            deleted = listOf(SweepStatus.DeletedPod("homer", "homer-5-pod", 5, "k8s")),
            errors = listOf("pod error"),
        )

    @Test
    fun `a refused Job delete is an error and a Job already gone is not`() {
        val seen = mutableListOf<Request>()
        val batch =
            interceptedBatchV1Api(seen) {
                when {
                    it.method == "GET" -> 200 to jobs("homer-99-a", "homer-98-b")
                    it.url.encodedPath.endsWith("homer-99-a") -> 500 to """{"message":"boom"}"""
                    else -> 404 to "{}"
                }
            }

        val result = OrphanJobSweep(batch, "homer", "").sweep(emptySet(), pods)

        assertEquals(2, seen.count { it.method == "DELETE" })
        assertEquals(emptyList(), result.deletedJobs)
        assertEquals(pods.deleted, result.deleted)
        assertEquals(2, result.errors.size)
        assertEquals("pod error", result.errors.first())
        assertTrue(result.errors.last().startsWith("delete Job homer-99-a failed: 500"), result.errors.last())
        assertEquals(
            "${PodLabels.MANAGED_BY}=${PodLabels.MANAGED_BY_VALUE}",
            seen.first().url.queryParameter("labelSelector"),
            "a blank instance id keeps the namespace-wide selector",
        )
    }

    @Test
    fun `a Job list the client cannot read is an error, not a throw`() {
        val unknownField = """{"items":[{"metadata":{"name":"j"},"spec":{"template":{}},"futureField":1}]}"""
        val batch = interceptedBatchV1Api(mutableListOf()) { 200 to unknownField }

        val result = OrphanJobSweep(batch, "homer", "").sweep(emptySet(), pods)

        assertEquals(emptyList(), result.deletedJobs)
        assertEquals(pods.deleted, result.deleted)
        assertTrue(result.errors.last().startsWith("list jobs failed:"), result.errors.last())
    }

    private fun jobs(vararg names: String): String =
        names.joinToString(",", """{"items":[""", "]}") { name ->
            val pid = name.split("-")[1]
            """{"metadata":{"name":"$name","labels":{"${PodLabels.PROCESS_ID}":"$pid"}},"spec":{"template":{}}}"""
        }
}
