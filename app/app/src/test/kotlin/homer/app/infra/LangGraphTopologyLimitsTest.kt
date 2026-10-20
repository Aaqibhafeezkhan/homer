package homer.app.infra

import homer.app.models.LangGraphTopology
import homer.app.models.LangGraphTopologyEdge
import homer.app.models.LangGraphTopologyNode
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LangGraphTopologyLimitsTest {
    private fun nodes(vararg ids: String) = ids.map { LangGraphTopologyNode(it) }

    private fun topology(
        nodes: List<LangGraphTopologyNode>,
        edges: List<LangGraphTopologyEdge> = emptyList(),
    ) = LangGraphTopology(nodes, edges)

    @Test
    fun `accepts a normal graph`() {
        val t =
            topology(
                nodes("__start__", "a", "__end__"),
                listOf(LangGraphTopologyEdge("__start__", "a"), LangGraphTopologyEdge("a", "__end__", "done", true)),
            )
        assertNull(LangGraphTopologyLimits.violation(t))
    }

    @Test
    fun `accepts exactly the node limit`() {
        assertNull(
            LangGraphTopologyLimits.violation(topology((1..LangGraphTopologyLimits.MAX_NODES).map { LangGraphTopologyNode("n$it") })),
        )
    }

    @Test
    fun `rejects one node over the limit`() {
        assertNotNull(
            LangGraphTopologyLimits.violation(topology((0..LangGraphTopologyLimits.MAX_NODES).map { LangGraphTopologyNode("n$it") })),
        )
    }

    @Test
    fun `rejects too many edges`() {
        val edges = (0..LangGraphTopologyLimits.MAX_EDGES).map { LangGraphTopologyEdge("a", "a") }
        assertNotNull(LangGraphTopologyLimits.violation(topology(nodes("a"), edges)))
    }

    @Test
    fun `rejects an empty or overlong node id`() {
        assertNotNull(LangGraphTopologyLimits.violation(topology(nodes(""))))
        assertNotNull(LangGraphTopologyLimits.violation(topology(nodes("x".repeat(LangGraphTopologyLimits.MAX_TEXT + 1)))))
    }

    @Test
    fun `rejects duplicate node ids`() {
        assertNotNull(LangGraphTopologyLimits.violation(topology(nodes("a", "a"))))
    }

    @Test
    fun `rejects an edge to an unknown node`() {
        assertNotNull(LangGraphTopologyLimits.violation(topology(nodes("a"), listOf(LangGraphTopologyEdge("a", "ghost")))))
    }

    @Test
    fun `rejects an overlong edge label`() {
        val edge = LangGraphTopologyEdge("a", "a", "x".repeat(LangGraphTopologyLimits.MAX_TEXT + 1))
        assertNotNull(LangGraphTopologyLimits.violation(topology(nodes("a"), listOf(edge))))
    }
}
