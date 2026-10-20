package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DispatchTest : E2eTestBase() {
    @Test
    fun `ask-and-dispatch fires the target agent with the operator answer`() =
        runBlocking {
            val askerId = HomerApi.startRun("dispatcher")
            // The dispatcher registers and returns — the dispatch outlives the run.
            assertEquals("FINISHED", Awaits.awaitTerminal(askerId).status)

            val dispatch =
                Awaits.awaitNotNull("PENDING dispatch registered by process $askerId") {
                    HomerApi.dispatches(status = "PENDING").dispatches.firstOrNull { it.originProcessId == askerId }
                }
            assertEquals("echo", dispatch.agentName)
            assertEquals("message", dispatch.answerParam)

            val question =
                Awaits.awaitNotNull("dispatch-bound question ${dispatch.questionId}") {
                    HomerApi
                        .questions(processId = askerId)
                        .questions
                        .firstOrNull { it.id == dispatch.questionId }
                }
            assertEquals("OPEN", question.status, "dispatch-bound question must survive the asker's terminal state")
            assertNotNull(question.dispatch, "question must carry its dispatch annotation")

            assertEquals(HttpStatusCode.OK, HomerApi.answer(question.id, "ping").status)

            val fired =
                Awaits.awaitNotNull("dispatch ${dispatch.id} to fire") {
                    HomerApi
                        .dispatches()
                        .dispatches
                        .firstOrNull { it.id == dispatch.id && it.status == "DISPATCHED" && it.dispatchedProcessId != null }
                }
            val target = Awaits.awaitTerminal(fired.dispatchedProcessId!!)
            assertEquals("echo", target.agentName)
            assertEquals("FINISHED", target.status)
            assertTrue(
                HomerApi.artifact(target.id, "cmd_0.out").bodyAsText().contains("got: ping"),
                "the operator answer must arrive as the target's input",
            )
        }

    @Test
    fun `question, dispatch and continuation listings respond with their documented paginated shapes`() =
        runBlocking {
            val listings =
                listOf("/api/v1/questions" to "questions", "/api/v1/dispatches" to "dispatches", "/api/v1/continuations" to "continuations")
            for ((path, key) in listings) {
                val response = E2e.client.get("$path?limit=1")
                assertEquals(HttpStatusCode.OK, response.status, path)
                val body = E2e.json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertTrue(body.containsKey(key), "$path: $body")
                assertTrue(body.getValue(key).jsonArray.size <= 1, "$path must honour limit=1: $body")
                assertEquals(1, body.getValue("limit").jsonPrimitive.int, path)
                assertEquals(0, body.getValue("offset").jsonPrimitive.int, path)
                assertTrue(body.getValue("total").jsonPrimitive.int >= body.getValue(key).jsonArray.size, "$path: $body")

                assertEquals(HttpStatusCode.BadRequest, E2e.client.get("$path?limit=1001").status, "$path caps limit at 1000")
                assertEquals(HttpStatusCode.BadRequest, E2e.client.get("$path?offset=-1").status, "$path rejects a negative offset")
            }
        }
}
