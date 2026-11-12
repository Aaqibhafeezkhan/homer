package homer.app.di

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.infra.AgentInputResolver
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.ProcessJobRegistry
import homer.app.infra.TextCompiler
import homer.app.models.Agent
import homer.app.models.AgentHook
import homer.app.models.AppConfig
import homer.app.models.ProcessStatus
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.ProcessRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

/**
 * `ProcessWiring`/`HookWiring` are built only by the real boot, so nothing else executes them.
 * This drives the graph they declare end to end: `ProcessManager` resolves `HookService` at call
 * time — the cycle `ProcessManager` → `HookService` → `AgentRunStarter` → `ProcessExecutor` →
 * `ProcessManager` forbids resolving it at construction — and a status write through that graph
 * reaches a hook start. See ADR-0107.
 */
class ProcessWiringTest {
    private val started = mutableListOf<String>()
    private val agents =
        mapOf(
            "declarer" to
                AgentFixtures.simpleAgent(name = "declarer").copy(
                    hooks = listOf(AgentHook(ProcessStatus.FINISHED, "notifier")),
                ),
            "notifier" to AgentFixtures.simpleAgent(name = "notifier"),
        )

    /** The two modules under test over the mocks the boot's own graph supplies from the app module. */
    private fun koin(
        hookFireScope: CoroutineScope,
        runStartGate: CompletableDeferred<Unit>,
    ): Koin {
        val db = PersistenceTestSupport.tempDatabase()
        val registry = ProcessJobRegistry()
        val runStarter = mockk<AgentRunStarter>()
        // Stubbed here, not inside `mockk<AgentsService> { }`: in that block a bare `agents` is
        // the mock's own property — which answers nothing — not this test's map.
        val agentsService = mockk<AgentsService>()
        every { agentsService.agents } returns agents
        coEvery {
            runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers
            {
                // Park before recording, so `started` is also the marker that a fire reached and
                // survived its start: while the gate is shut, nothing is recorded yet.
                runStartGate.await()
                started += firstArg<Agent>().name
                99
            }
        return koinApplication {
            modules(
                module {
                    factory { (name: String) -> LoggerFactory.getLogger(name) }
                    single {
                        AppConfig(
                            agentsSearchPath = "/tmp/agents",
                            workspaceRootPath = "/tmp/workspaces",
                            databasePath = "unused-in-this-test",
                        )
                    }
                    single<TextCompiler> { DefaultTextCompiler() }
                    single { db }
                    single { registry }
                    single<CoroutineScope>(named("applicationScope")) { hookFireScope }
                    single { ProcessRepository(db, registry) }
                    single { ProcessEventBroadcaster() }
                    single { mockk<QuestionsService>(relaxed = true) }
                    single { runStarter }
                    single { agentsService }
                    single {
                        mockk<Authorizer>().also {
                            every { it.canDispatchHop(any(), any(), any()) } returns true
                            every { it.canSee(any(), any(), any()) } returns true
                        }
                    }
                    single { mockk<AgentInputResolver>(relaxed = true) }
                },
                processModule,
                hookModule,
            )
        }.koin
    }

    @Test
    fun `a status write through the wired graph fires the hook its declaring agent declared without waiting on it`() =
        runTest {
            // The wiring launches the fire on the application scope, so the test owns that scope
            // and holds the fire parked inside start on a gate: if the status write ever awaited
            // the fire inline again, compareAndSetStatus could not return while the gate is shut
            // and runTest would time out before the first assertion.
            val runStartGate = CompletableDeferred<Unit>()
            val hookFireScope = CoroutineScope(Job())
            val manager = koin(hookFireScope, runStartGate).get<ProcessManager>()

            val id = manager.obtainProcessId("declarer", owner = "alice")
            manager.setProcessStatus(id, ProcessStatus.WORKING)
            manager.compareAndSetStatus(id, setOf(ProcessStatus.WORKING), ProcessStatus.FINISHED)

            // The write returned while its fire is still parked mid-start, so the response did
            // not wait on the hook chain the run fans out to. ADR-0107.
            assertEquals(emptyList<String>(), started)

            runStartGate.complete(Unit)
            hookFireScope.coroutineContext.job.children
                .toList()
                .joinAll()

            assertEquals(listOf("notifier"), started)
        }
}
