package homer.app.routes

import homer.app.EnvConstants
import homer.app.apiJson
import homer.app.configureStatusPages
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.mountApiV1Routes
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.MaintenanceConfig
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.AgentRunStarter
import homer.app.services.Authorizer
import homer.app.services.MaintenanceModeService
import homer.app.services.ProcessManager
import homer.app.services.persistence.ProcessRecordReads
import homer.app.services.persistence.ServerMetaRepository
import io.ktor.client.request.header
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The maintenance gate on the three start paths that build their process directly instead of going
 * through `AgentRunStarter`: `POST /processes/{id}/retry`, `POST /processes/{id}/resume` and
 * `POST /agents/{agentName}/debug`. Every start path has to queue while the mode is on (ADR-0092,
 * root §12 rules 4–5), so each of the three takes the gate itself.
 *
 * The gate is the **real** service with the wait at its allowed minimum: a start arriving while the
 * mode is on parks in its queue, is shed when the wait runs out, and creates no run — which is the
 * whole point of the window. Once the mode goes off the same request starts a run.
 */
class MaintenanceStartGateTest {
    private val debugBody = """{"commandIndex":0,"env":{},"queryParams":{},"headers":{}}"""

    /** The three endpoints' collaborators, with the gate already on. */
    private inner class Deps {
        val agent: Agent = AgentFixtures.simpleAgent(name = "watcher")
        val source: ProcessInfo = ProcessInfo(id = 42, status = ProcessStatus.FAILED, agentName = "watcher")
        val executor: ProcessExecutor = mockk(relaxed = true)
        val processFactory: ProcessFactory = mockk(relaxed = true)

        /** Real gate, mode already on, shortest allowed wait so a parked start is shed promptly. */
        val gate: MaintenanceModeService =
            MaintenanceModeService(
                repository = mockk<ServerMetaRepository>(relaxed = true),
                config = MaintenanceConfig(queueWaitSec = 1),
                initialEnabled = true,
                logger = TestFixtures.mockLogger(),
            )

        init {
            coEvery { executor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
        }
    }

    private fun scenario(
        mount: Route.(Deps) -> Unit,
        requests: suspend ApplicationTestBuilder.(Deps) -> Unit,
    ) = testApplication {
        val deps = Deps()
        try {
            application {
                install(ContentNegotiation) { json(apiJson) }
                configureStatusPages()
                routing { mount(deps) }
            }
            requests(deps)
        } finally {
            deps.gate.stop()
        }
    }

    private fun Route.mountRetry(deps: Deps) =
        post("/api/v1/processes/{id}/retry") {
            handleRetry(
                serverId = "test-server",
                call = call,
                sourceProcess = deps.source,
                agent = deps.agent,
                authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                processManager = mockk<ProcessManager>(relaxed = true),
                processExecutor = deps.executor,
                processFactory = deps.processFactory,
                telemetryService = TestFixtures.mockTelemetryService(),
                costMeter = mockk(relaxed = true),
                agentInputResolver = AgentInputResolver(),
                maintenanceGate = deps.gate,
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
                processManager = mockk<ProcessManager>(relaxed = true),
                processExecutor = deps.executor,
                processFactory = deps.processFactory,
                telemetryService = TestFixtures.mockTelemetryService(),
                costMeter = mockk(relaxed = true),
                agentInputResolver = AgentInputResolver(),
                maintenanceGate = deps.gate,
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
                maintenanceGate = deps.gate,
            )
        }

    private suspend fun ApplicationTestBuilder.postRetry() = client.post("/api/v1/processes/42/retry")

    private suspend fun ApplicationTestBuilder.postResume() = client.post("/api/v1/processes/42/resume")

    private suspend fun ApplicationTestBuilder.postDebug() =
        client.post("/api/v1/agents/watcher/debug") {
            contentType(ContentType.Application.Json)
            setBody(debugBody)
        }

    @Test
    fun `retry queues while the mode is on and starts once it is off`() =
        scenario({ deps -> mountRetry(deps) }) { deps ->
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                postRetry().status,
                "a retry arriving while the mode is on must park and be shed, never start",
            )
            coVerify(exactly = 0) { deps.executor.execute(any(), any()) }

            deps.gate.setEnabled(false)
            assertEquals(HttpStatusCode.OK, postRetry().status)
            coVerify(exactly = 1) { deps.executor.execute(any(), any()) }
        }

    @Test
    fun `resume queues while the mode is on and starts once it is off`() =
        scenario({ deps -> mountResume(deps) }) { deps ->
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                postResume().status,
                "a resume arriving while the mode is on must park and be shed, never start",
            )
            coVerify(exactly = 0) { deps.executor.execute(any(), any()) }

            deps.gate.setEnabled(false)
            assertEquals(HttpStatusCode.OK, postResume().status)
            coVerify(exactly = 1) { deps.executor.execute(any(), any()) }
        }

    @Test
    fun `debug queues while the mode is on and starts once it is off`() =
        scenario({ deps -> mountDebug(deps) }) { deps ->
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                postDebug().status,
                "a debug run arriving while the mode is on must park and be shed, never start",
            )
            coVerify(exactly = 0) { deps.executor.execute(any(), any()) }

            deps.gate.setEnabled(false)
            assertEquals(HttpStatusCode.OK, postDebug().status)
            coVerify(exactly = 1) { deps.executor.execute(any(), any()) }
        }

    // --- the HTTP start path: a caller-named parent is never a bypass (ADR-0092) ---

    /** A real gate, mode already on, shortest allowed wait so a parked start is shed promptly. */
    private fun liveGate(): MaintenanceModeService =
        MaintenanceModeService(
            repository = mockk<ServerMetaRepository>(relaxed = true),
            config = MaintenanceConfig(queueWaitSec = 1),
            initialEnabled = true,
            logger = TestFixtures.mockLogger(),
        )

    /**
     * Mounts the **real** `POST /agent/{agentName}` route and the **real** [AgentRunStarter] behind
     * it, so a request's parent header goes through the same resolution production runs. The bypass
     * is server-derived: a parent id the caller supplied must not admit the start however live it is.
     */
    private fun ApplicationTestBuilder.mountRealStartRoute(
        gate: MaintenanceModeService,
        reads: ProcessRecordReads,
        rows: ProcessManager,
        grants: Authorizer,
        executor: ProcessExecutor,
    ) {
        mountApiV1Routes {
            every { authorizer } returns grants
            every { processManager } returns rows
            every { agentsService.agents } returns mapOf("watcher" to AgentFixtures.simpleAgent(name = "watcher"))
            every { agentRunStarter } returns
                AgentRunStarter(
                    serverId = "test-server",
                    processExecutor = executor,
                    processFactory = mockk(relaxed = true),
                    telemetryService = TestFixtures.mockTelemetryService(),
                    costMeter = mockk(relaxed = true),
                    maxInvocationDepth = 5,
                    cooldownGate = mockk(relaxed = true),
                    maintenanceGate = gate,
                    processReads = reads,
                )
        }
    }

    @Test
    fun `a start naming a live WORKING parent through the header still parks`() =
        testApplication {
            val gate = liveGate()
            val reads = mockk<ProcessRecordReads> { coEvery { getProcessStatus(any()) } returns ProcessStatus.WORKING }
            val rows = mockk<ProcessManager>(relaxed = true)
            val executor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { executor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            coEvery { rows.getProcessInfo(42) } returns
                ProcessInfo(id = 42, status = ProcessStatus.WORKING, agentName = "watcher")
            mountRealStartRoute(gate, reads, rows, Authorizer(emptyMap(), emptyMap(), enabled = false), executor)

            try {
                val response =
                    client.post("/api/v1/agent/watcher") { header(EnvConstants.PARENT_PROCESS_HEADER, "42") }

                assertEquals(
                    HttpStatusCode.ServiceUnavailable,
                    response.status,
                    "a start whose parent came from the caller's header must park and be shed, never bypass",
                )
                coVerify(exactly = 0) { executor.execute(any(), any()) }
                // Not even the liveness read happens for a caller-supplied parent: the flag alone
                // decides, and it is false on every path that does not derive the parent itself.
                coVerify(exactly = 0) { reads.getProcessStatus(any()) }
            } finally {
                gate.stop()
            }
        }

    @Test
    fun `a parent the caller cannot see and an absent parent are the same 422 and start nothing`() =
        testApplication {
            val gate = liveGate()
            val reads = mockk<ProcessRecordReads> { coEvery { getProcessStatus(any()) } returns ProcessStatus.WORKING }
            val rows = mockk<ProcessManager>(relaxed = true)
            val executor = mockk<ProcessExecutor>(relaxed = true)
            val denying =
                mockk<Authorizer> {
                    every { canSee(any(), any(), any()) } returns false
                    every { anyGrant(any(), any()) } returns true
                    every { hasAction(any(), any(), any()) } returns true
                }
            mountRealStartRoute(gate, reads, rows, denying, executor)

            try {
                coEvery { rows.getProcessInfo(42) } returns
                    ProcessInfo(id = 42, status = ProcessStatus.WORKING, agentName = "watcher", owner = "bob")
                val denied = client.post("/api/v1/agent/watcher") { header(EnvConstants.PARENT_PROCESS_HEADER, "42") }

                coEvery { rows.getProcessInfo(42) } returns null
                val absent = client.post("/api/v1/agent/watcher") { header(EnvConstants.PARENT_PROCESS_HEADER, "42") }

                assertEquals(HttpStatusCode.UnprocessableEntity, denied.status)
                assertEquals(HttpStatusCode.UnprocessableEntity, absent.status)
                assertEquals(
                    absent.bodyAsText(),
                    denied.bodyAsText(),
                    "a hidden parent must be indistinguishable from an absent one (§12 rule 3)",
                )
                // The refusal lands before the gate and before any liveness read: naming a
                // parent is not a way to probe a row the caller cannot see.
                coVerify(exactly = 0) { executor.execute(any(), any()) }
                coVerify(exactly = 0) { reads.getProcessStatus(any()) }
            } finally {
                gate.stop()
            }
        }
}
