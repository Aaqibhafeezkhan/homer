package homer.app.services

import homer.app.exceptions.CostCapExceededException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentDispatchConfig
import homer.app.models.ProcessStatus
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

    private fun resolvedParams(): CapturingSlot<Map<String, String>> {
        every { agents.agents } returns mapOf("h" to agent)
        coEvery { repo.claim(5) } returns true
        val resolve = slot<suspend () -> Map<String, String>>()
        coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), capture(resolve)) } coAnswers
            {
                resolve.captured.invoke()
                99
            }
        val params = slot<Map<String, String>>()
        every { inputResolver.resolveFromParams(capture(params), any(), any()) } returns emptyMap()
        return params
    }

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

    @Test
    fun `a lost claim starts nothing`() =
        runTest {
            coEvery { repo.claim(5) } returns false

            fires.fire(pending(), ProcessStatus.FINISHED)

            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a target that is gone is marked failed without starting`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.claim(5) } returns true

            fires.fire(pending(), ProcessStatus.FINISHED)

            coVerify { repo.markFailed(5, "Target agent 'h' not found", 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a transient start failure is released for a retry`() =
        runTest {
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CostCapExceededException("global", 1.0, 2.0)

            fires.fire(pending(), ProcessStatus.FINISHED)

            coVerify { repo.releaseForRetry(5, any(), 1, 1060L) }
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
