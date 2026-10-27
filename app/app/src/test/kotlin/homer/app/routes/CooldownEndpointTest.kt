package homer.app.routes

import homer.app.apiJson
import homer.app.config.ExecutionConfig
import homer.app.configureStatusPages
import homer.app.executors.CommandExecutorFactory
import homer.app.executors.LockManager
import homer.app.executors.ParallelRunsManager
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.executors.RunMessageExpiry
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.infra.QuestionsWorkspaceSetup
import homer.app.infra.WorkspaceConfigFactory
import homer.app.models.Agent
import homer.app.models.Cooldown
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.services.Authorizer
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The denial test for the cooldown bound on the three manual-recovery endpoints
 * (root §12 rule 8): retry, resume and debug each get `429` inside the agent's
 * `minInterval` window, and the refused start launches nothing.
 *
 * Everything on the start path is **real** here — `ProcessFactory`,
 * `ProcessExecutor`, `ProcessManager` over a temp database — so the refusal comes
 * from the same INSERT transaction production uses
 * (`ProcessRepository.obtainProcessId`, ADR-0051) and the cooldown value it sees
 * is the one the handler actually passed (`Process.cooldown = agent.cooldown`,
 * `Process.kt:49`, fed by `Agent.withoutFailureBackoff()`). Only the workspace /
 * runner / telemetry collaborators are relaxed mocks; `create` is reached but
 * `Process.execute` never runs, because a refused start throws before the
 * coroutine is launched.
 *
 * "Launches nothing" is therefore asserted where it is observable: the process
 * table. A mocked `ProcessExecutor` could not show this — it is the INSERT inside
 * `execute` that has to be refused.
 *
 * The window is opened by the endpoint's *own* first start: the first request is
 * the `200` that proves admission, the immediate second one is the denial. Only
 * milliseconds pass between them, so a `1m` window cannot elapse mid-test — no
 * clock control, and no production signature change, is needed.
 */
class CooldownEndpointTest {
    /** Long enough that the second request is always inside the window. */
    private val oneMinute = Cooldown(minInterval = "1m")

    /** Only the failure backoff set — the window these three paths deliberately ignore. */
    private val failureOnly = Cooldown(afterFailure = "15m")

    private val debugBody = """{"commandIndex":0,"env":{},"queryParams":{},"headers":{}}"""

    /** A real start path over a fresh temp database, with the agent under test. */
    private inner class Deps(
        val agent: Agent,
        val source: ProcessInfo,
    ) {
        val processManager: ProcessManager = PersistenceTestSupport.processManager()

        val executor: ProcessExecutor =
            ProcessExecutor(
                telemetryService = TestFixtures.mockTelemetryService(),
                processManager = processManager,
                questionsService = mockk<QuestionsService>(relaxed = true),
                runMessages = mockk<RunMessageExpiry>(relaxed = true),
                coroutineScope = CoroutineScope(Dispatchers.Default + Job()),
                logger = TestFixtures.mockLogger(),
            )

        val processFactory: ProcessFactory =
            ProcessFactory(
                workspaceConfigFactory = mockk<WorkspaceConfigFactory>(relaxed = true),
                executorFactory = mockk<CommandExecutorFactory>(relaxed = true),
                telemetryService = TestFixtures.mockTelemetryService(),
                lockManager = mockk<LockManager>(relaxed = true),
                parallelRunsManager = mockk<ParallelRunsManager>(relaxed = true),
                config = ExecutionConfig(),
                processManager = processManager,
                runnerRegistry = mockk<RunnerRegistry>(relaxed = true),
                activeSessions = mockk<ActiveSessionsRegistry>(relaxed = true),
                questionsService = mockk<QuestionsService>(relaxed = true),
                questionsWorkspaceSetup = mockk<QuestionsWorkspaceSetup>(relaxed = true),
            )

        /**
         * Number of runs ever inserted — a refused start adds none. Counts from the
         * monotonic AUTOINCREMENT high-water (ids start at 1, -1 when empty), so it
         * sees a row the moment the INSERT transaction commits.
         */
        suspend fun processCount(): Int = processManager.lastProcessId()

        /**
         * Records a FAILED run of the subject agent, ending now, so the next start is
         * inside any `afterFailure` window. Without this the window is never open —
         * every run these tests actually launch ends `FINISHED` — and the admission
         * cases would pass whether or not the fix is present.
         */
        suspend fun seedFailedRun() {
            val id = processManager.obtainProcessId(agent.name, emptyMap())
            processManager.setProcessStatus(id, ProcessStatus.WORKING)
            processManager.setProcessStatus(id, ProcessStatus.FAILED)
        }
    }

    private fun scenario(
        agent: Agent,
        source: ProcessInfo,
        mount: Route.(Deps) -> Unit,
        requests: suspend ApplicationTestBuilder.(Deps) -> Unit,
    ) = testApplication {
        val deps = Deps(agent, source)
        application {
            install(ContentNegotiation) { json(apiJson) }
            configureStatusPages()
            routing { mount(deps) }
        }
        requests(deps)
    }

    /** A terminal source run for the replay endpoints. */
    private fun failedSource(agentName: String = "watcher") =
        ProcessInfo(
            id = 42,
            status = ProcessStatus.FAILED,
            agentName = agentName,
        )

    private fun Route.mountRetry(deps: Deps) =
        post("/api/v1/processes/{id}/retry") {
            handleRetry(
                serverId = "test-server",
                call = call,
                sourceProcess = deps.source,
                agent = deps.agent,
                authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                processManager = deps.processManager,
                processExecutor = deps.executor,
                processFactory = deps.processFactory,
                telemetryService = TestFixtures.mockTelemetryService(),
                costMeter = mockk(relaxed = true),
                agentInputResolver = AgentInputResolver(),
                maintenanceGate = mockk(relaxed = true),
            )
        }

    private fun Route.mountResume(deps: Deps) =
        post("/api/v1/processes/{id}/resume") {
            handleResume(
                serverId = "test-server",
                call = call,
                sourceProcess = deps.source,
                agent = deps.agent,
                threadId = "thread-1",
                authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                processManager = deps.processManager,
                processExecutor = deps.executor,
                processFactory = deps.processFactory,
                telemetryService = TestFixtures.mockTelemetryService(),
                costMeter = mockk(relaxed = true),
                agentInputResolver = AgentInputResolver(),
                maintenanceGate = mockk(relaxed = true),
            )
        }

    private fun Route.mountDebug(deps: Deps) =
        post("/api/v1/agents/{agentName}/debug") {
            handleAgentDebug(
                serverId = "test-server",
                call = call,
                agent = deps.agent,
                agentInputResolver = AgentInputResolver(),
                processExecutor = deps.executor,
                processFactory = deps.processFactory,
                telemetryService = TestFixtures.mockTelemetryService(),
                maintenanceGate = mockk(relaxed = true),
            )
        }

    private suspend fun ApplicationTestBuilder.postRetry() = client.post("/api/v1/processes/42/retry")

    private suspend fun ApplicationTestBuilder.postResume() = client.post("/api/v1/processes/42/resume")

    private suspend fun ApplicationTestBuilder.postDebug() =
        client.post("/api/v1/agents/watcher/debug") {
            contentType(ContentType.Application.Json)
            setBody(debugBody)
        }

    // ===== minInterval is honoured: refused with 429, nothing launched =====

    @Test
    fun `retry inside the minInterval window is refused with 429 and launches nothing`() =
        scenario(
            agent = AgentFixtures.simpleAgent(name = "watcher").copy(cooldown = oneMinute),
            source = failedSource(),
            mount = { deps -> mountRetry(deps) },
        ) { deps ->
            assertEquals(HttpStatusCode.OK, postRetry().status, "the first retry opens the window")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                postRetry().status,
                "a retry inside minInterval must be refused",
            )
            assertEquals(1, deps.processCount(), "the refused retry must not create a run")
        }

    @Test
    fun `resume inside the minInterval window is refused with 429 and launches nothing`() =
        scenario(
            agent = AgentFixtures.simpleAgent(name = "watcher").copy(cooldown = oneMinute),
            source = failedSource(),
            mount = { deps -> mountResume(deps) },
        ) { deps ->
            assertEquals(HttpStatusCode.OK, postResume().status, "the first resume opens the window")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                postResume().status,
                "a resume inside minInterval must be refused",
            )
            assertEquals(1, deps.processCount(), "the refused resume must not create a run")
        }

    @Test
    fun `debug inside the minInterval window is refused with 429 and launches nothing`() =
        scenario(
            agent = AgentFixtures.simpleAgent(name = "watcher").copy(cooldown = oneMinute),
            source = failedSource(),
            mount = { deps -> mountDebug(deps) },
        ) { deps ->
            assertEquals(HttpStatusCode.OK, postDebug().status, "the first debug opens the window")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                postDebug().status,
                "a debug inside minInterval must be refused",
            )
            assertEquals(1, deps.processCount(), "the refused debug must not create a run")
        }

    // ===== afterFailure is ignored: the start is admitted =====

    @Test
    fun `retry is admitted despite a previous failure inside the afterFailure window`() =
        scenario(
            agent = AgentFixtures.simpleAgent(name = "watcher").copy(cooldown = failureOnly),
            source = failedSource(),
            mount = { deps -> mountRetry(deps) },
        ) { deps ->
            deps.seedFailedRun()
            assertEquals(HttpStatusCode.OK, postRetry().status)
            assertEquals(
                HttpStatusCode.OK,
                postRetry().status,
                "a manual retry is the way out of a failure backoff, not a repeat of it",
            )
            assertEquals(3, deps.processCount())
        }

    @Test
    fun `resume is admitted despite a previous failure inside the afterFailure window`() =
        scenario(
            agent = AgentFixtures.simpleAgent(name = "watcher").copy(cooldown = failureOnly),
            source = failedSource(),
            mount = { deps -> mountResume(deps) },
        ) { deps ->
            deps.seedFailedRun()
            assertEquals(HttpStatusCode.OK, postResume().status)
            assertEquals(
                HttpStatusCode.OK,
                postResume().status,
                "manual recovery must not be held back by afterFailure",
            )
            assertEquals(3, deps.processCount())
        }

    @Test
    fun `debug is admitted despite a previous failure inside the afterFailure window`() =
        scenario(
            agent = AgentFixtures.simpleAgent(name = "watcher").copy(cooldown = failureOnly),
            source = failedSource(),
            mount = { deps -> mountDebug(deps) },
        ) { deps ->
            deps.seedFailedRun()
            assertEquals(HttpStatusCode.OK, postDebug().status)
            assertEquals(
                HttpStatusCode.OK,
                postDebug().status,
                "investigating a failure must not be held back by afterFailure",
            )
            assertEquals(3, deps.processCount())
        }
}
