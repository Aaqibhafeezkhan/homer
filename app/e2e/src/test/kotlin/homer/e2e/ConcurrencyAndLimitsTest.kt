package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.ErrorBody
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessList
import homer.e2e.support.RunResponse
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrencyAndLimitsTest : E2eTestBase() {
    @Test
    fun `WAIT lock serializes two concurrent runs`() =
        runBlocking {
            val firstId = HomerApi.startRun("lock-wait")
            Awaits.awaitStatus(firstId, setOf("WORKING"))
            val secondId = HomerApi.startRun("lock-wait")

            val first = Awaits.awaitTerminal(firstId)
            val second = Awaits.awaitTerminal(secondId, timeoutMs = 40_000)
            assertEquals("FINISHED", first.status)
            assertEquals("FINISHED", second.status)

            // Homer's own execution timestamps (unix seconds) prove serialization:
            // the waiter's command may not start before the holder's command ended.
            val firstEnd = first.executions[0].end
            val secondStart = second.executions[0].start
            assertTrue(
                secondStart >= firstEnd,
                "WAIT run overlapped the lock holder: first ended at $firstEnd, second started at $secondStart",
            )
        }

    @Test
    fun `SKIP_CURRENT fails the second run and leaves the holder running`() =
        runBlocking {
            val firstId = HomerApi.startRun("lock-skip")
            Awaits.awaitStatus(firstId, setOf("WORKING"))
            val secondId = HomerApi.startRun("lock-skip")

            // The collision is detected inside the async execution, so the HTTP
            // response was 200 — the skip surfaces as a FAILED process.
            val second = Awaits.awaitTerminal(secondId)
            assertEquals("FAILED", second.status)

            // The reason says why it never started. This client is admin, so the id of
            // the run that held the lock is appended; a caller who may not open that run
            // gets the same text without it. The reason is stored in a second transaction
            // after the FAILED status commits, so the poll that first sees the status can
            // still read a null reason — await the reason itself.
            val reason =
                Awaits.awaitNotNull("the skipped run's failure reason") {
                    Awaits.awaitTerminal(secondId).failureReason
                }
            assertTrue(reason.contains("SKIP_CURRENT"), "skipped run must say why: $reason")
            assertTrue(reason.contains("(run #$firstId)"), "the reason must name the holder: $reason")

            val first = Awaits.awaitTerminal(firstId)
            assertEquals("FINISHED", first.status, "the skipped run must not disturb the holder")
            assertEquals(null, first.failureReason, "a run that never failed carries no reason")
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT kills the holder and completes the new run`() =
        runBlocking {
            val firstId = HomerApi.startRun("lock-kill")
            Awaits.awaitStatus(firstId, setOf("WORKING"))
            val secondId = HomerApi.startRun("lock-kill")

            val first = Awaits.awaitTerminal(firstId)
            assertEquals("KILLED", first.status)
            val second = Awaits.awaitTerminal(secondId, timeoutMs = 40_000)
            assertEquals("FINISHED", second.status)
        }

    @Test
    fun `maxParallelRuns REJECT fails the overflow run`() =
        runBlocking {
            val firstId = HomerApi.startRun("parallel-reject")
            Awaits.awaitStatus(firstId, setOf("WORKING"))
            val secondId = HomerApi.startRun("parallel-reject")

            // Slot rejection happens inside the async execution: HTTP was 200,
            // the overflow run fails without ever running its command.
            val second = Awaits.awaitTerminal(secondId)
            assertEquals("FAILED", second.status)
            assertTrue(second.executions.isEmpty(), "rejected run must not execute commands: ${second.executions}")
            // Awaited, not read off the terminal poll: the reason write trails the FAILED status.
            val reason =
                Awaits.awaitNotNull("the rejected run's failure reason") {
                    Awaits.awaitTerminal(secondId).failureReason
                }
            assertTrue(
                reason.contains("maxParallelRuns"),
                "rejected run must say why it never started: $reason",
            )

            assertEquals("FINISHED", Awaits.awaitTerminal(firstId).status)
        }

    @Test
    fun `a SKIP_CURRENT skip does not arm the afterFailure cooldown`() =
        runBlocking {
            // A duplicate correctly dropped by the lock ends FAILED without ever running,
            // and that row must not back the agent off — including for a start over
            // another subject, which used to be refused with 429 for the whole window.
            val firstId = HomerApi.startRun("cooldown-lock-skip", query = mapOf("subject" to "a"))
            Awaits.awaitStatus(firstId, setOf("WORKING"))

            val skippedId = HomerApi.startRun("cooldown-lock-skip", query = mapOf("subject" to "a"))
            assertEquals("FAILED", Awaits.awaitTerminal(skippedId).status)
            val reason =
                Awaits.awaitNotNull("the skipped run's failure reason") {
                    Awaits.awaitTerminal(skippedId).failureReason
                }
            assertTrue(reason.contains("SKIP_CURRENT"), "the skipped run must say why: $reason")

            // afterFailure is 1h on this fixture; the skip must not have armed it.
            val other = HomerApi.run("cooldown-lock-skip", query = mapOf("subject" to "b"))
            assertEquals(
                HttpStatusCode.OK,
                other.status,
                "a skipped duplicate must not refuse another subject: ${other.bodyAsText()}",
            )
            Awaits.awaitStatus(HomerApi.decode<RunResponse>(other).processId, setOf("WORKING"))
        }

    @Test
    fun `request body over the configured cap is rejected with 413`() =
        runBlocking {
            // Target an agent that actually receives its body (`inputs` declares
            // body inputs) — the limit fires on receive, not on Content-Length.
            val oversized = "x".repeat(300 * 1024)
            val response =
                E2e.client.post("/api/v1/agent/inputs") {
                    parameter("n", "1")
                    setBody(TextContent("""{"service":{"id":"$oversized"}}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    // Cooldown on the debug endpoint (ADR-0051 amendment): `minInterval` is a pacing
    // bound on every start, `afterFailure` does not gate a manual investigation.
    // Debug bodies need no inputs, so the requests carry an empty body object.

    @Test
    fun `minInterval refuses a second debug start with 429`() =
        runBlocking {
            // The first debug is admitted and opens the window; the second is inside it.
            val first = debugResponse("cooldown-debug")
            assertEquals(HttpStatusCode.OK, first.status, "the first debug must be admitted")

            val second = debugResponse("cooldown-debug")
            assertEquals(HttpStatusCode.TooManyRequests, second.status, "debug inside minInterval must be refused")
        }

    @Test
    fun `afterFailure does not gate a debug start right after a failure`() =
        runBlocking {
            val failedId = HomerApi.startRun("cooldown-fail-debug")
            assertEquals("FAILED", Awaits.awaitTerminal(failedId).status)

            // Debug investigates a failure; the afterFailure window (1h, opened by the
            // failure just above) must not refuse it.
            val response = debugResponse("cooldown-fail-debug")
            assertEquals(HttpStatusCode.OK, response.status, "a manual debug run must not be held back by afterFailure")
        }

    // cooldown.key (ADR-0103): both windows are counted per rendered subject, and a
    // key that cannot be rendered refuses the start instead of sharing one window.

    @Test
    fun `a keyed afterFailure refuses the same subject and admits another`() =
        runBlocking {
            val failedId = HomerApi.startRun("cooldown-key", query = mapOf("mr" to "1"))
            assertEquals("FAILED", Awaits.awaitTerminal(failedId).status)

            // The failure armed afterFailure (1h) for mr-1 only.
            val sameSubject = HomerApi.run("cooldown-key", query = mapOf("mr" to "1"))
            assertEquals(
                HttpStatusCode.TooManyRequests,
                sameSubject.status,
                "a keyed afterFailure must refuse the subject that failed: ${sameSubject.bodyAsText()}",
            )

            val otherSubject = HomerApi.run("cooldown-key", query = mapOf("mr" to "2"))
            assertEquals(
                HttpStatusCode.OK,
                otherSubject.status,
                "another subject must not be held back: ${otherSubject.bodyAsText()}",
            )
            Awaits.awaitTerminal(HomerApi.decode<RunResponse>(otherSubject).processId)

            // Neither refusal left a row behind: the failed run and the admitted mr-2 run.
            val list: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("agentName" to "cooldown-key")))
            assertEquals(2, list.total, "a refused keyed start must not create a run")
        }

    @Test
    fun `an unrenderable cooldown key is refused with 400 and creates no run`() =
        runBlocking {
            // `mr` is optional, so the request resolves; `{{MR}}` then has nothing to
            // render from and the start must fail closed rather than run under a key
            // every subject would share.
            val response = HomerApi.run("cooldown-key")
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "an unresolved cooldown key must be refused: ${response.bodyAsText()}",
            )
            val error = E2e.json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertTrue(error.msg.contains("MR"), "the refusal should name the placeholder: ${error.msg}")

            val list: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("agentName" to "cooldown-key")))
            assertEquals(0, list.total, "a refused key must not create a run")
        }

    private suspend fun debugResponse(agent: String): HttpResponse =
        E2e.client.post("/api/v1/agents/$agent/debug") {
            contentType(ContentType.Application.Json)
            setBody("""{"commandIndex":0,"env":{},"queryParams":{},"headers":{}}""")
        }
}
