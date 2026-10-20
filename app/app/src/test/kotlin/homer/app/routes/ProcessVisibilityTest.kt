package homer.app.routes

import homer.app.EnvConstants
import homer.app.apiJson
import homer.app.configureStatusPages
import homer.app.executors.CommandArtifactPaths
import homer.app.executors.Process
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.SqlStatementRecorder
import homer.app.fixtures.authorizerFor
import homer.app.fixtures.installGrantsApp
import homer.app.infra.FileOperations
import homer.app.infra.LocalFileOperations
import homer.app.infra.SecretRedactor
import homer.app.infra.matchesTokenConstantTime
import homer.app.models.Action
import homer.app.models.AppConfig
import homer.app.models.ArtifactListingResponse
import homer.app.models.AuthConfig
import homer.app.models.Command
import homer.app.models.ContinuationListResponse
import homer.app.models.DispatchListResponse
import homer.app.models.ExecuteResponse
import homer.app.models.ExecutionInfo
import homer.app.models.Grant
import homer.app.models.LangGraphRunIdentity
import homer.app.models.LangGraphRunStatus
import homer.app.models.LlmEvent
import homer.app.models.PodPhase
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessInfo
import homer.app.models.ProcessInfoResponse
import homer.app.models.ProcessListResponse
import homer.app.models.ProcessStatus
import homer.app.models.QuestionsListResponse
import homer.app.models.RunnerConfig
import homer.app.models.RunnerState
import homer.app.models.Server
import homer.app.models.TokenConfig
import homer.app.services.AgentsService
import homer.app.services.ArtifactService
import homer.app.services.Authorizer
import homer.app.services.Continuation
import homer.app.services.ContinuationService
import homer.app.services.ContinuationStatus
import homer.app.services.Dispatch
import homer.app.services.DispatchService
import homer.app.services.DispatchStatus
import homer.app.services.LangGraphRunStatusService
import homer.app.services.LlmArtifact
import homer.app.services.LlmEventStreamService
import homer.app.services.LlmStreamEvent
import homer.app.services.LogTailService
import homer.app.services.ProcessEvent
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.StreamCursor
import homer.app.services.persistence.ContinuationRepository
import homer.app.services.persistence.DispatchRepository
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Task 6 (Plan 2, process visibility): the union rule (admin ∪ owner-match ∪
 * read-grant) enforced on the single-resource process routes
 * (status/kill/artifacts/artifacts-list/retry/answer), with denial mapped to
 * 404 so a foreign caller cannot distinguish "no permission" from "does not
 * exist". [installGrantsApp] mounts the production bearer provider, so
 * `call.principalName()` is exercised through the real Authentication
 * pipeline with a real [Authorizer] built from [TokenConfig]
 * grants.
 */
@OptIn(ExperimentalTime::class)
class ProcessVisibilityTest {
    private val deployAgent = AgentFixtures.simpleAgent(name = "deploy")
    private val otherAgent = AgentFixtures.simpleAgent(name = "other")

    private fun deps(
        authorizer: Authorizer,
        processInfo: ProcessInfo? = null,
        agents: Map<String, homer.app.models.Agent> = mapOf("deploy" to deployAgent, "other" to otherAgent),
        artifactService: ArtifactService = mockk(relaxed = true),
        fileOperations: FileOperations = mockk(relaxed = true),
        questionsService: QuestionsService = mockk(relaxed = true),
        processExecutor: ProcessExecutor = mockk(relaxed = true),
        dispatchService: DispatchService = mockk(relaxed = true),
        continuationService: ContinuationService = mockk(relaxed = true),
        processEventBroadcaster: ProcessEventBroadcaster = mockk(relaxed = true),
        logTailService: LogTailService = mockk(relaxed = true),
        llmEventStreamService: LlmEventStreamService = mockk(relaxed = true),
        processManagerOverride: ProcessManager? = null,
        langGraphRunStatusService: LangGraphRunStatusService = mockk(relaxed = true),
        processFactory: ProcessFactory = mockk(relaxed = true),
        runners: Map<String, RunnerConfig> = emptyMap(),
    ): ApiDeps {
        val agentsService = mockk<AgentsService>(relaxed = true)
        every { agentsService.agents } returns agents
        val processManager =
            processManagerOverride ?: mockk<ProcessManager>(relaxed = true) {
                coEvery { getProcessInfo(any()) } returns processInfo
                coEvery { getProcessStatus(any()) } returns (processInfo?.status ?: ProcessStatus.UNKNOWN)
            }
        return mockk<ApiDeps>(relaxed = true) {
            every { this@mockk.config } returns
                AppConfig(agentsSearchPath = "unused", workspaceRootPath = "unused", databasePath = "unused", runners = runners)
            every { this@mockk.authorizer } returns authorizer
            every { this@mockk.agentsService } returns agentsService
            every { this@mockk.processManager } returns processManager
            every { this@mockk.artifactService } returns artifactService
            every { this@mockk.fileOperations } returns fileOperations
            every { this@mockk.questionsService } returns questionsService
            every { this@mockk.processExecutor } returns processExecutor
            every { this@mockk.dispatchService } returns dispatchService
            every { this@mockk.continuationService } returns continuationService
            every { this@mockk.processEventBroadcaster } returns processEventBroadcaster
            every { this@mockk.logTailService } returns logTailService
            every { this@mockk.llmEventStreamService } returns llmEventStreamService
            every { this@mockk.langGraphRunStatusService } returns langGraphRunStatusService
            every { this@mockk.processFactory } returns processFactory
        }
    }

    // Owner principal, no grants at all -- canSee/canControl still pass via
    // the owner-match branch, but canRetry requires a RUN grant regardless.
    private val ownerToken = TokenConfig(token = "owner-token")

    // READ-only on "deploy" -- sees status/artifacts but cannot control (kill/retry).
    private val readToken = TokenConfig(token = "read-token", grants = listOf(Grant(listOf("deploy"), listOf(Action.READ))))

    // READ+CONTROL on "deploy" -- can also kill and answer.
    private val controlToken =
        TokenConfig(token = "control-token", grants = listOf(Grant(listOf("deploy"), listOf(Action.READ, Action.CONTROL))))

    // Grant on a *different* agent -- must be treated as a stranger to "deploy" processes.
    private val foreignToken =
        TokenConfig(token = "foreign-token", grants = listOf(Grant(listOf("other"), listOf(Action.READ, Action.CONTROL, Action.RUN))))

    private val adminToken = TokenConfig(token = "admin-token", role = "admin")

    // Owner AND a RUN grant on "deploy" -- the only combination that satisfies
    // canRetry = canSee (owner-match) && hasAction(RUN).
    private val ownerRunToken =
        TokenConfig(token = "owner-run-token", grants = listOf(Grant(listOf("deploy"), listOf(Action.RUN))))

    private fun deployProcess(
        owner: String? = "alice",
        status: ProcessStatus = ProcessStatus.FINISHED,
    ) = ProcessInfo(
        id = 42,
        status = status,
        agentName = "deploy",
        owner = owner,
    )

    // ---------- GET /status/{id} ----------

    @Test
    fun `status owner sees own process`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer owner-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `status foreign principal gets 404, hiding existence`() =
        testApplication {
            val tokens = mapOf("mallory" to foreignToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer foreign-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `status response includes the process owner`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer owner-token") }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.bodyAsText().contains("\"owner\": \"alice\""))
        }

    @Test
    fun `status admin sees any process`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer admin-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `status read-granted principal sees a process it does not own`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer read-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `status reports each execution's kind and the running command's kind and index`() =
        testApplication {
            // Simulates a debug single-command run at cmdIndex 3: the debug path
            // (AgentExecutor's selectedIndices) skips non-selected indices with a bare
            // `continue`, so `executions` never grows past one entry, but
            // commandArtifacts is still keyed by the command's real loop index — the
            // status handler must resolve the *tracked* index (3), not
            // `executions.size` (1).
            val tokens = mapOf("root" to adminToken)
            val pm = mockk<ProcessManager>(relaxed = true)
            val info =
                deployProcess(status = ProcessStatus.WORKING).copy(
                    executions = listOf(ExecutionInfo("s", 1, 2, 0, null, null, null, kind = "script")),
                    currentCommand = "gen",
                )
            coEvery { pm.getProcessInfo(42) } returns info
            coEvery { pm.latestCommandArtifacts(42) } returns
                (3 to CommandArtifactPaths(Path("/tmp/o"), Path("/tmp/e"), kind = "codex"))
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), processManagerOverride = pm)) }
            val body = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer admin-token") }.bodyAsText()
            val res = apiJson.decodeFromString<ProcessInfoResponse>(body)
            assertEquals("script", res.executions.single().kind)
            assertEquals("codex", res.currentCommandKind)
            assertEquals(3, res.currentCommandIndex)
        }

    @Test
    fun `status reports null current command kind and index when nothing is running`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            val pm = mockk<ProcessManager>(relaxed = true)
            val info = deployProcess(status = ProcessStatus.WORKING).copy(currentCommand = null)
            coEvery { pm.getProcessInfo(42) } returns info
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), processManagerOverride = pm)) }
            val body = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer admin-token") }.bodyAsText()
            val res = apiJson.decodeFromString<ProcessInfoResponse>(body)
            assertEquals(null, res.currentCommandKind)
            assertEquals(null, res.currentCommandIndex)
            coVerify(exactly = 0) { pm.latestCommandArtifacts(any()) }
        }

    // ---------- Runner alias disclosure (CWE-200) ----------

    private fun processWithRunner(alias: String = "k8s-prod-eu") =
        deployProcess().copy(runner = RunnerState(alias = alias, podPhase = PodPhase.READY))

    @Test
    fun `status withholds the runner alias from a non-admin caller and emits the backend type`() =
        testApplication {
            // CWE-200: the alias is operator-chosen backend metadata
            // (an ssh host, a Kubernetes pool, an external instance name). A read-granted
            // non-admin who can see the run must get the generic backend type instead.
            val tokens = mapOf("reader" to readToken)
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        processWithRunner(),
                        runners = mapOf("k8s-prod-eu" to RunnerConfig.Kubernetes(image = "ubuntu:24.04")),
                    ),
                )
            }

            val res = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer read-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            val runner = apiJson.decodeFromString<ProcessInfoResponse>(res.bodyAsText()).runner
            assertEquals(null, runner?.alias, "a non-admin caller must not receive the raw alias: ${res.bodyAsText()}")
            assertEquals("kubernetes", runner?.type)
        }

    @Test
    fun `status-all withholds the runner alias from a non-admin caller and emits the backend type`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery {
                pm.getAllProcesses(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns
                ProcessManager.ProcessListResult(processes = listOf(processWithRunner()), total = 1)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        processManagerOverride = pm,
                        questionsService = questionsService,
                        runners = mapOf("k8s-prod-eu" to RunnerConfig.Kubernetes(image = "ubuntu:24.04")),
                    ),
                )
            }

            val res = client.get("/api/v1/status/all") { header(HttpHeaders.Authorization, "Bearer read-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            val row = apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText()).processes.single()
            assertEquals(null, row.runner?.alias, "the list path must withhold the alias too: ${res.bodyAsText()}")
            assertEquals("kubernetes", row.runner?.type)
        }

    @Test
    fun `status keeps the runner alias for an admin caller`() =
        testApplication {
            // Sibling case: the admin gate must still let the real alias through, or the
            // suppression would be a blanket removal rather than a per-caller gate.
            val tokens = mapOf("root" to adminToken)
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        processWithRunner(),
                        runners = mapOf("k8s-prod-eu" to RunnerConfig.Kubernetes(image = "ubuntu:24.04")),
                    ),
                )
            }

            val res = client.get("/api/v1/status/42") { header(HttpHeaders.Authorization, "Bearer admin-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            val runner = apiJson.decodeFromString<ProcessInfoResponse>(res.bodyAsText()).runner
            assertEquals("k8s-prod-eu", runner?.alias)
            assertEquals("kubernetes", runner?.type)
        }

    @Test
    fun `status-all keeps the runner alias for an admin caller`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery {
                pm.getAllProcesses(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns
                ProcessManager.ProcessListResult(processes = listOf(processWithRunner()), total = 1)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        processManagerOverride = pm,
                        questionsService = questionsService,
                        runners = mapOf("k8s-prod-eu" to RunnerConfig.Kubernetes(image = "ubuntu:24.04")),
                    ),
                )
            }

            val res = client.get("/api/v1/status/all") { header(HttpHeaders.Authorization, "Bearer admin-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            val row = apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText()).processes.single()
            assertEquals("k8s-prod-eu", row.runner?.alias)
            assertEquals("kubernetes", row.runner?.type)
        }

    // ---------- DELETE /kill/{id} ----------

    @Test
    fun `kill owner can kill own process`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.delete("/api/v1/kill/42") { header(HttpHeaders.Authorization, "Bearer owner-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `kill read-only principal cannot kill`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.delete("/api/v1/kill/42") { header(HttpHeaders.Authorization, "Bearer read-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `kill control-granted principal can kill`() =
        testApplication {
            val tokens = mapOf("controller" to controlToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.delete("/api/v1/kill/42") { header(HttpHeaders.Authorization, "Bearer control-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `kill foreign principal gets 404`() =
        testApplication {
            val tokens = mapOf("mallory" to foreignToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            val res = client.delete("/api/v1/kill/42") { header(HttpHeaders.Authorization, "Bearer foreign-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `kill on an unknown process is 404 (existence never leaks, even for admin)`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), processInfo = null)) }

            val res = client.delete("/api/v1/kill/999") { header(HttpHeaders.Authorization, "Bearer admin-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    // ---------- GET /artifacts, GET /artifacts/list ----------

    private fun readyArtifactService(): ArtifactService =
        mockk(relaxed = true) {
            every { validateRelativePath(any()) } returns Result.success("cmd_0.out")
            coEvery { resolveArtifactPath(any(), any()) } returns Result.success(Path("/tmp/nonexistent-artifact.out"))
            every { checkArtifactSize(any()) } returns Result.success(0L)
            coEvery { streamArtifact(any(), any()) } returns Unit
            coEvery { listArtifacts(any()) } returns
                Result.success(ArtifactListingResponse(processId = 42, artifactsDir = "/tmp", files = emptyList()))
        }

    @Test
    fun `artifacts read-granted principal passes the visibility gate and streams the file`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val artifactService = readyArtifactService()
            val fileOperations = mockk<FileOperations>(relaxed = true) { every { fileExists(any()) } returns true }
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        deployProcess(owner = "alice"),
                        artifactService = artifactService,
                        fileOperations = fileOperations,
                    ),
                )
            }

            val res =
                client.get("/api/v1/artifacts?processId=42&path=cmd_0.out") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    private fun ApplicationTestBuilder.mountArtifactsFor(sizeCheck: Result<Long>) {
        val tokens = mapOf("reader" to readToken)
        val artifactService = readyArtifactService().also { every { it.checkArtifactSize(any()) } returns sizeCheck }
        val fileOperations = mockk<FileOperations>(relaxed = true) { every { fileExists(any()) } returns true }
        application {
            installGrantsApp(
                tokens,
                deps(
                    authorizerFor(tokens),
                    deployProcess(owner = "alice"),
                    artifactService = artifactService,
                    fileOperations = fileOperations,
                ),
            )
        }
    }

    @Test
    fun `artifacts over server maxArtifactBytes answer 413 naming both sizes`() =
        testApplication {
            mountArtifactsFor(Result.failure(homer.app.services.ArtifactTooLargeException(size = 200, maxBytes = 100)))

            val res =
                client.get("/api/v1/artifacts?processId=42&path=cmd_0.out") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
            assertTrue(res.bodyAsText().contains("Artifact exceeds server.maxArtifactBytes (200 > 100)"))
        }

    @Test
    fun `artifacts whose size check fails otherwise answer 500 with its message`() =
        testApplication {
            mountArtifactsFor(Result.failure(java.io.IOException("stat failed")))

            val res =
                client.get("/api/v1/artifacts?processId=42&path=cmd_0.out") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }
            assertEquals(HttpStatusCode.InternalServerError, res.status)
            assertTrue(res.bodyAsText().contains("stat failed"))
        }

    @Test
    fun `artifacts foreign principal gets 404 before the artifact is even resolved`() =
        testApplication {
            // Path validation happens before the process lookup (pure input
            // validation), so it still runs; what must NOT happen is resolving
            // the artifact path itself -- that's the visibility gate's job.
            val artifactService = readyArtifactService()
            val tokens = mapOf("mallory" to foreignToken)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), artifactService = artifactService))
            }

            val res =
                client.get("/api/v1/artifacts?processId=42&path=cmd_0.out") {
                    header(HttpHeaders.Authorization, "Bearer foreign-token")
                }
            assertEquals(HttpStatusCode.NotFound, res.status)
            io.mockk.coVerify(exactly = 0) { artifactService.resolveArtifactPath(any(), any()) }
        }

    @Test
    fun `artifacts list read-granted principal sees the listing`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val artifactService = readyArtifactService()
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), artifactService = artifactService))
            }

            val res =
                client.get("/api/v1/artifacts/list?processId=42") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `artifacts list foreign principal gets 404`() =
        testApplication {
            val artifactService = mockk<ArtifactService>(relaxed = true)
            val tokens = mapOf("mallory" to foreignToken)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), artifactService = artifactService))
            }

            val res =
                client.get("/api/v1/artifacts/list?processId=42") {
                    header(HttpHeaders.Authorization, "Bearer foreign-token")
                }
            assertEquals(HttpStatusCode.NotFound, res.status)
            io.mockk.coVerify(exactly = 0) { artifactService.listArtifacts(any()) }
        }

    // ---------- POST /processes/{id}/retry ----------

    @Test
    fun `retry foreign principal gets 404, not 409, even on a non-terminal process`() =
        testApplication {
            val tokens = mapOf("mallory" to foreignToken)
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice", status = ProcessStatus.WORKING)),
                )
            }

            val res = client.post("/api/v1/processes/42/retry") { header(HttpHeaders.Authorization, "Bearer foreign-token") }
            // Existence-hiding must win over the "cannot retry a non-terminal
            // process" 409 -- a foreign caller must not learn the process even
            // exists, let alone its current status.
            assertEquals(HttpStatusCode.NotFound, res.status)
            assertNotEquals(HttpStatusCode.Conflict, res.status)
        }

    @Test
    fun `retry owner without a RUN grant gets 404 despite owning a terminal process`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice", status = ProcessStatus.FINISHED)),
                )
            }

            val res = client.post("/api/v1/processes/42/retry") { header(HttpHeaders.Authorization, "Bearer owner-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `retry owner with a RUN grant passes canRetry and reaches handleRetry`() =
        testApplication {
            // Positive counterpart to the two denial tests above: proves the
            // canRetry gate actually lets an authorized caller through (not just
            // that it blocks everyone else) -- an inverted condition or a
            // wrong-argument bug in the gate would otherwise go undetected.
            val tokens = mapOf("alice" to ownerRunToken)
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 100)
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        deployProcess(owner = "alice", status = ProcessStatus.FINISHED),
                        processExecutor = processExecutor,
                    ),
                )
            }

            val res = client.post("/api/v1/processes/42/retry") { header(HttpHeaders.Authorization, "Bearer owner-run-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    // ---------- GET /processes/{id}/langgraph ----------

    @Test
    fun `langgraph unknown process gets 404`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), processInfo = null)) }

            val res = client.get("/api/v1/processes/999/langgraph") { header(HttpHeaders.Authorization, "Bearer admin-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `langgraph foreign principal gets 404, hiding existence`() =
        testApplication {
            val tokens = mapOf("mallory" to foreignToken)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice")))
            }

            val res = client.get("/api/v1/processes/42/langgraph") { header(HttpHeaders.Authorization, "Bearer foreign-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `langgraph returns 404 with a specific message when the process has no run-identity artifact`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            val statusService = mockk<LangGraphRunStatusService>(relaxed = true)
            coEvery { statusService.status(any(), any(), any()) } returns null
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice"), langGraphRunStatusService = statusService),
                )
            }

            val res = client.get("/api/v1/processes/42/langgraph") { header(HttpHeaders.Authorization, "Bearer owner-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
            assertTrue(res.bodyAsText().contains("no LangGraph run state"))
        }

    @Test
    fun `langgraph with a staged run-identity artifact returns the thread id`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            val artifactsDir = Files.createTempDirectory("langgraph-status-test")
            Files.writeString(artifactsDir.resolve("langgraph.json"), """{"threadId":"t1","module":"m","segment":"fresh"}""")
            val langGraphAgent = deployAgent.copy(commands = listOf(Command.LangGraph(module = "m")))
            val realStatusService =
                LangGraphRunStatusService(
                    fileOperations = LocalFileOperations(),
                    stateRunner = mockk(relaxed = true),
                    appConfig = AppConfig(agentsSearchPath = "unused", workspaceRootPath = "unused", databasePath = "unused"),
                    continuationRepository = mockk(relaxed = true),
                    processManager = mockk(relaxed = true),
                )
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        deployProcess(owner = "alice").copy(artifactsDir = artifactsDir),
                        agents = mapOf("deploy" to langGraphAgent, "other" to otherAgent),
                        langGraphRunStatusService = realStatusService,
                    ),
                )
            }

            val res = client.get("/api/v1/processes/42/langgraph") { header(HttpHeaders.Authorization, "Bearer owner-token") }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<LangGraphRunStatus>(res.bodyAsText())
            assertEquals("t1", body.threadId)
            assertEquals(null, body.nodes)
        }

    // ---------- POST /processes/{id}/resume ----------

    @Test
    fun `resume foreign principal gets 404, not 409, even on an eligible process`() =
        testApplication {
            val tokens = mapOf("mallory" to foreignToken)
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice", status = ProcessStatus.FAILED)),
                )
            }

            val res = client.post("/api/v1/processes/42/resume") { header(HttpHeaders.Authorization, "Bearer foreign-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `resume on a non-eligible status returns 409`() =
        testApplication {
            val tokens = mapOf("alice" to ownerRunToken)
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice", status = ProcessStatus.FINISHED)),
                )
            }

            val res = client.post("/api/v1/processes/42/resume") { header(HttpHeaders.Authorization, "Bearer owner-run-token") }
            assertEquals(HttpStatusCode.Conflict, res.status)
        }

    @Test
    fun `resume on a FAILED process without a run-identity artifact returns 400`() =
        testApplication {
            val tokens = mapOf("alice" to ownerRunToken)
            val langGraphAgent = deployAgent.copy(commands = listOf(Command.LangGraph(module = "m")))
            val statusService = mockk<LangGraphRunStatusService>(relaxed = true)
            every { statusService.identity(any()) } returns null
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        deployProcess(owner = "alice", status = ProcessStatus.FAILED),
                        agents = mapOf("deploy" to langGraphAgent, "other" to otherAgent),
                        langGraphRunStatusService = statusService,
                    ),
                )
            }

            val res = client.post("/api/v1/processes/42/resume") { header(HttpHeaders.Authorization, "Bearer owner-run-token") }
            assertEquals(HttpStatusCode.BadRequest, res.status)
            assertTrue(res.bodyAsText().contains("no LangGraph thread"))
        }

    @Test
    fun `resume on a FAILED process whose agent no longer defines a LangGraph command returns 400`() =
        testApplication {
            val tokens = mapOf("alice" to ownerRunToken)
            val statusService = mockk<LangGraphRunStatusService>(relaxed = true)
            every { statusService.identity(any()) } returns LangGraphRunIdentity(threadId = "t1", module = "m")
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        deployProcess(owner = "alice", status = ProcessStatus.FAILED),
                        langGraphRunStatusService = statusService,
                    ),
                )
            }

            val res = client.post("/api/v1/processes/42/resume") { header(HttpHeaders.Authorization, "Bearer owner-run-token") }
            assertEquals(HttpStatusCode.BadRequest, res.status)
            assertTrue(res.bodyAsText().contains("no longer defines a LangGraph command"))
        }

    @Test
    fun `resume on a FAILED process with a run-identity artifact starts a new process with the recover markers`() =
        testApplication {
            val tokens = mapOf("alice" to ownerRunToken)
            val langGraphAgent = deployAgent.copy(commands = listOf(Command.LangGraph(module = "m")))
            val statusService = mockk<LangGraphRunStatusService>(relaxed = true)
            every { statusService.identity(any()) } returns LangGraphRunIdentity(threadId = "t1", module = "m")
            val processFactory = mockk<ProcessFactory>(relaxed = true)
            val fakeProcess = mockk<Process>(relaxed = true)
            val paramsSlot = slot<Map<String, String>>()
            // Named matchers for every parameter: the real call supplies `owner` by name
            // (leaving commandIndices/invocationChain/ancestorLockKeys defaulted), and a
            // positional stub would otherwise bake the *stub's own* defaults in as literal
            // expected values -- silently mismatching the real (non-null) owner.
            every {
                processFactory.create(
                    agent = any(),
                    queryParams = capture(paramsSlot),
                    parentContext = any(),
                    commandIndices = any(),
                    invocationChain = any(),
                    ancestorLockKeys = any(),
                    owner = any(),
                )
            } returns fakeProcess
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(fakeProcess, any()) } returns ProcessCreationResult(processId = 100)
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        deployProcess(owner = "alice", status = ProcessStatus.FAILED),
                        agents = mapOf("deploy" to langGraphAgent, "other" to otherAgent),
                        langGraphRunStatusService = statusService,
                        processFactory = processFactory,
                        processExecutor = processExecutor,
                    ),
                )
            }

            val res = client.post("/api/v1/processes/42/resume") { header(HttpHeaders.Authorization, "Bearer owner-run-token") }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ExecuteResponse>(res.bodyAsText())
            assertEquals(100, body.processId)
            assertEquals("t1", paramsSlot.captured[EnvConstants.LG_THREAD])
            assertEquals("1", paramsSlot.captured[EnvConstants.LG_RECOVER])
        }

    // ---------- POST /questions/{id}/answer ----------

    private fun questionsServiceWith(question: QuestionsService.Question): QuestionsService =
        mockk(relaxed = true) {
            coEvery { this@mockk.get(question.id) } returns question
            coEvery { this@mockk.answer(question.id, any()) } returns
                question.copy(
                    status = QuestionsService.QuestionStatus.ANSWERED,
                    answer = "ok",
                    answeredAt = Clock.System.now().epochSeconds,
                )
        }

    private fun openQuestion() =
        QuestionsService.Question(
            id = "q1",
            processId = 42,
            agentName = "deploy",
            text = "Proceed?",
            status = QuestionsService.QuestionStatus.OPEN,
            createdAt = Clock.System.now().epochSeconds,
        )

    @Test
    fun `answer control-granted principal can answer`() =
        testApplication {
            val question = openQuestion()
            val tokens = mapOf("controller" to controlToken)
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice"), questionsService = questionsServiceWith(question)),
                )
            }

            val res =
                client.post("/api/v1/questions/${question.id}/answer") {
                    header(HttpHeaders.Authorization, "Bearer control-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"answer":"go"}""")
                }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `answer foreign principal gets 404`() =
        testApplication {
            val question = openQuestion()
            val tokens = mapOf("mallory" to foreignToken)
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice"), questionsService = questionsServiceWith(question)),
                )
            }

            val res =
                client.post("/api/v1/questions/${question.id}/answer") {
                    header(HttpHeaders.Authorization, "Bearer foreign-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"answer":"go"}""")
                }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    // ---------- GET /status/all (Task 7: list visibility) ----------

    @Test
    fun `status-all scoped principal computes visibleOwner and the resolved visibleAgents set`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery {
                pm.getAllProcesses(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns
                ProcessManager.ProcessListResult(processes = emptyList(), total = 0)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res = client.get("/api/v1/status/all") { header(HttpHeaders.Authorization, "Bearer read-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            coVerify {
                pm.getAllProcesses(
                    filterStatuses = null,
                    orderAsc = false,
                    offset = 0,
                    limit = 100,
                    agentName = null,
                    visibleOwner = "reader",
                    visibleAgents = setOf("deploy"),
                    parentProcessId = null,
                    rootProcessId = null,
                    rootsOnly = false,
                    tags = emptyList(),
                    screenTags = any(),
                    canSeeRoot = any(),
                )
            }
        }

    @Test
    fun `status-all admin principal gets visibleAgents null (no filter)`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery {
                pm.getAllProcesses(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns
                ProcessManager.ProcessListResult(processes = emptyList(), total = 0)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res = client.get("/api/v1/status/all") { header(HttpHeaders.Authorization, "Bearer admin-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            coVerify {
                pm.getAllProcesses(
                    filterStatuses = null,
                    orderAsc = false,
                    offset = 0,
                    limit = 100,
                    agentName = null,
                    visibleOwner = "root",
                    visibleAgents = null,
                    parentProcessId = null,
                    rootProcessId = null,
                    rootsOnly = false,
                    tags = emptyList(),
                    screenTags = any(),
                    canSeeRoot = any(),
                )
            }
        }

    @Test
    fun `status-all tag filter does not reveal a screened secret-derived tag`() =
        testApplication {
            // End-to-end denial path for the tag-filter oracle (CWE-203): a run stores a tag
            // derived from an input that is secret at run time. `?tag=<secret value>`
            // must return total=0 / empty even though the row exists and matches in SQL —
            // otherwise `total` is an oracle for the guessed secret. A sibling run carries
            // a non-secret tag with the same key and is still returned for its own value.
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            val secretValue = "supersecretvalue"
            // Owned by the reader so visibility never hides it — the screen is the only
            // thing that can drop it, isolating the fix under test.
            pm.obtainProcessId(
                "deploy",
                inputParams = mapOf("token" to secretValue),
                secretInputKeys = setOf("token"),
                owner = "reader",
                tags = listOf("token:$secretValue"),
            )
            val sibling =
                pm.obtainProcessId(
                    "deploy",
                    owner = "reader",
                    tags = listOf("token:publicvalue"),
                )
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            // Probing with the secret-derived value returns nothing: the oracle is closed.
            val denied =
                client.get("/api/v1/status/all?tag=token:$secretValue") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }
            assertEquals(HttpStatusCode.OK, denied.status)
            val deniedBody = apiJson.decodeFromString<ProcessListResponse>(denied.bodyAsText())
            assertEquals(0, deniedBody.total)
            assertTrue(deniedBody.processes.isEmpty(), "screened secret-derived tag must not be matchable: ${denied.bodyAsText()}")

            // A non-secret tag on a sibling run still filters correctly (AC #3).
            val allowed =
                client.get("/api/v1/status/all?tag=token:publicvalue") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }
            assertEquals(HttpStatusCode.OK, allowed.status)
            val allowedBody = apiJson.decodeFromString<ProcessListResponse>(allowed.bodyAsText())
            assertEquals(1, allowedBody.total)
            assertEquals(listOf(sibling), allowedBody.processes.map { it.id })
        }

    @Test
    fun `status-all rootProcessId omits a descendant owned by another principal`() =
        testApplication {
            // The flow filter runs inside the same visibility union as every other
            // predicate (ADR-0037): asking for root N's flow must not hand a caller the
            // segments of it that belong to somebody else.
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            // The foreign segment runs a different agent: the reader's grant is on
            // "deploy" alone, so an alice-owned *deploy* run would still be visible
            // through the read-grant branch of the union and prove nothing.
            val root = pm.obtainProcessId("deploy", owner = "reader")
            val mine = pm.obtainProcessId("deploy", owner = "reader", parentProcessId = root, rootProcessId = root)
            pm.obtainProcessId("other", owner = "alice", parentProcessId = root, rootProcessId = root)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/all?rootProcessId=$root") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText())
            assertEquals(listOf(mine), body.processes.map { it.id })
            assertEquals(1, body.total)
        }

    @Test
    fun `status-all with a rootProcessId the caller cannot see returns an empty list`() =
        testApplication {
            // A root owned by another principal must read exactly like an id that does
            // not exist — an error or a non-empty list would leak its existence (§12).
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            // Same reason as above: a foreign flow has to run an agent the caller
            // holds no grant on, or the read-grant branch would expose it.
            val foreignRoot = pm.obtainProcessId("other", owner = "alice")
            pm.obtainProcessId("other", owner = "alice", parentProcessId = foreignRoot, rootProcessId = foreignRoot)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/all?rootProcessId=$foreignRoot") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText())
            assertTrue(body.processes.isEmpty(), "an invisible root must not leak its flow: ${res.bodyAsText()}")
            assertEquals(0, body.total)
        }

    @Test
    fun `status-all rootProcessId filter is gated on the requested root's visibility`() =
        testApplication {
            // [information_disclosure / CWE-200] Existence-oracle denial: a caller who can
            // see one descendant of a hidden flow must not be able to test guessed root
            // ids by filtering on them. The filter is honoured only when the root row
            // exists AND is visible under the union rule, so a foreign id reads exactly
            // like an empty flow.
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            // The hidden flow: alice's root on an agent the reader holds no grant on, with
            // one deploy-agent descendant — visible on its own grant, so the reader does
            // hold a toehold in a flow whose root they cannot see.
            val foreignRoot = pm.obtainProcessId("other", owner = "alice")
            val toehold = pm.obtainProcessId("deploy", owner = "alice", parentProcessId = foreignRoot, rootProcessId = foreignRoot)

            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            // Guessing the hidden root id by filter yields an empty page, not the flow.
            val guessed =
                client.get("/api/v1/status/all?rootProcessId=$foreignRoot") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }
            assertEquals(HttpStatusCode.OK, guessed.status)
            val guessedBody = apiJson.decodeFromString<ProcessListResponse>(guessed.bodyAsText())
            assertTrue(guessedBody.processes.isEmpty(), "an invisible root must not leak its flow: ${guessed.bodyAsText()}")
            assertEquals(0, guessedBody.total)

            // The toehold row is still listable unfiltered — the denial is the filter
            // gate, not the row's own visibility.
            val unfiltered =
                client.get("/api/v1/status/all?order=asc") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }
            assertEquals(listOf(toehold), apiJson.decodeFromString<ProcessListResponse>(unfiltered.bodyAsText()).processes.map { it.id })
        }

    @Test
    fun `status-all roots filter returns one row per visible flow`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            val root = pm.obtainProcessId("deploy", owner = "reader")
            pm.obtainProcessId("deploy", owner = "reader", parentProcessId = root, rootProcessId = root)
            val foreignRoot = pm.obtainProcessId("other", owner = "alice")
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/all?roots=true") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText())
            assertEquals(listOf(root), body.processes.map { it.id })
            assertEquals(1, body.total)
            assertTrue(foreignRoot !in body.processes.map { it.id })
        }

    @Test
    fun `status-all serves rootProcessId on each row, null at a root`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            val root = pm.obtainProcessId("deploy", owner = "reader")
            pm.obtainProcessId("deploy", owner = "reader", parentProcessId = root, rootProcessId = root)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/all?order=asc") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText())
            val byId = body.processes.associateBy { it.id }
            assertEquals(null, byId.getValue(root).rootProcessId)
            assertEquals(root, byId.values.first { it.id != root }.rootProcessId)
        }

    @Test
    fun `status-all nulls rootProcessId on a visible row whose flow root the caller cannot see`() =
        testApplication {
            // CWE-200 denial path: the row's visibility check passes (a READ grant on
            // its agent), but the rootProcessId it carries names a run owned by another
            // principal on an agent the caller holds no grant on. Emitting the id would
            // leak a foreign flow root's existence, so it must read as unknown (null) —
            // the same value an evicted root already produces.
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            val foreignRoot = pm.obtainProcessId("other", owner = "alice")
            // The visible row runs "deploy", the agent the reader's grant covers, while
            // the root runs "other" — the read-grant branch cannot see the root.
            pm.obtainProcessId("deploy", owner = "alice", parentProcessId = foreignRoot, rootProcessId = foreignRoot)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/all?order=asc") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText())
            assertEquals(1, body.total)
            val row = body.processes.single()
            assertEquals(null, row.rootProcessId, "a foreign flow root must not leak its id: ${res.bodyAsText()}")
        }

    @Test
    fun `status nulls rootProcessId when the flow root is not visible to the caller`() =
        testApplication {
            // CWE-200 denial path for GET /status/{id}: the reader may see the
            // descendant (READ grant on its agent) but not the foreign root the row
            // points at, so the root id is nulled server-side.
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            val foreignRoot = pm.obtainProcessId("other", owner = "alice")
            val mine = pm.obtainProcessId("deploy", owner = "alice", parentProcessId = foreignRoot, rootProcessId = foreignRoot)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/$mine") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessInfoResponse>(res.bodyAsText())
            assertEquals(null, body.rootProcessId, "a foreign flow root must not leak its id: ${res.bodyAsText()}")
        }

    @Test
    fun `status-all nulls rootProcessId when the flow root row is gone`() =
        testApplication {
            // The fail-closed twin of the foreign-root denial above: an evicted or
            // record-purged root row is indistinguishable from an invisible one — both
            // null the id, the documented "flow root unknown" meaning (ADR-0058). Pinned
            // so the two read-path gates cannot drift apart on a missing row.
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            // Root 9999 was never inserted: getProcessInfo finds no row for it.
            pm.obtainProcessId("deploy", owner = "reader", parentProcessId = 9999, rootProcessId = 9999)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/all?order=asc") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText())
            assertEquals(1, body.total)
            assertEquals(null, body.processes.single().rootProcessId, "a missing root row must read as unknown: ${res.bodyAsText()}")
        }

    @Test
    fun `status-all reads the page, its open questions and its root visibility in one read transaction`() =
        testApplication {
            // The list, the open-question counts and the root-visibility reads share one
            // snapshot and one reader transaction, with the ADR-0058 root gate unchanged.
            val tokens = mapOf("reader" to readToken)
            val db = PersistenceTestSupport.tempDatabase()
            val pm = PersistenceTestSupport.processManager(db = db)
            val questionsService = PersistenceTestSupport.questionsService(db = db)
            val root = pm.obtainProcessId("deploy", owner = "reader")
            val mine = pm.obtainProcessId("deploy", owner = "reader", parentProcessId = root, rootProcessId = root)
            val foreignRoot = pm.obtainProcessId("other", owner = "alice")
            val toehold = pm.obtainProcessId("deploy", owner = "alice", parentProcessId = foreignRoot, rootProcessId = foreignRoot)
            questionsService.registerProcess(mine, "deploy")
            questionsService.ask(mine, "go?")
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            suspend fun list(query: String): ProcessListResponse {
                SqlStatementRecorder.reset()
                val res = client.get("/api/v1/status/all$query") { header(HttpHeaders.Authorization, "Bearer read-token") }
                assertEquals(HttpStatusCode.OK, res.status)
                assertEquals(1, SqlStatementRecorder.transactionCount(), "$query ran: ${SqlStatementRecorder.statements()}")
                return apiJson.decodeFromString<ProcessListResponse>(res.bodyAsText())
            }

            val all = list("?order=asc").processes.associateBy { it.id }
            assertEquals(listOf(root, mine, toehold), all.keys.toList())
            assertEquals(1, all.getValue(mine).openQuestions)
            assertEquals(root, all.getValue(mine).rootProcessId)
            assertEquals(null, all.getValue(toehold).rootProcessId, "a foreign flow root must not leak its id")

            val flow = list("?order=asc&rootProcessId=$root")
            assertEquals(listOf(mine), flow.processes.map { it.id })
            assertEquals(1, flow.processes.single().openQuestions)

            val guessed = list("?rootProcessId=$foreignRoot")
            assertTrue(guessed.processes.isEmpty())
            assertEquals(0, guessed.total)
        }

    @Test
    fun `status nulls rootProcessId when the flow root row is gone`() =
        testApplication {
            // Same fail-closed case on GET /status/{id}: the row itself is visible, but
            // the root row it points at no longer exists, so the id reads as unknown
            // rather than guessing (§12: fail closed).
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            val mine = pm.obtainProcessId("deploy", owner = "reader", parentProcessId = 9999, rootProcessId = 9999)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/$mine") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessInfoResponse>(res.bodyAsText())
            assertEquals(null, body.rootProcessId, "a missing root row must read as unknown: ${res.bodyAsText()}")
        }

    @Test
    fun `status keeps rootProcessId when the caller can see the flow root`() =
        testApplication {
            // The symmetric case: the root runs the granted agent, so the caller can see
            // it and the id is served unchanged.
            val tokens = mapOf("reader" to readToken)
            val pm = PersistenceTestSupport.processManager()
            val root = pm.obtainProcessId("deploy", owner = "reader")
            val mine = pm.obtainProcessId("deploy", owner = "reader", parentProcessId = root, rootProcessId = root)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            coEvery { questionsService.openCountByProcess() } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, questionsService = questionsService),
                )
            }

            val res =
                client.get("/api/v1/status/$mine") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = apiJson.decodeFromString<ProcessInfoResponse>(res.bodyAsText())
            assertEquals(root, body.rootProcessId)
        }

    // ---------- GET /questions (Task 7: filter by asking-process visibility) ----------

    @Test
    fun `questions list filters by visibility in SQL, so neither the page nor total leaks a hidden question`() =
        testApplication {
            // A question is visible through the asking process's owner, or a read grant on its agent
            // (a dispatch-bound question outlives its process, so the agent arm reads the question).
            val tokens = mapOf("reader" to readToken)
            val db = PersistenceTestSupport.tempDatabase()
            val pm = PersistenceTestSupport.processManager(db = db)
            val questionsService = PersistenceTestSupport.questionsService(db = db)

            suspend fun ask(
                agent: String,
                owner: String?,
            ): String {
                val id = pm.obtainProcessId(agent, owner = owner)
                questionsService.registerProcess(id, agent)
                return questionsService.ask(id, "Proceed?").id
            }
            val readable = ask("deploy", owner = null)
            val owned = ask("other", owner = "reader")
            ask("other", owner = "mallory")
            ask("other", owner = null)
            val dispatchService = mockk<DispatchService>(relaxed = true)
            coEvery { dispatchService.findByQuestionIds(any()) } returns emptyMap()
            application {
                installGrantsApp(
                    tokens,
                    deps(
                        authorizerFor(tokens),
                        processManagerOverride = pm,
                        questionsService = questionsService,
                        dispatchService = dispatchService,
                    ),
                )
            }

            suspend fun list(query: String): QuestionsListResponse {
                val res = client.get("/api/v1/questions$query") { header(HttpHeaders.Authorization, "Bearer read-token") }
                assertEquals(HttpStatusCode.OK, res.status)
                return apiJson.decodeFromString<QuestionsListResponse>(res.bodyAsText())
            }

            val all = list("")
            assertEquals(listOf(readable, owned), all.questions.map { it.id })
            assertEquals(2, all.total)
            assertEquals(100, all.limit)
            val second = list("?limit=1&offset=1")
            assertEquals(listOf(owned), second.questions.map { it.id })
            assertEquals(2, second.total, "total counts only visible questions")
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .get("/api/v1/questions?limit=0") {
                        header(HttpHeaders.Authorization, "Bearer read-token")
                    }.status,
            )
        }

    // ---------- GET /dispatches, GET /continuations (Task 7: filter by target-agent visibility) ----------

    private fun dispatch(
        id: Int,
        agentName: String,
        owner: String?,
    ) = Dispatch(
        id = id,
        questionId = "q-$id",
        status = DispatchStatus.PENDING,
        agentName = agentName,
        answerParam = "answer",
        params = emptyMap(),
        originProcessId = 1,
        originAgentName = "origin",
        expiresAt = null,
        createdAt = 0L,
        owner = owner,
    )

    private fun continuation(
        id: Int,
        targetAgentName: String,
        owner: String?,
    ) = Continuation(
        id = id,
        watchedProcessId = 1,
        status = ContinuationStatus.PENDING,
        targetAgentName = targetAgentName,
        params = emptyMap(),
        originProcessId = 1,
        originAgentName = "origin",
        expiresAt = null,
        createdAt = 0L,
        owner = owner,
    )

    @Test
    fun `dispatches list filters by visibility in SQL, so neither the page nor total leaks a hidden row`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val db = PersistenceTestSupport.tempDatabase()
            val repo = DispatchRepository(db)

            suspend fun seed(
                id: Int,
                agentName: String,
                owner: String?,
            ): Int {
                val q =
                    QuestionsService.Question(
                        "q-$id",
                        1,
                        "origin",
                        "t",
                        status = QuestionsService.QuestionStatus.OPEN,
                        createdAt = 0,
                        dispatchBound = true,
                    )
                return repo.create(q, dispatch(id, agentName, owner), maxPending = 100)!!
            }
            val granted = seed(1, agentName = "deploy", owner = null)
            val owned = seed(2, agentName = "other", owner = "reader")
            // Newest: a filter applied after the limit would put it on the first page or in the total.
            seed(3, agentName = "other", owner = "mallory")
            val dispatchService =
                PersistenceTestSupport.dispatchService(
                    db = db,
                    questionsService = PersistenceTestSupport.questionsService(db = db),
                    agentsService = mockk(relaxed = true),
                    runStarter = mockk(relaxed = true),
                    inputResolver = mockk(relaxed = true),
                )
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), dispatchService = dispatchService))
            }

            val first = client.get("/api/v1/dispatches?limit=1") { header(HttpHeaders.Authorization, "Bearer read-token") }
            assertEquals(HttpStatusCode.OK, first.status)
            val firstPage = apiJson.decodeFromString<DispatchListResponse>(first.bodyAsText())
            assertEquals(listOf(owned), firstPage.dispatches.map { it.id })
            assertEquals(2, firstPage.total)
            val all = client.get("/api/v1/dispatches") { header(HttpHeaders.Authorization, "Bearer read-token") }
            val allPage = apiJson.decodeFromString<DispatchListResponse>(all.bodyAsText())
            assertEquals(listOf(owned, granted), allPage.dispatches.map { it.id })
            assertEquals(2, allPage.total)
        }

    @Test
    fun `continuations list filters by visibility in SQL, so neither the page nor total leaks a hidden row`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val db = PersistenceTestSupport.tempDatabase()
            val repo = ContinuationRepository(db)
            val granted = repo.create(continuation(0, targetAgentName = "deploy", owner = null), maxPending = 100)!!
            val owned = repo.create(continuation(0, targetAgentName = "other", owner = "reader"), maxPending = 100)!!
            // Newest: a filter applied after the limit would put it on the first page or in the total.
            repo.create(continuation(0, targetAgentName = "other", owner = "mallory"), maxPending = 100)
            val continuationService =
                PersistenceTestSupport.continuationService(
                    db = db,
                    questionsService = PersistenceTestSupport.questionsService(db = db),
                    agentsService = mockk(relaxed = true),
                    runStarter = mockk(relaxed = true),
                    inputResolver = mockk(relaxed = true),
                    processManager = mockk(relaxed = true),
                )
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), continuationService = continuationService))
            }

            val first = client.get("/api/v1/continuations?limit=1") { header(HttpHeaders.Authorization, "Bearer read-token") }
            assertEquals(HttpStatusCode.OK, first.status)
            val firstPage = apiJson.decodeFromString<ContinuationListResponse>(first.bodyAsText())
            assertEquals(listOf(owned), firstPage.continuations.map { it.id })
            assertEquals(2, firstPage.total)
            val all = client.get("/api/v1/continuations") { header(HttpHeaders.Authorization, "Bearer read-token") }
            val allPage = apiJson.decodeFromString<ContinuationListResponse>(all.bodyAsText())
            assertEquals(listOf(owned, granted), allPage.continuations.map { it.id })
            assertEquals(2, allPage.total)
        }

    // A prefix grant resolves against the loaded agents only (as on /status/all): "dep*" sees the
    // loaded "deploy", never "other", and not "depot", which matches the pattern but is not loaded.
    private val prefixReadToken = TokenConfig(token = "prefix-token", grants = listOf(Grant(listOf("dep*"), listOf(Action.READ))))

    @Test
    fun `dispatches list under a prefix READ grant shows only loaded matching agents, in the page and total`() =
        testApplication {
            val tokens = mapOf("prefixer" to prefixReadToken)
            val db = PersistenceTestSupport.tempDatabase()
            val repo = DispatchRepository(db)

            suspend fun seed(
                id: Int,
                agentName: String,
            ): Int {
                val q =
                    QuestionsService.Question(
                        "q-$id",
                        1,
                        "origin",
                        "t",
                        status = QuestionsService.QuestionStatus.OPEN,
                        createdAt = 0,
                        dispatchBound = true,
                    )
                return repo.create(q, dispatch(id, agentName, owner = "mallory"), maxPending = 100)!!
            }
            val granted = seed(1, "deploy")
            seed(2, "other")
            seed(3, "depot")
            val dispatchService =
                PersistenceTestSupport.dispatchService(
                    db = db,
                    questionsService = PersistenceTestSupport.questionsService(db = db),
                    agentsService = mockk(relaxed = true),
                    runStarter = mockk(relaxed = true),
                    inputResolver = mockk(relaxed = true),
                )
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), dispatchService = dispatchService))
            }

            val res = client.get("/api/v1/dispatches?limit=1") { header(HttpHeaders.Authorization, "Bearer prefix-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            val page = apiJson.decodeFromString<DispatchListResponse>(res.bodyAsText())
            assertEquals(listOf(granted), page.dispatches.map { it.id })
            assertEquals(1, page.total)
        }

    @Test
    fun `continuations list under a prefix READ grant shows only loaded matching agents, in the page and total`() =
        testApplication {
            val tokens = mapOf("prefixer" to prefixReadToken)
            val db = PersistenceTestSupport.tempDatabase()
            val repo = ContinuationRepository(db)
            val granted = repo.create(continuation(0, targetAgentName = "deploy", owner = "mallory"), maxPending = 100)!!
            repo.create(continuation(0, targetAgentName = "other", owner = "mallory"), maxPending = 100)
            repo.create(continuation(0, targetAgentName = "depot", owner = "mallory"), maxPending = 100)
            val continuationService =
                PersistenceTestSupport.continuationService(
                    db = db,
                    questionsService = PersistenceTestSupport.questionsService(db = db),
                    agentsService = mockk(relaxed = true),
                    runStarter = mockk(relaxed = true),
                    inputResolver = mockk(relaxed = true),
                    processManager = mockk(relaxed = true),
                )
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), continuationService = continuationService))
            }

            val res = client.get("/api/v1/continuations?limit=1") { header(HttpHeaders.Authorization, "Bearer prefix-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            val page = apiJson.decodeFromString<ContinuationListResponse>(res.bodyAsText())
            assertEquals(listOf(granted), page.continuations.map { it.id })
            assertEquals(1, page.total)
        }

    // ---------- GET/DELETE /dispatches/{id}, /continuations/{id} (Task 7 Plan 3: single-resource visibility) ----------

    @Test
    fun `GET dispatch by id 404s for a principal who cannot see it`() =
        testApplication {
            val tokens = mapOf("foreign" to foreignToken)
            val dispatchService = mockk<DispatchService>(relaxed = true)
            coEvery { dispatchService.get(1) } returns dispatch(id = 1, owner = "alice", agentName = "deploy")
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), dispatchService = dispatchService))
            }

            val res = client.get("/api/v1/dispatches/1") { header(HttpHeaders.Authorization, "Bearer foreign-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `GET dispatch by id succeeds for the owner`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            val dispatchService = mockk<DispatchService>(relaxed = true)
            coEvery { dispatchService.get(1) } returns dispatch(id = 1, owner = "alice", agentName = "deploy")
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), dispatchService = dispatchService))
            }

            val res = client.get("/api/v1/dispatches/1") { header(HttpHeaders.Authorization, "Bearer owner-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `DELETE dispatch by id 404s without control`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val dispatchService = mockk<DispatchService>(relaxed = true)
            coEvery { dispatchService.get(1) } returns dispatch(id = 1, owner = "alice", agentName = "deploy")
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), dispatchService = dispatchService))
            }

            val res = client.delete("/api/v1/dispatches/1") { header(HttpHeaders.Authorization, "Bearer read-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
            coVerify(exactly = 0) { dispatchService.cancel(1) }
        }

    @Test
    fun `GET continuation by id 404s for a principal who cannot see it`() =
        testApplication {
            val tokens = mapOf("foreign" to foreignToken)
            val continuationService = mockk<ContinuationService>(relaxed = true)
            coEvery { continuationService.get(1) } returns continuation(id = 1, owner = "alice", targetAgentName = "deploy")
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), continuationService = continuationService))
            }

            val res = client.get("/api/v1/continuations/1") { header(HttpHeaders.Authorization, "Bearer foreign-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `GET continuation by id succeeds for the owner`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            val continuationService = mockk<ContinuationService>(relaxed = true)
            coEvery { continuationService.get(1) } returns continuation(id = 1, owner = "alice", targetAgentName = "deploy")
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), continuationService = continuationService))
            }

            val res = client.get("/api/v1/continuations/1") { header(HttpHeaders.Authorization, "Bearer owner-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `DELETE continuation by id 404s without control`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            val continuationService = mockk<ContinuationService>(relaxed = true)
            coEvery { continuationService.get(1) } returns continuation(id = 1, owner = "alice", targetAgentName = "deploy")
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), continuationService = continuationService))
            }

            val res = client.delete("/api/v1/continuations/1") { header(HttpHeaders.Authorization, "Bearer read-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
            coVerify(exactly = 0) { continuationService.cancel(1) }
        }

    // ---------- SSE /stream/processes (Task 7: per-connection visibility filter) ----------

    @Test
    fun `SSE stream processes forwards events only for visible processes`() =
        testApplication {
            val broadcaster = ProcessEventBroadcaster()
            val tokens = mapOf("reader" to readToken)
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery { pm.getProcessInfo(1) } returns
                ProcessInfo(id = 1, status = ProcessStatus.WORKING, agentName = "deploy", owner = null)
            coEvery { pm.getProcessInfo(2) } returns
                ProcessInfo(id = 2, status = ProcessStatus.WORKING, agentName = "other", owner = "mallory")
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), processManagerOverride = pm, processEventBroadcaster = broadcaster),
                )
            }

            client
                .prepareGet("/api/v1/stream/processes") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }.execute { response ->
                    coroutineScope {
                        val trigger =
                            launch {
                                delay(300)
                                broadcaster.emit(
                                    ProcessEvent.StatusChanged(
                                        processId = 1,
                                        status = ProcessStatus.WORKING,
                                        agentName = "deploy",
                                    ),
                                )
                                broadcaster.emit(
                                    ProcessEvent.StatusChanged(
                                        processId = 2,
                                        status = ProcessStatus.WORKING,
                                        agentName = "other",
                                    ),
                                )
                                // Sentinel on the visible process so the reader loop below can stop deterministically.
                                broadcaster.emit(
                                    ProcessEvent.QuestionAsked(processId = 1, questionId = "done-marker", agentName = "deploy"),
                                )
                            }
                        val channel = response.bodyAsChannel()
                        val dataLines = mutableListOf<String>()
                        withTimeout(10_000) {
                            while (true) {
                                val line = channel.readLine() ?: break
                                if (line.startsWith("data: ")) dataLines += line
                                if ("done-marker" in line) break
                            }
                        }
                        trigger.join()
                        assertTrue(dataLines.any { "\"processId\":1" in it }, "expected pid 1 event in $dataLines")
                        assertTrue(dataLines.none { "\"processId\":2" in it }, "pid 2 event must be filtered out: $dataLines")
                    }
                }
        }

    // ---------- SSE /stream/agents (agent-reload diff filtered by grant) ----------

    private suspend fun firstAgentReloadDataLine(
        broadcaster: ProcessEventBroadcaster,
        response: io.ktor.client.statement.HttpResponse,
        added: List<String> = listOf("deploy", "other"),
    ): String? =
        coroutineScope {
            val trigger =
                launch {
                    delay(300)
                    broadcaster.emit(
                        ProcessEvent.AgentsReloaded(
                            added = added,
                            updated = emptyList(),
                            removed = emptyList(),
                        ),
                    )
                }
            val channel = response.bodyAsChannel()
            var dataLine: String? = null
            withTimeout(10_000) {
                while (true) {
                    val line = channel.readLine() ?: break
                    // Only the reload frame carries a "data:" payload; pings are ": ping".
                    if (line.startsWith("data: ")) {
                        dataLine = line
                        break
                    }
                }
            }
            trigger.join()
            dataLine
        }

    @Test
    fun `SSE agents reload frame lists only agents the principal has a grant on`() =
        testApplication {
            val broadcaster = ProcessEventBroadcaster()
            val tokens = mapOf("reader" to readToken)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), processEventBroadcaster = broadcaster))
            }

            client
                .prepareGet("/api/v1/stream/agents") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }.execute { response ->
                    val dataLine = firstAgentReloadDataLine(broadcaster, response)
                    assertTrue(dataLine != null && "deploy" in dataLine, "expected deploy in $dataLine")
                    assertTrue("other" !in dataLine, "other must be filtered out: $dataLine")
                }
        }

    @Test
    fun `SSE agents reload frame is still sent when the filter empties the diff`() =
        testApplication {
            val broadcaster = ProcessEventBroadcaster()
            val tokens = mapOf("reader" to readToken)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), processEventBroadcaster = broadcaster))
            }

            client
                .prepareGet("/api/v1/stream/agents") {
                    header(HttpHeaders.Authorization, "Bearer read-token")
                }.execute { response ->
                    // Reload touches only a non-granted agent: the frame must still
                    // arrive (it is the reload-completed signal) with empty lists —
                    // suppressing it would both break consumers that await the event
                    // and let the silence reveal that some hidden agent changed.
                    val dataLine = firstAgentReloadDataLine(broadcaster, response, added = listOf("other"))
                    assertTrue(dataLine != null, "reload frame must be sent even when the filtered diff is empty")
                    assertTrue("other" !in dataLine, "other must be filtered out: $dataLine")
                    assertTrue("\"added\":[]" in dataLine.replace(" ", ""), "expected empty added list: $dataLine")
                }
        }

    @Test
    fun `SSE agents reload frame shows all agents to an admin`() =
        testApplication {
            val broadcaster = ProcessEventBroadcaster()
            val tokens = mapOf("root" to adminToken)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), processEventBroadcaster = broadcaster))
            }

            client
                .prepareGet("/api/v1/stream/agents") {
                    header(HttpHeaders.Authorization, "Bearer admin-token")
                }.execute { response ->
                    val dataLine = firstAgentReloadDataLine(broadcaster, response)
                    assertTrue(dataLine != null && "deploy" in dataLine && "other" in dataLine, "admin must see all: $dataLine")
                }
        }

    // ---------- SSE /stream/processes/{id}/logs and /stream/processes/{id}/llm-events (single-process gate) ----------

    @Test
    fun `SSE process logs foreign principal gets 404 before the stream opens`() =
        testApplication {
            val tokens = mapOf("mallory" to foreignToken)
            val logTailService = mockk<LogTailService>(relaxed = true)
            coEvery { logTailService.resolve(any(), any(), any()) } returns Path("/tmp/cmd_0.out")
            every { logTailService.tail(any(), any(), any()) } returns emptyFlow()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice"), logTailService = logTailService),
                )
            }

            client
                .prepareGet("/api/v1/stream/processes/42/logs?cmd=0&stream=out") {
                    header(HttpHeaders.Authorization, "Bearer foreign-token")
                }.execute { response ->
                    assertEquals(HttpStatusCode.NotFound, response.status)
                }
        }

    @Test
    fun `SSE process logs owner opens the stream (not 404)`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            val logTailService = mockk<LogTailService>(relaxed = true)
            coEvery { logTailService.resolve(any(), any(), any()) } returns Path("/tmp/cmd_0.out")
            every { logTailService.tail(any(), any(), any()) } returns emptyFlow()
            application {
                installGrantsApp(
                    tokens,
                    deps(authorizerFor(tokens), deployProcess(owner = "alice"), logTailService = logTailService),
                )
            }

            client
                .prepareGet("/api/v1/stream/processes/42/logs?cmd=0&stream=out") {
                    header(HttpHeaders.Authorization, "Bearer owner-token")
                }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                }
        }

    @Test
    fun `SSE llm-events foreign principal gets 404 before the stream opens`() =
        testApplication {
            val tokens = mapOf("mallory" to foreignToken)
            val svc = mockk<LlmEventStreamService>(relaxed = true)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), llmEventStreamService = svc))
            }

            client
                .prepareGet("/api/v1/stream/processes/42/llm-events?cmd=0") {
                    header(HttpHeaders.Authorization, "Bearer foreign-token")
                }.execute { response ->
                    assertEquals(HttpStatusCode.NotFound, response.status)
                }
            coVerify(exactly = 0) { svc.resolve(any(), any()) }
            coVerify(exactly = 0) { svc.streamCommand(any(), any(), any()) }
            coVerify(exactly = 0) { svc.streamProcess(any(), any()) }
        }

    @Test
    fun `SSE llm-events foreign principal gets 404 in process mode too`() =
        testApplication {
            val tokens = mapOf("mallory" to foreignToken)
            val svc = mockk<LlmEventStreamService>(relaxed = true)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), llmEventStreamService = svc))
            }

            client
                .prepareGet("/api/v1/stream/processes/42/llm-events") {
                    header(HttpHeaders.Authorization, "Bearer foreign-token")
                }.execute { response ->
                    assertEquals(HttpStatusCode.NotFound, response.status)
                }
            coVerify(exactly = 0) { svc.streamProcess(any(), any()) }
        }

    @Test
    fun `SSE llm-events answers 404 for an unknown process`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            val svc = mockk<LlmEventStreamService>(relaxed = true)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), processInfo = null, llmEventStreamService = svc)) }

            for (query in listOf("?cmd=0", "")) {
                client
                    .prepareGet("/api/v1/stream/processes/42/llm-events$query") {
                        header(HttpHeaders.Authorization, "Bearer admin-token")
                    }.execute { response ->
                        assertEquals(HttpStatusCode.NotFound, response.status, "query=$query")
                    }
            }
            coVerify(exactly = 0) { svc.resolve(any(), any()) }
            coVerify(exactly = 0) { svc.streamProcess(any(), any()) }
        }

    @Test
    fun `SSE llm-events passes a four-part Last-Event-ID as the resume cursor and ignores any other form`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            val svc = mockk<LlmEventStreamService>(relaxed = true)
            coEvery { svc.resolve(42, 0) } returns LlmArtifact(Path("/tmp/o"), "codex", 0, SecretRedactor.NONE)
            every { svc.streamCommand(42, 0, any()) } returns flowOf(LlmStreamEvent.End)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), llmEventStreamService = svc))
            }

            for (lastId in listOf("0:0:17:1", "0:0:17")) {
                client
                    .prepareGet("/api/v1/stream/processes/42/llm-events?cmd=0") {
                        header(HttpHeaders.Authorization, "Bearer admin-token")
                        header("Last-Event-ID", lastId)
                    }.execute { response ->
                        val ch = response.bodyAsChannel()
                        withTimeout(5_000) {
                            var line = ch.readLine()
                            while (line != null && line != "event: llm.end") line = ch.readLine()
                        }
                    }
            }
            verify(exactly = 1) { svc.streamCommand(42, 0, StreamCursor(0, 0, 17, 1)) }
            verify(exactly = 1) { svc.streamCommand(42, 0, null) }
        }

    @Test
    fun `SSE llm-events answers 404 when cmd is not an LLM command`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            val svc = mockk<LlmEventStreamService>(relaxed = true)
            coEvery { svc.resolve(42, 0) } returns null
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), llmEventStreamService = svc))
            }

            client
                .prepareGet("/api/v1/stream/processes/42/llm-events?cmd=0") {
                    header(HttpHeaders.Authorization, "Bearer admin-token")
                }.execute { response ->
                    assertEquals(HttpStatusCode.NotFound, response.status)
                }
        }

    @Test
    fun `SSE llm-events rejects a malformed cmd with 400`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            for (cmd in listOf("-1", "x")) {
                client
                    .prepareGet("/api/v1/stream/processes/42/llm-events?cmd=$cmd") {
                        header(HttpHeaders.Authorization, "Bearer admin-token")
                    }.execute { response ->
                        assertEquals(HttpStatusCode.BadRequest, response.status, "cmd=$cmd")
                    }
            }
        }

    @Test
    fun `SSE llm-events owner opens the stream and receives frames with ids`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            val svc = mockk<LlmEventStreamService>(relaxed = true)
            coEvery { svc.resolve(42, 0) } returns LlmArtifact(Path("/tmp/o"), "codex", 0, SecretRedactor.NONE)
            every { svc.streamCommand(42, 0, any()) } returns
                flowOf(
                    LlmStreamEvent.Event(0, 0, 17, 1, LlmEvent.SessionStart("t", null)),
                    LlmStreamEvent.End,
                )
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), llmEventStreamService = svc))
            }

            client
                .prepareGet("/api/v1/stream/processes/42/llm-events?cmd=0") {
                    header(HttpHeaders.Authorization, "Bearer owner-token")
                }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val lines = mutableListOf<String>()
                    val ch = response.bodyAsChannel()
                    withTimeout(5_000) {
                        while (true) {
                            val l = ch.readLine() ?: break
                            lines += l
                            if (l == "event: llm.end") break
                        }
                    }
                    assertTrue(lines.contains("event: llm.event"), "$lines")
                    assertTrue(lines.contains("id: 0:0:17:1"), "$lines")
                    assertTrue(lines.any { it.startsWith("data: ") && "\"cmdIndex\":0" in it && "\"session_start\"" in it }, "$lines")
                }
        }

    @Test
    fun `SSE llm-events without cmd follows the whole process`() =
        testApplication {
            val tokens = mapOf("alice" to ownerToken)
            val svc = mockk<LlmEventStreamService>(relaxed = true)
            every { svc.streamProcess(42, any()) } returns flowOf(LlmStreamEvent.End)
            application {
                installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"), llmEventStreamService = svc))
            }

            client
                .prepareGet("/api/v1/stream/processes/42/llm-events") {
                    header(HttpHeaders.Authorization, "Bearer owner-token")
                }.execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val ch = response.bodyAsChannel()
                    var sawEnd = false
                    withTimeout(5_000) {
                        while (!sawEnd) sawEnd = (ch.readLine() ?: break) == "event: llm.end"
                    }
                    assertTrue(sawEnd)
                }
            coVerify(exactly = 0) { svc.resolve(any(), any()) }
        }

    @Test
    fun `SSE stream processes id is gone`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), deployProcess(owner = "alice"))) }

            client
                .prepareGet("/api/v1/stream/processes/42") {
                    header(HttpHeaders.Authorization, "Bearer admin-token")
                }.execute { response ->
                    assertEquals(HttpStatusCode.NotFound, response.status)
                }
        }
}
