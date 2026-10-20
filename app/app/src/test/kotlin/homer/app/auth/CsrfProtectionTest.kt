package homer.app.auth

import homer.app.buildBearerProvider
import homer.app.configureStatusPages
import homer.app.infra.PasswordVerifier
import homer.app.models.AuthConfig
import homer.app.models.PasswordUserConfig
import homer.app.models.TokenConfig
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * H4 (security review 2026-06-08, ADR-0016): cookie/session-authenticated
 * requests must carry the `X-Homer-CSRF` header; token/HMAC callers are exempt.
 * The kill action moved off GET to DELETE.
 */
class CsrfProtectionTest {
    private val aliceHash = PasswordVerifier().hash("correct-horse", cost = 4)

    private val scheme =
        AuthConfig.Password(
            users = mapOf("alice" to PasswordUserConfig(passwordHash = aliceHash, endpoints = listOf("*"))),
            sessionSecret = "secret-key-v1-aaaaaaaaaaaaaaaaaa",
            sessionTtlMin = 60,
            cookieSecure = false,
            bcryptCost = 4,
        )

    private fun io.ktor.server.application.Application.installGuardedRoutes() {
        installPasswordSessions(scheme)
        install(ContentNegotiation) { json() }
        configureStatusPages()
        install(Authentication) {
            passwordSessionProvider("auth-session", scheme)
            buildBearerProvider(AuthConfig.Bearer(tokens = mapOf("ops" to TokenConfig(token = "valid-token"))))
        }
        routing {
            // Seed a valid session cookie without going through bcrypt login.
            get("/seed-session") {
                call.sessions.set(
                    HomerSession(username = "alice", exp = Instant.now().epochSecond + 3600),
                )
                call.respondText("ok")
            }
            authenticate("auth-session", "auth-bearer", strategy = AuthenticationStrategy.FirstSuccessful) {
                install(CsrfProtection)
                get("/api/v1/agents") { call.respondText("agents") }
                delete("/api/v1/kill/{processId}") { call.respondText("killed") }
            }
        }
    }

    @Test
    fun `session request without CSRF header is 403`() =
        testApplication {
            application { installGuardedRoutes() }
            val client = createClient { install(HttpCookies) }
            client.get("/seed-session")
            val resp = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }

    @Test
    fun `session request with CSRF header succeeds`() =
        testApplication {
            application { installGuardedRoutes() }
            val client = createClient { install(HttpCookies) }
            client.get("/seed-session")
            val resp =
                client.get("/api/v1/agents") {
                    header("X-Homer-CSRF", "1")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `bearer request without CSRF header succeeds - guard is a no-op for token auth`() =
        testApplication {
            application { installGuardedRoutes() }
            val resp =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer valid-token")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `kill over DELETE with CSRF header succeeds for session`() =
        testApplication {
            application { installGuardedRoutes() }
            val client = createClient { install(HttpCookies) }
            client.get("/seed-session")
            val resp =
                client.delete("/api/v1/kill/42") {
                    header("X-Homer-CSRF", "1")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `kill over GET is not routed`() =
        testApplication {
            application { installGuardedRoutes() }
            val resp =
                client.get("/api/v1/kill/42") {
                    header(HttpHeaders.Authorization, "Bearer valid-token")
                }
            assertEquals(true, resp.status.value in setOf(404, 405), "GET kill must not be routed; got ${resp.status}")
        }
}
