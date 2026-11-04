package homer.app.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunnerAffinityValidationTest {
    @Test
    fun `accepts a tag-shaped group and a wait inside the bounds`() {
        RunnerAffinityValidation.validate("Agent 'deploy'", RunnerAffinity("gpu.a100", 1))
        RunnerAffinityValidation.validate("Agent 'deploy'", RunnerAffinity("x_1-2", RunnerAffinityValidation.MAX_WAIT_SEC))
    }

    @Test
    fun `rejects a group that is not tag-shaped, naming the owner and the field`() {
        for (bad in listOf("-leading", ".leading", "has space", "colon:x", "slash/x", "a".repeat(65))) {
            val ex =
                assertThrows<IllegalArgumentException>("expected rejection of '$bad'") {
                    RunnerAffinityValidation.validate("Agent 'deploy'", RunnerAffinity(bad, 30))
                }
            assertTrue(ex.message!!.startsWith("Agent 'deploy': runnerAffinity.group must match"), ex.message)
            // A rejected value is never echoed back. CWE-209
            assertFalse(ex.message!!.contains(bad), ex.message)
        }
        assertThrows<IllegalArgumentException> { RunnerAffinityValidation.validate("Agent 'deploy'", RunnerAffinity("", 30)) }
    }

    @Test
    fun `rejects waitSec outside 1 to the hard maximum`() {
        for (bad in listOf(0, -1, RunnerAffinityValidation.MAX_WAIT_SEC + 1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val ex =
                assertThrows<IllegalArgumentException>("expected rejection of waitSec $bad") {
                    RunnerAffinityValidation.validate("Agent 'deploy'", RunnerAffinity("gpu", bad))
                }
            assertTrue(
                ex.message!!.startsWith("Agent 'deploy': runnerAffinity.waitSec must be in 1..${RunnerAffinityValidation.MAX_WAIT_SEC}"),
                ex.message,
            )
        }
    }
}
