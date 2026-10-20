package homer.app.services

import homer.app.executors.llm.ClaudeHarness
import homer.app.executors.llm.LlmStepInfo
import homer.app.infra.SecretRedactor
import homer.app.infra.TelemetryService
import homer.app.infra.TranslatedStream
import homer.app.infra.parseNdjson
import homer.app.infra.translateStream
import homer.app.models.FALLBACK_REASON_NO_RESULT_EVENT
import homer.app.models.LlmEvent
import homer.app.models.LlmUsage
import homer.app.models.TurnStatus
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Characterization tests for LlmTelemetryService.
 *
 * These tests exercise span emission from canonical [LlmEvent]s directly — no harness
 * dialect, no NDJSON parsing. Adapter-level concerns (session id gating, thinking/redacted
 * blocks, schema-drift leniency) live in the harness's own tests (e.g. `ClaudeHarnessTest`).
 */
class LlmTelemetryServiceTest {
    private lateinit var mockTracer: Tracer
    private lateinit var mockTelemetryService: TelemetryService
    private lateinit var mockLogger: Logger
    private lateinit var mockParentSpan: Span
    private lateinit var defaultBuilder: SpanBuilder
    private lateinit var defaultSpan: Span

    private lateinit var service: LlmTelemetryService

    @BeforeEach
    fun setup() {
        mockParentSpan = relaxedSpan()
        defaultSpan = relaxedSpan()
        defaultBuilder = relaxedBuilder(defaultSpan)
        mockTracer =
            mockk {
                every { spanBuilder(any()) } returns defaultBuilder
            }
        mockTelemetryService =
            mockk {
                every { tracer } returns mockTracer
            }
        mockLogger = mockk(relaxed = true)

        service = LlmTelemetryService(mockTelemetryService, mockLogger)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `tracer delegates to the wrapped telemetry service`() {
        assertEquals(mockTracer, service.tracer)
    }

    private fun relaxedSpan(): Span =
        mockk(relaxed = true) {
            every { setAttribute(any<String>(), any<String>()) } returns this
            every { setAttribute(any<String>(), any<Long>()) } returns this
            every { setAttribute(any<String>(), any<Boolean>()) } returns this
            every { setStatus(any()) } returns this
            every { setStatus(any(), any<String>()) } returns this
            every { end() } just Runs
            every { end(any<Instant>()) } just Runs
        }

    private fun relaxedBuilder(span: Span): SpanBuilder =
        mockk {
            every { setParent(any()) } returns this
            every { setStartTimestamp(any<Instant>()) } returns this
            every { startSpan() } returns span
        }

    /** Register a specific span for a specific spanBuilder name; other names still return the default. */
    private fun wireNamedSpan(
        nameMatcher: (String) -> Boolean,
        span: Span,
    ) {
        every { mockTracer.spanBuilder(match { nameMatcher(it) }) } returns relaxedBuilder(span)
    }

    private fun stream(
        vararg events: LlmEvent,
        ignored: Long = 0,
        malformed: Long = 0,
    ): TranslatedStream = TranslatedStream(events.toList(), emptyList(), ignored, malformed, emptyList())

    private suspend fun process(
        vararg events: LlmEvent,
        step: LlmStepInfo = LlmStepInfo(),
        resolvedCostUsd: Double? = null,
        redactor: SecretRedactor = SecretRedactor.NONE,
    ) = service.process(stream(*events), "prompt", mockParentSpan, redactor, step, resolvedCostUsd)

    @Test
    fun `empty output file logs warning and does not create any span`() =
        runBlocking {
            service.process(stream(), "prompt", mockParentSpan)

            verify { mockLogger.warn("LLM output is empty, skipping telemetry") }
            verify(exactly = 0) { mockTracer.spanBuilder(any()) }
        }

    @Test
    fun `a run cancelled while its spans are emitted stops instead of logging a telemetry failure`() =
        runTest {
            var returned = false
            val job =
                launch {
                    process(LlmEvent.ToolCall("tool-1", "MyTool", buildJsonObject {}, "m1"))
                    returned = true
                }
            runCurrent() // parked in the span-ordering delay
            job.cancel()
            job.join()

            assertFalse(returned)
            verify(exactly = 0) { mockLogger.error(any<String>(), any<Throwable>()) }
        }

    @Test
    fun `system event sets parent span metadata attributes`() =
        runBlocking {
            process(LlmEvent.SessionStart("s", "claude-opus-4", listOf("Read", "Write")))

            verify { mockParentSpan.setAttribute("metadata.model", "claude-opus-4") }
            verify { mockParentSpan.setAttribute("metadata.tools", match<String> { it.contains("Read") && it.contains("Write") }) }
        }

    @Test
    fun `assistant tool_use block emits generation span with tool name and input`() =
        runBlocking {
            val toolSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". MyTool") }, toolSpan)

            process(
                LlmEvent.SessionStart(null, "claude-opus-4"),
                LlmEvent.ToolCall("tool-1", "MyTool", buildJsonObject { put("arg", "value") }, "m1"),
            )

            verify { mockTracer.spanBuilder(match<String> { it.endsWith(". MyTool") }) }
            verify { toolSpan.setAttribute("name", "MyTool") }
            verify { toolSpan.setAttribute("model", "claude-opus-4") }
            verify { toolSpan.setAttribute("input", match<String> { it.contains("arg") && it.contains("value") }) }
            verify { toolSpan.setStatus(StatusCode.OK) }
            verify { toolSpan.end(any<Instant>()) }
        }

    @Test
    fun `tool_result attaches output to matching tool_use span`() =
        runBlocking {
            val toolSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". MyTool") }, toolSpan)

            process(
                LlmEvent.ToolCall("tool-1", "MyTool", buildJsonObject {}, "m1"),
                LlmEvent.ToolResult("tool-1", JsonPrimitive("done")),
            )

            verify { toolSpan.setAttribute("output", match<String> { it.contains("done") }) }
        }

    @Test
    fun `result success creates Result span with prompt and usage attributes`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            service.process(
                stream(
                    LlmEvent.TurnEnd(
                        TurnStatus.Success,
                        LlmUsage(input = 100, output = 50, cacheRead = 20, cacheWrite = 10),
                        costUsd = 0.01,
                        durationMs = 1500,
                        durationApiMs = 1200,
                        numTurns = 3,
                        resultText = "ok",
                    ),
                ),
                "my-prompt",
                mockParentSpan,
            )

            verify { mockTracer.spanBuilder("Result") }
            verify { resultSpan.setAttribute("prompt", "my-prompt") }
            verify { resultSpan.setAttribute("usage_details.input_tokens", 100L) }
            verify { resultSpan.setAttribute("usage_details.output_tokens", 50L) }
            verify { resultSpan.setAttribute("usage_details.cache_read_input_tokens", 20L) }
            verify { resultSpan.setAttribute("usage_details.cache_creation_input_tokens", 10L) }
            verify { resultSpan.setStatus(StatusCode.OK) }
            verify { resultSpan.end(any<Instant>()) }
        }

    @Test
    fun `result span reports usage and cost in the attributes Langfuse reads`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(
                    TurnStatus.Success,
                    LlmUsage(input = 100, output = 50, cacheRead = 20, cacheWrite = 10),
                    costUsd = 0.01,
                    durationMs = 1500,
                    durationApiMs = 1200,
                    numTurns = 3,
                    resultText = "ok",
                ),
            )

            verify { resultSpan.setAttribute("langfuse.observation.type", "generation") }
            verify { resultSpan.setAttribute("langfuse.observation.model.name", "unknown") }
            verify { resultSpan.setAttribute("telemetry.llm.model.source", "unknown") }
            verify {
                resultSpan.setAttribute(
                    "langfuse.observation.usage_details",
                    match<String> {
                        it.contains("\"input\":100") &&
                            it.contains("\"output\":50") &&
                            it.contains("\"cache_creation_input_tokens\":10") &&
                            it.contains("\"cache_read_input_tokens\":20")
                    },
                )
            }
            // The reported spend must travel in cost_details; as plain text inside `output`
            // (where it also is) Langfuse never reads it and prices the run from its own guess.
            verify {
                resultSpan.setAttribute(
                    "langfuse.observation.cost_details",
                    match<String> { it.contains("\"total\":0.01") },
                )
            }
        }

    @Test
    fun `cost_details uses the resolved cost when supplied`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 100, output = 50), costUsd = 0.01),
                resolvedCostUsd = 0.1795,
            )

            verify {
                resultSpan.setAttribute(
                    "langfuse.observation.cost_details",
                    match<String> { it.contains("\"total\":0.1795") },
                )
            }
        }

    @Test
    fun `cost_details falls back to the event cost when none is supplied`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 100, output = 50), costUsd = 0.01))

            verify {
                resultSpan.setAttribute(
                    "langfuse.observation.cost_details",
                    match<String> { it.contains("\"total\":0.01") },
                )
            }
        }

    @Test
    fun `child spans stay unpriced so a run is charged exactly once`() =
        runBlocking {
            val toolSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". MyTool") }, toolSpan)

            process(LlmEvent.ToolCall("tool-1", "MyTool", buildJsonObject { put("arg", "value") }, "m1"))

            verify { toolSpan.setAttribute("langfuse.observation.type", "span") }
            verify(exactly = 0) { toolSpan.setAttribute("langfuse.observation.usage_details", any<String>()) }
            verify(exactly = 0) { toolSpan.setAttribute("langfuse.observation.cost_details", any<String>()) }
        }

    @Test
    fun `secret input values are redacted from result span attributes`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)
            val redactor = SecretRedactor.from(mapOf("TOKEN" to "s3cr3t-value"), setOf("TOKEN"))

            service.process(
                stream(
                    LlmEvent.TurnEnd(
                        TurnStatus.Success,
                        LlmUsage(input = 1, output = 1),
                        costUsd = 0.01,
                        durationMs = 1,
                        durationApiMs = 1,
                        numTurns = 1,
                        resultText = "leaked s3cr3t-value here",
                    ),
                ),
                "prompt with s3cr3t-value",
                mockParentSpan,
                redactor,
            )

            verify { resultSpan.setAttribute("prompt", "prompt with ***") }
            verify { resultSpan.setAttribute("output", match<String> { it.contains("***") && !it.contains("s3cr3t-value") }) }
            verify(exactly = 0) { resultSpan.setAttribute("prompt", match<String> { it.contains("s3cr3t-value") }) }
        }

    @Test
    fun `secrets with JSON-escapable characters are redacted from encoded span attributes`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)
            // Multi-line + quote secret: its JSON-encoded form differs from the raw value.
            val secret = "line1\nline2\"quote"
            val redactor = SecretRedactor.from(mapOf("TOKEN" to secret), setOf("TOKEN"))

            service.process(
                stream(
                    LlmEvent.TurnEnd(
                        TurnStatus.Success,
                        LlmUsage(input = 1, output = 1),
                        costUsd = 0.01,
                        durationMs = 1,
                        durationApiMs = 1,
                        numTurns = 1,
                        resultText = "leaked $secret here",
                    ),
                ),
                "prompt",
                mockParentSpan,
                redactor,
            )

            verify { resultSpan.setAttribute("metadata", match<String> { it.contains("***") && !it.contains("line2") }) }
            verify { resultSpan.setAttribute("output", match<String> { it.contains("***") && !it.contains("line2") }) }
        }

    @Test
    fun `malformedPreview lines are redacted before being logged`() =
        runBlocking {
            // [improper_input_validation / CWE-20] malformedPreview is agent-controlled
            // stdout (§12); the denial path this pins is deleting the redactor.redact(it)
            // call at the WARN call site, which would leak the raw secret into app.log.
            val redactor = SecretRedactor.from(mapOf("TOKEN" to "s3cr3t"), setOf("TOKEN"))
            // A filler event so the stream is not "empty" (events + raw both empty is the
            // distinct "skip telemetry entirely" guard, not what this test is about).
            val stream =
                TranslatedStream(listOf(LlmEvent.AssistantMessage(null, null, "")), emptyList(), 0, 1, listOf("x s3cr3t y"))

            service.process(stream, "prompt", mockParentSpan, redactor)

            verify { mockLogger.warn(any<String>(), eq("x *** y")) }
            verify(exactly = 0) { mockLogger.warn(any<String>(), match<String> { "s3cr3t" in it }) }
        }

    @Test
    fun `a TransientError event is warned about with its message redacted`() =
        runBlocking {
            val redactor = SecretRedactor.from(mapOf("TOKEN" to "s3cr3t"), setOf("TOKEN"))

            process(LlmEvent.TransientError("retrying after s3cr3t leak"), redactor = redactor)

            verify { mockLogger.warn(any<String>(), eq("retrying after *** leak")) }
            verify(exactly = 0) { mockLogger.warn(any<String>(), match<String> { "s3cr3t" in it }) }
        }

    @Test
    fun `a failed turn's reason is logged with its secrets redacted`() =
        runBlocking {
            // The reason is harness stdout (Codex error.message); CWE-532.
            val redactor = SecretRedactor.from(mapOf("TOKEN" to "s3cr3t"), setOf("TOKEN"))

            process(LlmEvent.TurnEnd(TurnStatus.Failed("auth failed for s3cr3t")), redactor = redactor)

            verify { mockLogger.error(any<String>(), eq("auth failed for ***")) }
            verify(exactly = 0) { mockLogger.error(any<String>(), match<Any> { "s3cr3t" in it.toString() }) }
            verify(exactly = 0) { mockLogger.warn(any<String>(), match<Any> { "s3cr3t" in it.toString() }) }
            verify(exactly = 0) { mockLogger.info(match<String> { "s3cr3t" in it }) }
        }

    @Test
    fun `result with non-success subtype emits a failed generation span with subtype metadata`() =
        runBlocking {
            val failedSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". Result") }, failedSpan)

            process(LlmEvent.TurnEnd(TurnStatus.Failed("error_during_execution")))

            verify { mockTracer.spanBuilder(match<String> { it.endsWith(". Result") }) }
            verify { failedSpan.setAttribute("name", "Result") }
            verify { failedSpan.setAttribute("metadata", match<String> { it.contains("\"reason\":\"error_during_execution\"") }) }
        }

    @Test
    fun `unknown event type is skipped and other events still emit spans`() =
        runBlocking {
            val toolSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". MyTool") }, toolSpan)

            service.process(
                stream(LlmEvent.ToolCall("t1", "MyTool", buildJsonObject {}, "m"), ignored = 1),
                "prompt",
                mockParentSpan,
            )

            verify { mockTracer.spanBuilder(match<String> { it.endsWith(". MyTool") }) }
            verify { toolSpan.end(any<Instant>()) }
            verify { mockParentSpan.setAttribute("telemetry.llm.ignored_events", 1L) }
        }

    @Test
    fun `unknown event types increment telemetry_claude_unknown_events span attribute (audit P1 #15)`() =
        runBlocking {
            // A filler event with no side effects, so the stream is not truly empty (ignored
            // lines only, as in production, still leave `raw` non-empty).
            service.process(
                stream(LlmEvent.AssistantMessage(null, null, ""), ignored = 2),
                "prompt",
                mockParentSpan,
            )

            verify { mockParentSpan.setAttribute("telemetry.llm.ignored_events", 2L) }
            verify { mockParentSpan.setAttribute("telemetry.llm.malformed_events", 0L) }
        }

    @Test
    fun `malformed event payload increments telemetry_claude_malformed_events span attribute (audit P1 #15)`() =
        runBlocking {
            service.process(
                stream(LlmEvent.AssistantMessage(null, null, ""), malformed = 1),
                "prompt",
                mockParentSpan,
            )

            verify { mockParentSpan.setAttribute("telemetry.llm.malformed_events", 1L) }
            verify { mockParentSpan.setAttribute("telemetry.llm.ignored_events", 0L) }
        }

    @Test
    fun `clean event stream sets zero unknown_events and zero malformed_events`() =
        runBlocking {
            process(LlmEvent.SessionStart("s", "m", emptyList()))

            verify { mockParentSpan.setAttribute("telemetry.llm.ignored_events", 0L) }
            verify { mockParentSpan.setAttribute("telemetry.llm.malformed_events", 0L) }
        }

    @Test
    fun `mixed sequence accumulates unknown and malformed counts independently`() =
        runBlocking {
            service.process(
                stream(LlmEvent.SessionStart("s", "m", emptyList()), ignored = 1, malformed = 1),
                "prompt",
                mockParentSpan,
            )

            verify { mockParentSpan.setAttribute("telemetry.llm.ignored_events", 1L) }
            verify { mockParentSpan.setAttribute("telemetry.llm.malformed_events", 1L) }
        }

    @Test
    fun `system model is applied to a later assistant span lacking its own model`() =
        runBlocking {
            val messageSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". Message") }, messageSpan)

            process(
                LlmEvent.SessionStart(null, "m"),
                LlmEvent.AssistantMessage(null, null, "hi"),
            )

            verify { messageSpan.setAttribute("model", "m") }
        }

    @Test
    fun `only the first SessionStart carrying a model sets parent span metadata`() =
        runBlocking {
            // Real Claude streams emit a model-less system/hook_* SessionStart before init,
            // and another model-less one off the result line. Neither may set metadata or
            // log, and neither may block the init line's SessionStart from doing so once it
            // arrives; once it has, a later model-less (or modelled) one changes nothing.
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.SessionStart("s", null),
                LlmEvent.SessionStart("s", "m1", listOf("A")),
                LlmEvent.SessionStart("s", null),
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1)),
            )

            verify(exactly = 1) { mockParentSpan.setAttribute("metadata.model", "m1") }
            verify(exactly = 1) { mockParentSpan.setAttribute("metadata.tools", match<String> { it.contains("A") }) }
            verify { resultSpan.setAttribute("langfuse.observation.model.name", "m1") }
            verify { resultSpan.setAttribute("telemetry.llm.model.source", "session_start") }
        }

    @Test
    fun `a blank-text assistant message produces no Message span, a non-blank one does`() =
        runBlocking {
            process(LlmEvent.AssistantMessage(null, null, ""))

            verify(exactly = 0) { mockTracer.spanBuilder(match<String> { it.endsWith(". Message") }) }

            val messageSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". Message") }, messageSpan)

            process(LlmEvent.AssistantMessage(null, null, "hi"))

            verify(exactly = 1) { mockTracer.spanBuilder(match<String> { it.endsWith(". Message") }) }
        }

    @Test
    fun `the step's label names the result generation span`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "implement" }, resultSpan)

            process(
                LlmEvent.TurnEnd(
                    TurnStatus.Success,
                    LlmUsage(input = 1, output = 1),
                    costUsd = 0.01,
                    durationMs = 10,
                    durationApiMs = 8,
                    numTurns = 1,
                    resultText = "ok",
                ),
                step = LlmStepInfo(label = "implement"),
            )

            verify { mockTracer.spanBuilder("implement") }
            verify { resultSpan.setAttribute("name", "implement") }
            verify { resultSpan.setAttribute("output", match<String> { it.contains("\"name\":\"implement\"") }) }
            verify(exactly = 0) { mockTracer.spanBuilder("Result") }
        }

    @Test
    fun `a blank step label falls back to the historical Result name`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(
                    TurnStatus.Success,
                    LlmUsage(input = 1, output = 1),
                    costUsd = 0.01,
                    durationMs = 10,
                    durationApiMs = 8,
                    numTurns = 1,
                    resultText = "ok",
                ),
                step = LlmStepInfo(label = "  "),
            )

            verify { mockTracer.spanBuilder("Result") }
        }

    @Test
    fun `a failed result generation is named after the step too`() =
        runBlocking {
            val failedSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". implement") }, failedSpan)

            process(
                LlmEvent.TurnEnd(
                    TurnStatus.Failed("error_max_turns"),
                    durationMs = 100,
                    durationApiMs = 80,
                    numTurns = 40,
                    resultText = "",
                ),
                step = LlmStepInfo(label = "implement"),
            )

            verify { failedSpan.setAttribute("name", "implement") }
            verify { failedSpan.setAttribute("metadata", match<String> { it.contains("error_max_turns") }) }
        }

    @Test
    fun `a real CLI stream yields the run's model and exactly one priced generation`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "commit-message" }, resultSpan)
            val toolSpan = relaxedSpan()
            wireNamedSpan({ it.endsWith(". Bash") }, toolSpan)

            val text =
                requireNotNull(javaClass.getResourceAsStream("/claude/cli-2.1-stream.jsonl")) { "missing test resource" }
                    .bufferedReader()
                    .readText()
            val translated = translateStream(parseNdjson(text), ClaudeHarness()::translate)

            service.process(translated, "prompt", mockParentSpan, step = LlmStepInfo(label = "commit-message"))

            verify { mockParentSpan.setAttribute("telemetry.llm.malformed_events", 0L) }
            verify { mockParentSpan.setAttribute("telemetry.llm.ignored_events", 0L) }
            verify { resultSpan.setAttribute("langfuse.observation.model.name", "claude-sonnet-4-6") }
            verify { resultSpan.setAttribute("telemetry.llm.model.source", "session_start") }
            verify {
                resultSpan.setAttribute(
                    "langfuse.observation.cost_details",
                    match<String> { it.contains("\"total\":0.0421") },
                )
            }
            verify { toolSpan.setAttribute("output", match<String> { it.contains("a.txt") }) }
            verify { toolSpan.setAttribute("langfuse.observation.type", "span") }
            // Exactly one priced observation per run (see emitGenerationSpans): the result
            // span is the only one ever marked "generation", never the child tool span.
            verify(exactly = 1) { resultSpan.setAttribute("langfuse.observation.type", "generation") }
            verify(exactly = 0) { toolSpan.setAttribute("langfuse.observation.type", "generation") }
        }

    /** Records every `cost_details` value [span] is given, in emission order. */
    private fun captureCostDetails(span: Span): MutableList<String> =
        mutableListOf<String>().also { raw ->
            every { span.setAttribute("langfuse.observation.cost_details", capture(raw)) } returns span
        }

    private fun costTotal(costDetails: String): Double =
        Json
            .parseToJsonElement(costDetails)
            .jsonObject
            .getValue("total")
            .jsonPrimitive.double

    private fun backgroundAgentStream(): TranslatedStream {
        // Captured from Claude CLI 2.1.287: a background subagent finishes after the main turn
        // ends, so the CLI writes a second `result`, and total_cost_usd is cumulative across both.
        val text =
            requireNotNull(javaClass.getResourceAsStream("/claude/cli-2.1-background-agent-stream.jsonl")) { "missing test resource" }
                .bufferedReader()
                .readText()
        return translateStream(parseNdjson(text), ClaudeHarness()::translate)
    }

    @Test
    fun `a second result splits the attempt's resolved cost instead of repeating it`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "implement" }, resultSpan)

            service.process(
                backgroundAgentStream(),
                "prompt",
                mockParentSpan,
                step = LlmStepInfo(label = "implement"),
                resolvedCostUsd = 0.12,
            )

            val totals = costs.map(::costTotal)
            assertEquals(2, totals.size)
            assertEquals(0.12, totals.sum(), 1e-12)
            assertEquals(0.12 * 0.05582284999999999 / 0.060736649999999996, totals[0], 1e-12)
            verify(exactly = 2) { resultSpan.setAttribute("langfuse.observation.type", "generation") }
        }

    @Test
    fun `without a resolved cost each result is priced at its own increment of the CLI total`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "implement" }, resultSpan)

            service.process(backgroundAgentStream(), "prompt", mockParentSpan, step = LlmStepInfo(label = "implement"))

            assertEquals(listOf(0.05582284999999999, 0.060736649999999996 - 0.05582284999999999), costs.map(::costTotal))
        }

    @Test
    fun `an attempt whose increments cannot be read is priced on its last turn end`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.05),
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = null),
                resolvedCostUsd = 0.3,
            )

            // The earlier one is priced at zero, not omitted: Langfuse would price an omitted one itself.
            assertEquals(listOf(0.0, 0.3), costs.map(::costTotal))
        }

    @Test
    fun `CWE-682 a cost that went down prices the attempt at the highest cost reported`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.06),
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.01),
            )

            assertEquals(listOf(0.0, 0.06), costs.map(::costTotal))
        }

    @Test
    fun `CWE-682 a negative cost never inflates the other turn ends' shares`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = -1.0),
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.5),
                resolvedCostUsd = 0.3,
            )

            assertEquals(listOf(0.0, 0.3), costs.map(::costTotal))
        }

    @Test
    fun `CWE-682 a lone negative cost is never sent to Langfuse`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = -1.0))

            verify(exactly = 0) { resultSpan.setAttribute("langfuse.observation.cost_details", any<String>()) }
        }

    @Test
    fun `a resolved cost of zero is priced at zero, not at the CLI figure`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            // A spend endpoint that answered zero: the ledger charged nothing, so Langfuse must not show the CLI figure.
            process(LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.05), resolvedCostUsd = 0.0)

            assertEquals(listOf(0.0), costs.map(::costTotal))
        }

    @Test
    fun `CWE-345 a negative token count is never shown on the generation`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = -5, output = 3, cacheRead = -1), costUsd = 0.01))

            verify { resultSpan.setAttribute("usage_details.input_tokens", 0L) }
            verify { resultSpan.setAttribute("usage_details.cache_read_input_tokens", 0L) }
            verify { resultSpan.setAttribute("langfuse.observation.usage_details", match<String> { "-" !in it }) }
            verify(exactly = 0) { resultSpan.setAttribute(match<String> { it.startsWith("usage_details.") }, match<Long> { it < 0 }) }
            verify { resultSpan.setAttribute("metadata", match<String> { "-" !in it }) }
        }

    @Test
    fun `CWE-840 a failed turn end's share moves to the next successful one`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Failed("error_during_execution"), LlmUsage(input = 1, output = 1), costUsd = 0.02),
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.06),
                resolvedCostUsd = 0.3,
            )

            assertEquals(1, costs.size)
            assertEquals(0.3, costTotal(costs.single()), 1e-12)
        }

    @Test
    fun `an attempt with no successful turn end is priced on its last turn end`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Failed("error_during_execution"), LlmUsage(input = 1, output = 1), costUsd = 0.02),
                LlmEvent.TurnEnd(TurnStatus.Stopped("error_max_turns"), LlmUsage(input = 3, output = 2), costUsd = 0.06),
                resolvedCostUsd = 0.3,
            )

            assertEquals(1, costs.size)
            assertEquals(0.3, costTotal(costs.single()), 1e-12)
            verify { resultSpan.setAttribute("langfuse.observation.type", "generation") }
            verify { resultSpan.setAttribute("usage_details.input_tokens", 3L) }
            verify { resultSpan.setStatus(StatusCode.ERROR, "error_max_turns") }
            // The earlier failed turn end stays an unpriced span.
            verify { mockTracer.spanBuilder(match<String> { it.endsWith(". Result") }) }
        }

    @Test
    fun `a turn-limited attempt is priced at its charge, not reported as free`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "implement" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Stopped("error_max_turns"), LlmUsage(input = 30, output = 12), costUsd = 1.2),
                step = LlmStepInfo(label = "implement"),
                resolvedCostUsd = 1.66,
            )

            assertEquals(listOf(1.66), costs.map(::costTotal))
            verify { resultSpan.setAttribute("metadata", match<String> { it.contains("error_max_turns") }) }
            verify { resultSpan.setStatus(StatusCode.ERROR, "error_max_turns") }
        }

    @Test
    fun `a priced failed turn end redacts its reason before it reaches the span status`() =
        runBlocking {
            // CWE-532: the reason is the CLI's error text, and a status description is exported like any attribute.
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)
            val redactor = SecretRedactor.from(mapOf("TOKEN" to "super-secret-token"), setOf("TOKEN"))

            process(
                LlmEvent.TurnEnd(TurnStatus.Failed("failed reading /home/me/.config: super-secret-token"), LlmUsage(input = 4, output = 2)),
                resolvedCostUsd = 0.5,
                redactor = redactor,
            )

            verify { resultSpan.setStatus(StatusCode.ERROR, match<String> { "super-secret-token" !in it && "***" in it }) }
            verify(exactly = 0) { resultSpan.setAttribute(any<String>(), match<String> { "super-secret-token" in it }) }
        }

    @Test
    fun `an attempt with no turn end and a resolved cost gets one priced generation`() =
        runBlocking {
            // A CLI killed by a timeout, an OOM kill or a rollout writes no result line, yet the ledger charged it.
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.SessionStart("s", "claude-sonnet-4-6"),
                LlmEvent.AssistantMessage(null, null, "working"),
                resolvedCostUsd = 12.74,
            )

            assertEquals(listOf(12.74), costs.map(::costTotal))
            verify { resultSpan.setAttribute("langfuse.observation.type", "generation") }
            verify { resultSpan.setAttribute("langfuse.observation.model.name", "claude-sonnet-4-6") }
            verify { resultSpan.setStatus(StatusCode.ERROR, FALLBACK_REASON_NO_RESULT_EVENT) }
        }

    @Test
    fun `an attempt with no turn end and no resolved cost gets no generation`() =
        runBlocking {
            process(LlmEvent.SessionStart("s", "m"))

            verify(exactly = 0) { mockTracer.spanBuilder("Result") }
        }

    @Test
    fun `CWE-840 a stopped turn end after the last successful one leaves its share there`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.02),
                LlmEvent.TurnEnd(TurnStatus.Stopped("error_max_turns"), LlmUsage(input = 1, output = 1), costUsd = 0.06),
                resolvedCostUsd = 0.3,
            )

            assertEquals(1, costs.size)
            assertEquals(0.3, costTotal(costs.single()), 1e-12)
        }

    @Test
    fun `a zero first cost leaves the whole attempt to the turn that spent it`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.0),
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.05),
                resolvedCostUsd = 0.1,
            )

            assertEquals(listOf(0.0, 0.1), costs.map(::costTotal))
        }

    @Test
    fun `an attempt the CLI priced at zero is charged on its last turn end`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            val costs = captureCostDetails(resultSpan)
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.0),
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1), costUsd = 0.0),
                resolvedCostUsd = 0.2,
            )

            assertEquals(listOf(0.0, 0.2), costs.map(::costTotal))
        }

    @Test
    fun `turn ends nobody reported a cost for stay unpriced`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1)),
                LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1, output = 1)),
            )

            verify(exactly = 2) { resultSpan.setAttribute("langfuse.observation.type", "generation") }
            verify(exactly = 0) { resultSpan.setAttribute("langfuse.observation.cost_details", any<String>()) }
        }

    @Test
    fun `the assistant message model answers when the init line reported none`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.SessionStart(null, null),
                LlmEvent.AssistantMessage(null, "claude-haiku-4-5", "hi"),
                LlmEvent.TurnEnd(
                    TurnStatus.Success,
                    LlmUsage(input = 1, output = 1),
                    costUsd = 0.01,
                    durationMs = 10,
                    durationApiMs = 8,
                    numTurns = 1,
                    resultText = "ok",
                ),
            )

            verify { resultSpan.setAttribute("langfuse.observation.model.name", "claude-haiku-4-5") }
            verify { resultSpan.setAttribute("telemetry.llm.model.source", "assistant") }
        }

    @Test
    fun `a subagent message does not report the run's model`() =
        runBlocking {
            // A subagent carries whatever its own `agents:` block configured, which is not the
            // model the run resolved to; the ladder falls through to the turn-end's own accounting.
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.AssistantMessage(null, "claude-haiku-4-5", "hi", subagent = true),
                LlmEvent.TurnEnd(
                    TurnStatus.Success,
                    LlmUsage(input = 1, output = 1),
                    costUsd = 0.01,
                    durationMs = 10,
                    durationApiMs = 8,
                    numTurns = 1,
                    resultText = "ok",
                ),
            )

            verify { resultSpan.setAttribute("langfuse.observation.model.name", "unknown") }
            verify { resultSpan.setAttribute("telemetry.llm.model.source", "unknown") }
        }

    @Test
    fun `modelUsage keys name every model that served the run`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(
                    TurnStatus.Success,
                    LlmUsage(input = 1, output = 1),
                    costUsd = 0.01,
                    model = "claude-haiku-4-5+claude-sonnet-4-6",
                    durationMs = 10,
                    durationApiMs = 8,
                    numTurns = 1,
                    resultText = "ok",
                ),
            )

            verify { resultSpan.setAttribute("langfuse.observation.model.name", "claude-haiku-4-5+claude-sonnet-4-6") }
            verify { resultSpan.setAttribute("telemetry.llm.model.source", "turn_end") }
        }

    @Test
    fun `the step's configured model is the last resort before unknown`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)

            process(
                LlmEvent.TurnEnd(
                    TurnStatus.Success,
                    LlmUsage(input = 1, output = 1),
                    costUsd = 0.01,
                    durationMs = 10,
                    durationApiMs = 8,
                    numTurns = 1,
                    resultText = "ok",
                ),
                step = LlmStepInfo(configuredModel = "opusplan"),
            )

            verify { resultSpan.setAttribute("langfuse.observation.model.name", "opusplan") }
            verify { resultSpan.setAttribute("telemetry.llm.model.source", "configured") }
        }

    @Test
    fun `cost_details is omitted when neither a resolved nor a reported cost exists`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)
            process(LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(input = 1)))
            verify(exactly = 0) { resultSpan.setAttribute("langfuse.observation.cost_details", any<String>()) }
        }

    @Test
    fun `reasoning tokens are reported when non-zero`() =
        runBlocking {
            val resultSpan = relaxedSpan()
            wireNamedSpan({ it == "Result" }, resultSpan)
            process(LlmEvent.TurnEnd(TurnStatus.Success, LlmUsage(reasoning = 9)))
            verify { resultSpan.setAttribute("usage_details.reasoning_tokens", 9L) }
            verify { resultSpan.setAttribute("langfuse.observation.usage_details", match<String> { "\"reasoning\":9" in it }) }
        }
}
