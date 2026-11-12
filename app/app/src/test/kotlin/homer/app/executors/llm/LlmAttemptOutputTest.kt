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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LlmAttemptOutputTest {
    private val output = LlmAttemptOutput(FakeHarness(), LoggerFactory.getLogger(LlmAttemptOutputTest::class.java))
    private val command = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions())

    /** The same step, opted into the turn cap whose exit code a terminal stop is rewritten to. */
    private val cappedCommand = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions(maxTurnsExitCode = 75))
    private val accounting = LlmAccounting(sessionId = "s", costUsd = 0.0, source = CostSource.CLI)

    private val claudeOutput = LlmAttemptOutput(ClaudeHarness(), LoggerFactory.getLogger(LlmAttemptOutputTest::class.java))

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
    fun `an exit-0 attempt whose only result is an is_error is a malformed failure`(
        @TempDir dir: Path,
    ) {
        // The real ClaudeHarness, so the exit-0-but-error result reaches the retry/escalate
        // machinery as malformed rather than as a success. See ADR-0079.
        val claude = LlmAttemptOutput(ClaudeHarness(), LoggerFactory.getLogger(LlmAttemptOutputTest::class.java))
        val out =
            dir.resolve("out").also {
                it.writeText(
                    """{"type":"result","subtype":"success","is_error":true,"terminal_reason":"api_error","api_error_status":529}""" + "\n",
                )
            }
        val result = TestFixtures.mockCommandResult(exitCode = 0, stdOut = out)

        val failure =
            assertIs<RetryOutcome.MalformedFailure>(
                claude.exitZeroOutcome(command, result, accounting, out, attemptIndex = 0, span = TestFixtures.mockSpan()),
            )
        assertEquals(
            "result event is not a successful turn: is_error (terminal_reason=api_error, api_error_status=529)",
            failure.exception.reason,
        )
    }

    // ========== non-zero exit ==========
    // The CLI can end a turn-cap stop on a non-zero exit code, so a harness with a terminal stop
    // configured is read on that path too; everything else keeps the exit's own failure.

    @Test
    fun `a non-zero exit whose result is error_max_turns is the capped stop of exit 0`(
        @TempDir dir: Path,
    ) {
        val out = dir.resolve("out").also { it.writeText("""{"type":"result","subtype":"error_max_turns","session_id":"s"}""" + "\n") }
        val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = out)
        val span = TestFixtures.mockSpan()

        val capped = assertIs<RetryOutcome.CappedStop>(claudeOutput.nonZeroOutcome(cappedCommand, result, accounting, out, span))

        assertEquals(75, capped.result.exitCode)
        assertEquals(accounting, capped.result.llmAccounting)
        verify { span.setAttribute("claude.max_turns_stop", true) }
    }

    @Test
    fun `a harness that opts into a terminal stop is read on a non-zero exit too`(
        @TempDir dir: Path,
    ) {
        // The read is driven by the adapter's opt-in, not by a Claude-specific rule (ADR-0070).
        val fake = FakeHarness(terminalExit = 9, terminalStopConfigured = true)
        val out = dir.resolve("out").also { it.writeText("""{"t":"stop"}""") }
        val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = out)

        val capped =
            assertIs<RetryOutcome.CappedStop>(
                LlmAttemptOutput(fake, LoggerFactory.getLogger(LlmAttemptOutputTest::class.java))
                    .nonZeroOutcome(command, result, accounting, out, TestFixtures.mockSpan()),
            )

        assertEquals(9, capped.result.exitCode)
    }

    @Test
    fun `without a configured cap a non-zero exit never reads the stream`(
        @TempDir dir: Path,
    ) {
        val fake = FakeHarness()
        val out = dir.resolve("out").also { it.writeText("""{"t":"stop"}""") }
        val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = out)

        val failure =
            assertIs<RetryOutcome.OtherFailure>(
                LlmAttemptOutput(fake, LoggerFactory.getLogger(LlmAttemptOutputTest::class.java))
                    .nonZeroOutcome(command, result, accounting, out, TestFixtures.mockSpan()),
            )

        assertEquals(1, failure.result.exitCode)
        assertEquals(0, fake.inspectCalls)
        // No read was made, so the telemetry hand-off does its own lenient one.
        assertNull(failure.stream)
    }

    @Test
    fun `a non-zero exit whose stream holds no cap result stays the failure its exit code is`(
        @TempDir dir: Path,
    ) {
        // Denial path (§12 rule 8): only a terminal verdict is taken from the stream; an empty,
        // unparseable, result-less, successful, failed or is_error stream leaves the exit alone.
        val streams =
            listOf(
                "" to "empty stdout",
                "not json\n" to "unparseable",
                """{"type":"system","subtype":"init","session_id":"s"}""" + "\n" to "no result event",
                """{"type":"result","subtype":"success","session_id":"s","total_cost_usd":0.0}""" + "\n" to "a success result",
                """{"type":"result","subtype":"error_during_execution"}""" + "\n" to "another subtype",
                """{"type":"result","subtype":"success","is_error":true,"stop_reason":"refusal"}""" + "\n" to "an is_error result",
            )
        val span = TestFixtures.mockSpan()

        for ((text, what) in streams) {
            val out = dir.resolve("out").also { it.writeText(text) }
            val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = out)

            val failure =
                assertIs<RetryOutcome.OtherFailure>(claudeOutput.nonZeroOutcome(cappedCommand, result, accounting, out, span), what)

            assertEquals(1, failure.result.exitCode, what)
            assertEquals(accounting, failure.result.llmAccounting, what)
            // The read the inspection made is carried, so the telemetry hand-off
            // reuses it instead of parsing the file a second time.
            assertNotNull(failure.stream, what)
        }
    }

    @Test
    fun `an unreadable stdout on the opted-in non-zero path stays the failure its exit code is`(
        @TempDir dir: Path,
    ) {
        // Denial path (§12 rule 8): stdout that cannot be opened at all — deleted, or replaced
        // with a directory — must not throw out of the failed attempt: the outcome is the exit's
        // own failure, and no stream is carried (the telemetry hand-off does its own read).
        val unreadable =
            listOf(
                dir.resolve("missing") to "a deleted stdout file",
                dir.resolve("out-dir").also { it.toFile().mkdir() } to "a stdout path that is a directory",
            )

        for ((out, what) in unreadable) {
            val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = out)

            val failure =
                assertIs<RetryOutcome.OtherFailure>(
                    claudeOutput.nonZeroOutcome(cappedCommand, result, accounting, out, TestFixtures.mockSpan()),
                    what,
                )

            assertEquals(1, failure.result.exitCode, what)
            assertEquals(accounting, failure.result.llmAccounting, what)
            assertNull(failure.stream, what)
        }
    }

    @Test
    fun `a non-zero exit is not judged by the version pin a success carries`(
        @TempDir dir: Path,
    ) {
        val pinned =
            LlmAttemptOutput(ClaudeHarness(requireClaudeCodeVersion = "1.2.3"), LoggerFactory.getLogger(LlmAttemptOutputTest::class.java))
        val out =
            dir.resolve("out").also {
                it.writeText(
                    """
                    {"type":"system","subtype":"init","session_id":"s","claude_code_version":"9.9.9"}
                    {"type":"result","subtype":"success","session_id":"s","total_cost_usd":0.0}
                    """.trimIndent() + "\n",
                )
            }
        val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = out)

        val failure =
            assertIs<RetryOutcome.OtherFailure>(pinned.nonZeroOutcome(cappedCommand, result, accounting, out, TestFixtures.mockSpan()))

        assertEquals(1, failure.result.exitCode)
    }

    @Test
    fun `a cap result past the lenient read bound leaves the non-zero exit a failure`(
        @TempDir dir: Path,
    ) {
        // The read is bounded (CWE-400): a cap result past the event cap is never reached, so the
        // outcome is the plain failure and no exception escapes the parse.
        val out =
            dir.resolve("out").also {
                it.writeText(
                    ("""{"type":"system","subtype":"init","session_id":"s"}""" + "\n").repeat(50_001) +
                        """{"type":"result","subtype":"error_max_turns","session_id":"s"}""" + "\n",
                )
            }
        val result = TestFixtures.mockCommandResult(exitCode = 1, stdOut = out)

        val failure =
            assertIs<RetryOutcome.OtherFailure>(
                claudeOutput.nonZeroOutcome(cappedCommand, result, accounting, out, TestFixtures.mockSpan()),
            )

        assertEquals(1, failure.result.exitCode)
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
