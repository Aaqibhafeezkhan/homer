package homer.app.services.persistence

import homer.app.exceptions.CooldownActiveException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.SqlStatementRecorder
import homer.app.fixtures.TestFixtures
import homer.app.infra.ProcessJobRegistry
import homer.app.models.Cooldown
import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.FALLBACK_REASON_SESSION_FIGURE_PENDING
import homer.app.models.LlmAccounting
import homer.app.models.MAX_FAILURE_REASON_LENGTH
import homer.app.models.PodPhase
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.RunnerState
import homer.app.models.SubRunProgress
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService.Question
import homer.app.services.QuestionsService.QuestionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessRepositoryTest {
    // Identity screen for tests that exercise the SQL filter semantics: the read
    // refuses an unscreened tag filter, so every such test passes a pass-through one.
    private val identityScreen: (ProcessInfo) -> List<String> = { it.tags }

    // Every root visible, for tests of the bare ?rootProcessId= filter: the read refuses the filter without a check.
    private val seeAll: (String?, String) -> Boolean = { _, _ -> true }

    private fun repo(db: DatabaseService = PersistenceTestSupport.tempDatabase()) = ProcessRepository(db, ProcessJobRegistry())

    @Test
    fun `obtainProcessId returns increasing AUTOINCREMENT ids starting at 1`() =
        runTest {
            val repo = repo()
            assertEquals(1, repo.obtainProcessId("a", emptyMap(), 100).processId)
            assertEquals(2, repo.obtainProcessId("a", emptyMap(), 100).processId)
            assertEquals(2, repo.lastProcessId())
        }

    @Test
    fun `obtainProcessId owner round-trips via getInfo`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100, owner = "ci").processId

            assertEquals("ci", repo.getInfo(id)?.owner)
        }

    @Test
    fun `obtainProcessId parentProcessId round-trips via getInfo and defaults to null`() =
        runTest {
            val repo = repo()
            val root = repo.obtainProcessId("a", emptyMap(), 100).processId
            val child = repo.obtainProcessId("b", emptyMap(), 100, parentProcessId = root).processId

            assertEquals(null, repo.getInfo(root)?.parentProcessId)
            assertEquals(root, repo.getInfo(child)?.parentProcessId)
        }

    @Test
    fun `getAll parentProcessId filter returns only that parent's children`() =
        runTest {
            val repo = repo()
            val root = repo.obtainProcessId("a", emptyMap(), 100).processId
            val other = repo.obtainProcessId("a", emptyMap(), 100).processId
            val child1 = repo.obtainProcessId("b", emptyMap(), 100, parentProcessId = root).processId
            val child2 = repo.obtainProcessId("b", emptyMap(), 100, parentProcessId = root).processId
            repo.obtainProcessId("b", emptyMap(), 100, parentProcessId = other).processId

            val page = repo.getAll(null, orderAsc = true, offset = 0, limit = 10, agentName = null, parentProcessId = root)

            assertEquals(listOf(child1, child2), page.processes.map { it.id })
            assertEquals(2, page.total)
            // The filter composes with the visibility union: a caller who cannot see
            // agent `b` gets an empty page, not the children.
            val hidden = repo.getAll(null, true, 0, 10, null, visibleOwner = "nobody", visibleAgents = emptySet(), parentProcessId = root)
            assertEquals(0, hidden.total)
        }

    @Test
    fun `obtainProcessId rootProcessId round-trips via getInfo and defaults to null`() =
        runTest {
            val repo = repo()
            val root = repo.obtainProcessId("a", emptyMap(), 100).processId
            val child = repo.obtainProcessId("b", emptyMap(), 100, parentProcessId = root, rootProcessId = root).processId
            val grandchild =
                repo.obtainProcessId("c", emptyMap(), 100, parentProcessId = child, rootProcessId = root).processId

            // NULL at the root, and the same id all the way down — the column is written
            // as-is, never derived at read time.
            assertEquals(null, repo.getInfo(root)?.rootProcessId)
            assertEquals(root, repo.getInfo(child)?.rootProcessId)
            assertEquals(root, repo.getInfo(grandchild)?.rootProcessId)
        }

    @Test
    fun `getAll rootProcessId filter returns only that flow's runs at any depth`() =
        runTest {
            val repo = repo()
            val root = repo.obtainProcessId("a", emptyMap(), 100).processId
            val other = repo.obtainProcessId("a", emptyMap(), 100).processId
            val child = repo.obtainProcessId("b", emptyMap(), 100, parentProcessId = root, rootProcessId = root).processId
            val grandchild = repo.obtainProcessId("c", emptyMap(), 100, parentProcessId = child, rootProcessId = root).processId
            repo.obtainProcessId("b", emptyMap(), 100, parentProcessId = other, rootProcessId = other).processId

            val page =
                repo.getAll(
                    null,
                    orderAsc = true,
                    offset = 0,
                    limit = 10,
                    agentName = null,
                    rootProcessId = root,
                    canSeeRoot = seeAll,
                )

            // The root row itself is not a descendant of itself: `?rootProcessId=N` answers
            // "runs in N's flow below N", so the caller lists N separately or filters with
            // roots=true. Owner-scoped callers pass N's own id as visibleOwner anyway.
            assertEquals(listOf(child, grandchild), page.processes.map { it.id })
            assertEquals(2, page.total)
            // Composes with the visibility union like every other predicate.
            val hidden =
                repo.getAll(
                    null,
                    true,
                    0,
                    10,
                    null,
                    visibleOwner = "nobody",
                    visibleAgents = emptySet(),
                    rootProcessId = root,
                    canSeeRoot = seeAll,
                )
            assertEquals(0, hidden.total)
        }

    @Test
    fun `getAll rootProcessId filter of an id no run carries returns an empty page`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100).processId

            // An invisible or evicted root id must read as "nothing you can see", never as
            // an error and never as the whole list (§12: hide existence).
            val page = repo.getAll(null, true, 0, 10, null, rootProcessId = 9999, canSeeRoot = seeAll)

            assertEquals(emptyList(), page.processes.map { it.id })
            assertEquals(0, page.total)
        }

    @Test
    fun `getAll carries the page's open question counts from one batched read`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val repo = repo(db)
            val questions = QuestionsRepository(db)
            val asker = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:1")).processId
            val quiet = repo.obtainProcessId("a", emptyMap(), 100).processId
            val offPage = repo.obtainProcessId("a", emptyMap(), 100).processId

            fun question(
                id: String,
                processId: Int,
                status: QuestionStatus = QuestionStatus.OPEN,
                dispatchBound: Boolean = false,
            ) = Question(id, processId, "a", "?", status = status, createdAt = 1, dispatchBound = dispatchBound)
            questions.tryInsert(question("q1", asker), 10)
            questions.tryInsert(question("q2", asker), 10)
            questions.tryInsert(question("q3", asker, status = QuestionStatus.ANSWERED), 10)
            questions.tryInsert(question("q4", asker, dispatchBound = true), 10)
            questions.tryInsert(question("q5", offPage), 10)

            SqlStatementRecorder.reset()
            val page = repo.getAll(null, orderAsc = true, offset = 0, limit = 2, agentName = null)

            assertEquals(listOf(asker, quiet), page.processes.map { it.id })
            // Answered and dispatch-bound questions are not open; an id off the page is not counted.
            assertEquals(mapOf(asker to 2), page.openQuestions)
            // Counted over the page's ids in one IN read inside the list's own transaction,
            // never over the whole table and never one query per row.
            val countReads = SqlStatementRecorder.statements().filter { "FROM questions" in it }
            assertEquals(1, countReads.size, "$countReads")
            assertTrue("questions.process_id IN (" in countReads.single(), countReads.single())
            assertEquals(1, SqlStatementRecorder.transactionCount())
            // The tag-screened path carries the counts too.
            val tagged = repo.getAll(null, true, 0, 10, null, tags = listOf("mr:1"), screenTags = identityScreen)
            assertEquals(mapOf(asker to 2), tagged.openQuestions)
        }

    @Test
    fun `getAll names only the page's root ids the caller may see, and none without canSeeRoot`() =
        runTest {
            val repo = repo()
            val mine = repo.obtainProcessId("a", emptyMap(), 100, owner = "me").processId
            val foreign = repo.obtainProcessId("x", emptyMap(), 100, owner = "alice").processId
            repo.obtainProcessId("b", emptyMap(), 100, owner = "me", parentProcessId = mine, rootProcessId = mine)
            repo.obtainProcessId("b", emptyMap(), 100, owner = "me", parentProcessId = foreign, rootProcessId = foreign)
            // A root row that is gone fails closed, like an invisible one (ADR-0058).
            repo.obtainProcessId("b", emptyMap(), 100, owner = "me", parentProcessId = 9999, rootProcessId = 9999)
            val canSeeRoot = { owner: String?, _: String -> owner == "me" }

            SqlStatementRecorder.reset()
            val page = repo.getAll(null, true, 0, 10, null, canSeeRoot = canSeeRoot)

            assertEquals(setOf(mine), page.visibleRootIds)
            // All roots of the page in one IN read by primary key, in the list's transaction.
            val rootReads = SqlStatementRecorder.statements().filter { "processes.id IN (" in it }
            assertEquals(1, rootReads.size, "${SqlStatementRecorder.statements()}")
            assertEquals(1, SqlStatementRecorder.transactionCount())
            // Without a visibility callback no root is named visible: fail closed.
            assertEquals(emptySet(), repo.getAll(null, true, 0, 10, null).visibleRootIds)
        }

    @Test
    fun `information_disclosure CWE-200 - getAll root filter reads as an empty page when the root is hidden or gone`() =
        runTest {
            val repo = repo()
            val foreign = repo.obtainProcessId("x", emptyMap(), 100, owner = "alice").processId
            // A descendant the caller could see on its own: the gate is the root row, not the rows.
            repo.obtainProcessId("b", emptyMap(), 100, owner = "me", parentProcessId = foreign, rootProcessId = foreign)
            repo.obtainProcessId("b", emptyMap(), 100, owner = "me", parentProcessId = 9999, rootProcessId = 9999)
            val mine = repo.obtainProcessId("a", emptyMap(), 100, owner = "me").processId
            val child = repo.obtainProcessId("b", emptyMap(), 100, owner = "me", parentProcessId = mine, rootProcessId = mine).processId
            val canSeeRoot = { owner: String?, _: String -> owner == "me" }

            val hidden = repo.getAll(null, true, 0, 10, null, rootProcessId = foreign, canSeeRoot = canSeeRoot)
            assertEquals(0, hidden.total)
            assertTrue(hidden.processes.isEmpty())
            // A missing root row reads the same even when every row is visible.
            val gone = repo.getAll(null, true, 0, 10, null, rootProcessId = 9999, canSeeRoot = { _, _ -> true })
            assertEquals(0, gone.total)
            assertTrue(gone.processes.isEmpty())
            // A visible root's filter is honoured.
            val flow = repo.getAll(null, true, 0, 10, null, rootProcessId = mine, canSeeRoot = canSeeRoot)
            assertEquals(listOf(child), flow.processes.map { it.id })
            assertEquals(setOf(mine), flow.visibleRootIds)
        }

    @Test
    fun `information_disclosure CWE-200 - getAll refuses a root filter without a canSeeRoot check`() =
        runTest {
            val repo = repo()
            val root = repo.obtainProcessId("x", emptyMap(), 100, owner = "alice").processId
            repo.obtainProcessId("b", emptyMap(), 100, owner = "me", parentProcessId = root, rootProcessId = root)

            // Fail closed like the unscreened tag filter: an ungated root filter would list the
            // visible rows of a hidden flow, so the read never runs.
            SqlStatementRecorder.reset()
            val refused = assertFailsWith<IllegalArgumentException> { repo.getAll(null, true, 0, 10, null, rootProcessId = root) }
            assertEquals("canSeeRoot is required whenever rootProcessId is set", refused.message)
            assertTrue(SqlStatementRecorder.statements().isEmpty(), "${SqlStatementRecorder.statements()}")
        }

    @Test
    fun `getAll rootsOnly returns the first run of every flow`() =
        runTest {
            val repo = repo()
            val rootA = repo.obtainProcessId("a", emptyMap(), 100).processId
            val rootB = repo.obtainProcessId("b", emptyMap(), 100).processId
            repo.obtainProcessId("b", emptyMap(), 100, parentProcessId = rootA, rootProcessId = rootA).processId
            repo.obtainProcessId("c", emptyMap(), 100, parentProcessId = rootB, rootProcessId = rootB).processId

            val page = repo.getAll(null, orderAsc = true, offset = 0, limit = 10, agentName = null, rootsOnly = true)

            assertEquals(listOf(rootA, rootB), page.processes.map { it.id })
            assertEquals(2, page.total)
        }

    @Test
    fun `getAll rootsOnly composes with the status, agent and tag filters`() =
        runTest {
            val repo = repo()
            val rootA = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId
            val rootB = repo.obtainProcessId("b", emptyMap(), 100).processId
            repo.obtainProcessId("a", emptyMap(), 100, parentProcessId = rootA, rootProcessId = rootA, tags = listOf("mr:15")).processId

            val byAgent =
                repo.getAll(null, true, 0, 10, agentName = "a", rootsOnly = true).processes.map { it.id }
            assertEquals(listOf(rootA), byAgent)

            val byTag =
                repo
                    .getAll(
                        null,
                        true,
                        0,
                        10,
                        null,
                        rootsOnly = true,
                        tags = listOf("mr:15"),
                        screenTags = identityScreen,
                    ).processes
                    .map { it.id }
            assertEquals(listOf(rootA), byTag)

            val byStatus = repo.getAll(setOf(ProcessStatus.CREATED), true, 0, 10, null, rootsOnly = true)
            assertEquals(listOf(rootA, rootB), byStatus.processes.map { it.id })

            // No root matches a status they are not in: the predicates AND, never OR.
            val none = repo.getAll(setOf(ProcessStatus.FINISHED), true, 0, 10, null, rootsOnly = true)
            assertEquals(emptyList(), none.processes.map { it.id })
        }

    @Test
    fun `getAll rootsOnly still refuses a tag filter without a screen`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId

            // The roots-only predicate must not open a path around the fail-closed
            // screenTags contract (ADR-0060).
            assertFailsWith<IllegalArgumentException> {
                repo.getAll(null, true, 0, 10, null, rootsOnly = true, tags = listOf("mr:15"))
            }
        }

    @Test
    fun `obtainProcessId stores fromInputs tags co-transactionally and surfaces them`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15", "projectId:4711")).processId

            assertEquals(listOf("mr:15", "projectId:4711"), repo.getTags(id))
            assertEquals(listOf("mr:15", "projectId:4711"), repo.getInfo(id)?.tags)
        }

    // Tags come back sorted by tag (served by the process_tags primary
    // key), not in insertion order — these insert deliberately out of order so a
    // regression to insertion/rowid order would fail them.
    @Test
    fun `getTags returns a run's tags in tag order, not insertion order`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("zeta:1", "alpha:1", "mid:1")).processId

            assertEquals(listOf("alpha:1", "mid:1", "zeta:1"), repo.getTags(id))
        }

    @Test
    fun `getAll surfaces each run's tags in tag order via the batched read`() =
        runTest {
            val repo = repo()
            val first = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("zeta:1", "alpha:1")).processId
            val second =
                repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("yankee:1", "bravo:1", "kilo:1")).processId

            val page = repo.getAll(null, orderAsc = true, offset = 0, limit = 10, agentName = "a")

            val tagsById = page.processes.associate { it.id to it.tags }
            assertEquals(listOf("alpha:1", "zeta:1"), tagsById[first])
            assertEquals(listOf("bravo:1", "kilo:1", "yankee:1"), tagsById[second])
        }

    @Test
    fun `getAll with no tag filter returns every run`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId
            repo.obtainProcessId("a", emptyMap(), 100).processId

            val page = repo.getAll(null, orderAsc = true, offset = 0, limit = 10, agentName = null)

            assertEquals(2, page.total)
        }

    @Test
    fun `getAll single tag filter returns only runs carrying it and surfaces their tags`() =
        runTest {
            val repo = repo()
            val tagged = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:99")).processId

            val page = repo.getAll(null, true, 0, 10, null, tags = listOf("mr:15"), screenTags = identityScreen)

            assertEquals(listOf(tagged), page.processes.map { it.id })
            assertEquals(1, page.total)
            assertEquals(listOf("mr:15"), page.processes.single().tags)
        }

    @Test
    fun `getAll multiple tags AND — a run must carry every tag`() =
        runTest {
            val repo = repo()
            val both = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15", "project:x")).processId
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId

            val page =
                repo.getAll(null, true, 0, 10, null, tags = listOf("mr:15", "project:x"), screenTags = identityScreen)

            assertEquals(listOf(both), page.processes.map { it.id })
            assertEquals(1, page.total)
        }

    @Test
    fun `getAll tag filter collapses duplicate params`() =
        runTest {
            val repo = repo()
            val tagged = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId

            // A repeated identical ?tag= must not turn the HAVING COUNT into "needs
            // two rows" — the de-dup is what keeps a doubled filter matching.
            val page = repo.getAll(null, true, 0, 10, null, tags = listOf("mr:15", "mr:15"), screenTags = identityScreen)

            assertEquals(listOf(tagged), page.processes.map { it.id })
            assertEquals(1, page.total)
        }

    @Test
    fun `getAll tag filter with no match returns an empty page, not an error`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId

            val page = repo.getAll(null, true, 0, 10, null, tags = listOf("mr:does-not-exist"), screenTags = identityScreen)

            assertEquals(emptyList(), page.processes.map { it.id })
            assertEquals(0, page.total)
        }

    @Test
    fun `getAll tag filter composes with visibility and total counts the filtered set`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15"), owner = "ci").processId
            val mineHidden = repo.obtainProcessId("b", emptyMap(), 100, tags = listOf("mr:15"), owner = "me").processId

            // Caller sees only agent `b` via owner; the mr:15 run on agent `a` is
            // filtered out by visibility, never returned as a 403 — it is simply absent.
            val page =
                repo.getAll(
                    null,
                    true,
                    0,
                    10,
                    null,
                    visibleOwner = "me",
                    visibleAgents = emptySet(),
                    tags = listOf("mr:15"),
                    screenTags = identityScreen,
                )

            assertEquals(listOf(mineHidden), page.processes.map { it.id })
            assertEquals(1, page.total)
        }

    @Test
    fun `getAll tag filter hides a tag carried only by a run the caller cannot see`() =
        runTest {
            val repo = repo()
            // The tag exists solely on a run owned by another principal, on an agent the
            // caller holds no grant on. Resolving the id set must be scoped to the caller's
            // visibility, so the tag matches nothing — never leaking the invisible run's
            // existence, and never materializing its id. See ADR-0060 (CWE-400).
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("secret:x"), owner = "other").processId

            val page =
                repo.getAll(
                    null,
                    true,
                    0,
                    10,
                    null,
                    visibleOwner = "me",
                    visibleAgents = emptySet(),
                    tags = listOf("secret:x"),
                    screenTags = identityScreen,
                )

            assertEquals(emptyList(), page.processes.map { it.id })
            assertEquals(0, page.total)
        }

    @Test
    fun `denial_of_service - screened tag filter is row-capped and batched, not an unbounded N+1`() =
        runTest {
            val repo = repo()
            repeat(3) { repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")) }
            val screen: (ProcessInfo) -> List<String> = { info -> info.tags }

            SqlStatementRecorder.reset()
            val page = repo.getAll(null, true, 0, 1, null, tags = listOf("mr:15"), screenTags = screen)

            assertEquals(1, page.processes.size)
            assertEquals(3, page.total)
            val statements = SqlStatementRecorder.statements()
            // The candidate fetch is bounded in SQL — a caller's `limit=1` must not make
            // the screened path materialize every visible matching row first.
            assertTrue(
                statements.any { "LIMIT $MAX_TAG_SCREENED_ROWS" in it },
                "screened fetch must be SQL-bounded, ran: $statements",
            )
            // Tags for the surviving page arrive in one batched IN-list read, not one
            // per-row query per candidate row.
            // The candidate query itself carries the tag subquery; a tag read is a statement over process_tags.
            val batchedTagReads = statements.filter { it.trimStart().startsWith("SELECT process_tags.") && " IN (" in it }
            assertEquals(1, batchedTagReads.size, "expected one batched tag read, ran: $statements")
            val perRowTagReads = statements.filter { Regex("""process_tags\.process_id = (?!processes)""").containsMatchIn(it) }
            assertTrue(perRowTagReads.isEmpty(), "screened path must not run a per-row tag query: $perRowTagReads")
        }

    @Test
    fun `denial_of_service - screened tag filter reads tags in chunks past SQLite's parameter limit`() =
        runTest {
            val repo = repo()
            // A visible candidate set wider than SQLITE_LIMIT_VARIABLE_NUMBER (999,
            // default) must degrade to repeated chunked reads, not throw on a single
            // oversized IN-list. 513 ids force two 512-id chunks.
            repeat(513) { repo.obtainProcessId("a", emptyMap(), 1000, tags = listOf("mr:15")) }
            val screen: (ProcessInfo) -> List<String> = { info -> info.tags }

            SqlStatementRecorder.reset()
            val page = repo.getAll(null, true, 0, 10, null, tags = listOf("mr:15"), screenTags = screen)

            assertEquals(513, page.total)
            assertEquals(10, page.processes.size)
            // 513 ids → two chunks (512 + 1): the second chunk's single id renders as
            // `= ?`, not `IN (…)`, but both are one batched read of the page.
            val batchedTagReads =
                SqlStatementRecorder
                    .statements()
                    .filter { "SELECT process_tags.process_id, process_tags.tag FROM process_tags WHERE process_tags.process_id" in it }
            assertEquals(2, batchedTagReads.size, "expected two chunked batched tag reads, ran: ${SqlStatementRecorder.statements()}")
        }

    @Test
    fun `tag filter drops a run whose matched tag is screened, hiding the oracle`() =
        runTest {
            val repo = repo()
            // The stored tag `mr:supersecret` was derived from an input later flipped to
            // secret; the display screen would drop it. Probing by that value must return
            // an empty page / total=0 even though the row exists — otherwise `total`
            // discloses that the guessed secret is stored (CWE-203).
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:supersecret")).processId
            val screen: (ProcessInfo) -> List<String> = { info ->
                info.tags.filterNot { it.contains("supersecret") }
            }

            val page = repo.getAll(null, true, 0, 10, null, tags = listOf("mr:supersecret"), screenTags = screen)

            assertEquals(emptyList(), page.processes.map { it.id })
            assertEquals(0, page.total)
        }

    @Test
    fun `tag filter with a screen keeps a run whose tag survives and counts it accurately`() =
        runTest {
            val repo = repo()
            // A non-secret tag still matches and the total is accurate: the screen only
            // drops screened values, it must not over-hide legitimate tags (AC #3).
            val visible = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:99")).processId
            val screen: (ProcessInfo) -> List<String> = { info ->
                info.tags.filterNot { it.contains("supersecret") }
            }

            val page = repo.getAll(null, true, 0, 10, null, tags = listOf("mr:15"), screenTags = screen)

            assertEquals(listOf(visible), page.processes.map { it.id })
            assertEquals(1, page.total)
            assertEquals(listOf("mr:15"), page.processes.single().tags)
        }

    @Test
    fun `tag filter screen drops only the run whose value is secret, keeping a sibling with the same key`() =
        runTest {
            val repo = repo()
            // Two runs share the tag *key* `token`, but the value is a secret needle only
            // on the first run. A per-row screen must hide only that run, not the sibling —
            // a global reject of the value would over-hide the legitimate one (AC #3).
            repo
                .obtainProcessId(
                    "a",
                    mapOf("token" to "supersecret"),
                    100,
                    secretInputKeys = setOf("token"),
                    tags = listOf("token:supersecret"),
                ).processId
            val legit = repo.obtainProcessId("b", emptyMap(), 100, tags = listOf("token:supersecret")).processId
            val screen: (ProcessInfo) -> List<String> = { info ->
                val needles = info.secretInputKeys.orEmpty().mapNotNull { info.inputParams[it] }
                info.tags.filterNot { tag -> needles.any { tag.contains(it) } }
            }

            val page = repo.getAll(null, true, 0, 10, null, tags = listOf("token:supersecret"), screenTags = screen)

            assertEquals(listOf(legit), page.processes.map { it.id })
            assertEquals(1, page.total)
        }

    @Test
    fun `a tag filter without a screen function is refused, never executed`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId

            // The screen is what keeps the filter path from re-opening the CWE-203 oracle,
            // so a caller that filters by tag must not be able to skip it by leaving the
            // parameter unset — the read fails closed instead of counting unredacted rows.
            val error =
                assertFailsWith<IllegalArgumentException> {
                    repo.getAll(null, true, 0, 10, null, tags = listOf("mr:15"))
                }
            assertTrue(error.message!!.contains("screenTags"), error.message)
        }

    @Test
    fun `insertRuntimeTag refuses a write when the run row is gone`() =
        runTest {
            val repo = repo()
            // No process 999 exists (e.g. purged before a late callback). Without the
            // existence guard the tag would INSERT an orphan row that outlives the run,
            // since process_tags has no foreign key. The write must fail closed.
            val result = repo.insertRuntimeTag(999, "decision:approved", maxTags = 20)

            assertFalse(result.written)
            assertEquals(0, result.count)
            assertEquals(emptyList(), repo.getTags(999))
        }

    @Test
    fun `insertRuntimeTag appends, is idempotent, and enforces the per-run ceiling`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId

            val first = repo.insertRuntimeTag(id, "decision:approved", maxTags = 2)
            assertTrue(first.written)
            assertEquals(1, first.count)

            // Exact duplicate collapses: still written=true, count unchanged.
            val dup = repo.insertRuntimeTag(id, "decision:approved", maxTags = 2)
            assertTrue(dup.written)
            assertEquals(1, dup.count)

            val second = repo.insertRuntimeTag(id, "kind:bug", maxTags = 2)
            assertTrue(second.written)
            assertEquals(2, second.count)

            // At the ceiling a new distinct tag is refused, not written.
            val overflow = repo.insertRuntimeTag(id, "extra:one", maxTags = 2)
            assertFalse(overflow.written)
            assertEquals(2, overflow.count)
            assertEquals(listOf("decision:approved", "kind:bug"), repo.getTags(id))
        }

    @Test
    fun `denial_of_service - tag filter is one SQL subquery, no app-side id list`() =
        runTest {
            val repo = repo()
            // Many visible runs share the tag; the page is one row. Regardless of how many
            // match, the filter must not first pull every matching id into the application
            // and feed it back as `id IN (?, ?, ...)` — that list grows with the match set
            // and hits SQLite's bound-parameter cap. See ADR-0060 (CWE-400).
            repeat(25) { repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")) }

            SqlStatementRecorder.reset()
            val page = repo.getAll(null, true, 0, 1, null, tags = listOf("mr:15"), screenTags = identityScreen)

            assertEquals(1, page.processes.size)
            assertEquals(25, page.total)
            val statements = SqlStatementRecorder.statements()
            val idListQueries =
                statements.filter {
                    "process_tags pt" in it ||
                        Regex("""processes\.id IN \((\?, ){10,}""").containsMatchIn(it)
                }
            assertTrue(idListQueries.isEmpty(), "tag filter materialized an id list: $idListQueries")
            // For an admin, an uncorrelated subquery the tag index drives, not one probe per process row.
            assertTrue(
                statements.any { it.startsWith("SELECT processes.") && "processes.id IN (SELECT process_tags.process_id" in it },
                "expected the tag filter as an id IN (SELECT ...) subquery, ran: $statements",
            )
        }

    @Test
    fun `a scoped caller's tag filter only probes the runs it can see`() =
        runTest {
            val repo = repo()
            repeat(3) { repo.obtainProcessId("a", emptyMap(), 100, owner = "alice", tags = listOf("mr:15")) }
            repeat(3) { repo.obtainProcessId("b", emptyMap(), 100, owner = "bob", tags = listOf("mr:15")) }

            SqlStatementRecorder.reset()
            val page =
                repo.getAll(
                    null,
                    true,
                    0,
                    10,
                    null,
                    visibleOwner = "alice",
                    visibleAgents = emptySet(),
                    tags = listOf("mr:15"),
                    screenTags = identityScreen,
                )

            assertEquals(3, page.total)
            val statements = SqlStatementRecorder.statements()
            // The index-driven subquery walks every run with the tag, other principals' included, so its
            // timing would count them. A scoped caller keeps the correlated probe of visible rows only.
            assertTrue(
                statements.none { "processes.id IN (SELECT process_tags.process_id" in it },
                "scoped caller got the index-driven subquery: $statements",
            )
            assertTrue(
                statements.any { "EXISTS" in it.uppercase() && "process_tags.process_id = processes.id" in it },
                "expected the correlated EXISTS for a scoped caller, ran: $statements",
            )
        }

    @Test
    fun `insertRuntimeTag ceiling counts runtime tags only, not fromInputs tags`() =
        runTest {
            val repo = repo()
            // Two fromInputs tags already on the run (keys mr, projectId), written at start.
            val id = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15", "projectId:4711")).processId
            val fromInputsKeys = setOf("mr", "projectId")

            // The ceiling is 2 runtime tags; the two fromInputs tags must not consume it.
            val first = repo.insertRuntimeTag(id, "decision:approved", maxTags = 2, fromInputsKeys = fromInputsKeys)
            assertTrue(first.written)
            assertEquals(1, first.count)

            val second = repo.insertRuntimeTag(id, "kind:bug", maxTags = 2, fromInputsKeys = fromInputsKeys)
            assertTrue(second.written)
            assertEquals(2, second.count)

            // Now at the runtime ceiling: a third runtime tag is refused even though the
            // table holds four rows (two fromInputs + two runtime).
            val overflow = repo.insertRuntimeTag(id, "extra:one", maxTags = 2, fromInputsKeys = fromInputsKeys)
            assertFalse(overflow.written)
            assertEquals(2, overflow.count)
            assertEquals(
                setOf("mr:15", "projectId:4711", "decision:approved", "kind:bug"),
                repo.getTags(id).toSet(),
            )
        }

    @Test
    fun `remove deletes the run's tag rows`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId

            repo.remove(id)

            assertEquals(emptyList(), repo.getTags(id))
        }

    @Test
    fun `eviction deletes the evicted run's tag rows`() =
        runTest {
            val repo = repo()
            val id1 = repo.obtainProcessId("a", emptyMap(), 2, tags = listOf("mr:15")).processId
            repo.obtainProcessId("a", emptyMap(), 2).processId
            repo.transitionStatus(id1, ProcessStatus.FINISHED, 1, null)

            val result = repo.obtainProcessId("a", emptyMap(), 2)

            assertEquals(id1, result.evicted?.id)
            assertEquals(emptyList(), repo.getTags(id1))
        }

    @Test
    fun `costByTag sums cost across runs sharing a tag value`() =
        runTest {
            val repo = repo()
            val a = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("ticket_id:T-123")).processId
            val b = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("ticket_id:T-123")).processId
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("ticket_id:T-999")).processId
            repo.addCost(a, 1.0, CostSource.CLI)
            repo.addCost(b, 0.5, CostSource.CLI)

            val result = repo.costByTag("ticket_id", identityScreen)

            assertEquals(1.5, result.valuesUsd["T-123"])
            assertFalse(result.truncated)
        }

    @Test
    fun `costByTag reads the candidates without their history, executions or runner state`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", mapOf("IN" to "v"), 100, tags = listOf("ticket_id:T-1")).processId
            repo.addCost(id, 2.0, CostSource.CLI)

            val seen = mutableListOf<ProcessInfo>()
            SqlStatementRecorder.reset()
            val result =
                repo.costByTag("ticket_id", { info ->
                    seen += info
                    info.tags
                })

            // Up to 10 000 candidates per call, and the screen needs none of the blobs.
            val selects = SqlStatementRecorder.statements().filter { it.startsWith("SELECT processes.") }
            assertEquals(1, selects.size, selects.toString())
            assertFalse(selects.single().contains("processes.history"), selects.single())
            assertFalse(selects.single().contains("processes.executions"), selects.single())
            assertFalse(selects.single().contains("processes.runner_state"), selects.single())
            // What the screen does read is all there.
            val info = seen.single()
            assertEquals(listOf("ticket_id:T-1"), info.tags)
            assertEquals(mapOf("IN" to "v"), info.inputParams)
            assertEquals("a", info.agentName)
            assertEquals(2.0, result.valuesUsd["T-1"])
        }

    @Test
    fun `costByTag is not truncated when candidates exactly fill the bound`() =
        runTest {
            val repo = repo()
            repeat(3) { i ->
                val id = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("ticket_id:v$i")).processId
                repo.addCost(id, 1.0, CostSource.CLI)
            }

            val result = repo.costByTag("ticket_id", identityScreen, maxRows = 3)

            assertFalse(result.truncated)
            assertEquals(3.0, result.valuesUsd.values.sum())
        }

    @Test
    fun `costByTag is truncated when one more candidate exists than the bound, and sums only the newest kept rows`() =
        runTest {
            val repo = repo()
            // Oldest run first, so it is the one dropped when only the newest maxRows are kept.
            val oldest = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("ticket_id:oldest")).processId
            repo.addCost(oldest, 10.0, CostSource.CLI)
            repeat(3) { i ->
                val id = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("ticket_id:v$i")).processId
                repo.addCost(id, 1.0, CostSource.CLI)
            }

            val result = repo.costByTag("ticket_id", identityScreen, maxRows = 3)

            assertTrue(result.truncated)
            // The oldest run's cost is excluded: only the newest 3 rows are summed.
            assertEquals(3.0, result.valuesUsd.values.sum())
            assertEquals(null, result.valuesUsd["oldest"])
        }

    @Test
    fun `costByTag excludes a run whose tag value is currently secret`() =
        runTest {
            val repo = repo()
            val secretRun =
                repo
                    .obtainProcessId(
                        "a",
                        mapOf("ticketId" to "T-SECRET"),
                        100,
                        secretInputKeys = setOf("ticketId"),
                        tags = listOf("ticket_id:T-SECRET"),
                    ).processId
            repo.addCost(secretRun, 5.0, CostSource.CLI)
            val screen: (ProcessInfo) -> List<String> = { info ->
                val needles = info.secretInputKeys.orEmpty().mapNotNull { info.inputParams[it] }
                info.tags.filterNot { tag -> needles.any { tag.contains(it) } }
            }

            val result = repo.costByTag("ticket_id", screen)

            assertEquals(emptyMap(), result.valuesUsd)
        }

    @Test
    fun `costByTag returns an empty map for a key with no matching tags`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15")).processId

            val result = repo.costByTag("ticket_id", identityScreen)

            assertEquals(emptyMap(), result.valuesUsd)
            assertFalse(result.truncated)
        }

    @Test
    fun `costByTag key containing an underscore does not match a look-alike key`() =
        runTest {
            val repo = repo()
            // '_' is a SQL single-character wildcard; a naive LIKE 'ticket_id:%' would
            // also match 'ticketXid:...'. The range-bound prefix test must not.
            val lookalike = repo.obtainProcessId("a", emptyMap(), 100, tags = listOf("ticketXid:T-1")).processId
            repo.addCost(lookalike, 3.0, CostSource.CLI)

            val result = repo.costByTag("ticket_id", identityScreen)

            assertEquals(emptyMap(), result.valuesUsd)
        }

    @Test
    fun `eviction deletes oldest terminal row and new id never reuses it`() =
        runTest {
            val repo = repo()
            val id1 = repo.obtainProcessId("a", emptyMap(), 2).processId
            repo.obtainProcessId("a", emptyMap(), 2).processId
            // id1 terminal so it can be evicted when capacity (2) is hit.
            repo.transitionStatus(id1, ProcessStatus.FINISHED, 1, null)

            val result = repo.obtainProcessId("a", emptyMap(), 2)

            assertEquals(id1, result.evicted?.id)
            assertEquals(ProcessStatus.FINISHED, result.evicted?.status)
            assertEquals(ProcessStatus.UNKNOWN, repo.getStatus(id1)) // evicted row is gone
            assertTrue(result.processId > 2, "new id must be strictly greater than every prior id")
        }

    private val terminal = setOf(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.KILLED)

    @Test
    fun `getWorkspacePurgeable returns only due terminal rows that still have a directory`() =
        runTest {
            val repo = repo()

            val due = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(due, ProcessStatus.FINISHED, 1, null)
            repo.setArtifactsDir(due, "/tmp/ws/due/artifacts")
            repo.setPurgeAt(due, 500)

            val notYetDue = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(notYetDue, ProcessStatus.FINISHED, 1, null)
            repo.setArtifactsDir(notYetDue, "/tmp/ws/not-yet/artifacts")
            repo.setPurgeAt(notYetDue, 5000)

            val noPurgeAt = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(noPurgeAt, ProcessStatus.FINISHED, 1, null)

            val stillWorking = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(stillWorking, ProcessStatus.WORKING, 1, null)
            repo.setArtifactsDir(stillWorking, "/tmp/ws/working/artifacts")
            repo.setPurgeAt(stillWorking, 500)

            // Due but already swept: artifacts_dir cleared, so it must not come round again.
            val alreadySwept = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(alreadySwept, ProcessStatus.FINISHED, 1, null)
            repo.setPurgeAt(alreadySwept, 500)

            assertEquals(listOf(due), repo.getWorkspacePurgeable(terminal, nowSec = 1000).map { it.id })
        }

    @Test
    fun `getRecordPurgeable waits for the record window, not the workspace one`() =
        runTest {
            val repo = repo()

            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.FINISHED, 1, null)
            repo.setPurgeAt(id, purgeAt = 500, recordPurgeAt = 5000)

            // The directory window has passed; the record window has not.
            assertEquals(emptyList(), repo.getRecordPurgeable(terminal, nowSec = 1000).map { it.id })
            assertEquals(listOf(id), repo.getRecordPurgeable(terminal, nowSec = 6000).map { it.id })
        }

    @Test
    fun `getRecordPurgeable holds back a row whose workspace has not been swept`() =
        runTest {
            val repo = repo()

            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.FINISHED, 1, null)
            repo.setArtifactsDir(id, "/tmp/ws/stuck/artifacts")
            repo.setPurgeAt(id, 500)

            // Deleting the record first would orphan a directory whose delete keeps
            // failing, with nothing left pointing at it.
            assertEquals(emptyList(), repo.getRecordPurgeable(terminal, nowSec = 1000).map { it.id })

            repo.clearArtifactsDir(id)

            assertEquals(listOf(id), repo.getRecordPurgeable(terminal, nowSec = 1000).map { it.id })
        }

    @Test
    fun `setPurgeAt stores retain globs and getWorkspacePurgeable returns them`() =
        runTest {
            val repo = repo()

            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.FINISHED, 1, null)
            repo.setArtifactsDir(id, "/tmp/ws/retain/artifacts")
            repo.setPurgeAt(id, purgeAt = 500, recordPurgeAt = 1500, retainArtifacts = listOf("decision.json"))

            val purgeable = repo.getWorkspacePurgeable(terminal, nowSec = 1000)

            assertEquals(listOf("decision.json"), purgeable.single { it.id == id }.retainArtifacts)
        }

    @Test
    fun `markWorkspacePurged removes the row from the workspace sweep and hands it to the record sweep when due`() =
        runTest {
            val repo = repo()

            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.FINISHED, 1, null)
            repo.setArtifactsDir(id, "/tmp/ws/retained/artifacts")
            repo.setPurgeAt(id, purgeAt = 500, recordPurgeAt = 500, retainArtifacts = listOf("decision.json"))

            // Before mark: still due for the workspace sweep, not yet handed to the record sweep.
            assertEquals(listOf(id), repo.getWorkspacePurgeable(terminal, nowSec = 1000).map { it.id })
            assertEquals(emptyList(), repo.getRecordPurgeable(terminal, nowSec = 1000).map { it.id })

            repo.markWorkspacePurged(id, retainedTruncated = true)

            assertEquals(emptyList(), repo.getWorkspacePurgeable(terminal, nowSec = 1000).map { it.id })
            assertEquals(listOf(id), repo.getRecordPurgeable(terminal, nowSec = 1000).map { it.id })
            assertNotNull(repo.getInfo(id)?.artifactsDir)
            assertEquals(true, repo.getInfo(id)?.retainedTruncated)
        }

    @Test
    fun `clearArtifactsDir records retainedTruncated when the prune truncated everything`() =
        runTest {
            val repo = repo()

            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.FINISHED, 1, null)
            repo.setArtifactsDir(id, "/tmp/ws/overflow/artifacts")
            repo.setPurgeAt(id, purgeAt = 500, retainArtifacts = listOf("big.json"))

            repo.clearArtifactsDir(id, retainedTruncated = true)

            assertEquals(null, repo.getInfo(id)?.artifactsDir)
            assertEquals(true, repo.getInfo(id)?.retainedTruncated)
        }

    @Test
    fun `record sweep still requires the workspace sweep to have run first`() =
        runTest {
            val repo = repo()

            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.FINISHED, 1, null)
            repo.setArtifactsDir(id, "/tmp/ws/unmarked/artifacts")
            repo.setPurgeAt(id, purgeAt = 500, recordPurgeAt = 500, retainArtifacts = listOf("decision.json"))

            // artifacts_dir is still set and workspace_purged_at was never stamped — unchanged
            // ADR-0048 ordering invariant.
            assertEquals(emptyList(), repo.getRecordPurgeable(terminal, nowSec = 1000).map { it.id })
        }

    @Test
    fun `transitionStatus guard preserves KILLED over a late FINISHED (audit 9)`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.WORKING, 1, null)
            repo.transitionStatus(id, ProcessStatus.KILLED, 2, null)

            val result =
                repo.transitionStatus(
                    id,
                    ProcessStatus.FINISHED,
                    3,
                    guard = setOf(ProcessStatus.WORKING, ProcessStatus.CREATED),
                )

            assertEquals(false, result.updated)
            assertEquals(ProcessStatus.KILLED, repo.getStatus(id))
        }

    @Test
    fun `addCost accumulates and completeStep appends`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.addCost(id, 0.1, CostSource.CLI)
            repo.addCost(id, 0.25, CostSource.CLI)
            repo.completeStep(id, execution("cmd-1"), null, null)
            repo.completeStep(id, execution("cmd-2"), null, null)

            val info = repo.getInfo(id)!!
            assertEquals(0.35, info.costUsd, 1e-9)
            assertEquals(listOf("cmd-1", "cmd-2"), info.executions.map { it.label })
        }

    @Test
    fun `completeStep appends the execution and sets the next command in one write transaction`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val repo = repo(db)
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId

            SqlStatementRecorder.reset()
            val existed = repo.completeStep(id, execution("cmd-1"), nextLabel = "cmd-2", nextStart = 4242L)
            val writes = SqlStatementRecorder.statements().filter { it.trimStart().startsWith("UPDATE", ignoreCase = true) }

            assertTrue(existed)
            // The append and the current-command set ride the SAME transaction: one
            // UPDATE, not the two a split addExecution + setCurrentCommand produced.
            assertEquals(1, writes.size, "expected one write statement, ran: ${SqlStatementRecorder.statements()}")
            val info = repo.getInfo(id)!!
            assertEquals(listOf("cmd-1"), info.executions.map { it.label })
            assertEquals("cmd-2", info.currentCommand)
            assertEquals(4242L, info.currentCommandStart)
        }

    @Test
    fun `completeStep clears the current command when the next label is null`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.setCurrentCommand(id, "cmd-1", 100L)

            assertTrue(repo.completeStep(id, execution("cmd-1"), nextLabel = null, nextStart = null))

            val info = repo.getInfo(id)!!
            assertEquals(listOf("cmd-1"), info.executions.map { it.label })
            assertNull(info.currentCommand)
            assertNull(info.currentCommandStart)
        }

    @Test
    fun `completeStep on a missing row writes nothing and returns false`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val repo = repo(db)
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.remove(id)

            SqlStatementRecorder.reset()
            val existed = repo.completeStep(id, execution("cmd-1"), nextLabel = "cmd-2", nextStart = 1L)

            assertFalse(existed)
            // The guard returns inside the read, before the UPDATE — a concurrent kill
            // must leave no append and no stale current command behind.
            assertTrue(
                SqlStatementRecorder.statements().none { it.trimStart().startsWith("UPDATE", ignoreCase = true) },
                "missing row must not be written, ran: ${SqlStatementRecorder.statements()}",
            )
        }

    @Test
    fun `completeStep on a killed row records the execution but keeps the command cleared`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.WORKING, 1, guard = null)
            repo.killTransition(id, 2)

            // A concurrent kill between step start and step end: the append still
            // lands (the run did execute the step), but the boundary must not
            // resurrect the current command the kill cleared.
            val live = repo.completeStep(id, execution("cmd-1"), nextLabel = "cmd-2", nextStart = 4242L)

            assertFalse(live)
            val info = repo.getInfo(id)!!
            assertEquals(listOf("cmd-1"), info.executions.map { it.label })
            assertNull(info.currentCommand)
            assertNull(info.currentCommandStart)
        }

    @Test
    fun `completeStep on any other terminal row records the execution but keeps the command cleared`() =
        runTest {
            // The guard covers the row's whole terminal class, not just KILLED: a late step
            // boundary after a FAILED run or a restart-UNKNOWN one must not resurrect a
            // running command either. Each remaining terminal status is exercised so
            // narrowing the guard back to `status != KILLED` fails here.
            for (terminal in listOf(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.UNKNOWN)) {
                val repo = repo()
                val id = repo.obtainProcessId("a", emptyMap(), 100).processId
                repo.transitionStatus(id, ProcessStatus.WORKING, 1, guard = null)
                repo.transitionStatus(id, terminal, 2, guard = null)

                val live = repo.completeStep(id, execution("cmd-1"), nextLabel = "cmd-2", nextStart = 4242L)

                assertFalse(live, "a $terminal row must not be handed back a running command")
                val info = repo.getInfo(id)!!
                assertEquals(listOf("cmd-1"), info.executions.map { it.label })
                assertNull(info.currentCommand, "$terminal row must keep the command cleared")
                assertNull(info.currentCommandStart)
            }
        }

    @Test
    fun `setCurrentCommand on a terminal row writes nothing and returns false`() =
        runTest {
            // The step-start write carries the same guard as completeStep: a kill landing
            // between the run's WORKING transition and the step's start must not leave a
            // terminal row holding a running command.
            for (terminal in listOf(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.KILLED, ProcessStatus.UNKNOWN)) {
                val repo = repo()
                val id = repo.obtainProcessId("a", emptyMap(), 100).processId
                repo.transitionStatus(id, ProcessStatus.WORKING, 1, guard = null)
                if (terminal == ProcessStatus.KILLED) {
                    repo.killTransition(id, 2)
                } else {
                    repo.transitionStatus(id, terminal, 2, guard = null)
                }

                SqlStatementRecorder.reset()
                val written = repo.setCurrentCommand(id, "cmd-1", 4242L)

                assertFalse(written, "a $terminal row must not be handed a running command")
                assertTrue(
                    SqlStatementRecorder.statements().none { it.trimStart().startsWith("UPDATE", ignoreCase = true) },
                    "$terminal row must not be written, ran: ${SqlStatementRecorder.statements()}",
                )
                val info = repo.getInfo(id)!!
                assertNull(info.currentCommand)
                assertNull(info.currentCommandStart)
            }
        }

    @Test
    fun `setCurrentCommand on a missing row writes nothing and returns false`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val repo = repo(db)
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.remove(id)

            SqlStatementRecorder.reset()
            val written = repo.setCurrentCommand(id, "cmd-1", 4242L)

            assertFalse(written)
            assertTrue(
                SqlStatementRecorder.statements().none { it.trimStart().startsWith("UPDATE", ignoreCase = true) },
                "missing row must not be written, ran: ${SqlStatementRecorder.statements()}",
            )
        }

    @Test
    fun `execution llmAccounting round-trips through the executions JSON`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.completeStep(
                id,
                execution("claude").copy(
                    llmAccounting = LlmAccounting(sessionId = "sess-1", costUsd = 0.42, source = CostSource.SESSION),
                ),
                null,
                null,
            )
            repo.completeStep(id, execution("cmd"), null, null)
            val execs = repo.getInfo(id)!!.executions
            assertEquals(LlmAccounting("sess-1", 0.42, CostSource.SESSION, null), execs[0].llmAccounting)
            assertNull(execs[1].llmAccounting)
        }

    @Test
    fun `execution kind round-trips and an old row without it reads as empty`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val repo = repo(db)
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.completeStep(
                id,
                ExecutionInfo(label = "l", start = 1, end = 2, resultCode = 0, stdOut = null, stdErr = null, errMsg = null, kind = "codex"),
                null,
                null,
            )
            assertEquals(
                "codex",
                repo
                    .getInfo(id)!!
                    .executions
                    .single()
                    .kind,
            )

            // Simulate a pre-release row: raw JSON without the field.
            db.query {
                Processes.update({ Processes.id eq id }) {
                    it[executions] = """[{"label":"l","start":1,"end":2,"resultCode":0,"stdOut":null,"stdErr":null,"errMsg":null}]"""
                }
            }
            assertEquals(
                "",
                repo
                    .getInfo(id)!!
                    .executions
                    .single()
                    .kind,
            )
        }

    @Test
    fun `cost_source starts as cli and stays cli for cli charges`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            assertEquals("cli", repo.getInfo(id)!!.costSource)
            repo.addCost(id, 0.1, CostSource.CLI)
            assertEquals("cli", repo.getInfo(id)!!.costSource)
        }

    @Test
    fun `first session charge sets cost_source to session`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.addCost(id, 0.1, CostSource.SESSION)
            assertEquals("session", repo.getInfo(id)!!.costSource)
            repo.addCost(id, 0.2, CostSource.SESSION)
            assertEquals("session", repo.getInfo(id)!!.costSource)
        }

    @Test
    fun `a charge from a different source than the first makes cost_source mixed`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.addCost(id, 0.1, CostSource.SESSION)
            repo.addCost(id, 0.2, CostSource.CLI)
            val info = repo.getInfo(id)!!
            assertEquals("mixed", info.costSource)
            assertEquals(0.3, info.costUsd, 1e-9)
            // mixed is sticky
            repo.addCost(id, 0.1, CostSource.SESSION)
            assertEquals("mixed", repo.getInfo(id)!!.costSource)
        }

    @Test
    fun `markInterruptedAsUnknown flips CREATED and WORKING to UNKNOWN`() =
        runTest {
            val repo = repo()
            val created = repo.obtainProcessId("a", emptyMap(), 100).processId
            val working = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(working, ProcessStatus.WORKING, 1, null)
            val finished = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(finished, ProcessStatus.FINISHED, 1, null)

            val reconciled = repo.markInterruptedAsUnknown(nowSec = 999)

            assertEquals(setOf(created, working), reconciled.toSet())
            assertEquals(ProcessStatus.UNKNOWN, repo.getStatus(created))
            assertEquals(ProcessStatus.UNKNOWN, repo.getStatus(working))
            assertEquals(ProcessStatus.FINISHED, repo.getStatus(finished))
            assertEquals(999L to ProcessStatus.UNKNOWN, repo.getHistory(working).last())
        }

    @Test
    fun `concurrent transitions do not lose history updates`() =
        // Real parallel writers (Dispatchers.IO) exercise the BEGIN IMMEDIATE
        // serialization that replaced the Mutex: every append must survive.
        runBlocking {
            val repo = repo(PersistenceTestSupport.tempDatabase(dispatcher = Dispatchers.IO))
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            val writers = 50

            coroutineScope {
                repeat(writers) {
                    launch(Dispatchers.IO) {
                        repo.transitionStatus(id, ProcessStatus.WORKING, 1, null)
                    }
                }
            }

            assertEquals(writers, repo.getHistory(id).size)
        }

    @Test
    fun `state survives close and reopen (durability round-trip)`() =
        runTest {
            val path = PersistenceTestSupport.tempDatabasePath()
            val db1 = PersistenceTestSupport.tempDatabase(path = path)
            val repo1 = ProcessRepository(db1, ProcessJobRegistry())

            val id = repo1.obtainProcessId("deploy", mapOf("TARGET" to "prod"), 100).processId
            repo1.transitionStatus(id, ProcessStatus.WORKING, 10, null)
            repo1.completeStep(id, execution("build"), null, null)
            repo1.setRunnerState(id) { RunnerState(alias = "k8s", podPhase = PodPhase.READY) }
            repo1.setArtifactsDir(id, "/artifacts/$id")
            db1.close()

            val db2 = PersistenceTestSupport.tempDatabase(path = path)
            val repo2 = ProcessRepository(db2, ProcessJobRegistry())
            val info = repo2.getInfo(id)

            assertNotNull(info)
            assertEquals(ProcessStatus.WORKING, info.status)
            assertEquals("deploy", info.agentName)
            assertEquals(mapOf("TARGET" to "prod"), info.inputParams)
            assertEquals(listOf("build"), info.executions.map { it.label })
            assertEquals(PodPhase.READY, info.runner?.podPhase)
            assertEquals(Paths.get("/artifacts/$id"), info.artifactsDir)
        }

    @Test
    fun `isArtifactsDirShared detects other rows referencing the same dir regardless of status`() =
        runTest {
            val repo = repo()
            val shared = "/srv/runs/improver/artifacts"

            val old = repo.obtainProcessId("improver", emptyMap(), 100).processId
            repo.setArtifactsDir(old, shared)
            repo.transitionStatus(old, ProcessStatus.FINISHED, 1, null)

            val active = repo.obtainProcessId("improver", emptyMap(), 100).processId
            repo.setArtifactsDir(active, shared)
            repo.transitionStatus(active, ProcessStatus.WORKING, 1, null)

            val unrelated = repo.obtainProcessId("other", emptyMap(), 100).processId
            repo.setArtifactsDir(unrelated, "/srv/runs/42/artifacts")

            assertTrue(repo.isArtifactsDirShared(shared, excludeProcessId = old))
            assertTrue(repo.isArtifactsDirShared(shared, excludeProcessId = active))
            assertFalse(repo.isArtifactsDirShared("/srv/runs/42/artifacts", excludeProcessId = unrelated))
        }

    @Test
    fun `isArtifactsDirSharedWithActiveRun counts only live siblings, never the excluded id (CWE-362)`() =
        runTest {
            val repo = repo()
            val shared = "/srv/runs/improver/artifacts"

            // Every terminal sibling must not block cleanup forever.
            for (terminal in listOf(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.KILLED, ProcessStatus.UNKNOWN)) {
                val id = repo.obtainProcessId("improver", emptyMap(), 100).processId
                repo.setArtifactsDir(id, shared)
                repo.transitionStatus(id, terminal, 1, null)
                assertFalse(
                    repo.isArtifactsDirSharedWithActiveRun(shared, excludeProcessId = id),
                    "$terminal sibling must not count as a live sharer",
                )
            }

            // A CREATED and a WORKING sibling both count as live.
            val created = repo.obtainProcessId("improver", emptyMap(), 100).processId
            repo.setArtifactsDir(created, shared)
            val working = repo.obtainProcessId("improver", emptyMap(), 100).processId
            repo.setArtifactsDir(working, shared)
            repo.transitionStatus(working, ProcessStatus.WORKING, 1, null)

            assertTrue(repo.isArtifactsDirSharedWithActiveRun(shared, excludeProcessId = created))
            assertTrue(repo.isArtifactsDirSharedWithActiveRun(shared, excludeProcessId = working))
            // A lone live row never counts as its own sibling.
            val lone = repo.obtainProcessId("improver", emptyMap(), 100).processId
            repo.setArtifactsDir(lone, "/srv/runs/lone/artifacts")
            assertFalse(repo.isArtifactsDirSharedWithActiveRun("/srv/runs/lone/artifacts", excludeProcessId = lone))

            // An unrelated dir is never shared.
            val unrelated = repo.obtainProcessId("other", emptyMap(), 100).processId
            repo.setArtifactsDir(unrelated, "/srv/runs/42/artifacts")
            repo.transitionStatus(unrelated, ProcessStatus.WORKING, 1, null)
            assertFalse(repo.isArtifactsDirSharedWithActiveRun("/srv/runs/42/artifacts", excludeProcessId = unrelated))
        }

    @Test
    fun `createdRunsOf returns only CREATED rows of the named agents`() =
        runTest {
            val repo = repo()
            val createdA = repo.obtainProcessId("a", emptyMap(), 100).processId
            val workingA = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(workingA, ProcessStatus.WORKING, 1, null)
            val createdB = repo.obtainProcessId("b", emptyMap(), 100).processId
            repo.obtainProcessId("c", emptyMap(), 100)

            val runs = repo.createdRunsOf(listOf("a", "b"))

            assertEquals(setOf(createdA to "a", createdB to "b"), runs.toSet())
        }

    @Test
    fun `createdRunsOf returns empty for an empty agent name collection without querying`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100)

            SqlStatementRecorder.reset()
            val runs = repo.createdRunsOf(emptyList())

            assertEquals(emptyList(), runs)
            assertTrue(SqlStatementRecorder.statements().isEmpty(), "expected no query, ran: ${SqlStatementRecorder.statements()}")
        }

    @Test
    fun `getAll skips the page query when offset is past the total`() =
        runTest {
            val repo = repo()
            repeat(3) { repo.obtainProcessId("a", emptyMap(), 100) }

            SqlStatementRecorder.reset()
            val result = repo.getAll(filterStatuses = null, orderAsc = true, offset = 3, limit = 100, agentName = null)

            assertEquals(3, result.total)
            assertTrue(result.processes.isEmpty())
            val paged = SqlStatementRecorder.statements().filter { "OFFSET" in it }
            assertTrue(paged.isEmpty(), "offset >= total must not scan the table, ran: $paged")
        }

    @Test
    fun `getAll runs the page query when offset is within the total`() =
        runTest {
            val repo = repo()
            repeat(3) { repo.obtainProcessId("a", emptyMap(), 100) }

            SqlStatementRecorder.reset()
            val result = repo.getAll(filterStatuses = null, orderAsc = true, offset = 2, limit = 100, agentName = null)

            assertEquals(1, result.processes.size)
            assertTrue(SqlStatementRecorder.statements().any { "OFFSET" in it })
        }

    @Test
    fun `getAll visibility filter unions owner match and visibleAgents membership`() =
        runTest {
            val repo = repo()
            val ciA = repo.obtainProcessId("a", emptyMap(), 100, owner = "ci").processId
            val bobB = repo.obtainProcessId("b", emptyMap(), 100, owner = "bob").processId
            val ciB = repo.obtainProcessId("b", emptyMap(), 100, owner = "ci").processId
            val bobA = repo.obtainProcessId("a", emptyMap(), 100, owner = "bob").processId

            val result =
                repo.getAll(
                    filterStatuses = null,
                    orderAsc = true,
                    offset = 0,
                    limit = 100,
                    agentName = null,
                    visibleOwner = "ci",
                    visibleAgents = setOf("a"),
                )

            // owner=ci (ciA, ciB) OR agent=a (ciA, bobA) -- bobB is excluded either way.
            assertEquals(setOf(ciA, ciB, bobA), result.processes.map { it.id }.toSet())
            assertEquals(3, result.total)
        }

    @Test
    fun `getAll visibleAgents null (admin) returns every row unfiltered`() =
        runTest {
            val repo = repo()
            val ciA = repo.obtainProcessId("a", emptyMap(), 100, owner = "ci").processId
            val bobB = repo.obtainProcessId("b", emptyMap(), 100, owner = "bob").processId
            val ciB = repo.obtainProcessId("b", emptyMap(), 100, owner = "ci").processId
            val bobA = repo.obtainProcessId("a", emptyMap(), 100, owner = "bob").processId

            val result =
                repo.getAll(
                    filterStatuses = null,
                    orderAsc = true,
                    offset = 0,
                    limit = 100,
                    agentName = null,
                    visibleOwner = "ci",
                    visibleAgents = null,
                )

            assertEquals(setOf(ciA, bobB, ciB, bobA), result.processes.map { it.id }.toSet())
            assertEquals(4, result.total)
        }

    @Test
    fun `getAll empty visibleAgents reduces to owner-only`() =
        runTest {
            val repo = repo()
            val ciA = repo.obtainProcessId("a", emptyMap(), 100, owner = "ci").processId
            repo.obtainProcessId("b", emptyMap(), 100, owner = "bob").processId
            val ciB = repo.obtainProcessId("b", emptyMap(), 100, owner = "ci").processId
            repo.obtainProcessId("a", emptyMap(), 100, owner = "bob").processId

            val result =
                repo.getAll(
                    filterStatuses = null,
                    orderAsc = true,
                    offset = 0,
                    limit = 100,
                    agentName = null,
                    visibleOwner = "ci",
                    visibleAgents = emptySet(),
                )

            assertEquals(setOf(ciA, ciB), result.processes.map { it.id }.toSet())
            assertEquals(2, result.total)
        }

    private fun execution(label: String) =
        ExecutionInfo(
            label = label,
            start = 1000L,
            end = 2000L,
            resultCode = 0,
            stdOut = Paths.get("/tmp/$label.out"),
            stdErr = Paths.get("/tmp/$label.err"),
            errMsg = null,
        )

    @Test
    fun `settleAccounting distinguishes a gone row from a pending accounting not recorded yet`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            val pending = LlmAccounting("sess-1", 0.42, CostSource.CLI, FALLBACK_REASON_SESSION_FIGURE_PENDING)
            val settled = LlmAccounting("sess-1", 0.18, CostSource.SESSION)

            // No step carries the pending accounting yet.
            assertEquals(SettlementSwap.PendingNotFound, repo.settleAccounting(id, pending, settled))

            repo.addExecution(id, execution("llm").copy(llmAccounting = pending))
            assertEquals(SettlementSwap.Settled, repo.settleAccounting(id, pending, settled))
            assertEquals(listOf(settled), repo.getExecutions(id).map { it.llmAccounting })

            // A removed row has nothing to merge the settlement into.
            val gone = repo.obtainProcessId("b", emptyMap(), 100).processId
            repo.remove(gone)
            assertEquals(SettlementSwap.RowGone, repo.settleAccounting(gone, pending, settled))
        }

    @Test
    fun `setTraceId round-trips via getInfo and stays null until stamped`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            assertEquals(null, repo.getInfo(id)?.traceId)

            repo.setTraceId(id, "0af7651916cd43dd8448eb211c80319c")

            assertEquals("0af7651916cd43dd8448eb211c80319c", repo.getInfo(id)?.traceId)
        }
    // --- cooldown lookups ---

    @Test
    fun `latestRunStartedAt returns the newest run's insert time`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("watcher", emptyMap(), 100, nowSec = 100)
            repo.obtainProcessId("watcher", emptyMap(), 100, nowSec = 500)
            repo.obtainProcessId("other", emptyMap(), 100, nowSec = 900)

            // Newest row for THIS agent. The value is `created_at`, written in the
            // INSERT's transaction — history is written later and would read empty here.
            assertEquals(500, repo.latestRunStartedAt("watcher"))
            assertEquals(null, repo.latestRunStartedAt("never-run"))
        }

    @Test
    fun `latestFailureEndedAt returns the newest FAILED run's terminal time`() =
        runTest {
            val repo = repo()
            val failed = repo.obtainProcessId("watcher", emptyMap(), 100).processId
            repo.transitionStatus(failed, ProcessStatus.WORKING, nowSec = 100, guard = null)
            repo.transitionStatus(failed, ProcessStatus.FAILED, nowSec = 200, guard = null)
            val ok = repo.obtainProcessId("watcher", emptyMap(), 100).processId
            repo.transitionStatus(ok, ProcessStatus.FINISHED, nowSec = 800, guard = null)

            // The later successful run must not hide the failure window.
            assertEquals(200, repo.latestFailureEndedAt("watcher"))
            assertEquals(null, repo.latestFailureEndedAt("never-failed"))
        }

    // ========== Cooldown enforced atomically with the INSERT (ADR-0051) ==========

    @Test
    fun `latestRunStartedAt sees a just-created row, before any history is written`() =
        runTest {
            // The defect this pins: the value used to come from the first `history`
            // entry, which the launched coroutine writes later. Since the query takes
            // the newest row only, a row inserted moments ago read back empty and
            // shadowed the real previous run — `latestRunStartedAt` returned null and
            // the whole `minInterval` check was skipped.
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100, nowSec = 1_000)

            assertEquals(1_000, repo.latestRunStartedAt("a"))
        }

    @Test
    fun `obtainProcessId refuses a second start inside the minInterval window`() =
        runTest {
            val repo = repo()
            val cooldown = Cooldown(minInterval = "1m")
            repo.obtainProcessId("a", emptyMap(), 100, cooldown = cooldown, nowSec = 1_000)

            val e =
                assertFailsWith<CooldownActiveException> {
                    repo.obtainProcessId("a", emptyMap(), 100, cooldown = cooldown, nowSec = 1_030)
                }

            assertEquals("a", e.agentName)
            // No row was inserted for the refused start.
            assertEquals(1, repo.lastProcessId())
        }

    @Test
    fun `obtainProcessId allows the next start once minInterval has elapsed`() =
        runTest {
            val repo = repo()
            val cooldown = Cooldown(minInterval = "1m")
            repo.obtainProcessId("a", emptyMap(), 100, cooldown = cooldown, nowSec = 1_000)

            assertEquals(2, repo.obtainProcessId("a", emptyMap(), 100, cooldown = cooldown, nowSec = 1_060).processId)
        }

    @Test
    fun `a cooldown on one agent does not bound another`() =
        runTest {
            val repo = repo()
            val cooldown = Cooldown(minInterval = "1m")
            repo.obtainProcessId("a", emptyMap(), 100, cooldown = cooldown, nowSec = 1_000)

            assertEquals(2, repo.obtainProcessId("b", emptyMap(), 100, cooldown = cooldown, nowSec = 1_001).processId)
        }

    @Test
    fun `concurrent starts of a cooled-down agent admit exactly one`() =
        // The bound only holds if the check and the INSERT share a transaction. With
        // the check outside it, every one of these writers observed "no previous run"
        // and all 20 were admitted. Real parallel writers on Dispatchers.IO, so this
        // exercises the BEGIN IMMEDIATE serialization rather than a single-threaded
        // interleaving that could never race.
        runBlocking {
            val repo = repo(PersistenceTestSupport.tempDatabase(dispatcher = Dispatchers.IO))
            val cooldown = Cooldown(minInterval = "1h")
            val starters = 20
            val admitted = AtomicInteger(0)
            val refused = AtomicInteger(0)

            coroutineScope {
                repeat(starters) {
                    launch(Dispatchers.IO) {
                        try {
                            repo.obtainProcessId("a", emptyMap(), 100, cooldown = cooldown, nowSec = 1_000)
                            admitted.incrementAndGet()
                        } catch (e: CooldownActiveException) {
                            refused.incrementAndGet()
                        }
                    }
                }
            }

            assertEquals(1, admitted.get(), "exactly one start may pass the cooldown")
            assertEquals(starters - 1, refused.get())
            assertEquals(1, repo.lastProcessId(), "a refused start must not leave a row behind")
        }

    @Test
    fun `obtainProcessId refuses inside the afterFailure window and allows outside it`() =
        runTest {
            val repo = repo()
            val cooldown = Cooldown(afterFailure = "1m")
            val failed = repo.obtainProcessId("a", emptyMap(), 100, nowSec = 1_000).processId
            repo.transitionStatus(failed, ProcessStatus.FAILED, 1_000, guard = null)

            assertFailsWith<CooldownActiveException> {
                repo.obtainProcessId("a", emptyMap(), 100, cooldown = cooldown, nowSec = 1_030)
            }
            assertEquals(2, repo.obtainProcessId("a", emptyMap(), 100, cooldown = cooldown, nowSec = 1_060).processId)
        }

    @Test
    fun `obtainProcessId without a cooldown is never refused`() =
        runTest {
            val repo = repo()
            repo.obtainProcessId("a", emptyMap(), 100, nowSec = 1_000)

            assertEquals(2, repo.obtainProcessId("a", emptyMap(), 100, nowSec = 1_000).processId)
        }

    @Test
    fun `latestFailureEndedAt follows failure time, not row order, when runs overlap`() =
        runTest {
            // Same-agent runs overlap by default (lockStrategy.key is per process,
            // maxParallelRuns is 10), so the newest row id is not the newest failure.
            // Ordering by id picked run B's EARLIER failure and let a start through
            // inside the afterFailure window.
            val repo = repo()
            val a = repo.obtainProcessId("flaky", emptyMap(), 100, nowSec = 1).processId
            val b = repo.obtainProcessId("flaky", emptyMap(), 100, nowSec = 2).processId
            repo.transitionStatus(b, ProcessStatus.FAILED, 100, guard = null)
            repo.transitionStatus(a, ProcessStatus.FAILED, 200, guard = null)

            assertEquals(200, repo.latestFailureEndedAt("flaky"))
        }

    @Test
    fun `afterFailure measures from the latest failure even when an older run failed last`() =
        runTest {
            val repo = repo()
            val cooldown = Cooldown(afterFailure = "1m")
            val a = repo.obtainProcessId("flaky", emptyMap(), 100, nowSec = 1).processId
            val b = repo.obtainProcessId("flaky", emptyMap(), 100, nowSec = 2).processId
            repo.transitionStatus(b, ProcessStatus.FAILED, 100, guard = null)
            repo.transitionStatus(a, ProcessStatus.FAILED, 200, guard = null)

            // t=250 is outside B's window (100+60) but inside A's (200+60).
            assertFailsWith<CooldownActiveException> {
                repo.obtainProcessId("flaky", emptyMap(), 100, cooldown = cooldown, nowSec = 250)
            }
            repo.obtainProcessId("flaky", emptyMap(), 100, cooldown = cooldown, nowSec = 260)
        }

    @Test
    fun `ended_at is stamped on terminal transitions and stays null while active`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100, nowSec = 10).processId
            repo.transitionStatus(id, ProcessStatus.WORKING, 20, guard = null)
            assertEquals(null, repo.latestFailureEndedAt("a"), "an active run has no terminal time")

            repo.transitionStatus(id, ProcessStatus.FAILED, 30, guard = null)
            assertEquals(30, repo.latestFailureEndedAt("a"))
        }

    @Test
    fun `killTransition and the interrupted sweep stamp a terminal time too`() =
        runTest {
            val repo = repo()
            val killed = repo.obtainProcessId("a", emptyMap(), 100, nowSec = 10).processId
            repo.transitionStatus(killed, ProcessStatus.WORKING, 11, guard = null)
            repo.killTransition(killed, nowSec = 40)

            val interrupted = repo.obtainProcessId("a", emptyMap(), 100, nowSec = 12).processId
            repo.markInterruptedAsUnknown(nowSec = 50)

            // Neither is FAILED, so neither feeds afterFailure; the point is that every
            // terminal write records when it happened, so ended_at cannot go stale.
            assertEquals(ProcessStatus.KILLED, repo.getStatus(killed))
            assertEquals(ProcessStatus.UNKNOWN, repo.getStatus(interrupted))
            assertEquals(null, repo.latestFailureEndedAt("a"))
        }

    @Test
    fun `secretInputKeys round-trip and stay null when the row predates them`() =
        runTest {
            val repo = repo()
            val withKeys =
                repo.obtainProcessId("a", mapOf("TOKEN" to "s"), 100, secretInputKeys = setOf("TOKEN")).processId
            val withoutKeys = repo.obtainProcessId("a", mapOf("MR" to "1"), 100).processId

            assertEquals(setOf("TOKEN"), repo.getInfo(withKeys)?.secretInputKeys)
            // A caller that captured no snapshot stores null, which reads back as
            // "ask the current definition". An empty set is different and meaningful:
            // it says the agent had no secret inputs at run time.
            assertEquals(null, repo.getInfo(withoutKeys)?.secretInputKeys)
            val noSecrets = repo.obtainProcessId("a", emptyMap(), 100, secretInputKeys = emptySet()).processId
            assertEquals(emptySet(), repo.getInfo(noSecrets)?.secretInputKeys)
        }

    @Test
    fun `a pre-v14 FAILED row with no terminal time never masks a real one`() =
        runTest {
            // latestFailureEndedAtTx orders by ended_at DESC and takes one row, which is
            // only correct because SQLite sorts NULLs last under DESC. Migrated rows carry
            // a null ended_at, so if that assumption were wrong one of them would win the
            // ordering and report "no recent failure" for an agent that just failed.
            val repo = repo()
            val migrated = repo.obtainProcessId("flaky", emptyMap(), 100, nowSec = 1).processId
            repo.transitionStatus(migrated, ProcessStatus.FAILED, 100, guard = null)
            repo.clearEndedAtForTest(migrated)
            val recent = repo.obtainProcessId("flaky", emptyMap(), 100, nowSec = 2).processId
            repo.transitionStatus(recent, ProcessStatus.FAILED, 300, guard = null)

            assertEquals(300, repo.latestFailureEndedAt("flaky"))
        }

    @Test
    fun `only pre-v14 FAILED rows read as no recorded failure`() =
        runTest {
            val repo = repo()
            val migrated = repo.obtainProcessId("flaky", emptyMap(), 100, nowSec = 1).processId
            repo.transitionStatus(migrated, ProcessStatus.FAILED, 100, guard = null)
            repo.clearEndedAtForTest(migrated)

            assertEquals(null, repo.latestFailureEndedAt("flaky"))
        }

    @Test
    fun `inputParams round-trip through getInfo and every getAll path from the side table`() =
        runTest {
            val repo = repo()
            val params = mapOf("TICKET" to "PROJ-77", "TARGET" to "prod")
            val a = repo.obtainProcessId("a", params, 100, tags = listOf("mr:1")).processId
            val b = repo.obtainProcessId("b", emptyMap(), 100).processId

            assertEquals(params, repo.getInfo(a)?.inputParams)
            // The page path reads inputs in one batched query, not per row.
            val page = repo.getAll(null, orderAsc = true, offset = 0, limit = 10, agentName = null)
            assertEquals(params, page.processes.single { it.id == a }.inputParams)
            assertEquals(emptyMap(), page.processes.single { it.id == b }.inputParams)
            // The tag-screened path maps inputs too — a screen may read them.
            val screened =
                repo.getAll(
                    null,
                    orderAsc = true,
                    offset = 0,
                    limit = 10,
                    agentName = null,
                    tags = listOf("mr:1"),
                    screenTags = identityScreen,
                )
            assertEquals(params, screened.processes.single().inputParams)
        }

    @Test
    fun `inputParams survives a step update untouched`() =
        runTest {
            val repo = repo()
            val params = mapOf("TICKET" to "PROJ-77")
            val id = repo.obtainProcessId("a", params, 100).processId

            // The two hot per-step writes: neither may disturb the side row.
            repo.setCurrentCommand(id, "cmd-1", 1)
            repo.addExecution(id, execution("cmd-1"))

            assertEquals(params, repo.getInfo(id)?.inputParams)
        }

    @Test
    fun `remove deletes the run's input row`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val repo = repo(db)
            val id = repo.obtainProcessId("a", mapOf("T" to "1"), 100).processId
            assertEquals(1, inputRowCount(db, id))

            repo.remove(id)

            assertEquals(null, repo.getInfo(id))
            assertEquals(0, inputRowCount(db, id))
        }

    @Test
    fun `eviction deletes the evicted run's input row`() =
        runTest {
            val db = PersistenceTestSupport.tempDatabase()
            val repo = repo(db)
            val id1 = repo.obtainProcessId("a", mapOf("T" to "1"), 2).processId
            repo.obtainProcessId("a", emptyMap(), 2).processId
            repo.transitionStatus(id1, ProcessStatus.FINISHED, 1, null)

            val result = repo.obtainProcessId("a", emptyMap(), 2)

            assertEquals(id1, result.evicted?.id)
            assertEquals(0, inputRowCount(db, id1))
        }

    @Test
    fun `the record-level reads project away the blobs and still serve the fields their callers use`() =
        runTest {
            val repo = repo()
            // The directory is still recorded, so this row is the workspace sweep's.
            val due = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(due, ProcessStatus.FINISHED, 1, null)
            repo.addExecution(
                due,
                ExecutionInfo(label = "cmd", start = 1, end = 2, resultCode = 0, stdOut = null, stdErr = null, errMsg = null),
            )
            repo.setRunnerState(due) { it ?: RunnerState(alias = "local", podPhase = PodPhase.RUNNING) }
            repo.setArtifactsDir(due, "/tmp/ws/projected/artifacts")
            repo.setPurgeAt(due, 500)
            // Nothing retained: no directory, so this row is the record sweep's.
            val swept = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(swept, ProcessStatus.FINISHED, 1, null)
            repo.setPurgeAt(swept, 500)

            SqlStatementRecorder.reset()
            val workspacePurgeable = repo.getWorkspacePurgeable(terminal, nowSec = 1000)
            val recordPurgeable = repo.getRecordPurgeable(terminal, nowSec = 1000)

            // The two sweep reads surface no history, executions or runner state, so they
            // must not drag those blobs off disk — a sweep at a full `maxProcesses` table
            // would otherwise decode every row's blobs.
            val selects = SqlStatementRecorder.statements().filter { it.startsWith("SELECT processes.") }
            assertEquals(2, selects.size, selects.toString())
            for (sql in selects) {
                assertTrue(sql.contains("processes.id"), sql)
                assertTrue(sql.contains("processes.status"), sql)
                assertTrue(sql.contains("processes.agent_name"), sql)
                assertFalse(sql.contains("processes.history"), sql)
                assertFalse(sql.contains("processes.executions"), sql)
                assertFalse(sql.contains("processes.runner_state"), sql)
            }
            // The projection still serves every non-blob field these paths read; the
            // omitted ones read as absent rather than throwing.
            assertEquals(listOf(due), workspacePurgeable.map { it.id })
            assertEquals(listOf(swept), recordPurgeable.map { it.id })
            for (info in workspacePurgeable + recordPurgeable) {
                assertEquals(ProcessStatus.FINISHED, info.status)
                assertEquals("a", info.agentName)
                assertEquals(emptyList(), info.history)
                assertEquals(emptyList(), info.executions)
                assertEquals(null, info.runner)
            }
            assertEquals("/tmp/ws/projected/artifacts", workspacePurgeable.single().artifactsDir.toString())
            assertEquals(500L, recordPurgeable.single().purgeAt)
        }

    @Test
    fun `transitionStatus and killTransition select only the columns they use`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", mapOf("BIG" to "x".repeat(4096)), 100).processId
            repo.transitionStatus(id, ProcessStatus.WORKING, 1, null)

            SqlStatementRecorder.reset()
            repo.transitionStatus(id, ProcessStatus.FINISHED, 2, null)
            repo.killTransition(id, nowSec = 3)

            val selects = SqlStatementRecorder.statements().filter { it.startsWith("SELECT") }
            assertEquals(2, selects.size, selects.toString())
            for (sql in selects) {
                // The projection is explicit; `*` is what made these reads drag the
                // inputs/executions blobs of the row into memory under the write lock.
                assertFalse(sql.contains("SELECT *"), sql)
                assertTrue(sql.contains("processes.status"), sql)
                assertTrue(sql.contains("processes.history"), sql)
                assertTrue(sql.contains("processes.agent_name"), sql)
                assertFalse(sql.contains("processes.input_params"), sql)
                assertFalse(sql.contains("processes.executions"), sql)
                assertFalse(sql.contains("processes.runner_state"), sql)
            }
        }

    /** Rows in `process_inputs` for [processId], read directly — the repository never exposes absence. */
    private suspend fun inputRowCount(
        db: DatabaseService,
        processId: Int,
    ): Int =
        db.read {
            ProcessInputs
                .selectAll()
                .where { ProcessInputs.processId eq processId }
                .count()
                .toInt()
        }

    // sqlite-jdbc can throw SQLITE_BUSY from the re-BEGIN IMMEDIATE it issues
    // inside commit(), after the commit already landed. Exposed counts that as a failed
    // attempt and re-runs the block, which for these append-shaped writes would duplicate
    // durable state. Each test below drives that failure through the real DatabaseService
    // and asserts the write happened exactly once.

    /** A fresh writer handle on [path] whose commit() lands and then throws. */
    private fun failingDb(path: String) =
        DatabaseService(path, TestFixtures.mockLogger(), wrapWriterConnection = PostCommitFailureConnection::wrap)

    @Test
    fun `a replayed start creates one process row and one tag set`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("d.db").toString()
        PersistenceTestSupport.tempDatabase(path)
        val failing = ProcessRepository(failingDb(path), ProcessJobRegistry())

        assertFailsWith<SQLException> {
            failing.obtainProcessId("a", emptyMap(), 100, tags = listOf("mr:15"))
        }

        val probe = repo(PersistenceTestSupport.tempDatabase(path))
        val ids = probe.getAll(null, orderAsc = true, offset = 0, limit = 10, agentName = null).processes.map { it.id }
        assertEquals(listOf(1), ids, "a replay must not create a second AUTOINCREMENT row")
        assertEquals(listOf("mr:15"), probe.getTags(1), "the tag set must not be written twice")
    }

    @Test
    fun `a replayed execution append adds one entry`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("d.db").toString()
        val setup = repo(PersistenceTestSupport.tempDatabase(path))
        val id = setup.obtainProcessId("a", emptyMap(), 100).processId
        val failing = ProcessRepository(failingDb(path), ProcessJobRegistry())
        val entry = ExecutionInfo(label = "step", start = 1, end = 2, resultCode = 0, stdOut = null, stdErr = null, errMsg = null)

        assertFailsWith<SQLException> { failing.addExecution(id, entry) }

        assertEquals(listOf(entry), repo(PersistenceTestSupport.tempDatabase(path)).getInfo(id)?.executions)
    }

    @Test
    fun `a replayed cost add charges the run once`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("d.db").toString()
        val setup = repo(PersistenceTestSupport.tempDatabase(path))
        val id = setup.obtainProcessId("a", emptyMap(), 100).processId
        val failing = ProcessRepository(failingDb(path), ProcessJobRegistry())

        assertFailsWith<SQLException> { failing.addCost(id, 2.5, CostSource.CLI) }

        assertEquals(2.5, repo(PersistenceTestSupport.tempDatabase(path)).getInfo(id)?.costUsd ?: 0.0, 1e-9)
    }

    // --- The batched LangGraph sub-run read ---

    @Test
    fun `subRunProgress reads each existing run's agent, status, executions and current command in one statement`() =
        runTest {
            val repo = repo()
            val step = ExecutionInfo(label = "setup", start = 0L, end = 1L, resultCode = 0, stdOut = null, stdErr = null, errMsg = null)
            val working = repo.obtainProcessId("worker-a", emptyMap(), 100).processId
            repo.transitionStatus(working, ProcessStatus.WORKING, 1, null)
            repo.completeStep(working, step, "lint", 2L)
            val finished = repo.obtainProcessId("worker-b", emptyMap(), 100).processId
            repo.transitionStatus(finished, ProcessStatus.FINISHED, 1, null)
            SqlStatementRecorder.reset()

            // 9_999 was evicted or never existed: absent, not an error. Duplicates are read once.
            val progress = repo.subRunProgress(listOf(working, 9_999, finished, working))

            assertEquals(
                mapOf(
                    working to SubRunProgress("worker-a", ProcessStatus.WORKING, listOf(step), "lint"),
                    finished to SubRunProgress("worker-b", ProcessStatus.FINISHED, emptyList(), null),
                ),
                progress,
            )
            val sent = SqlStatementRecorder.statements()
            assertEquals(1, sent.size, "$sent")
            // Only the columns a sub entry shows, never the history or runner-state blobs.
            assertFalse("history" in sent.single() || "runner_state" in sent.single(), sent.single())
        }

    @Test
    fun `denial_of_service - subRunProgress reads past SQLite's parameter limit in 512-id chunks`() =
        runTest {
            val repo = repo()
            // 513 ids force two chunks (512 + 1); a collapsed chunk loop would either throw on
            // the parameter cap or drop the rows past the first chunk.
            val ids = (1..513).map { repo.obtainProcessId("worker", emptyMap(), 1000).processId }
            SqlStatementRecorder.reset()

            val progress = repo.subRunProgress(ids)

            assertEquals(ids.toSet(), progress.keys)
            val sent = SqlStatementRecorder.statements()
            assertEquals(2, sent.size, "$sent")
        }

    @Test
    fun `subRunProgress with no ids issues no statement`() =
        runTest {
            val repo = repo()
            SqlStatementRecorder.reset()
            assertEquals(emptyMap(), repo.subRunProgress(emptyList()))
            assertTrue(SqlStatementRecorder.statements().isEmpty())
        }

    // ========== Pre-start failure reason (ADR-0020) ==========

    private suspend fun failedRun(repo: ProcessRepository): Int {
        val id = repo.obtainProcessId("a", emptyMap(), 100).processId
        repo.transitionStatus(id, ProcessStatus.WORKING, 10, setOf(ProcessStatus.CREATED))
        repo.transitionStatus(id, ProcessStatus.FAILED, 20, setOf(ProcessStatus.WORKING))
        return id
    }

    @Test
    fun `setFailureReason stores the reason and the holder on a run that never started`() =
        runTest {
            val repo = repo()
            val id = failedRun(repo)

            assertTrue(repo.setFailureReason(id, "Lock skip (SKIP_CURRENT)", 999))

            val info = repo.getInfo(id)!!
            assertEquals("Lock skip (SKIP_CURRENT)", info.failureReason)
            assertEquals(999, info.failureReasonRunId)
        }

    @Test
    fun `setFailureReason stores a null holder for a cause that names no run`() =
        runTest {
            val repo = repo()
            val id = failedRun(repo)

            assertTrue(repo.setFailureReason(id, "Runner claim queue full", null))

            assertEquals(null, repo.getInfo(id)!!.failureReasonRunId)
        }

    @Test
    fun `setFailureReason refuses a run that recorded an execution`() =
        runTest {
            val repo = repo()
            val id = failedRun(repo)
            repo.addExecution(
                id,
                ExecutionInfo(label = "cmd", start = 1, end = 2, resultCode = 1, stdOut = null, stdErr = null, errMsg = null),
            )

            assertFalse(repo.setFailureReason(id, "Lock skip (SKIP_CURRENT)", 999))

            assertEquals(null, repo.getInfo(id)!!.failureReason)
        }

    @Test
    fun `setFailureReason refuses a run that still shows a current command`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.WORKING, 10, setOf(ProcessStatus.CREATED))
            repo.setCurrentCommand(id, "cmd-running", 15)
            repo.transitionStatus(id, ProcessStatus.FAILED, 20, setOf(ProcessStatus.WORKING))

            assertFalse(repo.setFailureReason(id, "Lock skip (SKIP_CURRENT)", 999))

            assertEquals(null, repo.getInfo(id)!!.failureReason)
        }

    @Test
    fun `setFailureReason refuses a run a kill won`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId
            repo.transitionStatus(id, ProcessStatus.WORKING, 10, setOf(ProcessStatus.CREATED))
            repo.killTransition(id, 20)

            assertFalse(repo.setFailureReason(id, "Lock skip (SKIP_CURRENT)", 999))

            assertEquals(null, repo.getInfo(id)!!.failureReason)
        }

    @Test
    fun `setFailureReason refuses a live run and a missing row`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100).processId

            assertFalse(repo.setFailureReason(id, "Lock skip (SKIP_CURRENT)", 999))
            assertFalse(repo.setFailureReason(id + 100, "Lock skip (SKIP_CURRENT)", 999))
        }

    @Test
    fun `setFailureReason caps an over-long reason`() =
        runTest {
            val repo = repo()
            val id = failedRun(repo)

            assertTrue(repo.setFailureReason(id, "x".repeat(2000), null))

            assertEquals(MAX_FAILURE_REASON_LENGTH, repo.getInfo(id)!!.failureReason!!.length)
        }

    @Test
    fun `getRunVisibility reads the gate's two columns by key, or null for a missing row`() =
        runTest {
            val repo = repo()
            val id = repo.obtainProcessId("a", emptyMap(), 100, owner = "alice").processId

            assertEquals("alice" to "a", repo.getRunVisibility(id))
            assertNull(repo.getRunVisibility(id + 100))
        }
}
