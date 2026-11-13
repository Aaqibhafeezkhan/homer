package homer.app.services

import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import org.slf4j.Logger

/**
 * The registration gates a single and a grouped dispatch share, after the payload and question
 * checks: the target is authorized and opted in, its inputs are satisfiable (an inheriting
 * dispatch to the same agent counts the origin run's stored inputs as provided, ADR-0101), and
 * the TTL is positive. See ADR-0021, ADR-0109.
 */
internal class DispatchTargetGate(
    private val authorizer: Authorizer,
    private val agentsService: AgentsService,
    private val agentInputResolver: AgentInputResolver,
    private val processManager: ProcessManager,
    private val logger: Logger,
) {
    /** Throws on the first failed gate; returns the origin run's owner, which the dispatch row records. */
    suspend fun check(
        originProcessId: Int,
        originAgentName: String,
        agent: String,
        answerParam: String,
        params: Map<String, String>,
        effectiveTtl: Int?,
        label: String,
    ): String? {
        val origin = processManager.getProcessInfo(originProcessId)
        val owner = origin?.owner
        val target = authorizer.dispatchableTarget(agentsService, owner, originAgentName, agent)
        val inherited = inheritedOriginInputs({ origin }, originAgentName, owner, target, label, logger)
        agentInputResolver.validateSatisfiable(target, params.keys + answerParam + inherited.params.keys)
        if (effectiveTtl != null && effectiveTtl <= 0) {
            throw ValidationException("ttlSec must be > 0; got $effectiveTtl", "ttlSec")
        }
        return owner
    }

    /** Why the claimed [dispatch] may no longer start [agent], else null. */
    fun hopDenial(
        dispatch: Dispatch,
        agent: Agent,
    ): String? {
        if (!authorizer.canDispatchHop(dispatch.owner, dispatch.originAgentName, dispatch.agentName)) {
            return "agent '${dispatch.originAgentName}' is not authorized to dispatch to '${dispatch.agentName}'"
        }
        // Re-check the opt-in at fire time: registration gated on `dispatch.enabled`, but the
        // agent may have been reloaded to `enabled: false` or replaced since. Without this a
        // pending dispatch created while enabled would still fire the now-disabled target.
        if (agent.dispatch?.enabled != true) {
            return "Target agent '${dispatch.agentName}' is no longer an allowed dispatch target"
        }
        return null
    }
}
