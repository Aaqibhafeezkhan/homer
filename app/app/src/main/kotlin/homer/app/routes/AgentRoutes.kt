package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import homer.app.models.Action
import homer.app.models.AgentFileContentResponse
import homer.app.models.AgentFileEntryResponse
import homer.app.models.AgentFileListResponse
import homer.app.models.AgentFileWriteRequest
import homer.app.models.AgentFileWriteResponse
import homer.app.models.AgentInfo
import homer.app.models.AgentsListResponse
import homer.app.models.CreateAgentRequest
import homer.app.models.CreateAgentResponse
import homer.app.models.CronInfo
import homer.app.models.ErrorResponse
import homer.app.models.SchemaErrorResponse
import homer.app.services.AgentFilesService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** `GET /agents`: the agents the caller holds any grant on. */
internal fun Route.agentListRoutes(deps: ApiDeps) {
    with(deps) {
        get("/agents") {
            val principal = call.principalName()
            val agentsList =
                agentsService.agents
                    .filter { (name, _) -> authorizer.anyGrant(principal, name) }
                    .map { (name, agent) ->
                        val status = cronSchedulerService.getScheduleStatus(agent)
                        val cron =
                            if (agent.cron != null && status != null) {
                                CronInfo(
                                    expression = agent.cron.expression,
                                    timezone = agent.cron.timezone,
                                    nextRunAt = status.nextRunAt,
                                    lastRunAt = status.lastRunAt,
                                    lastRunProcessId = status.lastRunProcessId,
                                )
                            } else {
                                null
                            }
                        AgentInfo.from(name, agent, cron)
                    }
            call.respond(AgentsListResponse(agents = agentsList))
        }
    }
}

/** `/schemas/agent` and agent management: reload, create, agent files, LangGraph topology, debug and cron-run. */
internal fun Route.agentRoutes(deps: ApiDeps) {
    agentDefinitionRoutes(deps)
    agentFileReadRoutes(deps)
    agentFileWriteRoutes(deps)
    agentToolingRoutes(deps)
}

/** `GET /schemas/agent`, `POST /agents/reload` and `POST /agents`: the agent schema, reloading and creating agents. */
private fun Route.agentDefinitionRoutes(deps: ApiDeps) {
    with(deps) {
        get("/schemas/agent") {
            val resource = this::class.java.classLoader.getResource("schemas/homer-agent.schema.json")
            if (resource == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(msg = "Agent schema resource not bundled with this build"),
                )
                return@get
            }
            call.respondOutputStream(ContentType.Application.Json, HttpStatusCode.OK) {
                resource.openStream().use { it.copyTo(this) }
            }
        }

        post("/agents/reload") {
            if (call.deniedUnlessAdmin(authorizer)) return@post
            val result = agentsService.reload()
            val reloadedAgents = result.updated.toSet() + result.removed.toSet()
            if (reloadedAgents.isNotEmpty()) {
                processManager
                    .createdRunsOf(reloadedAgents)
                    .forEach { (id, agentName) -> processManager.killProcess(id, "Agent '$agentName' was reloaded") }
            }
            parallelRunsManager.onAgentsReloaded(result.updated.toSet(), result.removed.toSet())
            call.respond(result)
        }

        post("/agents") {
            val req = call.receiveOr400<CreateAgentRequest>("Invalid JSON body: expected { name }")
            if (!authorizer.hasAction(call.principalName(), Action.EDIT, req.name)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Not permitted to edit '${req.name}'"))
            }
            respondFileOpResult(call, agentFilesService.create(req.name), schemaInvalidMsg = "Agent definition invalid") { r ->
                call.respond(HttpStatusCode.Created, CreateAgentResponse(name = r.value))
            }
        }
    }
}

/** `GET /agents/{agentName}/files[/{path...}]`: an agent's file listing and one file's content, for EDIT holders. */
private fun Route.agentFileReadRoutes(deps: ApiDeps) {
    with(deps) {
        get("/agents/{agentName}/files") {
            val name = call.pathParameter("agentName")
            if (!authorizer.hasAction(call.principalName(), Action.EDIT, name)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Not permitted to edit '$name'"))
            }
            respondFileOpResult(call, agentFilesService.list(name), schemaInvalidMsg = "Invalid") { r ->
                call.respond(
                    AgentFileListResponse(
                        entries =
                            r.value.map {
                                AgentFileEntryResponse(
                                    path = it.path,
                                    size = it.size,
                                    mtime = it.mtime,
                                    isDirectory = it.isDirectory,
                                )
                            },
                    ),
                )
            }
        }

        get("/agents/{agentName}/files/{path...}") {
            val name = call.pathParameter("agentName")
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            if (!authorizer.hasAction(call.principalName(), Action.EDIT, name)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Not permitted to edit '$name'"))
            }
            respondFileOpResult(call, agentFilesService.read(name, path), schemaInvalidMsg = "Invalid") { r ->
                call.respond(AgentFileContentResponse(path = path, content = r.value))
            }
        }
    }
}

/** `PUT` and `DELETE /agents/{agentName}/files/{path...}`: writing and deleting an agent's files, for EDIT holders. */
private fun Route.agentFileWriteRoutes(deps: ApiDeps) {
    with(deps) {
        put("/agents/{agentName}/files/{path...}") {
            val name = call.pathParameter("agentName")
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            if (!authorizer.hasAction(call.principalName(), Action.EDIT, name)) {
                return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Not permitted to edit '$name'"))
            }
            val body = call.receiveOr400<AgentFileWriteRequest>("Invalid JSON body: expected { content }")
            respondFileOpResult(
                call,
                // A definition write is also an agent create: reload() loads the
                // declared `name:` as an agent, so it needs the create-agent grant
                // (EDIT on that name) too — CWE-285, ADR-0068.
                agentFilesService.write(name, path, body.content) { declared ->
                    authorizer.hasAction(call.principalName(), Action.EDIT, declared)
                },
                schemaInvalidMsg = "Agent definition invalid",
            ) {
                call.respond(AgentFileWriteResponse(path = path))
            }
        }

        delete("/agents/{agentName}/files/{path...}") {
            val name = call.pathParameter("agentName")
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            if (!authorizer.hasAction(call.principalName(), Action.EDIT, name)) {
                return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Not permitted to edit '$name'"))
            }
            respondFileOpResult(call, agentFilesService.delete(name, path), schemaInvalidMsg = "Invalid") {
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** `GET /agents/{agentName}/langgraph`, `POST …/debug` and `POST …/cron-run`: topology, debug runs and cron fires. */
private fun Route.agentToolingRoutes(deps: ApiDeps) {
    with(deps) {
        get("/agents/{agentName}/langgraph") {
            val name = call.pathParameter("agentName")
            val agent =
                agentsService.agents[name]
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Agent not found: $name"))
            // Any grant (not just RUN) counts for visibility; denial hides
            // existence with the same NotFound message as an unknown agent.
            if (!authorizer.anyGrant(call.principalName(), name)) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Agent not found: $name"))
            }
            call.respond(langGraphInspectService.inspect(agent))
        }

        post("/agents/{agentName}/debug") {
            val agent = agentPermitting(call, Action.DEBUG, "debug") ?: return@post
            handleAgentDebug(
                serverId = serverId,
                call = call,
                agent = agent,
                agentInputResolver = agentInputResolver,
                processExecutor = processExecutor,
                processFactory = processFactory,
                telemetryService = telemetryService,
            )
        }

        // Admin-only, handler-level gate; a denial is the same 404 as an unknown agent
        // (ADR-0037, ADR-0047 rule 3). fireAgent swallows a failed fire, so the response
        // has no body (ADR-0080).
        post("/agents/{agentName}/cron-run") {
            val agentName = call.pathParameter("agentName")
            val agent =
                agentsService.agents[agentName]
                    ?.takeIf { authorizer.isAdmin(call.principalName()) }
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Agent not found: $agentName"))
            cronSchedulerService.fireNow(agent)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Maps an [AgentFilesService.FileOpResult] to an HTTP response. The error
 * cases (NotFound/BadRequest/Forbidden/Conflict/SchemaInvalid) are identical
 * across the agent-files endpoints; [onOk] renders the per-endpoint success
 * body, and [schemaInvalidMsg] is the message used for the 422 SchemaInvalid
 * response.
 */
internal suspend fun <T> respondFileOpResult(
    call: ApplicationCall,
    result: AgentFilesService.FileOpResult<T>,
    schemaInvalidMsg: String,
    onOk: suspend (AgentFilesService.FileOpResult.Ok<T>) -> Unit,
) {
    when (result) {
        is AgentFilesService.FileOpResult.Ok -> {
            onOk(result)
        }

        is AgentFilesService.FileOpResult.NotFound -> {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = result.msg))
        }

        is AgentFilesService.FileOpResult.BadRequest -> {
            throw QueryParamValidationException(result.msg)
        }

        is AgentFilesService.FileOpResult.Forbidden -> {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = result.msg))
        }

        is AgentFilesService.FileOpResult.Conflict -> {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(msg = result.msg))
        }

        is AgentFilesService.FileOpResult.SchemaInvalid -> {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                SchemaErrorResponse(msg = schemaInvalidMsg, errors = result.errors),
            )
        }
    }
}
