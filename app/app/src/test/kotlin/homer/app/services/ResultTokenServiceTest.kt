package homer.app.services

import homer.app.models.DispatchConfig
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.ResultTokenRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultTokenServiceTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: ResultTokenRepository
    private var now: Long = 1_000

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        db = DatabaseService(dir.resolve("d.db").toString(), LoggerFactory.getLogger("t"))
        db.migrate()
        repo = ResultTokenRepository(db)
    }

    private fun service(ttlSec: Int = 3600) =
        ResultTokenService(
            repository = repo,
            logger = LoggerFactory.getLogger("t"),
            config = DispatchConfig(resultTokenTtlSec = ttlSec),
            nowSec = { now },
        )

    @Test
    fun `mint then resolve returns the record with expiry now plus ttl`() =
        runTest {
            val svc = service(ttlSec = 600)
            val transformer = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.summary"))
            val token =
                svc.mint(
                    watchedProcessId = 7,
                    path = "cmd-0.out",
                    transformer = transformer,
                    sizeBytes = 9_000,
                    consumerAgent = "parent-agent",
                )
            assertEquals(64, token.length) // 32 bytes hex

            val record = svc.resolve(token)!!
            assertEquals(7, record.watchedProcessId)
            assertEquals("cmd-0.out", record.path)
            assertEquals(transformer, record.resultTransformer)
            assertEquals(9_000, record.sizeBytes)
            assertEquals(now + 600, record.expiresAt)
            // Minted for the continuation's target agent; the fetch is audience-bound to it.
            assertEquals("parent-agent", record.consumerAgent)
        }

    @Test
    fun `resolve returns null for an unknown token`() =
        runTest {
            assertNull(service().resolve("does-not-exist"))
        }

    @Test
    fun `resolve enforces TTL`() =
        runTest {
            val svc = service(ttlSec = 60)
            val token = svc.mint(watchedProcessId = 1, path = "p", transformer = null, sizeBytes = 1, consumerAgent = "a")
            // Still valid just before expiry, gone at/after expiry.
            now += 59
            assertTrue(svc.resolve(token) != null)
            now += 1 // now == createdAt + 60 == expiresAt
            assertNull(svc.resolve(token))
        }

    @Test
    fun `mint produces distinct tokens`() =
        runTest {
            val svc = service()
            val a = svc.mint(1, "p", null, 1, "agent")
            val b = svc.mint(1, "p", null, 1, "agent")
            assertTrue(a != b)
        }

    @Test
    fun `sweepExpired reaps lapsed tokens`() =
        runTest {
            val svc = service(ttlSec = 60)
            val token = svc.mint(1, "p", null, 1, "agent")
            now += 120
            svc.sweepExpired()
            // The row is physically gone, not just TTL-guarded at resolve time.
            assertNull(repo.get(token))
        }
}
