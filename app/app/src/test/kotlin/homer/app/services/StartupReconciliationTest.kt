package homer.app.services

import homer.app.executors.RunMessageExpiry
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.HookChainFixture
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.ProcessJobRegistry
import homer.app.models.Agent
import homer.app.models.AgentHook
import homer.app.models.ProcessStatus
import homer.app.models.Retain
import homer.app.models.RunMessagesConfig
import homer.app.services.QuestionsService.QuestionStatus
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.InterruptedResult
import homer.app.services.persistence.NewPendingHook
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.Processes
import homer.app.services.persistence.QuestionsRepository
import homer.app.services.persistence.RunMessagesRepository
import homer.app.services.persistence.insertPendingHooksTx
import homer.app.services.persistence.setPurgeAt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StartupReconciliationTest {
    private class Harness(
        val db: DatabaseService = PersistenceTestSupport.tempDatabase(),
        dispatchService: DispatchService = mockk(relaxed = true),
        continuationService: ContinuationService = mockk(relaxed = true),
        agents: Map<String, Agent> = emptyMap(),
        val runMessagesService: RunMessageExpiry = mockk(relaxed = true),
    ) {
        val processRepository = ProcessRepository(db, ProcessJobRegistry())
        val agentsService = mockk<AgentsService> { every { this@mockk.agents } returns agents }
        val questionsService =
            QuestionsService(QuestionsRepository(db), TestFixtures.mockLogger(), ProcessEventBroadcaster())
        val reconciliation =
            StartupReconciliation(
                processRepository = processRepository,
                questionsService = questionsService,
                runMessagesService = runMessagesService,
                dispatchService = dispatchService,
                continuationService = continuationService,
                hookService = TestFixtures.mockHookService(),
                agentsService = agentsService,
                logger = TestFixtures.mockLogger(),
                clock = { 999L },
            )

        /** (purge_at, record_purge_at, retain_artifacts) exactly as stored. */
        suspend fun purgeColumns(processId: Int): Triple<Long?, Long?, String?> =
            db.read {
                Processes
                    .select(Processes.purgeAt, Processes.recordPurgeAt, Processes.retainArtifacts)
                    .where { Processes.id eq processId }
                    .single()
                    .let { Triple(it[Processes.purgeAt], it[Processes.recordPurgeAt], it[Processes.retainArtifacts]) }
            }
    }

    private val day = 86_400L

    @Test
    fun `reconcile stamps purge times on UNKNOWN rows from the agent's current workspace config`() =
        runTest {
            val keeper = AgentFixtures.simpleAgent(name = "keeper")
            val keeperAgent =
                keeper.copy(
                    workspace =
                        keeper.workspace.copy(
                            purgeAfter = "1d",
                            retain = Retain(recordFor = "7d", artifacts = listOf("decision.json")),
                        ),
                )
            val h = Harness(agents = mapOf("keeper" to keeperAgent))
            val interrupted = h.processRepository.obtainProcessId("keeper", emptyMap(), 100).processId
            h.processRepository.transitionStatus(interrupted, ProcessStatus.WORKING, 1, null)

            h.reconciliation.reconcile()

            assertEquals(ProcessStatus.UNKNOWN, h.processRepository.getStatus(interrupted))
            assertEquals(Triple(999L + day, 999L + 7 * day, "[\"decision.json\"]"), h.purgeColumns(interrupted))
        }

    @Test
    fun `reconcile stamps the default purge times when the agent no longer exists`() =
        runTest {
            val h = Harness()
            val orphan = h.processRepository.obtainProcessId("removed-agent", emptyMap(), 100).processId
            h.processRepository.transitionStatus(orphan, ProcessStatus.WORKING, 1, null)

            h.reconciliation.reconcile()

            // Workspace() defaults: purgeAfter 5d, no retain block, so the record dies with the directory.
            assertEquals(Triple(999L + 5 * day, 999L + 5 * day, null), h.purgeColumns(orphan))
        }

    @Test
    fun `reconcile backfills UNKNOWN rows left unstamped by an earlier restart and leaves stamped rows alone`() =
        runTest {
            val h = Harness()
            val unstamped = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(unstamped, ProcessStatus.UNKNOWN, 1, null)

            val stamped = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(stamped, ProcessStatus.UNKNOWN, 1, null)
            h.processRepository.setPurgeAt(stamped, purgeAt = 123, recordPurgeAt = 456, retainArtifacts = listOf("x.json"))

            // Not UNKNOWN: its own coroutine stamps it, reconciliation must not.
            val finished = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(finished, ProcessStatus.FINISHED, 1, null)

            h.reconciliation.reconcile()

            assertEquals(Triple(999L + 5 * day, 999L + 5 * day, null), h.purgeColumns(unstamped))
            assertEquals(Triple(123L, 456L, "[\"x.json\"]"), h.purgeColumns(stamped))
            assertNull(h.purgeColumns(finished).first)
        }

    @Test
    fun `reconcile marks interrupted runs UNKNOWN and expires their open questions`() =
        runTest {
            val h = Harness()
            val working = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(working, ProcessStatus.WORKING, 1, null)
            h.questionsService.registerProcess(working, "a")
            val question = h.questionsService.ask(working, "Resume?")

            val finished = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(finished, ProcessStatus.FINISHED, 1, null)

            h.reconciliation.reconcile()

            assertEquals(ProcessStatus.UNKNOWN, h.processRepository.getStatus(working))
            assertEquals(ProcessStatus.FINISHED, h.processRepository.getStatus(finished))
            assertEquals(QuestionStatus.EXPIRED, h.questionsService.get(question.id)?.status)
            assertEquals(999L to ProcessStatus.UNKNOWN, h.processRepository.getHistory(working).last())
        }

    @Test
    fun `reconcile closes the inbox of each interrupted run and only those`() =
        runTest {
            val h = Harness()
            val a = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(a, ProcessStatus.WORKING, 1, null)
            val b = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(b, ProcessStatus.WORKING, 1, null)
            val done = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(done, ProcessStatus.FINISHED, 1, null)

            h.reconciliation.reconcile()

            coVerify(exactly = 1) { h.runMessagesService.closeForProcess(a) }
            coVerify(exactly = 1) { h.runMessagesService.closeForProcess(b) }
            coVerify(exactly = 0) { h.runMessagesService.closeForProcess(done) }
        }

    @Test
    fun `reconcile marks QUEUED messages of runs that ended without closing their inbox UNDELIVERED`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val messages =
                RunMessagesService(RunMessagesRepository(db), ProcessEventBroadcaster(), RunMessagesConfig(), TestFixtures.mockLogger())
            val h = Harness(db = db, runMessagesService = messages)
            val ended = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(ended, ProcessStatus.WORKING, 1, null)
            val interrupted = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(interrupted, ProcessStatus.WORKING, 1, null)
            messages.send(ended, null, "lost")
            messages.send(interrupted, null, "cut off")
            // The terminal status committed, but the run-end close never ran (it failed, or the server stopped).
            h.processRepository.transitionStatus(ended, ProcessStatus.FINISHED, 2, null)

            h.reconciliation.reconcile()

            assertEquals(listOf(RunMessageStatus.UNDELIVERED), messages.list(ended).map { it.status })
            assertEquals(listOf(RunMessageStatus.UNDELIVERED), messages.list(interrupted).map { it.status })
        }

    @Test
    fun `reconcile runs the ended-runs inbox sweep once`() =
        runTest {
            val h = Harness()

            h.reconciliation.reconcile()

            coVerify(exactly = 1) { h.runMessagesService.closeForEndedRuns() }
        }

    @Test
    fun `reconcile is a no-op when there are no interrupted runs`() =
        runTest {
            val h = Harness()
            val finished = h.processRepository.obtainProcessId("a", emptyMap(), 100).processId
            h.processRepository.transitionStatus(finished, ProcessStatus.FINISHED, 1, null)

            h.reconciliation.reconcile()

            assertEquals(ProcessStatus.FINISHED, h.processRepository.getStatus(finished))
        }

    @Test
    fun `reconcile fails orphaned dispatching then re-fires pending-answered dispatches`() =
        runTest {
            val dispatchService = mockk<DispatchService>(relaxed = true)
            val continuationService = mockk<ContinuationService>(relaxed = true)
            val processRepository = mockk<ProcessRepository>(relaxed = true)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            val logger = TestFixtures.mockLogger()
            coEvery { processRepository.markInterruptedAsUnknown(any(), any()) } returns
                InterruptedResult(emptyList(), emptyList())
            StartupReconciliation(
                processRepository,
                questionsService,
                mockk<RunMessageExpiry>(relaxed = true),
                dispatchService,
                continuationService,
                mockk(relaxed = true),
                mockk(relaxed = true),
                logger,
            ) { 1L }.reconcile()
            coVerifyOrder {
                dispatchService.failOrphanedDispatching()
                dispatchService.refirePendingAnswered()
            }
        }

    @Test
    fun `reconcile fails orphaned continuations then re-fires already-terminal watched`() =
        runTest {
            val dispatchService = mockk<DispatchService>(relaxed = true)
            val continuationService = mockk<ContinuationService>(relaxed = true)
            val processRepository = mockk<ProcessRepository>(relaxed = true)
            val questionsService = mockk<QuestionsService>(relaxed = true)
            val logger = TestFixtures.mockLogger()
            coEvery { processRepository.markInterruptedAsUnknown(any(), any()) } returns
                InterruptedResult(emptyList(), emptyList())
            StartupReconciliation(
                processRepository,
                questionsService,
                mockk<RunMessageExpiry>(relaxed = true),
                dispatchService,
                continuationService,
                mockk(relaxed = true),
                mockk(relaxed = true),
                logger,
            ) { 1L }.reconcile()
            coVerifyOrder {
                continuationService.failOrphanedDispatching()
                continuationService.refirePendingTerminal()
            }
        }

    @Test
    fun `reconcile fails orphaned hook fires, fires the rows UNKNOWN recorded, then replays the pending`() =
        runTest {
            val hookService = TestFixtures.mockHookService()
            val processRepository = mockk<ProcessRepository>(relaxed = true)
            coEvery { processRepository.markInterruptedAsUnknown(any(), any()) } returns
                InterruptedResult(listOf(7), listOf(11, 12))
            StartupReconciliation(
                processRepository,
                mockk(relaxed = true),
                mockk<RunMessageExpiry>(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                hookService,
                mockk(relaxed = true),
                TestFixtures.mockLogger(),
            ) { 1L }.reconcile()

            coVerifyOrder {
                hookService.failOrphanedDispatching()
                hookService.onRowsRecorded(listOf(11, 12))
                hookService.refirePending()
            }
        }

    @Test
    fun `a run interrupted by the restart fires its UNKNOWN hook once, and a second boot fires nothing`() =
        runTest {
            val f = chain(ProcessStatus.UNKNOWN to "notifier")
            val working = f.processManager.obtainProcessId("declarer", owner = "alice")
            f.processManager.setProcessStatus(working, ProcessStatus.WORKING)
            assertEquals(emptyList(), f.started)

            reconciliationOver(f).reconcile()

            assertEquals(ProcessStatus.UNKNOWN, f.processManager.getProcessStatus(working))
            assertEquals(listOf("notifier"), f.started)

            // The row is settled DISPATCHED, so the next boot replays nothing.
            reconciliationOver(f).reconcile()

            assertEquals(listOf("notifier"), f.started)
        }

    @Test
    fun `a hook row left PENDING by the previous run fires once at boot, and a second boot fires nothing`() =
        runTest {
            // The server stopped between the status write's commit and its fire: the row is
            // durable and PENDING, and the boot replay is the only thing that can fire it. The
            // run never reached FINISHED here, so the row is seeded as that write left it.
            val f = chain(ProcessStatus.FINISHED to "notifier")
            val run = f.processManager.obtainProcessId("declarer", owner = "alice")
            f.db.query {
                insertPendingHooksTx(
                    listOf(
                        NewPendingHook(
                            processId = run,
                            declaringAgent = "declarer",
                            triggeringStatus = ProcessStatus.FINISHED,
                            targetAgentName = "notifier",
                            owner = "alice",
                            createdAt = HookChainFixture.NOW_SEC,
                        ),
                    ),
                    maxPending = 10,
                )
            }

            reconciliationOver(f).reconcile()
            assertEquals(listOf("notifier"), f.started)

            reconciliationOver(f).reconcile()
            assertEquals(listOf("notifier"), f.started)
        }

    /** A declaring agent with [hooks], plus every agent they name, all loaded. */
    private fun chain(vararg hooks: Pair<ProcessStatus, String>): HookChainFixture =
        HookChainFixture(
            agents =
                buildMap {
                    put(
                        "declarer",
                        AgentFixtures
                            .simpleAgent(name = "declarer")
                            .copy(hooks = hooks.map { AgentHook(it.first, it.second) }),
                    )
                    hooks.map { it.second }.distinct().forEach {
                        put(it, AgentFixtures.simpleAgent(name = it))
                    }
                },
        )

    /** A reconciliation over [f]'s own database and hook service, so the boot steps run the real chain. */
    private fun reconciliationOver(f: HookChainFixture) =
        StartupReconciliation(
            processRepository = f.repository,
            questionsService = mockk(relaxed = true),
            runMessagesService = mockk<RunMessageExpiry>(relaxed = true),
            dispatchService = mockk(relaxed = true),
            continuationService = mockk(relaxed = true),
            hookService = f.hookService,
            agentsService = f.agentsService,
            logger = TestFixtures.mockLogger(),
            clock = { HookChainFixture.NOW_SEC },
        )
}
