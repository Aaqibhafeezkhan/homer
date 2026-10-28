package homer.app.services

/** Lifecycle of an operator message in a run's inbox. See ADR-0097. */
enum class RunMessageStatus {
    QUEUED,
    DELIVERED,
    UNDELIVERED,
}

/** One operator message sent to a running run; [text] is operator free text and never logged. */
data class RunMessage(
    val id: String,
    val processId: Int,
    val text: String,
    val status: RunMessageStatus,
    val sender: String?,
    val createdAt: Long,
    val deliveredAt: Long?,
)
