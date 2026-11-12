package homer.app.services.persistence

import homer.app.models.ProcessStatus
import homer.app.services.HookStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HookRepositoryTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: HookRepository

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        db = DatabaseService(dir.resolve("h.db").toString(), LoggerFactory.getLogger("t"))
        db.migrate()
        repo = HookRepository(db) { 1000L }
    }

    private fun hook(
        processId: Int = 1,
        declaringAgent: String = "declarer",
        triggeringStatus: ProcessStatus = ProcessStatus.FINISHED,
        target: String = "notifier",
        owner: String? = "alice",
        createdAt: Long = 500,
    ) = NewPendingHook(
        processId = processId,
        declaringAgent = declaringAgent,
        triggeringStatus = triggeringStatus,
        targetAgentName = target,
        owner = owner,
        createdAt = createdAt,
    )

    /** Records [hooks] in one write transaction, as a status transition does. */
    private suspend fun record(
        vararg hooks: NewPendingHook,
        maxPending: Int = 100,
    ): List<Int> = db.query { insertPendingHooksTx(hooks.toList(), maxPending) }

    @Test
    fun `a recorded row round-trips every field as PENDING`() =
        runTest {
            val id = record(hook(processId = 7, triggeringStatus = ProcessStatus.KILLED)).single()

            val stored = assertNotNull(repo.get(id))
            assertEquals(7, stored.processId)
            assertEquals("declarer", stored.declaringAgent)
            assertEquals(ProcessStatus.KILLED, stored.triggeringStatus)
            assertEquals("notifier", stored.targetAgentName)
            assertEquals("alice", stored.owner)
            assertEquals(HookStatus.PENDING, stored.status)
            assertEquals(0, stored.fireAttempts)
            assertNull(stored.nextAttemptAt)
            assertNull(stored.firedProcessId)
            assertNull(stored.firedAt)
            assertNull(stored.error)
            assertEquals(500, stored.createdAt)
        }

    @Test
    fun `one row is recorded per entry, in declaration order`() =
        runTest {
            val ids = record(hook(target = "first"), hook(target = "second"), hook(target = "third"))

            assertEquals(3, ids.size)
            assertEquals(listOf("first", "second", "third"), repo.list(HookStatus.PENDING).map { it.targetAgentName })
        }

    @Test
    fun `no entries record nothing`() =
        runTest {
            assertEquals(emptyList(), record())
        }

    @Test
    fun `the global pending cap stops the insert at maxPending and drops the rest`() =
        runTest {
            // A fan-out burst sheds rather than queues: the run that caused it must not fail,
            // and the rows over the cap are never written at all. §12 rule 4.
            val ids = record(hook(target = "a"), hook(target = "b"), hook(target = "c"), maxPending = 2)

            assertEquals(2, ids.size)
            assertEquals(listOf("a", "b"), repo.list(HookStatus.PENDING).map { it.targetAgentName })
        }

    @Test
    fun `the cap counts rows already pending, not only the ones in this write`() =
        runTest {
            record(hook(target = "a"), hook(target = "b"), maxPending = 2)

            assertEquals(emptyList(), record(hook(target = "c"), maxPending = 2))
            assertEquals(2, repo.list(HookStatus.PENDING).size)
        }

    @Test
    fun `a settled row frees a slot under the cap`() =
        runTest {
            val id = record(hook(target = "a"), maxPending = 1).single()
            repo.claim(id)
            repo.markFailed(id, "boom", 1000L)

            assertEquals(1, record(hook(target = "b"), maxPending = 1).size)
        }

    @Test
    fun `claim wins once and loses the second time`() =
        runTest {
            val id = record(hook()).single()

            assertTrue(repo.claim(id))
            assertFalse(repo.claim(id))
            assertEquals(HookStatus.DISPATCHING, assertNotNull(repo.get(id)).status)
        }

    @Test
    fun `markDispatched settles only a claimed row`() =
        runTest {
            val id = record(hook()).single()

            assertFalse(repo.markDispatched(id, 42, 1000L))

            repo.claim(id)
            assertTrue(repo.markDispatched(id, 42, 1000L))
            val stored = assertNotNull(repo.get(id))
            assertEquals(HookStatus.DISPATCHED, stored.status)
            assertEquals(42, stored.firedProcessId)
            assertEquals(1000L, stored.firedAt)
        }

    @Test
    fun `releaseForRetry returns the row to pending with the attempt counted`() =
        runTest {
            val id = record(hook()).single()
            repo.claim(id)

            assertTrue(repo.releaseForRetry(id, "cooldown", 2, 1_120L))

            val stored = assertNotNull(repo.get(id))
            assertEquals(HookStatus.PENDING, stored.status)
            assertEquals(2, stored.fireAttempts)
            assertEquals(1_120L, stored.nextAttemptAt)
            assertEquals("cooldown", stored.error)
        }

    @Test
    fun `a released row is due only once its retry time has come`() =
        runTest {
            val id = record(hook()).single()
            repo.claim(id)
            repo.releaseForRetry(id, "cooldown", 1, 1_060L)

            assertEquals(emptyList(), repo.findDueRetries(1_059L))
            assertEquals(listOf(id), repo.findDueRetries(1_060L).map { it.id })
        }

    @Test
    fun `a row never released is not a due retry`() =
        runTest {
            record(hook())

            assertEquals(emptyList(), repo.findDueRetries(999_999L))
        }

    @Test
    fun `markDropped settles a claimed row without ever firing it`() =
        runTest {
            val id = record(hook()).single()
            repo.claim(id)

            assertTrue(repo.markDropped(id, "chain too deep", 1000L))

            val stored = assertNotNull(repo.get(id))
            assertEquals(HookStatus.DROPPED, stored.status)
            assertEquals("chain too deep", stored.error)
            assertNull(stored.firedProcessId)
        }

    @Test
    fun `list returns only the requested status, oldest first`() =
        runTest {
            val first = record(hook(target = "a")).single()
            val second = record(hook(target = "b")).single()
            repo.claim(second)
            repo.markDispatched(second, 9, 1000L)

            assertEquals(listOf(first), repo.list(HookStatus.PENDING).map { it.id })
            assertEquals(listOf(second), repo.list(HookStatus.DISPATCHED).map { it.id })
            assertEquals(emptyList(), repo.list(HookStatus.FAILED))
        }

    @Test
    fun `a hook left dispatching by a restart is settled failed once`() =
        runTest {
            // The crash window between the run start and markDispatched: the row must not be
            // re-fired, or the target would run twice for one status. ADR-0105.
            val id = record(hook()).single()
            repo.claim(id)

            val orphaned = repo.failOrphanedDispatching(2000L)

            assertEquals(listOf(id), orphaned.map { it.id })
            assertEquals(HookStatus.FAILED, orphaned.single().status)
            val stored = assertNotNull(repo.get(id))
            assertEquals(HookStatus.FAILED, stored.status)
            assertEquals(2000L, stored.firedAt)
            assertTrue(stored.error!!.contains("restart"), stored.error)
            assertEquals(emptyList(), repo.failOrphanedDispatching(3000L))
        }

    @Test
    fun `pruneSettled deletes only settled rows older than the cutoff`() =
        runTest {
            val old = record(hook(target = "old", createdAt = 100)).single()
            val recent = record(hook(target = "recent", createdAt = 900)).single()
            val pending = record(hook(target = "pending", createdAt = 100)).single()
            for (id in listOf(old, recent)) {
                repo.claim(id)
                repo.markFailed(id, "boom", 1000L)
            }

            assertEquals(1, repo.pruneSettled(500L))

            assertNull(repo.get(old))
            assertNotNull(repo.get(recent))
            assertNotNull(repo.get(pending))
        }

    @Test
    fun `pruneSettled never touches a row that is still in flight`() =
        runTest {
            val dispatching = record(hook(createdAt = 100)).single()
            repo.claim(dispatching)

            assertEquals(0, repo.pruneSettled(500L))
            assertEquals(HookStatus.DISPATCHING, assertNotNull(repo.get(dispatching)).status)
        }

    @Test
    fun `pruneSettled batches past one page`() =
        runTest {
            val ids = (1..5).map { record(hook(target = "t$it", createdAt = 100)).single() }
            for (id in ids) {
                repo.claim(id)
                repo.markFailed(id, "boom", 1000L)
            }

            assertEquals(5, repo.pruneSettled(500L, batchSize = 2))
            assertEquals(emptyList(), repo.list(HookStatus.FAILED))
        }
}
