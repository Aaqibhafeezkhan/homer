package homer.app.executors.llm

import homer.app.exceptions.MalformedLlmOutputException
import homer.app.fixtures.TestFixtures
import homer.app.infra.TranslatedStream
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.LlmPrompt
import homer.app.models.LlmRetry
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetryOutcomeTest {
    private val stream =
        TranslatedStream(events = emptyList(), raw = emptyList(), ignored = 0, malformed = 0, malformedPreview = emptyList())

    @Test
    fun `a command without retry runs a single attempt`() {
        val command = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions())

        assertEquals(1, command.retryPolicy().attempts)
        assertEquals(3, command.copy(retry = LlmRetry(attempts = 3)).retryPolicy().attempts)
    }

    @Test
    fun `retry delay grows exponentially, adds jitter below the base delay and stops at the ceiling`() {
        val retry = LlmRetry(attempts = 5, baseDelayMs = 100, maxDelayMs = 1_000)

        val third = retryDelayMs(retry, attemptIndex = 3, random = Random(1))
        assertTrue(third in 400L until 500L, "attempt 3 backs off 100 << 2 plus jitter, got $third")
        assertEquals(1_000L, retryDelayMs(retry, attemptIndex = 40, random = Random(1)))
        assertEquals(0L, retryDelayMs(LlmRetry(attempts = 2, baseDelayMs = 0, maxDelayMs = 0), attemptIndex = 1, random = Random(1)))
    }

    @Test
    fun `a success reports the attempts it used and a malformed last outcome is rethrown`() {
        val span = TestFixtures.mockSpan()
        val success = RetryOutcome.Success(TestFixtures.mockCommandResult(exitCode = 0), attemptIndex = 1, stream = stream)

        assertSame(success, returnedOrThrow(success, LlmRetry(attempts = 3), span))
        verify { span.setAttribute("retry.attempts.used", 2L) }

        val malformed = MalformedLlmOutputException("bad")
        val thrown =
            assertThrows<MalformedLlmOutputException> {
                returnedOrThrow(
                    RetryOutcome.MalformedFailure(malformed),
                    LlmRetry(attempts = 3),
                    span,
                )
            }
        assertSame(malformed, thrown)
        verify { span.setAttribute("retry.attempts.used", 3L) }
    }

    @Test
    fun `each returned outcome names itself for the escalate predicate`() {
        val result = TestFixtures.mockCommandResult(exitCode = 0)

        assertEquals("success", RetryOutcome.Success(result, 0, stream).outcomeName)
        assertEquals("failed", RetryOutcome.OtherFailure(result).outcomeName)
        assertEquals("capped", RetryOutcome.CappedStop(result, stream).outcomeName)
    }
}
