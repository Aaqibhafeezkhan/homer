package homer.app.executors.llm

import homer.app.exceptions.MalformedLlmOutputException
import homer.app.infra.TranslatedStream
import homer.app.infra.boundedLines
import homer.app.infra.parseNdjson
import homer.app.infra.parseNdjsonLenient
import homer.app.infra.translateStream
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.LlmAccounting
import io.opentelemetry.api.trace.Span
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.io.path.bufferedReader

/**
 * Reads what one [LlmHarnessExecutor] attempt wrote: its stdout strictly, to judge an exit-0
 * attempt fail-closed, or leniently, to look for a configured terminal stop on a non-zero exit
 * and for the telemetry hand-off of any other outcome; and records the stdout filter's drops on
 * its stderr. Logs to the executor's [logger].
 */
internal class LlmAttemptOutput<C>(
    private val harness: LlmHarness<C>,
    private val logger: Logger,
) where C : Command, C : Command.LlmCommand {
    /** What an exit-0 attempt means: a usable result, a terminal cap, or malformed output to retry. */
    fun exitZeroOutcome(
        command: C,
        result: CommandResult,
        accounting: LlmAccounting,
        attemptOut: Path,
        attemptIndex: Int,
        span: Span,
    ): RetryOutcome {
        // Fail-closed: an exit-0 stdout that cannot be read as the harness's event
        // stream must not be marked succeeded — downstream env mappers would consume
        // output nobody can interpret.
        val stream =
            try {
                readStream(attemptOut)
            } catch (e: MalformedLlmOutputException) {
                return RetryOutcome.MalformedFailure(e)
            }
        return when (val inspection = harness.inspect(stream.events, stream.raw, command)) {
            Inspection.Success -> {
                RetryOutcome.Success(result.copy(llmAccounting = accounting), attemptIndex, stream)
            }

            is Inspection.Terminal -> {
                cappedStop(result, accounting, inspection, stream, span)
            }

            is Inspection.Malformed -> {
                RetryOutcome.MalformedFailure(MalformedLlmOutputException(inspection.reason))
            }
        }
    }

    /**
     * What a non-zero attempt means. The CLI can end a turn-cap stop on a non-zero exit code, so a
     * harness with a terminal stop configured gets its stream read (leniently, and bounded) for the
     * same [Inspection.Terminal] an exit-0 attempt yields; everything else stays the failure the
     * exit code already is — including a stream that cannot be read or holds no usable result.
     * Never malformed: a non-zero exit is judged by its exit code. See ADR-0070.
     */
    fun nonZeroOutcome(
        command: C,
        result: CommandResult,
        accounting: LlmAccounting,
        attemptOut: Path,
        span: Span,
    ): RetryOutcome {
        // Nothing to look for without an opt-in cap: the exit code is the whole verdict, and the
        // stream is left to the telemetry hand-off's own lenient read.
        if (!harness.terminalStopConfigured(command)) return RetryOutcome.OtherFailure(result.copy(llmAccounting = accounting))
        // The stdout of a non-zero exit is agent-written and may be unreadable, truncated or
        // oversized (§12 rule 2), and inspecting it can throw where an adapter checks something
        // only a success carries (Claude's version pin). Neither turns the exit into another
        // failure: only a terminal verdict is taken from it. The read is kept on the failure
        // either way, so the telemetry hand-off never parses the file a second time.
        val stream =
            runCatching { readLeniently(attemptOut, span) }.getOrNull()
                ?: return RetryOutcome.OtherFailure(result.copy(llmAccounting = accounting))
        val terminal =
            runCatching { harness.inspect(stream.events, stream.raw, command) as? Inspection.Terminal }.getOrNull()
                ?: return RetryOutcome.OtherFailure(result.copy(llmAccounting = accounting), stream)
        return cappedStop(result, accounting, terminal, stream, span)
    }

    /** The terminal cap both exit paths share: the configured exit code, its stream, never retried. */
    private fun cappedStop(
        result: CommandResult,
        accounting: LlmAccounting,
        inspection: Inspection.Terminal,
        stream: TranslatedStream,
        span: Span,
    ): RetryOutcome.CappedStop {
        // Not retried — a fresh attempt would spend the same budget the same way.
        logger.warn("{} stopped at a terminal cap; returning exit code {}", harness.id, inspection.exitCode)
        span.setAttribute("claude.max_turns_stop", true)
        return RetryOutcome.CappedStop(result.copy(exitCode = inspection.exitCode, llmAccounting = accounting), stream)
    }

    /**
     * Reads an exit-0 attempt's stdout as the harness's event stream. Fail-closed: an empty
     * or unreadable stdout is a [MalformedLlmOutputException], never a success.
     */
    private fun readStream(attemptOut: Path): TranslatedStream =
        try {
            val raw = attemptOut.bufferedReader().useLines { parseNdjson(it) }
            if (raw.isEmpty()) throw MalformedLlmOutputException("${harness.id} exited 0 but stdout is empty")
            translateStream(raw, harness::translate)
        } catch (e: MalformedLlmOutputException) {
            throw e
        } catch (e: Exception) {
            throw MalformedLlmOutputException("could not read stdout (${e.message})", e)
        }

    /**
     * The attempt's stdout as a stream for telemetry, skipping lines it cannot parse instead of
     * failing on them. Bounded on bytes read and events kept (denial_of_service / CWE-400): the
     * shell's `maxOutputBytes` bounds what the CLI could write, never what this parse keeps.
     * Every skipped line is counted into `malformed`, and a tripped bound is put on the span.
     */
    fun readLeniently(
        stdOut: Path,
        span: Span,
    ): TranslatedStream {
        val (parsed, cut) =
            boundedLines(stdOut, HANDOFF_PARSE_MAX_BYTES).use { bounded ->
                parseNdjsonLenient(bounded.lines, HANDOFF_PARSE_MAX_EVENTS) to bounded.cut
            }
        if (parsed.truncated || cut) {
            logger.warn(
                "the LLM stdout hand-off dropped output past its bounds (event cap tripped: {}, byte cap tripped: {})",
                parsed.truncated,
                cut,
            )
            span.setAttribute("telemetry.llm.handoff_truncated", true)
        }
        val translated = translateStream(parsed.events, harness::translate)
        return translated.copy(malformed = translated.malformed + parsed.skippedLines)
    }

    /**
     * Records what the stdout filter removed from this attempt: one `[HOMER]` line per
     * dropped subtype on the attempt's stderr and a span attribute. Never lets a write
     * failure mask the attempt's own outcome. See ADR-0063.
     */
    fun appendDropSummary(
        filter: LlmStdOutFilter,
        stdErr: Path,
        span: Span,
    ) {
        val dropped = filter.dropped()
        if (dropped.isEmpty()) return
        runCatching {
            stdErr.toFile().appendText(
                dropped.joinToString("") {
                    "\n[HOMER] Dropped ${it.events} system/${it.subtype} events (${it.bytes} bytes) from stdout\n"
                },
            )
            span.setAttribute("claude.dropped_system_events", dropped.sumOf { it.events })
        }.onFailure { logger.warn("could not append the dropped-events summary to {} ({})", stdErr, it.message) }
    }

    private companion object {
        // CWE-400: the lenient hand-off's own bounds, comfortably above any real CLI stream. The
        // event cap stops the parse on events kept; the byte cap stops the read, so a flood of
        // unparseable lines cannot outrun the event cap. See ADR-0047 §12 rule 4.
        const val HANDOFF_PARSE_MAX_EVENTS = 50_000
        const val HANDOFF_PARSE_MAX_BYTES = 32L * 1024 * 1024
    }
}
