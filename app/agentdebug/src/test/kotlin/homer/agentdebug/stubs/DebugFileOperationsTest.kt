package homer.agentdebug.stubs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class DebugFileOperationsTest {
    private val ops = DebugFileOperations()

    @Test
    fun `listRegularFiles walks the tree for files only, up to the limit`(
        @TempDir dir: Path,
    ) {
        dir.resolve("a.txt").writeText("a")
        dir
            .resolve("sub")
            .createDirectories()
            .resolve("b.txt")
            .writeText("b")

        assertEquals(setOf(dir.resolve("a.txt"), dir.resolve("sub/b.txt")), ops.listRegularFiles(dir, limit = 10).toSet())
        assertEquals(1, ops.listRegularFiles(dir, limit = 1).size)
    }

    @Test
    fun `listRegularFiles of a missing path or a file is empty`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("f.txt").also { it.writeText("x") }

        assertEquals(emptyList(), ops.listRegularFiles(dir.resolve("missing"), limit = 10))
        assertEquals(emptyList(), ops.listRegularFiles(file, limit = 10))
    }
}
