package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import homer.app.models.ErrorResponse
import homer.app.models.RunMessagesListResponse
import homer.app.models.SendRunMessageRequest
import homer.app.toResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Operator inbox of a run: `POST` and `GET /status/{processId}/messages`. See ADR-0097. */
internal fun Route.runMessageRoutes(deps: ApiDeps) {
    with(deps) {
        post("/status/{processId}/messages") {
            val processId = call.pathParameter("processId").toIntOrNull() ?: throw QueryParamValidationException("Invalid process ID")
            val info = processManager.getProcessInfo(processId)
            val principal = call.principalName()
            // Authorize before the body is read or the service is called (§12): a denial is the unknown-id 404.
            if (info == null || !authorizer.canControl(principal, info.owner, info.agentName)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@post
            }
            val request = call.receiveOr400<SendRunMessageRequest>("Invalid JSON body: expected { text }")
            call.respond(HttpStatusCode.Accepted, runMessagesService.send(processId, principal, request.text).toResponse())
        }

        get("/status/{processId}/messages") {
            val processId = call.pathParameter("processId").toIntOrNull() ?: throw QueryParamValidationException("Invalid process ID")
            val info = processManager.getProcessInfo(processId)
            if (info == null || !authorizer.canSee(call.principalName(), info.owner, info.agentName)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@get
            }
            call.respond(RunMessagesListResponse(runMessagesService.list(processId).map { it.toResponse() }))
        }
    }
}
