package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boots the real server module with an `!<external>` domain next to bearer
 * `apiAuth`, so the two principal classes meet the real routing tree: each
 * credential must be `401` on the other's routes (ADR-0066, §12).
 */
class ExternalRunnerBootTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    private fun config(
        withDomain: Boolean = true,
        domainToken: String = WORKERS_TOKEN,
    ) = AppConfig(
        server = Server(apiAuth = AuthConfig.Bearer(tokens = mapOf("ops" to TokenConfig(token = "ops-token", role = "admin")))),
        agentsSearchPath = agentsDir.absolutePath,
        workspaceRootPath = workspaceDir.absolutePath,
        databasePath = File(workspaceDir, "homer.db").absolutePath,
        langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
        openTelemetryExport = OpenTelemetryExport.LOG,
        runners =
            if (withDomain) {
                mapOf(
                    "workers" to RunnerConfig.External(token = domainToken, allowedTags = listOf("gpu")),
                )
            } else {
                emptyMap()
            },
    )

    // AgentsService.scanAgents() runs in a property initializer, so it (and the
    // runnerTagSets lambda's get<RunnerRegistry>() call) fires during Koin's
    // construction of AgentsService, right when configureServer boots — an
    // empty agentsDir would leave that lambda unexercised by this test.
    private fun writeTaggedAgent() {
        File(agentsDir, "tagged").mkdirs()
        File(agentsDir, "tagged/agent.yaml").writeText(
            """
            name: tagged
            runnerTags: [gpu]
            commands:
              - !<cmd>
                cmd: "echo hello"
            """.trimIndent(),
        )
    }

    @Test
    fun `a domain token under 32 characters aborts boot (CWE-331)`() {
        val ex =
            assertThrows<ConfigurationException> {
                testApplication {
                    application { configureServer(config(domainToken = "workers-token")) }
                    startApplication()
                }
            }
        assertEquals(true, ex.message!!.contains("runner 'workers'"), "got: ${ex.message}")
    }

    @Test
    fun `domain and operator credentials never satisfy each other's routes`() =
        testApplication {
            writeTaggedAgent()
            application { configureServer(config()) }

            val registered =
                client.post("/api/v1/runner/register") {
                    header(HttpHeaders.Authorization, "Bearer $WORKERS_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"host-1","tags":["gpu"]}""")
                }
            assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/agents") { header(HttpHeaders.Authorization, "Bearer $WORKERS_TOKEN") }.status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/status") { header(HttpHeaders.Authorization, "Bearer $WORKERS_TOKEN") }.status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .post("/api/v1/runner/register") {
                        header(HttpHeaders.Authorization, "Bearer ops-token")
                    }.status,
            )

            val health = client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer ops-token") }
            assertEquals(HttpStatusCode.OK, health.status)
            assertTrue(health.bodyAsText().contains("\"external\""), health.bodyAsText())
        }

    @Test
    fun `without an external domain every runner route is 401`() =
        testApplication {
            writeTaggedAgent()
            application { configureServer(config(withDomain = false)) }
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .post("/api/v1/runner/register") {
                        header(HttpHeaders.Authorization, "Bearer ops-token")
                    }.status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .post("/api/v1/runner/instances/x/claim") {
                        header(HttpHeaders.Authorization, "Bearer anything")
                    }.status,
            )
        }

    @Test
    fun `a registered instance shows on the admin runner status endpoint and the domain token is 401 there`() =
        testApplication {
            writeTaggedAgent()
            application { configureServer(config()) }

            val registered =
                client.post("/api/v1/runner/register") {
                    header(HttpHeaders.Authorization, "Bearer $WORKERS_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"host-1","tags":["gpu"],"concurrency":1}""")
                }
            assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())

            val asDomain = client.get("/api/v1/runners") { header(HttpHeaders.Authorization, "Bearer $WORKERS_TOKEN") }
            assertEquals(HttpStatusCode.Unauthorized, asDomain.status)

            val asAdmin = client.get("/api/v1/runners") { header(HttpHeaders.Authorization, "Bearer ops-token") }
            assertEquals(HttpStatusCode.OK, asAdmin.status)
            val body = asAdmin.bodyAsText()
            assertTrue(body.contains("\"kind\": \"external\"") || body.contains("\"kind\":\"external\""), body)
            assertTrue(body.contains("host-1"), body)
            assertFalse(body.contains(WORKERS_TOKEN), "the domain token must never appear in the payload")

            val queue = client.get("/api/v1/runners/queue") { header(HttpHeaders.Authorization, "Bearer ops-token") }
            assertEquals(HttpStatusCode.OK, queue.status)
            assertTrue(queue.bodyAsText().contains("\"pending\": 0") || queue.bodyAsText().contains("\"pending\":0"), queue.bodyAsText())
        }

    private companion object {
        /** At the 32-character floor `validateRunnerDomainTokenStrength` enforces at boot. */
        const val WORKERS_TOKEN = "workers-token-0123456789abcdef01"
    }
}
