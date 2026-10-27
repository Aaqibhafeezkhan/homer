package homer.app.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentGroupValidationTest {
    @Test
    fun `accepts a label and returns it unchanged`() {
        assertEquals("Platform / Billing", AgentGroupValidation.validate("Agent 'deploy'", "Platform / Billing"))
    }

    @Test
    fun `accepts null`() {
        assertNull(AgentGroupValidation.validate("Agent 'deploy'", null))
    }

    @Test
    fun `accepts a label of exactly the maximum length`() {
        val group = "g".repeat(AgentGroupValidation.MAX_GROUP_LENGTH)
        assertEquals(group, AgentGroupValidation.validate("Agent 'deploy'", group))
    }

    @Test
    fun `rejects a label past the maximum length and names the bound`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                AgentGroupValidation.validate("Agent 'deploy'", "g".repeat(AgentGroupValidation.MAX_GROUP_LENGTH + 1))
            }
        assertTrue(ex.message!!.contains("Agent 'deploy'"), ex.message)
        assertTrue(ex.message!!.contains("64"), ex.message)
    }

    @Test
    fun `rejects an empty or blank label`() {
        for (blank in listOf("", " ", "\t", "\n", "   ")) {
            val ex =
                assertThrows<IllegalArgumentException>("expected rejection of '${blank.replace("\n", "\\n")}'") {
                    AgentGroupValidation.validate("Agent 'deploy'", blank)
                }
            assertTrue(ex.message!!.contains("blank"), ex.message)
        }
    }

    @Test
    fun `rejects a label containing a control character`() {
        for (bad in listOf("a\u0000b", "line\nbreak", "tab\tseparated", "delete\u007Fme", "c1\u0085nel")) {
            val ex =
                assertThrows<IllegalArgumentException>("expected rejection of '${bad.map { it.code }}'") {
                    AgentGroupValidation.validate("Agent 'deploy'", bad)
                }
            assertTrue(ex.message!!.contains("control characters"), ex.message)
        }
    }

    @Test
    fun `does not echo the rejected label back in the message`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                AgentGroupValidation.validate("Agent 'deploy'", "leak-me\u0000")
            }
        assertTrue(!ex.message!!.contains("leak-me"), ex.message)
    }
}
