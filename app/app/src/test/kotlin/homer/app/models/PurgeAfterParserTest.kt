// app/app/src/test/kotlin/homer/app/models/PurgeAfterParserTest.kt
package homer.app.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PurgeAfterParserTest {
    @Test
    fun `parses minutes`() {
        assertEquals(1800L, PurgeAfterParser.toSeconds("30m"))
    }

    @Test
    fun `parses hours`() {
        assertEquals(7200L, PurgeAfterParser.toSeconds("2h"))
    }

    @Test
    fun `parses days`() {
        assertEquals(432000L, PurgeAfterParser.toSeconds("5d"))
    }

    @Test
    fun `parses weeks`() {
        assertEquals(604800L, PurgeAfterParser.toSeconds("1w"))
    }

    @Test
    fun `parses single minute`() {
        assertEquals(60L, PurgeAfterParser.toSeconds("1m"))
    }

    @Test
    fun `throws on unknown unit`() {
        assertFailsWith<IllegalArgumentException> {
            PurgeAfterParser.toSeconds("3x")
        }
    }

    @Test
    fun `throws on empty string`() {
        assertFailsWith<IllegalArgumentException> {
            PurgeAfterParser.toSeconds("")
        }
    }

    @Test
    fun `throws on number only`() {
        assertFailsWith<NumberFormatException> {
            PurgeAfterParser.toSeconds("42")
        }
    }

    @Test
    fun `throws on zero amount`() {
        assertFailsWith<IllegalArgumentException> {
            PurgeAfterParser.toSeconds("0m")
        }
    }

    @Test
    fun `throws on negative amount`() {
        assertFailsWith<IllegalArgumentException> {
            PurgeAfterParser.toSeconds("-1h")
        }
    }
}
