package homer.app.models

import kotlinx.serialization.Serializable

@Serializable
data class ContinuationResponse(
    val id: Int,
    val watchedProcessId: Int,
    val status: String,
    val agentName: String,
    val resumeParam: String,
    val params: Map<String, String>,
    val originProcessId: Int,
    val originAgentName: String,
    val expiresAt: Long? = null,
    val createdAt: Long,
    val firedAt: Long? = null,
    val firedProcessId: Int? = null,
    val error: String? = null,
)

@Serializable
data class ContinuationListResponse(
    val continuations: List<ContinuationResponse>,
    val total: Int, // Visible rows matching the filters, before pagination
    val offset: Int,
    val limit: Int,
)

/** Compact continuation info (console annotation on a watched process). */
@Serializable
data class ContinuationSummary(
    val id: Int,
    val agentName: String,
    val status: String,
    val firedProcessId: Int? = null,
    val error: String? = null,
)
