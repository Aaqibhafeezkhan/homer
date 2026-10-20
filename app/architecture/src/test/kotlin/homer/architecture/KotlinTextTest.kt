package homer.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KotlinTextTest {
    private val q = "\""
    private val raw = "\"\"\""

    @Test
    fun `codeOnly blanks a line comment and a block comment but keeps code and newlines`() {
        val text = "val a = 1 // get(\"/x\") {\n/* route {\n } */ val b = 2"

        val code = KotlinText.codeOnly(text)

        assertEquals("val a = 1", code.lines()[0].trimEnd())
        assertEquals("", code.lines()[1].trim())
        assertEquals("val b = 2", code.lines()[2].trim())
    }

    @Test
    fun `codeOnly blanks string contents including escaped quotes and keeps the delimiters`() {
        val text = "f(${q}a\\$q{b$q) {"

        assertEquals("f($q     $q) {", KotlinText.codeOnly(text))
    }

    @Test
    fun `codeOnly blanks a raw string holding quotes and braces`() {
        val text = "x($raw a \"b\" { $raw) + 1"

        val code = KotlinText.codeOnly(text)

        assertEquals("x($raw", code.substring(0, 5))
        assertEquals("$raw) + 1", code.substring(code.length - 8))
        assertEquals(' ', code[8])
        assertEquals(-1, code.substring(5, code.length - 8).indexOfAny(charArrayOf('{', '"')))
    }

    @Test
    fun `codeOnly treats a template with nested braces and quotes as part of the string`() {
        val text = "s($q\${ m.map { $q}$q } }$q) { body }"

        val code = KotlinText.codeOnly(text)

        assertEquals(" { body }", code.substring(code.indexOf(')') + 1))
        assertEquals(1, code.count { it == '{' })
    }

    @Test
    fun `codeOnly blanks char literals, an escaped quote and a brace among them`() {
        val text = "listOf('{', '\\'', '}') {"

        val code = KotlinText.codeOnly(text)

        assertEquals("listOf(", code.substring(0, 7))
        assertEquals(1, code.count { it == '{' })
        assertEquals(0, code.count { it == '}' })
    }

    @Test
    fun `codeOnly preserves every offset, so a match maps back onto the source`() {
        val text = "get($q/a$q) { // c\n  ${raw}x$raw }\n"

        val code = KotlinText.codeOnly(text)

        assertEquals(text.length, code.length)
        assertEquals(text.indices.filter { text[it] == '\n' }, code.indices.filter { code[it] == '\n' })
        assertEquals("/a", text.substring(code.indexOf(q) + 1, code.indexOf(q, code.indexOf(q) + 1)))
    }

    @Test
    fun `closingBrace skips braces in strings and comments`() {
        val text = "{ val s = $q}$q // }\n }"

        assertEquals(text.length - 1, KotlinText.closingBrace(text, 0))
    }

    @Test
    fun `a body opens after parameters whose default values hold lambdas and comparisons`() {
        val text = "fun f(a: () -> Unit = { }, b: Boolean = 1 > 2, c: Int = 3) {\n    a()\n}"

        assertEquals(3, KotlinText.bodyLines(text))
    }

    @Test
    fun `a body opens after a generic and a function return type`() {
        assertEquals(2, KotlinText.bodyLines("fun <T : Comparable<T>> f(): Map<String, List<T>> {\n}"))
        assertEquals(1, KotlinText.bodyLines("fun f(): (Int) -> Map<String, Int> = { emptyMap() }"))
    }

    @Test
    fun `an expression body counts from the line holding the equals sign`() {
        assertEquals(3, KotlinText.bodyLines("fun f(\n    x: Int,\n): Int =\n    x +\n        1"))
    }

    @Test
    fun `an abstract function has no body`() {
        assertNull(KotlinText.bodyLines("abstract fun f(x: Int): Map<String, Int>"))
    }
}
