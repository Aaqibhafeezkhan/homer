package homer.app.models

import kotlinx.serialization.Serializable

/** Body of `POST /api/v1/status/{processId}/messages`. */
@Serializable
data class SendRunMessageRequest(
    val text: String,
)

@Serializable
data class RunMessageResponse(
    val id: String,
    val processId: Int,
    val text: String,
    val status: String,
    val sender: String? = null,
    val createdAt: Long,
    val deliveredAt: Long? = null,
)

@Serializable
data class RunMessagesListResponse(
    val messages: List<RunMessageResponse>,
)
