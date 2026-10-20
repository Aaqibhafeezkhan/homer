package homer.app.services.persistence

import homer.app.services.Dispatch
import homer.app.services.DispatchStatus
import homer.app.services.QuestionsService.Question
import homer.app.services.QuestionsService.QuestionStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DispatchRepositoryTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: DispatchRepository
    private lateinit var dbPath: String

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        dbPath = dir.resolve("d.db").toString()
        db = DatabaseService(dbPath, LoggerFactory.getLogger("t"))
        db.migrate()
        repo = DispatchRepository(db)
    }

    private fun question(
        id: String,
        pid: Int = 1,
    ) = Question(id, pid, "asker", "text", emptyList(), QuestionStatus.OPEN, createdAt = 1, dispatchBound = true)

    private fun dispatch(
        qid: String,
        expiresAt: Long? = null,
        owner: String? = null,
    ) = Dispatch(0, qid, DispatchStatus.PENDING, "handler", "answer", mapOf("p1" to "v1"), 1, "asker", expiresAt, 1, owner = owner)

    @Test
    fun `create inserts question and dispatch atomically and returns id`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1"), maxPending = 10)
            assertTrue(id != null && id > 0)
            val found = repo.findByQuestionId("q1")!!
            assertEquals("handler", found.agentName)
            assertEquals(mapOf("p1" to "v1"), found.params)
            assertEquals(true, QuestionsRepository(db).get("q1")!!.dispatchBound)
        }

    @Test
    fun `create round-trips owner`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1", owner = "ci"), maxPending = 10)!!
            assertEquals("ci", repo.get(id)!!.owner)
        }

    @Test
    fun `create returns null when at pending cap`() =
        runTest {
            assertTrue(repo.create(question("q1"), dispatch("q1"), maxPending = 1) != null)
            assertNull(repo.create(question("q2", pid = 1), dispatch("q2"), maxPending = 1))
            assertNull(QuestionsRepository(db).get("q2"))
        }

    @Test
    fun `claim transitions PENDING to DISPATCHING once`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1"), 10)!!
            assertTrue(repo.claim(id))
            assertEquals(DispatchStatus.DISPATCHING, repo.get(id)!!.status)
            assertFalse(repo.claim(id))
        }

    @Test
    fun `claim refuses an expired PENDING dispatch`() =
        runTest {
            val repoAtT = DispatchRepository(db, nowSec = { 1_000 })
            val id = repoAtT.create(question("q1"), dispatch("q1", expiresAt = 500), 10)!!
            assertFalse(repoAtT.claim(id), "a lapsed-TTL dispatch must not be claimable/fireable")
            assertEquals(DispatchStatus.PENDING, repoAtT.get(id)!!.status)
        }

    @Test
    fun `claim allows a not-yet-expired PENDING dispatch`() =
        runTest {
            val repoAtT = DispatchRepository(db, nowSec = { 1_000 })
            val id = repoAtT.create(question("q1"), dispatch("q1", expiresAt = 5_000), 10)!!
            assertTrue(repoAtT.claim(id))
        }

    @Test
    fun `markDispatched only transitions from DISPATCHING`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1"), 10)!!
            // Cannot dispatch a still-PENDING (unclaimed) dispatch.
            assertFalse(repo.markDispatched(id, processId = 42, at = 5))
            assertTrue(repo.claim(id))
            assertTrue(repo.markDispatched(id, processId = 42, at = 5))
            val d = repo.get(id)!!
            assertEquals(DispatchStatus.DISPATCHED, d.status)
            assertEquals(42, d.dispatchedProcessId)
            assertFalse(repo.markFailed(id, "x", at = 6))
            assertEquals(DispatchStatus.DISPATCHED, repo.get(id)!!.status)
        }

    @Test
    fun `cancelWithQuestion cancels the dispatch and deletes its question atomically`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1"), 10)!!
            val cancelled = repo.cancelWithQuestion(id)!!
            assertEquals(DispatchStatus.CANCELLED, cancelled.status)
            assertEquals(DispatchStatus.CANCELLED, repo.get(id)!!.status)
            // The bound question is deleted (not just expired) so these rows don't leak.
            assertNull(QuestionsRepository(db).get("q1"))
        }

    @Test
    fun `markDispatched deletes the bound question`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1"), 10)!!
            repo.claim(id)
            assertTrue(repo.markDispatched(id, processId = 42, at = 5))
            assertNull(QuestionsRepository(db).get("q1"))
            // The dispatch row itself is retained as the durable record.
            assertEquals(DispatchStatus.DISPATCHED, repo.get(id)!!.status)
        }

    @Test
    fun `markFailed deletes the bound question`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1"), 10)!!
            repo.claim(id)
            assertTrue(repo.markFailed(id, "boom", at = 5))
            assertNull(QuestionsRepository(db).get("q1"))
            assertEquals(DispatchStatus.FAILED, repo.get(id)!!.status)
        }

    @Test
    fun `cancelWithQuestion returns null when not pending`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1"), 10)!!
            repo.claim(id)
            assertNull(repo.cancelWithQuestion(id))
            assertEquals(DispatchStatus.DISPATCHING, repo.get(id)!!.status)
            assertEquals(QuestionStatus.OPEN, QuestionsRepository(db).get("q1")!!.status)
        }

    @Test
    fun `failOrphanedDispatching marks DISPATCHING dispatches FAILED and deletes their questions`() =
        runTest {
            val orphan = repo.create(question("q1"), dispatch("q1"), 10)!!
            repo.claim(orphan)
            val pending = repo.create(question("q2"), dispatch("q2"), 10)!!
            val failed = repo.failOrphanedDispatching(at = 7)
            assertEquals(listOf(orphan), failed.map { it.id })
            assertEquals(DispatchStatus.FAILED, repo.get(orphan)!!.status)
            assertNull(QuestionsRepository(db).get("q1"))
            // The still-PENDING dispatch and its question are untouched.
            assertEquals(DispatchStatus.PENDING, repo.get(pending)!!.status)
            assertEquals(QuestionStatus.OPEN, QuestionsRepository(db).get("q2")!!.status)
        }

    @Test
    fun `listPendingAnswered returns pending dispatches with their question answer`() =
        runTest {
            val id = repo.create(question("q1"), dispatch("q1"), 10)!!
            QuestionsRepository(db).markAnswered("q1", "yes", 9)
            val pending = repo.listPendingAnswered()
            assertEquals(listOf(id), pending.map { it.dispatch.id })
            assertEquals("yes", pending.single().answer)
        }

    @Test
    fun `expirePending cancels lapsed dispatches and deletes their questions`() =
        runTest {
            repo.create(question("q1"), dispatch("q1", expiresAt = 100), 10)
            repo.create(question("q2"), dispatch("q2", expiresAt = null), 10)
            val cancelled = repo.expirePending(now = 200)
            assertEquals(listOf("q1"), cancelled.map { it.questionId })
            assertEquals(DispatchStatus.CANCELLED, repo.findByQuestionId("q1")!!.status)
            assertNull(QuestionsRepository(db).get("q1"))
            assertEquals(DispatchStatus.PENDING, repo.findByQuestionId("q2")!!.status)
            assertEquals(QuestionStatus.OPEN, QuestionsRepository(db).get("q2")!!.status)
        }

    // --- Idle sweep takes no write lock ---

    @Test
    fun `expirePending with no due row returns promptly while another connection holds the write lock`() =
        runTest {
            repo.create(question("q1"), dispatch("q1", expiresAt = null), 10)
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
                writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                var result: List<Dispatch> = emptyList()
                val elapsedMs = measureTimeMillis { result = repo.expirePending(now = 999_999) }
                writer.createStatement().use { it.execute("ROLLBACK") }

                // A sweep that took the write lock would sit out the whole 5 s busy_timeout.
                assertTrue(elapsedMs < 2_000, "idle sweep waited $elapsedMs ms behind the writer")
                assertTrue(result.isEmpty())
            }
        }

    @Test
    fun `expirePending does not cancel a dispatch no longer PENDING even though it is past its TTL`() =
        runTest {
            // Simulates a dispatch claimed for firing between the pre-check and the write: the
            // in-transaction predicate (status = PENDING) still guards it, so it is left alone.
            val repoAtT = DispatchRepository(db, nowSec = { 1_000 })
            val id = repoAtT.create(question("q1"), dispatch("q1", expiresAt = 5_000), 10)!!
            assertTrue(repoAtT.claim(id))
            val cancelled = repoAtT.expirePending(now = 6_000)
            assertTrue(cancelled.isEmpty())
            assertEquals(DispatchStatus.DISPATCHING, repoAtT.get(id)!!.status)
            assertEquals(QuestionStatus.OPEN, QuestionsRepository(db).get("q1")!!.status)
        }

    // --- Settled-row retention and the paginated list ---

    private suspend fun seed(
        qid: String,
        status: DispatchStatus,
        createdAt: Long,
        agentName: String = "handler",
        owner: String? = null,
    ): Int =
        repo.create(
            question(qid),
            Dispatch(0, qid, status, agentName, "answer", emptyMap(), 1, "asker", null, createdAt, owner = owner),
            maxPending = 1_000,
        )!!

    @Test
    fun `pruneSettled deletes an old settled dispatch of each status with its bound question`() =
        runTest {
            val settled = listOf(DispatchStatus.DISPATCHED, DispatchStatus.FAILED, DispatchStatus.CANCELLED)
            val ids = settled.map { seed("q-${it.name}", it, createdAt = 100) }

            assertEquals(3, repo.pruneSettled(cutoff = 1_000))

            ids.forEach { assertNull(repo.get(it)) }
            settled.forEach { assertNull(QuestionsRepository(db).get("q-${it.name}")) }
        }

    @Test
    fun `pruneSettled never deletes a PENDING or DISPATCHING dispatch however old`() =
        runTest {
            val pending = seed("q-pending", DispatchStatus.PENDING, createdAt = 1)
            val dispatching = seed("q-dispatching", DispatchStatus.DISPATCHING, createdAt = 1)

            assertEquals(0, repo.pruneSettled(cutoff = Long.MAX_VALUE))

            assertEquals(DispatchStatus.PENDING, repo.get(pending)!!.status)
            assertEquals(DispatchStatus.DISPATCHING, repo.get(dispatching)!!.status)
            assertEquals(QuestionStatus.OPEN, QuestionsRepository(db).get("q-pending")!!.status)
            assertEquals(QuestionStatus.OPEN, QuestionsRepository(db).get("q-dispatching")!!.status)
        }

    @Test
    fun `pruneSettled keeps a settled dispatch created inside the window`() =
        runTest {
            val atCutoff = seed("q-at", DispatchStatus.FAILED, createdAt = 1_000)
            val newer = seed("q-new", DispatchStatus.DISPATCHED, createdAt = 1_500)

            assertEquals(0, repo.pruneSettled(cutoff = 1_000))

            assertEquals(DispatchStatus.FAILED, repo.get(atCutoff)!!.status)
            assertEquals(DispatchStatus.DISPATCHED, repo.get(newer)!!.status)
        }

    @Test
    fun `pruneSettled leaves process-scoped questions alone`() =
        runTest {
            seed("q-old", DispatchStatus.CANCELLED, createdAt = 1)
            // A process-scoped question is never a dispatch's; the prune's guard is dispatch_bound = 1.
            QuestionsRepository(db).tryInsert(
                Question("plain", 1, "asker", "text", emptyList(), QuestionStatus.OPEN, createdAt = 1),
                maxOpenPerProcess = 10,
            )

            assertEquals(1, repo.pruneSettled(cutoff = 1_000))

            assertEquals(QuestionStatus.OPEN, QuestionsRepository(db).get("plain")!!.status)
        }

    @Test
    fun `pruneSettled drains more rows than one batch across several transactions`() =
        runTest {
            repeat(5) { seed("q$it", DispatchStatus.DISPATCHED, createdAt = 1) }
            val keep = seed("q-pending", DispatchStatus.PENDING, createdAt = 1)

            assertEquals(5, repo.pruneSettled(cutoff = 1_000, batchSize = 2))

            assertEquals(listOf(keep), repo.page(null, null, null, null, 0, 100).items.map { it.id })
        }

    @Test
    fun `pruneSettled with nothing due returns promptly while another connection holds the write lock`() =
        runTest {
            // Old but unsettled, and settled but new: neither is due.
            seed("q-pending", DispatchStatus.PENDING, createdAt = 1)
            seed("q-new", DispatchStatus.FAILED, createdAt = 5_000)
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
                writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                var pruned = -1
                val elapsedMs = measureTimeMillis { pruned = repo.pruneSettled(cutoff = 1_000) }
                writer.createStatement().use { it.execute("ROLLBACK") }

                // A prune that took the write lock would sit out the whole 5 s busy_timeout.
                assertTrue(elapsedMs < 2_000, "idle prune waited $elapsedMs ms behind the writer")
                assertEquals(0, pruned)
            }
        }

    @Test
    fun `page returns newest first with the total before paging`() =
        runTest {
            val ids = (1..5).map { seed("q$it", DispatchStatus.PENDING, createdAt = it.toLong()) }

            val first = repo.page(null, null, null, null, offset = 0, limit = 2)
            assertEquals(ids.reversed().take(2), first.items.map { it.id })
            assertEquals(5, first.total)
            val last = repo.page(null, null, null, null, offset = 4, limit = 2)
            assertEquals(listOf(ids.first()), last.items.map { it.id })
            val past = repo.page(null, null, null, null, offset = 9, limit = 2)
            assertTrue(past.items.isEmpty())
            assertEquals(5, past.total)
        }

    @Test
    fun `page applies status and agentName filters before counting`() =
        runTest {
            seed("q1", DispatchStatus.PENDING, createdAt = 1, agentName = "a")
            val failed = seed("q2", DispatchStatus.FAILED, createdAt = 1, agentName = "a")
            seed("q3", DispatchStatus.FAILED, createdAt = 1, agentName = "b")

            val page = repo.page(DispatchStatus.FAILED, "a", null, null, offset = 0, limit = 10)

            assertEquals(listOf(failed), page.items.map { it.id })
            assertEquals(1, page.total)
        }

    @Test
    fun `page for a scoped caller neither returns nor counts a row it cannot see`() =
        runTest {
            val own = seed("q1", DispatchStatus.PENDING, createdAt = 1, agentName = "other", owner = "alice")
            val granted = seed("q2", DispatchStatus.PENDING, createdAt = 2, agentName = "deploy", owner = "bob")
            // Newest, and invisible to alice: a leak would put it first on the page or in the total.
            seed("q3", DispatchStatus.PENDING, createdAt = 3, agentName = "other", owner = "mallory")

            val page = repo.page(null, null, visibleOwner = "alice", visibleAgents = setOf("deploy"), offset = 0, limit = 1)

            assertEquals(listOf(granted), page.items.map { it.id })
            assertEquals(2, page.total)
            val rest = repo.page(null, null, visibleOwner = "alice", visibleAgents = setOf("deploy"), offset = 1, limit = 1)
            assertEquals(listOf(own), rest.items.map { it.id })
            // No grant and no ownership: nothing, and a zero total.
            val stranger = repo.page(null, null, visibleOwner = "eve", visibleAgents = emptySet(), offset = 0, limit = 10)
            assertTrue(stranger.items.isEmpty())
            assertEquals(0, stranger.total)
        }
}
