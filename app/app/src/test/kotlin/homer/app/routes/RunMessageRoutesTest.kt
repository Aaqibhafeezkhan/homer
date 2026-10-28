package homer.app.routes

import homer.app.apiJson
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.authorizerFor
import homer.app.fixtures.installGrantsApp
import homer.app.infra.ProcessJobRegistry
import homer.app.models.Action
import homer.app.models.ErrorResponse
import homer.app.models.Grant
import homer.app.models.ProcessStatus
import homer.app.models.RunMessageResponse
import homer.app.models.RunMessagesConfig
import homer.app.models.RunMessagesListResponse
import homer.app.models.TokenConfig
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.RunMessagesService
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.RunMessagesRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RunMessageRoutesTest {
    private val ownerToken = TokenConfig(token = "owner-token")
    private val readToken = TokenConfig(token = "read-token", grants = listOf(Grant(listOf("deploy"), listOf(Action.READ))))
    private val foreignToken =
        TokenConfig(token = "foreign-token", grants = listOf(Grant(listOf("other"), listOf(Action.READ, Action.CONTROL))))
    private val operatorToken =
        TokenConfig(token = "operator-token", grants = listOf(Grant(listOf("deploy"), listOf(Action.READ, Action.CONTROL))))
    private val blindToken = TokenConfig(token = "blind-token", grants = listOf(Grant(listOf("deploy"), listOf(Action.CONTROL))))
    private val tokens =
        mapOf("alice" to ownerToken, "reader" to readToken, "mallory" to foreignToken, "operator" to operatorToken, "blind" to blindToken)

    private class Fixture(
        val pm: ProcessManager,
        val processes: ProcessRepository,
        val service: RunMessagesService,
        val broadcaster: ProcessEventBroadcaster,
        val processId: Int,
    )

    private fun fixture(config: RunMessagesConfig = RunMessagesConfig()): Fixture {
        val db = PersistenceTestSupport.tempDatabase()
        val broadcaster = ProcessEventBroadcaster()
        val pm = PersistenceTestSupport.processManager(db = db, broadcaster = broadcaster)
        val service =
            RunMessagesService(RunMessagesRepository(db), broadcaster, config, LoggerFactory.getLogger("test"))
        val processes = ProcessRepository(db, ProcessJobRegistry())
        val id = runBlocking { pm.obtainProcessId("deploy", owner = "alice") }
        return Fixture(pm, processes, service, broadcaster, id)
    }

    private fun ApplicationTestBuilder.mount(f: Fixture) {
        val deps =
            mockk<ApiDeps>(relaxed = true) {
                every { authorizer } returns authorizerFor(tokens)
                every { processManager } returns f.pm
                every { runMessagesService } returns f.service
                every { processEventBroadcaster } returns f.broadcaster
            }
        application { installGrantsApp(tokens, deps) }
    }

    private suspend fun HttpClient.send(
        path: String,
        token: String,
        body: String = """{"text":"hello"}""",
    ): HttpResponse =
        post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.list(
        path: String,
        token: String,
    ): HttpResponse = get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    @Test
    fun `owner sends a message and gets 202 QUEUED and list shows it`() =
        testApplication {
            val f = fixture()
            mount(f)
            val path = "/api/v1/status/${f.processId}/messages"

            val sent = client.send(path, "owner-token")

            assertEquals(HttpStatusCode.Accepted, sent.status)
            val body = apiJson.decodeFromString<RunMessageResponse>(sent.bodyAsText())
            assertEquals("QUEUED", body.status)
            assertEquals("hello", body.text)
            assertEquals("alice", body.sender)
            assertEquals(f.processId, body.processId)
            val listed = apiJson.decodeFromString<RunMessagesListResponse>(client.list(path, "owner-token").bodyAsText())
            assertEquals(listOf(body), listed.messages)
        }

    @Test
    fun `a caller with only a read grant gets 404 on POST and 200 on GET`() =
        testApplication {
            val f = fixture()
            mount(f)
            val path = "/api/v1/status/${f.processId}/messages"

            val post = client.send(path, "read-token")

            assertEquals(HttpStatusCode.NotFound, post.status)
            assertEquals("Process not found", apiJson.decodeFromString<ErrorResponse>(post.bodyAsText()).msg)
            assertEquals(HttpStatusCode.OK, client.list(path, "read-token").status)
            assertTrue(runBlocking { f.service.list(f.processId) }.isEmpty(), "a denied send must store nothing")
        }

    @Test
    fun `a non-owner with read and control grants on the agent sends with 202`() =
        testApplication {
            val f = fixture()
            mount(f)
            val path = "/api/v1/status/${f.processId}/messages"

            val sent = client.send(path, "operator-token")

            assertEquals(HttpStatusCode.Accepted, sent.status)
            assertEquals("operator", apiJson.decodeFromString<RunMessageResponse>(sent.bodyAsText()).sender)
        }

    @Test
    fun `a control grant without read is 404 on POST, same body as an unknown id`() =
        testApplication {
            val f = fixture()
            mount(f)

            val denied = client.send("/api/v1/status/${f.processId}/messages", "blind-token")
            val missing = client.send("/api/v1/status/9999/messages", "owner-token")

            assertEquals(HttpStatusCode.NotFound, denied.status)
            assertEquals(missing.status, denied.status)
            assertEquals(missing.bodyAsText(), denied.bodyAsText())
            assertTrue(runBlocking { f.service.list(f.processId) }.isEmpty(), "a denied send must store nothing")
        }

    @Test
    fun `a caller who cannot see the run gets 404 on both, same body as an unknown id`() =
        testApplication {
            val f = fixture()
            mount(f)
            val path = "/api/v1/status/${f.processId}/messages"
            val unknown = "/api/v1/status/9999/messages"

            val denied = listOf(client.send(path, "foreign-token"), client.list(path, "foreign-token"))
            val missing = listOf(client.send(unknown, "owner-token"), client.list(unknown, "owner-token"))

            (denied + missing).forEach { assertEquals(HttpStatusCode.NotFound, it.status) }
            val bodies = (denied + missing).map { apiJson.decodeFromString<ErrorResponse>(it.bodyAsText()) }
            assertEquals(1, bodies.toSet().size)
            assertEquals("Process not found", bodies.first().msg)
        }

    @Test
    fun `invalid processId is 400 and unknown processId is 404`() =
        testApplication {
            mount(fixture())

            assertEquals(HttpStatusCode.BadRequest, client.send("/api/v1/status/abc/messages", "owner-token").status)
            assertEquals(HttpStatusCode.BadRequest, client.list("/api/v1/status/abc/messages", "owner-token").status)
            assertEquals(HttpStatusCode.NotFound, client.send("/api/v1/status/9999/messages", "owner-token").status)
        }

    @Test
    fun `blank or over-long text is 400, an ended run is 409 and the cap is 429`() =
        testApplication {
            val f = fixture(RunMessagesConfig(maxTextLength = 5, maxPerRun = 1))
            mount(f)
            val path = "/api/v1/status/${f.processId}/messages"

            assertEquals(HttpStatusCode.BadRequest, client.send(path, "owner-token", """{"text":"   "}""").status)
            assertEquals(HttpStatusCode.BadRequest, client.send(path, "owner-token", """{"text":"123456"}""").status)
            assertEquals(HttpStatusCode.Accepted, client.send(path, "owner-token", """{"text":"ok"}""").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.send(path, "owner-token", """{"text":"ok"}""").status)

            val ended = runBlocking { f.pm.obtainProcessId("deploy", owner = "alice") }
            runBlocking { f.processes.transitionStatus(ended, ProcessStatus.FINISHED, 1, null) }
            assertEquals(HttpStatusCode.Conflict, client.send("/api/v1/status/$ended/messages", "owner-token").status)
        }

    @Test
    fun `malformed JSON body is 400`() =
        testApplication {
            val f = fixture()
            mount(f)

            val res = client.send("/api/v1/status/${f.processId}/messages", "owner-token", "{not json")

            assertEquals(HttpStatusCode.BadRequest, res.status)
        }

    @Test
    fun `message queued reaches a subscriber who can see the run and not one who cannot`() =
        testApplication {
            val f = fixture()
            mount(f)
            val otherId = runBlocking { f.pm.obtainProcessId("other", owner = "mallory") }

            suspend fun firstEventOf(token: String): String {
                val ready = CompletableDeferred<Unit>()
                var result = ""
                client.prepareGet("/api/v1/stream/processes") { header(HttpHeaders.Authorization, "Bearer $token") }.execute { response ->
                    coroutineScope {
                        launch {
                            ready.await()
                            delay(300)
                            f.service.send(f.processId, "alice", "hi")
                            f.broadcaster.emit(
                                homer.app.services.ProcessEvent
                                    .MessageQueued(otherId, "m-other"),
                            )
                        }
                        val channel = response.bodyAsChannel()
                        withTimeout(10_000) {
                            while (true) {
                                val line = channel.readLine() ?: break
                                if (line == ": ping") ready.complete(Unit)
                                if (line.startsWith("data: ")) {
                                    result = line
                                    break
                                }
                            }
                        }
                    }
                }
                return result
            }

            val seenByOwner = firstEventOf("owner-token")
            val seenByForeign = firstEventOf("foreign-token")

            assertTrue("\"processId\":${f.processId}" in seenByOwner, "owner got: $seenByOwner")
            assertNotEquals("", seenByForeign)
            assertTrue("\"processId\":$otherId" in seenByForeign, "foreign got: $seenByForeign")
        }
}
