package homer.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class PackageGraphTest {
    /** A graph given as `from → (to → import count)`; the imports themselves do not matter here. */
    private fun graph(vararg edges: Triple<String, String, Int>): Map<String, Map<String, List<Any>>> =
        edges.groupBy { it.first }.mapValues { (_, out) -> out.associate { (_, to, n) -> to to List(n) { it } } }

    @Test
    fun `a two-node cycle is cut at its lighter direction`() {
        val edges = graph(Triple("a", "b", 2), Triple("b", "a", 1))

        assertEquals(listOf("b" to "a"), PackageGraph.feedbackEdges(edges))
    }

    @Test
    fun `a triangle is cut once, at its lightest edge`() {
        val edges = graph(Triple("a", "b", 3), Triple("b", "c", 1), Triple("c", "a", 2))

        assertEquals(listOf("b" to "c"), PackageGraph.feedbackEdges(edges))
    }

    @Test
    fun `a kernel package's edge out of the kernel is cut before a lighter edge`() {
        val edges = graph(Triple("models", "services", 5), Triple("services", "models", 1))

        assertEquals(listOf("models" to "services"), PackageGraph.feedbackEdges(edges))
    }

    @Test
    fun `an acyclic graph has nothing to cut`() {
        assertEquals(emptyList(), PackageGraph.feedbackEdges(graph(Triple("a", "b", 1), Triple("b", "c", 1))))
    }

    @Test
    fun `cycleThrough names the shortest way back to the edge's source`() {
        val edges = graph(Triple("a", "b", 3), Triple("b", "c", 1), Triple("c", "a", 2), Triple("c", "b", 1))

        assertEquals(listOf("b", "c", "b"), PackageGraph.cycleThrough(edges, "b", "c"))
        assertEquals(listOf("a", "b", "c", "a"), PackageGraph.cycleThrough(edges, "a", "b"))
    }

    @Test
    fun `cycleThrough with no way back is just the edge`() {
        assertEquals(listOf("a", "b"), PackageGraph.cycleThrough(graph(Triple("a", "b", 1)), "a", "b"))
    }
}
