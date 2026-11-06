package homer.app.runners.kubernetes

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kubernetes.client.openapi.apis.CoreV1Api
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobStartupCheckTest {
    private val job = """{"metadata":{"name":"j"},"spec":{"template":{}}}"""

    private fun event(
        reason: String,
        message: String?,
        at: String,
    ): String {
        val msg = message?.let { ""","message":${io.kubernetes.client.openapi.JSON.serialize(it)}""" }.orEmpty()
        return """{"metadata":{"name":"e"},"involvedObject":{"name":"j"},"type":"Warning","reason":"$reason"$msg,"lastTimestamp":"$at"}"""
    }

    private fun check(
        vararg events: String,
        jobJson: String = job,
        eventsCode: Int = 200,
    ): JobStartupCheck {
        val core = CoreV1Api(interceptedApiClient(mutableListOf()) { eventsCode to """{"items":[${events.joinToString(",")}]}""" })
        return JobStartupCheck(core, interceptedBatchV1Api(mutableListOf()) { 200 to jobJson }, "ns", "j")
    }

    @Test
    fun `nothing wrong yet keeps waiting with the plain timeout message`() {
        val check = check(event("SuccessfulCreate", "created", "2026-01-01T00:00:01Z"))

        assertNull(check.failure())
        assertEquals("Job j had no ready pod within 5s (elapsed=5s)", check.timeoutMessage(5, 5))
    }

    @Test
    fun `the newest FailedCreate decides`() {
        val admission = event("FailedCreate", "violates PodSecurity", "2026-01-01T00:00:01Z")
        val quota = event("FailedCreate", "exceeded quota: q", "2026-01-01T00:00:09Z")

        val check = check(quota, admission)

        assertNull(check.failure())
        assertEquals("Job j had no ready pod within 5s (elapsed=6s); last FailedCreate: exceeded quota: q", check.timeoutMessage(5, 6))
        assertEquals("JobFailedCreate" to "violates PodSecurity", check(admission.replace(":01Z", ":10Z"), quota).failure())
    }

    @Test
    fun `a FailedCreate without a message still fails`() {
        assertEquals(
            "JobFailedCreate" to "FailedCreate without a message",
            check(event("FailedCreate", null, "2026-01-01T00:00:01Z")).failure(),
        )
    }

    @Test
    fun `a Failed condition wins, its reason standing in for a missing message`() {
        val failed =
            """{"metadata":{"name":"j"},"spec":{"template":{}},""" +
                """"status":{"conditions":[{"type":"Failed","status":"True","reason":"X"}]}}"""

        assertEquals("JobFailed" to "X", check(jobJson = failed).failure())
    }

    @Test
    fun `unreadable events keep waiting`() {
        assertNull(check(eventsCode = 403).failure())
    }

    @Test
    fun `a FailedCreate fails startup exactly when its message names no quota`() =
        runTest {
            checkAll(Arb.string(0..80)) { text ->
                val quotaMessage = "pods is forbidden: exceeded quota: $text"
                assertNull(check(event("FailedCreate", quotaMessage, "2026-01-01T00:00:01Z")).failure())
                val other = generateSequence(text) { it.replace("exceeded quota", "") }.first { "exceeded quota" !in it }
                val expected = other.ifBlank { "FailedCreate without a message" }
                assertEquals("JobFailedCreate" to expected, check(event("FailedCreate", other, "2026-01-01T00:00:01Z")).failure())
            }
        }
}
