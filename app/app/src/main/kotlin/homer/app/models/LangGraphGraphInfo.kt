package homer.app.models

import kotlinx.serialization.Serializable

/**
 * One LangGraph command's workflow graph, as returned by
 * `GET /api/v1/agents/{name}/langgraph`. Exactly one of [topology] / [error] is
 * non-null. [mermaid] is deprecated (ADR-0073) and may accompany [topology].
 * Response-only DTO — not part of the Agent/Command contract, so it is not in
 * the agent JSON schema.
 */
@Serializable
data class LangGraphGraphInfo(
    val label: String,
    val module: String,
    val topology: LangGraphTopology? = null,
    val mermaid: String? = null,
    val error: String? = null,
)
