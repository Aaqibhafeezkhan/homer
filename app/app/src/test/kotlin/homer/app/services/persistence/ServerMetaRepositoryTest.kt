package homer.app.services.persistence

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerMetaRepositoryTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: ServerMetaRepository

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        db = DatabaseService(dir.resolve("d.db").toString(), LoggerFactory.getLogger("t"))
        db.migrate()
        repo = ServerMetaRepository(db)
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
        val afterRestart = ServerMetaRepository(db).getOrCreateInstanceId()
        assertEquals(original, afterRestart)
    }
}
