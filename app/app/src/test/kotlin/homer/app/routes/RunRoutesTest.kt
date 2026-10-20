package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import homer.app.fixtures.AgentFixtures
import homer.app.models.Command
import homer.app.models.LangGraphRunIdentity
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.LangGraphRunStatusService
import homer.app.services.ProcessManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [langGraphThreadToResume], the LangGraph preconditions of `POST /api/v1/processes/{id}/resume`. */
class RunRoutesTest {
    private val source = ProcessInfo(id = 7, status = ProcessStatus.FAILED, agentName = "graph")
    private val langGraphAgent =
        AgentFixtures.simpleAgent(name = "graph").copy(commands = listOf(Command.LangGraph(module = "my_graphs.flow")))
    private val service = mockk<LangGraphRunStatusService>()

    @Test
    fun `the thread of the run's LangGraph identity is resumed`() {
        every { service.identity(source) } returns LangGraphRunIdentity(threadId = "thread-1", module = "my_graphs.flow")

        assertEquals("thread-1", langGraphThreadToResume(service, 7, source, langGraphAgent))
    }

    @Test
    fun `an agent without a LangGraph command is a 400`() {
        val e =
            assertFailsWith<QueryParamValidationException> {
                langGraphThreadToResume(service, 7, source, AgentFixtures.simpleAgent(name = "graph"))
            }
        assertEquals("Agent 'graph' no longer defines a LangGraph command", e.message)
    }

    @Test
    fun `a run without a LangGraph thread is a 400`() {
        every { service.identity(source) } returns null

        val e = assertFailsWith<QueryParamValidationException> { langGraphThreadToResume(service, 7, source, langGraphAgent) }
        assertEquals("Process 7 has no LangGraph thread to resume", e.message)
    }
}
