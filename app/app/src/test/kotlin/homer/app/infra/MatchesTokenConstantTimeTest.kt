package homer.app.infra

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [matchesTokenConstantTime] and a static regression guard for its
 * use in the production bearer-auth path.
 *
 * **Important honesty note:** because [matchesTokenConstantTime] returns the
 * same boolean as `expected == provided`, behavioural assertions alone cannot
 * detect a silent revert of the production call site to plain string
 * equality. Wall-clock timing assertions are too flaky on a JIT runtime to
 * stand in as a regression guard. We therefore combine:
 *
 *  1. Behavioural tests proving the helper itself returns false for any
 *     mismatch position and length, and true for equal strings, and
 *  2. A static source-grep test that fails if `AuthWiring.kt` reverts the
 *     auth lookup to `it.value.token ==` or stops referencing the helper.
 */
class MatchesTokenConstantTimeTest {
    private val expected = "s3cret-bearer-token-XYZ"

    @Test
    fun `mismatched bearer tokens reject at every position and length`() {
        val mismatchAtStart = "X3cret-bearer-token-XYZ"
        val mismatchInMiddle = "s3cret-Xearer-token-XYZ"
        val mismatchAtEnd = "s3cret-bearer-token-XYX"
        val mismatchDifferentLength = "s3cret-bearer-token"

        assertFalse(
            matchesTokenConstantTime(expected, mismatchAtStart),
            "Mismatch at first char must return false",
        )
        assertFalse(
            matchesTokenConstantTime(expected, mismatchInMiddle),
            "Mismatch in the middle must return false",
        )
        assertFalse(
            matchesTokenConstantTime(expected, mismatchAtEnd),
            "Mismatch at last char must return false",
        )
        assertFalse(
            matchesTokenConstantTime(expected, mismatchDifferentLength),
            "Length mismatch must return false",
        )
    }

    @Test
    fun `correct token still accepted`() {
        assertTrue(
            matchesTokenConstantTime(expected, expected),
            "Identical token strings must compare equal",
        )
    }

    @Test
    fun `empty strings compare equal and empty vs nonempty does not`() {
        assertTrue(matchesTokenConstantTime("", ""))
        assertFalse(matchesTokenConstantTime("", expected))
        assertFalse(matchesTokenConstantTime(expected, ""))
    }

    /**
     * Regression guard: fails if [matchesTokenConstantTime] is removed from
     * the production auth path or if the lookup reverts to plain string
     * equality. This catches silent reverts that behavioural tests alone
     * cannot, since `==` returns the same boolean as the helper.
     */
    @Test
    fun `production auth path uses matchesTokenConstantTime not equality`() {
        val authWiringKt = Paths.get("src/main/kotlin/homer/app/AuthWiring.kt")
        check(Files.exists(authWiringKt)) {
            "AuthWiring.kt not found at $authWiringKt - did the file move?"
        }
        val source = Files.readString(authWiringKt)

        assertTrue(
            source.contains("matchesTokenConstantTime(it.value.token, tokenCredential)"),
            "AuthWiring.kt must call matchesTokenConstantTime in the bearer auth lookup",
        )
        assertFalse(
            source.contains("it.value.token == tokenCredential"),
            "AuthWiring.kt must not use plain '==' for bearer token compare " +
                "(timing side-channel). Use matchesTokenConstantTime instead.",
        )
    }
}
