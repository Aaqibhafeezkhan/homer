package homer.app.infra

import homer.app.models.LangGraphTopology
import homer.app.models.LangGraphTopologyEdge
import homer.app.models.LangGraphTopologyNode

/**
 * Bounds a runtime-reported topology before Homer serves it: the graph comes from
 * agent code and `state` output, so it is validated where it is used. See ADR-0073.
 */
internal object LangGraphTopologyLimits {
    const val MAX_NODES = 200
    const val MAX_EDGES = 1000
    const val MAX_TEXT = 200

    /** Null when [topology] may be served, else why it may not. */
    fun violation(topology: LangGraphTopology): String? {
        if (topology.nodes.size > MAX_NODES) return "graph has ${topology.nodes.size} nodes (limit $MAX_NODES)"
        if (topology.edges.size > MAX_EDGES) return "graph has ${topology.edges.size} edges (limit $MAX_EDGES)"
        val ids = HashSet<String>(topology.nodes.size)
        return nodeViolation(topology.nodes, ids) ?: edgeViolation(topology.edges, ids)
    }

    /** Why [nodes] may not be served, else null with every node id collected into [ids]. */
    private fun nodeViolation(
        nodes: List<LangGraphTopologyNode>,
        ids: MutableSet<String>,
    ): String? {
        for (node in nodes) {
            if (node.id.isEmpty() || node.id.length > MAX_TEXT) return "graph node id is empty or longer than $MAX_TEXT characters"
            if (!ids.add(node.id)) return "graph node id '${node.id}' is duplicated"
        }
        return null
    }

    /** Why [edges] over the node [ids] may not be served, else null. */
    private fun edgeViolation(
        edges: List<LangGraphTopologyEdge>,
        ids: Set<String>,
    ): String? {
        for (edge in edges) {
            if (edge.source !in ids || edge.target !in ids) return "graph edge references an unknown node"
            if ((edge.label?.length ?: 0) > MAX_TEXT) return "graph edge label is longer than $MAX_TEXT characters"
        }
        return null
    }
}
