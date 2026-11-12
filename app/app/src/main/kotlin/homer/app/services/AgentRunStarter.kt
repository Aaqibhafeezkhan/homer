package homer.app.services

import homer.app.exceptions.InvocationCycleDetectedException
import homer.app.exceptions.InvocationDepthExceededException
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.TelemetryService
import homer.app.infra.langfuseTraceTags
import homer.app.infra.stampLangfuseTrace
import homer.app.infra.startAgentRootSpan
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ProcessStatus
import homer.app.services.persistence.ProcessRecordReads
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
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
    // identically while the mode is on — except a start from a run that is still `WORKING`,
    // which is admitted. See ADR-0092.
    private val maintenanceGate: MaintenanceModeService,
    // Read for the parent row's status: a start whose server-derived parent is still `WORKING`
    // is part of admitted work and does not park at the gate. See ADR-0092.
    private val processReads: ProcessRecordReads,
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
        // True only for a path whose [parentProcessId] the server derived from a run it knows
        // is live (a `!<agent>` hop, a dispatch, a continuation fire): it may admit this start
        // without queueing when that parent row still reads `WORKING`. A caller-supplied or
        // inherited parent link (HTTP parent header, retry, resume) leaves this false, so it
        // never bypasses. ADR-0092.
        serverDerivedParent: Boolean = false,
        resolveInputs: suspend () -> Map<String, String>,
    ): Int {
        val span = telemetryService.tracer.startAgentRootSpan(agent, "agent_request")

        try {
            val context = Context.current().with(span)
            val admitted = admittedByLiveParent(serverDerivedParent, parentProcessId)
            // Parks this start while maintenance mode is on, before any preflight: a released
            // start re-runs the checks and resolves its inputs at dequeue, not at enqueue.
            // See ADR-0092.
            val processId =
                maintenanceGate.gated(owner, admitted) {
                    startGated(
                        agent = agent,
                        span = span,
                        context = context,
                        admitted = admitted,
                        source = source,
                        invocationChain = invocationChain,
                        ancestorLockKeys = ancestorLockKeys,
                        owner = owner,
                        extraSecretInputKeys = extraSecretInputKeys,
                        parentProcessId = parentProcessId,
                        rootProcessId = rootProcessId,
                        extraAttributes = extraAttributes,
                        resolveInputs = resolveInputs,
                    )
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
     * The preflight, input resolution, record creation and launch of one admitted start —
     * everything [start] does between the maintenance gate and the run's id, split out to
     * keep [start]'s body inside the size rule (ADR-0083).
     */
    private suspend fun startGated(
        agent: Agent,
        span: Span,
        context: Context,
        admitted: Boolean,
        source: String,
        invocationChain: List<String>,
        ancestorLockKeys: List<String>,
        owner: String?,
        extraSecretInputKeys: Set<String>,
        parentProcessId: Int?,
        rootProcessId: Int?,
        extraAttributes: Attributes,
        resolveInputs: suspend () -> Map<String, String>,
    ): Int {
        // A keyed cooldown is checked after the inputs are resolved, because its key is a
        // template over them; a keyless one is refused by `checkPreflight`, before anything
        // is resolved. Both keep cycle → depth → cost cap → cooldown. See ADR-0103.
        val keyedCooldown = agent.cooldown?.key != null
        checkPreflight(agent, invocationChain, withCooldown = !keyedCooldown)
        val newChain = invocationChain + agent.name
        val queryParamsEnv = resolveInputs()
        // Rendered here, before `create`: an unusable key fails the start with no process row
        // to clean up, and the same value goes to the pre-flight gate and to the row, so the
        // two cannot scope differently. See ADR-0103.
        val cooldownKey = if (keyedCooldown) agent.renderCooldownKey(queryParamsEnv) else null
        if (keyedCooldown) cooldownGate.check(agent, cooldownKey)
        // Current flags for a fresh run, unioned with a replay's snapshot.
        val secretEnvNames = agent.secretEnvNames + extraSecretInputKeys
        val traceTags = listOf(source) + langfuseTraceTags(agent, queryParamsEnv, secretEnvNames)
        span.stampLangfuseTrace(agent, traceTags)

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
                    cooldownKey = cooldownKey,
                ),
                context,
            )

        span.setAllAttributes(
            runAttributes(
                result.processId,
                agent,
                source,
                parentProcessId,
                rootProcessId,
                admitted,
                extraAttributes,
            ),
        )
        return result.processId
    }

    /**
     * Whether this start is part of work the maintenance window — or the shutdown drain — already
     * admitted: [serverDerivedParent] is set (the caller derived the link from a run it knows is
     * live — a `!<agent>` hop, a dispatch, a continuation fire), the mode is on or the drain is
     * running, **and** that run's own row still reads `WORKING`. A parent that is absent or already
     * terminal is refused (drain) or parked (maintenance) as before.
     *
     * [information_disclosure / CWE-200] The status is read from the row, never taken from the caller's
     * link, and a false flag short-circuits **before** the read: a caller-supplied parent admits nothing
     * and probes nothing, so naming a parent cannot turn this into an existence check on a row the caller
     * cannot see (root §12 rule 3). The in-memory mode/drain check runs before the read, so the steady
     * state — neither set, the row's answer discarded anyway — pays no database round-trip.
     * See ADR-0092, ADR-0102.
     */
    private suspend fun admittedByLiveParent(
        serverDerivedParent: Boolean,
        parentProcessId: Int?,
    ): Boolean =
        serverDerivedParent &&
            parentProcessId != null &&
            (maintenanceGate.isEnabled() || maintenanceGate.isDraining()) &&
            processReads.getProcessStatus(parentProcessId) == ProcessStatus.WORKING

    /**
     * The request-boundary refusals, in order: invocation cycle, depth, cost cap,
     * cooldown. Each throws the HomerException the caller maps to its error surface.
     *
     * [withCooldown] is false for an agent whose `cooldown.key` has to be rendered from the
     * resolved inputs: the cycle, depth and cost-cap checks stay where they are, and the
     * caller runs the cooldown check itself once the key exists. See ADR-0103.
     */
    private suspend fun checkPreflight(
        agent: Agent,
        invocationChain: List<String>,
        withCooldown: Boolean = true,
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
        if (withCooldown) cooldownGate.check(agent)
    }

    /** The `agent_request` span's attributes for the started run [processId]. */
    private fun runAttributes(
        processId: Int,
        agent: Agent,
        source: String,
        parentProcessId: Int?,
        rootProcessId: Int?,
        admitted: Boolean,
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
            // Why this start did not wait at the maintenance gate: a boolean only, never a
            // parent's inputs. True only for a start admitted while the mode was on; false for
            // every parked start and every start that ran with the mode off.
            .put("maintenance.bypassed", admitted)
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
