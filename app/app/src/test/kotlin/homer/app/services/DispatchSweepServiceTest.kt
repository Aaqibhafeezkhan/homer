package homer.app.services

import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.models.DispatchConfig
import homer.app.services.QuestionsService.QuestionStatus
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.DispatchGroupQuestions
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.QuestionsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DispatchSweepServiceTest {
    private val dispatchService = mockk<DispatchService>(relaxed = true)

    private fun service(intervalSec: Int = 1) =
        DispatchSweepService(
            dispatchService = dispatchService,
            intervalSec = intervalSec,
            logger = TestFixtures.mockLogger(),
        )

    @Test
    fun `loop calls sweepExpired after interval elapses and stops after cancel`() =
        runTest {
            val svc = service(intervalSec = 1)
            svc.start(backgroundScope)

            // One full interval passes → exactly one sweep
            advanceTimeBy(1_001L)
            coVerify(exactly = 1) { dispatchService.sweepExpired() }

            // Cancel the loop
            svc.stop()

            // Advance several more intervals — no additional calls expected
            advanceTimeBy(5_000L)
            coVerify(exactly = 1) { dispatchService.sweepExpired() }
        }

    @Test
    fun `loop survives a sweepExpired exception and continues on the next tick`() =
        runTest {
            coEvery { dispatchService.sweepExpired() } throws RuntimeException("boom")

            val svc = service(intervalSec = 1)
            svc.start(backgroundScope)

            // Advance past two full intervals so the loop fires at least twice,
            // proving the catch block kept the while-isActive loop alive.
            advanceTimeBy(2_500L)

            coVerify(atLeast = 2) { dispatchService.sweepExpired() }
        }

    @Test
    fun `each tick prunes settled dispatches after the expiry sweep`() =
        runTest {
            service(intervalSec = 1).start(backgroundScope)

            advanceTimeBy(1_001L)

            coVerifyOrder {
                dispatchService.sweepExpired()
                dispatchService.pruneSettled()
            }
        }

    // --- Grouped dispatches on a tick (ADR-0105) ---

    /** A real [DispatchService] over [db], so a tick exercises the repository and the events it emits. */
    private suspend fun TestScope.tick(
        db: DatabaseService,
        broadcaster: ProcessEventBroadcaster,
        settledRetentionSec: Long = 3_600,
        block: suspend () -> Unit,
    ) {
        val dispatchService =
            PersistenceTestSupport.dispatchService(
                db = db,
                questionsService = PersistenceTestSupport.questionsService(db = db),
                agentsService = mockk(relaxed = true),
                runStarter = mockk(),
                inputResolver = mockk(relaxed = true),
                broadcaster = broadcaster,
                config = DispatchConfig(settledRetentionSec = settledRetentionSec),
            )
        val svc = DispatchSweepService(dispatchService, intervalSec = 1, TestFixtures.mockLogger())
        svc.start(backgroundScope)
        block()
        svc.stop()
    }

    private suspend fun seedGroup(
        db: DatabaseService,
        expiresAt: Long? = null,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Int =
        (DispatchRepository(db).createGroup(
            listOf("m1", "m2").map {
                QuestionsService.Question(it, 1, "asker", "text", emptyList(), QuestionStatus.OPEN, createdAt = 1, dispatchBound = true)
            },
            Dispatch(
                0,
                DispatchGroupService.GROUP_QUESTION_ID_PREFIX + "sentinel",
                DispatchStatus.PENDING,
                "handler",
                "answer",
                emptyMap(),
                1,
                "asker",
                expiresAt,
                createdAt,
                kind = DispatchKind.GROUP,
            ),
            maxPending = 10,
            maxOpenPerProcess = 10,
        ) as DispatchRepository.GroupCreateOutcome.Created).dispatchId

    @Test
    fun `a tick expires a lapsed group as a whole and emits one event per member`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val broadcaster = mockk<ProcessEventBroadcaster>(relaxed = true)
            val id = seedGroup(db, expiresAt = 1)

            // Registered just now, so the same tick's prune leaves the just-cancelled row alone:
            // this test is about the expiry, the next one about the prune.
            tick(db, broadcaster) { advanceTimeBy(1_001L) }

            val repo = DispatchRepository(db)
            assertEquals(DispatchStatus.CANCELLED, repo.get(id)!!.status)
            // Every member question goes with the group, not just the first.
            assertNull(QuestionsRepository(db).get("m1"))
            assertNull(QuestionsRepository(db).get("m2"))
            verify { broadcaster.emit(ProcessEvent.QuestionExpired(1, "m1")) }
            verify { broadcaster.emit(ProcessEvent.QuestionExpired(1, "m2")) }
        }

    @Test
    fun `a tick prunes a settled group with its member rows`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val broadcaster = mockk<ProcessEventBroadcaster>(relaxed = true)
            val id = seedGroup(db, createdAt = 1)
            val repo = DispatchRepository(db)
            repo.claim(id)
            repo.markFailed(id, "boom", at = 5)
            // The member questions are gone with the fire, but the settled row still reports them.
            assertEquals(listOf("m1", "m2"), repo.get(id)!!.questionIds)

            tick(db, broadcaster) { advanceTimeBy(1_001L) }

            assertNull(repo.get(id))
            val memberRows =
                db.read {
                    DispatchGroupQuestions
                        .selectAll()
                        .where { DispatchGroupQuestions.dispatchId eq id }
                        .count()
                }
            assertEquals(0, memberRows)
        }
}
