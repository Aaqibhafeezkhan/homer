package homer.app.services

import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.services.QuestionsService.Question
import homer.app.services.QuestionsService.QuestionStatus
import org.slf4j.Logger
import java.util.UUID

/**
 * The registration gates a single and a grouped dispatch share, after the payload and question
 * checks: the target is authorized and opted in, its inputs are satisfiable (an inheriting
 * dispatch to the same agent counts the origin run's stored inputs as provided, ADR-0101), and
 * the TTL is positive. The rows a registration writes — the dispatch-bound question and the
 * PENDING dispatch — are built here too, so the two create paths cannot drift. See ADR-0021,
 * ADR-0109.
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

    /**
     * The dispatch-bound question row a registration creates: OPEN, owned by the origin process,
     * exempt from the ordinary-question teardown paths (ADR-0021).
     */
    fun questionRow(
        originProcessId: Int,
        originAgentName: String,
        text: String,
        options: List<String>,
        now: Long,
    ): Question =
        Question(
            id = UUID.randomUUID().toString(),
            processId = originProcessId,
            agentName = originAgentName,
            text = text,
            options = options,
            status = QuestionStatus.OPEN,
            createdAt = now,
            dispatchBound = true,
        )

    /**
     * The PENDING dispatch row a registration inserts, single or grouped by [kind]. The
     * ADR-0052 secret snapshot is captured here — the one place both create paths get it
     * from — so a change to how a dispatch captures secrets cannot land in one create path
     * and miss the other.
     */
    fun dispatchRow(
        questionId: String,
        agent: String,
        answerParam: String,
        params: Map<String, String>,
        originProcessId: Int,
        originAgentName: String,
        effectiveTtl: Int?,
        now: Long,
        owner: String?,
        kind: DispatchKind = DispatchKind.SINGLE,
    ): Dispatch =
        Dispatch(
            id = 0,
            questionId = questionId,
            status = DispatchStatus.PENDING,
            agentName = agent,
            answerParam = answerParam,
            params = params,
            originProcessId = originProcessId,
            originAgentName = originAgentName,
            expiresAt = effectiveTtl?.let { now + it },
            createdAt = now,
            owner = owner,
            // Captured now, read at fire: the target may be edited in between, and a value
            // collected under `secret: true` must not become a trace tag or an unredacted
            // API field because the flag was flipped. See ADR-0052.
            secretInputKeys = agentsService.agents[agent]?.secretEnvNames,
            // paramName counterpart of secretInputKeys — see Dispatch.secretParamNames.
            secretParamNames = agentsService.agents[agent]?.secretParamNames,
            kind = kind,
        )

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
