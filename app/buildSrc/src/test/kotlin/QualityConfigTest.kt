import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QualityConfigTest {
    @Test
    fun `the crap check's threshold is read from checks json`() {
        val json = """{"checks":[{"name":"x","mode":"warn"},{"name":"crap","mode":"required","threshold":25}]}"""
        assertEquals(25.0, QualityConfig.crapThreshold(json))
    }

    @Test
    fun `a missing crap check or threshold reads as null`() {
        assertNull(QualityConfig.crapThreshold("""{"checks":[{"name":"x"}]}"""))
        assertNull(QualityConfig.crapThreshold("""{"checks":[{"name":"crap"}]}"""))
        assertNull(QualityConfig.crapThreshold("""[]"""))
    }

    @Test
    fun `the repository's checks json names a crap threshold`() {
        val file = File("../../tools/quality/checks.json")
        assertEquals(true, QualityConfig.crapThreshold(file.readText())?.let { it > 0 })
    }
}
