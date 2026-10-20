package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.routes.runnerBearerToken
import homer.app.runners.external.RunnerProtocol
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.jvm.javaio.toInputStream
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RunnerRouteBodyLimitTest {
    private val defaultLimit = 256L * 1024

    private fun config(vararg limits: Long) =
        AppConfig(
            server = Server(apiAuth = AuthConfig.Unsecured, maxRequestBodyBytes = defaultLimit),
            agentsSearchPath = "u",
            workspaceRootPath = "u",
            databasePath = "u",
            runners = limits.mapIndexed { i, l -> "d$i" to RunnerConfig.External(token = "t$i", maxTransferBytes = l) }.toMap(),
        )

    private val tarPath = "/api/v1/runner/sessions/s/ops/o/tar"

    @Test
    fun `a tar post is bounded by the presented token's own domain`() {
        val cfg = config(1_000, 5_000_000)
        assertEquals(1_000, runnerRouteBodyLimit(cfg, tarPath, "t0"))
        assertEquals(5_000_000, runnerRouteBodyLimit(cfg, tarPath, "t1"))
    }

    @Test
    fun `a tar post with no matching token gets only maxRequestBodyBytes`() {
        val cfg = config(1_000, 5_000_000)
        assertEquals(defaultLimit, runnerRouteBodyLimit(cfg, tarPath, null))
        assertEquals(defaultLimit, runnerRouteBodyLimit(cfg, tarPath, "not-a-domain-token"))
        assertEquals(defaultLimit, runnerRouteBodyLimit(config(), tarPath, "t0"))
    }

    @Test
    fun `non-tar runner paths and ordinary paths keep their own bounds`() {
        val cfg = config(1_000, 5_000_000)
        assertEquals(
            RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES.toLong(),
            runnerRouteBodyLimit(cfg, "/api/v1/runner/sessions/s/ops/o/output", "t1"),
        )
        assertEquals(
            RunnerProtocol.MAX_NEXT_BODY_BYTES.toLong(),
            runnerRouteBodyLimit(cfg, "/api/v1/runner/sessions/s/next", "t1"),
        )
        assertEquals(defaultLimit, runnerRouteBodyLimit(cfg, "/api/v1/runner/register", "t1"))
        assertEquals(defaultLimit, runnerRouteBodyLimit(cfg, "/api/v1/agent/echo", "t1"))
    }

    /** Mirrors the application-level install in `configureServer`. */
    private fun ApplicationTestBuilder.mount(cfg: AppConfig) {
        application {
            install(RequestBodyLimit) {
                bodyLimit { call -> runnerRouteBodyLimit(cfg, call.request.path(), call.request.runnerBearerToken()) }
            }
            configureStatusPages()
            routing {
                post("/api/v1/runner/sessions/{sid}/ops/{opId}/tar") {
                    call.receiveChannel().toInputStream().readBytes()
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/api/v1/runner/sessions/{sid}/next") {
                    call.receiveText()
                    call.respond(HttpStatusCode.Accepted)
                }
            }
        }
    }

    @Test
    fun `a small-domain token cannot post a body sized for the large domain's ceiling`() =
        testApplication {
            val cfg = config(1_000, 5_000_000)
            mount(cfg)
            val body = ByteArray(64 * 1024)

            val small =
                client.post(tarPath) {
                    header(HttpHeaders.Authorization, "Bearer t0")
                    setBody(body)
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, small.status)

            val large =
                client.post(tarPath) {
                    header(HttpHeaders.Authorization, "Bearer t1")
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Accepted, large.status, "the large domain's own ceiling still admits its bodies")
        }

    @Test
    fun `a next body past the protocol cap is refused at the limit`() =
        testApplication {
            mount(config(1_000, 5_000_000))
            val nextPath = "/api/v1/runner/sessions/s/next"

            val over =
                client.post(nextPath) {
                    header(HttpHeaders.Authorization, "Bearer t1")
                    setBody(ByteArray(RunnerProtocol.MAX_NEXT_BODY_BYTES + 1))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, over.status)

            val under =
                client.post(nextPath) {
                    header(HttpHeaders.Authorization, "Bearer t1")
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.Accepted, under.status, "a real poll body is far under the cap")
        }

    @Test
    fun `an unauthenticated tar post gets only maxRequestBodyBytes`() =
        testApplication {
            mount(config(1_000, 5_000_000))

            val response = client.post(tarPath) { setBody(ByteArray((defaultLimit + 1).toInt())) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }
}
