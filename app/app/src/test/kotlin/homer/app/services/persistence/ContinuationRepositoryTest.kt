package homer.app.services.persistence

import homer.app.fixtures.SqlStatementRecorder
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import homer.app.services.Continuation
import homer.app.services.ContinuationKind
import homer.app.services.ContinuationStatus
import homer.app.services.JoinWatch
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

class ContinuationRepositoryTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: ContinuationRepository
    private lateinit var dbPath: String

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        dbPath = dir.resolve("d.db").toString()
        db = DatabaseService(dbPath, LoggerFactory.getLogger("t"))
        db.migrate()
        repo = ContinuationRepository(db)
    }

    private fun continuation(
        watched: Int,
        expiresAt: Long? = null,
        transformer: ResultEnvParam? = null,
        owner: String? = null,
    ) = Continuation(
        id = 0,
        watchedProcessId = watched,
        status = ContinuationStatus.PENDING,
        targetAgentName = "handler",
        params = mapOf("p1" to "v1"),
        resumeParam = "HMR_LG_RESUME",
        resultTransformer = transformer,
        originProcessId = 1,
        originAgentName = "asker",
        expiresAt = expiresAt,
        createdAt = 1,
        owner = owner,
    )

    @Test
    fun `create inserts and returns id, round-tripping params and transformer`() =
        runTest {
            val transformer = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.x"))
            val id = repo.create(continuation(42, transformer = transformer), maxPending = 10)
            assertTrue(id != null && id > 0)
            val found = repo.get(id)!!
            assertEquals("handler", found.targetAgentName)
            assertEquals(mapOf("p1" to "v1"), found.params)
            assertEquals(transformer, found.resultTransformer)
            assertEquals(42, found.watchedProcessId)
        }

    @Test
    fun `create round-trips owner`() =
        runTest {
            val id = repo.create(continuation(42, owner = "ci"), maxPending = 10)!!
            assertEquals("ci", repo.get(id)!!.owner)
        }

    @Test
    fun `create returns null when at pending cap`() =
        runTest {
            assertTrue(repo.create(continuation(1), maxPending = 1) != null)
            assertNull(repo.create(continuation(2), maxPending = 1))
        }

    @Test
    fun `findByWatchedProcessId returns only PENDING continuations for that process`() =
        runTest {
            val a = repo.create(continuation(7), 10)!!
            repo.create(continuation(8), 10)
            val claimed = repo.create(continuation(7), 10)!!
            repo.claim(claimed)
            val pending = repo.findByWatchedProcessId(7)
            assertEquals(listOf(a), pending.map { it.id })
        }

    @Test
    fun `claim transitions PENDING to DISPATCHING once`() =
        runTest {
            val id = repo.create(continuation(7), 10)!!
            assertTrue(repo.claim(id))
            assertEquals(ContinuationStatus.DISPATCHING, repo.get(id)!!.status)
            assertFalse(repo.claim(id))
        }

    @Test
    fun `claim refuses an expired PENDING continuation`() =
        runTest {
            val repoAtT = ContinuationRepository(db, nowSec = { 1_000 })
            val id = repoAtT.create(continuation(7, expiresAt = 500), 10)!!
            assertFalse(repoAtT.claim(id), "a lapsed-TTL continuation must not be claimable/fireable")
            assertEquals(ContinuationStatus.PENDING, repoAtT.get(id)!!.status)
        }

    @Test
    fun `claim allows a not-yet-expired PENDING continuation`() =
        runTest {
            val repoAtT = ContinuationRepository(db, nowSec = { 1_000 })
            val id = repoAtT.create(continuation(7, expiresAt = 5_000), 10)!!
            assertTrue(repoAtT.claim(id))
        }

    @Test
    fun `markDispatched and markFailed only transition from DISPATCHING`() =
        runTest {
            val id = repo.create(continuation(7), 10)!!
            assertFalse(repo.markDispatched(id, processId = 42, at = 5))
            assertTrue(repo.claim(id))
            assertTrue(repo.markDispatched(id, processId = 42, at = 5))
            val c = repo.get(id)!!
            assertEquals(ContinuationStatus.DISPATCHED, c.status)
            assertEquals(42, c.firedProcessId)
            assertEquals(5, c.firedAt)
            assertFalse(repo.markFailed(id, "x", at = 6))
            assertEquals(ContinuationStatus.DISPATCHED, repo.get(id)!!.status)
        }

    @Test
    fun `cancel cancels a PENDING continuation and returns null otherwise`() =
        runTest {
            val id = repo.create(continuation(7), 10)!!
            assertEquals(ContinuationStatus.CANCELLED, repo.cancel(id)!!.status)
            assertEquals(ContinuationStatus.CANCELLED, repo.get(id)!!.status)
            assertNull(repo.cancel(id))
        }

    @Test
    fun `expirePending moves lapsed PENDING continuations to EXPIRED`() =
        runTest {
            repo.create(continuation(1, expiresAt = 100), 10)
            repo.create(continuation(2, expiresAt = null), 10)
            val expired = repo.expirePending(now = 200)
            assertEquals(listOf(1), expired.map { it.watchedProcessId })
            assertEquals(ContinuationStatus.EXPIRED, repo.list(ContinuationStatus.EXPIRED).single().status)
            assertEquals(ContinuationStatus.PENDING, repo.findByWatchedProcessId(2).single().status)
        }

    @Test
    fun `failOrphanedDispatching marks DISPATCHING continuations FAILED`() =
        runTest {
            val orphan = repo.create(continuation(1), 10)!!
            repo.claim(orphan)
            val pending = repo.create(continuation(2), 10)!!
            val failed = repo.failOrphanedDispatching(at = 7)
            assertEquals(listOf(orphan), failed.map { it.id })
            assertEquals(ContinuationStatus.FAILED, repo.get(orphan)!!.status)
            assertEquals(ContinuationStatus.PENDING, repo.get(pending)!!.status)
        }

    private fun joinContinuation(
        expiresAt: Long? = null,
        owner: String? = null,
    ) = Continuation(
        id = 0,
        watchedProcessId = 0,
        status = ContinuationStatus.PENDING,
        targetAgentName = "handler",
        params = mapOf("HMR_LG_THREAD" to "t-1"),
        resumeParam = "HMR_LG_RESUME",
        resultTransformer = null,
        originProcessId = 1,
        originAgentName = "asker",
        expiresAt = expiresAt,
        createdAt = 1,
        kind = ContinuationKind.JOIN,
        owner = owner,
    )

    @Test
    fun `createJoin round-trips kind, sentinel watched id, and watches`() =
        runTest {
            val transformer = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.x"))
            val watches =
                listOf(
                    JoinWatch(watchedProcessId = 10, interruptId = "int-a", resultTransformer = transformer),
                    JoinWatch(watchedProcessId = 11, interruptId = "int-b", resultTransformer = null),
                )
            val id = repo.createJoin(joinContinuation(), watches, maxPending = 10)!!
            val found = repo.get(id)!!
            assertEquals(ContinuationKind.JOIN, found.kind)
            assertEquals(0, found.watchedProcessId)
            assertEquals("handler", found.targetAgentName)
            assertEquals(watches.toSet(), found.watches.toSet())
        }

    @Test
    fun `createJoin round-trips owner`() =
        runTest {
            val id = repo.createJoin(joinContinuation(owner = "ci"), emptyList(), maxPending = 10)!!
            assertEquals("ci", repo.get(id)!!.owner)
        }

    @Test
    fun `createJoin returns null at pending cap and inserts no watch rows`() =
        runTest {
            assertTrue(repo.create(continuation(1), maxPending = 1) != null)
            val watches = listOf(JoinWatch(watchedProcessId = 10, interruptId = "int-a"))
            assertNull(repo.createJoin(joinContinuation(), watches, maxPending = 1))
            // The blocked join must not surface as a watcher of process 10.
            assertTrue(repo.findJoinsByWatchedProcessId(10).isEmpty())
        }

    @Test
    fun `findJoinsByWatchedProcessId finds the join for each member and skips the single trigger`() =
        runTest {
            val watches =
                listOf(
                    JoinWatch(watchedProcessId = 10, interruptId = "int-a"),
                    JoinWatch(watchedProcessId = 11, interruptId = "int-b"),
                )
            val id = repo.createJoin(joinContinuation(), watches, maxPending = 10)!!
            assertEquals(listOf(id), repo.findJoinsByWatchedProcessId(10).map { it.id })
            assertEquals(listOf(id), repo.findJoinsByWatchedProcessId(11).map { it.id })
            // A real member pid (>=1) is never matched by the single-watch trigger; the join's
            // sentinel watched id (0) is unreachable from onProcessTerminal since ids start at 1.
            assertTrue(repo.findByWatchedProcessId(10).isEmpty())
        }

    @Test
    fun `findJoinsByWatchedProcessId excludes joins no longer PENDING`() =
        runTest {
            val watches = listOf(JoinWatch(watchedProcessId = 10, interruptId = "int-a"))
            val id = repo.createJoin(joinContinuation(), watches, maxPending = 10)!!
            repo.claim(id)
            assertTrue(repo.findJoinsByWatchedProcessId(10).isEmpty())
        }

    @Test
    fun `list filters by status and agent name`() =
        runTest {
            val id = repo.create(continuation(1), 10)!!
            repo.claim(id)
            assertEquals(1, repo.list(ContinuationStatus.DISPATCHING).size)
            assertEquals(0, repo.list(ContinuationStatus.PENDING).size)
            assertEquals(1, repo.page(null, "handler", null, null, 0, 100).items.size)
            assertEquals(0, repo.page(null, "other", null, null, 0, 100).items.size)
        }

    // --- transient-failure retry ---

    @Test
    fun `releaseForRetry returns a DISPATCHING continuation to PENDING with the attempt counted`() =
        runTest {
            val id = repo.create(continuation(7), 10)!!
            repo.claim(id)

            assertTrue(repo.releaseForRetry(id, "cost cap exceeded", attempts = 1, nextAttemptAt = 500))

            val row = repo.get(id)!!
            assertEquals(ContinuationStatus.PENDING, row.status)
            assertEquals(1, row.fireAttempts)
            assertEquals("cost cap exceeded", row.error)
        }

    @Test
    fun `releaseForRetry only applies to a DISPATCHING row`() =
        runTest {
            val id = repo.create(continuation(7), 10)!!
            assertFalse(repo.releaseForRetry(id, "x", attempts = 1, nextAttemptAt = 500))
        }

    @Test
    fun `findDueRetries returns only released rows whose retry is due and not lapsed`() =
        runTest {
            val due = repo.create(continuation(1), 10)!!
            val notYet = repo.create(continuation(2), 10)!!
            val neverRetried = repo.create(continuation(3), 10)!!
            val lapsed = repo.create(continuation(4, expiresAt = 100), 10)!!
            listOf(due, notYet, lapsed).forEach { repo.claim(it) }
            repo.releaseForRetry(due, "x", attempts = 1, nextAttemptAt = 400)
            repo.releaseForRetry(notYet, "x", attempts = 1, nextAttemptAt = 900)
            repo.releaseForRetry(lapsed, "x", attempts = 1, nextAttemptAt = 400)

            val ids = repo.findDueRetries(now = 500).map { it.id }

            assertEquals(listOf(due), ids)
            // A row that never failed transiently has no next_attempt_at and is never swept.
            assertEquals(ContinuationStatus.PENDING, repo.get(neverRetried)!!.status)
        }

    // --- Idle sweep takes no write lock ---

    @Test
    fun `expirePending with no due row returns promptly while another connection holds the write lock`() =
        runTest {
            repo.create(continuation(1, expiresAt = null), 10)
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
                writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                var result: List<Continuation> = emptyList()
                val elapsedMs = measureTimeMillis { result = repo.expirePending(now = 999_999) }
                writer.createStatement().use { it.execute("ROLLBACK") }

                // A sweep that took the write lock would sit out the whole 5 s busy_timeout.
                assertTrue(elapsedMs < 2_000, "idle sweep waited $elapsedMs ms behind the writer")
                assertTrue(result.isEmpty())
            }
        }

    @Test
    fun `expirePending does not expire a row no longer PENDING even though it is past its TTL`() =
        runTest {
            // Simulates a row claimed for firing between the pre-check and the write: the
            // in-transaction predicate (status = PENDING) still guards it, so it is left alone.
            val repoAtT = ContinuationRepository(db, nowSec = { 1_000 })
            val id = repoAtT.create(continuation(1, expiresAt = 5_000), 10)!!
            assertTrue(repoAtT.claim(id))
            val expired = repoAtT.expirePending(now = 6_000)
            assertTrue(expired.isEmpty())
            assertEquals(ContinuationStatus.DISPATCHING, repoAtT.get(id)!!.status)
        }

    // --- Settled-row retention and the paginated list ---

    private suspend fun seed(
        status: ContinuationStatus,
        createdAt: Long,
        agentName: String = "handler",
        owner: String? = null,
    ): Int =
        repo.create(
            continuation(42, owner = owner).copy(status = status, createdAt = createdAt, targetAgentName = agentName),
            maxPending = 1_000,
        )!!

    private suspend fun seedJoin(
        status: ContinuationStatus,
        createdAt: Long,
    ): Int =
        repo.createJoin(
            continuation(0).copy(status = status, createdAt = createdAt),
            listOf(JoinWatch(10, "i-10"), JoinWatch(11, "i-11")),
            maxPending = 1_000,
        )!!

    private fun watchRows(continuationId: Int): Int =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM continuation_watches WHERE continuation_id = $continuationId").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    @Test
    fun `pruneSettled deletes an old settled continuation of each status with its watch rows`() =
        runTest {
            val settled =
                listOf(ContinuationStatus.DISPATCHED, ContinuationStatus.FAILED, ContinuationStatus.CANCELLED, ContinuationStatus.EXPIRED)
            val singles = settled.map { seed(it, createdAt = 100) }
            val joins = settled.map { seedJoin(it, createdAt = 100) }
            joins.forEach { assertEquals(2, watchRows(it)) }

            assertEquals(8, repo.pruneSettled(cutoff = 1_000))

            (singles + joins).forEach { assertNull(repo.get(it)) }
            joins.forEach { assertEquals(0, watchRows(it)) }
        }

    @Test
    fun `pruneSettled never deletes a PENDING or DISPATCHING continuation however old`() =
        runTest {
            val pending = seedJoin(ContinuationStatus.PENDING, createdAt = 1)
            val dispatching = seed(ContinuationStatus.DISPATCHING, createdAt = 1)

            assertEquals(0, repo.pruneSettled(cutoff = Long.MAX_VALUE))

            assertEquals(ContinuationStatus.PENDING, repo.get(pending)!!.status)
            assertEquals(2, watchRows(pending))
            assertEquals(ContinuationStatus.DISPATCHING, repo.get(dispatching)!!.status)
        }

    @Test
    fun `pruneSettled keeps a settled continuation created inside the window`() =
        runTest {
            val atCutoff = seed(ContinuationStatus.EXPIRED, createdAt = 1_000)
            val newer = seedJoin(ContinuationStatus.DISPATCHED, createdAt = 1_500)

            assertEquals(0, repo.pruneSettled(cutoff = 1_000))

            assertEquals(ContinuationStatus.EXPIRED, repo.get(atCutoff)!!.status)
            assertEquals(ContinuationStatus.DISPATCHED, repo.get(newer)!!.status)
            assertEquals(2, watchRows(newer))
        }

    @Test
    fun `pruneSettled drains more rows than one batch across several transactions`() =
        runTest {
            repeat(5) { seedJoin(ContinuationStatus.FAILED, createdAt = 1) }
            val keep = seed(ContinuationStatus.PENDING, createdAt = 1)

            assertEquals(5, repo.pruneSettled(cutoff = 1_000, batchSize = 2))

            assertEquals(listOf(keep), repo.page(null, null, null, null, 0, 100).items.map { it.id })
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM continuation_watches").use {
                        it.next()
                        assertEquals(0, it.getInt(1))
                    }
                }
            }
        }

    @Test
    fun `pruneSettled with nothing due returns promptly while another connection holds the write lock`() =
        runTest {
            // Old but unsettled, and settled but new: neither is due.
            seed(ContinuationStatus.PENDING, createdAt = 1)
            seed(ContinuationStatus.EXPIRED, createdAt = 5_000)
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
    fun `page returns newest first with the total before paging and carries join watches`() =
        runTest {
            val ids = (1..4).map { seed(ContinuationStatus.PENDING, createdAt = it.toLong()) }
            val join = seedJoin(ContinuationStatus.PENDING, createdAt = 5)

            val first = repo.page(null, null, null, null, offset = 0, limit = 2)
            assertEquals(listOf(join, ids.last()), first.items.map { it.id })
            assertEquals(
                2,
                first.items
                    .first()
                    .watches.size,
            )
            assertEquals(5, first.total)
            val past = repo.page(null, null, null, null, offset = 9, limit = 2)
            assertTrue(past.items.isEmpty())
            assertEquals(5, past.total)
        }

    // --- Batched JOIN continuation_watches read ---

    @Test
    fun `page maps SINGLE and JOIN rows to identical Continuation objects, watch order included`() =
        runTest {
            val single = repo.create(continuation(7), maxPending = 10)!!
            // Deliberately not in ascending watched-process-id order: watches fire in the
            // caller-supplied (registration) order, not pid order, so the mapping must not
            // silently re-sort them.
            val watches =
                listOf(
                    JoinWatch(watchedProcessId = 30, interruptId = "int-c"),
                    JoinWatch(watchedProcessId = 10, interruptId = "int-a"),
                    JoinWatch(watchedProcessId = 20, interruptId = "int-b"),
                )
            val join = repo.createJoin(joinContinuation(), watches, maxPending = 10)!!

            val page = repo.page(null, null, null, null, offset = 0, limit = 10)

            val joinResult = page.items.first { it.id == join }
            val singleResult = page.items.first { it.id == single }
            assertEquals(watches, joinResult.watches, "watch order must be preserved, not re-sorted by pid")
            assertEquals(emptyList<JoinWatch>(), singleResult.watches)
            // The single-row path (get) shares the same batch helper with one id; both must agree.
            assertEquals(repo.get(join), joinResult)
            assertEquals(repo.get(single), singleResult)
        }

    @Test
    fun `page loads every JOIN row's watches in one batched read, not one query per row`() =
        runTest {
            repeat(5) { i ->
                repo.createJoin(
                    joinContinuation(),
                    listOf(JoinWatch(watchedProcessId = i, interruptId = "int-$i"), JoinWatch(watchedProcessId = i + 100, "int-$i-b")),
                    maxPending = 20,
                )
            }
            SqlStatementRecorder.reset()

            val page = repo.page(null, null, null, null, offset = 0, limit = 10)

            assertEquals(5, page.items.size)
            assertTrue(page.items.all { it.watches.size == 2 })
            val watchReads = SqlStatementRecorder.statements().filter { "FROM continuation_watches" in it }
            // count + page + exactly one watch batch, however many JOIN rows are on the page.
            assertEquals(1, watchReads.size, "$watchReads")
            assertTrue("continuation_id IN (" in watchReads.single(), watchReads.single())
        }

    @Test
    fun `denial_of_service - byThread loads watches past SQLite's parameter limit in 512-id chunks`() =
        runTest {
            // 513 JOIN rows on one thread force two watch-read chunks (512 + 1); a collapsed
            // chunk loop would either throw on the parameter cap or drop the later rows' watches.
            val ids = (1..513).map { i -> repo.createJoin(joinContinuation(), listOf(JoinWatch(i, "int-$i")), maxPending = 1000)!! }
            SqlStatementRecorder.reset()

            val found = repo.byThread("t-1", null)

            assertEquals(ids, found.map { it.id })
            assertTrue(found.all { it.watches.size == 1 })
            val watchReads = SqlStatementRecorder.statements().filter { "FROM continuation_watches" in it }
            assertEquals(2, watchReads.size, "$watchReads")
        }

    @Test
    fun `page issues no continuation_watches read when the page has no JOIN rows`() =
        runTest {
            repo.create(continuation(1), maxPending = 10)
            repo.create(continuation(2), maxPending = 10)
            SqlStatementRecorder.reset()

            repo.page(null, null, null, null, offset = 0, limit = 10)

            assertTrue(SqlStatementRecorder.statements().none { "FROM continuation_watches" in it })
        }

    @Test
    fun `page applies status and agentName filters before counting`() =
        runTest {
            seed(ContinuationStatus.PENDING, createdAt = 1, agentName = "a")
            val failed = seed(ContinuationStatus.FAILED, createdAt = 1, agentName = "a")
            seed(ContinuationStatus.FAILED, createdAt = 1, agentName = "b")

            val page = repo.page(ContinuationStatus.FAILED, "a", null, null, offset = 0, limit = 10)

            assertEquals(listOf(failed), page.items.map { it.id })
            assertEquals(1, page.total)
        }

    @Test
    fun `page for a scoped caller neither returns nor counts a row it cannot see`() =
        runTest {
            val own = seed(ContinuationStatus.PENDING, createdAt = 1, agentName = "other", owner = "alice")
            val granted = seed(ContinuationStatus.PENDING, createdAt = 2, agentName = "deploy", owner = "bob")
            // Newest, and invisible to alice: a leak would put it first on the page or in the total.
            seed(ContinuationStatus.PENDING, createdAt = 3, agentName = "other", owner = "mallory")

            val page = repo.page(null, null, visibleOwner = "alice", visibleAgents = setOf("deploy"), offset = 0, limit = 1)

            assertEquals(listOf(granted), page.items.map { it.id })
            assertEquals(2, page.total)
            val rest = repo.page(null, null, visibleOwner = "alice", visibleAgents = setOf("deploy"), offset = 1, limit = 1)
            assertEquals(listOf(own), rest.items.map { it.id })
            val stranger = repo.page(null, null, visibleOwner = "eve", visibleAgents = emptySet(), offset = 0, limit = 10)
            assertTrue(stranger.items.isEmpty())
            assertEquals(0, stranger.total)
        }

    // --- Continuations by LangGraph thread ---

    private fun storedThreadIds(): Map<Int, String?> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, thread_id FROM continuations ORDER BY id").use { rs ->
                    generateSequence { if (rs.next()) rs.getInt(1) to rs.getString(2) else null }.toMap()
                }
            }
        }

    @Test
    fun `create and createJoin store the params thread id in thread_id, and null without one`() =
        runTest {
            val single = repo.create(continuation(1).copy(params = mapOf("HMR_LG_THREAD" to "t-s", "p" to "v")), 10)!!
            val join = repo.createJoin(joinContinuation(), listOf(JoinWatch(10, "int-a")), 10)!!
            val plain = repo.create(continuation(2), 10)!!

            assertEquals(mapOf(single to "t-s", join to "t-1", plain to null), storedThreadIds())
        }

    @Test
    fun `byThread returns that thread's SINGLE and JOIN continuations of every status in id order, and nothing else`() =
        runTest {
            val t1 = mapOf("HMR_LG_THREAD" to "t-1")
            val single = repo.create(continuation(1).copy(params = t1 + ("HMR_LG_INTERRUPT" to "i1")), 10)!!
            repo.create(continuation(2).copy(params = mapOf("HMR_LG_THREAD" to "t-2")), 10)
            val watches = listOf(JoinWatch(30, "int-c"), JoinWatch(10, "int-a"))
            val join = repo.createJoin(joinContinuation(), watches, 10)!!
            repo.create(continuation(3), 10)
            val fired = repo.createJoin(joinContinuation(), listOf(JoinWatch(40, "int-d")), 10)!!
            repo.claim(fired)
            repo.markDispatched(fired, processId = 99, at = 5)

            val found = repo.byThread("t-1", null)

            assertEquals(listOf(single, join, fired), found.map { it.id })
            // The same objects the other reads map, watch order included.
            assertEquals(listOf(repo.get(single), repo.get(join), repo.get(fired)), found)
            assertEquals(watches, found[1].watches)
            assertEquals(ContinuationStatus.DISPATCHED, found[2].status)
        }

    @Test
    fun `byThread matches the thread id exactly, never as a pattern or an expression`() =
        runTest {
            repo.create(continuation(1).copy(params = mapOf("HMR_LG_THREAD" to "t-1")), 10)
            repo.create(continuation(2), 10)

            assertTrue(repo.byThread("t-%", null).isEmpty())
            assertTrue(repo.byThread("t-1' OR '1'='1", null).isEmpty())
            assertTrue(repo.byThread("", null).isEmpty())
            assertTrue(repo.byThread("T-1", null).isEmpty())
        }

    @Test
    fun `byThread reads the thread's rows and all their join watches in two statements`() =
        runTest {
            repeat(6) { i ->
                repo.createJoin(joinContinuation(), listOf(JoinWatch(i, "int-$i"), JoinWatch(i + 100, "int-$i-b")), 20)
                repo.create(continuation(i).copy(params = mapOf("HMR_LG_THREAD" to "t-1", "HMR_LG_INTERRUPT" to "s-$i")), 20)
            }
            SqlStatementRecorder.reset()

            assertEquals(12, repo.byThread("t-1", null).size)

            val sent = SqlStatementRecorder.statements()
            assertEquals(2, sent.size, "$sent")
            assertTrue("continuations.thread_id = ?" in sent[0], sent[0])
            assertTrue("continuations.owner IS NULL" in sent[0], sent[0])
            assertTrue("FROM continuation_watches" in sent[1], sent[1])
        }

    // --- byThread is scoped to the run's owner ---

    private suspend fun threadRow(
        owner: String?,
        thread: String = "t-1",
    ): Int = repo.create(continuation(1, owner = owner).copy(params = mapOf("HMR_LG_THREAD" to thread)), 20)!!

    @Test
    fun `byThread returns only the given owner's rows of the thread, and nothing for another owner`() =
        runTest {
            val alice = threadRow("alice")
            val bob = threadRow("bob")
            val aliceJoin = repo.createJoin(joinContinuation(owner = "alice"), listOf(JoinWatch(5, "int-a")), 20)!!
            threadRow("alice", thread = "t-2")

            assertEquals(listOf(alice, aliceJoin), repo.byThread("t-1", "alice").map { it.id })
            assertEquals(listOf(bob), repo.byThread("t-1", "bob").map { it.id })
            assertTrue(repo.byThread("t-1", "mallory").isEmpty())
        }

    @Test
    fun `byThread with no owner matches only unowned rows, and an owner never matches unowned rows`() =
        runTest {
            val unowned = threadRow(null)
            val alice = threadRow("alice")

            assertEquals(listOf(unowned), repo.byThread("t-1", null).map { it.id })
            assertEquals(listOf(alice), repo.byThread("t-1", "alice").map { it.id })
            assertTrue(repo.byThread("t-1", "mallory").isEmpty(), "a stranger must not fall back to the unowned rows")
        }

    @Test
    fun `byThread matches the owner exactly, never as a pattern or an expression`() =
        runTest {
            val alice = threadRow("alice")
            threadRow(null)

            assertTrue(repo.byThread("t-1", "alice' OR '1'='1").isEmpty())
            assertTrue(repo.byThread("t-1", "' OR owner IS NULL OR '").isEmpty())
            assertTrue(repo.byThread("t-1", "%").isEmpty())
            assertTrue(repo.byThread("t-1", "a%").isEmpty())
            assertTrue(repo.byThread("t-1", "ALICE").isEmpty())
            assertTrue(repo.byThread("t-1", "").isEmpty())
            assertTrue(repo.byThread("t-1", "NULL").isEmpty())
            assertEquals(listOf(alice), repo.byThread("t-1", "alice").map { it.id })

            SqlStatementRecorder.reset()
            repo.byThread("t-1", "alice")
            val sent = SqlStatementRecorder.statements().single()
            assertTrue("continuations.owner = ?" in sent, sent)
            assertFalse("alice" in sent, "the owner must be bound, not inlined: $sent")
        }
}
