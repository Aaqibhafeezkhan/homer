package homer.agentdebug.commands

import com.github.ajalt.clikt.core.ProgramResult
import homer.app.infra.PasswordVerifier
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HashPasswordCommandTest {
    /** Runs [block] with [stdin] piped in (so there is no console) and returns what it printed. */
    private fun withPipedStdin(
        stdin: String,
        block: () -> Unit,
    ): String {
        val buffer = ByteArrayOutputStream()
        val originalIn = System.`in`
        val originalOut = System.out
        System.setIn(ByteArrayInputStream(stdin.toByteArray()))
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setIn(originalIn)
            System.setOut(originalOut)
        }
        return buffer.toString()
    }

    @Test
    fun `a piped password prints only a bcrypt hash of that password at the requested cost`() {
        val out = withPipedStdin("s3cret pass\nignored second line\n") { HashPasswordCommand(cost = 4).run() }

        val hash = out.trim()
        assertEquals(1, out.lines().count { it.isNotBlank() }, out)
        assertTrue(hash.startsWith("\$2a\$04\$"), hash)
        assertTrue(PasswordVerifier().verify("s3cret pass", hash))
    }

    @Test
    fun `a console password is prompted for twice and hashed once both entries match`() {
        val prompts = mutableListOf<String>()
        val out =
            withPipedStdin("") {
                HashPasswordCommand(cost = 4, readHidden = { prompt -> "s3cret".toCharArray().also { prompts += prompt } }).run()
            }

        assertEquals(listOf("Password: ", "Confirm:  "), prompts)
        assertTrue(PasswordVerifier().verify("s3cret", out.trim()))
    }

    @Test
    fun `an empty password ends the CLI with status 2`() {
        val failure = assertFailsWith<ProgramResult> { withPipedStdin("\n") { HashPasswordCommand(cost = 4).run() } }

        assertEquals(2, failure.statusCode)
    }

    @Test
    fun `mismatched console entries end the CLI with status 2`() {
        val entries = ArrayDeque(listOf("s3cret", "s3cre7"))
        val failure =
            assertFailsWith<ProgramResult> {
                withPipedStdin("") { HashPasswordCommand(cost = 4, readHidden = { entries.removeFirst().toCharArray() }).run() }
            }

        assertEquals(2, failure.statusCode)
    }

    @Test
    fun `a cost outside 4 to 15 is refused before reading a password`() {
        val error = assertFailsWith<IllegalArgumentException> { HashPasswordCommand(cost = 3).run() }
        assertEquals("cost must be in 4..15 (got 3)", error.message)
    }

    @Test
    fun `a confirmed password is returned, a mismatch is refused`() {
        assertEquals("s3cret", confirmedPassword("s3cret".toCharArray(), "s3cret".toCharArray()))
        assertNull(confirmedPassword("s3cret".toCharArray(), "s3cre7".toCharArray()))
    }

    @Test
    fun `an unread console entry counts as empty`() {
        assertEquals("", confirmedPassword(null, null))
        assertEquals("", confirmedPassword(null, charArrayOf()))
        assertNull(confirmedPassword(null, "x".toCharArray()))
        assertNull(confirmedPassword("x".toCharArray(), null))
    }
}
