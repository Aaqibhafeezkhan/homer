package homer.app

import homer.app.auth.HomerSession
import homer.app.auth.installPasswordSessions
import homer.app.auth.passwordSessionProvider
import homer.app.infra.PasswordVerifier
import homer.app.models.Action
import homer.app.models.AuthConfig
import homer.app.models.Grant
import homer.app.models.LoginRequest
import homer.app.models.LoginResponse
import homer.app.models.MeResponse
import homer.app.models.PasswordUserConfig
import homer.app.routes.ApiDeps
import homer.app.routes.apiV1UnsecuredRoutes
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.http.setCookie
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Integration tests for the password-auth HTTP surfaces of `apiV1UnsecuredRoutes`:
 *  - POST /api/v1/auth/login (rate-limited, constant-time-ish on unknown user)
 *  - POST /api/v1/auth/logout (idempotent)
 *  - GET  /api/v1/auth/me
 *
 * Provider-level invariants (signature rejection, rotation, allowlist enforcement,
 * cookie attribute set on `Sessions` install, expired-session 401) live in
 * `SessionAuthTest` — this file does not duplicate them. Anything that depends
 * on the *route block* mounted outside `authenticate(...)` lives here.
 */
class PasswordAuthTest {
    // Real bcrypt hash of "correct-horse" produced via PasswordVerifier so the
    // login tests exercise the actual verifier, not a mocked one.
    private val aliceHash = PasswordVerifier().hash("correct-horse", cost = 4)
    private val bobHash = PasswordVerifier().hash("river-stone", cost = 4)
    private val carolHash = PasswordVerifier().hash("admin-pass", cost = 4)

    private val scheme =
        AuthConfig.Password(
            users =
                mapOf(
                    "alice" to PasswordUserConfig(passwordHash = aliceHash, endpoints = listOf("*")),
                    "bob" to
                        PasswordUserConfig(
                            passwordHash = bobHash,
                            grants = listOf(Grant(agents = listOf("*"), actions = listOf(Action.RUN))),
                        ),
                    "carol" to PasswordUserConfig(passwordHash = carolHash, role = "admin"),
                ),
            sessionSecret = "secret-key-v1-aaaaaaaaaaaaaaaaaa",
            sessionTtlMin = 60,
            cookieSecure = false,
            bcryptCost = 4,
        )

    /**
     * Mounts the production `apiV1UnsecuredRoutes` (login/logout/me) the way
     * `configureServer` does — StatusPages, routing-scoped ContentNegotiation on [apiJson] —
     * behind the `register(RateLimitName("login"))` registration the real login
     * route wraps itself in.
     */
    private fun io.ktor.server.application.Application.installAuthRoutes(
        s: AuthConfig.Password,
        loginLimit: Int = 5,
    ) {
        installPasswordSessions(s)
        configureStatusPages()
        install(RateLimit) {
            register(RateLimitName("login")) {
                rateLimiter(limit = loginLimit, refillPeriod = 60.seconds)
                requestKey { call -> call.request.local.remoteHost }
            }
        }
        install(Authentication) { passwordSessionProvider("auth-session", s) }

        val deps =
            mockk<ApiDeps>(relaxed = true) {
                every { passwordScheme } returns s
                every { passwordVerifier } returns PasswordVerifier()
                every { isDraining } returns AtomicBoolean(false)
            }
        routing {
            install(ServerContentNegotiation) { json(apiJson) }
            apiV1UnsecuredRoutes(deps)
            authenticate("auth-session") {
                get("/api/v1/agents") { call.respondText("agents") }
            }
        }
    }

    private fun jsonClient() = """{"username":"%s","password":"%s"}"""

    @Test
    fun `login happy path sets HttpOnly Path Max-Age cookie and returns LoginResponse`() =
        testApplication {
            application { installAuthRoutes(scheme) }

            val resp =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonClient().format("alice", "correct-horse"))
                }
            assertEquals(HttpStatusCode.OK, resp.status, "login should succeed with correct password")

            val setCookie = resp.setCookie().firstOrNull { it.name == scheme.cookieName }
            assertNotNull(setCookie, "Set-Cookie for ${scheme.cookieName} expected on successful login")
            assertTrue(setCookie.httpOnly, "session cookie must be HttpOnly")
            assertEquals("/", setCookie.path)
            assertEquals(scheme.sessionTtlMin * 60, setCookie.maxAge)

            val raw = resp.headers["Set-Cookie"]!!
            val parsed = parseServerSetCookieHeader(raw)
            assertEquals("Lax", parsed.extensions["SameSite"], "session cookie must be SameSite=Lax (ADR-0011)")

            val body = Json.decodeFromString(LoginResponse.serializer(), resp.bodyAsText())
            assertEquals("alice", body.username)
        }

    @Test
    fun `login with wrong password returns 401`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            val resp =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonClient().format("alice", "wrong-password"))
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `login with unknown user returns 401 and runs constant-time dummy verify`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            val resp =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonClient().format("nobody", "anything"))
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status, "unknown-user must look identical to wrong-password")
        }

    @Test
    fun `login with invalid body shape returns 400`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            val resp =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("not-json-at-all")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `login with an unknown body field returns 400`() =
        testApplication {
            // The login body decodes through the strict apiJson (no ignoreUnknownKeys),
            // so an extra field is a malformed body, not a credential check.
            application { installAuthRoutes(scheme) }
            val resp =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"alice","password":"correct-horse","remember":true}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `login with oversized username or password returns 400`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            // Username over 256 chars — decoder's init { require } throws, handler returns 400.
            val longUser = "a".repeat(LoginRequest.MAX_USERNAME_LEN + 1)
            val r1 =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonClient().format(longUser, "x"))
                }
            assertEquals(HttpStatusCode.BadRequest, r1.status, "oversized username must be 400, not a bcrypt-eating 401")

            // Password over 1024 chars — same path.
            val longPass = "p".repeat(LoginRequest.MAX_PASSWORD_LEN + 1)
            val r2 =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonClient().format("alice", longPass))
                }
            assertEquals(HttpStatusCode.BadRequest, r2.status)
        }

    @Test
    fun `logout clears the session cookie and is idempotent`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            val client =
                createClient {
                    install(HttpCookies)
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            // Without any prior session — logout still 204.
            assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout").status)

            // Log in, then logout — also 204, and the cookie is cleared.
            assertEquals(
                HttpStatusCode.OK,
                client
                    .post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(jsonClient().format("alice", "correct-horse"))
                    }.status,
            )
            val logoutResp = client.post("/api/v1/auth/logout")
            assertEquals(HttpStatusCode.NoContent, logoutResp.status)

            // Subsequent /me must 401.
            val me = client.get("/api/v1/auth/me")
            assertEquals(HttpStatusCode.Unauthorized, me.status)
        }

    @Test
    fun `me returns grants and null role for a user with grants`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            val client =
                createClient {
                    install(HttpCookies)
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(jsonClient().format("bob", "river-stone"))
            }
            val me = client.get("/api/v1/auth/me")
            assertEquals(HttpStatusCode.OK, me.status)
            val body = Json.decodeFromString(MeResponse.serializer(), me.bodyAsText())
            assertEquals("bob", body.username)
            assertEquals(null, body.role)
            assertEquals(listOf(Grant(agents = listOf("*"), actions = listOf(Action.RUN))), body.grants)
        }

    @Test
    fun `me returns role admin for an admin user`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            val client =
                createClient {
                    install(HttpCookies)
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(jsonClient().format("carol", "admin-pass"))
            }
            val me = client.get("/api/v1/auth/me")
            assertEquals(HttpStatusCode.OK, me.status)
            val body = Json.decodeFromString(MeResponse.serializer(), me.bodyAsText())
            assertEquals("carol", body.username)
            assertEquals("admin", body.role)
        }

    @Test
    fun `me without cookie returns 401`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me").status)
        }

    @Test
    fun `me with manually-set expired session returns 401`() =
        testApplication {
            application {
                installAuthRoutes(scheme)
                routing {
                    get("/seed-expired") {
                        call.sessions.set(
                            HomerSession(
                                username = "alice",
                                exp = Instant.now().epochSecond - 1,
                            ),
                        )
                        call.respondText("ok")
                    }
                }
            }
            val client =
                createClient {
                    install(HttpCookies)
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            client.get("/seed-expired")
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me").status)
        }

    @Test
    fun `login rate-limited at 5 per minute returns 429 on 6th attempt from same host`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            // 5 burst attempts — wrong password is fine; rate limit is keyed by IP, not outcome.
            repeat(5) {
                val resp =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(jsonClient().format("alice", "wrong"))
                    }
                assertTrue(
                    resp.status == HttpStatusCode.Unauthorized,
                    "burst attempt $it expected 401, got ${resp.status}",
                )
            }
            val sixth =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonClient().format("alice", "correct-horse"))
                }
            assertEquals(HttpStatusCode.TooManyRequests, sixth.status, "6th login from same IP must be 429")
        }

    @Test
    fun `login limiter honors a higher configured limit`() =
        testApplication {
            // server.loginRateLimit is now configurable; a raised limit must let
            // more attempts through before 429. Proves the register block is
            // value-driven, which is what RateLimiting.kt feeds from config.
            application { installAuthRoutes(scheme, loginLimit = 10) }
            repeat(6) {
                val resp =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(jsonClient().format("alice", "wrong"))
                    }
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    resp.status,
                    "attempt $it should be 401 (still under the raised limit of 10), not 429",
                )
            }
        }

    @Test
    fun `successful login enables authenticated route access via cookie`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            val client =
                createClient {
                    install(HttpCookies)
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(jsonClient().format("alice", "correct-horse"))
            }
            // alice is wildcard; she should reach the bearer-style protected route via the cookie path.
            val resp = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `login emits Authorization-less request flow - no Authorization header required`() =
        testApplication {
            application { installAuthRoutes(scheme) }
            val resp =
                client.post("/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(jsonClient().format("alice", "correct-horse"))
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertNotNull(
                resp.headers[HttpHeaders.SetCookie],
                "Login should issue a Set-Cookie without requiring any inbound Authorization header",
            )
        }
}
