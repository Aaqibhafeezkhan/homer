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
import kotlin.test.assertFalse
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
    fun `ask-and-dispatch to the asking agent inherits the origin run's inputs`() =
        runBlocking {
            // `continuation-inherit` opts into inheritance, so the dispatched run gets the
            // asking run's subject — its fromInputs tag and its lock key — and the asking
            // run's secret input stays masked, even though the dispatch record carries
            // neither. ADR-0101.
            val ticket = "e2e-dispatch-${System.nanoTime()}"
            val secret = "dispatch-secret-${System.nanoTime()}"
            val askerId =
                HomerApi.startRun(
                    "continuation-inherit",
                    query =
                        mapOf(
                            "ticket" to ticket,
                            "token" to secret,
                            "askDispatch" to "true",
                            "dispatchSub" to "false",
                        ),
                )
            assertEquals("FINISHED", Awaits.awaitTerminal(askerId).status)

            val dispatch =
                Awaits.awaitNotNull("PENDING dispatch registered by process $askerId") {
                    HomerApi.dispatches(status = "PENDING").dispatches.firstOrNull { it.originProcessId == askerId }
                }
            assertEquals("continuation-inherit", dispatch.agentName)
            assertEquals("note", dispatch.answerParam)

            // The asking run's secret input is not part of the dispatch record at all.
            val rawDispatch = E2e.client.get("/api/v1/dispatches/${dispatch.id}").bodyAsText()
            assertFalse(rawDispatch.contains(secret), "the dispatch record must not carry the origin run's secret: $rawDispatch")

            val question =
                Awaits.awaitNotNull("dispatch-bound question ${dispatch.questionId}") {
                    HomerApi
                        .questions(processId = askerId)
                        .questions
                        .firstOrNull { it.id == dispatch.questionId }
                }
            assertEquals(HttpStatusCode.OK, HomerApi.answer(question.id, "beta").status)

            val fired =
                Awaits.awaitNotNull("dispatch ${dispatch.id} to fire") {
                    HomerApi
                        .dispatches()
                        .dispatches
                        .firstOrNull { it.id == dispatch.id && it.status == "DISPATCHED" && it.dispatchedProcessId != null }
                }
            val target = Awaits.awaitTerminal(fired.dispatchedProcessId!!)
            assertEquals("FINISHED", target.status, "the dispatched run must run: ${target.failureReason}")

            // The subject arrives by inheritance alone: the dispatch's own params carry
            // only the answer param and the fixture's command arguments.
            assertEquals(ticket, target.inputParams["TICKET_ID"], "the dispatched run must inherit the subject input")
            assertTrue(
                target.tags.contains("ticket:$ticket"),
                "the fromInputs tag must be derived from the inherited input: ${target.tags}",
            )
            assertTrue(
                target.tags.none { it.contains(secret) },
                "no tag may embed the inherited secret input: ${target.tags}",
            )

            // The inherited secret input is masked, and the raw value is nowhere in the response.
            assertEquals("***", target.inputParams["SECRET_TOKEN"], "the inherited secret input must be masked")
            val rawStatus = HomerApi.statusResponse(target.id).bodyAsText()
            assertFalse(rawStatus.contains(secret), "the run's status must not carry the inherited secret: $rawStatus")

            // The operator's answer and the inherited subject both reached the command env.
            val hold = target.executions[1]
            assertFalse(hold.skipped, "the hold command must run in the dispatched run: ${target.executions}")
            val stdout = HomerApi.artifactText(target.id, hold.stdOut, "cmd_1.out")
            assertTrue(stdout.contains("ticket=$ticket"), "the inherited input must reach the command env: $stdout")
            assertTrue(stdout.contains("note=beta"), "the operator answer must reach the command env: $stdout")
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
