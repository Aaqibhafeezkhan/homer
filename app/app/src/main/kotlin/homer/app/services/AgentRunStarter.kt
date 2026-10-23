package homer.app.services

import homer.app.exceptions.InvocationCycleDetectedException
import homer.app.exceptions.InvocationDepthExceededException
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.TelemetryService
import homer.app.infra.langfuseTraceTags
import homer.app.infra.startAgentRootSpan
import homer.app.models.Agent
import homer.app.models.Command
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context

/**
 * Call-independent core of starting an agent run: `agent_request` span,
 * invocation-chain cycle/depth checks, cost-cap preflight, and process
 * creation + launch. Shared by the HTTP route (`handleAgentExecution`) and
 * the MCP agent tools so caps, locks, and parallel-run limits apply
 * identically regardless of entry point.
 */
internal class AgentRunStarter(
    private val serverId: String,
    private val processExecutor: ProcessExecutor,
    private val processFactory: ProcessFactory,
    private val telemetryService: TelemetryService,
    private val costMeter: CostMeter,
    private val maxInvocationDepth: Int,
    private val cooldownGate: CooldownGate,
    // The maintenance-mode admission gate. It sits above the claim queue, so every runner
    // backend and every start source (HTTP, MCP, dispatch, continuation, `!agent`) queues
    // identically while the mode is on. See ADR-0092.
    private val maintenanceGate: MaintenanceModeService,
) {
    /**
     * Starts an agent run and returns the new process id.
     *
     * [resolveInputs] is invoked inside the span, after the cycle/depth/cost
     * checks — preserving the HTTP handler's original exception ordering
     * (cycle → depth → cost cap → input validation). Throws the same
     * HomerExceptions the HTTP handler always did; callers map them to their
     * own error surface (StatusPages for HTTP, isError results for MCP).
     */
    @Suppress("SuspendFunSwallowedCancellation") // records the span error and rethrows
    suspend fun start(
        agent: Agent,
        invocationChain: List<String>,
        source: String,
        extraAttributes: Attributes = Attributes.empty(),
        ancestorLockKeys: List<String> = emptyList(),
        owner: String? = null,
        // Non-empty only for a path replaying STORED inputs (dispatch and continuation
        // fires): the target's `secret` flags may have changed since those values were
        // collected, and the run must honour the flags in force then. See ADR-0052.
        extraSecretInputKeys: Set<String> = emptySet(),
        // The run that caused this one, already validated by the caller (a live
        // RunAgent/dispatch/continuation origin, or an external caller's visible
        // parent). Stored with the row and exported as HMR_PARENT_PROCESS_ID. ADR-0058.
        parentProcessId: Int? = null,
        // The flow's first run: `parent.rootProcessId ?: parent.id`, derived from
        // the same already-validated parent. Stored with the row and exported as
        // HMR_ROOT_PROCESS_ID. Null for a run with no parent. ADR-0058.
        rootProcessId: Int? = null,
        resolveInputs: suspend () -> Map<String, String>,
    ): Int {
        val span = telemetryService.tracer.startAgentRootSpan(agent, "agent_request")

        try {
            val context = Context.current().with(span)
            // Parks this start while maintenance mode is on, before any preflight: a start
            // released later must re-run the cycle/depth/cost/cooldown checks and resolve its
            // inputs against the state in force at dequeue, not at enqueue. The gate holds its
            // release slot until the run exists — or the start fails — and frees it even when
            // this caller is cancelled. See ADR-0092.
            val processId =
                maintenanceGate.gated(owner) {
                    checkPreflight(agent, invocationChain)
                    val newChain = invocationChain + agent.name
                    val queryParamsEnv = resolveInputs()
                    // Current flags for a fresh run; union with the registration snapshot when
                    // this is a replay of stored inputs.
                    val secretEnvNames = agent.secretEnvNames + extraSecretInputKeys

                    val result =
                        processExecutor.execute(
                            processFactory.create(
                                agent = agent,
                                queryParams = queryParamsEnv,
                                parentContext = context,
                                invocationChain = newChain,
                                ancestorLockKeys = ancestorLockKeys,
                                owner = owner,
                                extraSecretInputKeys = secretEnvNames,
                                parentProcessId = parentProcessId,
                                rootProcessId = rootProcessId,
                            ),
                            context,
                        )

                    val traceTags = listOf(source) + langfuseTraceTags(agent, queryParamsEnv, secretEnvNames)
                    span.setAllAttributes(
                        runAttributes(result.processId, agent, source, parentProcessId, rootProcessId, traceTags, extraAttributes),
                    )
                    result.processId
                }
            span.setStatus(StatusCode.OK)

            return processId
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }

    /**
     * The request-boundary refusals, in order: invocation cycle, depth, cost cap,
     * cooldown. Each throws the HomerException the caller maps to its error surface.
     */
    private suspend fun checkPreflight(
        agent: Agent,
        invocationChain: List<String>,
    ) {
        if (agent.name in invocationChain) {
            throw InvocationCycleDetectedException(agent.name, invocationChain)
        }
        if (invocationChain.size + 1 > maxInvocationDepth) {
            throw InvocationDepthExceededException(agent.name, maxInvocationDepth, invocationChain)
        }
        // Pre-flight cost cap check at the request boundary so a refusal can
        // surface to the caller. The same exception thrown later (inside the
        // launched process) only marks the process FAILED — it cannot influence
        // the already-sent response. Skip the check for agents that never run an
        // LLM harness command (Claude or Codex); they cannot accrue or be bound
        // by LLM spend caps.
        if (agent.commands.any { it is Command.LlmCommand }) {
            costMeter.checkCaps(agent.name)
        }
        // Frequency bound, after the spend bound: both are pre-flight refusals at
        // the request boundary, and a caller over its cost cap should hear about
        // that first — it is the more consequential of the two.
        cooldownGate.check(agent)
    }

    /** The `agent_request` span's attributes for the started run [processId]. */
    private fun runAttributes(
        processId: Int,
        agent: Agent,
        source: String,
        parentProcessId: Int?,
        rootProcessId: Int?,
        traceTags: List<String>,
        extraAttributes: Attributes,
    ): Attributes =
        Attributes
            .builder()
            .put("process.id", processId.toLong())
            .put("server.id", serverId)
            .put("agent.name", agent.name)
            .put("request.source", source)
            .apply { parentProcessId?.let { put("parent.process.id", it.toLong()) } }
            .apply { rootProcessId?.let { put("root.process.id", it.toLong()) } }
            // Langfuse names a trace after its root span, which is the constant
            // "agent_request" — so every run of every agent lands under one name and its
            // trace API (name/tags/userId/sessionId only) cannot separate them. These two
            // attributes are the documented way to give it the agent instead.
            .put("langfuse.trace.name", agent.name)
            .put(AttributeKey.stringArrayKey("langfuse.trace.tags"), traceTags)
            .putAll(extraAttributes)
            .build()
}

/**
 * [information_disclosure / CWE-200] The flow-root id a write path may carry:
 * [candidate] only when the root row exists and [principal] can see it under
 * the read union (ADR-0037). Otherwise the id would reach the caller through
 * the new row, its `HMR_ROOT_PROCESS_ID` env and the `root.process.id` span
 * attribute — the disclosure the status responses already deny. A missing root
 * row fails closed (null). Shared by the routes' caller-attributed writes
 * (external parent header, retry, resume) and the fire paths' origin
 * derivation. §12.
 */
internal suspend fun ProcessManager.visibleRootProcessId(
    principal: String?,
    authorizer: Authorizer,
    candidate: Int?,
): Int? =
    candidate?.let { rootId ->
        getProcessInfo(rootId)
            ?.takeIf { authorizer.canSee(principal, it.owner, it.agentName) }
            ?.id
    }

/**
 * The flow's first run as seen from a run whose parent is [originProcessId]:
 * the origin's own root when it has one, else the origin itself. Null when the
 * origin row is unreadable — the child then reads as a root rather than being
 * linked to a guessed id. [principal] is the owner the new run will be
 * attributed to; a derived root that owner cannot see is dropped, never
 * replaced by the origin's id, so a foreign flow-root id reaches no row, env or
 * span. Shared by every internal start path (RunAgent, dispatch, continuation)
 * so they cannot drift. See ADR-0058. §12.
 */
internal suspend fun ProcessManager.visibleOriginRootProcessId(
    originProcessId: Int,
    principal: String?,
    authorizer: Authorizer,
): Int? {
    if (originProcessId <= 0) return null
    val origin = getProcessInfo(originProcessId) ?: return null
    val derived = origin.rootProcessId ?: return origin.id
    return visibleRootProcessId(principal, authorizer, derived)
}
