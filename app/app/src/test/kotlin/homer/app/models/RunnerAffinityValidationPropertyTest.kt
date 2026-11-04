package homer.app.models

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RunnerAffinityValidationPropertyTest {
    @Test
    fun `a group is accepted exactly when a runner tag of the same shape is`() =
        runTest {
            checkAll(Arb.string(0..200), Arb.int(-5..RunnerAffinityValidation.MAX_WAIT_SEC + 5)) { raw, waitSec ->
                val accepted =
                    runCatching { RunnerAffinityValidation.validate("Agent 'a'", RunnerAffinity(raw, waitSec)) }.isSuccess
                val groupOk = runCatching { RunnerTagValidation.validate("Agent 'a'", listOf(raw)) }.isSuccess
                val waitOk = waitSec in 1..RunnerAffinityValidation.MAX_WAIT_SEC
                assertEquals(groupOk && waitOk, accepted, "group codepoints: ${raw.map { it.code }} waitSec: $waitSec")
            }
        }

    @Test
    fun `waitSec is accepted exactly inside 1 to the hard maximum`() =
        runTest {
            checkAll(Arb.int(-10_000..200_000)) { waitSec ->
                val accepted = runCatching { RunnerAffinityValidation.validate("Agent 'a'", RunnerAffinity("gpu", waitSec)) }.isSuccess
                assertEquals(waitSec in 1..RunnerAffinityValidation.MAX_WAIT_SEC, accepted, "waitSec: $waitSec")
            }
        }

    @Test
    fun `a rejected group is never echoed back`() =
        runTest {
            checkAll(Arb.string(0..80)) { raw ->
                // A leading '/' can never start a tag-shaped group, so every generated value is rejected.
                val group = "/$raw"
                val ex =
                    assertThrows<IllegalArgumentException>("group codepoints: ${group.map { it.code }}") {
                        RunnerAffinityValidation.validate("Agent 'a'", RunnerAffinity(group, 1))
                    }
                assertFalse(ex.message!!.contains(group), ex.message)
            }
        }
}
