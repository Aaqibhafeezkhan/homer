package homer.app.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunnerFilterTest {
    @Test
    fun `alias describes itself`() {
        assertEquals("runner 'build-host'", RunnerFilter.Alias("build-host").describe())
    }

    @Test
    fun `tags describe themselves sorted`() {
        assertEquals("tags [gpu, linux]", RunnerFilter.Tags(setOf("linux", "gpu")).describe())
    }

    @Test
    fun `tags filter rejects an empty set`() {
        assertThrows<IllegalArgumentException> { RunnerFilter.Tags(emptySet()) }
    }

    @Test
    fun `tags filter rejects a malformed tag`() {
        val ex = assertThrows<IllegalArgumentException> { RunnerFilter.Tags(setOf("bad tag")) }
        assertTrue(ex.message!!.startsWith("runner tags filter"), ex.message)
    }
}
