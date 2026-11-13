package homer.app.fixtures

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockCallBudgetTest {
    /** A polled call, as a fake process's `waitFor` is. */
    fun interface Poll {
        fun ready(timeoutMs: Long): Boolean
    }

    private fun context(): ExtensionContext =
        mockk {
            every { requiredTestClass } returns MockCallBudgetTest::class.java
            every { displayName } returns "spins()"
        }

    private fun polled(times: Int): Poll {
        val poll = mockk<Poll> { every { ready(any()) } returns false }
        repeat(times) { poll.ready(250) }
        return poll
    }

    @Test
    fun `a method past the warning is one report line and its recorded calls are cleared, its answers kept`(
        @TempDir dir: File,
    ) {
        val poll = polled(12)
        MockCallBudget(warnAt = 10, failAt = 100, reportDir = { dir }).afterEach(context())
        val line = Json.parseToJsonElement(dir.resolve(MockCallBudget.REPORT_FILE).readText().trim()).jsonObject
        assertEquals("homer.app.fixtures.MockCallBudgetTest", line.getValue("testClass").jsonPrimitive.content)
        assertEquals("spins()", line.getValue("test").jsonPrimitive.content)
        assertEquals("Poll.ready", line.getValue("method").jsonPrimitive.content)
        assertEquals(12, line.getValue("calls").jsonPrimitive.int)
        assertEquals(10, line.getValue("warnAt").jsonPrimitive.int)
        assertEquals(100, line.getValue("failAt").jsonPrimitive.int)
        verify(exactly = 0) { poll.ready(any()) }
        assertFalse(poll.ready(1), "clearing recorded calls keeps the stubbed answer")
    }

    @Test
    fun `a method at the limit fails the test naming it and its count, with its calls already cleared`(
        @TempDir dir: File,
    ) {
        val poll = polled(100)
        val error = assertThrows<AssertionError> { MockCallBudget(warnAt = 10, failAt = 100, reportDir = { dir }).afterEach(context()) }
        assertTrue(error.message!!.startsWith("Poll.ready was called 100 times in this test (the limit is 100)"), error.message)
        assertTrue(dir.resolve(MockCallBudget.REPORT_FILE).exists(), "a failed budget is reported too")
        verify(exactly = 0) { poll.ready(any()) }
    }

    @Test
    fun `calls under the warning leave no report`(
        @TempDir dir: File,
    ) {
        polled(9)
        MockCallBudget(warnAt = 10, failAt = 100, reportDir = { dir }).afterEach(context())
        assertFalse(dir.resolve(MockCallBudget.REPORT_FILE).exists())
    }

    @Test
    fun `without a report dir a method past the warning is only counted`() {
        polled(12)
        MockCallBudget(warnAt = 10, failAt = 100, reportDir = { null }).afterEach(context())
    }

    @Test
    fun `the busiest method is the one counted, and only from the threshold`() {
        val counts = mapOf("A.a" to 3, "B.b" to 7)
        assertEquals("B.b" to 7, MockCallBudget.busiest(counts, 7))
        assertNull(MockCallBudget.busiest(counts, 8))
        assertNull(MockCallBudget.busiest(emptyMap(), 0))
    }

    @Test
    fun `a method of an unnamed type is still named`() {
        assertEquals("?.invoke", MockCallBudget.methodName(null, "invoke"))
    }
}
