package homer.app.routes

import homer.app.models.SetMaintenanceRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * `GET`/`POST /api/v1/maintenance`: the admin-only maintenance-mode toggle and its state.
 *
 * The gate is handler-level and fail-closed, and a denial is the same `404` an unknown
 * resource answers — never a `403`, which would confirm the endpoint exists
 * (ADR-0037, ADR-0047 rule 3, ADR-0092). The body is parsed *after* the gate, so a
 * non-admin caller learns nothing about the request shape either.
 */
internal fun Route.maintenanceRoutes(deps: ApiDeps) {
    with(deps) {
        get("/maintenance") {
            if (!authorizer.isAdmin(call.principalName())) return@get call.respondNotFound()
            call.respondNoStore(maintenanceModeService.snapshot())
        }

        post("/maintenance") {
            if (!authorizer.isAdmin(call.principalName())) return@post call.respondNotFound()
            val request = call.receiveOr400<SetMaintenanceRequest>("Invalid JSON body: expected { enabled }")
            maintenanceModeService.setEnabled(request.enabled)
            call.respondNoStore(maintenanceModeService.snapshot())
        }
    }
}

/**
 * The denial: the exact `404` a path that was never mounted answers, body included — an
 * `ErrorResponse` here would let a grant-probing caller tell a denied handler from a
 * missing route, which is the existence the denial exists to hide.
 */
private suspend fun ApplicationCall.respondNotFound() = respond(HttpStatusCode.NotFound)

/**
 * The mode's state is live operator state: a cached copy would let a proxy or a browser
 * serve a stale "off" after the mode was turned on.
 */
private suspend fun ApplicationCall.respondNoStore(body: Any) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(body)
}
