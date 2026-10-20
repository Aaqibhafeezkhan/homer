package homer.app.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunnerTagValidationTest {
    @Test
    fun `accepts well-formed tags and returns them as a set`() {
        assertEquals(
            setOf("linux", "gpu.a100", "x_1-2"),
            RunnerTagValidation.validate("runner 'k8s'", listOf("linux", "gpu.a100", "x_1-2")),
        )
    }

    @Test
    fun `accepts an empty collection`() {
        assertEquals(emptySet(), RunnerTagValidation.validate("runner 'k8s'", emptyList()))
    }

    @Test
    fun `rejects tags outside the character set or over 64 characters`() {
        for (bad in listOf("", "-leading", ".leading", "has space", "colon:x", "slash/x", "a".repeat(65))) {
            val ex =
                assertThrows<IllegalArgumentException>(
                    "expected rejection of '$bad'",
                ) { RunnerTagValidation.validate("runner 'k8s'", listOf(bad)) }
            assertTrue(ex.message!!.startsWith("runner 'k8s'"), ex.message)
        }
    }

    @Test
    fun `rejects more than 32 tags`() {
        val ex = assertThrows<IllegalArgumentException> { RunnerTagValidation.validate("Agent 'a'", (1..33).map { "t$it" }) }
        assertTrue(ex.message!!.contains("32"), ex.message)
    }

    @Test
    fun `rejects duplicates`() {
        val ex = assertThrows<IllegalArgumentException> { RunnerTagValidation.validate("Agent 'a'", listOf("linux", "linux")) }
        assertTrue(ex.message!!.contains("duplicate"), ex.message)
    }

    @Test
    fun `is case-sensitive`() {
        assertEquals(setOf("Linux", "linux"), RunnerTagValidation.validate("x", listOf("Linux", "linux")))
    }
}
