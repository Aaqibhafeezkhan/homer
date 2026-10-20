package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.RateLimitConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Verifies the `perCaller` rate limiter wired in `RateLimiting.kt`
 * (`configureRateLimiting`, keyed by `callerKey`):
 *
 *  - **Per-caller isolation** — the bucket is keyed on the authenticated
 *    identity (`UserIdPrincipal.name`), so exhausting one token's budget does
 *    not affect another token. This only holds if `rateLimit` is nested
 *    *inside* `authenticate` so the principal is populated before the limiter's
 *    `requestKey` runs. If the nesting regressed (limiter wrapping auth), the
 *    principal would be null at key time, both tokens would collapse onto the
 *    shared IP bucket, and the isolation assertion below would fail.
 *  - **Unsecured fallback** — with no auth provider there is no principal, so
 *    the key falls back to `local.remoteHost`; same-host requests share one
 *    bucket. Mirrors the login limiter's IP stance (ADR-0011 / ADR-0014).
 */
class PerCallerRateLimitTest {
    private val tokens =
        mapOf(
            "tokenA" to TokenConfig(token = "secret-a"),
            "tokenB" to TokenConfig(token = "secret-b"),
        )

    /** A per-caller bucket of 2 requests, refilling far beyond the test's lifetime. */
    private fun config(apiAuth: AuthConfig) =
        AppConfig(
            server =
                Server(
                    apiAuth = apiAuth,
                    perCallerRateLimit = RateLimitConfig(limit = 2, refillPeriod = 60),
                ),
            agentsSearchPath = "unused",
            workspaceRootPath = "unused",
            databasePath = "unused",
        )

    @Test
    fun `per-caller bucket isolates one token from another`() =
        testApplication {
            application {
                configureRateLimiting(config(AuthConfig.Bearer(tokens = tokens)))
                install(Authentication) { buildBearerProvider(AuthConfig.Bearer(tokens = tokens)) }
                routing {
                    authenticate("auth-bearer") {
                        rateLimit(RateLimitName("perCaller")) {
                            get("/api/v1/ping") { call.respondText("pong") }
                        }
                    }
                }
            }

            // tokenA: two allowed, third over the limit.
            assertEquals(HttpStatusCode.OK, pingWith("secret-a").status)
            assertEquals(HttpStatusCode.OK, pingWith("secret-a").status)
            assertEquals(
                HttpStatusCode.TooManyRequests,
                pingWith("secret-a").status,
                "tokenA's 3rd request must be 429 — its own bucket is exhausted",
            )

            // tokenB has its own bucket — unaffected by tokenA exhausting theirs.
            assertEquals(
                HttpStatusCode.OK,
                pingWith("secret-b").status,
                "tokenB must still be served — proves the bucket is keyed per principal, not shared",
            )
        }

    @Test
    fun `unsecured mode falls back to IP key and shares one bucket per host`() =
        testApplication {
            application {
                configureRateLimiting(config(AuthConfig.Unsecured))
                routing {
                    rateLimit(RateLimitName("perCaller")) {
                        get("/api/v1/ping") { call.respondText("pong") }
                    }
                }
            }

            // No principal → key is the (single, shared) test host.
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/ping").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/ping").status)
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/api/v1/ping").status,
                "3rd same-host request must be 429 when all callers share the IP bucket",
            )
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.pingWith(token: String) =
        client.get("/api/v1/ping") { header(HttpHeaders.Authorization, "Bearer $token") }
}
