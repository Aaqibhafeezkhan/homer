package homer.app.infra

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PasswordVerifierTest {
    private val verifier = PasswordVerifier()

    @Test
    fun `hash then verify round-trips`() {
        val hash = verifier.hash("hunter2", cost = 4)
        assertTrue(verifier.verify("hunter2", hash))
    }

    @Test
    fun `wrong password fails verification`() {
        val hash = verifier.hash("hunter2", cost = 4)
        assertFalse(verifier.verify("not-it", hash))
    }

    @Test
    fun `dummyHashAtCost produces a hash that verifies the documented plaintext`() {
        val hash = verifier.dummyHashAtCost(4)
        assertTrue(
            verifier.verify(PasswordVerifier.DUMMY_PLAINTEXT, hash),
            "dummy hash must be a valid bcrypt hash of DUMMY_PLAINTEXT",
        )
    }

    @Test
    fun `dummyHashAtCost rejects arbitrary user passwords`() {
        val hash = verifier.dummyHashAtCost(4)
        assertFalse(verifier.verify("password", hash))
        assertFalse(verifier.verify("admin", hash))
    }

    @Test
    fun `dummyHashAtCost embeds the requested cost in the bcrypt prefix`() {
        // Closes the timing oracle that existed when a fixed-cost dummy was paired
        // with a configurable bcryptCost: the dummy must match the configured cost
        // so the unknown-user verify takes the same wall-clock time as a real one.
        val prefix = Regex("""^\$2[aby]\$(\d{2})\$""")
        listOf(4, 6, 10).forEach { cost ->
            val match = prefix.find(verifier.dummyHashAtCost(cost))
            assertNotNull(match, "dummy hash at cost $cost should have a bcrypt prefix")
            assertEquals(cost, match.groupValues[1].toInt(), "dummy hash cost prefix must match requested cost")
        }
    }

    @Test
    fun `dummyHashAtCost memoizes per cost`() {
        // Same cost returns the same exact String reference — the memoization is
        // what makes the pre-warm pay off (one computeIfAbsent at startup, every
        // login afterwards is just verify).
        val first = verifier.dummyHashAtCost(4)
        val second = verifier.dummyHashAtCost(4)
        assertSame(first, second, "dummyHashAtCost(cost) must be memoized")
    }
}
