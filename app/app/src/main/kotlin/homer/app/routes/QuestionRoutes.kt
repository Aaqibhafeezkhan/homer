package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import homer.app.models.AnswerQuestionRequest
import homer.app.models.ErrorResponse
import homer.app.models.QuestionsListResponse
import homer.app.services.QuestionsService
import homer.app.toResponse
import homer.app.toSummary
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("homer.app.routes.QuestionRoutes")

/** Operator questions: `GET /questions` and `POST /questions/{id}/answer`. */
internal fun Route.questionRoutes(deps: ApiDeps) {
    // Operator view of agent questions (ADR-0019). Answering a
    // non-OPEN question or an unknown id maps to 409/404 via the
    // QuestionNotOpen/QuestionNotFound exceptions in StatusPages.
    questionListRoute(deps)
    questionAnswerRoute(deps)
}

/** `GET /questions`: the questions asked by the processes the caller can see. */
private fun Route.questionListRoute(deps: ApiDeps) {
    with(deps) {
        get("/questions") {
            val statusParam = call.request.queryParameters["status"]
            val status =
                statusParam?.let { raw ->
                    QuestionsService.QuestionStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        ?: throw QueryParamValidationException(
                            "Invalid status parameter. Must be one of: " +
                                QuestionsService.QuestionStatus.entries.joinToString(", ") { it.name },
                            paramName = "status",
                        )
                }
            val processIdParam = call.request.queryParameters["processId"]
            val processId =
                processIdParam?.let {
                    it.toIntOrNull()
                        ?: throw QueryParamValidationException("Invalid processId parameter", paramName = "processId")
                }
            val (limit, offset) = call.recordPageParams()
            val principal = call.principalName()
            // Visibility runs in SQL before the limit — the asking process's owner, or a read grant
            // on the question's agent — so the page and `total` hold only questions this caller can
            // see (§12, ADR-0039).
            val page =
                questionsService.page(
                    status = status,
                    processId = processId,
                    visibleOwner = principal,
                    visibleAgents = visibleAgentNames(authorizer, agentsService, principal),
                    offset = offset,
                    limit = limit,
                )
            val dispatches = dispatchService.findByQuestionIds(page.items.map { it.id })
            call.respond(
                QuestionsListResponse(
                    questions = page.items.map { it.toResponse(dispatches[it.id]?.toSummary()) },
                    total = page.total,
                    offset = offset,
                    limit = limit,
                ),
            )
        }
    }
}

/** `POST /questions/{id}/answer`: answering a question of a process the caller can control, firing its dispatch. */
private fun Route.questionAnswerRoute(deps: ApiDeps) {
    with(deps) {
        post("/questions/{id}/answer") {
            val id = call.pathParameter("id")
            val request = call.receiveOr400<AnswerQuestionRequest>("Invalid JSON body: expected { answer }")

            val question = questionsService.get(id)
            if (question == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Question not found"))
                return@post
            }
            val askingInfo = processManager.getProcessInfo(question.processId)
            val askingOwner = askingInfo?.owner
            // Dispatch-bound questions can outlive their asking process, which
            // may be purged -- fall back to the question's own agentName.
            val askingAgent = askingInfo?.agentName ?: question.agentName
            if (!authorizer.canControl(call.principalName(), askingOwner, askingAgent)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Question not found"))
                return@post
            }

            val answered = questionsService.answer(id, request.answer)
            val dispatch =
                try {
                    dispatchService.fire(id, request.answer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Dispatch fire failed for answered question {}: {}", id, e.message)
                    null
                }
            call.respond(answered.toResponse(dispatch?.toSummary()))
        }
    }
}
