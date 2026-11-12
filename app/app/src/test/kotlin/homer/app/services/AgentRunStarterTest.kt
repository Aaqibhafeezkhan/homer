package homer.app.services

import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.CooldownKeyInvalidException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.HomerException
import homer.app.exceptions.InvocationCycleDetectedException
import homer.app.exceptions.InvocationDepthExceededException
import homer.app.exceptions.MaintenanceQueueFullException
import homer.app.exceptions.MaintenanceWaitTimeoutException
import homer.app.exceptions.ServerDrainingException
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.Agent
import homer.app.models.AgentTelemetry
import homer.app.models.Cooldown
import homer.app.models.Param
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessStatus
import homer.app.services.persistence.ProcessRecordReads
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
                processReads = mockk(relaxed = true),
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

        // The tags are stamped on the root before `execute`, not folded into the attributes set
        // after it, so a child span started inside the run inherits them. See ADR-0050.
        val tags = slot<List<String>>()
        verify { span.setAttribute(tagsKey, capture(tags)) }
        return tags.captured
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
                processReads = mockk(relaxed = true),
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
                processReads = mockk(relaxed = true),
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
                processReads = mockk(relaxed = true),
            )

        runBlocking { starter.start(AgentFixtures.simpleAgent(name = "child"), emptyList(), "agent") { emptyMap() } }

        val attributes = slot<Attributes>()
        verify { span.setAllAttributes(capture(attributes)) }
        assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
        assertEquals(null, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
    }

    // --- the maintenance gate (ADR-0092) ---

    /**
     * A starter whose gate is [gate], returning a run id from a relaxed [ProcessExecutor].
     * [parentStatus] is what the parent read reports for any id — `UNKNOWN`, the status of an
     * absent or already-ended row, unless a test is exercising the maintenance bypass. See ADR-0092.
     */
    private fun starterWithGate(
        gate: MaintenanceModeService,
        processExecutor: ProcessExecutor,
        cooldownGate: CooldownGate = mockk(relaxed = true),
        costMeter: CostMeter = mockk(relaxed = true),
        parentStatus: ProcessStatus = ProcessStatus.UNKNOWN,
        processReads: ProcessRecordReads = mockk { coEvery { getProcessStatus(any()) } returns parentStatus },
        processFactory: ProcessFactory = mockk(relaxed = true),
    ): AgentRunStarter =
        AgentRunStarter(
            serverId = "test-server",
            processExecutor = processExecutor,
            processFactory = processFactory,
            telemetryService = TestFixtures.mockTelemetryService(TestFixtures.mockSpan()),
            costMeter = costMeter,
            maxInvocationDepth = 5,
            cooldownGate = cooldownGate,
            maintenanceGate = gate,
            processReads = processReads,
        )

    /**
     * A gate with the mode on that refuses every parker outright, so a start that reaches it fails
     * instead of hanging the test — and a start that does not reach it proves the bypass.
     */
    private fun refusingGate(): MaintenanceModeService =
        mockk {
            every { isEnabled() } returns true
            every { isDraining() } returns false
            coEvery { awaitRelease(any()) } throws MaintenanceQueueFullException(500)
        }

    /**
     * A gate with the drain running and the mode off: a start that is not admitted is refused
     * outright, so a start that reaches [MaintenanceModeService.awaitRelease] would hang the test
     * — and one that does not reach the gate at all proves the drain admitted it. See ADR-0102.
     */
    private fun drainingGate(): MaintenanceModeService =
        mockk {
            every { isEnabled() } returns false
            every { isDraining() } returns true
            coEvery { awaitRelease(any()) } throws MaintenanceQueueFullException(500)
        }

    @Test
    fun `a start parks at the gate and only then runs, once the mode goes off`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val gate = mockk<MaintenanceModeService>()
            every { gate.isDraining() } returns false
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
                every { gate.isDraining() } returns false
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

    // ===== cooldown.key: rendered from the resolved inputs, before any row exists =====

    /** An agent whose cooldown key is a template over one declared input. */
    private fun keyedAgent(cooldown: Cooldown? = Cooldown(minInterval = "1m", key = "mr-{{MR}}")) =
        AgentFixtures
            .simpleAgent(name = "child")
            .copy(
                inputs = listOf(Param(paramName = "mr", envName = "MR", required = false)),
                cooldown = cooldown,
            )

    @Test
    fun `a keyed cooldown is checked after the inputs are resolved, and its key reaches the row`() =
        runBlocking {
            // The key is a template over the inputs, so the cooldown check cannot run before they
            // are resolved — but it must still run before `create`, so a refused or unrenderable
            // start leaves no process row behind. See ADR-0105.
            val order = mutableListOf<String>()
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val cooldownGate = mockk<CooldownGate>(relaxed = true)
            coEvery { cooldownGate.check(any(), any()) } coAnswers {
                order += "cooldown"
            }
            val processFactory = mockk<ProcessFactory>(relaxed = true)
            val starter =
                starterWithGate(
                    mockk(relaxed = true),
                    processExecutor,
                    cooldownGate = cooldownGate,
                    processFactory = processFactory,
                )

            assertEquals(
                7,
                starter.start(keyedAgent(), emptyList(), "http") {
                    order += "inputs"
                    mapOf("MR" to "1")
                },
            )

            assertEquals(listOf("inputs", "cooldown"), order)
            coVerify(exactly = 1) { cooldownGate.check(any(), "mr-1") }
            // The same rendered value goes to the row, so the pre-flight gate and the
            // authoritative check inside the INSERT cannot scope differently.
            verify {
                processFactory.create(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    "mr-1",
                )
            }
        }

    @Test
    fun `a keyless cooldown is still checked before the inputs are resolved`() =
        runBlocking {
            // The order the keyless path always had: cycle → depth → cost cap → cooldown →
            // input validation, so a start that is going to be refused resolves nothing.
            val order = mutableListOf<String>()
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val cooldownGate = mockk<CooldownGate>(relaxed = true)
            coEvery { cooldownGate.check(any(), any()) } coAnswers {
                order += "cooldown"
            }
            val starter = starterWithGate(mockk(relaxed = true), processExecutor, cooldownGate = cooldownGate)

            starter.start(keyedAgent(cooldown = Cooldown(minInterval = "1m")), emptyList(), "http") {
                order += "inputs"
                mapOf("MR" to "1")
            }

            assertEquals(listOf("cooldown", "inputs"), order)
            coVerify(exactly = 1) { cooldownGate.check(any(), null) }
        }

    @Test
    fun `an unresolvable key fails the start and creates no run`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            val processFactory = mockk<ProcessFactory>(relaxed = true)
            val starter =
                starterWithGate(
                    mockk(relaxed = true),
                    processExecutor,
                    processFactory = processFactory,
                )

            assertFailsWith<CooldownKeyInvalidException> {
                starter.start(keyedAgent(), emptyList(), "http") { emptyMap() }
            }
            verify(exactly = 0) {
                processFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
        }

    @Test
    fun `a start the queue sheds creates no run`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            val gate = mockk<MaintenanceModeService>()
            every { gate.isDraining() } returns false
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
                every { gate.isDraining() } returns false
                coEvery { gate.awaitRelease(any()) } throws refusal
                val starter = starterWithGate(gate, mockk(relaxed = true))

                val thrown =
                    assertFailsWith<HomerException> {
                        starter.start(agent, emptyList(), "http") { mapOf("TOKEN" to secret) }
                    }
                assertFalse(thrown.message!!.contains(secret), "the refusal must not echo an input value")
            }
        }

    // --- the bypass for work the window already admitted (ADR-0092) ---

    @Test
    fun `a server-derived start whose parent is still WORKING runs without parking`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val gate = refusingGate()
            val starter = starterWithGate(gate, processExecutor, parentStatus = ProcessStatus.WORKING)

            val processId =
                starter.start(
                    AgentFixtures.simpleAgent(name = "child"),
                    emptyList(),
                    "agent",
                    parentProcessId = 42,
                    serverDerivedParent = true,
                ) { emptyMap() }

            assertEquals(7, processId)
            coVerify(exactly = 0) { gate.awaitRelease(any()) }
            coVerify(exactly = 1) { processExecutor.execute(any(), any()) }
        }

    @Test
    fun `the bypass is reported on the request span as a boolean`() =
        runBlocking {
            val span = TestFixtures.mockSpan()
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val starter =
                AgentRunStarter(
                    serverId = "test-server",
                    processExecutor = processExecutor,
                    processFactory = mockk(relaxed = true),
                    telemetryService = TestFixtures.mockTelemetryService(span),
                    costMeter = mockk(relaxed = true),
                    maxInvocationDepth = 5,
                    cooldownGate = mockk(relaxed = true),
                    maintenanceGate = refusingGate(),
                    processReads = mockk { coEvery { getProcessStatus(any()) } returns ProcessStatus.WORKING },
                )

            starter.start(
                AgentFixtures.simpleAgent(name = "child"),
                emptyList(),
                "agent",
                parentProcessId = 42,
                serverDerivedParent = true,
            ) { emptyMap() }

            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(true, attributes.captured.get(AttributeKey.booleanKey("maintenance.bypassed")))
        }

    @Test
    fun `a server-derived start runs with the mode off without reading the parent row`() =
        runBlocking {
            // The steady state: the in-memory flag decides first, so the start neither parks
            // (nothing to bypass) nor probes a parent row it would discard the answer of.
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val gate =
                mockk<MaintenanceModeService> {
                    every { isEnabled() } returns false
                    every { isDraining() } returns false
                    coEvery { awaitRelease(any()) } returns MaintenanceSlot { }
                }
            val processReads =
                mockk<ProcessRecordReads> {
                    coEvery { getProcessStatus(any()) } returns ProcessStatus.WORKING
                }
            val span = TestFixtures.mockSpan()
            val starter =
                AgentRunStarter(
                    serverId = "test-server",
                    processExecutor = processExecutor,
                    processFactory = mockk(relaxed = true),
                    telemetryService = TestFixtures.mockTelemetryService(span),
                    costMeter = mockk(relaxed = true),
                    maxInvocationDepth = 5,
                    cooldownGate = mockk(relaxed = true),
                    maintenanceGate = gate,
                    processReads = processReads,
                )

            val processId =
                starter.start(
                    AgentFixtures.simpleAgent(name = "child"),
                    emptyList(),
                    "agent",
                    parentProcessId = 42,
                    serverDerivedParent = true,
                ) { emptyMap() }

            assertEquals(7, processId)
            coVerify(exactly = 0) { processReads.getProcessStatus(any()) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(false, attributes.captured.get(AttributeKey.booleanKey("maintenance.bypassed")))
        }

    @Test
    fun `a start whose server-derived parent has ended parks as before`() =
        runBlocking {
            for (status in listOf(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.KILLED, ProcessStatus.UNKNOWN)) {
                val processExecutor = mockk<ProcessExecutor>(relaxed = true)
                val gate = refusingGate()
                val starter = starterWithGate(gate, processExecutor, parentStatus = status)

                assertFailsWith<MaintenanceQueueFullException> {
                    starter.start(
                        AgentFixtures.simpleAgent(name = "child"),
                        emptyList(),
                        "agent",
                        parentProcessId = 42,
                        serverDerivedParent = true,
                    ) { emptyMap() }
                }
                coVerify(exactly = 1) { gate.awaitRelease(any()) }
                coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
            }
        }

    @Test
    fun `a start with no parent parks even when the flag is set`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            val gate = refusingGate()
            val starter = starterWithGate(gate, processExecutor, parentStatus = ProcessStatus.WORKING)

            assertFailsWith<MaintenanceQueueFullException> {
                starter.start(
                    AgentFixtures.simpleAgent(name = "child"),
                    emptyList(),
                    "agent",
                    serverDerivedParent = true,
                ) { emptyMap() }
            }
            coVerify(exactly = 1) { gate.awaitRelease(any()) }
            coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
        }

    @Test
    fun `a caller-supplied parent that happens to be WORKING still parks`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            val gate = refusingGate()
            val starter = starterWithGate(gate, processExecutor, parentStatus = ProcessStatus.WORKING)

            assertFailsWith<MaintenanceQueueFullException> {
                starter.start(
                    AgentFixtures.simpleAgent(name = "child"),
                    emptyList(),
                    "http",
                    parentProcessId = 42,
                ) { emptyMap() }
            }
            coVerify(exactly = 1) { gate.awaitRelease(any()) }
            coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
        }

    @Test
    fun `a bypassing start still runs the cycle, depth, cost-cap and cooldown checks`() =
        runBlocking {
            supervisorScope {
                val agent = AgentFixtures.simpleAgent(name = "child")
                val processExecutor = mockk<ProcessExecutor>(relaxed = true)
                val costMeter = mockk<CostMeter>(relaxed = true)
                val cooldownGate = mockk<CooldownGate>(relaxed = true)

                suspend fun start(
                    chain: List<String>,
                    target: Agent = agent,
                ): Int =
                    starterWithGate(
                        refusingGate(),
                        processExecutor,
                        cooldownGate = cooldownGate,
                        costMeter = costMeter,
                        parentStatus = ProcessStatus.WORKING,
                    ).start(target, chain, "agent", parentProcessId = 42, serverDerivedParent = true) { emptyMap() }

                // The admitted start skips the gate, not the request-boundary refusals.
                assertFailsWith<InvocationCycleDetectedException> { start(listOf("child")) }
                assertFailsWith<InvocationDepthExceededException> { start(listOf("a", "b", "c", "d", "e")) }

                coEvery { costMeter.checkCaps(any()) } throws CostCapExceededException("child", 1.0, 2.0)
                assertFailsWith<CostCapExceededException> { start(emptyList(), AgentFixtures.claudeAgent(name = "child")) }

                coEvery { cooldownGate.check(any()) } throws CooldownActiveException("child", 5, "cooling down")
                assertFailsWith<CooldownActiveException> { start(emptyList()) }

                coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
            }
        }

    // --- the shutdown drain (ADR-0102) ---

    @Test
    fun `a server-derived start whose parent is still WORKING runs during the drain`() =
        runBlocking {
            // A `!<agent>` hop, a dispatch or a continuation fire made by a run that is still
            // WORKING is part of the drain's own work: refusing it would leave that run unable
            // to finish, which is exactly what the drain is waiting for.
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { processExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val gate = drainingGate()
            val starter = starterWithGate(gate, processExecutor, parentStatus = ProcessStatus.WORKING)

            val processId =
                starter.start(
                    AgentFixtures.simpleAgent(name = "child"),
                    emptyList(),
                    "agent",
                    parentProcessId = 42,
                    serverDerivedParent = true,
                ) { emptyMap() }

            assertEquals(7, processId)
            coVerify(exactly = 0) { gate.awaitRelease(any()) }
            coVerify(exactly = 1) { processExecutor.execute(any(), any()) }
        }

    @Test
    fun `a start whose parent has ended is refused by the drain, never parked`() =
        runBlocking {
            // Shed, not queued: a start parked behind a process that is going away could only
            // end UNKNOWN at the next boot. ADR-0047 rule 5, ADR-0102.
            for (status in listOf(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.KILLED, ProcessStatus.UNKNOWN)) {
                val processExecutor = mockk<ProcessExecutor>(relaxed = true)
                val gate = drainingGate()
                val starter = starterWithGate(gate, processExecutor, parentStatus = status)

                assertFailsWith<ServerDrainingException> {
                    starter.start(
                        AgentFixtures.simpleAgent(name = "child"),
                        emptyList(),
                        "agent",
                        parentProcessId = 42,
                        serverDerivedParent = true,
                    ) { emptyMap() }
                }
                coVerify(exactly = 0) { gate.awaitRelease(any()) }
                coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
            }
        }

    @Test
    fun `a caller-supplied parent is refused by the drain without probing its row`() =
        runBlocking {
            // The flag short-circuits before the read, so naming a parent admits no start and
            // probes no row the caller cannot see (root §12 rule 3).
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            val gate = drainingGate()
            val processReads =
                mockk<ProcessRecordReads> {
                    coEvery { getProcessStatus(any()) } returns ProcessStatus.WORKING
                }
            val starter = starterWithGate(gate, processExecutor, processReads = processReads)

            assertFailsWith<ServerDrainingException> {
                starter.start(
                    AgentFixtures.simpleAgent(name = "child"),
                    emptyList(),
                    "http",
                    parentProcessId = 42,
                ) { emptyMap() }
            }

            coVerify(exactly = 0) { processReads.getProcessStatus(any()) }
            coVerify(exactly = 0) { gate.awaitRelease(any()) }
            coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
        }

    @Test
    fun `a parentless start is refused by the drain`() =
        runBlocking {
            val processExecutor = mockk<ProcessExecutor>(relaxed = true)
            val gate = drainingGate()
            val starter = starterWithGate(gate, processExecutor, parentStatus = ProcessStatus.WORKING)

            assertFailsWith<ServerDrainingException> {
                starter.start(AgentFixtures.simpleAgent(name = "child"), emptyList(), "http") { emptyMap() }
            }

            coVerify(exactly = 0) { gate.awaitRelease(any()) }
            coVerify(exactly = 0) { processExecutor.execute(any(), any()) }
        }
}
