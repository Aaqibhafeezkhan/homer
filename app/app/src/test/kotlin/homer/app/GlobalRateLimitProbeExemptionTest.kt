package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.LivenessResponse
import homer.app.models.OpenTelemetryExport
import homer.app.models.RateLimitConfig
import homer.app.models.ReadinessResponse
import homer.app.models.Server
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `/api/v1/livez` and `/api/v1/readyz` probes must not draw from any rate
 * limiter (`RateLimiting.kt` `configureRateLimiting`).
 *
 * These tests assert the denial path (§12): from a drained *global* bucket and
 * from an exhausted *per-caller* bucket, including with the drain flag set,
 * while the ceiling still binds everything else so the exemption did not
 * disable either limiter. Why: `app/SERVER.md`, "Liveness & readiness probes".
 */
class GlobalRateLimitProbeExemptionTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    /**
     * `isDraining` is a JVM-global flag flipped by `ApplicationStopping`; each test
     * host's shutdown leaves it set for whichever test runs next, which would make
     * `/readyz` answer 503 here. Reset it on both sides so the drain state is
     * deterministic, and never depends on JUnit's method order.
     */
    @BeforeEach
    @AfterEach
    fun resetDrainFlag() {
        isDraining.set(false)
    }

    /** A bucket of [limit] requests, refilling far beyond the test's lifetime. */
    private fun config(
        limit: Int = 2,
        perCallerLimit: Int = 2,
    ) = AppConfig(
        server =
            Server(
                apiAuth = AuthConfig.Unsecured,
                rateLimit = RateLimitConfig(limit = limit, refillPeriod = 60),
                perCallerRateLimit = RateLimitConfig(limit = perCallerLimit, refillPeriod = 60),
            ),
        agentsSearchPath = agentsDir.absolutePath,
        workspaceRootPath = workspaceDir.absolutePath,
        databasePath = File(workspaceDir, "homer.db").absolutePath,
        langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
        openTelemetryExport = OpenTelemetryExport.LOG,
    )

    @Test
    fun `drained global bucket leaves the probes 200 and still 429s an ordinary route`() =
        testApplication {
            application { configureServer(config()) }

            // Drain the shared bucket on a route that must keep its ceiling.
            repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/heartbeat").status) }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/api/v1/heartbeat").status,
                "the global bucket must be exhausted before the probes are checked",
            )

            // The probes are exempt: the kubelet sees 200, not 429, under the same load.
            val livez = client.get("/api/v1/livez")
            assertEquals(HttpStatusCode.OK, livez.status, "liveness must never be rate-limited — a 429 restarts the pod")
            assertEquals("alive", apiJson.decodeFromString<LivenessResponse>(livez.bodyAsText()).status)
            val readyz = client.get("/api/v1/readyz")
            assertEquals(HttpStatusCode.OK, readyz.status, "readiness must report the drain state, never 429")
            assertEquals("ready", apiJson.decodeFromString<ReadinessResponse>(readyz.bodyAsText()).status)

            // The drained-bucket + draining combination is the state the pod actually
            // hits during a graceful shutdown: readiness must surface the drain as
            // 503 — and still never as 429 — while liveness stays 200.
            isDraining.set(true)
            val drainingReadyz = client.get("/api/v1/readyz")
            assertEquals(HttpStatusCode.ServiceUnavailable, drainingReadyz.status, "drain state must surface, and still not as 429")
            assertEquals("draining", apiJson.decodeFromString<ReadinessResponse>(drainingReadyz.bodyAsText()).status)
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/livez").status,
                "liveness must stay 200 during drain even with the bucket drained",
            )
            // The exemption is a probe key, not a disabled limiter — and drain is
            // not an exemption: the ceiling still binds during shutdown.
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/api/v1/heartbeat").status,
                "ordinary routes must still 429 during drain",
            )
        }

    @Test
    fun `probe requests do not consume the global bucket`() =
        testApplication {
            application { configureServer(config(limit = 2)) }

            // Probes first, then exactly the two secured requests the bucket allows.
            // If the probes drew from the same bucket, the third call below would 429.
            repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/livez").status) }
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/heartbeat").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/heartbeat").status)
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/api/v1/heartbeat").status,
                "the bucket must be exactly `limit` deep, unaffected by the probe traffic",
            )
        }

    @Test
    fun `drained per-caller bucket leaves the probes 200 and still 429s an ordinary route`() =
        testApplication {
            application { configureServer(config(limit = 100, perCallerLimit = 2)) }

            // In unsecured mode the per-caller key falls back to the TCP peer, so the
            // test client is one caller: drain its bucket on a secured route.
            repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/status").status) }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/api/v1/status").status,
                "the per-caller bucket must be exhausted before the probes are checked",
            )

            // The probes sit outside the `perCaller` wrapper by mount position; this
            // guards that structural fact, which nothing in `configureRateLimiting`
            // itself enforces.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/livez").status,
                "liveness must never be rate-limited — a 429 restarts the pod",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/readyz").status,
                "readiness must never be rate-limited by the per-caller bucket",
            )
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/api/v1/status").status,
                "ordinary routes must still share the exhausted per-caller bucket",
            )
        }

    @Test
    fun `isProbePath matches only the two probe routes`() {
        assertTrue(isProbePath("/api/v1/livez"))
        assertTrue(isProbePath("/api/v1/readyz"))

        // Same /api/v1 family, same access-log exclusion, but they keep the ceiling.
        assertFalse(isProbePath("/api/v1/health"))
        assertFalse(isProbePath("/api/v1/heartbeat"))
        assertFalse(isProbePath("/api/v1/livez/x"))
        assertFalse(isProbePath("/api/v1/readyz/x"))
        assertFalse(isProbePath("/api/v1/stream/processes"))
        assertFalse(isProbePath("/api/v1/runner/instances/1/claim"))
        assertFalse(isProbePath("/livez"))
    }
}
