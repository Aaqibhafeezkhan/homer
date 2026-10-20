package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.PasswordUserConfig
import homer.app.models.Server
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H3 (security review 2026-06-08, ADR-0016): credentialed CORS must only be
 * enabled for pinned origins, and a cookie/session (Password) auth scheme
 * without `corsAllowedOrigins` must fail startup rather than ship a CORS
 * credential reflector.
 */
class CorsConfigTest {
    private val passwordScheme =
        AuthConfig.Password(
            users = mapOf("alice" to PasswordUserConfig(passwordHash = "\$2a\$04\$xxxxxxxxxxxxxxxxxxxxxx", endpoints = listOf("*"))),
            sessionSecret = "secret-key-v1-aaaaaaaaaaaaaaaaaa",
            sessionTtlMin = 60,
            cookieSecure = false,
            bcryptCost = 4,
        )

    private fun configWith(
        apiAuth: AuthConfig,
        corsAllowedOrigins: List<String>,
    ) = AppConfig(
        server = Server(apiAuth = apiAuth, corsAllowedOrigins = corsAllowedOrigins),
        agentsSearchPath = "/tmp/agents",
        workspaceRootPath = "/tmp/workspace",
        databasePath = "/tmp/homer-test.db",
        langfuse = LangfuseConfig(serverUrl = "http://localhost", publicKey = "pk", secretKey = "sk"),
        openTelemetryExport = OpenTelemetryExport.LOG,
    )

    // ---- validateCorsForCookieAuth (fail-fast) ----

    @Test
    fun `password auth with empty corsAllowedOrigins throws ConfigurationException`() {
        val ex =
            assertFailsWith<ConfigurationException> {
                validateCorsForCookieAuth(configWith(passwordScheme, emptyList()))
            }
        assertEquals(true, ex.message?.contains("corsAllowedOrigins"), "message must name the fix")
    }

    @Test
    fun `password auth inside Combined with empty corsAllowedOrigins throws`() {
        val combined = AuthConfig.Combined(schemes = listOf(passwordScheme))
        assertFailsWith<ConfigurationException> {
            validateCorsForCookieAuth(configWith(combined, emptyList()))
        }
    }

    @Test
    fun `password auth with explicit origins does not throw`() {
        validateCorsForCookieAuth(configWith(passwordScheme, listOf("https://console.example.com")))
    }

    @Test
    fun `unsecured auth with empty corsAllowedOrigins does not throw`() {
        validateCorsForCookieAuth(configWith(AuthConfig.Unsecured, emptyList()))
    }

    // ---- configureCors credential behavior ----

    @Test
    fun `empty origins allow any host as wildcard but without Allow-Credentials`() =
        testApplication {
            application {
                configureCors(emptyList())
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://anything.example.com")
                }
            // Without credentials, anyHost() answers `*` rather than reflecting
            // the request Origin — a `*` response can never carry credentials.
            assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertNull(
                response.headers[HttpHeaders.AccessControlAllowCredentials],
                "anyHost() must not advertise credentials — that is the H3 reflector",
            )
        }

    @Test
    fun `preflight allows the X-Homer-CSRF request header`() =
        testApplication {
            application {
                configureCors(listOf("http://console.example.com"))
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            // The console sends X-Homer-CSRF (non-safelisted) on every request, so
            // the cross-origin preflight must see it echoed in Allow-Headers or the
            // browser blocks the real request (ADR-0016).
            val response =
                client.options("/test") {
                    header(HttpHeaders.Origin, "http://console.example.com")
                    header(HttpHeaders.AccessControlRequestMethod, "GET")
                    header(HttpHeaders.AccessControlRequestHeaders, "X-Homer-CSRF")
                }
            val allowed = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty()
            assertTrue(
                allowed.lowercase().contains("x-homer-csrf"),
                "preflight Allow-Headers must include X-Homer-CSRF; got '$allowed'",
            )
        }

    @Test
    fun `pinned origin echoes origin with Allow-Credentials true`() =
        testApplication {
            application {
                configureCors(listOf("http://console.example.com"))
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://console.example.com")
                }
            assertEquals(
                "http://console.example.com",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
            assertEquals(
                "true",
                response.headers[HttpHeaders.AccessControlAllowCredentials],
                "pinned origins keep credentialed CORS for cookie auth",
            )
        }
}
