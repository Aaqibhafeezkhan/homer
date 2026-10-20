package homer.app.routes

import homer.app.EnvConstants
import homer.app.apiJson
import homer.app.configureStatusPages
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.mountApiV1Routes
import homer.app.fixtures.mountCallbackRoutes
import homer.app.infra.AgentInputResolver
import homer.app.infra.SecretRedactor
import homer.app.models.Command
import homer.app.models.CreateAgentResponse
import homer.app.models.LangGraphRunIdentity
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.RunnerConfig
import homer.app.models.TagValidation
import homer.app.models.Tags
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.queue.RunnerClaimQueue
import homer.app.services.AgentFilesService
import homer.app.services.AgentRunStarter
import homer.app.services.ProcessManager
import homer.app.services.persistence.ProcessRepository
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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the exact wire form of every 400 a route answers through StatusPages:
 * status, `Content-Type` and the byte-exact pretty-printed `ErrorResponse` body
 * (or no body at all on the SSE stream endpoints). The `/status/all` query
 * parameters are pinned in [StatusAllValidationTest].
 */
class BadRequestResponsesTest {
    private suspend fun assertBadRequest(
        response: HttpResponse,
        msg: String,
    ) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("application/json", response.headers[HttpHeaders.ContentType])
        assertEquals("{\n    \"err\": 1,\n    \"msg\": \"$msg\"\n}", response.bodyAsText())
    }

    private suspend fun assertBodylessBadRequest(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(response.headers[HttpHeaders.ContentType])
        assertEquals("", response.bodyAsText())
    }

    private suspend fun HttpClient.postJson(
        path: String,
        body: String,
        token: String? = null,
    ): HttpResponse =
        post(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    // ---------- process routes ----------

    @Test
    fun `status with a non-numeric process id`() =
        testApplication {
            mountApiV1Routes()
            assertBadRequest(client.get("/api/v1/status/abc"), "Invalid process ID")
        }

    @Test
    fun `kill with a non-numeric process id`() =
        testApplication {
            mountApiV1Routes()
            assertBadRequest(client.delete("/api/v1/kill/abc"), "Invalid process ID")
        }

    @Test
    fun `artifact without a process id`() =
        testApplication {
            mountApiV1Routes()
            assertBadRequest(client.get("/api/v1/artifacts?path=cmd_0.out"), "Invalid or missing processId parameter")
        }

    @Test
    fun `artifact without a path`() =
        testApplication {
            mountApiV1Routes()
            assertBadRequest(client.get("/api/v1/artifacts?processId=1"), "Missing path parameter")
        }

    @Test
    fun `artifact with a traversal path`() =
        testApplication {
            mountApiV1Routes {
                every { artifactService.validateRelativePath("../etc/passwd") } returns
                    Result.failure(IllegalArgumentException("Path traversal (..) is not allowed"))
            }
            assertBadRequest(
                client.get("/api/v1/artifacts?processId=1&path=../etc/passwd"),
                "Invalid path: Path traversal (..) is not allowed",
            )
        }

    @Test
    fun `artifact list without a process id`() =
        testApplication {
            mountApiV1Routes()
            assertBadRequest(client.get("/api/v1/artifacts/list"), "Invalid or missing processId parameter")
        }

    // ---------- run routes ----------

    @Test
    fun `retry, langgraph and resume with a non-numeric process id`() =
        testApplication {
            mountApiV1Routes()
            assertBadRequest(client.post("/api/v1/processes/abc/retry"), "Invalid process id")
            assertBadRequest(client.get("/api/v1/processes/abc/langgraph"), "Invalid process id")
            assertBadRequest(client.post("/api/v1/processes/abc/resume"), "Invalid process id")
        }

    private fun ApplicationTestBuilder.mountResume(
        agentCommands: List<Command>,
        identity: LangGraphRunIdentity?,
    ) {
        val failed = ProcessInfo(id = 42, status = ProcessStatus.FAILED, agentName = "deploy")
        val agent = AgentFixtures.simpleAgent(name = "deploy").copy(commands = agentCommands)
        mountApiV1Routes {
            coEvery { processManager.getProcessInfo(42) } returns failed
            every { agentsService.agents } returns mapOf("deploy" to agent)
            every { langGraphRunStatusService.identity(any()) } returns identity
        }
    }

    @Test
    fun `resume of a run whose agent lost its LangGraph command`() =
        testApplication {
            mountResume(AgentFixtures.simpleAgent().commands, LangGraphRunIdentity(threadId = "t1", module = "m"))
            assertBadRequest(client.post("/api/v1/processes/42/resume"), "Agent 'deploy' no longer defines a LangGraph command")
        }

    @Test
    fun `resume of a run without a LangGraph thread`() =
        testApplication {
            mountResume(listOf(Command.LangGraph(module = "m")), identity = null)
            assertBadRequest(client.post("/api/v1/processes/42/resume"), "Process 42 has no LangGraph thread to resume")
        }

    // ---------- agent run ----------

    @Test
    fun `CWE-248 - agent run with a malformed Content-Type answers 400, not 500`() =
        testApplication {
            val agent =
                AgentFixtures.simpleAgent(name = "a").copy(inputs = listOf(Param("user", "USER", ParamSource.BODY, ParamType.STRING)))
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("a" to agent)
                every { agentInputResolver } returns AgentInputResolver()
                every { agentRunStarter } returns
                    AgentRunStarter(
                        serverId = "test-server",
                        processExecutor = mockk(relaxed = true),
                        processFactory = mockk(relaxed = true),
                        telemetryService = TestFixtures.mockTelemetryService(),
                        costMeter = mockk(relaxed = true),
                        maxInvocationDepth = 5,
                        cooldownGate = mockk(relaxed = true),
                    )
            }
            val response =
                client.post("/api/v1/agent/a") {
                    // On the content, not the request: the client's own transformers would parse a bad
                    // request Content-Type and throw before sending.
                    setBody(
                        object : OutgoingContent.ByteArrayContent() {
                            override val headers = headersOf(HttpHeaders.ContentType, "application")

                            override fun bytes() = """{"user":"alice"}""".toByteArray()
                        },
                    )
                }
            // Reading the body as text parses the header for its charset first, so the body read
            // is what fails; AgentInputResolverTest covers the resolver's own Content-Type check.
            assertBadRequest(response, "Invalid JSON body")
        }

    // ---------- debug run ----------

    private fun ApplicationTestBuilder.mountDebug() {
        mountApiV1Routes { every { agentsService.agents } returns mapOf("a" to AgentFixtures.simpleAgent(name = "a")) }
    }

    @Test
    fun `debug with a malformed body`() =
        testApplication {
            mountDebug()
            assertBadRequest(
                client.postJson("/api/v1/agents/a/debug", "nope"),
                "Invalid JSON body: expected { commandIndex, env, queryParams, body, headers }",
            )
        }

    @Test
    fun `debug with a command index out of range`() =
        testApplication {
            mountDebug()
            assertBadRequest(client.postJson("/api/v1/agents/a/debug", """{"commandIndex":5}"""), "commandIndex out of range: 5 (0..0)")
        }

    @Test
    fun `debug overriding reserved env names`() =
        testApplication {
            mountDebug()
            assertBadRequest(
                client.postJson("/api/v1/agents/a/debug", """{"commandIndex":0,"env":{"HMR_X":"1","HMR_Y":"2"}}"""),
                "env overrides reserved names (Homer / host-controlled): HMR_X, HMR_Y",
            )
        }

    // ---------- shared helpers ----------

    @Serializable
    private data class Probe(
        val name: String,
    )

    private fun ApplicationTestBuilder.mountProbe(block: suspend io.ktor.server.application.ApplicationCall.() -> Unit) {
        application {
            configureStatusPages()
            routing {
                install(ContentNegotiation) { json(apiJson) }
                post("/probe") { call.block() }
            }
        }
    }

    @Test
    fun `receiveOr400 with a body that does not decode`() =
        testApplication {
            mountProbe { respond(receiveOr400<Probe>("Invalid JSON body: expected { name }")) }
            assertBadRequest(client.postJson("/probe", """{"nope":1}"""), "Invalid JSON body: expected { name }")
        }

    @Test
    fun `respondFileOpResult maps BadRequest`() =
        testApplication {
            mountProbe {
                respondFileOpResult(this, AgentFilesService.FileOpResult.BadRequest("Invalid path: bad"), "unused") {
                    respond(CreateAgentResponse(name = "unused"))
                }
            }
            assertBadRequest(client.post("/probe"), "Invalid path: bad")
        }

    // ---------- callback tags ----------

    private fun ApplicationTestBuilder.mountTags(
        secretRedactor: (String) -> SecretRedactor = { SecretRedactor.NONE },
        written: Boolean = true,
    ): String {
        val service = PersistenceTestSupport.questionsService()
        val token = runBlocking { service.registerProcess(1, "agent-a", secretRedactor) }
        val agent =
            AgentFixtures.simpleAgent(name = "agent-a").copy(tags = Tags(fromInputs = listOf("mr"), runtime = listOf("decision")))
        mountCallbackRoutes {
            every { questionsService } returns service
            every { agentsService.agents } returns mapOf("agent-a" to agent)
            coEvery { processManager.addRuntimeTag(any(), any(), any(), any()) } returns
                ProcessRepository.RuntimeTagResult(written = written, count = TagValidation.MAX_RUNTIME_TAGS_PER_RUN)
        }
        return token
    }

    private suspend fun HttpClient.declareTag(
        token: String,
        body: String,
    ) = postJson("/api/v1/callback/tags", body, token)

    @Test
    fun `declare tag with a malformed body`() =
        testApplication {
            val token = mountTags()
            assertBadRequest(client.declareTag(token, """{"nope":"x"}"""), "Invalid JSON body: expected { tag }")
        }

    @Test
    fun `declare tag without a key-value separator`() =
        testApplication {
            val token = mountTags()
            assertBadRequest(client.declareTag(token, """{"tag":"decision"}"""), "Tag must be 'key:value'")
        }

    @Test
    fun `declare tag with an undeclared key`() =
        testApplication {
            val token = mountTags()
            assertBadRequest(client.declareTag(token, """{"tag":"other:x"}"""), "key 'other' is not a declared tags.runtime key")
        }

    @Test
    fun `declare tag overriding a fromInputs key`() =
        testApplication {
            val token = mountTags()
            assertBadRequest(
                client.declareTag(token, """{"tag":"mr:15"}"""),
                "key 'mr' is a tags.fromInputs key; runtime tags cannot override it",
            )
        }

    @Test
    fun `declare tag with a malformed value`() =
        testApplication {
            val token = mountTags()
            assertBadRequest(client.declareTag(token, """{"tag":"decision:a:b"}"""), "Tag value must match ^[A-Za-z0-9._-]{1,128}$")
        }

    @Test
    fun `declare tag carrying a secret value`() =
        testApplication {
            val token =
                mountTags(secretRedactor = { token ->
                    SecretRedactor.from(
                        mapOf("TOKEN" to "s3cr3tvalue1", EnvConstants.CALLBACK_TOKEN to token),
                        setOf("TOKEN", EnvConstants.CALLBACK_TOKEN),
                        minNeedleLength = TagValidation.SECRET_NEEDLE_MIN_LENGTH,
                    )
                })
            assertBadRequest(client.declareTag(token, """{"tag":"decision:s3cr3tvalue1"}"""), "Tag value must not contain a secret value")
        }

    @Test
    fun `declare tag past the per-run ceiling`() =
        testApplication {
            val token = mountTags(written = false)
            assertBadRequest(client.declareTag(token, """{"tag":"decision:ok"}"""), "Run already has the maximum of 20 runtime tags")
        }

    // ---------- runner protocol ----------

    @Test
    fun `runner register with a body that does not decode`() =
        testApplication {
            val queue = RunnerClaimQueue(maxPending = 10)
            val domain = ExternalRunnerDomain("workers", RunnerConfig.External(token = "workers-token"), queue)
            application {
                configureStatusPages()
                routing {
                    install(ContentNegotiation) { json(apiJson) }
                    runnerProtocolRoutes(listOf(domain), queue)
                }
            }
            for (body in listOf("not json", """{"concurrency":"many"}""")) {
                assertBadRequest(
                    client.postJson("/api/v1/runner/register", body, token = "workers-token"),
                    "Invalid JSON body: expected { name?, tags?, concurrency?, version? }",
                )
            }
        }

    // ---------- SSE streams: a bodyless 400 ----------

    @Test
    fun `log stream with an invalid process id or stream`() =
        testApplication {
            mountApiV1Routes()
            assertBodylessBadRequest(client.get("/api/v1/stream/processes/abc/logs"))
            assertBodylessBadRequest(client.get("/api/v1/stream/processes/1/logs?stream=bogus"))
        }

    @Test
    fun `llm-event stream with an invalid process id or command index`() =
        testApplication {
            mountApiV1Routes()
            assertBodylessBadRequest(client.get("/api/v1/stream/processes/abc/llm-events"))
            assertBodylessBadRequest(client.get("/api/v1/stream/processes/1/llm-events?cmd=-1"))
        }
}
