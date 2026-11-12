package homer.app.routes

import homer.app.EnvConstants
import homer.app.exceptions.QueryParamValidationException
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.AgentInputResolver
import homer.app.infra.ReservedEnvNames
import homer.app.infra.TelemetryService
import homer.app.infra.langfuseTraceTags
import homer.app.infra.stampLangfuseTrace
import homer.app.infra.startAgentRootSpan
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.DebugAgentRequest
import homer.app.models.ErrorResponse
import homer.app.models.ExecuteResponse
import homer.app.models.ProcessInfo
import homer.app.services.AgentRunStarter
import homer.app.services.Authorizer
import homer.app.services.CostMeter
import homer.app.services.MaintenanceModeService
import homer.app.services.ProcessManager
import homer.app.services.gated
import homer.app.services.renderCooldownKey
import homer.app.services.visibleOriginRootProcessId
import homer.app.services.visibleRootProcessId
import homer.app.services.withoutFailureBackoff
import io.ktor.http.HttpStatusCode
import io.ktor.http.ParametersBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context

internal suspend fun handleAgentExecution(
    call: ApplicationCall,
    agent: Agent,
    agentInputResolver: AgentInputResolver,
    agentRunStarter: AgentRunStarter,
    // Already validated against the caller's visibility by resolveParentProcessHeader;
    // a link is metadata only and never joins the chain or lock scope below. ADR-0058.
    parentProcessId: Int? = null,
    // The validated parent's own root (`parent.rootProcessId ?: parent.id`), so an
    // external run joins the parent's flow. Derived from the same already-validated
    // row — no new caller-settable input. ADR-0058.
    rootProcessId: Int? = null,
) {
    // External callers always start a fresh invocation chain with no inherited
    // ancestor lock keys. Internal RunAgent dispatch is in-process (it threads
    // the chain and lock keys directly to AgentRunStarter), so this route no
    // longer trusts any chain/lock headers. See ADR-0030.
    val processId =
        agentRunStarter.start(
            agent = agent,
            invocationChain = emptyList(),
            source = "http",
            owner = call.principalName(),
            parentProcessId = parentProcessId,
            rootProcessId = rootProcessId,
        ) { agentInputResolver.resolve(call, agent) }

    call.respond(ExecuteResponse(processId = processId))
}

/** [AgentInputResolver.resolve] over a live call; the body is read only if an input needs it. */
internal suspend fun AgentInputResolver.resolve(
    call: ApplicationCall,
    agent: Agent,
): Map<String, String> =
    resolve(
        method = call.request.httpMethod,
        queryParameters = call.request.queryParameters,
        headers = call.request.headers,
        readBody = { call.receiveText() },
        agent = agent,
    )

/**
 * Reads the optional `X-Homer-Parent-Process-Id` header of a run request and
 * resolves the parent row. The parent must exist AND be visible to the caller;
 * both failures answer the same 422 so process ids cannot be probed through this
 * header (§12: hide existence). A non-integer value is a plain 400. See ADR-0058.
 */
internal suspend fun resolveParentProcessHeader(
    call: ApplicationCall,
    processManager: ProcessManager,
    authorizer: Authorizer,
): ProcessInfo? {
    val raw =
        call.request.headers[EnvConstants.PARENT_PROCESS_HEADER]
            ?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
    val parentId =
        raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw QueryParamValidationException(
                "Invalid ${EnvConstants.PARENT_PROCESS_HEADER} header. Must be a positive integer",
                HttpStatusCode.BadRequest,
            )
    val parent = processManager.getProcessInfo(parentId)
    if (parent == null || !authorizer.canSee(call.principalName(), parent.owner, parent.agentName)) {
        throw QueryParamValidationException("Parent process $parentId not found", HttpStatusCode.UnprocessableEntity)
    }
    return parent
}

/**
 * The parent link and flow root for an external run, both derived from the one
 * parent row [resolveParentProcessHeader] fetched and visibility-checked: the
 * parent's id, and its root (`parent.rootProcessId ?: parent.id`, the shape of
 * [ProcessManager.visibleOriginRootProcessId]). One read per request, so the two links
 * cannot disagree. See ADR-0058.
 */
internal suspend fun resolveParentHeaderLinks(
    call: ApplicationCall,
    processManager: ProcessManager,
    authorizer: Authorizer,
): Pair<Int?, Int?> {
    val parent = resolveParentProcessHeader(call, processManager, authorizer) ?: return null to null
    // [information_disclosure / CWE-200] The parent row is visible by construction;
    // its flow root may not be. The derived root is carried only when this caller can
    // see that row too — a hidden root is dropped, never replaced by the parent id,
    // so the run is stored rootless rather than mis-rooted. §12.
    val rootId =
        if (parent.rootProcessId == null) {
            parent.id
        } else {
            processManager.visibleRootProcessId(call.principalName(), authorizer, parent.rootProcessId)
        }
    return parent.id to rootId
}

internal suspend fun handleAgentDebug(
    serverId: String,
    call: ApplicationCall,
    agent: Agent,
    agentInputResolver: AgentInputResolver,
    processExecutor: ProcessExecutor,
    processFactory: ProcessFactory,
    telemetryService: TelemetryService,
    maintenanceGate: MaintenanceModeService,
) {
    val req =
        call.receiveOr400<DebugAgentRequest>(
            "Invalid JSON body: expected { commandIndex, env, queryParams, body, headers }",
        )
    if (req.commandIndex < 0 || req.commandIndex >= agent.commands.size) {
        throw QueryParamValidationException("commandIndex out of range: ${req.commandIndex} (0..${agent.commands.size - 1})")
    }
    val reservedOverrides = req.env.keys.filter { ReservedEnvNames.isReserved(it) }
    if (reservedOverrides.isNotEmpty()) {
        throw QueryParamValidationException(
            "env overrides reserved names (Homer / host-controlled): " +
                reservedOverrides.joinToString(", "),
        )
    }
    respondStartedUnderRootSpan(call, telemetryService, agent, "agent_debug_request", maintenanceGate) { context, span ->
        // Manual recovery is not gated by `afterFailure`; `minInterval` still holds. See ADR-0051.
        val debugAgent = agent.copy(env = agent.env + req.env).withoutFailureBackoff()
        val resolvedQueryParams = resolveDebugParams(agentInputResolver, debugAgent, req)
        // Stamped before `execute`, so the run's own steps and generations inherit both.
        span.stampLangfuseTrace(
            agent,
            listOf("debug") + langfuseTraceTags(agent, resolvedQueryParams, agent.secretEnvNames),
        )
        // Rendered from the debug agent's env — this request's overrides included — so a keyed
        // `minInterval` scopes the window to the subject a normal start of these inputs would
        // use. `afterFailure` is already gone above. Fails the request before any row exists.
        // See ADR-0103.
        val cooldownKey = debugAgent.renderCooldownKey(resolvedQueryParams)
        val result =
            processExecutor.execute(
                processFactory.create(
                    debugAgent,
                    resolvedQueryParams,
                    context,
                    commandIndices = listOf(req.commandIndex),
                    owner = call.principalName(),
                    cooldownKey = cooldownKey,
                ),
                context,
            )
        result.processId to
            Attributes
                .builder()
                .put("process.id", result.processId.toLong())
                .put("server.id", serverId)
                .put("agent.name", agent.name)
                .put("debug.command_index", req.commandIndex.toLong())
                .put("request.source", "debug")
                .build()
    }
}

/**
 * The debug request's inputs, resolved through the same extraction and validation a live run
 * uses — from this request's `queryParams`, `body` and `headers` rather than from storage, so
 * there is no historical value that could have been secret under an older definition. See
 * ADR-0052.
 */
private fun resolveDebugParams(
    agentInputResolver: AgentInputResolver,
    agent: Agent,
    req: DebugAgentRequest,
): Map<String, String> {
    val builder = ParametersBuilder()
    req.queryParams.forEach { (k, v) -> builder.append(k, v) }
    return agentInputResolver.resolveDebug(
        queryParams = builder.build(),
        bodyJson = req.body,
        headers = req.headers,
        agent = agent,
        enforceRequired = false,
    )
}

internal suspend fun handleRetry(
    serverId: String,
    call: ApplicationCall,
    sourceProcess: ProcessInfo,
    agent: Agent,
    authorizer: Authorizer,
    processManager: ProcessManager,
    processExecutor: ProcessExecutor,
    processFactory: ProcessFactory,
    telemetryService: TelemetryService,
    costMeter: CostMeter,
    agentInputResolver: AgentInputResolver,
    maintenanceGate: MaintenanceModeService,
) {
    respondStartedUnderRootSpan(call, telemetryService, agent, "agent_retry_request", maintenanceGate) { context, span ->
        // Re-validate the stored inputs against the CURRENT agent definition (a fresh
        // request would); retry must not replay values the current input policy rejects.
        val retryParams = agentInputResolver.resolveRetryParams(agent, sourceProcess.inputParams)
        // What was secret for the SOURCE run stays secret for its replay, whatever the
        // definition says now; union with the current flags so a newly-secret input is
        // covered too. Drives both the trace tags and the new row's own snapshot.
        val retrySecretEnvNames = agent.secretEnvNames + sourceProcess.secretInputKeys.orEmpty()
        // Pre-flight cost cap check — same rationale as handleAgentExecution.
        if (agent.commands.any { it is Command.LlmCommand }) {
            costMeter.checkCaps(agent.name)
        }
        // [information_disclosure / CWE-200] The replaying caller may see the source run
        // without being able to see the flow root it points at; the root is carried only
        // when they can. See ADR-0058.
        val retryRootProcessId =
            processManager.visibleRootProcessId(call.principalName(), authorizer, sourceProcess.rootProcessId)
        // Union, not the current definition alone: these values were collected under the source
        // run's flags, and an input flipped to `secret: false` since would otherwise be tagged in
        // the clear. Stamped before `execute` so the run's own spans inherit it. See ADR-0052.
        span.stampLangfuseTrace(agent, listOf("retry") + langfuseTraceTags(agent, retryParams, retrySecretEnvNames))
        // A replay carries the source run's subject, so a keyed `minInterval` still paces it
        // against that subject's other starts. See ADR-0103.
        val retryCooldownKey = agent.renderCooldownKey(retryParams)

        val result =
            processExecutor.execute(
                processFactory.create(
                    // Manual recovery is not gated by `afterFailure`; `minInterval` still holds. See ADR-0051.
                    agent.withoutFailureBackoff(),
                    retryParams,
                    context,
                    owner = call.principalName(),
                    extraSecretInputKeys = retrySecretEnvNames,
                    // A replay is still part of the flow that caused the source run.
                    parentProcessId = sourceProcess.parentProcessId,
                    rootProcessId = retryRootProcessId,
                    cooldownKey = retryCooldownKey,
                ),
                context,
            )

        result.processId to
            Attributes
                .builder()
                .put("process.id", result.processId.toLong())
                .put("server.id", serverId)
                .put("agent.name", agent.name)
                .put("retry.of_process_id", sourceProcess.id.toLong())
                .put("request.source", "retry")
                .apply { sourceProcess.parentProcessId?.let { put("parent.process.id", it.toLong()) } }
                .apply { retryRootProcessId?.let { put("root.process.id", it.toLong()) } }
                .build()
    }
}

internal suspend fun handleResume(
    serverId: String,
    call: ApplicationCall,
    sourceProcess: ProcessInfo,
    agent: Agent,
    threadId: String,
    authorizer: Authorizer,
    processManager: ProcessManager,
    processExecutor: ProcessExecutor,
    processFactory: ProcessFactory,
    telemetryService: TelemetryService,
    costMeter: CostMeter,
    agentInputResolver: AgentInputResolver,
    maintenanceGate: MaintenanceModeService,
) {
    respondStartedUnderRootSpan(call, telemetryService, agent, "agent_resume_request", maintenanceGate) { context, span ->
        // Same re-validation as retry, plus the recover markers. A stored
        // HMR_LG_RESUME survives resolveRetryParams' reserved-name passthrough
        // and wins over recover in the runtime -- the correct replay for a
        // segment that crashed mid-interrupt-resume. See ADR-0044.
        val resumeParams =
            agentInputResolver.resolveRetryParams(agent, sourceProcess.inputParams) +
                mapOf(
                    EnvConstants.LG_THREAD to threadId,
                    EnvConstants.LG_RECOVER to "1",
                )
        // Same reasoning as retry: these are the source run's stored inputs.
        val resumeSecretEnvNames = agent.secretEnvNames + sourceProcess.secretInputKeys.orEmpty()
        // Pre-flight cost cap check — same rationale as handleAgentExecution.
        if (agent.commands.any { it is Command.LlmCommand }) {
            costMeter.checkCaps(agent.name)
        }
        // Same root-visibility gate as retry: the resuming caller may see the source
        // run without being able to see the flow root it points at. See ADR-0058.
        val resumeRootProcessId =
            processManager.visibleRootProcessId(call.principalName(), authorizer, sourceProcess.rootProcessId)
        // Same as retry: the stored inputs carry the source run's secrecy, and the stamp has to
        // land before `execute` for the run's own spans to inherit it.
        span.stampLangfuseTrace(agent, listOf("resume") + langfuseTraceTags(agent, resumeParams, resumeSecretEnvNames))
        // Same as retry: the resumed segment keeps the source run's subject. See ADR-0103.
        val resumeCooldownKey = agent.renderCooldownKey(resumeParams)

        val result =
            processExecutor.execute(
                processFactory.create(
                    // Manual recovery is not gated by `afterFailure`; `minInterval` still holds. See ADR-0051.
                    agent.withoutFailureBackoff(),
                    resumeParams,
                    context,
                    owner = call.principalName(),
                    extraSecretInputKeys = resumeSecretEnvNames,
                    parentProcessId = sourceProcess.parentProcessId,
                    rootProcessId = resumeRootProcessId,
                    cooldownKey = resumeCooldownKey,
                ),
                context,
            )

        result.processId to
            Attributes
                .builder()
                .put("process.id", result.processId.toLong())
                .put("server.id", serverId)
                .put("agent.name", agent.name)
                .put("resume.of_process_id", sourceProcess.id.toLong())
                .put("request.source", "resume")
                .apply { sourceProcess.parentProcessId?.let { put("parent.process.id", it.toLong()) } }
                .apply { resumeRootProcessId?.let { put("root.process.id", it.toLong()) } }
                .build()
    }
}

/**
 * Starts a run under a fresh root span [spanName] and answers its process id. [start] gets the
 * span's context and the span itself — it stamps the Langfuse trace name and tags with
 * [stampLangfuseTrace] before it calls `processExecutor.execute` — and returns the started process
 * id with the span's remaining attributes; a failure marks the span ERROR and propagates. Via
 * startAgentRootSpan, not spanBuilder: debug, retry and resume build their own root span outside
 * AgentRunStarter, so anything that gates run tracing has to be applied here too —
 * `telemetry.export: OFF` included. See ADR-0050.
 *
 * [maintenanceGate] parks [start] while maintenance mode is on, in the span and before [start]
 * runs, exactly as [AgentRunStarter.start] does: these three are the starts that do not go through
 * the starter, and every start path has to queue. See ADR-0092.
 */
@Suppress("SuspendFunSwallowedCancellation") // records the span error and rethrows
private suspend fun respondStartedUnderRootSpan(
    call: ApplicationCall,
    telemetryService: TelemetryService,
    agent: Agent,
    spanName: String,
    maintenanceGate: MaintenanceModeService,
    start: suspend (Context, Span) -> Pair<Int, Attributes>,
) {
    val span = telemetryService.tracer.startAgentRootSpan(agent, spanName)
    try {
        val (processId, attributes) =
            maintenanceGate.gated(call.principalName()) { start(Context.current().with(span), span) }
        span.setAllAttributes(attributes)
        span.setStatus(StatusCode.OK)
        call.respond(ExecuteResponse(processId = processId))
    } catch (e: Exception) {
        span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}
