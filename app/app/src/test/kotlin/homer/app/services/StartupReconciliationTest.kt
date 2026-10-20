package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.ProcessJobRegistry
import homer.app.models.Agent
import homer.app.models.ProcessStatus
import homer.app.models.Retain
import homer.app.services.QuestionsService.QuestionStatus
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.Processes
import homer.app.services.persistence.QuestionsRepository
import homer.app.services.persistence.setPurgeAt
import io.mockk.coEvery
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
    ) {
        val processRepository = ProcessRepository(db, ProcessJobRegistry())
        val agentsService = mockk<AgentsService> { every { this@mockk.agents } returns agents }
        val questionsService =
            QuestionsService(QuestionsRepository(db), TestFixtures.mockLogger(), ProcessEventBroadcaster())
        val reconciliation =
            StartupReconciliation(
                processRepository = processRepository,
                questionsService = questionsService,
                dispatchService = dispatchService,
                continuationService = continuationService,
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
            coEvery { processRepository.markInterruptedAsUnknown(any()) } returns emptyList()
            StartupReconciliation(
                processRepository,
                questionsService,
                dispatchService,
                continuationService,
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
            coEvery { processRepository.markInterruptedAsUnknown(any()) } returns emptyList()
            StartupReconciliation(
                processRepository,
                questionsService,
                dispatchService,
                continuationService,
                mockk(relaxed = true),
                logger,
            ) { 1L }.reconcile()
            coVerifyOrder {
                continuationService.failOrphanedDispatching()
                continuationService.refirePendingTerminal()
            }
        }
}
