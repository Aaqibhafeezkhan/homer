package homer.app.services

import homer.app.EnvConstants
import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.HomerException
import homer.app.exceptions.MaxLockWaitersExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.exceptions.ServerDrainingException
import homer.app.infra.AgentInputResolver
import homer.app.infra.hookControlParams
import homer.app.models.Agent
import homer.app.models.ProcessInfo
import homer.app.services.persistence.HookRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import kotlin.math.min

/** Longest `HMR_HOOK_TAGS` value Homer will build; tags past it are dropped, never truncated mid-tag. */
internal const val MAX_HOOK_TAGS_CHARS = 1024

/**
 * Fires one pending agent-level hook: claims the row (PENDING→DISPATCHING CAS) so a live
 * transition, the boot replay and the retry sweep fire it once, re-checks that the target
 * agent still exists and that the triggering run's owner may still start it, then starts the
 * target with the triggering run's `HMR_HOOK_*` metadata, and settles the row DISPATCHED,
 * deferred for a retry, or FAILED. The start's own failures are contained here; the row
 * writes around it are not, so [HookService] isolates every call into this service from the
 * status write that triggered it. See ADR-0107, ADR-0023.
 */
internal class HookFireService(
    private val repository: HookRepository,
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    private val agentRunStarter: AgentRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val processManager: ProcessManager,
    private val logger: Logger,
    private val maxChainDepth: Int,
    private val nowSec: () -> Long,
) {
    /** Fires a single [hook] recorded by a status transition. */
    suspend fun fire(hook: PendingHook) {
        if (!repository.claim(hook.id)) return
        val now = nowSec()
        // The row is durable and may be replayed after a restart, so both checks run here, on
        // the delivery, not only when it was recorded: the target agent may have been deleted
        // and the owner's grant revoked in between. A denial settles the hook and starts
        // nothing — the same fail-closed rule the dispatch hop follows. See ADR-0039, §12.
        val target = agentsService.agents[hook.targetAgentName]
        if (target == null) {
            settle(hook, "Target agent '${hook.targetAgentName}' is not loaded", now)
            return
        }
        if (!authorizer.canDispatchHop(hook.owner, hook.declaringAgent, hook.targetAgentName)) {
            settle(
                hook,
                "agent '${hook.declaringAgent}' is not authorized to start '${hook.targetAgentName}'",
                now,
            )
            return
        }
        // The triggering run's row carries both the chain bound and the HMR_HOOK_* metadata:
        // its stored HMR_HOOK_DEPTH is how many hook hops it already is, so the run this row
        // starts is one deeper. Read from the row, not kept in memory, so a restart between a
        // hook's start and its own transition cannot reset the bound. See ADR-0107.
        val run = runOrNull(hook.processId)
        val depth = hookDepthOf(run?.inputParams.orEmpty()) + 1
        if (depth > maxChainDepth) {
            // Shed, never retried: the chain bound is a property of the declarations, so the
            // next attempt would be refused for the same reason. See ADR-0107.
            repository.markDropped(
                hook.id,
                "hook chain depth $depth exceeds server.hooks.maxChainDepth ($maxChainDepth)",
                now,
            )
            logger.warn("Hook {} dropped: chain depth {} exceeds the limit {}", hook.id, depth, maxChainDepth)
            return
        }
        startTarget(hook, target, run, depth, now)
    }

    /**
     * The triggering run's row, or null when it can no longer be read — a purge or eviction
     * between the transition and this fire leaves the hook carrying only what the row held.
     * A cancellation is rethrown; every other failure is not this hook's to escalate.
     */
    private suspend fun runOrNull(processId: Int): ProcessInfo? =
        try {
            processManager.getProcessInfo(processId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Hook fire could not read run {}: {}", processId, e.message)
            null
        }

    /** Starts the target run for a claimed [hook] and settles the row. Never throws. */
    private suspend fun startTarget(
        hook: PendingHook,
        target: Agent,
        run: ProcessInfo?,
        depth: Int,
        now: Long,
    ) {
        val params = hookParams(hook, run, depth)
        try {
            val processId =
                agentRunStarter.start(
                    target,
                    // The declaring agent is the chain this fire extends, so the start
                    // preflight's cycle check refuses a hook that would re-enter it. See ADR-0008.
                    invocationChain = listOf(hook.declaringAgent),
                    source = "hook",
                    owner = hook.owner,
                    parentProcessId = hook.processId,
                    // [information_disclosure / CWE-200] The fired run is attributed to the
                    // triggering run's owner, so the flow root it inherits is gated on that
                    // owner's visibility — a foreign root id reaches no row, env or span. §12.
                    rootProcessId =
                        processManager.visibleOriginRootProcessId(
                            hook.processId,
                            hook.owner,
                            authorizer,
                        ),
                    // The origin link is server-derived, so a hook fired while its run is still
                    // CREATED/WORKING is admitted rather than parked. ADR-0092.
                    serverDerivedParent = true,
                ) {
                    // The target's own declared defaults, plus the server-minted hook metadata
                    // merged back over them: AgentInputResolver emits only declared inputs, and
                    // these names are reserved. See ADR-0107.
                    agentInputResolver.resolveFromParams(emptyMap(), target) + hookControlParams(params)
                }
            repository.markDispatched(hook.id, processId, now)
            logger.info(
                "Hook {} fired ({} of run {}) -> run {}",
                hook.id,
                hook.triggeringStatus,
                hook.processId,
                processId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomerException) {
            settleFailedFire(hook, e, now)
        } catch (e: Exception) {
            settle(hook, e.message ?: "Unexpected hook fire failure", now)
        }
    }

    /**
     * Settles a fire that threw a [HomerException]. A refusal that reflects momentary server
     * state (cooldown, cost cap, capacity, a full queue, the drain) is retried on the
     * continuation back-off, bounded by [ContinuationFireService.MAX_FIRE_ATTEMPTS]; anything
     * else fails on the first attempt, since retrying it would only burn sweep cycles.
     */
    private suspend fun settleFailedFire(
        hook: PendingHook,
        e: HomerException,
        now: Long,
    ) {
        val msg = e.message ?: "Hook fire failed"
        val attempts = hook.fireAttempts + 1
        if (!isTransient(e) || attempts >= ContinuationFireService.MAX_FIRE_ATTEMPTS) {
            val reason =
                if (isTransient(e)) {
                    "$msg (gave up after $attempts attempts)"
                } else {
                    msg
                }
            settle(hook, reason, now)
            return
        }
        val delaySec =
            min(
                ContinuationFireService.RETRY_MAX_DELAY_SEC,
                ContinuationFireService.RETRY_BASE_DELAY_SEC shl
                    (attempts - 1).coerceAtMost(ContinuationFireService.BACKOFF_SHIFT_CAP),
            )
        repository.releaseForRetry(hook.id, msg, attempts, now + delaySec)
        logger.info(
            "Hook {} fire deferred (attempt {}/{}, retry in {}s): {}",
            hook.id,
            attempts,
            ContinuationFireService.MAX_FIRE_ATTEMPTS,
            delaySec,
            msg,
        )
    }

    /** Marks a claimed [hook] FAILED with [reason] and logs it. */
    private suspend fun settle(
        hook: PendingHook,
        reason: String,
        now: Long,
    ) {
        repository.markFailed(hook.id, reason, now)
        logger.warn("Hook {} failed: {}", hook.id, reason)
    }

    /** True for start-time rejections that reflect momentary server/agent state, not this hook. */
    private fun isTransient(e: HomerException): Boolean =
        e is CooldownActiveException ||
            e is CostCapExceededException ||
            e is ProcessManagerAtCapacityException ||
            e is MaxQueuedRunsExceededException ||
            e is MaxLockWaitersExceededException ||
            // A fire refused because the drain started is released for the next boot's sweep
            // rather than settled FAILED: the status transition already happened and its hook
            // must not be lost. See ADR-0102.
            e is ServerDrainingException

    /**
     * The `HMR_HOOK_*` metadata of the run [hook] was recorded from. Only Homer-recorded
     * fields ride it — the run id, the declaring agent, the status, the depth of the run being
     * started, the stored pre-start failure reason (already the fixed-vocabulary, redacted
     * sentence, ADR-0098) and the run's subject tags. No input value, secret or otherwise, is
     * ever copied. See ADR-0107.
     */
    private fun hookParams(
        hook: PendingHook,
        run: ProcessInfo?,
        depth: Int,
    ): Map<String, String> =
        buildMap {
            put(EnvConstants.HOOK_PROCESS_ID, hook.processId.toString())
            put(EnvConstants.HOOK_AGENT_NAME, hook.declaringAgent)
            put(EnvConstants.HOOK_STATUS, hook.triggeringStatus.name)
            put(EnvConstants.HOOK_DEPTH, depth.toString())
            run?.failureReason?.let { put(EnvConstants.HOOK_FAILURE_REASON, it) }
            hookTagsParam(run?.tags.orEmpty())?.let { put(EnvConstants.HOOK_TAGS, it) }
        }
}

/** [EnvConstants.HOOK_DEPTH] of [inputParams]: how many hook hops the run carrying them already is. */
internal fun hookDepthOf(inputParams: Map<String, String>): Int = inputParams[EnvConstants.HOOK_DEPTH]?.toIntOrNull() ?: 0

/**
 * The run's subject tags joined for [EnvConstants.HOOK_TAGS], or null when there is nothing to
 * send. Control characters are dropped and the whole value is capped at [MAX_HOOK_TAGS_CHARS]
 * characters — a tag that does not fit is left out rather than cut in half, so every tag in the
 * value is one the run actually carried. Tags come from `tags.fromInputs` / `tags.runtime`
 * derivations, never from a raw input value. See ADR-0060, ADR-0107.
 */
internal fun hookTagsParam(tags: List<String>): String? {
    val kept = mutableListOf<String>()
    var length = 0
    for (tag in tags) {
        val clean = tag.filterNot { it.isISOControl() }
        if (clean.isEmpty()) continue
        if (length + clean.length + 1 > MAX_HOOK_TAGS_CHARS) break
        kept += clean
        length += clean.length + 1
    }
    return kept.takeIf { it.isNotEmpty() }?.joinToString(",")
}
