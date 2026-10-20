package homer.app.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentReloadResult(
    val added: List<String>,
    val updated: List<String>,
    val removed: List<String>,
    val errors: List<String>,
)
