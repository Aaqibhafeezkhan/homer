package homer.app

import homer.app.exceptions.CostCapExceededException
import homer.app.executors.Process
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.mountApiV1Routes
import homer.app.infra.AgentInputResolver
import homer.app.infra.InputSchemaValidator
import homer.app.infra.LocalFileOperations
import homer.app.infra.TelemetryService
import homer.app.models.Agent
import homer.app.models.AgentDispatchConfig
import homer.app.models.AgentReloadResult
import homer.app.models.AgentsListResponse
import homer.app.models.AppConfig
import homer.app.models.ArtifactListingResponse
import homer.app.models.Command
import homer.app.models.CostByTagResponse
import homer.app.models.CronConfig
import homer.app.models.ErrorResponse
import homer.app.models.ExecuteResponse
import homer.app.models.ExecutionInfo
import homer.app.models.HealthResponse
import homer.app.models.HeartbeatResponse
import homer.app.models.LangfuseConfig
import homer.app.models.MaintenanceResponse
import homer.app.models.MonthlyCostsResponse
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.PodPhase
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessInfo
import homer.app.models.ProcessInfoResponse
import homer.app.models.ProcessListResponse
import homer.app.models.ProcessStatus
import homer.app.models.ProcessStatusResponse
import homer.app.models.RunnerConfig
import homer.app.models.RunnerState
import homer.app.models.RunnerSummary
import homer.app.models.ServerStatusResponse
import homer.app.models.SweepDeletedPodResponse
import homer.app.models.TotalCostsResponse
import homer.app.routes.ApiDeps
import homer.app.routes.apiV1Routes
import homer.app.routes.handleAgentExecution
import homer.app.routes.handleResume
import homer.app.routes.handleRetry
import homer.app.runners.kubernetes.SweepStatus
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.ArtifactService
import homer.app.services.Authorizer
import homer.app.services.CostMeter
import homer.app.services.CronSchedulerService
import homer.app.services.MaintenanceModeService
import homer.app.services.ProcessManager
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP endpoint tests for Application
 * Tests API contract and response formats
 */
class ApplicationTest {
    private lateinit var mockProcessManager: ProcessManager
    private lateinit var mockAgentsService: AgentsService
    private lateinit var mockTelemetryService: TelemetryService
    private val logger = LoggerFactory.getLogger(ApplicationTest::class.java)

    @BeforeEach
    fun setup() {
        mockProcessManager = mockk(relaxed = true)
        mockAgentsService = mockk(relaxed = true)
        mockTelemetryService = TestFixtures.mockTelemetryService()

        every { mockProcessManager.startTime } returns "1700000000"
        coEvery { mockProcessManager.lastProcessId() } returns 5
        every { mockAgentsService.agents } returns emptyMap()
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    /** A real ProcessManager for the /status/all round-trip tests (no mocking of getAllProcesses). */
    private fun realProcessManager(): ProcessManager = PersistenceTestSupport.processManager(logger = logger)

    /**
     * Mounts the real [apiV1Routes] handler with [pm] as the only wired
     * dependency (everything else is relaxed).
     */
    private fun ApplicationTestBuilder.mountStatusAllRoutes(pm: ProcessManager) {
        mountApiV1Routes { every { processManager } returns pm }
    }

    /**
     * Mounts the real [apiV1Routes] wired to this class's [mockProcessManager] and
     * [mockAgentsService]; [stubDeps] wires any further dependency.
     */
    private fun ApplicationTestBuilder.mountWithServiceMocks(stubDeps: ApiDeps.() -> Unit = {}) =
        mountApiV1Routes {
            every { processManager } returns mockProcessManager
            every { agentsService } returns mockAgentsService
            stubDeps()
        }

    /**
     * Wires the run-starting dependencies a retry or debug handler reaches: the given
     * factory and executor, this class's telemetry mock and a real [AgentInputResolver].
     */
    private fun ApiDeps.stubRunDeps(
        factory: ProcessFactory,
        executor: ProcessExecutor,
    ) {
        every { processFactory } returns factory
        every { processExecutor } returns executor
        every { telemetryService } returns mockTelemetryService
        every { agentInputResolver } returns AgentInputResolver()
    }

    // ========== GET /schemas/agent Tests ==========

    @Test
    fun `GET schemas agent serves bundled homer-agent schema`() =
        testApplication {
            mountApiV1Routes()

            val response = client.get("/api/v1/schemas/agent")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"title\""), "Schema body should contain a title field")
            assertTrue(body.contains("Homer Agent"), "Schema body should declare itself as the Homer Agent schema")
        }

    // ========== GET /heartbeat Tests ==========

    @Test
    fun `GET heartbeat should return ok status`() =
        testApplication {
            mountApiV1Routes()

            val response = client.get("/api/v1/heartbeat")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", apiJson.decodeFromString<HeartbeatResponse>(response.bodyAsText()).status)
        }

    // ========== GET /health Tests ==========

    /** A real [AppConfig] declaring one kubernetes runner, aliased `k8s-dev`. */
    private fun configWithK8sRunner() =
        AppConfig(
            agentsSearchPath = "/agents",
            workspaceRootPath = "/workspaces",
            databasePath = "/homer.db",
            runners = mapOf("k8s-dev" to RunnerConfig.Kubernetes(image = "homer:latest")),
        )

    @Test
    fun `GET health returns runners and null sweep when no sweep has run`() =
        testApplication {
            mountApiV1Routes {
                every { config } returns configWithK8sRunner()
                every { sweepStatus } returns SweepStatus()
            }

            val response = client.get("/api/v1/health")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<HealthResponse>(response.bodyAsText())
            assertEquals("ok", body.status)
            assertEquals(
                listOf(RunnerSummary(alias = "local", type = "local"), RunnerSummary(alias = "k8s-dev", type = "kubernetes")),
                body.runners,
            )
            assertNull(body.sweep)
        }

    @Test
    fun `GET health exposes sweep snapshot when one has run`() =
        testApplication {
            val sweep = SweepStatus()
            sweep.set(
                SweepStatus.Snapshot(
                    ranAt = 1_700_000_000L,
                    scanned = 3,
                    deleted =
                        listOf(
                            SweepStatus.DeletedPod(
                                namespace = "homer",
                                name = "homer-99-xyz",
                                processId = 99,
                                runnerAlias = "k8s",
                            ),
                        ),
                    errors = emptyList(),
                ),
            )
            mountApiV1Routes {
                every { config } returns configWithK8sRunner()
                every { sweepStatus } returns sweep
            }

            val response = client.get("/api/v1/health")

            assertEquals(HttpStatusCode.OK, response.status)
            val snapshot = assertNotNull(apiJson.decodeFromString<HealthResponse>(response.bodyAsText()).sweep)
            assertEquals(1_700_000_000L, snapshot.ranAt)
            assertEquals(3, snapshot.scanned)
            assertEquals(
                listOf(SweepDeletedPodResponse(namespace = "homer", name = "homer-99-xyz", processId = 99, runnerAlias = "k8s")),
                snapshot.deleted,
            )
        }

    // ========== GET/POST /maintenance Tests ==========

    /** The service's read model for a mode turned on at [since] with [queued] parked starts. */
    private fun maintenanceState(
        enabled: Boolean = true,
        since: Long? = 1_700_000_000L,
        queued: Int = 0,
    ) = MaintenanceResponse(enabled = enabled, since = since, queued = queued)

    @Test
    fun `GET maintenance returns the mode state to an admin, uncached`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>()
            every { maintenance.snapshot() } returns maintenanceState(queued = 3)
            mountApiV1Routes { every { maintenanceModeService } returns maintenance }

            val response = client.get("/api/v1/maintenance")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(maintenanceState(queued = 3), apiJson.decodeFromString<MaintenanceResponse>(response.bodyAsText()))
            // Live operator state: a cached copy could serve a stale "off".
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `POST maintenance turns the mode on for an admin`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            every { maintenance.snapshot() } returns maintenanceState()
            mountApiV1Routes { every { maintenanceModeService } returns maintenance }

            val response =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled": true}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 1) { maintenance.setEnabled(true) }
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `POST maintenance turns the mode off for an admin`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            every { maintenance.snapshot() } returns maintenanceState(enabled = false, since = null)
            mountApiV1Routes { every { maintenanceModeService } returns maintenance }

            val response =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled": false}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 1) { maintenance.setEnabled(false) }
        }

    @Test
    fun `GET and POST maintenance deny a non-admin with the same 404 as an unknown resource`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            mountApiV1Routes {
                every { maintenanceModeService } returns maintenance
                // Enabled auth with no grants: a non-admin principal (ADR-0047 rule 3).
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
            }

            val unknown = client.get("/api/v1/no-such-resource")
            val getDenied = client.get("/api/v1/maintenance")
            val postDenied =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled": true}""")
                }

            assertEquals(HttpStatusCode.NotFound, getDenied.status)
            assertEquals(unknown.bodyAsText(), getDenied.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, postDenied.status)
            assertEquals(unknown.bodyAsText(), postDenied.bodyAsText())
            // The denial is total: the mode never moves.
            coVerify(exactly = 0) { maintenance.setEnabled(any()) }
        }

    @Test
    fun `POST maintenance answers 404, not 400, for a malformed body from a non-admin`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            mountApiV1Routes {
                every { maintenanceModeService } returns maintenance
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
            }

            val response =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"nonsense": 1}""")
                }

            // The body is parsed after the gate, so a denied caller cannot even probe the request shape.
            assertEquals(HttpStatusCode.NotFound, response.status)
            coVerify(exactly = 0) { maintenance.setEnabled(any()) }
        }

    @Test
    fun `POST maintenance rejects a non-boolean or unknown-key body with 400`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            mountApiV1Routes { every { maintenanceModeService } returns maintenance }

            for (body in listOf("""{"enabled": "yes"}""", """{"enabled": true, "surprise": 1}""", "not json")) {
                val response =
                    client.post("/api/v1/maintenance") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
            }
            coVerify(exactly = 0) { maintenance.setEnabled(any()) }
        }

    @Test
    fun `GET health exposes the maintenance flag`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>()
            every { maintenance.isEnabled() } returns true
            mountApiV1Routes {
                every { config } returns configWithK8sRunner()
                every { sweepStatus } returns SweepStatus()
                every { maintenanceModeService } returns maintenance
            }

            val response = client.get("/api/v1/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(apiJson.decodeFromString<HealthResponse>(response.bodyAsText()).maintenance)
        }

    @Test
    fun `turning the mode on leaves a run already WORKING untouched`() =
        testApplication {
            val pm = realProcessManager()
            val id = pm.obtainProcessId("long-running-agent")
            assertTrue(pm.compareAndSetStatus(id, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING))
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            every { maintenance.snapshot() } returns maintenanceState()
            mountApiV1Routes {
                every { processManager } returns pm
                every { maintenanceModeService } returns maintenance
            }

            val response =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled": true}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // Maintenance is an admission gate, not a kill switch: the run in flight keeps running.
            assertEquals(ProcessStatus.WORKING, pm.getProcessInfo(id)?.status)
        }

    // ========== GET /costs Tests ==========

    /** Mounts the real routes with a [CostMeter] whose snapshot is [snap] and a real config. */
    private fun ApplicationTestBuilder.mountCostsRoutes(snap: CostMeter.Snapshot) {
        val meter = mockk<CostMeter> { coEvery { snapshot() } returns snap }
        mountApiV1Routes {
            every { costMeter } returns meter
            every { config } returns configWithTrackedTagKeys(emptyList())
        }
    }

    @Test
    fun `GET costs returns CostMeter snapshot serialized as JSON`() =
        testApplication {
            mountCostsRoutes(
                CostMeter.Snapshot(
                    globalUsdLast60s = 0.42,
                    globalCapUsdPerMin = 5.0,
                    perAgentUsdLast24h = mapOf("alpha" to 1.25, "beta" to 0.10),
                    perAgentCapUsdPerDay = mapOf("alpha" to 10.0),
                    snapshotAtSec = 1_700_000_000L,
                ),
            )

            val response = client.get("/api/v1/costs")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"globalUsdLast60s\": 0.42"))
            assertTrue(body.contains("\"globalCapUsdPerMin\": 5.0"))
            assertTrue(body.contains("\"alpha\": 1.25"))
            assertTrue(body.contains("\"beta\": 0.1"))
            assertTrue(body.contains("\"snapshotAtSec\": 1700000000"))
        }

    @Test
    fun `GET costs omits global cap when not configured`() =
        testApplication {
            mountCostsRoutes(
                CostMeter.Snapshot(
                    globalUsdLast60s = 0.0,
                    globalCapUsdPerMin = null,
                    perAgentUsdLast24h = emptyMap(),
                    perAgentCapUsdPerDay = emptyMap(),
                    snapshotAtSec = 1_700_000_001L,
                ),
            )

            val response = client.get("/api/v1/costs")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"globalUsdLast60s\": 0.0"))
            assertTrue(body.contains("\"globalCapUsdPerMin\": null"))
        }

    // ========== GET /costs/monthly and /costs/totals Tests ==========

    private fun seededCostMeter(dir: java.nio.file.Path): CostMeter {
        val db =
            homer.app.services.persistence
                .DatabaseService(dir.resolve("costs.db").toString(), org.slf4j.LoggerFactory.getLogger("t"))
        db.migrate()
        val repo =
            homer.app.services.persistence
                .CostRepository(db)
        val meter = CostMeter(repository = repo)
        kotlinx.coroutines.runBlocking {
            // One June and two July 2026 entries (UTC).
            repo.insert(
                "alpha",
                java.time.LocalDateTime
                    .of(2026, 6, 15, 12, 0)
                    .toEpochSecond(java.time.ZoneOffset.UTC),
                1.0,
            )
            repo.insert(
                "alpha",
                java.time.LocalDateTime
                    .of(2026, 7, 2, 12, 0)
                    .toEpochSecond(java.time.ZoneOffset.UTC),
                2.0,
            )
            repo.insert(
                "beta",
                java.time.LocalDateTime
                    .of(2026, 7, 3, 12, 0)
                    .toEpochSecond(java.time.ZoneOffset.UTC),
                0.5,
            )
        }
        return meter
    }

    @Test
    fun `GET costs monthly returns per-agent sums for the requested UTC month`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        val meter = seededCostMeter(dir)
        mountApiV1Routes { every { costMeter } returns meter }

        val response = client.get("/api/v1/costs/monthly?month=2026-07")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<MonthlyCostsResponse>(response.bodyAsText())
        assertEquals("2026-07", body.month)
        assertEquals(mapOf("alpha" to 2.0, "beta" to 0.5), body.perAgentUsd)
        assertEquals(2.5, body.totalUsd, 1e-9)
        assertEquals(listOf("2026-07", "2026-06"), body.availableMonths)
    }

    @Test
    fun `GET costs monthly with a malformed month returns 400 via StatusPages`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        val meter = seededCostMeter(dir)
        mountApiV1Routes { every { costMeter } returns meter }

        val response = client.get("/api/v1/costs/monthly?month=garbage")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET costs totals returns all-time per-agent sums`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        val meter = seededCostMeter(dir)
        mountApiV1Routes { every { costMeter } returns meter }

        val response = client.get("/api/v1/costs/totals")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<TotalCostsResponse>(response.bodyAsText())
        assertEquals(mapOf("alpha" to 3.0, "beta" to 0.5), body.perAgentUsd)
        assertEquals(3.5, body.totalUsd, 1e-9)
    }

    // ========== GET /costs/by-tag/{key} Tests ==========

    private fun configWithTrackedTagKeys(keys: List<String>) =
        AppConfig(
            agentsSearchPath = "/agents",
            workspaceRootPath = "/workspaces",
            databasePath = "/homer.db",
            costTrackedTagKeys = keys,
        )

    @Test
    fun `GET costs by-tag returns the per-value breakdown for a configured key`() =
        testApplication {
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery { pm.costByTag(eq("ticket_id"), any()) } returns
                ProcessManager.CostByTagResult(mapOf("T-123" to 1.5, "T-456" to 0.25), truncated = false)
            mountApiV1Routes {
                every { processManager } returns pm
                every { config } returns configWithTrackedTagKeys(listOf("ticket_id"))
            }

            val response = client.get("/api/v1/costs/by-tag/ticket_id")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<CostByTagResponse>(response.bodyAsText())
            assertEquals("ticket_id", body.key)
            assertEquals(mapOf("T-123" to 1.5, "T-456" to 0.25), body.valuesUsd)
            assertEquals(1.75, body.totalUsd, 1e-9)
            assertFalse(body.truncated)
        }

    @Test
    fun `GET costs by-tag reports truncated when the repository hit its row bound`() =
        testApplication {
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery { pm.costByTag(eq("ticket_id"), any()) } returns
                ProcessManager.CostByTagResult(mapOf("T-123" to 1.5), truncated = true)
            mountApiV1Routes {
                every { processManager } returns pm
                every { config } returns configWithTrackedTagKeys(listOf("ticket_id"))
            }

            val response = client.get("/api/v1/costs/by-tag/ticket_id")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<CostByTagResponse>(response.bodyAsText())
            assertTrue(body.truncated)
        }

    @Test
    fun `GET costs by-tag with an unconfigured key returns 400`() =
        testApplication {
            mountApiV1Routes {
                every { config } returns configWithTrackedTagKeys(emptyList())
            }

            val response = client.get("/api/v1/costs/by-tag/ticket_id")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET costs by-tag denies a non-admin caller with 403`() =
        testApplication {
            mountApiV1Routes {
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
                every { config } returns configWithTrackedTagKeys(listOf("ticket_id"))
            }

            val response = client.get("/api/v1/costs/by-tag/ticket_id")

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ========== GET /status Tests ==========

    @Test
    fun `GET status should return server status`() =
        testApplication {
            mountWithServiceMocks()

            val response = client.get("/api/v1/status")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<ServerStatusResponse>(response.bodyAsText())
            assertEquals("1700000000", body.startTime)
            assertTrue(body.currentTime.isNotBlank(), "Response should carry the current time")
            assertEquals(5, body.lastProcessId)
        }

    // ========== GET /agents Tests ==========

    @Test
    fun `GET agents should return empty list when no agents`() =
        testApplication {
            mountApiV1Routes { every { agentsService.agents } returns emptyMap() }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(apiJson.decodeFromString<AgentsListResponse>(response.bodyAsText()).agents.isEmpty())
        }

    @Test
    fun `GET agents exposes new-style inputs (regression - inputs-migrated agents showed no params)`() =
        testApplication {
            // Exercises the REAL /agents route (not a duplicated handler) so the DTO
            // mapping can't silently drop the new `inputs:` field again.
            val agent =
                AgentFixtures.simpleAgent(name = "with-inputs").copy(
                    inputs =
                        listOf(
                            Param(
                                paramName = "ticket_id",
                                envName = "TICKET_ID",
                                type = ParamType.STRING,
                                required = true,
                            ),
                        ),
                )
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("with-inputs" to agent)
            }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"inputs\""), "response must include the inputs field: $body")
            assertTrue(body.contains("ticket_id"), "declared input must be exposed: $body")
        }

    @Test
    fun `GET agents should include description when agent has one`() =
        testApplication {
            val agent =
                Agent(
                    name = "test_agent",
                    description = "My test description",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)),
                )
            mountApiV1Routes { every { agentsService.agents } returns mapOf("test_agent" to agent) }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            val listed = apiJson.decodeFromString<AgentsListResponse>(response.bodyAsText()).agents.single()
            assertEquals("My test description", listed.description)
        }

    private fun secretSummaryParams() =
        listOf(
            Param(
                paramName = "token",
                envName = "TOKEN",
                type = ParamType.STR,
                required = false,
                secret = true,
                values = listOf("abc", "def"),
                default = "super-secret-default",
            ),
            Param(
                paramName = "environment",
                envName = "ENV_NAME",
                type = ParamType.STR,
                required = false,
                values = listOf("dev", "prod"),
                default = "dev",
            ),
        )

    private suspend fun assertSecretFieldsDropped(response: HttpResponse) {
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("secret-defaults"), "agent must be listed: $body")
        assertTrue(body.contains("environment"), "the declared param must be listed: $body")
        assertFalse(body.contains("super-secret-default"), "secret default must not leave the API: $body")
        assertFalse(body.contains("abc"), "secret input's values must not leave the API: $body")
        assertTrue(body.contains("dev"), "non-secret default must still be served: $body")
    }

    @Test
    fun `GET agents drops a secret input's default and values from the served summary`() =
        testApplication {
            // Real route via AgentInfo.from; any grant-holder reads it. See ADR-0071.
            val agent = AgentFixtures.simpleAgent(name = "secret-defaults").copy(inputs = secretSummaryParams())
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("secret-defaults" to agent)
            }

            assertSecretFieldsDropped(client.get("/api/v1/agents"))
        }

    @Test
    fun `GET agents drops a secret input's default and values on the legacy queryParams alias`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "secret-defaults").copy(queryParams = secretSummaryParams())
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("secret-defaults" to agent)
            }

            assertSecretFieldsDropped(client.get("/api/v1/agents"))
        }

    @Test
    fun `GET agents reports no cron and reload carries the disable error for an unsatisfiable cron agent`() =
        testApplication {
            // Black-box check of the load-time rule: an agent whose required input a
            // cron fire can never satisfy loads with its schedule cleared, so the
            // agents list must serve `cron: null` for it, and the reload result must
            // carry the disable reason (CLAUDE.md §6). `agents` holds the agent as
            // load produced it — schedule already stripped, per AgentsServiceTest.
            val agent =
                AgentFixtures.simpleAgent(name = "cron-unsatisfiable").copy(
                    inputs =
                        listOf(
                            Param(
                                paramName = "id",
                                envName = "ID",
                                type = ParamType.STRING,
                                required = true,
                            ),
                        ),
                )
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("cron-unsatisfiable" to agent)
                every { cronSchedulerService.getScheduleStatus(any()) } returns null
                every { agentsService.reload() } returns
                    AgentReloadResult(
                        added = emptyList(),
                        updated = emptyList(),
                        removed = emptyList(),
                        errors =
                            listOf(
                                "Agent 'cron-unsatisfiable' in agent: " +
                                    "cron schedule disabled — required input 'id' has no default; scheduled runs pass no inputs",
                            ),
                    )
            }

            val listBody = client.get("/api/v1/agents").bodyAsText()
            val listed = Json.decodeFromString<AgentsListResponse>(listBody).agents.single()
            assertNull(listed.cron, "a disabled schedule must be served as `cron: null`: $listBody")

            val reloadResponse = client.post("/api/v1/agents/reload")
            assertEquals(HttpStatusCode.OK, reloadResponse.status)
            val reloadBody = reloadResponse.bodyAsText()
            assertTrue(
                reloadBody.contains("cron-unsatisfiable") && reloadBody.contains("cron schedule disabled"),
                "reload response must carry the disable reason: $reloadBody",
            )
        }

    // ========== GET /status/{processId} Tests ==========

    @Test
    fun `GET status processId should return full process info`() =
        testApplication {
            val mockProcessInfo =
                ProcessInfo(
                    id = 1,
                    status = ProcessStatus.WORKING,
                    agentName = "test-agent",
                    job = null,
                    history =
                        listOf(
                            1700000000L to ProcessStatus.CREATED,
                            1700000100L to ProcessStatus.WORKING,
                        ),
                    executions =
                        listOf(
                            ExecutionInfo(
                                label = "cmd",
                                start = 1700000050L,
                                end = 1700000090L,
                                resultCode = 0,
                                stdOut =
                                    java.nio.file.Paths
                                        .get("/tmp/out.txt"),
                                stdErr =
                                    java.nio.file.Paths
                                        .get("/tmp/err.txt"),
                                errMsg = null,
                            ),
                        ),
                )
            coEvery { mockProcessManager.getProcessInfo(1) } returns mockProcessInfo
            mountWithServiceMocks()

            val response = client.get("/api/v1/status/1")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"id\": 1"), "Response should contain process ID")
            assertTrue(body.contains("\"status\": \"WORKING\""), "Response should contain process status")
            assertTrue(body.contains("\"agentName\": \"test-agent\""), "Response should contain agent name")
            assertTrue(body.contains("\"history\""), "Response should contain history")
            assertTrue(body.contains("\"executions\""), "Response should contain executions")
            assertTrue(body.contains("CREATED"), "Response should contain CREATED in history")
            assertTrue(body.contains("1700000050"), "Response should contain execution start time")
            assertTrue(body.contains("\"label\": \"cmd\""), "Response should contain execution label")
        }

    @Test
    fun `GET status processId should return NotFound for non-existent process`() =
        testApplication {
            coEvery { mockProcessManager.getProcessInfo(999) } returns null
            mountWithServiceMocks()

            val response = client.get("/api/v1/status/999")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Process not found", apiJson.decodeFromString<ErrorResponse>(response.bodyAsText()).msg)
        }

    @Test
    fun `GET status processId should return BadRequest for invalid ID`() =
        testApplication {
            mountWithServiceMocks()

            val response = client.get("/api/v1/status/invalid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid process ID", apiJson.decodeFromString<ErrorResponse>(response.bodyAsText()).msg)
            coVerify(exactly = 0) { mockProcessManager.getProcessInfo(any()) }
        }

    // ========== GET /status/{processId} secret redaction Tests (Task 9) ==========

    @Test
    fun `status response redacts secret input values`() =
        testApplication {
            val pm = realProcessManager()
            val id =
                pm.obtainProcessId(
                    "secret-agent",
                    inputParams = mapOf("TOKEN" to "hunter2", "USER_N" to "alice"),
                )
            val secretAgent =
                AgentFixtures.simpleAgent(name = "secret-agent").copy(
                    inputs =
                        listOf(
                            Param(paramName = "token", envName = "TOKEN", type = ParamType.ANY, secret = true),
                            Param(paramName = "user", envName = "USER_N", type = ParamType.ANY),
                        ),
                )

            mountApiV1Routes {
                every { processManager } returns pm
                every { agentsService.agents } returns mapOf("secret-agent" to secretAgent)
            }

            val response = client.get("/api/v1/status/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val info = apiJson.decodeFromString<ProcessInfoResponse>(response.bodyAsText())
            assertEquals("***", info.inputParams["TOKEN"])
            assertEquals("alice", info.inputParams["USER_N"])
        }

    @Test
    fun `status response redacts a stored value whose envName no longer matches any current input`() =
        testApplication {
            val pm = realProcessManager()
            val id =
                pm.obtainProcessId(
                    "renamed-secret-agent",
                    inputParams = mapOf("TOKEN" to "hunter2", "USER_N" to "alice"),
                )
            // The secret input was renamed from envName TOKEN to API_TOKEN since this run
            // stored its value; the old TOKEN key matches no current input.
            val renamedAgent =
                AgentFixtures.simpleAgent(name = "renamed-secret-agent").copy(
                    inputs =
                        listOf(
                            Param(paramName = "token", envName = "API_TOKEN", type = ParamType.ANY, secret = true),
                            Param(paramName = "user", envName = "USER_N", type = ParamType.ANY),
                        ),
                )

            mountApiV1Routes {
                every { processManager } returns pm
                every { agentsService.agents } returns mapOf("renamed-secret-agent" to renamedAgent)
            }

            val response = client.get("/api/v1/status/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val info = apiJson.decodeFromString<ProcessInfoResponse>(response.bodyAsText())
            assertEquals("***", info.inputParams["TOKEN"])
            assertEquals("alice", info.inputParams["USER_N"])
        }

    @Test
    fun `status response redacts all input values when agent definition is gone`() =
        testApplication {
            val pm = realProcessManager()
            val id =
                pm.obtainProcessId(
                    "removed-agent",
                    inputParams = mapOf("TOKEN" to "hunter2", "USER_N" to "alice"),
                )

            mountApiV1Routes {
                every { processManager } returns pm
                every { agentsService.agents } returns emptyMap()
            }

            val response = client.get("/api/v1/status/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val info = apiJson.decodeFromString<ProcessInfoResponse>(response.bodyAsText())
            assertTrue(info.inputParams.values.all { it == "***" })
        }

    // ========== GET /status/{processId} retainedTruncated Tests (Task 6) ==========

    @Test
    fun `status response surfaces retainedTruncated after a retention prune`() =
        testApplication {
            val pm = realProcessManager()
            val id = pm.obtainProcessId("retain-agent")
            pm.setProcessStatus(id, ProcessStatus.FINISHED)
            pm.markWorkspacePurged(id, retainedTruncated = true)

            mountApiV1Routes {
                every { processManager } returns pm
            }

            val response = client.get("/api/v1/status/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"retainedTruncated\""), "Response should contain retainedTruncated")
            val info = apiJson.decodeFromString<ProcessInfoResponse>(body)
            assertEquals(true, info.retainedTruncated)
        }

    // ========== GET /status/all Tests ==========
    //
    // These mount the *real* apiV1Routes handler against a *real* ProcessManager
    // (mockk<ApiDeps> only supplies the processManager dependency) so the whole
    // parse → getAllProcesses → serialize path is exercised. Query-parameter
    // validation (invalid types/order/limit/offset and early-return ordering)
    // is pinned by StatusAllValidationTest, not here.

    @Test
    fun `GET status all should return all processes without filters`() =
        testApplication {
            val pm = realProcessManager()
            val id1 = pm.obtainProcessId("test-agent")
            val id2 = pm.obtainProcessId("test-agent")
            pm.setProcessStatus(id1, ProcessStatus.WORKING)
            pm.setProcessStatus(id2, ProcessStatus.FINISHED)

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(2, result.total)
            assertEquals(0, result.offset)
            assertEquals(100, result.limit)
            assertEquals(setOf(id1, id2), result.processes.map { it.id }.toSet())
        }

    @Test
    fun `GET status all should return the root id on each row and filter by rootProcessId`() =
        testApplication {
            val pm = realProcessManager()
            val root = pm.obtainProcessId("test-agent")
            val segment = pm.obtainProcessId("test-agent", parentProcessId = root, rootProcessId = root)
            val grandchild = pm.obtainProcessId("test-agent", parentProcessId = segment, rootProcessId = root)
            val unrelated = pm.obtainProcessId("test-agent")

            mountStatusAllRoutes(pm)

            val all = client.get("/api/v1/status/all?order=asc")
            assertEquals(HttpStatusCode.OK, all.status)
            val allResult = apiJson.decodeFromString<ProcessListResponse>(all.bodyAsText())
            assertEquals(null, allResult.processes.first { it.id == root }.rootProcessId)
            assertEquals(root, allResult.processes.first { it.id == segment }.rootProcessId)

            // One paginated read covers the whole flow: both hops below the root, in id order.
            val flow = client.get("/api/v1/status/all?rootProcessId=$root&order=asc")
            assertEquals(HttpStatusCode.OK, flow.status)
            val flowResult = apiJson.decodeFromString<ProcessListResponse>(flow.bodyAsText())
            assertEquals(listOf(segment, grandchild), flowResult.processes.map { it.id })
            assertEquals(2, flowResult.total)
            assertTrue(unrelated !in flowResult.processes.map { it.id })
        }

    @Test
    fun `GET status all should filter to flow roots with roots=true`() =
        testApplication {
            val pm = realProcessManager()
            val rootA = pm.obtainProcessId("test-agent")
            val rootB = pm.obtainProcessId("test-agent")
            pm.obtainProcessId("test-agent", parentProcessId = rootA, rootProcessId = rootA)
            pm.obtainProcessId("test-agent", parentProcessId = rootB, rootProcessId = rootB)

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?roots=true&order=asc")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(listOf(rootA, rootB), result.processes.map { it.id })
            assertEquals(2, result.total)
        }

    @Test
    fun `GET status all should report one root for a continuation chain three segments deep`() =
        testApplication {
            // A real ProcessManager plus a real ContinuationService sharing one database: every
            // segment row is INSERTed through the production persistence path (obtainProcessId),
            // carrying the root the fire path derived, so the read below sees stored values.
            val db = PersistenceTestSupport.tempDatabase()
            val pm = PersistenceTestSupport.processManager(db = db)
            val questions = PersistenceTestSupport.questionsService(db = db)
            val agentsService = mockk<AgentsService>()
            val target = AgentFixtures.simpleAgent(name = "continuer").copy(dispatch = AgentDispatchConfig(enabled = true))
            every { agentsService.agents } returns mapOf("continuer" to target)

            // The flow's first run: no parent, and therefore no root of its own.
            val root = pm.obtainProcessId("continuer")

            val runStarter = mockk<AgentRunStarter>()
            var lastFired: Int? = null
            // Each fire INSERTs the segment's row with the parent and root the fire path
            // derived, exactly as ProcessExecutor would.
            coEvery {
                runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                pm
                    .obtainProcessId("continuer", parentProcessId = arg<Int?>(7), rootProcessId = arg<Int?>(8))
                    .also { lastFired = it }
            }
            val continuations =
                PersistenceTestSupport.continuationService(
                    db = db,
                    questionsService = questions,
                    agentsService = agentsService,
                    runStarter = runStarter,
                    inputResolver = mockk(relaxed = true),
                    processManager = pm,
                )

            // Fire three continuations in a chain: each segment registers the next one from
            // itself, so the chain is root -> segment 1 -> segment 2 -> segment 3.
            var originId = root
            repeat(3) {
                kotlinx.coroutines.runBlocking {
                    questions.registerProcess(originId, "continuer")
                    // The watched run is already terminal, so `create` race-closes and fires
                    // the continuation immediately (ADR-0023).
                    val watched = pm.obtainProcessId("continuer")
                    pm.setProcessStatus(watched, ProcessStatus.FINISHED)
                    continuations.create(originId, watched, "continuer", emptyMap(), "HMR_LG_RESUME", null, null)
                }
                originId = assertNotNull(lastFired, "the chain's segment $it did not fire")
            }
            // The last segment's stored row carries the flow root, not its own id.
            assertEquals(root, pm.getProcessInfo(originId)?.rootProcessId)

            mountStatusAllRoutes(pm)

            val flow = client.get("/api/v1/status/all?rootProcessId=$root&order=asc")
            assertEquals(HttpStatusCode.OK, flow.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(flow.bodyAsText())
            // Three segments below the root, every one of them in the same flow.
            assertEquals(3, result.total)
            assertTrue(
                result.processes.all { it.rootProcessId == root },
                "every descendant must report the flow root; got ${result.processes.map { it.id to it.rootProcessId }}",
            )
            assertEquals(null, pm.getProcessInfo(root)?.rootProcessId, "the root run stays rootless")
        }

    @Test
    fun `GET status all should filter by single status type`() =
        testApplication {
            val pm = realProcessManager()
            val working = pm.obtainProcessId("test-agent")
            val finished = pm.obtainProcessId("test-agent")
            pm.setProcessStatus(working, ProcessStatus.WORKING)
            pm.setProcessStatus(finished, ProcessStatus.FINISHED)

            mountStatusAllRoutes(pm)

            // Lowercase on purpose: also pins the parser's case-insensitive types.
            val response = client.get("/api/v1/status/all?types=working")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(1, result.total)
            assertEquals(listOf(working), result.processes.map { it.id })
            assertEquals("WORKING", result.processes.single().status)
        }

    @Test
    fun `GET status all should filter by multiple status types`() =
        testApplication {
            val pm = realProcessManager()
            val working = pm.obtainProcessId("test-agent")
            val finished = pm.obtainProcessId("test-agent")
            val failed = pm.obtainProcessId("test-agent")
            pm.setProcessStatus(working, ProcessStatus.WORKING)
            pm.setProcessStatus(finished, ProcessStatus.FINISHED)
            pm.setProcessStatus(failed, ProcessStatus.FAILED)

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?types=WORKING,FINISHED")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(2, result.total)
            assertEquals(setOf(working, finished), result.processes.map { it.id }.toSet())
            assertEquals(setOf("WORKING", "FINISHED"), result.processes.map { it.status }.toSet())
        }

    @Test
    fun `GET status all should order ascending when specified`() =
        testApplication {
            val pm = realProcessManager()
            val id1 = pm.obtainProcessId("test-agent")
            val id2 = pm.obtainProcessId("test-agent")
            val id3 = pm.obtainProcessId("test-agent")

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?order=asc")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            // Ascending order: presence and position pinned together.
            assertEquals(listOf(id1, id2, id3), result.processes.map { it.id })
        }

    @Test
    fun `GET status all should return empty list when no processes exist`() =
        testApplication {
            val pm = realProcessManager()

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(0, result.total)
            assertTrue(result.processes.isEmpty())
        }

    @Test
    fun `GET status all should apply pagination with offset and limit`() =
        testApplication {
            val pm = realProcessManager()
            val ids = List(10) { pm.obtainProcessId("test-agent") }

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?offset=2&limit=2")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(10, result.total)
            assertEquals(2, result.offset)
            assertEquals(2, result.limit)
            // Default order is descending by id; offset 2 / limit 2 selects the 3rd and 4th newest.
            assertEquals(ids.sortedDescending().subList(2, 4), result.processes.map { it.id })
        }

    @Test
    fun `GET status all should filter by agent name`() =
        testApplication {
            val pm = realProcessManager()
            val alphaProc = pm.obtainProcessId("agent-alpha")
            val betaProc = pm.obtainProcessId("agent-beta")

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?agentName=agent-alpha")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(1, result.total)
            assertEquals(listOf(alphaProc), result.processes.map { it.id })
            assertEquals("agent-alpha", result.processes.single().agentName)
        }

    @Test
    fun `GET status all should combine filtering ordering and pagination`() =
        testApplication {
            val pm = realProcessManager()
            // 6 processes alternating WORKING/FINISHED. Filter WORKING, order
            // ascending, then offset=1 limit=1 selects the 2nd-oldest WORKING id.
            val workingIds = mutableListOf<Int>()
            repeat(6) { idx ->
                val pid = pm.obtainProcessId("test-agent")
                if (idx % 2 == 0) {
                    pm.setProcessStatus(pid, ProcessStatus.WORKING)
                    workingIds.add(pid)
                } else {
                    pm.setProcessStatus(pid, ProcessStatus.FINISHED)
                }
            }

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?types=WORKING&order=asc&offset=1&limit=1")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(3, result.total)
            assertEquals(1, result.offset)
            assertEquals(1, result.limit)
            assertEquals(listOf(workingIds.sorted()[1]), result.processes.map { it.id })
        }

    // ========== DELETE /kill/{processId} Tests ==========

    @Test
    fun `DELETE kill processId should kill process and return status`() =
        testApplication {
            coEvery { mockProcessManager.getProcessInfo(1) } returns
                ProcessInfo(id = 1, status = ProcessStatus.WORKING, agentName = "test-agent")
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.KILLED
            mountWithServiceMocks()

            val response = client.delete("/api/v1/kill/1")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("KILLED", apiJson.decodeFromString<ProcessStatusResponse>(response.bodyAsText()).status)
            coVerify { mockProcessManager.killProcess(1) }
        }

    @Test
    fun `DELETE kill processId should return BadRequest for invalid ID`() =
        testApplication {
            mountWithServiceMocks()

            val response = client.delete("/api/v1/kill/invalid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
        }

    // ========== Response Format Tests ==========

    @Test
    fun `HeartbeatResponse should have correct format`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                routing {
                    get("/test") {
                        call.respond(HeartbeatResponse())
                    }
                }
            }

            val response = client.get("/test")
            val body = response.bodyAsText()

            assertTrue(body.contains("\"status\""), "Response should contain status field")
            assertTrue(body.contains("\"ok\""), "Status should be ok")
        }

    @Test
    fun `ErrorResponse should have correct format`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                routing {
                    get("/test") {
                        call.respond(ErrorResponse(msg = "Test error message"))
                    }
                }
            }

            val response = client.get("/test")
            val body = response.bodyAsText()

            assertTrue(body.contains("\"err\""), "Response should contain err field")
            assertTrue(body.contains("\"msg\""), "Response should contain msg field")
            assertTrue(body.contains("Test error message"), "Response should contain error message")
        }

    @Test
    fun `ExecuteResponse should have correct format`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                routing {
                    get("/test") {
                        call.respond(ExecuteResponse(processId = 123))
                    }
                }
            }

            val response = client.get("/test")
            val body = response.bodyAsText()

            assertTrue(body.contains("\"processId\""), "Response should contain processId field")
            assertTrue(body.contains("123"), "Response should contain process ID value")
        }

    // ========== GET /artifacts Tests ==========

    /**
     * Mounts the real routes with a real [ArtifactService] over [LocalFileOperations];
     * every process lookup answers [processInfo].
     */
    private fun ApplicationTestBuilder.mountArtifactRoutes(processInfo: ProcessInfo? = null) {
        coEvery { mockProcessManager.getProcessInfo(any()) } returns processInfo
        val files = LocalFileOperations()
        mountWithServiceMocks {
            every { artifactService } returns ArtifactService(mockProcessManager, files, logger)
            every { fileOperations } returns files
        }
    }

    private fun finishedProcessWithArtifacts(
        id: Int,
        artifactsDir: java.nio.file.Path,
    ) = ProcessInfo(
        id = id,
        status = ProcessStatus.FINISHED,
        agentName = "test-agent",
        artifactsDir = artifactsDir,
    )

    private suspend fun HttpResponse.errorMsg(): String = apiJson.decodeFromString<ErrorResponse>(bodyAsText()).msg

    @Test
    fun `GET artifacts should return 400 when processId is missing`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts?path=cmd_0.out")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid or missing processId parameter", response.errorMsg())
        }

    @Test
    fun `GET artifacts should return 400 when processId is invalid`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts?processId=invalid&path=cmd_0.out")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid or missing processId parameter", response.errorMsg())
        }

    @Test
    fun `GET artifacts should return 400 when path is missing`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts?processId=123")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Missing path parameter", response.errorMsg())
        }

    @Test
    fun `GET artifacts should return 400 when path contains traversal`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts?processId=123&path=../etc/passwd")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid path: Path traversal (..) is not allowed", response.errorMsg())
            // Path validation runs before the process lookup.
            coVerify(exactly = 0) { mockProcessManager.getProcessInfo(any()) }
        }

    @Test
    fun `GET artifacts should return 404 when process not found`() =
        testApplication {
            mountArtifactRoutes(processInfo = null)

            val response = client.get("/api/v1/artifacts?processId=999&path=cmd_0.out")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Process not found", response.errorMsg())
        }

    @Test
    fun `GET artifacts should return 404 when file not found`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        mountArtifactRoutes(finishedProcessWithArtifacts(123, dir))

        val response = client.get("/api/v1/artifacts?processId=123&path=nonexistent.out")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Artifact file not found", response.errorMsg())
    }

    // ========== POST /agents/reload Tests ==========

    @Test
    fun `POST agents reload returns diff result and kills CREATED processes for updated and removed agents`() =
        testApplication {
            val reloadResult =
                AgentReloadResult(
                    added = listOf("new_agent"),
                    updated = listOf("changed_agent"),
                    removed = listOf("old_agent"),
                    errors = emptyList(),
                )
            every { mockAgentsService.reload() } returns reloadResult
            coEvery { mockProcessManager.createdRunsOf(any()) } returns
                listOf(10 to "changed_agent", 11 to "old_agent")
            mountWithServiceMocks()

            val response = client.post("/api/v1/agents/reload")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(reloadResult, apiJson.decodeFromString<AgentReloadResult>(response.bodyAsText()))
            // Only CREATED runs of updated/removed agents are looked up — never the added agent's.
            coVerify(exactly = 1) { mockProcessManager.createdRunsOf(setOf("changed_agent", "old_agent")) }
            coVerify(exactly = 1) { mockProcessManager.killProcess(10, "Agent 'changed_agent' was reloaded") }
            coVerify(exactly = 1) { mockProcessManager.killProcess(11, "Agent 'old_agent' was reloaded") }
            coVerify(exactly = 2) { mockProcessManager.killProcess(any(), any()) }
        }

    @Test
    fun `POST agents reload does not kill processes when only agents are added`() =
        testApplication {
            val reloadResult =
                AgentReloadResult(
                    added = listOf("new_agent"),
                    updated = emptyList(),
                    removed = emptyList(),
                    errors = emptyList(),
                )
            every { mockAgentsService.reload() } returns reloadResult
            mountWithServiceMocks()

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/agents/reload").status)

            coVerify(exactly = 0) { mockProcessManager.createdRunsOf(any()) }
            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
        }

    @Test
    fun `POST agent with missing required query param returns 400 via StatusPages`() =
        testApplication {
            val agent =
                Agent(
                    name = "test_agent",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)),
                    queryParams =
                        listOf(
                            Param(
                                paramName = "required_param",
                                envName = "REQUIRED_PARAM",
                                type = ParamType.STRING,
                                required = true,
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("test_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/agent/test_agent")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(body.msg.contains("required_param"), "msg should mention the missing param name")
        }

    @Test
    fun `POST agent always starts a fresh invocation chain ignoring any inbound chain header`() =
        testApplication {
            val agent =
                Agent(
                    name = "ext_agent",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                )
            every { mockAgentsService.agents } returns mapOf("ext_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    // Depth 2 — a five-deep spoofed chain would otherwise blow past it.
                                    maxInvocationDepth = 2,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            // The HTTP route no longer trusts any inbound chain header (RunAgent
            // dispatch is in-process; see ADR-0030). An external caller fabricating a
            // five-entry chain is ignored: the run starts at chain = [ext_agent].
            val response =
                client.post("/api/v1/agent/ext_agent") {
                    header("X-Homer-Invocation-Chain", "a,b,c,d,e")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    agent = agent,
                    queryParams = any(),
                    parentContext = any(),
                    invocationChain = listOf("ext_agent"),
                )
            }
        }

    @Test
    fun `POST agent ignores any inbound ancestor-lock-keys header`() =
        testApplication {
            val agent =
                Agent(
                    name = "ext_agent",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                )
            every { mockAgentsService.agents } returns mapOf("ext_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 2)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            // The route ignores any inbound ancestor-lock-keys header, so an external
            // caller cannot make an agent skip its lock (see ADR-0030).
            val response =
                client.post("/api/v1/agent/ext_agent") {
                    header("X-Homer-Ancestor-Lock-Keys", """["shared-key"]""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    agent = agent,
                    queryParams = any(),
                    parentContext = any(),
                    invocationChain = any(),
                    ancestorLockKeys = emptyList(),
                )
            }
        }

    private fun deployAgentWithSchema(): Agent =
        Agent(
            name = "deploy",
            commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
            inputs = listOf(Param(paramName = "service", envName = "SERVICE", src = ParamSource.BODY, type = ParamType.STRING)),
            inputSchema =
                Json.parseToJsonElement(
                    """
                    {"type":"object","required":["service"],
                     "properties":{"service":{"type":"string"}},
                     "additionalProperties":false}
                    """.trimIndent(),
                ) as JsonObject,
        )

    @Test
    fun `POST agent with conforming payload returns 200`() =
        testApplication {
            val agent = deployAgentWithSchema()
            every { mockAgentsService.agents } returns mapOf("deploy" to agent)
            val agentInputResolver = AgentInputResolver(InputSchemaValidator())
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val resp =
                client.post("/api/v1/agent/deploy") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"service":"api"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `POST agent with schema-violating payload returns 422`() =
        testApplication {
            val agent = deployAgentWithSchema()
            every { mockAgentsService.agents } returns mapOf("deploy" to agent)
            val agentInputResolver = AgentInputResolver(InputSchemaValidator())
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 8)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockk(relaxed = true),
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val resp =
                client.post("/api/v1/agent/deploy") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"unexpected":true}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        }

    @Test
    fun `POST agent with schema set but empty body returns 400`() =
        testApplication {
            val agent = deployAgentWithSchema()
            every { mockAgentsService.agents } returns mapOf("deploy" to agent)
            val agentInputResolver = AgentInputResolver(InputSchemaValidator())
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 9)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockk(relaxed = true),
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val resp =
                client.post("/api/v1/agent/deploy") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `POST agent with only an optional body input accepts a bodyless request`() =
        testApplication {
            val agent =
                Agent(
                    name = "optional_body",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "prompt",
                                envName = "PROMPT",
                                src = ParamSource.BODY,
                                type = ParamType.STR,
                                required = false,
                                default = "",
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("optional_body" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 11)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockk(relaxed = true),
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            // No body and no Content-Type — the console/Export Curl shape.
            val resp = client.post("/api/v1/agent/optional_body")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertContains(resp.bodyAsText(), "processId")
        }

    @Test
    fun `POST agent rejects with 429 when cost cap exceeded for an agent with a Claude command`() =
        testApplication {
            val agent = AgentFixtures.claudeAgent("billed_agent")
            every { mockAgentsService.agents } returns mapOf("billed_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockCostMeter = mockk<CostMeter>()
            coEvery { mockCostMeter.checkCaps("billed_agent") } throws
                CostCapExceededException("globalUsdPerMin", 5.0, 5.42)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockCostMeter,
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/agent/billed_agent")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(body.msg.contains("globalUsdPerMin"), "msg should name the breached limit")
            // Refused at the boundary — no process was launched.
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { mockCostMeter.checkCaps("billed_agent") }
        }

    @Test
    fun `POST agent rejects with 429 when cost cap exceeded for an agent with a Codex command`() =
        testApplication {
            // The preflight gates on any Command.LlmCommand, not only Claude — a
            // Codex-only agent must be refused the same way.
            val agent = AgentFixtures.codexAgent("billed_codex_agent")
            every { mockAgentsService.agents } returns mapOf("billed_codex_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockCostMeter = mockk<CostMeter>()
            coEvery { mockCostMeter.checkCaps("billed_codex_agent") } throws
                CostCapExceededException("globalUsdPerMin", 5.0, 5.42)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockCostMeter,
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/agent/billed_codex_agent")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(body.msg.contains("globalUsdPerMin"), "msg should name the breached limit")
            // Refused at the boundary — no process was launched.
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { mockCostMeter.checkCaps("billed_codex_agent") }
        }

    @Test
    fun `retry rejects with 429 when cost cap exceeded for an agent with a Codex command`() =
        testApplication {
            val agent = AgentFixtures.codexAgent("billed_codex_agent")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "billed_codex_agent",
                )
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockCostMeter = mockk<CostMeter>()
            coEvery { mockCostMeter.checkCaps("billed_codex_agent") } throws
                CostCapExceededException("globalUsdPerMin", 5.0, 5.42)

            application {
                install(ContentNegotiation) { json(apiJson) }
                configureStatusPages()
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = mockTelemetryService,
                            costMeter = mockCostMeter,
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            // Refused at the boundary — no process was launched.
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { mockCostMeter.checkCaps("billed_codex_agent") }
        }

    @Test
    fun `resume rejects with 429 when cost cap exceeded for an agent with a Codex command`() =
        testApplication {
            val agent = AgentFixtures.codexAgent("billed_codex_agent")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "billed_codex_agent",
                )
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockCostMeter = mockk<CostMeter>()
            coEvery { mockCostMeter.checkCaps("billed_codex_agent") } throws
                CostCapExceededException("globalUsdPerMin", 5.0, 5.42)

            application {
                install(ContentNegotiation) { json(apiJson) }
                configureStatusPages()
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = mockTelemetryService,
                            costMeter = mockCostMeter,
                            agentInputResolver = AgentInputResolver(),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/resume")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            // Refused at the boundary — no process was launched.
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { mockCostMeter.checkCaps("billed_codex_agent") }
        }

    @Test
    fun `POST agent skips cost cap check for agents without Claude commands`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent("cmd_only")
            every { mockAgentsService.agents } returns mapOf("cmd_only" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val mockCostMeter = mockk<CostMeter>(relaxed = true)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockCostMeter,
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/agent/cmd_only")

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 0) { mockCostMeter.checkCaps(any()) }
        }

    @Test
    fun `POST agent with mixed query body header inputs creates process with env from all sources`() =
        testApplication {
            val agent =
                Agent(
                    name = "mixed-inputs",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "id",
                                envName = "ID",
                                src = ParamSource.QUERY,
                                type = ParamType.STRING,
                            ),
                            Param(
                                paramName = "user",
                                envName = "USER",
                                src = ParamSource.BODY,
                                type = ParamType.STRING,
                            ),
                            Param(
                                paramName = "X-Trace-Id",
                                envName = "TRACE",
                                src = ParamSource.HEADER,
                                type = ParamType.STRING,
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("mixed-inputs" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery {
                mockProcessExecutor.execute(any(), any())
            } returns ProcessCreationResult(processId = 11)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response =
                client.post("/api/v1/agent/mixed-inputs?id=42") {
                    contentType(ContentType.Application.Json)
                    header("X-Trace-Id", "abc")
                    setBody("""{"user":"alice"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    agent = agent,
                    queryParams = mapOf("ID" to "42", "USER" to "alice", "TRACE" to "abc"),
                    parentContext = any(),
                    invocationChain = any(),
                )
            }
        }

    @Test
    fun `GET agent with body input returns 400 BadRequest`() =
        testApplication {
            val agent =
                Agent(
                    name = "needs-body",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "user",
                                envName = "USER",
                                src = ParamSource.BODY,
                                type = ParamType.STRING,
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("needs-body" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    get("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.get("/api/v1/agent/needs-body")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(
                body.msg.contains("requires request body"),
                "msg should explain that the agent requires a request body, got: ${body.msg}",
            )
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `GET agent by name returns 404 when agent not found`() =
        testApplication {
            every { mockAgentsService.agents } returns emptyMap()
            mountWithServiceMocks()

            val response = client.get("/api/v1/agent/nonexistent")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Agent not found: nonexistent", apiJson.decodeFromString<ErrorResponse>(response.bodyAsText()).msg)
        }

    // ========== CORS Tests ==========

    @Test
    fun `CORS allows request from configured allowed origin`() =
        testApplication {
            application {
                configureCors(listOf("http://console.example.com"))
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://console.example.com")
                }
            assertEquals(
                "http://console.example.com",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
        }

    @Test
    fun `CORS rejects request from origin not in allowed list`() =
        testApplication {
            application {
                configureCors(listOf("http://console.example.com"))
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://evil.example.com")
                }
            assertEquals(null, response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `CORS uses anyHost wildcard when allowed origins list is empty`() =
        testApplication {
            application {
                configureCors(emptyList())
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            // Empty origins keep anyHost() but without credential reflection, so
            // Ktor answers `*` rather than echoing the request Origin (H3 / ADR-0016).
            val response1 =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://anything.example.com")
                }
            assertEquals("*", response1.headers[HttpHeaders.AccessControlAllowOrigin])
            val response2 =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://other.example.com")
                }
            assertEquals("*", response2.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    // ========== Security headers (audit P1 #13) ==========

    @Test
    fun `responses include hardening headers on every request`() =
        testApplication {
            application {
                configureSecurityHeaders()
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response = client.get("/test")

            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("same-origin", response.headers["Referrer-Policy"])
            assertEquals("default-src 'self'", response.headers["Content-Security-Policy"])
        }

    @Test
    fun `responses include hardening headers even on error responses`() =
        testApplication {
            application {
                configureSecurityHeaders()
                routing {
                    get("/boom") {
                        call.respond(HttpStatusCode.InternalServerError, "fail")
                    }
                }
            }
            val response = client.get("/boom")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("default-src 'self'", response.headers["Content-Security-Policy"])
        }

    @Test
    fun `CORS preflight does not break when SecurityHeaders runs after CORS completes the response`() =
        testApplication {
            // Real install order: CORS first, then SecurityHeaders. CORS completes the
            // preflight response before SecurityHeaders' onCall runs; the plugin must
            // skip header append on the already-committed response instead of throwing.
            application {
                configureCors(listOf("http://console.example.com"))
                configureSecurityHeaders()
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response =
                client.options("/test") {
                    header(HttpHeaders.Origin, "http://console.example.com")
                    header(HttpHeaders.AccessControlRequestMethod, "GET")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "http://console.example.com",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
            // CORS already committed the preflight response, so SecurityHeaders must
            // skip the append (in Netty the unguarded append throws and logs ERROR).
            assertNull(response.headers["X-Frame-Options"])
        }

    // ========== GET /artifacts/list Tests ==========

    @Test
    fun `GET artifacts list returns 400 when processId missing`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts/list")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid or missing processId parameter", response.errorMsg())
        }

    @Test
    fun `GET artifacts list returns 404 when process unknown`() =
        testApplication {
            mountArtifactRoutes(processInfo = null)

            val response = client.get("/api/v1/artifacts/list?processId=999")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Process not found", response.errorMsg())
        }

    @Test
    fun `GET artifacts list returns 200 with listing on success`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        dir.resolve("cmd_0.out").toFile().writeText("hello world\n")
        mountArtifactRoutes(finishedProcessWithArtifacts(7, dir))

        val response = client.get("/api/v1/artifacts/list?processId=7")

        assertEquals(HttpStatusCode.OK, response.status)
        val listing = apiJson.decodeFromString<ArtifactListingResponse>(response.bodyAsText())
        assertEquals(7, listing.processId)
        assertEquals(listOf("cmd_0.out" to 12L), listing.files.map { it.path to it.size })
    }

    // ========== POST /processes/{id}/retry Tests ==========

    @Test
    fun `POST retry should return 400 for non-integer id`() =
        testApplication {
            mountWithServiceMocks()

            val response = client.post("/api/v1/processes/not-a-number/retry")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid process id", response.errorMsg())
        }

    @Test
    fun `POST retry should return 404 when process not found`() =
        testApplication {
            coEvery { mockProcessManager.getProcessInfo(999) } returns null
            mountWithServiceMocks()

            val response = client.post("/api/v1/processes/999/retry")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Process 999 not found", response.errorMsg())
        }

    @Test
    fun `POST retry should return 409 when process still active`() =
        testApplication {
            val active =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.WORKING,
                    agentName = "deploy",
                    inputParams = mapOf("X" to "y"),
                )
            coEvery { mockProcessManager.getProcessInfo(42) } returns active
            mountWithServiceMocks()

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("Cannot retry a non-terminal process (status: WORKING)", response.errorMsg())
        }

    @Test
    fun `POST retry should return 404 when agent no longer exists`() =
        testApplication {
            val finished =
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "ghost",
                    inputParams = emptyMap(),
                )
            coEvery { mockProcessManager.getProcessInfo(7) } returns finished
            every { mockAgentsService.agents } returns emptyMap()
            mountWithServiceMocks()

            val response = client.post("/api/v1/processes/7/retry")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Agent 'ghost' no longer exists", response.errorMsg())
        }

    @Test
    fun `POST retry should replay inputParams and return new processId on success`() =
        testApplication {
            val mockProcessFactory = mockk<ProcessFactory>()
            val mockProcessExecutor = mockk<ProcessExecutor>()
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "deploy",
                    inputParams = mapOf("X" to "y"),
                )
            // The agent still declares the stored input, so retry re-validation replays
            // it unchanged (a value the current policy still accepts).
            val fakeAgent =
                Agent(
                    name = "deploy",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs = listOf(Param(paramName = "x", envName = "X", type = ParamType.STR)),
                )
            val fakeProcess = mockk<Process>(relaxed = true)
            val creationResult = ProcessCreationResult(processId = 99)

            coEvery { mockProcessManager.getProcessInfo(42) } returns sourceProcess
            every { mockAgentsService.agents } returns mapOf("deploy" to fakeAgent)
            every {
                mockProcessFactory.create(any<Agent>(), any<Map<String, String>>(), any(), any())
            } returns fakeProcess
            coEvery { mockProcessExecutor.execute(any<Process>(), any()) } returns creationResult
            mountWithServiceMocks { stubRunDeps(mockProcessFactory, mockProcessExecutor) }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(99, apiJson.decodeFromString<ExecuteResponse>(response.bodyAsText()).processId)
            verify {
                mockProcessFactory.create(
                    fakeAgent,
                    mapOf("X" to "y"),
                    any(),
                    any(),
                )
            }
            coVerify { mockProcessExecutor.execute(fakeProcess, any()) }
        }

    @Test
    fun `POST retry returns 400 when the current agent now requires an input absent from the stored run`() =
        testApplication {
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "deploy",
                    inputParams = emptyMap(),
                )
            // The stored run predates a reload that made "id" required — retry must
            // validate against the CURRENT agent definition, not the original one.
            val reloadedAgent =
                Agent(
                    name = "deploy",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs = listOf(Param(paramName = "id", envName = "ID", required = true)),
                )
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = reloadedAgent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = mockTelemetryService,
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = agentInputResolver,
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(body.msg.contains("id"), "msg should mention the now-required param name")
            coVerify(exactly = 0) { mockProcessExecutor.execute(any(), any()) }
        }

    // ========== POST /agents/{name}/debug Tests ==========

    @Test
    fun `POST agents debug accepts body and headers payload and resolves them into env`() =
        testApplication {
            val agent =
                Agent(
                    name = "debug-body",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "user",
                                envName = "USER",
                                src = ParamSource.BODY,
                                type = ParamType.STRING,
                            ),
                            Param(
                                paramName = "X-Trace-Id",
                                envName = "TRACE",
                                src = ParamSource.HEADER,
                                type = ParamType.STRING,
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("debug-body" to agent)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery {
                mockProcessExecutor.execute(any(), any())
            } returns ProcessCreationResult(processId = 7)
            mountWithServiceMocks { stubRunDeps(mockProcessFactory, mockProcessExecutor) }

            val response =
                client.post("/api/v1/agents/debug-body/debug") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "commandIndex": 0,
                          "env": {"EXTRA":"v"},
                          "body": {"user":"alice"},
                          "headers": {"X-Trace-Id":"abc"}
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    match<Agent> { it.name == "debug-body" && it.env["EXTRA"] == "v" },
                    mapOf("USER" to "alice", "TRACE" to "abc"),
                    any(),
                    commandIndices = listOf(0),
                )
            }
        }

    @Test
    fun `debug endpoint rejects reserved env overrides`() =
        testApplication {
            val agent =
                Agent(
                    name = "debug-reserved",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                )
            every { mockAgentsService.agents } returns mapOf("debug-reserved" to agent)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery {
                mockProcessExecutor.execute(any(), any())
            } returns ProcessCreationResult(processId = 7)
            mountWithServiceMocks { stubRunDeps(mockProcessFactory, mockProcessExecutor) }

            val response =
                client.post("/api/v1/agents/debug-reserved/debug") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"commandIndex":0,"env":{"PATH":"/tmp/evil"},"queryParams":{},"headers":{}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)

            // BASH_ENV is sourced by non-interactive `bash -c` (WhenEvaluator)
            // before the expression runs, so it must be rejected like PATH.
            val bashEnvResponse =
                client.post("/api/v1/agents/debug-reserved/debug") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"commandIndex":0,"env":{"BASH_ENV":"/tmp/evil.sh"},"queryParams":{},"headers":{}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, bashEnvResponse.status)
            verify(exactly = 0) {
                mockProcessFactory.create(any(), any(), any(), any(), any(), any())
            }
        }

    // ========== POST /agents/{name}/cron-run Tests ==========

    @Test
    fun `POST agents cron-run fires the agent once and returns 204`() =
        testApplication {
            val agent =
                AgentFixtures.simpleAgent(name = "cron-run-agent").copy(
                    cron = CronConfig(expression = "* * * * *", timezone = "UTC"),
                )
            val scheduler = mockk<CronSchedulerService>(relaxed = true)
            coEvery { scheduler.fireNow(agent) } returns Unit
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("cron-run-agent" to agent)
                every { cronSchedulerService } returns scheduler
            }

            val response = client.post("/api/v1/agents/cron-run-agent/cron-run")

            assertEquals(HttpStatusCode.NoContent, response.status)
            coVerify(exactly = 1) { scheduler.fireNow(agent) }
        }

    @Test
    fun `POST agents cron-run denies a non-admin with the same 404 as an unknown agent`() =
        testApplication {
            val agent =
                AgentFixtures.simpleAgent(name = "cron-run-agent").copy(
                    cron = CronConfig(expression = "* * * * *", timezone = "UTC"),
                )
            val scheduler = mockk<CronSchedulerService>(relaxed = true)
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("cron-run-agent" to agent)
                every { cronSchedulerService } returns scheduler
                // Enabled auth with no grants: a non-admin principal.
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
            }

            val response = client.post("/api/v1/agents/cron-run-agent/cron-run")

            assertEquals(HttpStatusCode.NotFound, response.status)
            coVerify(exactly = 0) { scheduler.fireNow(any()) }
        }

    @Test
    fun `POST agents cron-run denial body matches the unknown-agent 404`() =
        testApplication {
            val agent =
                AgentFixtures.simpleAgent(name = "cron-run-agent").copy(
                    cron = CronConfig(expression = "* * * * *", timezone = "UTC"),
                )
            val scheduler = mockk<CronSchedulerService>(relaxed = true)
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("cron-run-agent" to agent)
                every { cronSchedulerService } returns scheduler
                // Enabled auth with no grants: a non-admin principal.
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
            }

            val denied = client.post("/api/v1/agents/cron-run-agent/cron-run")
            val unknown = client.post("/api/v1/agents/no-such-agent/cron-run")

            // Existence hiding (ADR-0047 rule 3) is the body, not the status alone:
            // the denial must be the unknown-agent 404 apart from the name the
            // caller itself supplied — no "not permitted" wording or extra field
            // a grant-probing caller could read existence from.
            assertEquals(HttpStatusCode.NotFound, denied.status)
            assertEquals(unknown.bodyAsText().replace("no-such-agent", "cron-run-agent"), denied.bodyAsText())
            coVerify(exactly = 0) { scheduler.fireNow(any()) }
        }

    @Test
    fun `POST agents cron-run returns 404 for an unknown agent`() =
        testApplication {
            val scheduler = mockk<CronSchedulerService>(relaxed = true)
            mountApiV1Routes {
                every { agentsService.agents } returns emptyMap()
                every { cronSchedulerService } returns scheduler
            }

            val response = client.post("/api/v1/agents/no-such-agent/cron-run")

            assertEquals(HttpStatusCode.NotFound, response.status)
            coVerify(exactly = 0) { scheduler.fireNow(any()) }
        }

    // ========== CORS exposed-headers Tests ==========

    @Test
    fun `CORS exposes artifact completeness headers to cross-origin clients`() =
        testApplication {
            application {
                configureCors(emptyList())
                routing {
                    get("/api/v1/artifacts") {
                        call.response.headers.append("X-Process-Status", "FINISHED")
                        call.response.headers.append("X-File-Complete", "true")
                        call.respondText("done")
                    }
                }
            }

            val response =
                client.get("/api/v1/artifacts") {
                    header(HttpHeaders.Origin, "http://localhost:3000")
                }

            // Without Access-Control-Expose-Headers the browser hides these
            // custom headers from JS, so the console reads `null` and treats
            // every finished artifact as still streaming (stuck Live badge).
            val exposed = response.headers["Access-Control-Expose-Headers"] ?: ""
            assertTrue(
                exposed.contains("X-File-Complete"),
                "Access-Control-Expose-Headers must list X-File-Complete, was: '$exposed'",
            )
            assertTrue(
                exposed.contains("X-Process-Status"),
                "Access-Control-Expose-Headers must list X-Process-Status, was: '$exposed'",
            )
        }

    // ========== GET /agents/{agentName}/langgraph Tests ==========

    @Test
    fun `GET langgraph returns 404 for unknown agent`() =
        testApplication {
            mountApiV1Routes()

            val response = client.get("/api/v1/agents/does-not-exist/langgraph")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ========== server.maxRequestBodyBytes Tests ==========

    /**
     * Mounts a minimal app with [RequestBodyLimit] installed at `maxRequestBodyBytes`
     * (mirrors the application-level install in `configureServer`) plus a `body-input-agent`
     * with a single BODY input, wired the same way as the other `handleAgentExecution` tests.
     */
    private fun ApplicationTestBuilder.mountBodyLimitedAgent(maxRequestBodyBytes: Long) {
        val agent =
            Agent(
                name = "body-input-agent",
                commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                inputs =
                    listOf(
                        Param(
                            paramName = "user",
                            envName = "USER",
                            src = ParamSource.BODY,
                            type = ParamType.STRING,
                        ),
                    ),
            )
        every { mockAgentsService.agents } returns mapOf("body-input-agent" to agent)
        val agentInputResolver = AgentInputResolver()
        val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
        val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
        coEvery {
            mockProcessExecutor.execute(any(), any())
        } returns ProcessCreationResult(processId = 31)

        application {
            install(RequestBodyLimit) {
                bodyLimit { maxRequestBodyBytes }
            }
            install(ContentNegotiation) {
                json(apiJson)
            }
            configureStatusPages()
            routing {
                post("/api/v1/agent/{agentName}") {
                    handleAgentExecution(
                        call = call,
                        agent = agent,
                        agentInputResolver = agentInputResolver,
                        agentRunStarter =
                            AgentRunStarter(
                                serverId = "test-server",
                                processExecutor = mockProcessExecutor,
                                processFactory = mockProcessFactory,
                                telemetryService = mockTelemetryService,
                                costMeter = mockk(relaxed = true),
                                maxInvocationDepth = 5,
                                cooldownGate = mockk(relaxed = true),
                                maintenanceGate = mockk(relaxed = true),
                            ),
                    )
                }
            }
        }
    }

    @Test
    fun `request body over maxRequestBodyBytes is rejected with 413`() =
        testApplication {
            mountBodyLimitedAgent(maxRequestBodyBytes = 1024)

            val bigBody = """{"user":"${"x".repeat(2048)}"}"""
            val response =
                client.post("/api/v1/agent/body-input-agent") {
                    contentType(ContentType.Application.Json)
                    setBody(bigBody)
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `request body under maxRequestBodyBytes passes the limit`() =
        testApplication {
            mountBodyLimitedAgent(maxRequestBodyBytes = 1024)

            val response =
                client.post("/api/v1/agent/body-input-agent") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"user":"alice"}""")
                }

            assertNotEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    // ---- Langfuse trace deep link ----

    private fun processWithTrace(traceId: String?) =
        ProcessInfo(
            id = 1,
            status = ProcessStatus.FINISHED,
            agentName = "test-agent",
            traceId = traceId,
        )

    @Test
    fun `toResponse builds a Langfuse trace URL from serverUrl, projectId and traceId`() {
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://lf.example.test/", projectId = "p1"),
            )

        assertEquals(
            "https://lf.example.test/project/p1/traces/0af7651916cd43dd8448eb211c80319c",
            response.langfuseTraceUrl,
        )
    }

    @Test
    fun `toResponse omits the trace URL when projectId is not configured`() {
        // Langfuse derives the project from the API keys and never sends it back over
        // OTLP, so without the configured id there is no URL that resolves.
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://lf.example.test"),
            )

        assertNull(response.langfuseTraceUrl)
    }

    @Test
    fun `toResponse omits the trace URL for a run that has no trace id`() {
        val response =
            processWithTrace(null).toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://lf.example.test", projectId = "p1"),
            )

        assertNull(response.langfuseTraceUrl)
    }

    @Test
    fun `toResponse strips credentials from the trace URL it returns`() {
        // The field goes to every caller who can see the process, and on to the browser
        // as an external link. An operator credential in serverUrl must not ride along
        // — the same policy the log sink already applied.
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://user:pa55w0rd@lf.example.test", projectId = "p1"),
            )

        assertEquals(
            "https://lf.example.test/project/p1/traces/0af7651916cd43dd8448eb211c80319c",
            response.langfuseTraceUrl,
        )
    }

    @Test
    fun `toResponse strips a query token and fragment from the trace URL`() {
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://lf.example.test?token=s3cret#f", projectId = "p1"),
            )

        assertEquals(
            "https://lf.example.test/project/p1/traces/0af7651916cd43dd8448eb211c80319c",
            response.langfuseTraceUrl,
        )
    }

    @Test
    fun `toResponse keeps a base path and an explicit port in the trace URL`() {
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "http://localhost:3000/langfuse", projectId = "p1"),
            )

        assertEquals(
            "http://localhost:3000/langfuse/project/p1/traces/0af7651916cd43dd8448eb211c80319c",
            response.langfuseTraceUrl,
        )
    }

    @Test
    fun `toResponse omits the trace URL when serverUrl will not parse`() {
        // No link beats a guessed one: an unparseable value is where deciding which
        // part is sensitive is least safe.
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "http://ho st:/// not a uri", projectId = "p1"),
            )

        assertNull(response.langfuseTraceUrl)
    }

    // ---- Runner alias disclosure (CWE-200) ----

    private fun processWithRunner(alias: String) =
        ProcessInfo(
            id = 1,
            status = ProcessStatus.FINISHED,
            agentName = "test-agent",
            runner =
                RunnerState(
                    alias = alias,
                    podPhase = PodPhase.READY,
                ),
        )

    @Test
    fun `RunnerState toResponse withholds the alias by default but always emits the type`() {
        // Fail-closed default: a caller that does not pass `aliasVisible = true`
        // (a non-admin) gets the generic backend kind, never the operator-chosen alias.
        val response =
            RunnerState(alias = "k8s-prod-eu", podPhase = PodPhase.READY)
                .toResponse(type = "kubernetes", aliasVisible = false)

        assertNull(response.alias, "a non-admin response must not carry the raw alias")
        assertEquals("kubernetes", response.type)
    }

    @Test
    fun `RunnerState toResponse keeps the alias for an admin caller`() {
        val response =
            RunnerState(alias = "k8s-prod-eu", podPhase = PodPhase.READY)
                .toResponse(type = "kubernetes", aliasVisible = true)

        assertEquals("k8s-prod-eu", response.alias)
        assertEquals("kubernetes", response.type)
    }

    @Test
    fun `ProcessInfo toResponse withholds the runner alias for a non-admin and emits the type`() {
        val response =
            processWithRunner("ssh-build-01").toResponse(
                agent = null,
                aliasVisible = false,
                runnerType = "ssh",
            )

        val runner = assertNotNull(response.runner)
        assertNull(runner.alias, "a non-admin row must not leak the runner alias")
        assertEquals("ssh", runner.type)
    }

    @Test
    fun `ProcessInfo toResponse keeps the runner alias for an admin`() {
        val response =
            processWithRunner("ssh-build-01").toResponse(
                agent = null,
                aliasVisible = true,
                runnerType = "ssh",
            )

        assertEquals("ssh-build-01", response.runner?.alias)
        assertEquals("ssh", response.runner?.type)
    }

    @Test
    fun `ProcessInfo toResponse has no runner block when the run never got a session`() {
        val response =
            processWithTrace(null).toResponse(
                agent = null,
                aliasVisible = true,
                runnerType = null,
            )

        assertNull(response.runner)
    }
}
