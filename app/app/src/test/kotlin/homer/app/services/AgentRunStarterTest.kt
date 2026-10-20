package homer.app.services

import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.AgentTelemetry
import homer.app.models.Param
import homer.app.models.ProcessCreationResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Trace tags built from `traceTag` inputs: the Langfuse traces API filters on
 * tags, so this is what makes a run findable by the ticket / MR it is about.
 */
class AgentRunStarterTest {
    private val tagsKey = AttributeKey.stringArrayKey("langfuse.trace.tags")

    private fun startAndCaptureTags(
        traceTags: List<String> = emptyList(),
        inputs: List<Param>? = null,
        legacyQueryParams: List<Param>? = null,
        resolved: Map<String, String> = emptyMap(),
    ): List<String> {
        val span = TestFixtures.mockSpan()
        val processExecutor = mockk<ProcessExecutor>(relaxed = true)
        coEvery {
            processExecutor.execute(any(), any())
        } returns ProcessCreationResult(processId = 7)

        val starter =
            AgentRunStarter(
                serverId = "test-server",
                processExecutor = processExecutor,
                processFactory = mockk(relaxed = true),
                telemetryService = TestFixtures.mockTelemetryService(span),
                costMeter = mockk(relaxed = true),
                maxInvocationDepth = 5,
                cooldownGate = mockk(relaxed = true),
            )
        val agent =
            AgentFixtures
                .simpleAgent(name = "triage-decision")
                .copy(
                    inputs = inputs,
                    queryParams = legacyQueryParams,
                    telemetry = AgentTelemetry(traceTags = traceTags),
                )

        runBlocking { starter.start(agent, emptyList(), "dispatch") { resolved } }

        val attributes = slot<Attributes>()
        verify { span.setAllAttributes(capture(attributes)) }
        return attributes.captured.get(tagsKey) ?: emptyList()
    }

    @Test
    fun `request source is the only tag when the agent declares none`() {
        val tags =
            startAndCaptureTags(
                inputs = listOf(Param(paramName = "mr", envName = "MR_IID")),
                resolved = mapOf("MR_IID" to "4211"),
            )
        assertEquals(listOf("dispatch"), tags)
    }

    @Test
    fun `declared traceTags are emitted as paramName colon value`() {
        val tags =
            startAndCaptureTags(
                traceTags = listOf("projectId", "mr"),
                inputs =
                    listOf(
                        Param(paramName = "projectId", envName = "PROJECT_ID"),
                        Param(paramName = "mr", envName = "MR_IID"),
                        Param(paramName = "note", envName = "NOTE_ID"),
                    ),
                resolved = mapOf("PROJECT_ID" to "4711", "MR_IID" to "4211", "NOTE_ID" to "99"),
            )
        assertEquals(listOf("dispatch", "projectId:4711", "mr:4211"), tags)
    }

    @Test
    fun `tag order follows the declared list, not the input order`() {
        // A property the per-input flag could not offer: the author controls tag order.
        val tags =
            startAndCaptureTags(
                traceTags = listOf("mr", "projectId"),
                inputs =
                    listOf(
                        Param(paramName = "projectId", envName = "PROJECT_ID"),
                        Param(paramName = "mr", envName = "MR_IID"),
                    ),
                resolved = mapOf("PROJECT_ID" to "4711", "MR_IID" to "4211"),
            )
        assertEquals(listOf("dispatch", "mr:4211", "projectId:4711"), tags)
    }

    @Test
    fun `absent and blank values add no tag`() {
        val tags =
            startAndCaptureTags(
                traceTags = listOf("mr", "note"),
                inputs =
                    listOf(
                        Param(paramName = "mr", envName = "MR_IID", required = false),
                        Param(paramName = "note", envName = "NOTE_ID", required = false),
                    ),
                resolved = mapOf("NOTE_ID" to "   "),
            )
        assertEquals(listOf("dispatch"), tags)
    }

    @Test
    fun `value over the length limit is skipped rather than truncated`() {
        val tags =
            startAndCaptureTags(
                traceTags = listOf("prompt"),
                inputs = listOf(Param(paramName = "prompt", envName = "PROMPT")),
                resolved = mapOf("PROMPT" to "x".repeat(129)),
            )
        assertEquals(listOf("dispatch"), tags)
    }

    @Test
    fun `a path-shaped value with a slash is dropped, not tagged`() {
        // Decision 4: the value charset has no `/`, so a `group/repo` project path is
        // dropped with a WARN — the author must tag the numeric project id instead.
        val tags =
            startAndCaptureTags(
                traceTags = listOf("project"),
                inputs = listOf(Param(paramName = "project", envName = "PROJECT")),
                resolved = mapOf("PROJECT" to "group/repo"),
            )
        assertEquals(listOf("dispatch"), tags)
    }

    @Test
    fun `value at the length limit is still tagged`() {
        val value = "x".repeat(128)
        val tags =
            startAndCaptureTags(
                traceTags = listOf("prompt"),
                inputs = listOf(Param(paramName = "prompt", envName = "PROMPT")),
                resolved = mapOf("PROMPT" to value),
            )
        assertEquals(listOf("dispatch", "prompt:$value"), tags)
    }

    @Test
    fun `legacy queryParams declarations are honoured too`() {
        val tags =
            startAndCaptureTags(
                traceTags = listOf("mr"),
                legacyQueryParams = listOf(Param(paramName = "mr", envName = "MR_IID")),
                resolved = mapOf("MR_IID" to "4211"),
            )
        assertEquals(listOf("dispatch", "mr:4211"), tags)
    }

    @Test
    fun `a name that matches no declared input is skipped, not guessed`() {
        // Agent load rejects this, so it can only happen if the definition was edited
        // underneath a live run. Skipping beats tagging with an empty or invented value.
        val tags =
            startAndCaptureTags(
                traceTags = listOf("gone"),
                inputs = listOf(Param(paramName = "mr", envName = "MR_IID")),
                resolved = mapOf("MR_IID" to "4211"),
            )
        assertEquals(listOf("dispatch"), tags)
    }

    @Test
    fun `a currently-secret input is never tagged, even if traceTags names it`() {
        // Agent load rejects this combination, so reaching it means the definition was
        // edited underneath a live run. Belt and braces: the tag builder refuses too,
        // rather than trusting that the only way in was closed.
        val tags =
            startAndCaptureTags(
                traceTags = listOf("token"),
                inputs = listOf(Param(paramName = "token", envName = "TOKEN", secret = true)),
                resolved = mapOf("TOKEN" to "tok-secret"),
            )
        assertEquals(listOf("dispatch"), tags)
    }

    @Test
    fun `parentProcessId reaches the process row and the request span`() {
        val span = TestFixtures.mockSpan()
        val processExecutor = mockk<ProcessExecutor>(relaxed = true)
        coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 8)
        val processFactory = mockk<ProcessFactory>(relaxed = true)
        val starter =
            AgentRunStarter(
                serverId = "test-server",
                processExecutor = processExecutor,
                processFactory = processFactory,
                telemetryService = TestFixtures.mockTelemetryService(span),
                costMeter = mockk(relaxed = true),
                maxInvocationDepth = 5,
                cooldownGate = mockk(relaxed = true),
            )
        val agent = AgentFixtures.simpleAgent(name = "child")

        runBlocking { starter.start(agent, emptyList(), "agent", parentProcessId = 7) { emptyMap() } }

        verify {
            processFactory.create(
                agent = agent,
                queryParams = any(),
                parentContext = any(),
                invocationChain = listOf("child"),
                ancestorLockKeys = any(),
                owner = any(),
                extraSecretInputKeys = any(),
                parentProcessId = 7,
            )
        }
        val attributes = slot<Attributes>()
        verify { span.setAllAttributes(capture(attributes)) }
        assertEquals(7L, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
    }

    @Test
    fun `rootProcessId reaches the process row and the request span`() {
        val span = TestFixtures.mockSpan()
        val processExecutor = mockk<ProcessExecutor>(relaxed = true)
        coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 8)
        val processFactory = mockk<ProcessFactory>(relaxed = true)
        val starter =
            AgentRunStarter(
                serverId = "test-server",
                processExecutor = processExecutor,
                processFactory = processFactory,
                telemetryService = TestFixtures.mockTelemetryService(span),
                costMeter = mockk(relaxed = true),
                maxInvocationDepth = 5,
                cooldownGate = mockk(relaxed = true),
            )
        val agent = AgentFixtures.simpleAgent(name = "child")

        runBlocking {
            starter.start(agent, emptyList(), "agent", parentProcessId = 7, rootProcessId = 3) { emptyMap() }
        }

        verify {
            processFactory.create(
                agent = agent,
                queryParams = any(),
                parentContext = any(),
                invocationChain = listOf("child"),
                ancestorLockKeys = any(),
                owner = any(),
                extraSecretInputKeys = any(),
                parentProcessId = 7,
                rootProcessId = 3,
            )
        }
        val attributes = slot<Attributes>()
        verify { span.setAllAttributes(capture(attributes)) }
        assertEquals(3L, attributes.captured.get(AttributeKey.longKey("root.process.id")))
    }

    @Test
    fun `a rootless start stamps neither the parent nor the root span attribute`() {
        val span = TestFixtures.mockSpan()
        val processExecutor = mockk<ProcessExecutor>(relaxed = true)
        coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 8)
        val starter =
            AgentRunStarter(
                serverId = "test-server",
                processExecutor = processExecutor,
                processFactory = mockk(relaxed = true),
                telemetryService = TestFixtures.mockTelemetryService(span),
                costMeter = mockk(relaxed = true),
                maxInvocationDepth = 5,
                cooldownGate = mockk(relaxed = true),
            )

        runBlocking { starter.start(AgentFixtures.simpleAgent(name = "child"), emptyList(), "agent") { emptyMap() } }

        val attributes = slot<Attributes>()
        verify { span.setAllAttributes(capture(attributes)) }
        assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
        assertEquals(null, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
    }
}
