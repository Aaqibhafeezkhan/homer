package homer.app.services.persistence

import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiredRecordsTest {
    private lateinit var db: DatabaseService
    private lateinit var records: FiredRecords<Dispatches>
    private var nextQuestion = 0

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        db = DatabaseService(dir.resolve("f.db").toString(), LoggerFactory.getLogger("t"))
        db.migrate()
        records = FiredRecords(db, Dispatches, listOf("PENDING", "DISPATCHING", "DONE", "GONE"), nowSec = { 100 })
    }

    private suspend fun row(
        status: String,
        createdAt: Long = 1,
        expiresAt: Long? = null,
        agent: String = "a",
        owner: String? = null,
    ): Int =
        db.query {
            Dispatches.insert {
                it[questionId] = "q${nextQuestion++}"
                it[Dispatches.status] = status
                it[agentName] = agent
                it[answerParam] = "x"
                it[params] = "{}"
                it[originProcessId] = 1
                it[originAgentName] = "o"
                it[Dispatches.createdAt] = createdAt
                it[Dispatches.expiresAt] = expiresAt
                it[Dispatches.owner] = owner
            }[Dispatches.id]
        }

    private suspend fun statuses(): List<String> = db.read { Dispatches.selectAll().map { it[Dispatches.status] } }

    @Test
    fun `the PENDING cap counts PENDING rows only`() =
        runTest {
            row("PENDING")
            row("DONE")
            assertFalse(db.query { records.pendingCapReachedTx(2) })
            assertTrue(db.query { records.pendingCapReachedTx(1) })
        }

    @Test
    fun `claim takes a live PENDING row once and refuses one whose TTL has run out`() =
        runTest {
            val live = row("PENDING", expiresAt = 101)
            val lapsed = row("PENDING", expiresAt = 100)
            val open = row("PENDING")
            assertTrue(records.claim(live))
            assertFalse(records.claim(live))
            assertFalse(records.claim(lapsed))
            assertTrue(records.claim(open))
        }

    @Test
    fun `anyDue reports whether a PENDING row has lapsed`() =
        runTest {
            row("PENDING", expiresAt = 50)
            row("DONE", expiresAt = 10)
            assertTrue(records.anyDue(records.lapsedAt(50)))
            assertFalse(records.anyDue(records.lapsedAt(49)))
        }

    @Test
    fun `pruneSettled deletes only the settled statuses, dependents first, batch by batch`() =
        runTest {
            row("PENDING")
            row("DISPATCHING")
            repeat(3) { row("DONE") }
            row("GONE")
            row("DONE", createdAt = 200)
            val batches = mutableListOf<List<String>>()
            val remainingAtCallback = mutableListOf<Long>()

            val deleted =
                records.pruneSettled(cutoff = 100, batchSize = 2, dependentColumns = listOf(Dispatches.questionId)) { batch ->
                    batches += batch.map { it[Dispatches.questionId] }
                    remainingAtCallback += Dispatches.selectAll().count()
                }

            assertEquals(4, deleted)
            assertEquals(listOf(listOf("q2", "q3"), listOf("q4", "q5")), batches)
            // Each batch's dependents go first, while its rows still exist, in the batch's own transaction.
            assertEquals(listOf(7L, 5L), remainingAtCallback)
            assertEquals(listOf("PENDING", "DISPATCHING", "DONE"), statuses())
        }

    @Test
    fun `pruneSettled with nothing due deletes nothing and calls no dependent delete`() =
        runTest {
            row("PENDING")
            row("DONE", createdAt = 200)
            var called = false
            assertEquals(0, records.pruneSettled(cutoff = 100, batchSize = 2, dependentColumns = emptyList()) { called = true })
            assertFalse(called)
            assertEquals(2, statuses().size)
        }

    @Test
    fun `page maps only the requested rows, newest first, and counts every visible match`() =
        runTest {
            val first = row("DONE", owner = "alice")
            row("DONE", agent = "b")
            val third = row("PENDING", agent = "c", owner = "alice")
            val mapped = mutableListOf<List<Int>>()
            val toIds = { rows: List<ResultRow> -> rows.map { it[Dispatches.id] }.also { mapped += it } }

            val page = records.page(null, null, "alice", emptySet(), offset = 0, limit = 1, toRecords = toIds)
            assertEquals(RecordPage(listOf(third), 2), page)
            assertEquals(RecordPage(listOf(first), 1), records.page("DONE", null, "alice", emptySet(), 0, 10, toIds))

            val pastTheEnd = records.page(null, null, null, null, offset = 3, limit = 10, toRecords = toIds)
            assertEquals(RecordPage(emptyList(), 3), pastTheEnd)
            assertEquals(2, mapped.size)
        }

    @Test
    fun `page for a scoped caller with no owner sees only its agents, and with neither sees nothing`() =
        runTest {
            row("DONE", owner = "alice")
            val agentRow = row("DONE", agent = "b")
            val toIds = { rows: List<ResultRow> -> rows.map { it[Dispatches.id] } }

            assertEquals(RecordPage(listOf(agentRow), 1), records.page(null, null, null, setOf("b"), 0, 10, toIds))
            assertEquals(RecordPage(emptyList(), 0), records.page(null, null, null, emptySet(), 0, 10, toIds))
        }
}
