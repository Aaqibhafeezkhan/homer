package homer.app.services

import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.DispatchNotFoundException
import homer.app.exceptions.DispatchNotPendingException
import homer.app.exceptions.HomerException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.TooManyPendingDispatchesException
import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.infra.reservedControlParams
import homer.app.infra.validatePayloadParam
import homer.app.models.Agent
import homer.app.models.DispatchConfig
import homer.app.services.QuestionsService.Question
import homer.app.services.QuestionsService.QuestionStatus
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.RecordPage
import io.opentelemetry.api.common.Attributes
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Status names used as the `dispatches.status` column value. */
enum class DispatchStatus { PENDING, DISPATCHING, DISPATCHED, FAILED, CANCELLED }

/**
 * A registered ask-and-dispatch: an operator question plus the agent to start
 * (with [params] and the answer under [answerParam]) once it is answered. The
 * dispatch and its question outlive the asking process. See the ask-and-dispatch ADR.
 */
data class Dispatch(
    val id: Int,
    val questionId: String,
    val status: DispatchStatus,
    val agentName: String,
    val answerParam: String,
    val params: Map<String, String>,
    val originProcessId: Int,
    val originAgentName: String,
    val expiresAt: Long?,
    val createdAt: Long,
    val dispatchedAt: Long? = null,
    val dispatchedProcessId: Int? = null,
    val error: String? = null,
    // The origin process's owner, copied in at registration (live, since the origin
    // may have finished by fire time) so the fired run inherits it. See ADR-0038.
    val owner: String? = null,
    // Target agent's `secret: true` envNames at registration. Read at fire so a
    // definition edited in between cannot expose a stored value; null for rows
    // predating the column, which fall back to the flags in force at fire time.
    // See ADR-0052.
    val secretInputKeys: Set<String>? = null,
    // Target agent's `secret: true` paramNames at registration — the paramName
    // counterpart of [secretInputKeys]. [params] above is stored keyed by paramName,
    // so redactStoredParams matches this directly instead of translating envNames
    // through the (possibly since-edited) current agent definition. Null for rows
    // predating the column, which fall back to the envName-only check. See ADR-0052.
    val secretParamNames: Set<String>? = null,
)

@OptIn(ExperimentalTime::class)
internal class DispatchService(
    private val repository: DispatchRepository,
    private val questionsService: QuestionsService,
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    private val agentRunStarter: AgentRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val processManager: ProcessManager,
    private val broadcaster: ProcessEventBroadcaster,
    private val logger: Logger,
    private val config: DispatchConfig = DispatchConfig(),
    private val nowSec: () -> Long = { Clock.System.now().epochSeconds },
) {
    data class CreateResult(
        val dispatchId: Int,
        val questionId: String,
    )

    suspend fun create(
        originProcessId: Int,
        text: String,
        options: List<String>,
        agent: String,
        answerParam: String,
        params: Map<String, String>,
        ttlSec: Int?,
    ): CreateResult {
        val registration = questionsService.liveRegistration(originProcessId)
        // A reserved name would place the operator's answer nowhere — or, before the
        // reservedControlParams merge was narrowed, into the target's PATH.
        validatePayloadParam(answerParam, "answerParam", params)
        questionsService.validateQuestionInput(text, options)
        val owner = processManager.getProcessInfo(originProcessId)?.owner
        val target = authorizer.dispatchableTarget(agentsService, owner, registration.agentName, agent)
        agentInputResolver.validateSatisfiable(target, params.keys + answerParam)
        val effectiveTtl = ttlSec ?: config.defaultTtlSec
        if (effectiveTtl != null && effectiveTtl <= 0) {
            throw ValidationException("ttlSec must be > 0; got $effectiveTtl", "ttlSec")
        }
        val now = nowSec()
        val questionId = UUID.randomUUID().toString()
        val question =
            Question(
                id = questionId,
                processId = originProcessId,
                agentName = registration.agentName,
                text = text,
                options = options,
                status = QuestionStatus.OPEN,
                createdAt = now,
                dispatchBound = true,
            )
        val dispatch =
            Dispatch(
                id = 0,
                questionId = questionId,
                status = DispatchStatus.PENDING,
                agentName = agent,
                answerParam = answerParam,
                params = params,
                originProcessId = originProcessId,
                originAgentName = registration.agentName,
                expiresAt = effectiveTtl?.let { now + it },
                createdAt = now,
                owner = owner,
                // Captured now, read at fire: the target may be edited in between, and a
                // value collected under `secret: true` must not become a trace tag or an
                // unredacted API field because the flag was flipped. See ADR-0052.
                secretInputKeys = agentsService.agents[agent]?.secretEnvNames,
                // paramName counterpart of secretInputKeys — see Dispatch.secretParamNames.
                secretParamNames = agentsService.agents[agent]?.secretParamNames,
            )
        val dispatchId =
            repository.create(question, dispatch, config.maxPending)
                ?: throw TooManyPendingDispatchesException(config.maxPending)
        logger.info("Process {} registered dispatch {} -> agent '{}'", originProcessId, dispatchId, agent)
        broadcaster.emit(ProcessEvent.QuestionAsked(originProcessId, questionId, registration.agentName))
        return CreateResult(dispatchId, questionId)
    }

    /**
     * Starts the target run for the (now answered) question. Claims the dispatch
     * (PENDING→DISPATCHING) before starting the run so a crash between run-start and
     * markDispatched cannot cause a duplicate re-fire. No-op when there is nothing to
     * fire (no dispatch, not PENDING, or the claim was lost to a concurrent cancel) —
     * idempotent, safe for both the answer path and the startup re-fire. After the
     * claim succeeds, ANY failure (HomerException or unexpected) marks the dispatch
     * FAILED immediately — the claim is never left dangling in-process; boot-time
     * [failOrphanedDispatching] only handles a hard crash mid-fire. Returns the
     * resulting Dispatch (DISPATCHED/FAILED), or null when there is nothing to fire.
     */
    suspend fun fire(
        questionId: String,
        answer: String,
    ): Dispatch? {
        val dispatch = repository.findByQuestionId(questionId) ?: return null
        if (dispatch.status != DispatchStatus.PENDING) return null
        if (!repository.claim(dispatch.id)) return null
        val now = nowSec()
        val agent =
            agentsService.agents[dispatch.agentName]
                ?: return markFailed(dispatch, "Target agent '${dispatch.agentName}' not found", now)
        val denial = hopDenial(dispatch, agent)
        if (denial != null) return markFailed(dispatch, denial, now)
        return startTarget(dispatch, agent, answer, now)
    }

    /** Why the claimed [dispatch] may no longer start [agent], else null. */
    private fun hopDenial(
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

    private suspend fun markFailed(
        dispatch: Dispatch,
        msg: String,
        now: Long,
    ): Dispatch {
        repository.markFailed(dispatch.id, msg, now)
        return dispatch.copy(status = DispatchStatus.FAILED, error = msg, dispatchedAt = now)
    }

    /** Starts the claimed [dispatch]'s target run with the operator's [answer]; any failure settles it FAILED. */
    private suspend fun startTarget(
        dispatch: Dispatch,
        agent: Agent,
        answer: String,
        now: Long,
    ): Dispatch {
        val inputParams = dispatch.params + (dispatch.answerParam to answer)
        return try {
            val processId =
                agentRunStarter.start(
                    agent,
                    emptyList(),
                    "dispatch",
                    owner = dispatch.owner,
                    extraSecretInputKeys = dispatch.secretInputKeys.orEmpty(),
                    parentProcessId = dispatch.originProcessId,
                    // [information_disclosure / CWE-200] The fired run is attributed to the
                    // dispatch's owner, so the root it inherits is gated on that owner's
                    // visibility — a foreign flow-root id reaches no row, env or span. §12.
                    rootProcessId =
                        processManager.visibleOriginRootProcessId(
                            dispatch.originProcessId,
                            dispatch.owner,
                            authorizer,
                        ),
                ) {
                    agentInputResolver.resolveFromParams(inputParams, agent) + reservedControlParams(inputParams)
                }
            repository.markDispatched(dispatch.id, processId, now)
            logger.info("Dispatch {} fired -> run {}", dispatch.id, processId)
            dispatch.copy(status = DispatchStatus.DISPATCHED, dispatchedProcessId = processId, dispatchedAt = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomerException) {
            repository.markFailed(dispatch.id, e.message ?: "Dispatch failed", now)
            logger.warn("Dispatch {} failed to fire: {}", dispatch.id, e.message)
            dispatch.copy(status = DispatchStatus.FAILED, error = e.message, dispatchedAt = now)
        } catch (e: Exception) {
            val msg = e.message ?: "Unexpected dispatch failure"
            repository.markFailed(dispatch.id, msg, now)
            logger.warn("Dispatch {} failed to fire (unexpected): {}", dispatch.id, msg, e)
            dispatch.copy(status = DispatchStatus.FAILED, error = msg, dispatchedAt = now)
        }
    }

    /**
     * Starts [agent] immediately — no operator question, no dispatch row — and returns the
     * new process id. Fire-and-forget and fully decoupled: a fresh empty invocation chain
     * (no cycle/depth linkage), exactly like [fire]. The only link back to the caller is the
     * referer ([originProcessId] + asking agent name) recorded on the run's `agent_request`
     * span. The origin process must still be live ([QuestionsClosedForProcessException]) so a
     * dead process cannot keep spawning runs, and the target must opt in via `dispatch.enabled`
     * — the same gate [create] enforces. See ADR-0022.
     */
    suspend fun dispatchNow(
        originProcessId: Int,
        agent: String,
        params: Map<String, String>,
    ): Int {
        val registration = questionsService.liveRegistration(originProcessId)
        val owner = processManager.getProcessInfo(originProcessId)?.owner
        val target = authorizer.dispatchableTarget(agentsService, owner, registration.agentName, agent)
        val refererAttributes =
            Attributes
                .builder()
                .put("referer.process.id", originProcessId.toLong())
                .put("referer.agent.name", registration.agentName)
                .build()
        val processId =
            agentRunStarter.start(
                target,
                emptyList(),
                "dispatch",
                refererAttributes,
                owner = owner,
                parentProcessId = originProcessId,
                // Same root-visibility gate as fire: the fired run carries the dispatch
                // owner's grant, not the origin process's. §12.
                rootProcessId = processManager.visibleOriginRootProcessId(originProcessId, owner, authorizer),
            ) {
                agentInputResolver.resolveFromParams(params, target)
            }
        logger.info(
            "Process {} ('{}') dispatched run {} -> agent '{}'",
            originProcessId,
            registration.agentName,
            processId,
            agent,
        )
        return processId
    }

    /**
     * The checks [dispatchNow] makes before it starts anything — live origin, dispatchable and
     * authorized target, and the [params] resolving against the target's inputs — without
     * starting it. A caller that starts several runs in one request calls this for each first, so
     * a bad later entry is refused before an earlier one started. See ADR-0026.
     */
    suspend fun validateDispatchNow(
        originProcessId: Int,
        agent: String,
        params: Map<String, String>,
    ) {
        val registration = questionsService.liveRegistration(originProcessId)
        val owner = processManager.getProcessInfo(originProcessId)?.owner
        val target = authorizer.dispatchableTarget(agentsService, owner, registration.agentName, agent)
        agentInputResolver.resolveFromParams(params, target)
    }

    suspend fun get(id: Int): Dispatch? = repository.get(id)

    suspend fun page(
        status: DispatchStatus?,
        agentName: String?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
    ): RecordPage<Dispatch> = repository.page(status, agentName, visibleOwner, visibleAgents, offset, limit)

    suspend fun findByQuestionIds(ids: Collection<String>): Map<String, Dispatch> = repository.findByQuestionIds(ids)

    suspend fun cancel(id: Int) {
        // Pre-read to map absent→404 vs wrong-status→409 (cancelWithQuestion's single
        // null return can't distinguish them). cancelWithQuestion re-checks PENDING
        // atomically, so a concurrent claim racing this read still resolves correctly.
        val existing = repository.get(id) ?: throw DispatchNotFoundException(id)
        if (existing.status != DispatchStatus.PENDING) {
            throw DispatchNotPendingException(id, existing.status.name)
        }
        val cancelled =
            repository.cancelWithQuestion(id)
                ?: throw DispatchNotPendingException(id, repository.get(id)?.status?.name ?: existing.status.name)
        broadcaster.emit(ProcessEvent.QuestionExpired(cancelled.originProcessId, cancelled.questionId))
    }

    suspend fun sweepExpired() {
        val cancelled = repository.expirePending(nowSec())
        cancelled.forEach { broadcaster.emit(ProcessEvent.QuestionExpired(it.originProcessId, it.questionId)) }
        if (cancelled.isNotEmpty()) {
            logger.info("Swept {} expired dispatch(es)", cancelled.size)
        }
    }

    /** Deletes settled dispatches older than `settledRetentionSec`, with their questions. See ADR-0021. */
    suspend fun pruneSettled() {
        val pruned = repository.pruneSettled(nowSec() - config.settledRetentionSec)
        if (pruned > 0) {
            logger.info("Pruned {} settled dispatch(es) older than {} s", pruned, config.settledRetentionSec)
        }
    }

    /** Fails dispatches left DISPATCHING by a restart (avoids duplicate re-fire). Boot only. */
    suspend fun failOrphanedDispatching() {
        val orphaned = repository.failOrphanedDispatching(nowSec())
        if (orphaned.isNotEmpty()) {
            logger.warn("Failed {} dispatch(es) interrupted mid-dispatch by restart", orphaned.size)
        }
    }

    suspend fun refirePendingAnswered() {
        for ((dispatch, answer) in repository.listPendingAnswered()) {
            if (answer.isBlank()) continue
            logger.info("Re-firing dispatch {} answered before restart", dispatch.id)
            fire(dispatch.questionId, answer)
        }
    }
}
