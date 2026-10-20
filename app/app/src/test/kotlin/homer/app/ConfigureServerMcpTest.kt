package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.McpServerConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boots the real server module (`configureServer`) with `server.mcp.enabled`
 * to catch wiring-level regressions that per-route test apps can't see —
 * e.g. the app-level ContentNegotiation install colliding with the
 * route-scoped McpJson install on /mcp (DuplicatePluginException at startup).
 */
class ConfigureServerMcpTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    private val json = Json { ignoreUnknownKeys = true }

    private fun config() =
        AppConfig(
            server = Server(apiAuth = AuthConfig.Unsecured, mcp = McpServerConfig(enabled = true)),
            agentsSearchPath = agentsDir.absolutePath,
            workspaceRootPath = workspaceDir.absolutePath,
            databasePath = File(workspaceDir, "homer.db").absolutePath,
            langfuse =
                LangfuseConfig(
                    serverUrl = "http://localhost:9",
                    publicKey = "pk-test",
                    secretKey = "sk-test",
                ),
            openTelemetryExport = OpenTelemetryExport.LOG,
        )

    private fun writeBodyInputAgent() {
        File(agentsDir, "body-input").mkdirs()
        File(agentsDir, "body-input/agent.yaml").writeText(
            """
            name: body-input
            commands:
              - !<cmd>
                cmd: "echo hello"
            inputs:
              - paramName: user
                envName: USER_NAME
                src: body
                type: STRING
                required: true
            """.trimIndent(),
        )
    }

    @Test
    fun `mcp endpoint initializes through the real server module`() =
        testApplication {
            application { configureServer(config()) }

            val response =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(
                        """
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                            "protocolVersion":"2025-06-18",
                            "capabilities":{},
                            "clientInfo":{"name":"test-client","version":"1.0"}
                        }}
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val result =
                assertNotNull(
                    json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]?.jsonObject,
                    "expected a JSON-RPC result",
                )
            assertEquals(
                "homer",
                result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content,
            )
        }

    @Test
    fun `rest endpoints still serialize through the global json`() =
        testApplication {
            application { configureServer(config()) }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.bodyAsText().contains("agents"),
                "agents list should serialize as JSON",
            )
        }

    @Test
    fun `status pages error responses still serialize as ErrorResponse json`() =
        testApplication {
            writeBodyInputAgent()
            application { configureServer(config()) }

            // GET on an agent with body inputs throws QueryParamValidationException,
            // which only StatusPages (app-level, ADR-0002) turns into a response.
            val response = client.get("/api/v1/agent/body-input")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val msg =
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["msg"]
                    ?.jsonPrimitive
                    ?.content
            assertNotNull(msg, "StatusPages must serialize ErrorResponse as JSON")
            assertTrue(msg.contains("requires request body"), "unexpected error message: $msg")
        }
}
