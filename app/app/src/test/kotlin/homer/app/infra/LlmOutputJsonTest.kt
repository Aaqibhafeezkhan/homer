package homer.app.infra

import homer.app.exceptions.MalformedLlmOutputException
import homer.app.executors.llm.ClaudeHarness
import homer.app.models.LlmEvent
import homer.app.models.Translation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmOutputJsonTest {
    private val claude = ClaudeHarness()

    @TempDir
    lateinit var tempDir: Path

    /**
     * A stand-in for a harness adapter that does NOT gate its own session id — used to
     * prove the scan itself refuses to trust it. Reads `id` off the raw line and reports
     * it, ungated, as a [LlmEvent.SessionStart].
     */
    private fun ungatedSessionIdTranslate(obj: JsonObject): Translation =
        Translation.Events(listOf(LlmEvent.SessionStart((obj["id"] as? JsonPrimitive)?.contentOrNull, model = null)))

    @Test
    fun `parses one element per non-blank line`() {
        val ndjson =
            """
            {"type":"system","subtype":"init"}

            {"type":"result","subtype":"success","total_cost_usd":0.5}
            """.trimIndent()
        val events = parseNdjson(ndjson)
        assertEquals(2, events.size)
        assertEquals("system", events[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("result", events[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `throws on the first malformed line`() {
        val ndjson =
            """
            {"type":"system"}
            this-is-not-json
            {"type":"result"}
            """.trimIndent()
        assertThrows<MalformedLlmOutputException> { parseNdjson(ndjson) }
    }

    @Test
    fun `throws on a valid JSON line that is not an object`() {
        // Each event is a JSON object; valid-but-non-object lines (a bare array — e.g.
        // legacy JSON-array stdout on one line — a number, or a string) are rejected so a
        // malformed stream fails the post-mortem read.
        assertThrows<MalformedLlmOutputException> {
            parseNdjson("""["not","an","object"]""")
        }
        assertThrows<MalformedLlmOutputException> {
            parseNdjson("42")
        }
    }

    @Test
    fun `lenient parse skips blank unparseable and non-object lines`() {
        val ndjson =
            """
            {"type":"system","subtype":"init"}

            this-is-not-json
            ["not","an","object"]
            {"type":"result","subtype":"success","total_cost_usd":0.5}
            {"type":"assis
            """.trimIndent()
        val parsed = parseNdjsonLenient(ndjson.lineSequence())
        assertEquals(2, parsed.events.size)
        assertEquals(3, parsed.skippedLines)
        assertFalse(parsed.truncated)
        assertEquals(
            "system",
            parsed.events[0]
                .jsonObject["type"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "result",
            parsed.events[1]
                .jsonObject["type"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `lenient parse cap stops on events kept and counts every unusable line`() {
        // The cap counts EVENTS KEPT, so a garbage flood trips it too — and every kind of
        // unusable line (unparseable, or a lenient JSON primitive an object is required)
        // is counted as skipped, never silently dropped.
        val ndjson =
            """
            {"type":"system","subtype":"init"}
            this-is-not-json
            42
            {"type":"msg"}
            {"type":"result"}
            """.trimIndent()
        val parsed = parseNdjsonLenient(ndjson.lineSequence(), maxEvents = 2)
        assertEquals(2, parsed.events.size)
        assertEquals(2, parsed.skippedLines)
        assertTrue(parsed.truncated)

        val unbounded = parseNdjsonLenient(ndjson.lineSequence())
        assertEquals(3, unbounded.events.size)
        assertEquals(2, unbounded.skippedLines)
        assertFalse(unbounded.truncated)
    }

    @Test
    fun `boundedLines stops reading at the byte bound and marks the cut`() {
        val file = tempDir.resolve("flood.ndjson")
        Files.write(file, "line\n".repeat(10).toByteArray())
        val bounded = boundedLines(file, maxBytes = 12)
        val lines = bounded.lines.toList()
        // 5 bytes per line: 3 whole lines (15 bytes) are read, the 4th pushes the budget
        // negative and the sequence stops there.
        assertEquals(3, lines.size)
        assertTrue(bounded.cut)
        assertFalse(boundedLines(file, maxBytes = 100).cut)
    }

    @Test
    fun `translateStream counts ignored and malformed lines and keeps the raw list`() {
        val raw =
            parseNdjson(
                """
                {"type":"system","subtype":"hook_started"}
                {"type":"assistant","message":{"content":"bad"}}
                {"type":"result","subtype":"success"}
                """.trimIndent(),
            )
        val s = translateStream(raw, claude::translate)
        assertEquals(1L, s.ignored)
        assertEquals(1L, s.malformed)
        assertEquals(1, s.events.size)
        assertEquals(3, s.raw.size)
        assertEquals(1, s.malformedPreview.size)
    }

    @Test
    fun `scan collects session id cost resultSeen usage and distinct model calls`() {
        val scan =
            scanLlmAccounting(
                """
                {"type":"system","subtype":"init","session_id":"s-1","model":"m"}
                {"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"a"}]}}
                {"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t","name":"Bash","input":{}}]}}
                {"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"t2","name":"Bash","input":{}}]}}
                {"type":"result","subtype":"success","total_cost_usd":0.25,"usage":{"input_tokens":7,"output_tokens":3}}
                """.trimIndent().lineSequence(),
                claude::translate,
            )
        assertEquals("s-1", scan.sessionId)
        assertEquals(0.25, scan.cliCostUsd)
        assertTrue(scan.resultSeen)
        assertEquals(2, scan.modelCalls)
        assertEquals(7L, scan.usage?.input)
    }

    @Test
    fun `scan never throws on a truncated last line and reports cost absent when result has none`() {
        val scan =
            scanLlmAccounting(
                """
                {"type":"result","subtype":"success"}
                {"type":"assis
                """.trimIndent().lineSequence(),
                claude::translate,
            )
        assertTrue(scan.resultSeen)
        assertNull(scan.cliCostUsd)
    }

    @Test
    fun `scan caps distinct model calls at 10000`() {
        val lines =
            (0 until 10_050).asSequence().map {
                """{"type":"assistant","message":{"id":"m$it","content":[{"type":"text","text":"x"}]}}"""
            }
        assertEquals(10_000, scanLlmAccounting(lines, claude::translate).modelCalls)
    }

    @Test
    fun `scan charges every turn of an attempt that ends more than one`() {
        // Captured from Claude CLI 2.1.287: a background subagent finishes after the first
        // `result`, so a second one follows. total_cost_usd is cumulative, usage per turn.
        val lines =
            requireNotNull(javaClass.getResourceAsStream("/claude/cli-2.1-background-agent-stream.jsonl")) { "missing test resource" }
                .bufferedReader()
                .readLines()
        val scan = scanLlmAccounting(lines.asSequence(), claude::translate)
        assertEquals(0.060736649999999996, scan.cliCostUsd)
        assertEquals(17L + 10L, scan.usage?.input)
        assertEquals(281L + 53L, scan.usage?.output)
    }

    @Test
    fun `a later turn end without a cost keeps the cost reported before it`() {
        val scan =
            scanLlmAccounting(
                """
                {"type":"result","subtype":"success","total_cost_usd":0.1,"usage":{"input_tokens":1,"output_tokens":1}}
                {"type":"result","subtype":"success","usage":{"input_tokens":9,"output_tokens":9}}
                """.trimIndent().lineSequence(),
                claude::translate,
            )
        assertEquals(0.1, scan.cliCostUsd)
        assertEquals(10L, scan.usage?.input)
    }

    @Test
    fun `CWE-682 a later lower cost never lowers the charge`() {
        val scan =
            scanLlmAccounting(
                """
                {"type":"result","subtype":"success","total_cost_usd":0.9,"usage":{"input_tokens":1,"output_tokens":1}}
                {"type":"result","subtype":"success","total_cost_usd":0.1,"usage":{"input_tokens":1,"output_tokens":1}}
                """.trimIndent().lineSequence(),
                claude::translate,
            )
        assertEquals(0.9, scan.cliCostUsd)
    }

    @Test
    fun `CWE-682 a negative cost is never charged`() {
        val scan =
            scanLlmAccounting(
                """
                {"type":"result","subtype":"success","total_cost_usd":0.2,"usage":{"input_tokens":1,"output_tokens":1}}
                {"type":"result","subtype":"success","total_cost_usd":-5,"usage":{"input_tokens":1,"output_tokens":1}}
                """.trimIndent().lineSequence(),
                claude::translate,
            )
        assertEquals(0.2, scan.cliCostUsd)

        val alone = scanLlmAccounting(sequenceOf("""{"type":"result","subtype":"success","total_cost_usd":-5}"""), claude::translate)
        assertTrue(alone.resultSeen)
        assertNull(alone.cliCostUsd)
    }

    @Test
    fun `CWE-345 a negative token count is never summed into the usage`() {
        val scan =
            scanLlmAccounting(
                """
                {"type":"result","subtype":"success","total_cost_usd":0.1,"usage":{"input_tokens":5,"output_tokens":7,"cache_read_input_tokens":2,"cache_creation_input_tokens":4}}
                {"type":"result","subtype":"success","total_cost_usd":0.2,"usage":{"input_tokens":-100,"output_tokens":3,"cache_read_input_tokens":-7,"cache_creation_input_tokens":-9}}
                """.trimIndent().lineSequence(),
                claude::translate,
            )
        assertEquals(5L, scan.usage?.input)
        assertEquals(10L, scan.usage?.output)
        assertEquals(2L, scan.usage?.cacheRead)
        assertEquals(4L, scan.usage?.cacheWrite)
    }

    @Test
    fun `scan rejects an ungated session id failing the shared charset pattern`() {
        val scan = scanLlmAccounting(sequenceOf("""{"id":"../x"}"""), ::ungatedSessionIdTranslate)
        assertNull(scan.sessionId)
    }

    @Test
    fun `scan rejects an ungated session id over 128 chars`() {
        val tooLong = "a".repeat(129)
        val scan = scanLlmAccounting(sequenceOf("""{"id":"$tooLong"}"""), ::ungatedSessionIdTranslate)
        assertNull(scan.sessionId)
    }

    @Test
    fun `scan takes the first valid session id when an invalid one precedes it`() {
        val scan =
            scanLlmAccounting(
                sequenceOf("""{"id":"abc/def"}""", """{"id":"valid-2"}"""),
                ::ungatedSessionIdTranslate,
            )
        assertEquals("valid-2", scan.sessionId)
    }

    @Test
    fun `scan skips non-object and blank lines regardless of the adapter`() {
        val scan = scanLlmAccounting(sequenceOf("", "42", "\"str\"", """{"id":"ok-1"}"""), ::ungatedSessionIdTranslate)
        assertEquals("ok-1", scan.sessionId)
    }

    @Test
    fun `scan of empty input yields an empty scan`() {
        val scan = scanLlmAccounting(emptySequence(), ::ungatedSessionIdTranslate)
        assertNull(scan.sessionId)
        assertNull(scan.cliCostUsd)
        assertFalse(scan.resultSeen)
        assertEquals(0, scan.modelCalls)
        assertNull(scan.usage)
    }

    @Test
    fun `scan caps distinct model calls at 10000 regardless of adapter`() {
        fun idAsModelCall(obj: JsonObject): Translation =
            Translation.Events(listOf(LlmEvent.AssistantMessage((obj["id"] as? JsonPrimitive)?.contentOrNull, model = null, text = "x")))
        val lines = (0 until 10_050).asSequence().map { """{"id":"m$it"}""" }
        assertEquals(10_000, scanLlmAccounting(lines, ::idAsModelCall).modelCalls)
    }
}
