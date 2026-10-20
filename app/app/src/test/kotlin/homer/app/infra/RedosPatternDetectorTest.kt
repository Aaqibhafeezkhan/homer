package homer.app.infra

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RedosPatternDetectorTest {
    @Test
    fun `flags nested unbounded quantifiers`() {
        listOf("(a+)+", "(a*)*", "(a+)*", "(a*)+", "^(a+)+$", "((ab)+)+", "(?:a+)+", "(\\d+)*").forEach {
            assertNotNull(RedosPatternDetector.vulnerabilityReason(it), "expected $it to be flagged")
        }
    }

    @Test
    fun `flags unbounded interval nesting`() {
        assertNotNull(RedosPatternDetector.vulnerabilityReason("(a{1,})+"))
    }

    @Test
    fun `flags quantified overlapping alternations`() {
        listOf("(a|a)*", "(a|ab)*", "(a|)+", "(abc|ab)*").forEach {
            assertNotNull(RedosPatternDetector.vulnerabilityReason(it), "expected $it to be flagged")
        }
    }

    @Test
    fun `flags overlapping alternations behind group prefixes and nesting`() {
        listOf(
            "(?:a|ab)*", // non-capturing group
            "(?:a|ab)+",
            "((a|ab))*", // one level of nesting
            "(((a|ab)))+", // deeper nesting
            "((a|ab)|c)*", // overlap carried up from a sibling branch
            "(?<x>a|ab)*", // named group
            "(?i:a|ab)*", // flag-scoped group
        ).forEach {
            assertNotNull(RedosPatternDetector.vulnerabilityReason(it), "expected $it to be flagged")
        }
    }

    @Test
    fun `passes common safe patterns`() {
        listOf(
            "^[A-Z]+-[0-9]+$",
            "^[a-z0-9_-]+$",
            "\\d{4}-\\d{2}-\\d{2}",
            "(\\d{4}-)+",
            "(cat|dog|bird)",
            "(https|http)://.*",
            "a+b+c+",
            "^v[0-9]+\\.[0-9]+\\.[0-9]+$",
            "[+*]{2,5}",
            "(?:abc)+", // non-capturing, no alternation
            "(?:a|b|c)*", // non-overlapping alternation
            "((a|b))*", // nested but non-overlapping
            "(?<year>\\d{4})-", // named group, bounded body
        ).forEach {
            assertNull(RedosPatternDetector.vulnerabilityReason(it), "expected $it to pass")
        }
    }

    @Test
    fun `sees the body behind every lookaround and named-group prefix`() {
        listOf(
            "(?=a|ab)*", // lookahead
            "(?!a|ab)*", // negative lookahead
            "(?<=a|ab)*", // lookbehind
            "(?<!a|ab)*", // negative lookbehind
            "(?'x'a|ab)*", // quote-named group
            "(?<x>a+)+", // named group with an unbounded body
        ).forEach {
            assertNotNull(RedosPatternDetector.vulnerabilityReason(it), "expected $it to be flagged")
        }
    }

    @Test
    fun `bare flag directives and unterminated prefixes fall back to a plain group`() {
        listOf(
            "(?i)a+", // bare inline-flag directive
            "(?i)(a|b)*",
            "a(?", // prefix cut off after '?'
            "(?<x", // unterminated <name>
            "(?'x", // unterminated 'name'
            "(?i", // flags with no ':' or ')'
        ).forEach {
            assertNull(RedosPatternDetector.vulnerabilityReason(it), "expected $it to pass")
        }
        assertNotNull(RedosPatternDetector.vulnerabilityReason("(?i)(a|ab)*"))
    }

    @Test
    fun `any pattern over regex metacharacters is analysed without throwing`() =
        runTest {
            val alphabet = "ab()?:=!<>'|*+{},\\[]^1".toList()
            checkAll(Arb.list(Arb.element(alphabet), 0..24).map { it.joinToString("") }) { pattern ->
                RedosPatternDetector.vulnerabilityReason(pattern)
            }
        }

    @Test
    fun `an overlapping alternation is flagged behind every recognised group prefix`() =
        runTest {
            val prefixes = listOf("", "?:", "?=", "?!", "?<=", "?<!", "?<n>", "?'n'", "?i:", "?is:")
            checkAll(Arb.element(prefixes), Arb.pattern("[a-z]{1,6}")) { prefix, word ->
                val pattern = "($prefix$word|${word}z)*"
                assertNotNull(RedosPatternDetector.vulnerabilityReason(pattern), "expected $pattern to be flagged")
            }
        }
}
