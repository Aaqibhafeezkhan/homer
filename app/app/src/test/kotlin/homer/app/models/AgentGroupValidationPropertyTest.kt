package homer.app.models

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AgentGroupValidationPropertyTest {
    private val controlCharacters = listOf('\u0000', '\u0007', '\t', '\n', '\r', '\u001B', '\u007F', '\u0085', '\u009F')

    @Test
    fun `a group is accepted exactly when it is non-blank, within the cap and control-free`() =
        runTest {
            checkAll(Arb.string(0..200)) { raw ->
                val accepted = runCatching { AgentGroupValidation.validate("Agent 'p'", raw) }.isSuccess
                val expected =
                    raw.isNotBlank() &&
                        raw.length <= AgentGroupValidation.MAX_GROUP_LENGTH &&
                        raw.none { it.isISOControl() }
                assertEquals(expected, accepted, "group codepoints: ${raw.map { it.code }}")
            }
        }

    @Test
    fun `a label carrying a control character is never accepted`() =
        runTest {
            checkAll(Arb.string(0..40), Arb.element(controlCharacters)) { base, control ->
                val group = base + control
                assertThrows<IllegalArgumentException>("group codepoints: ${group.map { it.code }}") {
                    AgentGroupValidation.validate("Agent 'p'", group)
                }
            }
        }

    @Test
    fun `a label past the cap is never accepted`() =
        runTest {
            checkAll(Arb.string(0..40), Arb.int(1..40)) { base, extra ->
                val group = base + "x".repeat(AgentGroupValidation.MAX_GROUP_LENGTH + extra)
                assertThrows<IllegalArgumentException>("group length: ${group.length}") {
                    AgentGroupValidation.validate("Agent 'p'", group)
                }
            }
        }
}
