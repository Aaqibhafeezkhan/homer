package homer.app.models

import kotlinx.serialization.Serializable

/**
 * A LangGraph command's workflow graph as nodes and edges, emitted by
 * `homer_langgraph inspect --json` / `state` and drawn by the console.
 * Response-only DTO, bounded by `LangGraphTopologyLimits`. See ADR-0073.
 */
@Serializable
data class LangGraphTopology(
    val nodes: List<LangGraphTopologyNode>,
    val edges: List<LangGraphTopologyEdge>,
)

@Serializable
data class LangGraphTopologyNode(
    val id: String,
)

@Serializable
data class LangGraphTopologyEdge(
    val source: String,
    val target: String,
    val label: String? = null,
    val conditional: Boolean = false,
)

/**
 * One edge of a run's traversed path: a `(source, target)` pair the run's
 * checkpoint history recorded as taken. See ADR-0075.
 */
@Serializable
data class LangGraphEdgeRef(
    val source: String,
    val target: String,
)
