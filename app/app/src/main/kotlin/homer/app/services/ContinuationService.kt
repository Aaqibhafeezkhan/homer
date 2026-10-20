package homer.app.services

import homer.app.EnvConstants
import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.ContinuationNotFoundException
import homer.app.exceptions.ContinuationNotPendingException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.TooManyPendingContinuationsException
import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.infra.validatePayloadParam
import homer.app.models.DispatchConfig
import homer.app.models.ProcessStatus
import homer.app.models.ResultEnvParam
import homer.app.services.persistence.ContinuationRepository
import homer.app.services.persistence.RecordPage
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Status names used as the `continuations.status` column value. */
enum class ContinuationStatus { PENDING, DISPATCHING, DISPATCHED, FAILED, CANCELLED, EXPIRED }

/**
 * Continuation shape, stored in the `continuations.kind` column.
 *
 * - [SINGLE] watches one process and resumes the target once it ends (today's path).
 * - [JOIN] watches N processes (a parallel fan-out group) and resumes the target a
 *   single time after the *last* of them reaches a terminal state, carrying all N
 *   results keyed by interrupt id. See ADR-0026.
 */
enum class ContinuationKind { SINGLE, JOIN }

/**
 * One watched sub-process of a [ContinuationKind.JOIN] continuation: the process to
 * wait on, the LangGraph interrupt id its result is keyed under in the resume map, and
 * the per-sub result transformer (null = pass the raw result). See ADR-0026.
 */
data class JoinWatch(
    val watchedProcessId: Int,
    val interruptId: String,
    val resultTransformer: ResultEnvParam? = null,
)

/**
 * A registered completion continuation: the target agent to start once a watched
 * sub-process reaches a terminal state, carrying that process's result under
 * [resumeParam]. The continuation outlives both the origin and watched processes.
 * See ADR-0023.
 *
 * For [ContinuationKind.JOIN] continuations [watchedProcessId] is the sentinel `0`
 * (real process ids start at 1) so the single-watch trigger never matches them; the
 * watched set lives in [watches] instead. See ADR-0026.
 */
data class Continuation(
    val id: Int,
    val watchedProcessId: Int,
    val status: ContinuationStatus,
    val targetAgentName: String,
    val params: Map<String, String>,
    val resumeParam: String = EnvConstants.LG_RESUME,
    val resultTransformer: ResultEnvParam? = null,
    val originProcessId: Int,
    val originAgentName: String,
    val expiresAt: Long?,
    val createdAt: Long,
    val firedAt: Long? = null,
    val firedProcessId: Int? = null,
    val error: String? = null,
    // Target agent's `secret: true` envNames at registration; see Dispatch.secretInputKeys.
    val secretInputKeys: Set<String>? = null,
    // Target agent's `secret: true` paramNames at registration; see Dispatch.secretParamNames.
    val secretParamNames: Set<String>? = null,
    val kind: ContinuationKind = ContinuationKind.SINGLE,
    val watches: List<JoinWatch> = emptyList(),
    // The origin process's owner, copied in at registration (live, since the origin
    // may have finished by fire time) so the fired run inherits it. See ADR-0038.
    val owner: String? = null,
    // Fire attempts already spent on this continuation. Non-zero only for one that
    // failed transiently and was released back to PENDING for a later retry.
    val fireAttempts: Int = 0,
)

/**
 * Transform cap (4 MiB): the largest artifact a server-side JsonPath/regex transform will parse
 * (`GET /api/v1/callback/results/{token}` answers 413 above it). Shared with [ContinuationFireService]
 * so no `resultRef` is ever minted for a transformed result that the fetch could not serve.
 */
internal const val RESULT_TRANSFORM_MAX_BYTES = 4L * 1024 * 1024

@OptIn(ExperimentalTime::class)
internal class ContinuationService(
    private val repository: ContinuationRepository,
    private val questionsService: QuestionsService,
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    agentRunStarter: AgentRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val processManager: ProcessManager,
    resultTokenService: ResultTokenService,
    private val logger: Logger,
    private val config: DispatchConfig = DispatchConfig(),
    private val nowSec: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val fires =
        ContinuationFireService(
            repository,
            agentsService,
            authorizer,
            agentRunStarter,
            agentInputResolver,
            processManager,
            resultTokenService,
            logger,
            nowSec,
        )

    /**
     * Validates everything [create] checks before it inserts a row — the dispatch hop's
     * authorization, origin liveness, resumeParam (blank / key collision), dispatchable target, and
     * ttl. Callers that start a side effect (e.g. a sub-process) before registering the continuation
     * MUST call this first so a 4xx can't leave an untracked run behind. Throws the same exceptions
     * [create] would. See ADR-0023, ADR-0039.
     */
    suspend fun validate(
        originProcessId: Int,
        target: String,
        resumeParam: String,
        params: Map<String, String>,
        ttlSec: Int?,
    ) {
        val registration = questionsService.liveRegistration(originProcessId)
        val owner = processManager.getProcessInfo(originProcessId)?.owner
        if (!authorizer.canDispatchHop(owner, registration.agentName, target)) throw DispatchAgentNotFoundException(target)
        validatePayloadParam(resumeParam, "resumeParam", params)
        val agent = authorizer.dispatchableTarget(agentsService, owner, registration.agentName, target)
        agentInputResolver.validateSatisfiable(agent, params.keys + resumeParam)
        val effectiveTtl = ttlSec ?: config.defaultTtlSec
        if (effectiveTtl != null && effectiveTtl <= 0) {
            throw ValidationException("ttlSec must be > 0; got $effectiveTtl", "ttlSec")
        }
    }

    suspend fun create(
        originProcessId: Int,
        watchedProcessId: Int,
        target: String,
        params: Map<String, String>,
        resumeParam: String,
        resultTransformer: ResultEnvParam?,
        ttlSec: Int?,
    ): Int {
        val continuation =
            pendingContinuation(originProcessId, target, params, resumeParam, ttlSec)
                .copy(watchedProcessId = watchedProcessId, resultTransformer = resultTransformer)
        val id =
            repository.create(continuation, config.maxPending)
                ?: throw TooManyPendingContinuationsException(config.maxPending)
        logger.info(
            "Process {} registered continuation {} watching process {} -> agent '{}'",
            originProcessId,
            id,
            watchedProcessId,
            target,
        )
        // Race close: the watched sub-process may have reached a terminal state in the gap
        // between its start and this insert, so the live terminal hook already ran and found
        // no PENDING row. Re-check now; if it is already terminal, fire here. The claim CAS
        // makes this safe against a concurrent live hook (only one fires). See ADR-0023.
        val watchedStatus = processManager.getProcessStatus(watchedProcessId)
        if (watchedStatus in TERMINAL_STATUSES) {
            fires.fire(continuation.copy(id = id), watchedStatus)
        }
        return id
    }

    /**
     * Full pre-flight for a JOIN continuation — everything [createJoin] checks plus the
     * dispatchability and dispatch-hop authorization of every sub agent. The endpoint MUST call
     * this BEFORE starting any sub so a 4xx can't leave started subs untracked (ADR-0023's
     * validate-before-start, generalized to N). Throws the same exceptions as [validate].
     * See ADR-0026, ADR-0039.
     */
    suspend fun validateJoin(
        originProcessId: Int,
        target: String,
        subAgents: List<String>,
        resumeParam: String,
        params: Map<String, String>,
        ttlSec: Int?,
    ) {
        validate(originProcessId, target, resumeParam, params, ttlSec)
        if (subAgents.isEmpty()) {
            throw ValidationException("a join must watch at least one sub", "subs")
        }
        if (subAgents.size > config.maxJoinSubs) {
            throw ValidationException(
                "a join may fan out to at most ${config.maxJoinSubs} subs (server.dispatch.maxJoinSubs); got ${subAgents.size}",
                "subs",
            )
        }
        val registration = questionsService.liveRegistration(originProcessId)
        val owner = processManager.getProcessInfo(originProcessId)?.owner
        subAgents.forEach { authorizer.dispatchableTarget(agentsService, owner, registration.agentName, it) }
    }

    /**
     * Registers a JOIN continuation over already-started sub-processes (in [watches]) and resumes
     * [target] a single time once the last sub reaches a terminal state. Sub agents were validated
     * and started by the caller, so this re-validates only the continuation-level inputs (origin,
     * target, resumeParam, ttl). Race-closes if every sub is already terminal. See ADR-0026.
     */
    suspend fun createJoin(
        originProcessId: Int,
        watches: List<JoinWatch>,
        target: String,
        params: Map<String, String>,
        resumeParam: String,
        ttlSec: Int?,
    ): Int {
        val continuation =
            pendingContinuation(originProcessId, target, params, resumeParam, ttlSec)
                .copy(kind = ContinuationKind.JOIN, watches = watches)
        val id =
            repository.createJoin(continuation, watches, config.maxPending)
                ?: throw TooManyPendingContinuationsException(config.maxPending)
        logger.info(
            "Process {} registered join continuation {} watching {} process(es) -> agent '{}'",
            originProcessId,
            id,
            watches.size,
            target,
        )
        // Race close: every sub may have terminated before this insert, so the live terminal hooks
        // already ran and found no PENDING join. Re-check; if all are terminal, fire here. The claim
        // CAS makes this safe against a concurrent live hook (only one fires). See ADR-0026.
        val registered = continuation.copy(id = id)
        if (allWatchedTerminal(registered)) {
            fires.fireJoin(registered)
        }
        return id
    }

    /** True when every watched sub of a JOIN continuation has reached a terminal status. */
    private suspend fun allWatchedTerminal(continuation: Continuation): Boolean =
        continuation.watches.all { processManager.getProcessStatus(it.watchedProcessId) in TERMINAL_STATUSES }

    /**
     * [validate]s a registration and builds its PENDING row, not yet inserted (id 0) and watching
     * nothing (`watchedProcessId` 0): the origin's live agent and owner, with the hop authorization
     * re-checked against them, and the TTL counted from now.
     */
    private suspend fun pendingContinuation(
        originProcessId: Int,
        target: String,
        params: Map<String, String>,
        resumeParam: String,
        ttlSec: Int?,
    ): Continuation {
        validate(originProcessId, target, resumeParam, params, ttlSec)
        val registration = questionsService.liveRegistration(originProcessId)
        val owner = processManager.getProcessInfo(originProcessId)?.owner
        if (!authorizer.canDispatchHop(owner, registration.agentName, target)) throw DispatchAgentNotFoundException(target)
        val effectiveTtl = ttlSec ?: config.defaultTtlSec
        val now = nowSec()
        return Continuation(
            id = 0,
            watchedProcessId = 0,
            status = ContinuationStatus.PENDING,
            targetAgentName = target,
            // See Dispatch.secretInputKeys: the target's flags as they stand now, so a
            // later edit cannot expose a stored value when this fires. ADR-0052.
            secretInputKeys = agentsService.agents[target]?.secretEnvNames,
            // paramName counterpart — see Dispatch.secretParamNames.
            secretParamNames = agentsService.agents[target]?.secretParamNames,
            params = params,
            resumeParam = resumeParam,
            originProcessId = originProcessId,
            originAgentName = registration.agentName,
            expiresAt = effectiveTtl?.let { now + it },
            createdAt = now,
            owner = owner,
        )
    }

    /**
     * The completion trigger. Fires every PENDING continuation watching [processId] now that it
     * has reached [status] (any terminal status — the parked workflow must always wake). Each is
     * claimed (PENDING→DISPATCHING CAS) before its run starts so a crash between run-start and
     * markDispatched cannot double-fire; the claim makes this idempotent against concurrent calls
     * and boot-time replay. Never throws into the caller — every failure marks the continuation
     * FAILED and is logged, so a hook failure can never corrupt the watched process's terminal
     * status. See ADR-0023.
     */
    suspend fun onProcessTerminal(
        processId: Int,
        status: ProcessStatus,
    ) {
        val pending =
            try {
                repository.findByWatchedProcessId(processId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Continuation lookup for terminal process {} failed: {}", processId, e.message, e)
                emptyList()
            }
        for (continuation in pending) {
            fires.fire(continuation, status)
        }
        // JOIN continuations watching this process fire only once all of their watched processes are
        // terminal; the last sub to finish triggers the resume. Two subs finishing concurrently can
        // both observe "all terminal" — the claim CAS in fireJoin keeps it exactly-once. See ADR-0026.
        val joins =
            try {
                repository.findJoinsByWatchedProcessId(processId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Join lookup for terminal process {} failed: {}", processId, e.message, e)
                return
            }
        for (continuation in joins) {
            if (allWatchedTerminal(continuation)) fires.fireJoin(continuation)
        }
    }

    suspend fun get(id: Int): Continuation? = repository.get(id)

    suspend fun page(
        status: ContinuationStatus?,
        agentName: String?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
    ): RecordPage<Continuation> = repository.page(status, agentName, visibleOwner, visibleAgents, offset, limit)

    suspend fun cancel(id: Int) {
        val existing = repository.get(id) ?: throw ContinuationNotFoundException(id)
        if (existing.status != ContinuationStatus.PENDING) {
            throw ContinuationNotPendingException(id, existing.status.name)
        }
        repository.cancel(id)
            ?: throw ContinuationNotPendingException(id, repository.get(id)?.status?.name ?: existing.status.name)
    }

    suspend fun sweepExpired() {
        val expired = repository.expirePending(nowSec())
        if (expired.isNotEmpty()) {
            logger.info("Swept {} expired continuation(s)", expired.size)
        }
    }

    /** Deletes settled continuations older than `settledRetentionSec`, with their watch rows. See ADR-0023. */
    suspend fun pruneSettled() {
        val pruned = repository.pruneSettled(nowSec() - config.settledRetentionSec)
        if (pruned > 0) {
            logger.info("Pruned {} settled continuation(s) older than {} s", pruned, config.settledRetentionSec)
        }
    }

    /** Fails continuations left DISPATCHING by a restart (avoids duplicate re-fire). Boot only. */
    suspend fun failOrphanedDispatching() {
        val orphaned = repository.failOrphanedDispatching(nowSec())
        if (orphaned.isNotEmpty()) {
            logger.warn("Failed {} continuation(s) interrupted mid-dispatch by restart", orphaned.size)
        }
    }

    /**
     * Fires any PENDING continuation whose watched process was already terminal before this boot —
     * the terminal hook never ran (or its commit raced the crash), so resume here. The claim CAS
     * makes this safe to run alongside a concurrent live terminal hook. Boot only.
     */
    suspend fun refirePendingTerminal() {
        for (continuation in repository.list(ContinuationStatus.PENDING)) {
            when (continuation.kind) {
                ContinuationKind.SINGLE -> {
                    val status = processManager.getProcessStatus(continuation.watchedProcessId)
                    if (status in TERMINAL_STATUSES) {
                        logger.info(
                            "Re-firing continuation {} whose watched process {} ended before restart",
                            continuation.id,
                            continuation.watchedProcessId,
                        )
                        fires.fire(continuation, status)
                    }
                }

                ContinuationKind.JOIN -> {
                    if (allWatchedTerminal(continuation)) {
                        logger.info(
                            "Re-firing join continuation {} whose {} watched process(es) all ended before restart",
                            continuation.id,
                            continuation.watches.size,
                        )
                        fires.fireJoin(continuation)
                    }
                }
            }
        }
    }

    /**
     * Re-fires continuations whose transient failure retry is due. Driven by
     * [ContinuationSweepService] on the same interval as the TTL sweep.
     */
    suspend fun sweepRetries() {
        val due =
            try {
                repository.findDueRetries(nowSec())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Continuation retry lookup failed: {}", e.message, e)
                return
            }
        for (continuation in due) fireIfWatchedTerminal(continuation)
    }

    /** Fires [continuation] once what it watches is terminal: its one process, or every watched one for a JOIN. */
    private suspend fun fireIfWatchedTerminal(continuation: Continuation) {
        when (continuation.kind) {
            ContinuationKind.SINGLE -> {
                val status = processManager.getProcessStatus(continuation.watchedProcessId)
                if (status in TERMINAL_STATUSES) fires.fire(continuation, status)
            }

            ContinuationKind.JOIN -> {
                if (allWatchedTerminal(continuation)) fires.fireJoin(continuation)
            }
        }
    }

    private companion object {
        val TERMINAL_STATUSES =
            setOf(
                ProcessStatus.FINISHED,
                ProcessStatus.FAILED,
                ProcessStatus.KILLED,
                ProcessStatus.UNKNOWN,
            )
    }
}
