package homer.app.routes

import homer.app.apiJson
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.mountApiV1Routes
import homer.app.fixtures.mountCallbackRoutes
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentDispatchConfig
import homer.app.models.CreateDispatchResponse
import homer.app.models.DispatchListResponse
import homer.app.models.DispatchResponse
import homer.app.models.QuestionsListResponse
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The callback POST and the operator REST endpoints sit on different mount
 * points (token-auth vs operator-auth), and the route-test helpers each install
 * ContentNegotiation at the routing root — mounting both in one `testApplication`
 * throws DuplicatePluginException. So callback-side behavior is exercised through
 * [mountCallbackRoutes] and operator-side behavior through [mountApiV1Routes],
 * seeding the dispatch via the shared service when a test only needs one side.
 */
class DispatchRoutesTest {
    /** A QuestionsService + DispatchService sharing one database, with the target agent stubbed. */
    private class Harness {
        val db = PersistenceTestSupport.tempDatabase()
        val questions = PersistenceTestSupport.questionsService(db = db)
        val agents = mockk<AgentsService>()
        val runStarter = mockk<AgentRunStarter>()
        val inputResolver = mockk<AgentInputResolver>(relaxed = true)
        val dispatches =
            PersistenceTestSupport.dispatchService(
                db = db,
                questionsService = questions,
                agentsService = agents,
                runStarter = runStarter,
                inputResolver = inputResolver,
            )

        init {
            val handler = mockk<Agent>(relaxed = true)
            every { handler.dispatch } returns AgentDispatchConfig(enabled = true)
            val blocked = mockk<Agent>(relaxed = true)
            every { blocked.dispatch } returns null
            every { agents.agents } returns mapOf("handler" to handler, "blocked" to blocked)
        }
    }

    @Test
    fun `callback dispatch is created, fired on answer, and exposed via GET`() =
        testApplication {
            val h = Harness()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } returns 42

            // Asker registers the dispatch with its callback token.
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            val created =
                runBlocking {
                    h.dispatches.create(7, "Which region?", listOf("us", "eu"), "handler", "answer", emptyMap(), null)
                }

            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
            }

            // Operator answers the question -> fire starts the target run.
            val answerResp =
                client.post("/api/v1/questions/${created.questionId}/answer") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"answer":"us"}""")
                }
            assertEquals(HttpStatusCode.OK, answerResp.status)

            val getResp = client.get("/api/v1/dispatches/${created.dispatchId}")
            assertEquals(HttpStatusCode.OK, getResp.status)
            val dispatch = apiJson.decodeFromString<DispatchResponse>(getResp.bodyAsText())
            assertEquals("DISPATCHED", dispatch.status)
            assertEquals(7, dispatch.originProcessId)
            assertEquals(42, dispatch.dispatchedProcessId)
            // The token is unused on the operator side but proves registration happened.
            assertNotNull(token)
        }

    @Test
    fun `POST callback dispatch returns the dispatch and question ids`() =
        testApplication {
            val h = Harness()
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
            }

            val resp =
                client.post("/api/v1/callback/dispatches") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"Which region?","agent":"handler","options":["us","eu"]}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val created = apiJson.decodeFromString<CreateDispatchResponse>(resp.bodyAsText())
            assertEquals(created.questionId, runBlocking { h.dispatches.get(created.dispatchId) }?.questionId)
        }

    @Test
    fun `POST callback dispatch to an unknown agent responds 404`() =
        testApplication {
            val h = Harness()
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
            }

            val resp =
                client.post("/api/v1/callback/dispatches") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"Which region?","agent":"nope"}""")
                }

            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `POST callback dispatch to a non-dispatchable agent responds 403`() =
        testApplication {
            val h = Harness()
            val token = runBlocking { h.questions.registerProcess(7, "asker") }
            mountCallbackRoutes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
            }

            val resp =
                client.post("/api/v1/callback/dispatches") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"Which region?","agent":"blocked"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }

    @Test
    fun `answer returns 200 and dispatch is FAILED when dispatch fire throws a non-HomerException`() =
        testApplication {
            val h = Harness()
            coEvery { h.runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("db exploded")

            runBlocking { h.questions.registerProcess(7, "asker") }
            val created =
                runBlocking {
                    h.dispatches.create(7, "Which region?", listOf("us", "eu"), "handler", "answer", emptyMap(), null)
                }

            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
            }

            // fire() catches all non-cancellation exceptions and marks the dispatch FAILED immediately.
            val answerResp =
                client.post("/api/v1/questions/${created.questionId}/answer") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"answer":"eu"}""")
                }
            assertEquals(HttpStatusCode.OK, answerResp.status)

            // fire() caught the RuntimeException and called markFailed, so the dispatch is FAILED not DISPATCHING.
            val getResp = client.get("/api/v1/dispatches/${created.dispatchId}")
            assertEquals(HttpStatusCode.OK, getResp.status)
            val dispatch = apiJson.decodeFromString<DispatchResponse>(getResp.bodyAsText())
            assertEquals("FAILED", dispatch.status)
        }

    @Test
    fun `DELETE dispatch cancels it and deletes its question`() =
        testApplication {
            val h = Harness()
            runBlocking { h.questions.registerProcess(7, "asker") }
            val created =
                runBlocking {
                    h.dispatches.create(7, "Which region?", emptyList(), "handler", "answer", emptyMap(), null)
                }
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
            }

            val cancelResp = client.delete("/api/v1/dispatches/${created.dispatchId}")
            assertEquals(HttpStatusCode.NoContent, cancelResp.status)

            // The bound question is removed on cancel (not left as an EXPIRED row).
            val all =
                apiJson.decodeFromString<QuestionsListResponse>(
                    client.get("/api/v1/questions").bodyAsText(),
                )
            assertNull(all.questions.firstOrNull { it.id == created.questionId })
        }

    @Test
    fun `GET dispatches pages newest first and reports total, offset and limit`() =
        testApplication {
            val h = Harness()
            runBlocking { h.questions.registerProcess(7, "asker") }
            val ids =
                runBlocking {
                    (1..3).map { h.dispatches.create(7, "Q$it?", emptyList(), "handler", "answer", emptyMap(), null).dispatchId }
                }
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
            }

            val first = apiJson.decodeFromString<DispatchListResponse>(client.get("/api/v1/dispatches?limit=2").bodyAsText())
            assertEquals(ids.reversed().take(2), first.dispatches.map { it.id })
            assertEquals(3, first.total)
            assertEquals(0, first.offset)
            assertEquals(2, first.limit)
            val rest = apiJson.decodeFromString<DispatchListResponse>(client.get("/api/v1/dispatches?limit=2&offset=2").bodyAsText())
            assertEquals(listOf(ids.first()), rest.dispatches.map { it.id })
            assertEquals(3, rest.total)
            val defaults = apiJson.decodeFromString<DispatchListResponse>(client.get("/api/v1/dispatches").bodyAsText())
            assertEquals(100, defaults.limit)
            assertEquals(3, defaults.dispatches.size)
        }

    @Test
    fun `GET dispatches rejects a limit outside 1 to 1000 and a negative offset`() =
        testApplication {
            val h = Harness()
            mountApiV1Routes {
                every { questionsService } returns h.questions
                every { dispatchService } returns h.dispatches
            }

            for (query in listOf("limit=0", "limit=1001", "offset=-1")) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/dispatches?$query").status, query)
            }
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/dispatches?limit=1000").status)
        }
}
