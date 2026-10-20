package homer.app

import homer.app.infra.PasswordVerifier
import homer.app.infra.WorkspaceConfig
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.PasswordUserConfig
import homer.app.models.RateLimitConfig
import homer.app.models.RunnerConfig
import homer.app.models.RunnerFilter
import homer.app.models.Server
import homer.app.runners.RunnerRegistry
import homer.app.runners.RunnerSessionParams
import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.RegisterInstanceResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.context.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.ktor.ext.get
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Runner-protocol requests with a valid domain token, and `POST /api/v1/auth/login`,
 * leave the unauthenticated global bucket (ADR-0014 amendment 2026-09-30).
 *
 * Every test asserts the denial path (§12 rule 8): a missing or invalid domain token
 * still rides the drained global bucket and gets `429`, a valid-token flood still
 * hits its own domain's bucket, and login is still bounded by the `login` bucket.
 */
class GlobalRateLimitRunnerLoginExemptionTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    @TempDir
    lateinit var tmp: Path

    private fun config(
        apiAuth: AuthConfig = AuthConfig.Unsecured,
        globalLimit: Int = 2,
        loginLimit: Int = 5,
        runners: Map<String, RunnerConfig> = mapOf("workers" to RunnerConfig.External(token = WORKERS_TOKEN)),
    ) = AppConfig(
        server =
            Server(
                apiAuth = apiAuth,
                rateLimit = RateLimitConfig(limit = globalLimit, refillPeriod = 60),
                loginRateLimit = RateLimitConfig(limit = loginLimit, refillPeriod = 60),
                corsAllowedOrigins = if (apiAuth is AuthConfig.Password) listOf("http://localhost:3000") else emptyList(),
            ),
        agentsSearchPath = agentsDir.absolutePath,
        workspaceRootPath = workspaceDir.absolutePath,
        databasePath = File(workspaceDir, "homer.db").absolutePath,
        langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
        openTelemetryExport = OpenTelemetryExport.LOG,
        runners = runners,
    )

    /** Spends the whole global bucket on an unauthenticated route and proves it is empty. */
    private suspend fun ApplicationTestBuilder.drainGlobalBucket(limit: Int) {
        repeat(limit) { assertTrue(client.get("/api/v1/heartbeat").status != HttpStatusCode.TooManyRequests) }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.get("/api/v1/heartbeat").status,
            "the global bucket must be exhausted before the exemption is checked",
        )
    }

    private suspend fun ApplicationTestBuilder.runnerPost(
        path: String,
        token: String?,
        body: String? = null,
    ): HttpResponse =
        client.post(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private fun params(): RunnerSessionParams {
        val root = tmp.resolve("runs/7").createDirectories()
        val ws =
            mockk<WorkspaceConfig> {
                every { projectRoot } returns root
                every { maxOutputBytes } returns 1024
            }
        return RunnerSessionParams(7, "job", ws, mapOf(EnvConstants.WORK_DIR to "$root/workdir"), Context.root())
    }

    @Test
    fun `a drained global bucket still lets a valid-token runner register, claim and poll and keeps it alive`() =
        testApplication {
            lateinit var registry: RunnerRegistry
            application {
                configureServer(config())
                registry = get<RunnerRegistry>()
            }

            drainGlobalBucket(limit = 2)

            val registered = runnerPost("/api/v1/runner/register", WORKERS_TOKEN, """{"name":"host-1","concurrency":1}""")
            assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())
            val instanceId = apiJson.decodeFromString(RegisterInstanceResponse.serializer(), registered.bodyAsText()).instanceId
            val domain = registry.externalDomains.single()
            val instance = domain.instance(instanceId)!!

            // Denial path: without a verified token the runner routes still ride the drained bucket.
            assertEquals(HttpStatusCode.TooManyRequests, runnerPost("/api/v1/runner/instances/$instanceId/claim", null).status)
            assertEquals(
                HttpStatusCode.TooManyRequests,
                runnerPost("/api/v1/runner/instances/$instanceId/claim", "not-the-workers-token-0123456789ab").status,
                "an invalid domain token must gain nothing from the exemption",
            )
            // A valid domain token exempts runner-protocol routes only, never an operator route.
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client.get("/api/v1/heartbeat") { header(HttpHeaders.Authorization, "Bearer $WORKERS_TOKEN") }.status,
            )

            // One second from the heartbeat deadline: only a served request can keep it registered.
            instance.lastSeenAt = domain.now() - domain.config.heartbeatTimeoutSec * 1000 + 1000
            coroutineScope {
                val run = async { registry.queue.awaitClaim(params(), RunnerFilter.Alias("workers"), 10.seconds) }
                val claimedAt = domain.now()
                val claim = runnerPost("/api/v1/runner/instances/$instanceId/claim", WORKERS_TOKEN)
                assertEquals(HttpStatusCode.OK, claim.status, claim.bodyAsText())
                val sessionId = apiJson.decodeFromString(ClaimResponse.serializer(), claim.bodyAsText()).sessionId
                assertTrue(instance.lastSeenAt >= claimedAt, "the claim must refresh lastSeenAt")

                // Past the stale deadline: the sweep must keep the instance the claim refreshed.
                delay(1_500)
                assertTrue(domain.sweepExpired().isEmpty(), "a served runner must not expire under a drained global bucket")
                assertEquals(instance, domain.instance(instanceId))

                // The session's next poll is served too (the close op answers it at once).
                run.await().close()
                val polledAt = domain.now()
                val next = runnerPost("/api/v1/runner/sessions/$sessionId/next", WORKERS_TOKEN)
                assertEquals(HttpStatusCode.OK, next.status, next.bodyAsText())
                assertTrue(next.bodyAsText().contains("close"), next.bodyAsText())
                assertTrue(instance.lastSeenAt >= polledAt, "the next poll must refresh lastSeenAt")
            }

            // The exemption drew nothing from the global bucket, and refilled nothing either.
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v1/heartbeat").status)
        }

    @Test
    fun `a valid-token flood is 429 from its own domain bucket and leaves the global bucket and other domains alone`() =
        testApplication {
            val small = RunnerConfig.External(token = WORKERS_TOKEN, maxInstances = 1, maxConcurrencyPerInstance = 1)
            val other = RunnerConfig.External(token = OTHER_TOKEN)
            application { configureServer(config(globalLimit = 2, runners = mapOf("workers" to small, "other" to other))) }

            val limit = runnerDomainRateLimit(small)
            assertEquals(RUNNER_SLOT_REQUESTS_PER_MINUTE, limit, "one instance at concurrency 1 is one slot")
            repeat(limit) {
                assertEquals(HttpStatusCode.NotFound, runnerPost("/api/v1/runner/instances/nope/claim", WORKERS_TOKEN).status)
            }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                runnerPost("/api/v1/runner/instances/nope/claim", WORKERS_TOKEN).status,
                "a valid domain token must still be bounded by its domain's bucket",
            )

            // Keyed by domain: another domain and the unauthenticated bucket are untouched.
            assertEquals(HttpStatusCode.NotFound, runnerPost("/api/v1/runner/instances/nope/claim", OTHER_TOKEN).status)
            drainGlobalBucket(limit = 2)
        }

    private val password =
        AuthConfig.Password(
            users = mapOf("carol" to PasswordUserConfig(passwordHash = PasswordVerifier().hash("admin-pass", cost = 4), role = "admin")),
            sessionSecret = "secret-key-v1-aaaaaaaaaaaaaaaaaa",
            cookieSecure = false,
            bcryptCost = 4,
        )

    private suspend fun ApplicationTestBuilder.login(): HttpResponse =
        client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"carol","password":"admin-pass"}""")
        }

    @Test
    fun `login works with a drained global bucket and the per-peer login limit still applies`() =
        testApplication {
            // The aggregate login bucket is as deep as the global one (5); the per-peer one (2) binds first.
            application { configureServer(config(apiAuth = password, globalLimit = 5, loginLimit = 2, runners = emptyMap())) }

            drainGlobalBucket(limit = 5)

            repeat(2) { login().also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) } }
            assertEquals(HttpStatusCode.TooManyRequests, login().status, "the per-peer login bucket must still bound one peer")
            // Only POST is exempt: another method on the same path keeps the global ceiling.
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v1/auth/login").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/v1/heartbeat").status)
        }

    /**
     * Test-host serves every request from one fixed peer address, so "many distinct
     * peers" is modelled as a per-peer limit that never binds: the only bucket left to
     * refuse is the aggregate one, whose key does not depend on the peer at all.
     */
    @Test
    fun `a login flood that no per-peer limit catches hits the aggregate login ceiling`() =
        testApplication {
            application { configureServer(config(apiAuth = password, globalLimit = 3, loginLimit = 1000, runners = emptyMap())) }

            repeat(3) { login().also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) } }
            assertEquals(
                HttpStatusCode.TooManyRequests,
                login().status,
                "login must have an aggregate ceiling however many peers it comes from",
            )

            // Separate from the global bucket both ways: the login flood spent none of it.
            drainGlobalBucket(limit = 3)
        }

    @Test
    fun `without a password scheme the login path keeps the global bucket`() =
        testApplication {
            application { configureServer(config(globalLimit = 2, runners = emptyMap())) }

            drainGlobalBucket(limit = 2)

            // No login route and no login bucket are mounted: exempting the path would leave it unbounded.
            assertEquals(
                HttpStatusCode.TooManyRequests,
                client
                    .post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }.status,
            )
        }

    @Test
    fun `globalRateLimitKey exempts only verified runner paths, mounted POST login and the probes`() {
        val domains = listOf(RunnerDomainToken("workers", WORKERS_TOKEN))

        fun key(
            path: String,
            token: String? = null,
            method: HttpMethod = HttpMethod.Post,
            loginMounted: Boolean = true,
        ) = globalRateLimitKey(method, path, token, domains, loginMounted)

        assertEquals(RunnerDomainLimitKey("workers"), key("/api/v1/runner/register", WORKERS_TOKEN))
        assertEquals(RunnerDomainLimitKey("workers"), key("/api/v1/runner/sessions/s/next", WORKERS_TOKEN))
        assertEquals(RunnerDomainLimitKey("workers"), key("/api/v1/runner/instances/i/claim", WORKERS_TOKEN))

        // Denial paths: every unverified or out-of-scope request keeps the global key.
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/runner/register"))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/runner/register", ""))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/runner/register", WORKERS_TOKEN.dropLast(1)))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/runner/register", WORKERS_TOKEN + "x"))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/status", WORKERS_TOKEN))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/runnerx/register", WORKERS_TOKEN))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/auth/login", method = HttpMethod.Get))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/auth/login", loginMounted = false))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/auth/login/"))
        assertEquals(GLOBAL_LIMIT_KEY, key("/api/v1/auth/logout"))

        assertEquals(LOGIN_LIMIT_KEY, key("/api/v1/auth/login"))
        assertEquals(PROBE_LIMIT_KEY, key("/api/v1/livez", method = HttpMethod.Get))
        assertEquals(PROBE_LIMIT_KEY, key("/api/v1/readyz", method = HttpMethod.Get))
    }

    @Test
    fun `the domain bucket scales with the configured slots and saturates instead of overflowing`() {
        val defaults = RunnerConfig.External(token = WORKERS_TOKEN)
        assertEquals(32 * 16 * RUNNER_SLOT_REQUESTS_PER_MINUTE, runnerDomainRateLimit(defaults))
        val huge = RunnerConfig.External(token = WORKERS_TOKEN, maxInstances = Int.MAX_VALUE, maxConcurrencyPerInstance = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, runnerDomainRateLimit(huge))
    }

    private companion object {
        /** At the 32-character floor `validateRunnerDomainTokenStrength` enforces at boot. */
        const val WORKERS_TOKEN = "workers-token-0123456789abcdef01"
        const val OTHER_TOKEN = "other-token-0123456789abcdef0123"
    }
}
