package homer.app.infra

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.fixtures.TestFixtures
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.Param
import homer.app.models.TagValidation
import homer.app.models.Tags
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Direct unit tests for [langfuseTraceTags]'s [TagValidation.VALUE_PATTERN]
 * enforcement: a valid value tags the run, an off-charset or oversized value is
 * dropped (never rewritten) with a WARN, and an empty effective `fromInputs` list
 * short-circuits to an empty list. See ADR-0060.
 */
class AgentTracingTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: LogbackLogger

    @BeforeEach
    fun setup() {
        logger = LoggerFactory.getLogger("homer.app.infra.AgentTracing") as LogbackLogger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(appender)
    }

    private fun agentWith(
        fromInputs: List<String>,
        param: Param = Param(paramName = "mr", envName = "MR_IID"),
    ) = Agent(
        name = "tag_agent",
        commands = listOf(Command.Cmd(cmd = listOf("echo", "hi"), timeout = 60)),
        inputs = listOf(param),
        tags = Tags(fromInputs = fromInputs),
    )

    private fun warnings(): List<String> = appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }

    @Test
    fun `a valid-charset value produces a paramName colon value tag`() {
        val tags =
            langfuseTraceTags(
                agentWith(fromInputs = listOf("mr")),
                resolvedInputs = mapOf("MR_IID" to "15"),
                secretEnvNames = emptySet(),
            )

        assertEquals(listOf("mr:15"), tags)
        assertTrue(warnings().isEmpty(), "a valid value must not warn")
    }

    @Test
    fun `an off-charset value is dropped and logs a WARN`() {
        val tags =
            langfuseTraceTags(
                agentWith(fromInputs = listOf("mr")),
                // A space is outside [A-Za-z0-9._-], so the value cannot be a tag.
                resolvedInputs = mapOf("MR_IID" to "mr 15"),
                secretEnvNames = emptySet(),
            )

        assertEquals(emptyList(), tags)
        assertTrue(
            warnings().any { it.contains("outside [A-Za-z0-9._-]") },
            "an off-charset value must log a WARN naming the charset: ${warnings()}",
        )
    }

    @Test
    fun `an oversized value is dropped and logs a WARN`() {
        val oversized = "v".repeat(TagValidation.MAX_TAG_VALUE_LENGTH + 1)
        val tags =
            langfuseTraceTags(
                agentWith(fromInputs = listOf("mr")),
                resolvedInputs = mapOf("MR_IID" to oversized),
                secretEnvNames = emptySet(),
            )

        assertEquals(emptyList(), tags)
        assertTrue(
            warnings().any { it.contains("over the ${TagValidation.MAX_TAG_VALUE_LENGTH}-char") },
            "an oversized value must log a WARN naming the limit: ${warnings()}",
        )
    }

    @Test
    fun `a secret input is excluded and produces no tag`() {
        val tags =
            langfuseTraceTags(
                agentWith(fromInputs = listOf("mr")),
                resolvedInputs = mapOf("MR_IID" to "15"),
                secretEnvNames = setOf("MR_IID"),
            )

        assertEquals(emptyList(), tags)
        assertTrue(warnings().isEmpty(), "excluding a secret must not warn")
    }

    @Test
    fun `an empty effective fromInputs returns an empty list without warning`() {
        val tags =
            langfuseTraceTags(
                agentWith(fromInputs = emptyList()),
                resolvedInputs = mapOf("MR_IID" to "15"),
                secretEnvNames = emptySet(),
            )

        assertEquals(emptyList(), tags)
        assertTrue(warnings().isEmpty())
    }

    // ========== stampLangfuseTrace ==========

    private fun stampedName(span: Span): String? {
        val name = slot<String>()
        verify { span.setAttribute(LANGFUSE_TRACE_NAME, capture(name)) }
        return name.captured
    }

    private fun stampedTags(span: Span): List<String>? {
        val tags = slot<List<String>>()
        verify { span.setAttribute(LANGFUSE_TRACE_TAGS, capture(tags)) }
        return tags.captured
    }

    @Test
    fun `stamps the agent name and the derived tags on the span`() {
        val span = TestFixtures.mockSpan()

        span.stampLangfuseTrace(agentWith(fromInputs = listOf("mr")), listOf("http", "mr:15"))

        assertEquals("tag_agent", stampedName(span))
        assertEquals(listOf("http", "mr:15"), stampedTags(span))
    }

    @Test
    fun `a secret input stays out of the stamped tags`() {
        // The stamp is the last point before the values are exported on every span of the run,
        // so the filtering has to be visible here and not only in the derivation.
        val span = TestFixtures.mockSpan()
        val agent = agentWith(fromInputs = listOf("mr"))

        span.stampLangfuseTrace(
            agent,
            listOf("http") +
                langfuseTraceTags(agent, mapOf("MR_IID" to "tok-secret"), secretEnvNames = setOf("MR_IID")),
        )

        assertEquals(listOf("http"), stampedTags(span))
    }

    @Test
    fun `an empty tag list stamps an empty array`() {
        // Not "no attribute": Langfuse reads the key per observation, and a span without it is
        // indistinguishable from one whose run has no tags. An empty array is the honest answer.
        val span = TestFixtures.mockSpan()

        span.stampLangfuseTrace(agentWith(fromInputs = emptyList()), emptyList())

        assertEquals(emptyList(), stampedTags(span))
    }

    @Test
    fun `an over-count tag list is truncated at the per-span ceiling with a WARN`() {
        // The list rides on every span of the run, so it is capped where it is stamped rather
        // than in the collector (denial_of_service / CWE-400).
        val span = TestFixtures.mockSpan()
        val over = (1..TagValidation.MAX_TRACE_TAGS + 5).map { "tag$it" }

        span.stampLangfuseTrace(agentWith(fromInputs = emptyList()), over)

        assertEquals(over.take(TagValidation.MAX_TRACE_TAGS), stampedTags(span))
        assertTrue(
            warnings().any { it.contains("over the ${TagValidation.MAX_TRACE_TAGS}-tag per-span limit") },
            "a truncated list must log a WARN naming the ceiling: ${warnings()}",
        )
    }

    @Test
    fun `a list at the ceiling is stamped whole and without a WARN`() {
        val span = TestFixtures.mockSpan()
        val atCap = (1..TagValidation.MAX_TRACE_TAGS).map { "tag$it" }

        span.stampLangfuseTrace(agentWith(fromInputs = emptyList()), atCap)

        assertEquals(atCap, stampedTags(span))
        assertTrue(warnings().isEmpty(), "a list at the ceiling is not a defect: ${warnings()}")
    }
}
