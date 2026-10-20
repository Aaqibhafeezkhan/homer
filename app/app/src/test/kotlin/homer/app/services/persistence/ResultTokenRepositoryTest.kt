package homer.app.services.persistence

import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultTokenRepositoryTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: ResultTokenRepository
    private lateinit var dbPath: String

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        dbPath = dir.resolve("d.db").toString()
        db = DatabaseService(dbPath, LoggerFactory.getLogger("t"))
        db.migrate()
        repo = ResultTokenRepository(db)
    }

    private fun record(
        token: String,
        expiresAt: Long? = null,
        transformer: ResultEnvParam? = null,
    ) = ResultTokenRecord(
        token = token,
        watchedProcessId = 42,
        path = "cmd-0.out",
        resultTransformer = transformer,
        sizeBytes = 1_000_000,
        createdAt = 1,
        expiresAt = expiresAt,
        consumerAgent = "parent-agent",
    )

    @Test
    fun `create then get round-trips fields including transformer`() =
        runTest {
            val transformer = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.summary"))
            repo.create(record("tok-1", expiresAt = 500, transformer = transformer))
            val found = repo.get("tok-1")!!
            assertEquals(42, found.watchedProcessId)
            assertEquals("cmd-0.out", found.path)
            assertEquals(transformer, found.resultTransformer)
            assertEquals(1_000_000, found.sizeBytes)
            assertEquals(500, found.expiresAt)
            // The token is audience-bound to the agent that will redeem it (ADR-0025).
            assertEquals("parent-agent", found.consumerAgent)
        }

    @Test
    fun `get returns null for an unknown token`() =
        runTest {
            assertNull(repo.get("nope"))
        }

    @Test
    fun `deleteExpired removes lapsed tokens and keeps live or non-expiring ones`() =
        runTest {
            repo.create(record("expired", expiresAt = 100))
            repo.create(record("live", expiresAt = 300))
            repo.create(record("forever", expiresAt = null))
            val removed = repo.deleteExpired(now = 200)
            assertEquals(1, removed)
            assertNull(repo.get("expired"))
            assertEquals("live", repo.get("live")!!.token)
            assertEquals("forever", repo.get("forever")!!.token)
        }

    // --- Idle sweep takes no write lock ---

    @Test
    fun `deleteExpired with no due row returns promptly while another connection holds the write lock`() =
        runTest {
            repo.create(record("forever", expiresAt = null))
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
                writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                var removed = -1
                val elapsedMs = measureTimeMillis { removed = repo.deleteExpired(now = 999_999) }
                writer.createStatement().use { it.execute("ROLLBACK") }

                // A sweep that took the write lock would sit out the whole 5 s busy_timeout.
                assertTrue(elapsedMs < 2_000, "idle sweep waited $elapsedMs ms behind the writer")
                assertEquals(0, removed)
            }
        }
}
