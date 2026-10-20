package homer.app

import homer.app.models.AuthConfig
import homer.app.models.TokenConfig
import homer.app.routes.ApiDeps
import homer.app.routes.apiV1UnsecuredRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals

/**
 * Integration tests for bearer token authentication.
 *
 * Runs the production provider("auth-bearer") ([buildBearerProvider]): a
 * valid token authenticates regardless of request path. `TokenConfig.endpoints`
 * is a deprecated, tolerated-but-unenforced field here (see ADR-0037);
 * authorization by grants happens at the route, not in this provider.
 */
class AuthTest {
    // Token definitions used across tests
    private val tokenWildcardAll = TokenConfig(token = "token-all", endpoints = listOf("*"))
    private val tokenAgentPrefix = TokenConfig(token = "token-agent", endpoints = listOf("/api/v1/agent/*"))
    private val tokenExactStatus = TokenConfig(token = "token-status", endpoints = listOf("/api/v1/status"))

    private val allTokens: Map<String, TokenConfig> =
        mapOf(
            "user-all" to tokenWildcardAll,
            "user-agent" to tokenAgentPrefix,
            "user-status" to tokenExactStatus,
        )

    /**
     * Installs the production `auth-bearer` provider ([buildBearerProvider]) plus three protected routes.
     */
    private fun Application.installAuthApp(tokens: Map<String, TokenConfig>) {
        install(Authentication) { buildBearerProvider(AuthConfig.Bearer(tokens = tokens)) }

        routing {
            authenticate("auth-bearer") {
                get("/api/v1/agents") {
                    call.respond(HttpStatusCode.OK)
                }
                get("/api/v1/agent/myagent") {
                    call.respond(HttpStatusCode.OK)
                }
                get("/api/v1/status") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }

    // ===== Test 1: wildcard "*" token allows all routes =====

    @Test
    fun `valid token with wildcard endpoints allows all routes`() =
        testApplication {
            application { installAuthApp(allTokens) }

            val r1 =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer token-all")
                }
            assertEquals(HttpStatusCode.OK, r1.status, "Expected 200 on /api/v1/agents with wildcard token")

            val r2 =
                client.get("/api/v1/agent/myagent") {
                    header(HttpHeaders.Authorization, "Bearer token-all")
                }
            assertEquals(HttpStatusCode.OK, r2.status, "Expected 200 on /api/v1/agent/myagent with wildcard token")

            val r3 =
                client.get("/api/v1/status") {
                    header(HttpHeaders.Authorization, "Bearer token-all")
                }
            assertEquals(HttpStatusCode.OK, r3.status, "Expected 200 on /api/v1/status with wildcard token")
        }

    // ===== Test 2: prefix wildcard allows the sub-path =====

    @Test
    fun `valid token with prefix wildcard allows matching sub-path`() =
        testApplication {
            application { installAuthApp(allTokens) }

            val response =
                client.get("/api/v1/agent/myagent") {
                    header(HttpHeaders.Authorization, "Bearer token-agent")
                }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "Expected 200 on /api/v1/agent/myagent with /api/v1/agent/* token",
            )
        }

    // ===== Test 3: a valid token authenticates regardless of its (deprecated) endpoints scope =====

    @Test
    fun `valid token with prefix wildcard authenticates on a route outside that prefix`() =
        testApplication {
            application { installAuthApp(allTokens) }

            val response =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer token-agent")
                }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "Expected 200 on /api/v1/agents with /api/v1/agent/* token — the auth provider no longer " +
                    "gates on endpoints; authorization moves to the route (ADR-0037)",
            )
        }

    // ===== Test 4: wrong/unknown token returns 401 =====

    @Test
    fun `wrong token returns 401`() =
        testApplication {
            application { installAuthApp(allTokens) }

            val response =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer totally-wrong-token")
                }
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "Expected 401 for unknown token",
            )
        }

    // ===== Test 5: no Authorization header returns 401 =====

    @Test
    fun `missing Authorization header returns 401`() =
        testApplication {
            application { installAuthApp(allTokens) }

            val response = client.get("/api/v1/agents")
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "Expected 401 when Authorization header is absent",
            )
        }

    // ===== Test 6: a token scoped (deprecated field) to one prefix authenticates on both routes =====

    @Test
    fun `prefix-scoped token authenticates both on its prefix and outside it`() =
        testApplication {
            application { installAuthApp(allTokens) }

            val onPrefix =
                client.get("/api/v1/agent/myagent") {
                    header(HttpHeaders.Authorization, "Bearer token-agent")
                }
            assertEquals(
                HttpStatusCode.OK,
                onPrefix.status,
                "Expected 200 on /api/v1/agent/myagent",
            )

            val offPrefix =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer token-agent")
                }
            assertEquals(
                HttpStatusCode.OK,
                offPrefix.status,
                "Expected 200 on /api/v1/agents — the auth provider authenticates any valid token " +
                    "regardless of its (deprecated) endpoints scope; authorization moves to the route (ADR-0037)",
            )
        }

    // ===== Test 7: a token scoped (deprecated field) to one exact path authenticates on another too =====

    @Test
    fun `exact-path-scoped token authenticates both on that path and on other paths`() =
        testApplication {
            application { installAuthApp(allTokens) }

            val onPath =
                client.get("/api/v1/status") {
                    header(HttpHeaders.Authorization, "Bearer token-status")
                }
            assertEquals(
                HttpStatusCode.OK,
                onPath.status,
                "Expected 200 on exact-match path /api/v1/status",
            )

            val offPath =
                client.get("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer token-status")
                }
            assertEquals(
                HttpStatusCode.OK,
                offPath.status,
                "Expected 200 on /api/v1/agents with a status-only token — the auth provider authenticates " +
                    "any valid token regardless of its (deprecated) endpoints scope; authorization moves to " +
                    "the route (ADR-0037)",
            )
        }

    @Test
    fun `livez and readyz are reachable without bearer token under bearer auth (audit P1 #12)`() =
        testApplication {
            val deps =
                mockk<ApiDeps>(relaxed = true) {
                    every { isDraining } returns AtomicBoolean(false)
                    every { passwordScheme } returns null
                }
            application {
                installAuthApp(allTokens)
                routing {
                    // The production probe routes, mounted OUTSIDE authenticate(...)
                    // as configureRouting does, so kubelet (which never sends
                    // Authorization) can hit them.
                    install(ContentNegotiation) { json(apiJson) }
                    apiV1UnsecuredRoutes(deps)
                }
            }

            // No Authorization header — should be 200, not 401.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/livez").status,
                "Expected /livez to be reachable without a bearer token",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/readyz").status,
                "Expected /readyz to be reachable without a bearer token",
            )

            // Sanity check: a protected route under the same auth provider
            // still 401s without a token, confirming auth is installed.
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/status").status,
                "Expected /status to require a bearer token (sanity check)",
            )
        }
}
