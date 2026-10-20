package homer.app.infra

import homer.app.exceptions.MalformedLlmOutputException
import homer.app.models.LlmEvent
import homer.app.models.LlmUsage
import homer.app.models.Translation
import homer.app.models.gateSessionId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.nio.file.Path
import kotlin.io.path.bufferedReader

/**
 * Shared lenient JSON reader for LLM harness output. One instance is reused by every
 * harness adapter's typed decode ([homer.app.executors.llm.ClaudeHarness],
 * [homer.app.executors.llm.CodexHarness]) and by the readers in this file, so they can
 * never disagree on parsing leniency.
 */
val LlmOutputJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Splits a harness CLI's NDJSON stdout (one JSON event per line) into one [JsonObject]
 * per non-blank line.
 *
 * Throws [MalformedLlmOutputException] on the first line that is not a valid JSON
 * object. This is a deliberate tightening over a whole-array parse: a truncated/garbled
 * stream fails the post-mortem read rather than silently dropping events. Each event
 * must be a JSON object — `isLenient` would otherwise accept a bare unquoted token
 * (e.g. a truncated line) as a string primitive, which is not a usable event. The
 * caller owns the empty-output check (callers report it differently).
 *
 * Prefer the [Sequence] overload with `Path.useLines` so a large (up to
 * `maxOutputBytes`) stdout is read line-by-line rather than materialized as one
 * String alongside the parsed list — halving peak heap under `maxParallelRuns`
 * (2026-07-12 security review, L4). The [String] overload remains for callers
 * that already hold the text (e.g. tests).
 */
fun parseNdjson(lines: Sequence<String>): List<JsonObject> =
    lines
        .filter { it.isNotBlank() }
        .map { line ->
            val element =
                try {
                    LlmOutputJson.parseToJsonElement(line)
                } catch (e: SerializationException) {
                    throw MalformedLlmOutputException("stdout line is not valid JSON (${e.message})", e)
                }
            if (element !is JsonObject) {
                throw MalformedLlmOutputException("stdout line is not a JSON object")
            }
            element
        }.toList()

fun parseNdjson(output: String): List<JsonObject> = parseNdjson(output.lineSequence())

/**
 * At most [maxBytes] bytes of the file as a line [Sequence]; lines past the bound are
 * never read. [cut] is meaningful only once [lines] has been consumed — it reads the
 * marker the sequence set when it stopped at the bound. Deliberately a soft ceiling
 * ([ServerConfig.maxOutputBytes] is one too — see its own doc): the last line may be a
 * partial read, which a lenient parse skips as malformed.
 */
class BoundedLines(
    path: Path,
    maxBytes: Long,
) : Closeable {
    private val reader = path.bufferedReader()
    private var budget = maxBytes
    private var cutMarker = false

    /** True once the read stopped at the bound; read after consuming [lines]. */
    val cut: Boolean
        get() = cutMarker

    val lines: Sequence<String> =
        generateSequence {
            if (budget < 0) {
                cutMarker = true
                null
            } else {
                val line = reader.readLine()
                if (line == null) {
                    null
                } else {
                    budget -= line.length + 1
                    line
                }
            }
        }

    override fun close() {
        reader.close()
    }
}

/** Reads at most [maxBytes] of [path] line-by-line, so a flood cannot be materialized whole (§12 rule 4). */
fun boundedLines(
    path: Path,
    maxBytes: Long,
): BoundedLines = BoundedLines(path, maxBytes)

/**
 * What the lenient parse recovered: the parsed events, plus how many lines it had to skip.
 * [skippedLines] is surfaced on `telemetry.llm.malformed_events` so a CLI that garbles a line
 * mid-stream — not only a truncated tail — is visible instead of silently losing that event's
 * spans. See ADR-0063.
 */
data class LenientParse(
    val events: List<JsonObject>,
    val skippedLines: Long,
    /**
     * True when the parse stopped at the caller's [parseNdjsonLenient] `maxEvents` cap —
     * lines after it were neither parsed nor counted.
     */
    val truncated: Boolean = false,
)

/**
 * Lenient sibling of [parseNdjson]: blank, unparseable and non-object lines are skipped
 * instead of throwing, exactly as [scanLlmAccounting] reads the same file. Used for the
 * telemetry hand-off of a non-zero exit, where a killed or truncated stream (a trailing
 * partial line differs by runner, ADR-0063) must still keep its tool and message spans.
 * The exit-0 success path keeps the strict [parseNdjson] validation.
 *
 * [maxEvents] bounds the returned event list (CWE-400): once it is reached the parse
 * stops — later lines are neither read nor counted, and [LenientParse.truncated] says
 * so. Every kind of unusable line — unparseable, or a valid JSON value that is not an
 * object, both under `isLenient` — increments [LenientParse.skippedLines], so a garbage
 * flood is counted, never silently dropped. Default null keeps the unbounded shape the
 * strict callers rely on.
 */
fun parseNdjsonLenient(
    lines: Sequence<String>,
    maxEvents: Int? = null,
): LenientParse {
    val events = ArrayList<JsonObject>()
    var skipped = 0L
    var truncated = false
    for (line in lines) {
        if (maxEvents != null && events.size >= maxEvents) {
            truncated = true
            break
        }
        if (line.isBlank()) continue
        val obj =
            try {
                LlmOutputJson.parseToJsonElement(line) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        if (obj == null) skipped++ else events.add(obj)
    }
    return LenientParse(events, skipped, truncated)
}

/**
 * The result of translating a whole attempt's raw NDJSON through one harness adapter.
 * [raw] is the strict-parsed line list (for checks the canonical model does not carry,
 * e.g. Claude's version pin); [malformedPreview] is up to 5 truncated raw lines the
 * adapter flagged, for the WARN log.
 */
data class TranslatedStream(
    val events: List<LlmEvent>,
    val raw: List<JsonObject>,
    val ignored: Long,
    val malformed: Long,
    val malformedPreview: List<String>,
)

fun translateStream(
    raw: List<JsonObject>,
    translate: (JsonObject) -> Translation,
): TranslatedStream {
    val events = ArrayList<LlmEvent>()
    var ignored = 0L
    var malformed = 0L
    val preview = ArrayList<String>()
    for (line in raw) {
        when (val t = translate(line)) {
            is Translation.Events -> {
                events.addAll(t.events)
            }

            Translation.Ignored -> {
                ignored++
            }

            is Translation.Malformed -> {
                malformed++
                if (preview.size < MALFORMED_PREVIEW_LINES) preview.add(line.toString().take(RAW_EVENT_PREVIEW_LENGTH))
            }
        }
    }
    return TranslatedStream(events, raw, ignored, malformed, preview)
}

/** What the lenient accounting scan could recover from an attempt's stdout. */
data class LlmAccountingScan(
    val sessionId: String?,
    val cliCostUsd: Double?,
    /**
     * True once a turn-ending event was seen, regardless of whether it carried a usable
     * cost. Distinguishes "no turn end at all" (a kill/crash — [cliCostUsd] null is
     * expected) from "a turn end with no usable cost" (a schema-drift signal) for callers
     * that react differently to the two.
     */
    val resultSeen: Boolean,
    /**
     * Distinct model-call ids seen (e.g. Claude's `message.id` — one raw event per content
     * block, all sharing the id). Events without one are ignored, and the count stops at
     * 10 000. Expected-count input for the session-spend re-poll (ADR-0061).
     */
    val modelCalls: Int,
    val usage: LlmUsage?,
)

private const val MAX_MODEL_CALLS = 10_000
private const val MALFORMED_PREVIEW_LINES = 5
private const val RAW_EVENT_PREVIEW_LENGTH = 500

/**
 * Lenient per-attempt scan: unparseable lines are skipped. An attempt can end more than one
 * turn (Claude: a background subagent finishing after the first `result`), so the cost is the
 * highest one reported — it is cumulative — and the usage is summed, being per turn (see
 * [LlmEvent.TurnEnd] and ADR-0074). Reads only the harness-neutral events [translate] produces — no
 * per-harness raw-field fallback. This function is shared by every harness's accounting
 * (Codex included), so any leniency a specific CLI's dialect needs (e.g. a `system` line with
 * no `model`, or a cost field the CLI sometimes can't format as a number) belongs in that
 * harness's own `translate`, never here. `sessionId` is gated with [gateSessionId] regardless
 * of what the adapter returns, so a future harness that forgets to gate its own session id
 * cannot leak an unvalidated one into storage. See ADR-0061.
 */
fun scanLlmAccounting(
    lines: Sequence<String>,
    translate: (JsonObject) -> Translation,
): LlmAccountingScan {
    val totals = AccountingTotals()
    for (line in lines) {
        if (line.isBlank()) continue
        val obj =
            try {
                LlmOutputJson.parseToJsonElement(line) as? JsonObject ?: continue
            } catch (_: SerializationException) {
                continue
            }
        val events = (translate(obj) as? Translation.Events)?.events.orEmpty()
        for (e in events) totals.add(e)
    }
    return totals.toScan()
}

/** The running totals [scanLlmAccounting] folds an attempt's events into, in stream order. */
private class AccountingTotals {
    private var sessionId: String? = null
    private var cost: Double? = null
    private var resultSeen = false
    private var usage: LlmUsage? = null
    private val messageIds = HashSet<String>()

    fun add(e: LlmEvent) {
        when (e) {
            is LlmEvent.SessionStart -> {
                if (sessionId == null) sessionId = gateSessionId(e.sessionId)
            }

            is LlmEvent.AssistantMessage -> {
                e.messageId?.let { if (messageIds.size < MAX_MODEL_CALLS) messageIds.add(it) }
            }

            is LlmEvent.ToolCall -> {
                e.messageId?.let { if (messageIds.size < MAX_MODEL_CALLS) messageIds.add(it) }
            }

            is LlmEvent.TurnEnd -> {
                resultSeen = true
                // CWE-682: the cost is cumulative, so a later figure below the
                // highest (or below zero) is drift or agent-written stdout, never a refund — charging it under-counts.
                e.costUsd?.takeIf { it >= 0.0 }?.let { c -> cost = maxOf(cost ?: c, c) }
                // CWE-345: a negative token count in agent-written stdout would hide real usage.
                val turn = e.usage.nonNegative()
                usage = usage?.plus(turn) ?: turn
            }

            is LlmEvent.ToolResult, is LlmEvent.TransientError -> {}
        }
    }

    fun toScan() = LlmAccountingScan(sessionId, cost, resultSeen, messageIds.size, usage)
}
