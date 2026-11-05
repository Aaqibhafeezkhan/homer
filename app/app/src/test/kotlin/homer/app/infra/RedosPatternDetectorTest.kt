package homer.app.infra

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
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

    private fun reason(pattern: String): String? = RedosPatternDetector.vulnerabilityReason(pattern)

    @Test
    fun `the reason names the nested quantifier or the overlapping branches and the pattern`() {
        val nested = assertNotNull(reason("(a+)+"))
        assertContains(nested, "nested unbounded quantifier")
        assertContains(nested, "\"(a+)+\"")
        val overlap = assertNotNull(reason("(a|ab)*"))
        assertContains(overlap, "overlapping branches (\"a\" | \"ab\")")
        assertContains(overlap, "\"(a|ab)*\"")
        assertContains(assertNotNull(reason("(a|)+")), "(\"a\" | \"\")")
        assertContains(assertNotNull(reason("(|a)+")), "(\"\" | \"a\")")
    }

    @Test
    fun `an unbounded body carried up through a plain group is flagged when the outer group is quantified`() {
        listOf("((a+))+", "(((a+)))*", "((a*))+", "(?:(?:a+))+", "(x(a+)y)+").forEach {
            assertContains(assertNotNull(reason(it)), "nested unbounded quantifier", message = it)
        }
        assertNull(reason("((a+))"))
        assertNull(reason("((a+))?"))
        assertNull(reason("((a+))x"))
    }

    @Test
    fun `an unquantified group is safe even at the very end of the pattern`() {
        listOf("(a+)", "(a|ab)", "x(a+)", "((a+))", "(a|)", "(a+)?", "(a|ab)?").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
    }

    @Test
    fun `an unmatched closing parenthesis is ignored`() {
        listOf("a)", ")", "a)+", "))+", "a+)+").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
        assertContains(assertNotNull(reason("a)(b+)+")), "nested unbounded quantifier")
    }

    @Test
    fun `an escaped metacharacter is a literal and never opens a group or a quantifier`() {
        listOf("\\(a+\\)+", "\\(a|ab\\)*", "(\\)+)", "(a\\*)+", "(a\\+)+", "(\\|a|\\|b)*", "a\\\\(b)+").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
        assertNotNull(reason("(a\\\\+)+")) // an escaped backslash leaves the '+' live
    }

    @Test
    fun `a character class hides its metacharacters from the scan`() {
        listOf(
            "([*])+",
            "([+])+",
            "([a*b])+",
            "([)])+",
            "([(])+",
            "([|a])+",
            "([a|ab])+",
            "([^*])+",
            "([^)])+",
        ).forEach {
            assertNull(reason(it), "expected $it to pass")
        }
    }

    @Test
    fun `a character class ends at its closing bracket so what follows is still scanned`() {
        listOf("[a](c+)+", "[ab](c+)+", "[^a](c+)+", "[a](x|xy)*", "[*](c+)+", "[+](c+)+").forEach {
            assertNotNull(reason(it), "expected $it to be flagged")
        }
    }

    @Test
    fun `a leading closing bracket is a literal class member`() {
        listOf("([]*])+", "([^]*])+", "([]a*])+", "([^]a*])+", "([]])+").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
        listOf("[]](c+)+", "[^]](c+)+", "[]a](c+)+", "[^]a](c+)+").forEach {
            assertNotNull(reason(it), "expected $it to be flagged")
        }
    }

    @Test
    fun `an escaped character inside a class never closes it`() {
        listOf("([\\]*])+", "([a\\-z]+)", "([\\]a]+)", "([a\\-z*])+", "([\\\\*])+", "([\\[*])+").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
        assertNotNull(reason("([\\]a]+)+"))
        assertNotNull(reason("[a\\-z](c+)+"))
        assertNotNull(reason("[\\]](c+)+"))
        assertNotNull(reason("[\\\\](c+)+")) // an escaped backslash does not escape the closing bracket
    }

    @Test
    fun `an unterminated character class swallows the rest of the pattern`() {
        listOf("[(a+)+", "[a(a+)+", "[^(a+)+", "[]", "[", "[\\").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
    }

    @Test
    fun `an interval after a group is unbounded only when it has no upper limit`() {
        listOf("(a+){2,}", "(a+){0,}", "(a+){10,}", "(a|ab){1,}").forEach {
            assertNotNull(reason(it), "expected $it to be flagged")
        }
        listOf("(a+){2}", "(a+){2,5}", "(a+){0,1}", "(a|ab){3}", "(a|ab){1,3}", "(a+){x}", "(a+){,5}", "(a+){2,x}", "(a+){2,5").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
    }

    @Test
    fun `an unbounded interval inside a group counts as an unbounded body`() {
        listOf("(a{2,})+", "(a{0,})*", "(?:a{1,})+").forEach {
            assertNotNull(reason(it), "expected $it to be flagged")
        }
        listOf("(a{2})+", "(a{2,5})+", "(a{,5})+", "(a{2,5)+", "(a{x})+").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
    }

    @Test
    fun `an interval is skipped whole so its digits are never read as pattern text`() {
        // "{2,5}" must be one token: the alternation branches stay "ab" and "cd".
        assertNull(reason("(ab{2,5}|cd)+"))
        assertNull(reason("(a{12}|b{3,45})+"))
        assertNotNull(reason("(a{2,5}|a{2,5}b)+"))
    }

    @Test
    fun `alternation branches overlap when one is a prefix of the other in either order and not otherwise`() {
        listOf("(ab|a)*", "(a|ab)*", "(a|b|ab)*", "(b|a|ab)*", "(x|y|)*", "(|x|y)*", "(a|b|a)*").forEach {
            assertNotNull(reason(it), "expected $it to be flagged")
        }
        listOf("(a|b)*", "(ab|ba)*", "(abc|abd)*", "(a|b|c)+", "(xa|ya|za)*", "(a|b|c|d)*").forEach {
            assertNull(reason(it), "expected $it to pass")
        }
    }

    @Test
    fun `the overlap reason reports the first overlapping pair of three branches`() {
        assertContains(assertNotNull(reason("(a|b|ab)*")), "(\"a\" | \"ab\")")
        assertContains(assertNotNull(reason("(ab|b|a)*")), "(\"ab\" | \"a\")")
    }

    @Test
    fun `an alternation is analysed per group so a sibling group's branches do not mix`() {
        assertNull(reason("(a|b)(ab|c)*"))
        assertNull(reason("(a)(ab)*"))
        assertNotNull(reason("(a)(a|ab)*"))
    }

    @Test
    fun `an unterminated or unknown group prefix still yields a body that is scanned`() {
        assertNotNull(reason("(?<n>a+)+"))
        assertNotNull(reason("(?'n'a+)+"))
        assertNull(reason("(?<n"))
        assertNull(reason("(?'n"))
        assertNull(reason("(?<>a)+"))
    }
}
