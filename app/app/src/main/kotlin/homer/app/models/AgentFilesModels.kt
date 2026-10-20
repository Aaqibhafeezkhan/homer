package homer.app.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AgentFileEntryResponse(
    val path: String,
    val size: Long,
    val mtime: Long,
    val isDirectory: Boolean,
)

@Serializable
data class AgentFileListResponse(
    val entries: List<AgentFileEntryResponse>,
)

@Serializable
data class AgentFileContentResponse(
    val path: String,
    val content: String,
)

@Serializable
data class AgentFileWriteRequest(
    val content: String,
)

@Serializable
data class AgentFileWriteResponse(
    val path: String,
    val ok: Boolean = true,
)

@Serializable
data class CreateAgentRequest(
    val name: String,
)

@Serializable
data class CreateAgentResponse(
    val name: String,
)

@Serializable
data class SchemaErrorResponse(
    val msg: String,
    val errors: List<String>,
)

@Serializable
data class DebugAgentRequest(
    val commandIndex: Int,
    val env: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val body: JsonObject? = null,
    val headers: Map<String, String> = emptyMap(),
)
