package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentSubRunStarterTest {
    private val target = AgentFixtures.simpleAgent(name = "target")
    private val agentsService = mockk<AgentsService> { every { agents } returns mapOf("target" to target) }
    private val authorizer = mockk<Authorizer>()
    private val processManager = mockk<ProcessManager>()
    private val agentRunStarter = mockk<AgentRunStarter>()
    private var providerCalls = 0
    private val subRuns =
        AgentSubRunStarter(
            agentRunStarter = {
                providerCalls++
                agentRunStarter
            },
            agentsService = agentsService,
            authorizer = authorizer,
            processManager = processManager,
        )

    @Test
    fun `resolves the target among the loaded agents`() {
        assertSame(target, subRuns.agent("target"))
        assertNull(subRuns.agent("missing"))
    }

    @Test
    fun `a hop the authorizer denies is denied`() {
        every { authorizer.canDispatchHop("alice", "caller", "target") } returns false

        assertFalse(subRuns.canDispatchHop("alice", "caller", "target"))
    }

    @Test
    fun `a flow root the principal cannot see is dropped`() =
        runTest {
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(id = 7, status = ProcessStatus.WORKING, agentName = "caller", rootProcessId = 3)
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "root", owner = "bob")
            every { authorizer.canSee("alice", "bob", "root") } returns false

            assertNull(subRuns.visibleOriginRootProcessId(7, "alice"))
        }

    @Test
    fun `starts through the run starter resolved at call time`() =
        runTest {
            coEvery {
                agentRunStarter.start(target, listOf("caller"), "agent", any(), listOf("lock"), "alice", any(), 7, 3, any())
            } returns 42
            assertEquals(0, providerCalls)

            val processId = subRuns.start(target, listOf("caller"), "agent", listOf("lock"), "alice", 7, 3) { emptyMap() }

            assertEquals(42, processId)
            assertEquals(1, providerCalls)
            coVerify(exactly = 1) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
}
