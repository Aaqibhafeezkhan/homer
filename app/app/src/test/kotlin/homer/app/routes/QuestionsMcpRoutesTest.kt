package homer.app.routes

import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.all
import homer.app.models.QuestionsConfig
import homer.app.services.QuestionsMcpToolFactory
import homer.app.services.QuestionsService
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for /mcp/questions using raw JSON-RPC POSTs — no SDK
 * client, so the wire format itself (callback-token auth, SSE response
 * mode, mid-call progress notifications) is what's under test.
 */
class QuestionsMcpRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    private fun newService() = PersistenceTestSupport.questionsService()

    /** Mirrors Application.kt's mounting: outside authenticate, gated by the MCP kill switch. */
    private fun Application.wireQuestionsMcp(
        service: QuestionsService,
        enabled: Boolean = true,
        holdChunkSec: Int = 1,
    ) {
        val factory =
            QuestionsMcpToolFactory(
                questionsService = service,
                questionsConfig = QuestionsConfig(),
                dispatchService = mockk(relaxed = true),
                runMessagesService = mockk(relaxed = true),
                logger = TestFixtures.mockLogger(),
                holdChunkSec = holdChunkSec,
            )
        val deps =
            mockk<ApiDeps> {
                every { questionsService } returns service
                every { questionsMcpToolFactory } returns factory
            }
        routing {
            if (enabled) questionsMcpRoutes(deps)
        }
    }

    private suspend fun HttpClient.mcpPost(
        body: String,
        bearer: String? = null,
        accept: String = "application/json, text/event-stream",
    ): HttpResponse =
        post("/mcp/questions") {
            header(HttpHeaders.Accept, accept)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    private fun initializeBody(id: Int = 1): String =
        """
        {"jsonrpc":"2.0","id":$id,"method":"initialize","params":{
            "protocolVersion":"2025-06-18",
            "capabilities":{},
            "clientInfo":{"name":"test-client","version":"1.0"}
        }}
        """.trimIndent()

    private fun toolsCallBody(
        tool: String,
        argumentsJson: String,
        id: Int = 3,
        meta: String? = null,
    ): String {
        val metaField = meta?.let { ""","_meta":$it""" } ?: ""
        return """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$argumentsJson$metaField}}"""
    }

    /** Extracts the JSON-RPC result from an SSE response body (`data:` lines). */
    private fun sseResult(body: String): JsonObject {
        val payload =
            body
                .lineSequence()
                .filter { it.startsWith("data: {") }
                .map { json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
                .firstOrNull { "result" in it }
        return assertNotNull(payload, "expected a JSON-RPC result event in: $body")["result"]!!.jsonObject
    }

    // ========== mounting / auth ==========

    @Test
    fun `kill switch off means no questions mcp route`() =
        testApplication {
            val service = newService()
            application { wireQuestionsMcp(service, enabled = false) }

            assertEquals(HttpStatusCode.NotFound, client.mcpPost(initializeBody()).status)
        }

    @Test
    fun `missing or unknown callback token is rejected with 401`() =
        testApplication {
            val service = newService()
            application { wireQuestionsMcp(service) }

            assertEquals(HttpStatusCode.Unauthorized, client.mcpPost(initializeBody()).status)
            assertEquals(HttpStatusCode.Unauthorized, client.mcpPost(initializeBody(), bearer = "bogus").status)
        }

    @Test
    fun `GET and DELETE are rejected with 405`() =
        testApplication {
            val service = newService()
            application { wireQuestionsMcp(service) }

            assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp/questions").status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.delete("/mcp/questions").status)
        }

    @Test
    fun `POST without event-stream accept is rejected with 406`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            application { wireQuestionsMcp(service) }

            val response = client.mcpPost(initializeBody(), bearer = token, accept = "application/json")

            assertEquals(HttpStatusCode.NotAcceptable, response.status)
        }

    // ========== protocol round-trips over SSE ==========

    @Test
    fun `initialize round-trips as an SSE event stream`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            application { wireQuestionsMcp(service) }

            val response = client.mcpPost(initializeBody(), bearer = token)

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.headers[HttpHeaders.ContentType]!!.startsWith("text/event-stream"),
                "SSE response mode must answer with an event stream",
            )
            val result = sseResult(response.bodyAsText())
            assertEquals(
                "homer-questions",
                result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content,
            )
        }

    @Test
    fun `ask_user creates a question owned by the calling process`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(7, "agent-a") }
            application { wireQuestionsMcp(service) }

            val response =
                client.mcpPost(
                    toolsCallBody("ask_user", """{"text":"Deploy?","options":["yes","no"]}"""),
                    bearer = token,
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val structured = sseResult(response.bodyAsText())["structuredContent"]!!.jsonObject
            val questionId = structured["questionId"]!!.jsonPrimitive.content
            assertEquals("OPEN", structured["status"]!!.jsonPrimitive.content)
            val question = assertNotNull(runBlocking { service.get(questionId) })
            assertEquals(7, question.processId)
            assertEquals(listOf("yes", "no"), question.options)
        }

    @Test
    fun `check_answer returns the operator answer`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question =
                runBlocking {
                    val q = service.ask(1, "Proceed?")
                    service.answer(q.id, "yes, go")
                    q
                }
            application { wireQuestionsMcp(service) }

            val response =
                client.mcpPost(
                    toolsCallBody("check_answer", """{"questionId":"${question.id}"}"""),
                    bearer = token,
                )

            val structured = sseResult(response.bodyAsText())["structuredContent"]!!.jsonObject
            assertEquals("ANSWERED", structured["status"]!!.jsonPrimitive.content)
            assertEquals("yes, go", structured["answer"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a foreign process question reads as an error result`() =
        testApplication {
            val service = newService()
            val question =
                runBlocking {
                    service.registerProcess(1, "agent-a")
                    service.ask(1, "mine")
                }
            val otherToken = runBlocking { service.registerProcess(2, "agent-b") }
            application { wireQuestionsMcp(service) }

            val response =
                client.mcpPost(
                    toolsCallBody("check_answer", """{"questionId":"${question.id}"}"""),
                    bearer = otherToken,
                )

            val result = sseResult(response.bodyAsText())
            assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        }

    @Test
    fun `ask_user_and_wait streams progress notifications before the answer`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            application { wireQuestionsMcp(service, holdChunkSec = 1) }

            // The answerer works against server-side state as a sibling of the
            // request — the test client hands over a POST response only after
            // the handler completes, so it cannot answer from inside a body
            // reader. Held for >1 chunk so a progress notification fires first.
            coroutineScope {
                val answerer =
                    launch {
                        var open = service.all(QuestionsService.QuestionStatus.OPEN)
                        while (open.isEmpty()) {
                            delay(100)
                            open = service.all(QuestionsService.QuestionStatus.OPEN)
                        }
                        delay(1_500)
                        service.answer(open.single().id, "finally yes")
                    }
                val response =
                    withTimeout(15_000) {
                        // progressToken == the JSON-RPC request id: the TS SDK
                        // convention the relatedRequestId routing relies on.
                        client.mcpPost(
                            toolsCallBody(
                                "ask_user_and_wait",
                                """{"text":"Long one?","ttlSec":30}""",
                                id = 3,
                                meta = """{"progressToken":3}""",
                            ),
                            bearer = token,
                        )
                    }
                answerer.join()

                assertEquals(HttpStatusCode.OK, response.status)
                val dataLines =
                    response
                        .bodyAsText()
                        .lineSequence()
                        .filter { it.startsWith("data: {") }
                        .map { it.removePrefix("data: ") }
                        .toList()
                val progressIndex = dataLines.indexOfFirst { "notifications/progress" in it }
                val resultIndex = dataLines.indexOfFirst { "\"result\"" in it }
                assertTrue(progressIndex >= 0, "expected a progress notification in $dataLines")
                assertTrue(progressIndex < resultIndex, "progress must stream before the final result")
                val structured =
                    json
                        .parseToJsonElement(dataLines[resultIndex])
                        .jsonObject["result"]!!
                        .jsonObject["structuredContent"]!!
                        .jsonObject
                assertEquals("ANSWERED", structured["status"]!!.jsonPrimitive.content)
                assertEquals("finally yes", structured["answer"]!!.jsonPrimitive.content)
            }
        }
}
