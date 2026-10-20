package homer.app.routes

import homer.app.models.AgentNameMatcher
import homer.app.models.ErrorResponse
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/** The bearer/HMAC/session principal name whichever auth provider authenticated this call; null in unsecured mode. */
internal fun ApplicationCall.principalName(): String? = principal<UserIdPrincipal>()?.name

/**
 * The admin gate, called explicitly at the top of each admin-only handler: answers 403
 * and returns true when the caller is not an admin.
 */
internal suspend fun ApplicationCall.deniedUnlessAdmin(authorizer: Authorizer): Boolean {
    if (authorizer.isAdmin(principalName())) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Admin role required"))
    return true
}

/**
 * The secured /api/v1 routes (everything mounted under the bearer/HMAC
 * authenticate wrapper). Extracted from Application.kt's configureServer
 * for separation of concerns; behavior is unchanged.
 *
 * The endpoints live in per-resource files under routes/; this mounts them in registration order.
 */
internal fun Route.apiV1Routes(deps: ApiDeps) {
    with(deps) {
        route("/api/v1") {
            serverRoutes(deps)
            agentListRoutes(deps)
            processRoutes(deps)
            agentRoutes(deps)
            questionRoutes(deps)
            dispatchRoutes(deps)
            continuationRoutes(deps)

            streamingRoute(processEventBroadcaster, logTailService, authorizer, processManager, llmEventStreamService)

            runRoutes(deps)
        }
    }
}

/**
 * The loaded agents [principal] holds READ on, for the SQL visibility filter of a list read; null
 * for an admin (no filter). Paired with an `owner = principal` clause this is the union rule of
 * [Authorizer.canSee], narrowed to loaded agents; shared by `GET /status/all` and the dispatch and
 * continuation lists (ADR-0037).
 */
internal fun visibleAgentNames(
    authorizer: Authorizer,
    agentsService: AgentsService,
    principal: String?,
): Set<String>? =
    authorizer.readableAgents(principal)?.let { patterns ->
        agentsService.agents.keys
            .filter { name -> patterns.any { AgentNameMatcher.matches(it, name) } }
            .toSet()
    }
