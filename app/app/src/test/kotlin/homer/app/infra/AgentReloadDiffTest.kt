package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.models.AgentReloadResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentReloadDiffTest {
    private val a = AgentFixtures.simpleAgent(name = "a")
    private val b = AgentFixtures.simpleAgent(name = "b")
    private val c = AgentFixtures.simpleAgent(name = "c")

    @Test
    fun `a scan is merged onto the state and diffed against it`() {
        val state = AgentCatalogState(mapOf("a" to a, "b" to b), mapOf("a" to "a", "b" to "b"))
        val changedA = a.copy(description = "changed")
        val scan = AgentScan(mapOf("a" to changedA, "c" to c), mapOf("a" to "a", "c" to "c"), setOf("a", "c"), listOf("e"))

        val computation = AgentReloadDiff.compute(scan, state)

        assertEquals(mapOf("a" to changedA, "c" to c), computation.newState.agents)
        assertEquals(mapOf("a" to "a", "c" to "c"), computation.newState.sourceDirs)
        assertEquals(AgentReloadResult(listOf("c"), listOf("a"), listOf("b"), listOf("e")), computation.result)
    }

    @Test
    fun `an agent whose directory is still present but failed to parse is kept`() {
        val state = AgentCatalogState(mapOf("b" to b), mapOf("b" to "b"))
        val scan = AgentScan(emptyMap(), emptyMap(), setOf("b"), listOf("b broke"))

        assertEquals(emptySet(), AgentReloadDiff.removedAgentNames(state.agents, state.sourceDirs, scan))
        assertEquals(mapOf("b" to b), AgentReloadDiff.compute(scan, state).newState.agents)
    }
}
