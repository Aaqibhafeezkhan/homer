package homer.app.runners

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputCapTest {
    @TempDir
    lateinit var dir: Path

    private fun file(
        name: String,
        bytes: Int,
    ): Path = dir.resolve(name).also { it.writeText("x".repeat(bytes)) }

    @Test
    fun `the cap is exceeded only when one stream is strictly longer than it`() {
        assertFalse(exceedsOutputCap(file("out", 10), file("err", 10), maxBytes = 10))
        assertTrue(exceedsOutputCap(file("out", 11), file("err", 0), maxBytes = 10))
        assertTrue(exceedsOutputCap(file("out", 0), file("err", 11), maxBytes = 10))
    }
}
