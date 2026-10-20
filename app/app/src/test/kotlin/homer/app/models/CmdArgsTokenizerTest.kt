package homer.app.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class CmdArgsTokenizerTest {
    @Test
    fun `simple whitespace split`() {
        assertEquals(listOf("echo", "hello"), CmdArgsTokenizer.tokenize("echo hello"))
    }

    @Test
    fun `double-quoted argument keeps internal spaces`() {
        assertEquals(listOf("echo", "hello world"), CmdArgsTokenizer.tokenize("""echo "hello world""""))
    }

    @Test
    fun `single-quoted argument keeps internal spaces`() {
        assertEquals(listOf("echo", "hello world"), CmdArgsTokenizer.tokenize("echo 'hello world'"))
    }

    @Test
    fun `escaped double quote inside double quotes is preserved as literal quote`() {
        assertEquals(
            listOf("echo", """he said "hi""""),
            CmdArgsTokenizer.tokenize("""echo "he said \"hi\"""""),
        )
    }

    @Test
    fun `backslash outside quotes escapes the next char`() {
        assertEquals(listOf("echo", "hello world"), CmdArgsTokenizer.tokenize("""echo hello\ world"""))
    }

    @Test
    fun `runs of whitespace collapse and tabs are treated as whitespace`() {
        assertEquals(listOf("echo", "hello"), CmdArgsTokenizer.tokenize("echo\t\t  hello"))
    }

    @Test
    fun `single quotes inside double quotes are literal`() {
        assertEquals(listOf("echo", "it's"), CmdArgsTokenizer.tokenize("""echo "it's""""))
    }

    @Test
    fun `double quotes inside single quotes are literal`() {
        assertEquals(listOf("echo", """say "hi""""), CmdArgsTokenizer.tokenize("""echo 'say "hi"'"""))
    }

    @Test
    fun `empty string returns empty list`() {
        assertEquals(emptyList(), CmdArgsTokenizer.tokenize(""))
    }

    @Test
    fun `whitespace-only string returns empty list`() {
        assertEquals(emptyList(), CmdArgsTokenizer.tokenize("   \t  "))
    }

    @Test
    fun `unterminated double quote throws`() {
        assertThrows<IllegalArgumentException> { CmdArgsTokenizer.tokenize("""echo "oops""") }
    }

    @Test
    fun `unterminated single quote throws`() {
        assertThrows<IllegalArgumentException> { CmdArgsTokenizer.tokenize("echo 'oops") }
    }

    @Test
    fun `dangling backslash throws`() {
        assertThrows<IllegalArgumentException> { CmdArgsTokenizer.tokenize("""echo hello\""") }
    }

    @Test
    fun `regression - curl with header`() {
        assertEquals(
            listOf("curl", "-H", "Content-Type: application/json", "https://api.example.com"),
            CmdArgsTokenizer.tokenize("""curl -H "Content-Type: application/json" https://api.example.com"""),
        )
    }

    @Test
    fun `empty quoted argument produces empty string token`() {
        assertEquals(listOf("echo", ""), CmdArgsTokenizer.tokenize("""echo """""))
    }
}
