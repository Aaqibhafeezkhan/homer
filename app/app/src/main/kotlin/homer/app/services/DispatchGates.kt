package homer.app.services

import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.models.Agent

// The origin and target gates shared by DispatchService and ContinuationService.

/** The asking process's registration; throws if it is gone or closed (a dead origin must not dispatch or register). */
internal fun QuestionsService.liveRegistration(originProcessId: Int): QuestionsService.Registration {
    val registration = registrationFor(originProcessId)
    if (registration == null || registration.closed) {
        throw QuestionsClosedForProcessException(originProcessId)
    }
    return registration
}

/**
 * The target agent; throws if the hop is unauthorized, the agent unknown, or not opted in via
 * `dispatch.enabled` (the shared gate). An unauthorized hop throws the unknown-agent exception,
 * so agent code cannot probe which agents exist (ADR-0047 rule 3, ADR-0039 amendment).
 */
internal fun Authorizer.dispatchableTarget(
    agentsService: AgentsService,
    owner: String?,
    dispatchingAgent: String,
    agent: String,
): Agent {
    if (!canDispatchHop(owner, dispatchingAgent, agent)) throw DispatchAgentNotFoundException(agent)
    val target = agentsService.agents[agent] ?: throw DispatchAgentNotFoundException(agent)
    if (target.dispatch?.enabled != true) throw AgentNotDispatchableException(agent)
    return target
}
