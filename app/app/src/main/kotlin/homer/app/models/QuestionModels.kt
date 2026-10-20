package homer.app.models

import kotlinx.serialization.Serializable

/** Body of `POST /api/v1/callback/questions`. */
@Serializable
data class AskQuestionRequest(
    val text: String,
    val options: List<String> = emptyList(),
)

/** Response of `POST /api/v1/callback/questions` — the question id to wait on. */
@Serializable
data class AskQuestionResponse(
    val id: String,
)

/** Body of `POST /api/v1/questions/{id}/answer`. */
@Serializable
data class AnswerQuestionRequest(
    val answer: String,
)

@Serializable
data class QuestionResponse(
    val id: String,
    val processId: Int,
    val agentName: String,
    val text: String,
    val options: List<String> = emptyList(),
    val status: String,
    val answer: String? = null,
    val createdAt: Long,
    val answeredAt: Long? = null,
    val dispatch: DispatchSummary? = null,
)

@Serializable
data class QuestionsListResponse(
    val questions: List<QuestionResponse>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)
