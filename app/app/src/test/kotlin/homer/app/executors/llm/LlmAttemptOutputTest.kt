package homer.app.executors.llm

import homer.app.fixtures.TestFixtures
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.CostSource
import homer.app.models.LlmAccounting
import homer.app.models.LlmPrompt
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LlmAttemptOutputTest {
    private val output = LlmAttemptOutput(FakeHarness(), LoggerFactory.getLogger(LlmAttemptOutputTest::class.java))
    private val command = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions())
    private val accounting = LlmAccounting(sessionId = "s", costUsd = 0.0, source = CostSource.CLI)

    @Test
    fun `an exit-0 attempt with a successful turn end is a success, and an empty stdout is malformed`(
        @TempDir dir: Path,
    ) {
        val out = dir.resolve("out").also { it.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true}\n") }
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = out)

        val success = output.exitZeroOutcome(command, result, accounting, out, attemptIndex = 0, span = TestFixtures.mockSpan())
        assertIs<RetryOutcome.Success>(success)
        assertEquals(accounting, success.result.llmAccounting)

        out.writeText("")
        val empty = output.exitZeroOutcome(command, result, accounting, out, attemptIndex = 0, span = TestFixtures.mockSpan())
        assertContains(assertIs<RetryOutcome.MalformedFailure>(empty).exception.message.orEmpty(), "stdout is empty")
    }

    @Test
    fun `the lenient read skips unparseable lines and counts them as malformed`(
        @TempDir dir: Path,
    ) {
        val out = dir.resolve("out").also { it.writeText("{\"t\":\"start\",\"sid\":\"s\"}\nnot json\n{\"t\":\"end\",\"ok\":tru") }

        val stream = output.readLeniently(out, TestFixtures.mockSpan())

        assertEquals(1, stream.events.size)
        assertEquals(2L, stream.malformed)
    }

    @Test
    fun `the drop summary writes one line per dropped subtype and records the total`(
        @TempDir dir: Path,
    ) {
        val err = dir.resolve("err").also { it.writeText("") }
        val filter = mockk<LlmStdOutFilter> { every { dropped() } returns listOf(DroppedSubtype("hook", events = 3, bytes = 120)) }
        val span = TestFixtures.mockSpan()

        output.appendDropSummary(filter, err, span)

        assertContains(err.readText(), "[HOMER] Dropped 3 system/hook events (120 bytes) from stdout")
        verify { span.setAttribute("claude.dropped_system_events", 3L) }
    }
}
