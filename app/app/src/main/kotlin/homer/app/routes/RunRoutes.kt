package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import homer.app.models.Action
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ErrorResponse
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.LangGraphRunStatusService
import homer.app.services.ProcessManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Run starts (`/agent/{agentName}`, `/processes/{id}/retry`, `/processes/{id}/resume`) and `GET /processes/{id}/langgraph`. */
internal fun Route.runRoutes(deps: ApiDeps) {
    runStartRoutes(deps)
    processLangGraphRoutes(deps)
}

/** `GET`/`POST /agent/{agentName}` and `POST /processes/{id}/retry`: starting an agent and replaying a terminal run. */
private fun Route.runStartRoutes(deps: ApiDeps) {
    with(deps) {
        route("/agent/{agentName}") {
            get { agentPermitting(call, Action.RUN, "run")?.let { startAgentRun(call, it) } }
            post { agentPermitting(call, Action.RUN, "run")?.let { startAgentRun(call, it) } }
        }

        post("/processes/{id}/retry") {
            val sourceProcess = visibleProcess(call) ?: return@post
            val agent =
                replayableAgent(call, sourceProcess, RETRYABLE_STATUSES) {
                    "Cannot retry a non-terminal process (status: ${it.name})"
                } ?: return@post
            handleRetry(
                serverId,
                call,
                sourceProcess,
                agent,
                authorizer,
                processManager,
                processExecutor,
                processFactory,
                telemetryService,
                costMeter,
                agentInputResolver,
                maintenanceModeService,
            )
        }
    }
}

/** `GET /processes/{id}/langgraph` and `POST /processes/{id}/resume`: a run's LangGraph state and resuming its thread. */
private fun Route.processLangGraphRoutes(deps: ApiDeps) {
    with(deps) {
        get("/processes/{id}/langgraph") {
            val processInfo = visibleProcess(call) ?: return@get
            val agent = agentsService.agents[processInfo.agentName]
            if (agent == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Agent '${processInfo.agentName}' no longer exists"))
                return@get
            }
            val includeGraph = call.request.queryParameters["graph"] == "true"
            val status = langGraphRunStatusService.status(processInfo, agent, includeGraph)
            if (status == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process ${processInfo.id} has no LangGraph run state"))
                return@get
            }
            call.respond(status)
        }

        post("/processes/{id}/resume") {
            val sourceProcess = visibleProcess(call) ?: return@post
            val agent =
                replayableAgent(call, sourceProcess, RESUMABLE_STATUSES) {
                    "Cannot resume a process in status ${it.name}"
                } ?: return@post
            val threadId = langGraphThreadToResume(langGraphRunStatusService, sourceProcess.id, sourceProcess, agent)
            handleResume(
                serverId,
                call,
                sourceProcess,
                agent,
                threadId,
                authorizer,
                processManager,
                processExecutor,
                processFactory,
                telemetryService,
                costMeter,
                agentInputResolver,
                maintenanceModeService,
            )
        }
    }
}

/** The statuses a retry replays from: terminal runs only. */
private val RETRYABLE_STATUSES =
    setOf(
        ProcessStatus.FINISHED,
        ProcessStatus.FAILED,
        ProcessStatus.KILLED,
    )

/** The statuses a resume continues from: runs that ended without finishing. */
private val RESUMABLE_STATUSES =
    setOf(
        ProcessStatus.FAILED,
        ProcessStatus.KILLED,
        ProcessStatus.UNKNOWN,
    )

/**
 * The agent the `{agentName}` path parameter names, when the caller holds [action] on it. Otherwise
 * responds and returns null: `404` when the caller holds no grant on it at all, the same as an
 * unknown agent (ADR-0047 rule 3), else `403` "Not permitted to [verb]".
 */
internal suspend fun ApiDeps.agentPermitting(
    call: ApplicationCall,
    action: Action,
    verb: String,
): Agent? {
    val agentName = call.pathParameter("agentName")
    val agent = agentsService.agents[agentName]?.takeIf { authorizer.anyGrant(call.principalName(), agentName) }
    if (agent == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Agent not found: $agentName"))
        return null
    }
    if (!authorizer.hasAction(call.principalName(), action, agentName)) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Not permitted to $verb '$agentName'"))
        return null
    }
    return agent
}

/** Starts [agent] for an external run request, linked to the parent its header names (ADR-0058). */
private suspend fun ApiDeps.startAgentRun(
    call: ApplicationCall,
    agent: Agent,
) {
    val (parentId, rootId) = resolveParentHeaderLinks(call, processManager, authorizer)
    handleAgentExecution(
        call,
        agent,
        agentInputResolver,
        agentRunStarter,
        parentProcessId = parentId,
        rootProcessId = rootId,
    )
}

/**
 * The process the `{id}` path parameter names, when the caller can see it. A non-integer id is a
 * `400`; otherwise responds `404` and returns null, the same for a missing and a hidden process
 * (ADR-0039) — so nothing a caller is not shown, terminal status or agent, is disclosed after it.
 */
private suspend fun ApiDeps.visibleProcess(call: ApplicationCall): ProcessInfo? {
    val processId = call.parameters["id"]?.toIntOrNull() ?: throw QueryParamValidationException("Invalid process id")
    val processInfo = processManager.getProcessInfo(processId)
    if (processInfo == null || !authorizer.canSee(call.principalName(), processInfo.owner, processInfo.agentName)) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process $processId not found"))
        return null
    }
    return processInfo
}

/**
 * The agent a retry or resume of the visible [sourceProcess] replays, when the run is in one of
 * [eligible] and the caller may replay it. Otherwise responds and returns null: `409` with
 * [conflict] for another status, `404` when the agent is gone, and `404` "not found" for a caller
 * who can see the run but lacks RUN (ADR-0047 rule 3).
 */
private suspend fun ApiDeps.replayableAgent(
    call: ApplicationCall,
    sourceProcess: ProcessInfo,
    eligible: Set<ProcessStatus>,
    conflict: (ProcessStatus) -> String,
): Agent? {
    if (sourceProcess.status !in eligible) {
        call.respond(HttpStatusCode.Conflict, ErrorResponse(msg = conflict(sourceProcess.status)))
        return null
    }
    val agent = agentsService.agents[sourceProcess.agentName]
    if (agent == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Agent '${sourceProcess.agentName}' no longer exists"))
        return null
    }
    if (!authorizer.canRetry(call.principalName(), sourceProcess.owner, sourceProcess.agentName)) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process ${sourceProcess.id} not found"))
        return null
    }
    return agent
}

/**
 * The LangGraph thread a resume of process [processId] ([sourceProcess]) continues: a 400 when
 * [agent] no longer defines a LangGraph command or the run left no thread to resume.
 */
internal fun langGraphThreadToResume(
    langGraphRunStatusService: LangGraphRunStatusService,
    processId: Int,
    sourceProcess: ProcessInfo,
    agent: Agent,
): String {
    if (agent.commands.none { it is Command.LangGraph }) {
        throw QueryParamValidationException("Agent '${sourceProcess.agentName}' no longer defines a LangGraph command")
    }
    val identity = langGraphRunStatusService.identity(sourceProcess)
    if (identity == null) {
        throw QueryParamValidationException("Process $processId has no LangGraph thread to resume")
    }
    return identity.threadId
}
