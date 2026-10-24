package homer.app.services

import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.HomerException
import homer.app.exceptions.MaintenanceQueueFullException
import homer.app.exceptions.MaintenanceWaitTimeoutException
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.AgentTelemetry
import homer.app.models.Param
import homer.app.models.ProcessCreationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

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
                maintenanceGate = mockk(relaxed = true),
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
                maintenanceGate = mockk(relaxed = true),
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
                maintenanceGate = mockk(relaxed = true),
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
                maintenanceGate = mockk(relaxed = true),
            )

        runBlocking { starter.start(AgentFixtures.simpleAgent(name = "child"), emptyList(), "agent") { emptyMap() } }

        val attributes = slot<Attributes>()
        verify { span.setAllAttributes(capture(attributes)) }
        assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
        assertEquals(null, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
    }

    // --- the maintenance gate (ADR-0092) ---

    /** A starter whose gate is [gate], returning a run id from a relaxed [ProcessExecutor]. */
    private fun starterWithGate(
        gate: MaintenanceModeService,
        processExecutor: ProcessExecutor,
        cooldownGate: CooldownGate = mockk(relaxed = true),
        costMeter: CostMeter = mockk(relaxed = true),
    ): AgentRunStarter =
        AgentRunStarter(
            serverId = "test-server",
            processExecutor = processExecutor,
            processFactory = mockk(relaxed = true),
            telemetryService = TestFixtures.mockTelemetryService(TestFixtures.mockSpan()),
            costMeter = costMeter,
            maxInvocationDepth = 5,
            cooldownGate = cooldownGate,
            maintenanceGate = gate,
        )

    @Test
    fun `a start parks at the gate and only then runs, once the mode goes off`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val gate = mockk<MaintenanceModeService>()
            val modeOff = CompletableDeferred<Unit>()
            coEvery { gate.awaitRelease(any()) } coAnswers {
                modeOff.await()
                MaintenanceSlot { }
            }
            val starter = starterWithGate(gate, processExecutor)

            val start = async { starter.start(AgentFixtures.simpleAgent(name = "child"), emptyList(), "http") { emptyMap() } }
            yield()
            coVerify(exactly = 0) { processExecutor.execute(any(), any()) }

            modeOff.complete(Unit)
            assertEquals(7, start.await())
            coVerify(exactly = 1) { processExecutor.execute(any(), any()) }
        }

    @Test
    fun `preflight runs at dequeue, not at enqueue`() =
        runBlocking {
            // supervisorScope: the start fails on purpose, and a failing child of the test's
            // own scope would cancel the test body instead of surfacing at the await.
            supervisorScope {
                val processExecutor = mockk<ProcessExecutor>(relaxed = true)
                val cooldownGate = mockk<CooldownGate>()
                coEvery { cooldownGate.check(any()) } throws CooldownActiveException("child", 5, "cooling down")
                val gate = mockk<MaintenanceModeService>()
                val modeOff = CompletableDeferred<Unit>()
                coEvery { gate.awaitRelease(any()) } coAnswers {
                    modeOff.await()
                    MaintenanceSlot { }
                }
                val starter = starterWithGate(gate, processExecutor, cooldownGate = cooldownGate)

                val start = async { starter.start(AgentFixtures.simpleAgent(name = "child"), emptyList(), "http") { emptyMap() } }
                yield()
                // Parked, so nothing has been refused yet: the checks run against the state at dequeue.
                coVerify(exactly = 0) { cooldownGate.check(any()) }

                modeOff.complete(Unit)
                assertFailsWith<CooldownActiveException> { start.await() }
                coVerify(exactly = 1) { cooldownGate.check(any()) }
                coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
            }
        }

    @Test
    fun `a start the queue sheds creates no run`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            val gate = mockk<MaintenanceModeService>()
            coEvery { gate.awaitRelease(any()) } throws MaintenanceQueueFullException(500)
            val starter = starterWithGate(gate, processExecutor)

            assertFailsWith<MaintenanceQueueFullException> {
                starter.start(AgentFixtures.simpleAgent(name = "child"), emptyList(), "http") { emptyMap() }
            }
            coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
        }

    @Test
    fun `a shed or expired start names no input value`() =
        runBlocking {
            val secret = "s3cr3t-token-value"
            val agent = AgentFixtures.simpleAgent(name = "child")
            for (refusal in listOf(MaintenanceQueueFullException(500), MaintenanceWaitTimeoutException(600.seconds))) {
                val gate = mockk<MaintenanceModeService>()
                coEvery { gate.awaitRelease(any()) } throws refusal
                val starter = starterWithGate(gate, mockk(relaxed = true))

                val thrown =
                    assertFailsWith<HomerException> {
                        starter.start(agent, emptyList(), "http") { mapOf("TOKEN" to secret) }
                    }
                assertFalse(thrown.message!!.contains(secret), "the refusal must not echo an input value")
            }
        }
}
