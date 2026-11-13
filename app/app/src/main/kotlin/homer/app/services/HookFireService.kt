package homer.app.services

import homer.app.EnvConstants
import homer.app.exceptions.HomerException
import homer.app.infra.AgentInputResolver
import homer.app.infra.hookControlParams
import homer.app.models.Agent
import homer.app.models.ProcessInfo
import homer.app.services.persistence.HookRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger

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
        val depth = nextDepthOrDrop(hook, run, now) ?: return
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
                    // The root derives from the row already read (`run`): a run with no root of
                    // its own is the flow root, an invisible derived root is dropped — never
                    // replaced by the run's id (ADR-0058). No second read of the triggering row.
                    rootProcessId =
                        run?.let { info ->
                            if (info.rootProcessId == null) {
                                info.id
                            } else {
                                processManager.visibleRootProcessId(hook.owner, authorizer, info.rootProcessId)
                            }
                        },
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
        if (!ContinuationFireService.isTransientFireFailure(e) || attempts >= ContinuationFireService.MAX_FIRE_ATTEMPTS) {
            val reason =
                if (ContinuationFireService.isTransientFireFailure(e)) {
                    "$msg (gave up after $attempts attempts)"
                } else {
                    msg
                }
            settle(hook, reason, now)
            return
        }
        val delaySec = ContinuationFireService.retryDelaySec(attempts)
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

    /**
     * How deep the run this fire starts will be: one more than the [EnvConstants.HOOK_DEPTH]
     * the triggering run's row stores. Fails closed — drops [hook] and returns null — when
     * the bound cannot be read off that row: the run is unreadable (purged, evicted, a read
     * error), or its stored depth is present but not a number. The stored depth is what
     * carries the chain bound across a restart, so a fire that cannot read it must not
     * restart the chain at depth 1: a chain whose middle run was purged would spawn without
     * limit. Neither state heals on a retry, so the hook is dropped, not deferred. Only a
     * readable run with no depth key counts as depth 0. See ADR-0107, §12 (CWE-400, CWE-755).
     */
    private suspend fun nextDepthOrDrop(
        hook: PendingHook,
        run: ProcessInfo?,
        now: Long,
    ): Int? {
        if (run == null) {
            return dropOverUnreadableDepth(
                hook,
                "the triggering run is no longer readable, so its hook-chain depth cannot be checked",
                now,
            )
        }
        val stored = run.inputParams[EnvConstants.HOOK_DEPTH] ?: return 1
        val current = stored.toIntOrNull()
        if (current == null) {
            return dropOverUnreadableDepth(hook, "the triggering run's stored hook-chain depth is not a number", now)
        }
        return current + 1
    }

    /** Marks [hook] dropped because its chain depth cannot be checked, logs it, and returns null. */
    private suspend fun dropOverUnreadableDepth(
        hook: PendingHook,
        reason: String,
        now: Long,
    ): Int? {
        repository.markDropped(hook.id, reason, now)
        logger.warn("Hook {} dropped: {}", hook.id, reason)
        return null
    }

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
        // The separator is charged from the second tag on: the first tag may use the whole
        // cap, since a lone tag joins to a value with no separator in it.
        val sep = if (kept.isEmpty()) 0 else 1
        if (length + sep + clean.length > MAX_HOOK_TAGS_CHARS) break
        kept += clean
        length += sep + clean.length
    }
    return kept.takeIf { it.isNotEmpty() }?.joinToString(",")
}
