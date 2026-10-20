package homer.e2e.support

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.appendIfNameAbsent
import kotlinx.serialization.json.Json

/**
 * Shared configuration for the black-box suite: base URL and token come from
 * the environment (set by docker-compose.e2e.yml or a local shell), and two
 * lazily-built clients — one sending the operator bearer token by default,
 * one sending no credentials. Clients live for the whole JVM; the test JVM
 * exit closes them.
 */
object E2e {
    val baseUrl: String = System.getenv("E2E_BASE_URL").orEmpty().trimEnd('/')
    val token: String = System.getenv("E2E_TOKEN").orEmpty()

    val json =
        Json {
            ignoreUnknownKeys = true
        }

    val client: HttpClient by lazy { buildClient(authenticated = true) }
    val unauthClient: HttpClient by lazy { buildClient(authenticated = false) }

    private fun buildClient(authenticated: Boolean): HttpClient =
        HttpClient(CIO) {
            engine {
                // SSE subscriptions and long polls outlive CIO's 15 s default;
                // per-call deadlines are enforced by Awaits/SseCollector instead.
                requestTimeout = 0
            }
            // Half the suite asserts 4xx responses — never throw on them.
            expectSuccess = false
            install(ContentNegotiation) {
                json(json)
            }
            install(SSE)
            defaultRequest {
                url(baseUrl)
                if (authenticated && token.isNotEmpty()) {
                    // appendIfNameAbsent lets individual requests override the
                    // credential (wrong-token and no-token auth tests).
                    headers.appendIfNameAbsent(HttpHeaders.Authorization, "Bearer $token")
                }
            }
        }
}
