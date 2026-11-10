package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `installHttpPlugins` wiring: the hardening headers, the configured CORS policy and the
 * request body limit must reach a real response served by the booted module, so dropping
 * `configureSecurityHeaders`, `configureCors` or the `RequestBodyLimit` install from
 * `installHttpPlugins` fails a test. The plugins' own unit cases stay in `CorsConfigTest` /
 * `ApplicationTest`; these go through `configureServer`.
 */
class HttpPluginsWiringTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    private val pinnedOrigin = "http://console.example.com"

    private fun config(
        corsAllowedOrigins: List<String> = listOf(pinnedOrigin),
        maxRequestBodyBytes: Long = 10L * 1024 * 1024,
    ) = AppConfig(
        server =
            Server(
                apiAuth = AuthConfig.Unsecured,
                corsAllowedOrigins = corsAllowedOrigins,
                maxRequestBodyBytes = maxRequestBodyBytes,
            ),
        agentsSearchPath = agentsDir.absolutePath,
        workspaceRootPath = workspaceDir.absolutePath,
        databasePath = File(workspaceDir, "homer.db").absolutePath,
        langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
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
    fun `a real response carries the hardening headers`() =
        testApplication {
            application { configureServer(config()) }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("same-origin", response.headers["Referrer-Policy"])
            assertEquals("default-src 'self'", response.headers["Content-Security-Policy"])
        }

    @Test
    fun `the pinned origin is allowed with credentials and any other origin is not`() =
        testApplication {
            application { configureServer(config()) }

            val allowed =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Origin, pinnedOrigin)
                }
            assertEquals(pinnedOrigin, allowed.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("true", allowed.headers[HttpHeaders.AccessControlAllowCredentials])

            val disallowed =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Origin, "http://evil.example.com")
                }
            assertNull(
                disallowed.headers[HttpHeaders.AccessControlAllowOrigin],
                "an origin outside corsAllowedOrigins must get no allow header",
            )
            assertNull(disallowed.headers[HttpHeaders.AccessControlAllowCredentials])
        }

    @Test
    fun `a body over server maxRequestBodyBytes is refused with 413`() =
        testApplication {
            writeBodyInputAgent()
            application { configureServer(config(maxRequestBodyBytes = 1024)) }

            val response =
                client.post("/api/v1/agent/body-input") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"user":"${"x".repeat(4096)}"}""")
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }
}
