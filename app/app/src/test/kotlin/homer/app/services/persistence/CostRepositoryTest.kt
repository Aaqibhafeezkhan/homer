package homer.app.services.persistence

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CostRepositoryTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: CostRepository

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        db = DatabaseService(dir.resolve("d.db").toString(), LoggerFactory.getLogger("t"))
        db.migrate()
        repo = CostRepository(db)
    }

    /** Epoch seconds for a UTC wall-clock time — keeps month-boundary tests readable. */
    private fun utcSec(
        y: Int,
        m: Int,
        d: Int,
        h: Int = 12,
        min: Int = 0,
        s: Int = 0,
    ): Long = LocalDateTime.of(y, m, d, h, min, s).toEpochSecond(ZoneOffset.UTC)

    @Test
    fun `aggregates are zero or empty on an empty table`() =
        runTest {
            assertEquals(0.0, repo.sumGlobalSince(0), 1e-9)
            assertEquals(0.0, repo.sumAgentSince("a", 0), 1e-9)
            assertTrue(repo.sumPerAgentSince(0).isEmpty())
            assertTrue(repo.sumPerAgentBetween(0, Long.MAX_VALUE).isEmpty())
            assertTrue(repo.totalPerAgent().isEmpty())
            assertTrue(repo.availableMonths().isEmpty())
        }

    @Test
    fun `window sums include only entries at or after the cutoff`() =
        runTest {
            repo.insert("a", 100, 1.0)
            repo.insert("a", 200, 2.0)
            repo.insert("b", 200, 4.0)
            assertEquals(7.0, repo.sumGlobalSince(100), 1e-9)
            assertEquals(6.0, repo.sumGlobalSince(101), 1e-9)
            assertEquals(2.0, repo.sumAgentSince("a", 150), 1e-9)
            assertEquals(3.0, repo.sumAgentSince("a", 100), 1e-9)
            assertEquals(mapOf("a" to 2.0, "b" to 4.0), repo.sumPerAgentSince(150))
        }

    @Test
    fun `sumPerAgentBetween respects UTC month boundaries`() =
        runTest {
            repo.insert("a", utcSec(2026, 6, 30, 23, 59, 59), 1.0) // last second of June
            repo.insert("a", utcSec(2026, 7, 1, 0, 0, 0), 2.0) // first second of July
            repo.insert("b", utcSec(2026, 7, 15), 4.0)
            val juneStart = utcSec(2026, 6, 1, 0, 0, 0)
            val julyStart = utcSec(2026, 7, 1, 0, 0, 0)
            val augStart = utcSec(2026, 8, 1, 0, 0, 0)
            assertEquals(mapOf("a" to 2.0, "b" to 4.0), repo.sumPerAgentBetween(julyStart, augStart))
            assertEquals(mapOf("a" to 1.0), repo.sumPerAgentBetween(juneStart, julyStart))
        }

    @Test
    fun `totalPerAgent sums the full history regardless of age`() =
        runTest {
            repo.insert("a", utcSec(2025, 1, 1), 1.0)
            repo.insert("a", utcSec(2026, 7, 1), 2.0)
            repo.insert("b", utcSec(2026, 7, 2), 0.5)
            assertEquals(mapOf("a" to 3.0, "b" to 0.5), repo.totalPerAgent())
        }

    @Test
    fun `availableMonths returns distinct UTC months newest first`() =
        runTest {
            repo.insert("a", utcSec(2026, 6, 15), 1.0)
            repo.insert("a", utcSec(2026, 7, 1), 1.0)
            repo.insert("b", utcSec(2026, 7, 20), 1.0)
            assertEquals(listOf("2026-07", "2026-06"), repo.availableMonths())
        }

    @Test
    fun `a commit that throws after it landed charges the ledger once`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("failing.db").toString()
        DatabaseService(path, LoggerFactory.getLogger("t")).migrate()
        // A writer handle on the same file whose commit() lands then throws: the
        // append-only ledger (ADR-0035) carries no dedupe key, so a replay would
        // double-count the spend window.
        val failing = DatabaseService(path, LoggerFactory.getLogger("t"), wrapWriterConnection = PostCommitFailureConnection::wrap)

        assertFailsWith<SQLException> { CostRepository(failing).insert("a", 100, 2.5) }

        val probe = CostRepository(DatabaseService(path, LoggerFactory.getLogger("t")))
        assertEquals(2.5, probe.sumGlobalSince(0), 1e-9)
        assertEquals(listOf("a" to 2.5), probe.sumPerAgentSince(0).toList())
    }
}
