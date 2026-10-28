package homer.app.services.persistence

import homer.app.fixtures.PersistenceTestSupport
import homer.app.infra.ProcessJobRegistry
import homer.app.models.ProcessStatus
import homer.app.services.RunMessage
import homer.app.services.RunMessageStatus
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunMessagesRepositoryTest {
    private val db = PersistenceTestSupport.tempDatabase()
    private val processes = ProcessRepository(db, ProcessJobRegistry())
    private val repo = RunMessagesRepository(db)

    private suspend fun run(status: ProcessStatus = ProcessStatus.WORKING): Int {
        val id = processes.obtainProcessId("a", emptyMap(), 100).processId
        processes.transitionStatus(id, status, 1, null)
        return id
    }

    private fun message(
        id: String,
        processId: Int,
        status: RunMessageStatus = RunMessageStatus.QUEUED,
    ) = RunMessage(id, processId, "text $id", status, sender = "alice", createdAt = 1000L, deliveredAt = null)

    @Test
    fun `tryInsert into a WORKING run is Inserted and list returns it QUEUED`() =
        runTest {
            val id = run()
            assertEquals(RunMessagesRepository.InsertResult.Inserted, repo.tryInsert(message("m1", id), maxPerRun = 10))

            val stored = repo.list(id).single()
            assertEquals(message("m1", id), stored)
            assertNull(stored.deliveredAt)
        }

    @Test
    fun `tryInsert into a FINISHED, FAILED, KILLED or UNKNOWN run is RunNotActive and stores nothing`() =
        runTest {
            for (status in listOf(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.KILLED, ProcessStatus.UNKNOWN)) {
                val id = run(status)
                assertEquals(RunMessagesRepository.InsertResult.RunNotActive, repo.tryInsert(message("m-$status", id), 10), "$status")
                assertEquals(emptyList(), repo.list(id), "$status")
            }
            assertEquals(RunMessagesRepository.InsertResult.RunNotActive, repo.tryInsert(message("ghost", 9999), 10))
        }

    @Test
    fun `tryInsert at maxPerRun stored messages of any status is CapReached`() =
        runTest {
            val id = run()
            repo.tryInsert(message("d1", id), 3)
            repo.tryInsert(message("d2", id), 3)
            repo.take(id, 2000L)
            repo.tryInsert(message("q1", id), 3)

            assertEquals(RunMessagesRepository.InsertResult.CapReached, repo.tryInsert(message("q2", id), 3))
            assertEquals(3, repo.list(id).size)
        }

    @Test
    fun `take returns QUEUED messages oldest first, marks them DELIVERED with deliveredAt, and a second take returns empty`() =
        runTest {
            val id = run()
            repo.tryInsert(message("m1", id), 10)
            repo.tryInsert(message("m2", id), 10)

            val taken = repo.take(id, 2000L)

            assertEquals(listOf("m1", "m2"), taken.map { it.id })
            assertEquals(listOf(RunMessageStatus.DELIVERED, RunMessageStatus.DELIVERED), taken.map { it.status })
            assertEquals(listOf(2000L, 2000L), taken.map { it.deliveredAt })
            assertEquals(taken, repo.list(id))
            assertEquals(emptyList(), repo.take(id, 3000L))
        }

    @Test
    fun `take never returns another process's messages`() =
        runTest {
            val mine = run()
            val other = run()
            repo.tryInsert(message("mine", mine), 10)
            repo.tryInsert(message("other", other), 10)

            assertEquals(listOf("mine"), repo.take(mine, 2000L).map { it.id })
            assertEquals(RunMessageStatus.QUEUED, repo.list(other).single().status)
        }

    @Test
    fun `markUndelivered turns only QUEUED rows UNDELIVERED and returns them, DELIVERED rows keep their status`() =
        runTest {
            val id = run()
            repo.tryInsert(message("read", id), 10)
            repo.take(id, 2000L)
            repo.tryInsert(message("unread", id), 10)

            val marked = repo.markUndelivered(id)

            assertEquals(listOf("unread"), marked.map { it.id })
            assertEquals(RunMessageStatus.UNDELIVERED, marked.single().status)
            assertEquals(
                mapOf("read" to RunMessageStatus.DELIVERED, "unread" to RunMessageStatus.UNDELIVERED),
                repo.list(id).associate { it.id to it.status },
            )
            assertEquals(emptyList(), repo.markUndelivered(id))
        }

    @Test
    fun `record purge and capacity eviction delete the run's messages`() =
        runTest {
            val purged = run()
            repo.tryInsert(message("p1", purged), 10)
            processes.transitionStatus(purged, ProcessStatus.FINISHED, 2, null)
            processes.remove(purged)
            assertEquals(emptyList(), repo.list(purged))

            val evicted = run()
            repo.tryInsert(message("e1", evicted), 10)
            processes.transitionStatus(evicted, ProcessStatus.FINISHED, 2, null)
            // At capacity (one row), the next start evicts the terminal victim.
            processes.obtainProcessId("b", emptyMap(), maxProcesses = 1)
            assertEquals(emptyList(), repo.list(evicted))
        }

    @Test
    fun `take and markUndelivered on an inbox with nothing QUEUED open no write transaction`() =
        runTest {
            val spiedDb = spyk(PersistenceTestSupport.tempDatabase())
            val spiedRepo = RunMessagesRepository(spiedDb)
            val id = ProcessRepository(spiedDb, ProcessJobRegistry()).obtainProcessId("a", emptyMap(), 100).processId

            assertEquals(emptyList(), spiedRepo.take(id, 2000L))
            assertEquals(emptyList(), spiedRepo.markUndelivered(id))
            assertEquals(emptyList(), spiedRepo.take(9999, 2000L))
            // obtainProcessId is the only write so far.
            coVerify(exactly = 1) { spiedDb.query<Any?>(any()) }

            spiedRepo.tryInsert(message("m1", id), 10)
            assertEquals(listOf("m1"), spiedRepo.take(id, 2000L).map { it.id })
            coVerify(exactly = 3) { spiedDb.query<Any?>(any()) }
        }

    @Test
    fun `markUndeliveredForEndedRuns marks QUEUED rows of ended or missing runs UNDELIVERED and spares active runs`() =
        runTest {
            val working = run()
            val created = run(ProcessStatus.CREATED)
            val ended = run()
            val gone = run()
            repo.tryInsert(message("w", working), 10)
            repo.tryInsert(message("c", created), 10)
            repo.tryInsert(message("e-read", ended), 10)
            repo.take(ended, 2000L)
            repo.tryInsert(message("e-queued", ended), 10)
            repo.tryInsert(message("g", gone), 10)
            // The run ended (or its row vanished) without its inbox being closed.
            processes.transitionStatus(ended, ProcessStatus.FINISHED, 2, null)
            db.query { Processes.deleteWhere { Processes.id eq gone } }

            assertEquals(2, repo.markUndeliveredForEndedRuns())

            assertEquals(RunMessageStatus.QUEUED, repo.list(working).single().status)
            assertEquals(RunMessageStatus.QUEUED, repo.list(created).single().status)
            assertEquals(
                mapOf("e-read" to RunMessageStatus.DELIVERED, "e-queued" to RunMessageStatus.UNDELIVERED),
                repo.list(ended).associate { it.id to it.status },
            )
            assertEquals(RunMessageStatus.UNDELIVERED, repo.list(gone).single().status)
            assertEquals(0, repo.markUndeliveredForEndedRuns())
        }
}
