package homer.app.exceptions

import homer.app.executors.PreStartFailureReason
import homer.app.models.MAX_FAILURE_REASON_LENGTH
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnresolvedLockKeyPlaceholderExceptionTest {
    @Test
    fun `one unresolved placeholder is named in the singular`() {
        val exception = UnresolvedLockKeyPlaceholderException(listOf("TICKET_ID"))

        assertEquals("Lock key placeholder {{TICKET_ID}} has no value", exception.message)
        assertEquals(listOf("TICKET_ID"), exception.placeholders)
    }

    @Test
    fun `several unresolved placeholders are named in the plural`() {
        val exception = UnresolvedLockKeyPlaceholderException(listOf("B", "A"))

        assertEquals("Lock key placeholders {{A}}, {{B}} have no value", exception.message)
        assertEquals(listOf("A", "B"), exception.placeholders)
    }

    @Test
    fun `a placeholder name is cut, so the echoed sentence stays short`() {
        val exception = UnresolvedLockKeyPlaceholderException(listOf("N".repeat(1000)))

        assertTrue(exception.placeholders.single().length < 1000, "name=${exception.placeholders.single()}")
        assertTrue(exception.message!!.length <= MAX_FAILURE_REASON_LENGTH, "message=${exception.message}")
    }

    @Test
    fun `the denial's log sentence and the stored reason share one placeholder sentence`() {
        // Both wordings are built from the same singular/plural sentence, so a wording change
        // reaches the log and the stored reason together (ADR-0098).
        for (names in listOf(listOf("TICKET_ID"), listOf("B", "A"))) {
            val exception = UnresolvedLockKeyPlaceholderException(names)
            val reason = PreStartFailureReason.of(exception)!!.reason

            assertEquals("Lock key ${placeholderSentence(exception.placeholders)}", exception.message)
            assertEquals("${exception.message} (lockStrategy.key), so this run never started.", reason)
        }
    }
}
