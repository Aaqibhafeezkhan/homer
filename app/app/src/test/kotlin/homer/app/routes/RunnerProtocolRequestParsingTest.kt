package homer.app.routes

import homer.app.exceptions.ValidationException
import homer.app.runners.external.NextRequest
import homer.app.runners.external.OpResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [nextRequest] and [isDirTarKind], the request parses of the runner protocol's `next` and tar-post routes. */
class RunnerProtocolRequestParsingTest {
    @Test
    fun `a blank next body carries no result`() {
        assertEquals(NextRequest(), nextRequest(""))
        assertEquals(NextRequest(), nextRequest(" \n"))
    }

    @Test
    fun `a next body carrying a result is decoded`() {
        assertEquals(NextRequest(OpResult("op-1", exitCode = 3)), nextRequest("""{"completed":{"opId":"op-1","exitCode":3}}"""))
    }

    @Test
    fun `a next body that is not a NextRequest is a 400`() {
        for (body in listOf("{", "[]", """{"completed":5}""", """{"completed":{"exitCode":3}}""")) {
            val e = assertFailsWith<ValidationException>(body) { nextRequest(body) }
            assertEquals("Validation error: Invalid JSON body: expected { completed? }", e.message)
        }
    }

    @Test
    fun `a tar kind is dir by default, dir or file when given, and a 400 otherwise`() {
        assertTrue(isDirTarKind(null))
        assertTrue(isDirTarKind("dir"))
        assertFalse(isDirTarKind("file"))
        for (kind in listOf("", "DIR", "files")) {
            val e = assertFailsWith<ValidationException>(kind) { isDirTarKind(kind) }
            assertEquals("Validation error for 'kind': kind must be dir or file", e.message)
        }
    }
}
