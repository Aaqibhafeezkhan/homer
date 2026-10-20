package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.Continuation
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
