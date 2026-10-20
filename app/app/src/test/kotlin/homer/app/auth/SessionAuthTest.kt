package homer.app.auth

import homer.app.models.AuthConfig
import homer.app.models.PasswordUserConfig
import homer.app.models.SameSiteMode
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.http.setCookie
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionAuthTest {
    private val baseScheme =
        AuthConfig.Password(
            users =
                mapOf(
                    "alice" to PasswordUserConfig(passwordHash = "irrelevant", endpoints = listOf("*")),
                    "bob" to PasswordUserConfig(passwordHash = "irrelevant", endpoints = listOf("/api/v1/agents")),
                ),
            sessionSecret = "secret-key-v1-aaaaaaaaaaaaaaaaaa",
            sessionTtlMin = 60,
            cookieSecure = false,
        )

    @Test
    fun `valid session reaches authenticated route`() =
        testApplication {
            application {
                installPasswordSessions(baseScheme)
                install(Authentication) {
                    passwordSessionProvider("auth-session", baseScheme)
                }
                routing {
                    // login-equivalent stub that sets the session directly
                    get("/seed") {
                        call.sessions.set(
                            HomerSession(
                                username = "alice",
                                exp = Instant.now().epochSecond + 600,
                            ),
                        )
                        call.respondText("ok")
                    }
                    authenticate("auth-session") {
                        get("/api/v1/agents") { call.respondText("agents") }
                    }
                }
            }
            val client = createClient { install(HttpCookies) }
            val seed = client.get("/seed")
            assertEquals(HttpStatusCode.OK, seed.status)
            val resp = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("agents", resp.bodyAsText())
        }

    @Test
    fun `no cookie returns 401`() =
        testApplication {
            application {
                installPasswordSessions(baseScheme)
                install(Authentication) {
                    passwordSessionProvider("auth-session", baseScheme)
                }
                routing {
                    authenticate("auth-session") {
                        get("/api/v1/agents") { call.respondText("agents") }
                    }
                }
            }
            val resp = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `expired session returns 401`() =
        testApplication {
            application {
                installPasswordSessions(baseScheme)
                install(Authentication) {
                    passwordSessionProvider("auth-session", baseScheme)
                }
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
                    authenticate("auth-session") {
                        get("/api/v1/agents") { call.respondText("agents") }
                    }
                }
            }
            val client = createClient { install(HttpCookies) }
            client.get("/seed-expired")
            val resp = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `unknown user in session returns 401`() =
        testApplication {
            application {
                installPasswordSessions(baseScheme)
                install(Authentication) {
                    passwordSessionProvider("auth-session", baseScheme)
                }
                routing {
                    get("/seed-ghost") {
                        call.sessions.set(
                            HomerSession(
                                username = "carol",
                                exp = Instant.now().epochSecond + 600,
                            ),
                        )
                        call.respondText("ok")
                    }
                    authenticate("auth-session") {
                        get("/api/v1/agents") { call.respondText("agents") }
                    }
                }
            }
            val client = createClient { install(HttpCookies) }
            client.get("/seed-ghost")
            val resp = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `valid session authenticates regardless of user's (deprecated) endpoints scope`() =
        testApplication {
            // bob's `endpoints` field is deprecated and no longer enforced by the
            // provider — a valid session authenticates on any route; authorization
            // by grants happens at the route (ADR-0037).
            application {
                installPasswordSessions(baseScheme)
                install(Authentication) {
                    passwordSessionProvider("auth-session", baseScheme)
                }
                routing {
                    get("/seed-bob") {
                        call.sessions.set(
                            HomerSession(
                                username = "bob",
                                exp = Instant.now().epochSecond + 600,
                            ),
                        )
                        call.respondText("ok")
                    }
                    authenticate("auth-session") {
                        get("/api/v1/agents") { call.respondText("agents") }
                        get("/api/v1/status") { call.respondText("status") }
                    }
                }
            }
            val client = createClient { install(HttpCookies) }
            client.get("/seed-bob")
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/agents").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/status").status)
        }

    @Test
    fun `session cookie carries HttpOnly Path and Max-Age`() =
        testApplication {
            application {
                installPasswordSessions(baseScheme)
                routing {
                    get("/seed") {
                        call.sessions.set(
                            HomerSession(
                                username = "alice",
                                exp = Instant.now().epochSecond + 600,
                            ),
                        )
                        call.respondText("ok")
                    }
                }
            }
            val resp = client.get("/seed")
            val setCookie = resp.setCookie().firstOrNull { it.name == baseScheme.cookieName }
            assertNotNull(setCookie, "Set-Cookie header for ${baseScheme.cookieName} expected")
            assertTrue(setCookie.httpOnly, "Cookie must be HttpOnly")
            assertEquals("/", setCookie.path)
            // sessionTtlMin = 60 → 3600 seconds
            assertEquals(3600, setCookie.maxAge)
            // SameSite=Lax goes through the extensions map → check the raw header
            val raw = resp.headers["Set-Cookie"]!!
            val parsed = parseServerSetCookieHeader(raw)
            assertEquals("Lax", parsed.extensions["SameSite"])
        }

    @Test
    fun `cookieSameSite NONE issues a SameSite=None Secure cookie`() =
        testApplication {
            val crossOrigin = baseScheme.copy(cookieSameSite = SameSiteMode.NONE, cookieSecure = true)
            application {
                installPasswordSessions(crossOrigin)
                routing {
                    get("/seed") {
                        call.sessions.set(
                            HomerSession(
                                username = "alice",
                                exp = Instant.now().epochSecond + 600,
                            ),
                        )
                        call.respondText("ok")
                    }
                }
            }
            val resp = client.get("/seed")
            val setCookie = resp.setCookie().firstOrNull { it.name == crossOrigin.cookieName }
            assertNotNull(setCookie, "Set-Cookie header for ${crossOrigin.cookieName} expected")
            assertTrue(setCookie.secure, "SameSite=None cookie must be Secure")
            val raw = resp.headers["Set-Cookie"]!!
            val parsed = parseServerSetCookieHeader(raw)
            assertEquals("None", parsed.extensions["SameSite"])
        }

    @Test
    fun `cookieSameSite NONE without cookieSecure fails config validation`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                baseScheme.copy(cookieSameSite = SameSiteMode.NONE, cookieSecure = false)
            }
        assertTrue(
            ex.message!!.contains("cookieSameSite=NONE requires cookieSecure=true"),
            "expected the None-without-Secure guard message, got: ${ex.message}",
        )
    }

    @Test
    fun `session signed with previous secret still verifies during rotation`() {
        val oldSecret = "old-secret-v0-aaaaaaaaaaaaaaaaaa"
        val newSecret = "new-secret-v2-bbbbbbbbbbbbbbbbbb"
        val oldOnly = baseScheme.copy(sessionSecret = oldSecret, sessionSecretPrevious = null)
        val rotated = baseScheme.copy(sessionSecret = newSecret, sessionSecretPrevious = oldSecret)

        // Mint a session cookie under the OLD secret in a one-off app.
        var mintedCookie: io.ktor.http.Cookie? = null
        testApplication {
            application {
                installPasswordSessions(oldOnly)
                routing {
                    get("/seed") {
                        call.sessions.set(
                            HomerSession(
                                username = "alice",
                                exp = Instant.now().epochSecond + 600,
                            ),
                        )
                        call.respondText("ok")
                    }
                }
            }
            val resp = client.get("/seed")
            mintedCookie = resp.setCookie().first { it.name == oldOnly.cookieName }
        }
        val cookie = assertNotNull(mintedCookie, "Failed to mint cookie under old secret")

        // Replay the old-secret-signed cookie against the rotated app — accepted via previous-key transformer.
        testApplication {
            application {
                installPasswordSessions(rotated)
                install(Authentication) {
                    passwordSessionProvider("auth-session", rotated)
                }
                routing {
                    authenticate("auth-session") {
                        get("/api/v1/agents") { call.respondText("agents") }
                    }
                }
            }
            val resp =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Cookie, "${cookie.name}=${cookie.value}")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `tampered signature returns 401`() =
        testApplication {
            application {
                installPasswordSessions(baseScheme)
                install(Authentication) {
                    passwordSessionProvider("auth-session", baseScheme)
                }
                routing {
                    authenticate("auth-session") {
                        get("/api/v1/agents") { call.respondText("agents") }
                    }
                }
            }
            val resp =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Cookie, "${baseScheme.cookieName}=bogus-value-with-no-valid-mac")
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
}
