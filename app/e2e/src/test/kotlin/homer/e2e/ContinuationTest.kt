package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.Continuation
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessList
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuationTest : E2eTestBase() {
    @Test
    fun `homer-dispatch continuation resumes the target with the sub result`() =
        runBlocking {
            val originId = HomerApi.startRun("continuer")
            assertEquals("FINISHED", Awaits.awaitTerminal(originId).status, "the origin returns right after registering")

            val continuation =
                Awaits.awaitNotNull("continuation registered by process $originId") {
                    HomerApi.continuations().continuations.firstOrNull { it.originProcessId == originId }
                }
            assertEquals("continued", continuation.agentName)
            assertEquals("HMR_LG_RESUME", continuation.resumeParam)
            assertEquals("from-continuer", continuation.params["note"])

            // The watched sub is the fire-and-forget run started by homer-dispatch.
            val sub = Awaits.awaitTerminal(continuation.watchedProcessId)
            assertEquals("json-emitter", sub.agentName)
            assertEquals("FINISHED", sub.status)

            val fired =
                Awaits.awaitNotNull("continuation ${continuation.id} to fire after the sub finished") {
                    HomerApi
                        .continuations()
                        .continuations
                        .firstOrNull { it.id == continuation.id && it.status == "DISPATCHED" && it.firedProcessId != null }
                }

            val target = Awaits.awaitTerminal(fired.firedProcessId!!)
            assertEquals("continued", target.agentName)
            assertEquals("FINISHED", target.status)

            val stdout = HomerApi.artifact(target.id, "cmd_0.out").bodyAsText()
            assertTrue(stdout.contains("thread=thread-42"), "HMR_LG_THREAD must reach the resumed run: $stdout")
            assertTrue(stdout.contains("note=from-continuer"), "continue-params must reach the resumed run: $stdout")
            assertTrue(
                stdout.contains("forty-two"),
                "resume payload must carry the jsonPath-extracted sub result: $stdout",
            )
            assertTrue(stdout.contains("FINISHED"), "resume payload must carry the sub's terminal status: $stdout")

            // Record endpoints: fetchable by id; cancel conflicts once fired.
            val byId: Continuation = HomerApi.decode(E2e.client.get("/api/v1/continuations/${continuation.id}"))
            assertEquals("DISPATCHED", byId.status)
            val cancel = E2e.client.delete("/api/v1/continuations/${continuation.id}")
            assertEquals(HttpStatusCode.Conflict, cancel.status, "cancelling a fired continuation must 409")
        }

    @Test
    fun `a continuation fired back to its own agent inherits the origin run's inputs`() =
        runBlocking {
            // `continuation-inherit` resumes itself with `continuation.inheritInputs: true`,
            // so the resumed segment carries the registering run's subject even though the
            // continuation itself never passed it. Its `ticket` input is `required: true`,
            // so registration succeeds only because the origin run's stored inputs count as
            // provided. ADR-0101.
            val ticket = "e2e-inherit-${System.nanoTime()}"
            val originId = HomerApi.startRun("continuation-inherit", query = mapOf("ticket" to ticket))

            val continuation =
                Awaits.awaitNotNull("continuation registered by process $originId") {
                    HomerApi.continuations().continuations.firstOrNull { it.originProcessId == originId }
                }
            assertEquals("continuation-inherit", continuation.agentName)
            assertEquals("HMR_LG_RESUME", continuation.resumeParam)

            // The watched sub is the fire-and-forget run started by homer-dispatch.
            val sub = Awaits.awaitTerminal(continuation.watchedProcessId)
            assertEquals("continuation-inherit", sub.agentName)
            assertEquals("FINISHED", sub.status)

            val fired =
                Awaits.awaitNotNull("continuation ${continuation.id} to fire after the sub finished") {
                    HomerApi
                        .continuations()
                        .continuations
                        .firstOrNull { it.id == continuation.id && it.status == "DISPATCHED" && it.firedProcessId != null }
                }
            val resumed = Awaits.awaitTerminal(fired.firedProcessId!!)
            assertEquals("FINISHED", resumed.status, "the resumed segment must run: ${resumed.failureReason}")

            // The subject arrives by inheritance alone — no param of the continuation carries it.
            assertEquals(ticket, resumed.inputParams["TICKET_ID"], "the resumed segment must inherit the subject input")
            assertTrue(
                resumed.tags.contains("ticket:$ticket"),
                "the fromInputs tag must be derived from the inherited input: ${resumed.tags}",
            )

            // Both segments are findable by the subject tag.
            val matched: ProcessList = HomerApi.decode(HomerApi.statusAll(mapOf("tag" to "ticket:$ticket")))
            assertTrue(matched.processes.any { it.id == originId }, "the origin run carries the tag: $matched")
            assertTrue(matched.processes.any { it.id == resumed.id }, "the resumed segment carries the tag: $matched")

            // The inherited input reached the segment's own environment, and the
            // continuation's own params still won over the origin run's stored ones.
            val hold = resumed.executions[1]
            assertFalse(hold.skipped, "the hold command must run in the resumed segment: ${resumed.executions}")
            val stdout = HomerApi.artifactText(resumed.id, hold.stdOut, "cmd_1.out")
            assertTrue(stdout.contains("ticket=$ticket"), "the inherited input must reach the command env: $stdout")
            assertTrue(stdout.contains("holding 6s"), "the continuation's own param must win over the stored one: $stdout")
        }

    @Test
    fun `a resumed segment holds the origin subject's lock key`() =
        runBlocking {
            // The fixture's lock key is `ticket-{{TICKET_ID}}`, so a resumed segment that
            // renders it with the inherited subject holds the same key the origin run did —
            // which is what makes a fresh run for the same subject skip rather than stack.
            // ADR-0101.
            val ticket = "e2e-key-${System.nanoTime()}"
            val originId = HomerApi.startRun("continuation-inherit", query = mapOf("ticket" to ticket))

            val continuation =
                Awaits.awaitNotNull("continuation registered by process $originId") {
                    HomerApi.continuations().continuations.firstOrNull { it.originProcessId == originId }
                }
            val fired =
                Awaits.awaitNotNull("continuation ${continuation.id} to fire") {
                    HomerApi
                        .continuations()
                        .continuations
                        .firstOrNull { it.id == continuation.id && it.status == "DISPATCHED" && it.firedProcessId != null }
                }
            val resumedId = fired.firedProcessId!!

            // WORKING is written after the lock is acquired, and the resumed segment holds
            // it for 6s (the continuation's own holdSec), so this is a stable window.
            Awaits.awaitStatus(resumedId, setOf("WORKING"))

            val colliding =
                HomerApi.startRun(
                    "continuation-inherit",
                    query = mapOf("ticket" to ticket, "dispatchSub" to "false"),
                )
            val skipped = Awaits.awaitTerminal(colliding)
            assertEquals("FAILED", skipped.status, "a run for the same subject must not stack on the resumed segment")
            val reason =
                Awaits.awaitNotNull("the skipped run's failure reason") {
                    Awaits.awaitTerminal(colliding).failureReason
                }
            assertTrue(reason.contains("SKIP_CURRENT"), "skipped run must say why: $reason")
            assertTrue(reason.contains("(run #$resumedId)"), "the holder must be the resumed segment: $reason")

            // A run for a *different* subject must not be affected. Together the two
            // halves pin the key to the inherited value: an unrendered template would
            // leave the resumed segment on the literal `ticket-{{TICKET_ID}}`, which no
            // fresh run (every one of them renders its own subject) could ever collide
            // with, while two segments of different subjects would collide with each other.
            val other =
                HomerApi.startRun(
                    "continuation-inherit",
                    query = mapOf("ticket" to "$ticket-other", "dispatchSub" to "false"),
                )
            assertEquals("FINISHED", Awaits.awaitTerminal(other).status)

            val holder = Awaits.awaitTerminal(resumedId)
            assertEquals("FINISHED", holder.status, "the skipped run must not disturb the holder: ${holder.failureReason}")
        }
}
