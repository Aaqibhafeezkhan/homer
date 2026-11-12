package homer.app.infra

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultTextCompilerTest {
    private val compiler = DefaultTextCompiler()

    @Test
    fun `single variable is replaced`() {
        assertEquals("hello world", compiler.replaceArguments("hello {{NAME}}", mapOf("NAME" to "world")))
    }

    @Test
    fun `multiple variables replaced in one pass`() {
        val result = compiler.replaceArguments("{{A}} and {{B}}", mapOf("A" to "foo", "B" to "bar"))
        assertEquals("foo and bar", result)
    }

    @Test
    fun `value containing double-brace syntax is not recursively expanded`() {
        // A's value contains {{B}} — single-pass must not expand it
        val result = compiler.replaceArguments("{{A}}", mapOf("A" to "{{B}}", "B" to "world"))
        assertEquals("{{B}}", result)
    }

    @Test
    fun `unknown variable is left unreplaced`() {
        assertEquals("hello {{MISSING}}", compiler.replaceArguments("hello {{MISSING}}", emptyMap()))
    }

    @Test
    fun `empty template returns empty string`() {
        assertEquals("", compiler.replaceArguments("", mapOf("X" to "y")))
    }

    @Test
    fun `empty arguments map leaves template unchanged`() {
        assertEquals("{{FOO}} bar", compiler.replaceArguments("{{FOO}} bar", emptyMap()))
    }

    @Test
    fun `same variable used multiple times is replaced at all occurrences`() {
        assertEquals("a/a/a", compiler.replaceArguments("{{X}}/{{X}}/{{X}}", mapOf("X" to "a")))
    }

    // ---- placeholderNames: what a caller must resolve before rendering ----

    @Test
    fun `placeholderNames extracts every distinct name`() {
        assertEquals(
            setOf("A", "B"),
            placeholderNames("ticket-{{A}}-{{B}}-{{A}}"),
        )
    }

    @Test
    fun `placeholderNames is empty for a template without a placeholder`() {
        assertTrue(placeholderNames("deploy-lock").isEmpty())
        // Not `{{NAME}}` shape: a stray brace run is literal text, not a name to resolve.
        assertTrue(placeholderNames("{{A} } {{}} {{A B}}").isEmpty())
    }

    @Test
    fun `placeholderNames sees a name the compiler would leave literal`() {
        // The two must agree: a name this reports is exactly one replaceArguments cannot expand.
        val names = placeholderNames("ticket-{{TICKET_ID}}")
        assertEquals(setOf("TICKET_ID"), names)
        assertEquals("ticket-{{TICKET_ID}}", compiler.replaceArguments("ticket-{{TICKET_ID}}", emptyMap()))
    }
}
