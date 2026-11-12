package homer.app.fixtures

import homer.app.infra.AgentInputResolver
import homer.app.infra.ProcessJobRegistry
import homer.app.models.Agent
import homer.app.models.HookConfig
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import homer.app.services.HookService
import homer.app.services.ProcessManager
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.HookRepository
import homer.app.services.persistence.ProcessRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.slf4j.Logger

/**
 * The real status-hook chain over one isolated SQLite database: a [ProcessManager] whose
 * transitions record hook rows through a real [HookService], which fires them through a real
 * `HookFireService` into a mocked [AgentRunStarter]. `ProcessWiring`/`HookWiring` resolve the
 * service at call time for exactly this shape, so a test built on this fixture drives the chain
 * production runs rather than a stand-in for it. See ADR-0106.
 */
internal class HookChainFixture(
    val agents: Map<String, Agent> = emptyMap(),
    config: HookConfig = HookConfig(),
    val firedRunId: Int = FIRED_RUN_ID,
    val db: DatabaseService = PersistenceTestSupport.tempDatabase(),
    val logger: Logger = TestFixtures.mockLogger(),
) {
    val registry = ProcessJobRegistry()
    val repository = ProcessRepository(db, registry)
    val agentsService = mockk<AgentsService>()
    val runStarter = mockk<AgentRunStarter>()
    val authorizer =
        mockk<Authorizer>().also {
            every { it.canDispatchHop(any(), any(), any()) } returns true
            every { it.canSee(any(), any(), any()) } returns true
        }

    /** The target agent of every hook run this chain started, in fire order. */
    val started = mutableListOf<String>()

    val processManager: ProcessManager =
        ProcessManager(
            repository = repository,
            jobRegistry = registry,
            logger = logger,
            // Both resolved at call time, as the DI wiring does, so the hook service can be
            // built after the manager that calls back into it.
            hookRecording = { hookService.recording() },
            onHooksRecorded = { recorded -> hookService.onRowsRecorded(recorded) },
        )

    val hookService: HookService by lazy {
        HookService(
            HookRepository(db),
            runStarter,
            agentsService,
            authorizer,
            mockk<AgentInputResolver>(relaxed = true),
            processManager,
            logger,
            config,
        ) { NOW_SEC }
    }

    init {
        // Stubbed here, not inside `mockk<AgentsService> { }`: in that block a bare `agents` is
        // the mock's own property — which answers nothing — not this fixture's map.
        every { agentsService.agents } returns agents
        coEvery {
            runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers
            {
                started += firstArg<Agent>().name
                firedRunId
            }
    }

    companion object {
        const val FIRED_RUN_ID = 99
        const val NOW_SEC = 1000L
    }
}
