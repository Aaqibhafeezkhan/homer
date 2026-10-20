package homer.app.routes

import homer.app.apiJson
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.mountApiV1Routes
import homer.app.models.ProcessInfoResponse
import homer.app.models.ProcessListResponse
import homer.app.models.QuestionResponse
import homer.app.models.QuestionsListResponse
import homer.app.services.ProcessEventBroadcaster
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import io.mockk.every
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestionsRoutesTest {
    private fun newService(broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster()) =
        PersistenceTestSupport.questionsService(broadcaster = broadcaster)

    @Test
    fun `GET questions lists all questions`() =
        testApplication {
            val service = newService()
            runBlocking {
                service.registerProcess(1, "agent-a")
                service.ask(1, "one")
                service.ask(1, "two")
            }
            mountApiV1Routes { every { questionsService } returns service }

            val response = client.get("/api/v1/questions")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<QuestionsListResponse>(response.bodyAsText())
            assertEquals(listOf("one", "two"), body.questions.map { it.text })
        }

    @Test
    fun `GET questions filters by status and processId`() =
        testApplication {
            val service = newService()
            val answeredId =
                runBlocking {
                    service.registerProcess(1, "agent-a")
                    service.registerProcess(2, "agent-b")
                    val q = service.ask(1, "answered one")
                    service.ask(2, "open one")
                    service.answer(q.id, "done").id
                }
            mountApiV1Routes { every { questionsService } returns service }

            val byStatus =
                apiJson.decodeFromString<QuestionsListResponse>(
                    client.get("/api/v1/questions?status=answered").bodyAsText(),
                )
            assertEquals(listOf(answeredId), byStatus.questions.map { it.id })

            val byProcess =
                apiJson.decodeFromString<QuestionsListResponse>(
                    client.get("/api/v1/questions?processId=2").bodyAsText(),
                )
            assertEquals(listOf("open one"), byProcess.questions.map { it.text })
        }

    @Test
    fun `GET questions with invalid status responds 400`() =
        testApplication {
            mountApiV1Routes { every { questionsService } returns newService() }

            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/questions?status=bogus").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/questions?processId=abc").status)
        }

    @Test
    fun `POST answer resolves the question`() =
        testApplication {
            val service = newService()
            val question =
                runBlocking {
                    service.registerProcess(1, "agent-a")
                    service.ask(1, "Proceed?")
                }
            mountApiV1Routes { every { questionsService } returns service }

            val response =
                client.post("/api/v1/questions/${question.id}/answer") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"answer":"yes, go"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<QuestionResponse>(response.bodyAsText())
            assertEquals("ANSWERED", body.status)
            assertEquals("yes, go", body.answer)
            assertEquals("ANSWERED", runBlocking { service.get(question.id) }?.status?.name)
        }

    @Test
    fun `POST answer on unknown id responds 404`() =
        testApplication {
            mountApiV1Routes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/questions/nope/answer") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"answer":"yes"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `POST answer twice responds 409`() =
        testApplication {
            val service = newService()
            val question =
                runBlocking {
                    service.registerProcess(1, "agent-a")
                    val q = service.ask(1, "Proceed?")
                    service.answer(q.id, "first")
                    q
                }
            mountApiV1Routes { every { questionsService } returns service }

            val response =
                client.post("/api/v1/questions/${question.id}/answer") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"answer":"second"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `status all rows carry openQuestions counts`() =
        testApplication {
            // The list reads the counts with its page, so both share one database.
            val db = PersistenceTestSupport.tempDatabase()
            val service = PersistenceTestSupport.questionsService(db = db)
            val pm = PersistenceTestSupport.processManager(db = db)
            runBlocking {
                val id = pm.obtainProcessId("agent-a")
                service.registerProcess(id, "agent-a")
                service.ask(id, "one")
                service.ask(id, "two")
            }
            mountApiV1Routes {
                every { questionsService } returns service
                every { processManager } returns pm
            }

            val response = client.get("/api/v1/status/all")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(2, body.processes.single().openQuestions)
        }

    @Test
    fun `single process status carries openQuestions count`() =
        testApplication {
            val service = newService()
            val pm = PersistenceTestSupport.processManager()
            val processId =
                runBlocking {
                    val id = pm.obtainProcessId("agent-a")
                    service.registerProcess(id, "agent-a")
                    service.ask(id, "one")
                    id
                }
            mountApiV1Routes {
                every { questionsService } returns service
                every { processManager } returns pm
            }

            val response = client.get("/api/v1/status/$processId")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<ProcessInfoResponse>(response.bodyAsText())
            assertEquals(1, body.openQuestions)
        }

    @Test
    fun `stream processes emits a ping comment immediately on connect`() =
        testApplication {
            val broadcaster = ProcessEventBroadcaster()
            val service = newService(broadcaster)
            mountApiV1Routes {
                every { questionsService } returns service
                every { processEventBroadcaster } returns broadcaster
            }

            // No events are emitted: the first bytes must be the connect ping that
            // flushes the response headers (Netty defers them until the first write).
            client.prepareGet("/api/v1/stream/processes").execute { response ->
                val channel = response.bodyAsChannel()
                val firstLine =
                    withTimeout(5_000) {
                        channel.readLine()
                    }
                assertEquals(": ping", firstLine)
            }
        }

    @Test
    fun `stream processes forwards question asked answered and expired events`() =
        testApplication {
            val broadcaster = ProcessEventBroadcaster()
            val service = newService(broadcaster)
            runBlocking { service.registerProcess(1, "agent-a") }
            mountApiV1Routes {
                every { questionsService } returns service
                every { processEventBroadcaster } returns broadcaster
            }

            client.prepareGet("/api/v1/stream/processes").execute { response ->
                coroutineScope {
                    val trigger =
                        launch {
                            delay(300)
                            val q = service.ask(1, "Proceed?")
                            service.answer(q.id, "yes")
                            service.ask(1, "Still there?")
                            service.expireForProcess(1)
                        }
                    val channel = response.bodyAsChannel()
                    val seen = mutableListOf<String>()
                    withTimeout(10_000) {
                        while (true) {
                            val line = channel.readLine() ?: break
                            if (line.startsWith("event: ")) seen += line.removePrefix("event: ")
                            if ("question.expired" in seen) break
                        }
                    }
                    trigger.join()
                    assertTrue("question.asked" in seen, "expected question.asked in $seen")
                    assertTrue("question.answered" in seen, "expected question.answered in $seen")
                    assertTrue("question.expired" in seen, "expected question.expired in $seen")
                }
            }
        }
}
