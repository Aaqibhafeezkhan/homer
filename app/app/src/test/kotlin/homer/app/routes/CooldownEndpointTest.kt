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
import homer.app.models.Param
import homer.app.models.ParamType
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.services.Authorizer
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
import kotlin.test.assertFalse

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

    /** The subject input the keyed cases template their `cooldown.key` over. */
    private val mrInput = Param(paramName = "mr", envName = "MR", type = ParamType.STR, required = false)

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
        suspend fun seedFailedRun(cooldownKey: String? = null) {
            val id = processManager.obtainProcessId(agent.name, emptyMap(), cooldownKey = cooldownKey)
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

    private suspend fun ApplicationTestBuilder.postDebug(queryParams: String = "{}") =
        client.post("/api/v1/agents/watcher/debug") {
            contentType(ContentType.Application.Json)
            setBody("""{"commandIndex":0,"env":{},"queryParams":$queryParams,"headers":{}}""")
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

    // ===== cooldown.key: the same windows, counted per subject (ADR-0105) =====

    private fun keyed(cooldown: Cooldown) =
        AgentFixtures
            .simpleAgent(name = "watcher")
            .copy(inputs = listOf(mrInput), cooldown = cooldown)

    /** A source run that carried `MR=1`, so a replay renders the key `mr-1`. */
    private fun keyedSource() = failedSource().copy(inputParams = mapOf("MR" to "1"))

    @Test
    fun `retry inside its subject's keyed minInterval window is refused and launches nothing`() =
        scenario(
            agent = keyed(Cooldown(minInterval = "1m", key = "mr-{{MR}}")),
            source = keyedSource(),
            mount = { deps -> mountRetry(deps) },
        ) { deps ->
            assertEquals(HttpStatusCode.OK, postRetry().status, "the first retry opens mr-1's window")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                postRetry().status,
                "a replay of the same subject must be refused inside its own window",
            )
            assertEquals(1, deps.processCount(), "the refused retry must not create a run")
        }

    @Test
    fun `resume inside its subject's keyed minInterval window is refused and launches nothing`() =
        scenario(
            agent = keyed(Cooldown(minInterval = "1m", key = "mr-{{MR}}")),
            source = keyedSource(),
            mount = { deps -> mountResume(deps) },
        ) { deps ->
            assertEquals(HttpStatusCode.OK, postResume().status, "the first resume opens mr-1's window")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                postResume().status,
                "a replay of the same subject must be refused inside its own window",
            )
            assertEquals(1, deps.processCount(), "the refused resume must not create a run")
        }

    @Test
    fun `retry with a key is still admitted despite a keyed afterFailure`() =
        scenario(
            agent = keyed(Cooldown(afterFailure = "15m", key = "mr-{{MR}}")),
            source = keyedSource(),
            mount = { deps -> mountRetry(deps) },
        ) { deps ->
            deps.seedFailedRun(cooldownKey = "mr-1")
            assertEquals(HttpStatusCode.OK, postRetry().status)
            assertEquals(
                HttpStatusCode.OK,
                postRetry().status,
                "a key narrows the window; it does not make afterFailure gate manual recovery",
            )
            assertEquals(3, deps.processCount())
        }

    @Test
    fun `debug with a key paces its own subject and leaves another alone`() =
        scenario(
            agent = keyed(Cooldown(minInterval = "1m", key = "mr-{{MR}}")),
            source = failedSource(),
            mount = { deps -> mountDebug(deps) },
        ) { deps ->
            assertEquals(HttpStatusCode.OK, postDebug("""{"mr":"1"}""").status, "the first debug opens mr-1's window")
            assertEquals(
                HttpStatusCode.TooManyRequests,
                postDebug("""{"mr":"1"}""").status,
                "mr-1 must be refused inside its own window",
            )
            assertEquals(
                HttpStatusCode.OK,
                postDebug("""{"mr":"2"}""").status,
                "mr-2 is a different subject and must not be paced by mr-1",
            )
            assertEquals(2, deps.processCount(), "the refused debug must not create a run")
        }

    @Test
    fun `debug with an unresolvable key is refused with 400 and launches nothing`() =
        scenario(
            agent = keyed(Cooldown(minInterval = "1m", key = "mr-{{MR}}")),
            source = failedSource(),
            mount = { deps -> mountDebug(deps) },
        ) { deps ->
            // `mr` is optional, so the request resolves and the key is what fails: a
            // template left as a literal would give every subject the same window.
            assertEquals(
                HttpStatusCode.BadRequest,
                postDebug().status,
                "an unresolved cooldown key must fail the start",
            )
            // `-1` is the empty-table sentinel of the id high-water read: no row was inserted.
            assertEquals(-1, deps.processCount(), "a refused key must not create a run")
        }

    // ===== the stored-secret snapshot refuses a key naming a secret input (CWE-312) =====

    /**
     * A source run whose snapshot marks `MR` secret although the current definition does
     * not: what was secret when the inputs were stored stays secret for the replay
     * (ADR-0052), so a key templated over it must be refused — not rendered into the
     * indexed `processes.cooldown_key` column.
     */
    private fun storedSecretSource() = failedSource().copy(inputParams = mapOf("MR" to "s3cret-mr-value"), secretInputKeys = setOf("MR"))

    @Test
    fun `retry with a key naming a stored-secret input is refused with 400 and launches nothing`() =
        scenario(
            agent = keyed(Cooldown(minInterval = "1m", key = "mr-{{MR}}")),
            source = storedSecretSource(),
            mount = { deps -> mountRetry(deps) },
        ) { deps ->
            val response = postRetry()
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "the stored-secret snapshot must refuse a key naming a secret input",
            )
            assertEquals(-1, deps.processCount(), "the refused retry must not create a run")
            val body = response.bodyAsText()
            assertFalse(body.contains("s3cret-mr-value"), "the 400 must not carry the stored input value")
            assertFalse(body.contains("mr-s3cret-mr-value"), "the 400 must not carry the rendered key")
        }

    @Test
    fun `resume with a key naming a stored-secret input is refused with 400 and launches nothing`() =
        scenario(
            agent = keyed(Cooldown(minInterval = "1m", key = "mr-{{MR}}")),
            source = storedSecretSource(),
            mount = { deps -> mountResume(deps) },
        ) { deps ->
            val response = postResume()
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "the stored-secret snapshot must refuse a key naming a secret input",
            )
            assertEquals(-1, deps.processCount(), "the refused resume must not create a run")
            val body = response.bodyAsText()
            assertFalse(body.contains("s3cret-mr-value"), "the 400 must not carry the stored input value")
            assertFalse(body.contains("mr-s3cret-mr-value"), "the 400 must not carry the rendered key")
        }
}
