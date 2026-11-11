package homer.app

import homer.app.infra.PasswordVerifier
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.PasswordUserConfig
import homer.app.models.Server
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The session cookie `installPasswordSessions` wires (`auth/SessionAuth.kt`): a login through the
 * real startup path must issue a cookie that is `HttpOnly` on `Path=/`, so dropping either
 * attribute from the plugin's cookie config fails a test. `PasswordAuthTest` mounts the plugin
 * itself — which is exactly why those lines could be dropped unnoticed — so these go through
 * `configureServer` instead. The secret and password hash are inline fixtures.
 */
@ExtendWith(ResetDrainingExtension::class)
class SessionCookieWiringTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    private val passwordHash = PasswordVerifier().hash("correct-horse", cost = 4)

    private fun config(cookieSecure: Boolean) =
        AppConfig(
            server =
                Server(
                    apiAuth =
                        AuthConfig.Password(
                            users = mapOf("alice" to PasswordUserConfig(passwordHash = passwordHash, role = "admin")),
                            sessionSecret = "session-secret-fixture-0123456789abcdef",
                            sessionTtlMin = 60,
                            cookieSecure = cookieSecure,
                            bcryptCost = 4,
                        ),
                    // Cookie auth requires pinned CORS origins (ADR-0016).
                    corsAllowedOrigins = listOf("http://console.example.com"),
                ),
            agentsSearchPath = agentsDir.absolutePath,
            workspaceRootPath = workspaceDir.absolutePath,
            databasePath = File(workspaceDir, "homer.db").absolutePath,
            langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
            openTelemetryExport = OpenTelemetryExport.LOG,
        )

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.login(): io.ktor.client.statement.HttpResponse =
        client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","password":"correct-horse"}""")
        }

    @Test
    fun `the login response sets an HttpOnly session cookie on path slash`() =
        testApplication {
            application { configureServer(config(cookieSecure = true)) }

            val response = login()
            assertEquals(HttpStatusCode.OK, response.status)

            val cookie = response.setCookie().firstOrNull { it.name == "homer_session" }
            assertNotNull(cookie, "login must issue the session cookie")
            assertTrue(cookie.httpOnly, "the session cookie must be HttpOnly (ADR-0011)")
            assertEquals("/", cookie.path)
            assertTrue(cookie.secure, "cookieSecure=true must still mark the cookie Secure")
        }

    @Test
    fun `the cookie keeps HttpOnly and Path when cookieSecure is off`() =
        testApplication {
            application { configureServer(config(cookieSecure = false)) }

            val response = login()
            assertEquals(HttpStatusCode.OK, response.status)

            val cookie = response.setCookie().firstOrNull { it.name == "homer_session" }
            assertNotNull(cookie, "login must issue the session cookie")
            assertTrue(cookie.httpOnly, "the session cookie must be HttpOnly (ADR-0011)")
            assertEquals("/", cookie.path)
            assertFalse(cookie.secure, "cookieSecure=false must be honoured")
        }
}
