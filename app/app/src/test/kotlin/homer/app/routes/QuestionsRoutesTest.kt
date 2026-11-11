package homer.app.routes

import homer.app.apiJson
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.authorizerFor
import homer.app.fixtures.installGrantsApp
import homer.app.fixtures.mountApiV1Routes
import homer.app.models.Action
import homer.app.models.Grant
import homer.app.models.ProcessInfo
import homer.app.models.ProcessInfoResponse
import homer.app.models.ProcessListResponse
import homer.app.models.ProcessStatus
import homer.app.models.QuestionResponse
import homer.app.models.QuestionsListResponse
import homer.app.models.TokenConfig
import homer.app.services.Authorizer
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

    /** The ApiDeps an [installGrantsApp] mount needs: grants, questions and process rows only. */
    private fun grantsDeps(
        authorizer: Authorizer,
        questionsService: QuestionsService,
        processManager: ProcessManager,
    ): ApiDeps =
        mockk(relaxed = true) {
            every { this@mockk.authorizer } returns authorizer
            every { this@mockk.questionsService } returns questionsService
            every { this@mockk.processManager } returns processManager
        }

    private suspend fun HttpClient.answer(
        token: String,
        questionId: String,
    ) = post("/api/v1/questions/$questionId/answer") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody("""{"answer":"yes, go"}""")
    }

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
    fun `POST answer without control responds 404 identical to an unknown id`() =
        testApplication {
            val service = newService()
            val question =
                runBlocking {
                    service.registerProcess(7, "deploy")
                    service.ask(7, "Proceed?")
                }
            // READ only on "deploy": the question is visible, answering it is not.
            val tokens = mapOf("bob" to TokenConfig(token = "read-token", grants = listOf(Grant(listOf("deploy"), listOf(Action.READ)))))
            val processManager =
                mockk<ProcessManager>(relaxed = true) {
                    coEvery { getProcessInfo(any()) } returns
                        ProcessInfo(id = 7, status = ProcessStatus.WORKING, agentName = "deploy", owner = "alice")
                }
            application { installGrantsApp(tokens, grantsDeps(authorizerFor(tokens), service, processManager)) }

            val denied = client.answer("read-token", question.id)
            val unknown = client.answer("read-token", "nope")

            assertEquals(HttpStatusCode.NotFound, denied.status)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(unknown.bodyAsText(), denied.bodyAsText())
            assertEquals("OPEN", runBlocking { service.get(question.id) }?.status?.name)
        }

    @Test
    fun `POST answer falls back to the question's agent when its process is purged`() =
        testApplication {
            val service = newService()
            val question =
                runBlocking {
                    service.registerProcess(7, "deploy")
                    service.ask(7, "Proceed?")
                }
            // Dispatch-bound questions outlive their asking process: the row is
            // gone, so only the question's own agentName can carry the grant check.
            val tokens =
                mapOf(
                    "bob" to
                        TokenConfig(
                            token = "control-token",
                            grants = listOf(Grant(listOf("deploy"), listOf(Action.READ, Action.CONTROL))),
                        ),
                )
            val processManager = mockk<ProcessManager>(relaxed = true) { coEvery { getProcessInfo(any()) } returns null }
            application { installGrantsApp(tokens, grantsDeps(authorizerFor(tokens), service, processManager)) }

            val response = client.answer("control-token", question.id)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ANSWERED", runBlocking { service.get(question.id) }?.status?.name)
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
