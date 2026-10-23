package homer.app.services.persistence

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerMetaRepositoryTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: ServerMetaRepository
    private val logger: Logger = mockk(relaxed = true)

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        db = DatabaseService(dir.resolve("d.db").toString(), LoggerFactory.getLogger("t"))
        db.migrate()
        repo = ServerMetaRepository(db, logger)
    }

    @Test
    fun `getOrCreateInstanceId returns a stable id across calls`() {
        val first = repo.getOrCreateInstanceId()
        val second = repo.getOrCreateInstanceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, second, "the persisted instance id must be stable")
    }

    @Test
    fun `instance id persists across repository instances on the same database`() {
        val original = repo.getOrCreateInstanceId()
        // A fresh repository over the same DB (models a restart) must read the same id,
        // so the k8s sweep still recognizes this instance's own orphan pods.
        val afterRestart = ServerMetaRepository(db, logger).getOrCreateInstanceId()
        assertEquals(original, afterRestart)
    }

    @Test
    fun `maintenance mode is off when the key has never been written`() {
        assertFalse(repo.readMaintenanceMode())
    }

    @Test
    fun `maintenance mode round-trips through the meta table`() {
        runBlocking { repo.writeMaintenanceMode(true) }
        assertTrue(repo.readMaintenanceMode())
        runBlocking { repo.writeMaintenanceMode(false) }
        assertFalse(repo.readMaintenanceMode())
    }

    @Test
    fun `maintenance mode survives a restart, and the write is idempotent`() {
        runBlocking { repo.writeMaintenanceMode(true) }
        // Re-writing the same value must update the existing row, not fail on the primary key.
        runBlocking { repo.writeMaintenanceMode(true) }
        // A fresh repository over the same database models a restart.
        assertTrue(ServerMetaRepository(db, logger).readMaintenanceMode())
    }

    @Test
    fun `a corrupt stored value is logged and read as off`() {
        runBlocking {
            db.query {
                ServerMeta.insert {
                    it[key] = "maintenance_mode"
                    it[value] = "yes"
                }
            }
        }

        assertFalse(repo.readMaintenanceMode(), "an unreadable flag must never hold starts back")
        verify { logger.warn(any<String>(), any<String>(), any<String>()) }
    }
}
