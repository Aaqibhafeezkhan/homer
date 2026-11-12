package homer.app.services

import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.MaxLockWaitersExceededException
import homer.app.exceptions.ServerDrainingException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentContinuationConfig
import homer.app.models.AgentDispatchConfig
import homer.app.models.Command
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.Transformer
import homer.app.services.persistence.ContinuationRepository
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContinuationFireServiceTest {
    private val repo = mockk<ContinuationRepository>(relaxed = true)
    private val agents = mockk<AgentsService>()
    private val runStarter = mockk<AgentRunStarter>()
    private val inputResolver = mockk<AgentInputResolver>(relaxed = true)
    private val processManager = mockk<ProcessManager>(relaxed = true)
    private val authorizer =
        mockk<Authorizer>().also {
            every { it.canDispatchHop(any(), any(), any()) } returns true
            every { it.canSee(any(), any(), any()) } returns true
        }
    private val agent =
        mockk<Agent>(relaxed = true).also { every { it.dispatch } returns AgentDispatchConfig(enabled = true) }

    /** The secret-key set the fire hands the starter; see the ADR-0052 inheritance cases. */
    private val secretKeys = slot<Set<String>>()

    private val fires =
        ContinuationFireService(
            repo,
            agents,
            authorizer,
            runStarter,
            inputResolver,
            processManager,
            mockk(relaxed = true),
            LoggerFactory.getLogger("t"),
        ) { 1000L }

    private fun pending(
        kind: ContinuationKind = ContinuationKind.SINGLE,
        watches: List<JoinWatch> = emptyList(),
    ) = Continuation(
        id = 5,
        watchedProcessId = if (kind == ContinuationKind.SINGLE) 42 else 0,
        status = ContinuationStatus.PENDING,
        targetAgentName = "h",
        params = mapOf("HMR_LG_THREAD" to "thread-1"),
        originProcessId = 7,
        originAgentName = "asker",
        expiresAt = null,
        createdAt = 1,
        kind = kind,
        watches = watches,
    )

    private fun resolvedParams(target: Agent = agent): CapturingSlot<Map<String, String>> {
        every { agents.agents } returns mapOf("h" to target)
        coEvery { repo.claim(5) } returns true
        val resolve = slot<suspend () -> Map<String, String>>()
        coEvery {
            runStarter.start(
                any(),
                any(),
                eq("continuation"),
                any(),
                any(),
                any(),
                capture(secretKeys),
                any(),
                any(),
                // The origin link is server-derived: a fire whose origin is still WORKING is
                // admitted by the maintenance gate. See ADR-0092.
                serverDerivedParent = true,
                capture(resolve),
            )
        } coAnswers
            {
                resolve.captured.invoke()
                99
            }
        val params = slot<Map<String, String>>()
        every { inputResolver.resolveFromParams(capture(params), any(), any(), any()) } returns emptyMap()
        return params
    }

    /** A target agent that opts into inheriting its own runs' inputs, as the e2e fixture does. */
    private fun inheritingAgent(): Agent =
        mockk<Agent>(relaxed = true).also {
            every { it.name } returns "h"
            every { it.dispatch } returns AgentDispatchConfig(enabled = true)
            every { it.continuation } returns AgentContinuationConfig(inheritInputs = true)
            every { it.effectiveInputs } returns listOf(ticketInput())
        }

    /** The same target with the flag off: it must fire exactly as it did before the flag existed. */
    private fun plainAgent(): Agent =
        mockk<Agent>(relaxed = true).also {
            every { it.name } returns "h"
            every { it.dispatch } returns AgentDispatchConfig(enabled = true)
            every { it.continuation } returns null
            every { it.effectiveInputs } returns listOf(ticketInput())
        }

    private fun ticketInput() = Param(paramName = "ticket", envName = "TICKET_ID", src = ParamSource.QUERY, type = ParamType.ANY)

    /** The origin run's stored inputs, as the continuation's `originProcessId` row holds them. */
    private fun originInfo(inputs: Map<String, String> = mapOf("TICKET_ID" to "123")) =
        ProcessInfo(
            id = 7,
            status = ProcessStatus.FINISHED,
            agentName = "h",
            inputParams = inputs,
            secretInputKeys = setOf("SECRET_TOKEN"),
        )

    @Test
    fun `fire starts the target with the watched status as resume payload and marks it dispatched`() =
        runTest {
            val params = resolvedParams()

            fires.fire(pending(), ProcessStatus.FAILED)

            coVerify { repo.markDispatched(5, 99, 1000L) }
            val payload = Json.parseToJsonElement(params.captured.getValue("HMR_LG_RESUME")).jsonObject
            assertEquals("FAILED", payload.getValue("status").jsonPrimitive.content)
            assertEquals("42", payload.getValue("processId").jsonPrimitive.content)
            assertEquals("thread-1", params.captured["HMR_LG_THREAD"])
        }

    // Which run a fire starts: the continuation's target agent, the stored params plus the
    // resume payload under its resumeParam, and the registration-time secret snapshot. ADR-0052.
    @Test
    fun `fire starts the continuation's target with its params and registration secret snapshot`() =
        runTest {
            val target = plainAgent()
            val params = resolvedParams(target)

            fires.fire(pending().copy(secretInputKeys = setOf("API_TOKEN")), ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    eq(target),
                    eq(emptyList()),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    eq(setOf("API_TOKEN")),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
            coVerify { repo.markDispatched(5, 99, 1000L) }
            assertEquals("thread-1", params.captured["HMR_LG_THREAD"])
            val resume = Json.parseToJsonElement(params.captured.getValue("HMR_LG_RESUME")).jsonObject
            assertEquals("FINISHED", resume.getValue("status").jsonPrimitive.content)
        }

    // A row predating the secret columns carries no snapshot: the fire hands over exactly the
    // keys it inherited, never a null for the starter to trip on.
    @Test
    fun `a null registration secret snapshot hands over exactly the inherited keys`() =
        runTest {
            resolvedParams(inheritingAgent())
            coEvery { processManager.getProcessInfo(7) } returns originInfo()

            fires.fire(pending().copy(originAgentName = "h"), ProcessStatus.FINISHED)

            coVerify { repo.markDispatched(5, 99, 1000L) }
            assertEquals(setOf("SECRET_TOKEN"), secretKeys.captured)
        }

    @Test
    fun `a fire's parent link is the origin run, with the flag the maintenance gate reads`() =
        runTest {
            resolvedParams()

            fires.fire(pending(), ProcessStatus.FINISHED)

            // The bypass is decided on the origin's own row, never on the watched process the
            // continuation stores: the fire carries the origin id (7) as its parent, with the
            // server-derived flag set, so an origin that is no longer `WORKING` parks as before.
            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = 7,
                    rootProcessId = any(),
                    serverDerivedParent = true,
                    resolveInputs = any(),
                )
            }
        }

    @Test
    fun `a lost claim starts nothing`() =
        runTest {
            coEvery { repo.claim(5) } returns false

            fires.fire(pending(), ProcessStatus.FINISHED)

            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a target that is gone is marked failed without starting`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.claim(5) } returns true

            fires.fire(pending(), ProcessStatus.FINISHED)

            coVerify { repo.markFailed(5, "Target agent 'h' not found", 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a transient start failure is released for a retry`() =
        runTest {
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CostCapExceededException("global", 1.0, 2.0)

            fires.fire(pending(), ProcessStatus.FINISHED)

            coVerify { repo.releaseForRetry(5, any(), 1, 1060L) }
        }

    @Test
    fun `a target whose lock-wait queue is full is released for a retry`() =
        runTest {
            // A full lock-wait queue reflects the target's momentary state, like the slot queue: the
            // resume must not be consumed by it. See ADR-0104, ADR-0023.
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                MaxLockWaitersExceededException("h", 5)

            fires.fire(pending(), ProcessStatus.FINISHED)

            coVerify { repo.releaseForRetry(5, any(), 1, 1060L) }
            coVerify(exactly = 0) { repo.markFailed(5, any(), any()) }
        }

    @Test
    fun `a fire the drain refused is released for a retry, not settled failed`() =
        runTest {
            // The origin run ended and its resume is the only thing the target will ever see:
            // the next boot's sweep must fire it, so the row goes back to PENDING with the
            // attempt counted rather than to FAILED. See ADR-0023, ADR-0102.
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                ServerDrainingException()

            fires.fire(pending(), ProcessStatus.FINISHED)

            coVerify { repo.releaseForRetry(5, any(), 1, 1060L) }
            coVerify(exactly = 0) { repo.markFailed(5, any(), any()) }
        }

    @Test
    fun `an inheriting continuation hands the origin run's stored inputs to the target`() =
        runTest {
            val params = resolvedParams(inheritingAgent())
            coEvery { processManager.getProcessInfo(7) } returns originInfo()

            fires.fire(pending().copy(originAgentName = "h"), ProcessStatus.FINISHED)

            // The origin's `TICKET_ID` is what renders the segment's lock key and its
            // fromInputs tag; the continuation's own control param is untouched. ADR-0101.
            assertEquals("123", params.captured["ticket"])
            assertEquals("thread-1", params.captured["HMR_LG_THREAD"])
        }

    @Test
    fun `a join continuation inherits the origin run's inputs too`() =
        runTest {
            val params = resolvedParams(inheritingAgent())
            coEvery { processManager.getProcessInfo(7) } returns originInfo()
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED

            fires.fireJoin(
                pending(ContinuationKind.JOIN, listOf(JoinWatch(10, "int-a"))).copy(originAgentName = "h"),
            )

            assertEquals("123", params.captured["ticket"])
            assertEquals(setOf("SECRET_TOKEN"), secretKeys.captured)
        }

    @Test
    fun `the continuation's own param wins over an inherited value of the same name`() =
        runTest {
            val params = resolvedParams(inheritingAgent())
            coEvery { processManager.getProcessInfo(7) } returns originInfo()

            fires.fire(
                pending().copy(originAgentName = "h", params = mapOf("ticket" to "own", "HMR_LG_THREAD" to "thread-1")),
                ProcessStatus.FINISHED,
            )

            assertEquals("own", params.captured["ticket"])
        }

    @Test
    fun `the flag off and a different origin agent both inherit nothing`() =
        runTest {
            val params = resolvedParams(plainAgent())
            coEvery { processManager.getProcessInfo(7) } returns originInfo()

            fires.fire(pending().copy(originAgentName = "h"), ProcessStatus.FINISHED)
            assertNull(params.captured["ticket"], "the flag off must not inherit")

            every { agents.agents } returns mapOf("h" to inheritingAgent())
            fires.fire(pending().copy(originAgentName = "other"), ProcessStatus.FINISHED)
            assertNull(params.captured["ticket"], "another origin agent must not inherit")
        }

    @Test
    fun `an inherited secret input rides the target's secret snapshot`() =
        runTest {
            resolvedParams(inheritingAgent())
            coEvery { processManager.getProcessInfo(7) } returns originInfo()

            // The union is what the fired run must honour: the continuation's own
            // registration snapshot and the origin run's, either alone would drop the
            // other half's masking. ADR-0052, ADR-0101.
            fires.fire(
                pending().copy(originAgentName = "h", secretInputKeys = setOf("OWN_SECRET")),
                ProcessStatus.FINISHED,
            )

            assertEquals(setOf("OWN_SECRET", "SECRET_TOKEN"), secretKeys.captured)
        }

    @Test
    fun `an inherited value the target rejects fails the continuation naming the param, never the value`() =
        runTest {
            // A real resolver, so the merged map really goes through ParamValidator: the
            // origin run accepted `TICKET_ID` as ANY, the target now declares it integer.
            val strict =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("true"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(paramName = "ticket", envName = "TICKET_ID", src = ParamSource.QUERY, type = ParamType.INT),
                        ),
                    dispatch = AgentDispatchConfig(enabled = true),
                    continuation = AgentContinuationConfig(inheritInputs = true),
                )
            resolvedParams(strict)
            coEvery { processManager.getProcessInfo(7) } returns originInfo(mapOf("TICKET_ID" to "not-a-number"))
            val strictFires =
                ContinuationFireService(
                    repo,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    mockk(relaxed = true),
                    LoggerFactory.getLogger("t"),
                ) { 1000L }

            strictFires.fire(pending().copy(originAgentName = "h"), ProcessStatus.FINISHED)

            // The reason names the paramName and never the value, and no run was started.
            coVerify { repo.markFailed(5, match { it.contains("'ticket'") && !it.contains("not-a-number") }, 1000L) }
            coVerify(exactly = 0) { repo.markDispatched(5, any(), any()) }
        }

    @Test
    fun `an inherited stored value is validated without running its transformer a second time`() =
        runTest {
            // The origin run stored SUMMARY already post-transformer — its JsonPath
            // transformer extracted `$.summary` when it resolved its inputs. Running the
            // transformer over the extracted string again would reject the param and fail
            // every fire that inherits it. ADR-0101.
            val graph =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("true"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "summary",
                                envName = "SUMMARY",
                                src = ParamSource.QUERY,
                                type = ParamType.ANY,
                                transformer = Transformer.JsonPath("$.summary"),
                            ),
                        ),
                    dispatch = AgentDispatchConfig(enabled = true),
                    continuation = AgentContinuationConfig(inheritInputs = true),
                )
            resolvedParams(graph)
            coEvery { processManager.getProcessInfo(7) } returns originInfo(mapOf("SUMMARY" to "the subject"))
            val realFires =
                ContinuationFireService(
                    repo,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    mockk(relaxed = true),
                    LoggerFactory.getLogger("t"),
                ) { 1000L }

            realFires.fire(pending().copy(originAgentName = "h"), ProcessStatus.FINISHED)

            coVerify { repo.markDispatched(5, 99, 1000L) }
        }

    @Test
    fun `the continuation's own param of an inherited name is transformed as caller-supplied input`() =
        runTest {
            // The inherited names whose value survives the merge exclude the continuation's
            // own params: an own param named like an inherited one wins, and its raw JSON
            // is transformed as caller-supplied input — never passed through as a stored,
            // already-transformed value. Real resolver, so the transformer really runs.
            // ADR-0101.
            val graph =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("true"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "summary",
                                envName = "SUMMARY",
                                src = ParamSource.QUERY,
                                type = ParamType.ANY,
                                transformer = Transformer.JsonPath("$.summary"),
                            ),
                        ),
                    dispatch = AgentDispatchConfig(enabled = true),
                    continuation = AgentContinuationConfig(inheritInputs = true),
                )
            every { agents.agents } returns mapOf("h" to graph)
            coEvery { repo.claim(5) } returns true
            coEvery { processManager.getProcessInfo(7) } returns originInfo(mapOf("SUMMARY" to "the subject"))
            val realFires =
                ContinuationFireService(
                    repo,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    mockk(relaxed = true),
                    LoggerFactory.getLogger("t"),
                ) { 1000L }
            val resolve = slot<suspend () -> Map<String, String>>()
            var resolved: Map<String, String>? = null
            coEvery {
                runStarter.start(
                    any(),
                    any(),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    serverDerivedParent = true,
                    capture(resolve),
                )
            } coAnswers
                {
                    resolved = resolve.captured.invoke()
                    99
                }

            realFires.fire(
                pending().copy(
                    originAgentName = "h",
                    params = mapOf("summary" to "{\"summary\": \"own raw\"}"),
                ),
                ProcessStatus.FINISHED,
            )

            assertEquals("own raw", resolved?.get("SUMMARY"))
            coVerify { repo.markDispatched(5, 99, 1000L) }
        }

    @Test
    fun `fireJoin resumes with one entry per watched sub, keyed by interrupt id`() =
        runTest {
            val params = resolvedParams()
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            coEvery { processManager.getProcessStatus(11) } returns ProcessStatus.KILLED

            fires.fireJoin(pending(ContinuationKind.JOIN, listOf(JoinWatch(10, "int-a"), JoinWatch(11, "int-b"))))

            val outer = Json.parseToJsonElement(params.captured.getValue("HMR_LG_RESUME")).jsonObject
            assertEquals(setOf("int-a", "int-b"), outer.keys)
            val subB = Json.parseToJsonElement(outer.getValue("int-b").jsonPrimitive.content).jsonObject
            assertEquals("KILLED", subB.getValue("status").jsonPrimitive.content)
            coVerify { repo.markDispatched(5, 99, 1000L) }
        }
}
