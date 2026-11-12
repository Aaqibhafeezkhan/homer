package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentHook
import homer.app.models.HookConfig
import homer.app.models.ProcessStatus
import homer.app.services.persistence.HookRepository
import homer.app.services.persistence.RecordedHooks
import homer.app.services.persistence.ShedHooks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

class HookServiceTest {
    private val repo = mockk<HookRepository>(relaxed = true)
    private val agents = mockk<AgentsService>()
    private val runStarter = mockk<AgentRunStarter>(relaxed = true)
    private val processManager = mockk<ProcessManager>(relaxed = true)
    private val authorizer =
        mockk<Authorizer>().also { every { it.canDispatchHop(any(), any(), any()) } returns true }

    private fun service(
        config: HookConfig = HookConfig(),
        now: Long = 1000L,
    ) = HookService(
        repo,
        runStarter,
        agents,
        authorizer,
        mockk<AgentInputResolver>(relaxed = true),
        processManager,
        LoggerFactory.getLogger("t"),
        config,
    ) { now }

    /** The same service over [logger], so a test can assert the lines it writes. */
    private fun serviceOver(
        logger: Logger,
        config: HookConfig = HookConfig(),
    ) = HookService(
        repo,
        runStarter,
        agents,
        authorizer,
        mockk<AgentInputResolver>(relaxed = true),
        processManager,
        logger,
        config,
    ) { 1000L }

    private fun hook(
        id: Int,
        status: HookStatus = HookStatus.PENDING,
        targetAgentName: String = "notifier",
    ) = PendingHook(
        id = id,
        processId = 42,
        declaringAgent = "declarer",
        triggeringStatus = ProcessStatus.FINISHED,
        targetAgentName = targetAgentName,
        owner = "alice",
        status = status,
        fireAttempts = 0,
        nextAttemptAt = null,
        firedProcessId = null,
        firedAt = null,
        error = null,
        createdAt = 500,
    )

    @Test
    fun `onRowsRecorded claims every row the transition recorded`() =
        runTest {
            // No target is loaded, so each fire settles FAILED at once — what this asserts is
            // that each recorded row was picked up and claimed exactly once.
            every { agents.agents } returns emptyMap()
            coEvery { repo.get(any()) } answers { hook(firstArg()) }
            coEvery { repo.claim(any()) } returns true

            service().onRowsRecorded(RecordedHooks(listOf(5, 6)))

            coVerify(exactly = 1) { repo.claim(5) }
            coVerify(exactly = 1) { repo.claim(6) }
        }

    @Test
    fun `onRowsRecorded does nothing for a transition that recorded none`() =
        runTest {
            service().onRowsRecorded(RecordedHooks())

            coVerify(exactly = 0) { repo.get(any()) }
            coVerify(exactly = 0) { repo.claim(any()) }
        }

    @Test
    fun `a transition that shed rows over the pending cap logs one line per shed entry`() =
        runTest {
            // The drop is the only outcome with no row to look at: without this line an operator
            // sees a hook that never fired and no reason for it. The cap is named, so the fix is
            // the config key rather than a code hunt. See ADR-0107.
            val logger = TestFixtures.mockLogger()

            serviceOver(logger, config = HookConfig(maxPending = 200))
                .onRowsRecorded(
                    RecordedHooks(
                        shed =
                            listOf(
                                ShedHooks(count = 2, agentName = "declarer", status = ProcessStatus.FINISHED),
                                ShedHooks(count = 1, agentName = "other", status = ProcessStatus.FAILED),
                            ),
                    ),
                )

            verify(exactly = 1) {
                logger.warn(
                    match<String> { it.contains("Dropped") && it.contains("hook row(s)") },
                    2,
                    "declarer",
                    ProcessStatus.FINISHED,
                    200,
                )
            }
            verify(exactly = 1) {
                logger.warn(any<String>(), 1, "other", ProcessStatus.FAILED, 200)
            }
        }

    @Test
    fun `a fully shed transition logs the drop and claims nothing`() =
        runTest {
            // Every entry was shed, so there is no row to fire — the log line is the whole
            // record of the transition's hooks.
            val logger = TestFixtures.mockLogger()

            serviceOver(logger)
                .onRowsRecorded(
                    RecordedHooks(shed = listOf(ShedHooks(1, "declarer", ProcessStatus.CREATED))),
                )

            coVerify(exactly = 0) { repo.get(any()) }
            coVerify(exactly = 0) { repo.claim(any()) }
            verify(exactly = 1) { logger.warn(any<String>(), 1, "declarer", ProcessStatus.CREATED, 200) }
        }

    @Test
    fun `a row that cannot be looked up never reaches the status write`() =
        runTest {
            // The transition has already committed when this runs: a hook failure must not be
            // able to fail it, and the row stays PENDING for the boot replay. ADR-0023.
            coEvery { repo.get(any()) } throws IllegalStateException("database is gone")

            service().onRowsRecorded(RecordedHooks(listOf(5)))

            coVerify(exactly = 0) { repo.claim(any()) }
        }

    @Test
    fun `a lookup that is a cancellation is rethrown`() =
        runTest {
            coEvery { repo.get(any()) } throws CancellationException("shutting down")

            assertThrows<CancellationException> { service().onRowsRecorded(RecordedHooks(listOf(5))) }
        }

    @Test
    fun `a fire that throws never reaches the status write and leaves the other rows to fire`() =
        runTest {
            // The row writes around a fire (the claim CAS, a settle) can throw; the transition
            // this runs after has already committed, so nothing may reach it — and the rows
            // behind the failing one must still be attempted. ADR-0023.
            every { agents.agents } returns emptyMap()
            coEvery { repo.get(any()) } answers { hook(firstArg()) }
            coEvery { repo.claim(5) } throws IllegalStateException("database is gone")
            coEvery { repo.claim(6) } returns true

            service().onRowsRecorded(RecordedHooks(listOf(5, 6)))

            coVerify(exactly = 1) { repo.claim(6) }
        }

    @Test
    fun `a fire that throws mid-sweep leaves the sweep's other rows to fire`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.findDueRetries(1000L) } returns listOf(hook(5), hook(6))
            coEvery { repo.claim(5) } throws IllegalStateException("database is gone")
            coEvery { repo.claim(6) } returns true

            service().sweepRetries()

            coVerify(exactly = 1) { repo.claim(6) }
        }

    @Test
    fun `a fire that throws during the boot replay leaves the replay's other rows to fire`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.list(HookStatus.PENDING) } returns listOf(hook(5), hook(6))
            coEvery { repo.claim(5) } throws IllegalStateException("database is gone")
            coEvery { repo.claim(6) } returns true

            service().refirePending()

            coVerify(exactly = 1) { repo.claim(6) }
        }

    @Test
    fun `sweepRetries fires every due row`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.findDueRetries(1000L) } returns listOf(hook(5), hook(6))
            coEvery { repo.claim(any()) } returns true

            service().sweepRetries()

            coVerify(exactly = 1) { repo.claim(5) }
            coVerify(exactly = 1) { repo.claim(6) }
        }

    @Test
    fun `a failed retry lookup leaves the sweep to its other work`() =
        runTest {
            coEvery { repo.findDueRetries(any()) } throws IllegalStateException("database is gone")

            service().sweepRetries()

            coVerify(exactly = 0) { repo.claim(any()) }
        }

    @Test
    fun `refirePending fires every row a previous run left pending`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.list(HookStatus.PENDING) } returns listOf(hook(5))
            coEvery { repo.claim(5) } returns true

            service().refirePending()

            coVerify(exactly = 1) { repo.claim(5) }
        }

    @Test
    fun `failOrphanedDispatching settles what a restart interrupted`() =
        runTest {
            coEvery { repo.failOrphanedDispatching(1000L) } returns listOf(hook(5, HookStatus.DISPATCHING))

            service().failOrphanedDispatching()

            coVerify { repo.failOrphanedDispatching(1000L) }
        }

    @Test
    fun `pruneSettled cuts at the configured retention behind now`() =
        runTest {
            service(config = HookConfig(settledRetentionSec = 3600)).pruneSettled()

            coVerify { repo.pruneSettled(1000L - 3600L) }
        }

    @Test
    fun `get reads the row through the repository`() =
        runTest {
            coEvery { repo.get(5) } returns hook(5)

            assertEquals(5, service().get(5)?.id)
        }

    @Test
    fun `the recording lambda selects the entries of that agent and status only`() {
        val declarer =
            AgentFixtures.simpleAgent(name = "declarer").copy(
                hooks =
                    listOf(
                        AgentHook(ProcessStatus.FINISHED, "notifier"),
                        AgentHook(ProcessStatus.FAILED, "pager"),
                        AgentHook(ProcessStatus.FINISHED, "audit"),
                    ),
            )
        every { agents.agents } returns mapOf("declarer" to declarer)

        val recording = hookRecordingFor(agents, maxPending = 7)

        assertEquals(
            listOf("notifier", "audit"),
            recording.hooksFor("declarer", ProcessStatus.FINISHED).map { it.agent },
        )
        assertEquals(
            listOf("pager"),
            recording.hooksFor("declarer", ProcessStatus.FAILED).map { it.agent },
        )
        assertEquals(emptyList(), recording.hooksFor("declarer", ProcessStatus.CREATED))
        assertEquals(emptyList(), recording.hooksFor("unknown", ProcessStatus.FINISHED))
        assertEquals(7, recording.maxPending)
    }

    @Test
    fun `recording is the same lookup the transitions run, capped by the service config`() {
        // `ProcessManager` records its rows through this, so it must be the config's cap and
        // the live catalog, not a second definition of either.
        every { agents.agents } returns mapOf("declarer" to reloaded("notifier"))

        val recording = service(config = HookConfig(maxPending = 3)).recording()

        assertEquals(listOf("notifier"), recording.hooksFor("declarer", ProcessStatus.FINISHED).map { it.agent })
        assertEquals(3, recording.maxPending)
    }

    @Test
    fun `the recording lambda sees an agent reloaded after the run started`() {
        // The lookup runs at transition time, not at start time, so a reload in between is
        // honoured rather than firing the hooks the agent used to declare.
        every { agents.agents } returns emptyMap()
        val recording = hookRecordingFor(agents, maxPending = 7)

        every { agents.agents } returns mapOf("declarer" to reloaded("notifier"))

        assertEquals(listOf("notifier"), recording.hooksFor("declarer", ProcessStatus.FINISHED).map { it.agent })
    }

    private fun reloaded(target: String): Agent =
        AgentFixtures.simpleAgent(name = "declarer").copy(hooks = listOf(AgentHook(ProcessStatus.FINISHED, target)))
}
