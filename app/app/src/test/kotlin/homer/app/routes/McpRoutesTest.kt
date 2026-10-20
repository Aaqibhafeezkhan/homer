package homer.app.routes

import homer.app.buildBearerProvider
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.models.AgentMcpConfig
import homer.app.models.AuthConfig
import homer.app.models.McpServerConfig
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.TokenConfig
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.ArtifactService
import homer.app.services.Authorizer
import homer.app.services.McpToolFactory
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
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
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the /mcp endpoint using raw JSON-RPC POSTs — no SDK
 * client involved, so the wire format itself (route-scoped McpJson
 * ContentNegotiation, stateless JSON-response mode) is what's under test.
 */
class McpRoutesTest {
    private lateinit var agentsService: AgentsService
    private lateinit var agentRunStarter: AgentRunStarter
    private lateinit var processManager: ProcessManager

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        agentsService = mockk(relaxed = true)
        every { agentsService.agents } returns emptyMap()
        agentRunStarter = mockk()
        coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend () -> Map<String, String>>(9).invoke()
            42
        }
        processManager = mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    private fun deps(): ApiDeps {
        val factory =
            McpToolFactory(
                agentsService = agentsService,
                authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                agentInputResolver = AgentInputResolver(),
                agentRunStarter = agentRunStarter,
                processManager = processManager,
                artifactService =
                    ArtifactService(
                        processManager = processManager,
                        fileOperations = homer.app.infra.LocalFileOperations(),
                        logger = TestFixtures.mockLogger(),
                    ),
                processEventBroadcaster = ProcessEventBroadcaster(),
                mcpConfig = McpServerConfig(enabled = true),
                logger = TestFixtures.mockLogger(),
            )
        return mockk<ApiDeps> {
            every { mcpToolFactory } returns factory
        }
    }

    /**
     * Mirrors Application.kt's mounting: /mcp inside the authenticate block (the production
     * [buildBearerProvider] when [requireAuth]), gated by the kill switch.
     */
    private fun Application.wireMcp(
        enabled: Boolean = true,
        requireAuth: Boolean = false,
    ) {
        if (requireAuth) {
            install(Authentication) {
                buildBearerProvider(AuthConfig.Bearer(tokens = mapOf("admin" to TokenConfig(token = "secret"))))
            }
        }
        routing {
            if (requireAuth) {
                authenticate("auth-bearer") {
                    if (enabled) mcpRoutes(deps())
                }
            } else {
                if (enabled) mcpRoutes(deps())
            }
        }
    }

    private suspend fun HttpClient.mcpPost(
        body: String,
        accept: String = "application/json, text/event-stream",
        bearer: String? = null,
    ): HttpResponse =
        post("/mcp") {
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

    private fun toolsListBody(id: Int = 2): String = """{"jsonrpc":"2.0","id":$id,"method":"tools/list","params":{}}"""

    private fun toolsCallBody(
        tool: String,
        argumentsJson: String,
        id: Int = 3,
    ): String = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$argumentsJson}}"""

    private fun result(body: String): JsonObject {
        val parsed = json.parseToJsonElement(body).jsonObject
        return assertNotNull(parsed["result"]?.jsonObject, "expected JSON-RPC result in: $body")
    }

    // ========== mounting / auth ==========

    @Test
    fun `kill switch off means no mcp route`() =
        testApplication {
            application { wireMcp(enabled = false) }

            val response = client.mcpPost(initializeBody())

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `missing bearer token is rejected with 401`() =
        testApplication {
            application { wireMcp(requireAuth = true) }

            val response = client.mcpPost(initializeBody())

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET and DELETE are rejected with 405`() =
        testApplication {
            application { wireMcp() }

            assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp").status)
            assertEquals(HttpStatusCode.MethodNotAllowed, client.delete("/mcp").status)
        }

    @Test
    fun `POST without event-stream accept is rejected with 406`() =
        testApplication {
            application { wireMcp() }

            val response = client.mcpPost(initializeBody(), accept = "application/json")

            assertEquals(HttpStatusCode.NotAcceptable, response.status)
        }

    // ========== protocol round-trips ==========

    @Test
    fun `initialize round-trips through route-scoped McpJson`() =
        testApplication {
            application { wireMcp(requireAuth = true) }

            val response = client.mcpPost(initializeBody(), bearer = "secret")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = result(response.bodyAsText())
            assertEquals(
                "homer",
                result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content,
            )
            assertTrue(
                result["capabilities"]!!.jsonObject.containsKey("tools"),
                "server should advertise the tools capability",
            )
        }

    @Test
    fun `tools list reflects opt-in and agent reload`() =
        testApplication {
            val optedIn =
                AgentFixtures
                    .simpleAgent(name = "exposed", description = "the exposed one")
                    .copy(mcp = AgentMcpConfig(enabled = true))
            val hidden = AgentFixtures.simpleAgent(name = "hidden")
            every { agentsService.agents } returns mapOf("exposed" to optedIn, "hidden" to hidden)
            application { wireMcp() }

            val first = result(client.mcpPost(toolsListBody()).bodyAsText())
            val firstNames = toolNames(first)
            assertTrue("exposed" in firstNames, "opted-in agent should be a tool: $firstNames")
            assertTrue("hidden" !in firstNames, "non-opted-in agent must not be a tool")
            assertEquals(
                setOf("get_run_info", "list_artifacts", "get_artifact", "cancel_run"),
                firstNames.filter { it != "exposed" }.toSet(),
                "management tools should always be registered",
            )

            // Simulate an agent reload between requests: the next POST builds a
            // fresh Server, so the new agent appears without any listChanged dance.
            val added =
                AgentFixtures
                    .simpleAgent(name = "added_later")
                    .copy(mcp = AgentMcpConfig(enabled = true))
            every { agentsService.agents } returns
                mapOf("exposed" to optedIn, "added_later" to added)

            val second = toolNames(result(client.mcpPost(toolsListBody()).bodyAsText()))
            assertTrue("added_later" in second, "reloaded agent should appear: $second")
        }

    @Test
    fun `tools call starts a run and returns runId`() =
        testApplication {
            val agent =
                AgentFixtures
                    .simpleAgent(name = "runnable")
                    .copy(mcp = AgentMcpConfig(enabled = true))
            every { agentsService.agents } returns mapOf("runnable" to agent)
            coEvery { processManager.getProcessStatus(42) } returns ProcessStatus.WORKING
            application { wireMcp() }

            val response = client.mcpPost(toolsCallBody("runnable", "{}"))

            assertEquals(HttpStatusCode.OK, response.status)
            val result = result(response.bodyAsText())
            val structured = result["structuredContent"]!!.jsonObject
            assertEquals(42, structured["runId"]!!.jsonPrimitive.content.toInt())
            assertEquals("WORKING", structured["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `tools call with _wait returns the final status`() =
        testApplication {
            val agent =
                AgentFixtures
                    .simpleAgent(name = "awaited")
                    .copy(mcp = AgentMcpConfig(enabled = true))
            every { agentsService.agents } returns mapOf("awaited" to agent)
            coEvery { processManager.getProcessStatus(42) } returns ProcessStatus.FINISHED
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "awaited",
                    costUsd = 0.07,
                )
            application { wireMcp() }

            val response = client.mcpPost(toolsCallBody("awaited", """{"_wait": 10}"""))

            assertEquals(HttpStatusCode.OK, response.status)
            val structured = result(response.bodyAsText())["structuredContent"]!!.jsonObject
            assertEquals("FINISHED", structured["status"]!!.jsonPrimitive.content)
            assertEquals(0.07, structured["costUsd"]!!.jsonPrimitive.content.toDouble())
        }

    @Test
    fun `cancel_run tool kills the run`() =
        testApplication {
            coEvery { processManager.getProcessInfo(42) } returns
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.WORKING,
                    agentName = "whatever",
                )
            coEvery { processManager.getProcessStatus(42) } returns ProcessStatus.KILLED
            application { wireMcp() }

            val response = client.mcpPost(toolsCallBody("cancel_run", """{"runId": 42}"""))

            assertEquals(HttpStatusCode.OK, response.status)
            val structured = result(response.bodyAsText())["structuredContent"]!!.jsonObject
            assertEquals("KILLED", structured["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `tool domain failure surfaces as isError result, not protocol error`() =
        testApplication {
            coEvery { processManager.getProcessInfo(999) } returns null
            application { wireMcp() }

            val response = client.mcpPost(toolsCallBody("get_run_info", """{"runId": 999}"""))

            assertEquals(HttpStatusCode.OK, response.status)
            val result = result(response.bodyAsText())
            assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        }

    private fun toolNames(toolsListResult: JsonObject): List<String> =
        (toolsListResult["tools"] as JsonArray).map {
            (it.jsonObject["name"] as JsonPrimitive).content
        }
}
