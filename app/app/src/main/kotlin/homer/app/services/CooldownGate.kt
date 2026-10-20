package homer.app.services

import homer.app.exceptions.CooldownActiveException
import homer.app.models.Agent
import homer.app.models.Cooldown
import homer.app.services.persistence.ProcessRepository

/**
 * Enforces an agent's `cooldown`: the minimum spacing between its runs.
 *
 * Homer bounds how many runs happen at once (`lockStrategy`, `maxParallelRuns`)
 * and how much they may spend (`costLimits`), but nothing bounded how *often*
 * one could start. A run that fails before reaching Claude costs nothing and
 * holds no lock by the time the next cron tick fires, so a fast-failing agent
 * on a one-minute schedule re-runs against identical state indefinitely — with
 * whatever side effects each attempt has (a comment on an MR, a message in a
 * channel) repeated every time.
 *
 * State comes from the process table, so a cooldown survives a restart — unlike
 * the scheduler's in-memory last-run map, which only ever fed the status
 * report. It does not survive `workspace.purgeAfter` deleting the rows, but
 * that window (days) is far longer than any sensible cooldown.
 */
internal class CooldownGate(
    private val repository: ProcessRepository,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /**
     * Throws [CooldownActiveException] when [agent]'s cooldown has not elapsed. No-op when unset.
     *
     * Pre-flight only: it refuses before the run does any work, but it is *not*
     * the bound. Two concurrent starts can both pass here, so the authoritative
     * check runs inside `ProcessRepository.obtainProcessId`'s transaction, atomic
     * with the INSERT. Keep both — this one preserves the caller-facing exception
     * ordering (cost cap → cooldown → input validation) and avoids resolving
     * inputs for a start that is going to be refused. See ADR-0051.
     */
    suspend fun check(agent: Agent) {
        val cooldown = agent.cooldown ?: return
        CooldownDecision
            .evaluate(
                agentName = agent.name,
                cooldown = cooldown,
                nowSec = nowSec(),
                // Read only what the configured windows need.
                latestRunStartedAt = cooldown.minIntervalSeconds?.let { repository.latestRunStartedAt(agent.name) },
                latestFailureEndedAt = cooldown.afterFailureSeconds?.let { repository.latestFailureEndedAt(agent.name) },
            )?.let { throw it }
    }
}

/**
 * [agent] as a manual-recovery start sees it: its cooldown with `afterFailure`
 * cleared, so a person unblocking a failed run is not held back by the window
 * that exists to stop an automated repeat of a failure.
 *
 * `minInterval` is a pacing bound on every start of the agent, so it always
 * survives — and when it is unset the whole cooldown is dropped, because
 * `Cooldown`'s `init` rejects a value with neither window set. Retry, resume and
 * debug all start through this, so the rule is stated once. See ADR-0051.
 */
internal fun Agent.withoutFailureBackoff(): Agent =
    cooldown
        ?.let { cd -> cd.minInterval?.let { cd.copy(afterFailure = null) } }
        ?.let { copy(cooldown = it) }
        ?: copy(cooldown = null)

/**
 * The cooldown decision as a pure function of the configured windows and the two
 * timestamps, with no I/O of its own.
 *
 * Extracted so the pre-flight [CooldownGate] and the authoritative check inside
 * `ProcessRepository.obtainProcessId`'s transaction share one implementation and
 * cannot drift apart — a transaction cannot call the suspending gate, and a second
 * copy of the arithmetic is exactly how the two would come to disagree.
 */
internal object CooldownDecision {
    /** The refusal to throw, or null when the start is allowed. */
    fun evaluate(
        agentName: String,
        cooldown: Cooldown,
        nowSec: Long,
        latestRunStartedAt: Long?,
        latestFailureEndedAt: Long?,
    ): CooldownActiveException? {
        cooldown.afterFailureSeconds?.let { window ->
            latestFailureEndedAt?.let { failedAt ->
                val remaining = failedAt + window - nowSec
                if (remaining > 0) {
                    return CooldownActiveException(agentName, remaining, "last run failed ${nowSec - failedAt}s ago")
                }
            }
        }
        cooldown.minIntervalSeconds?.let { window ->
            latestRunStartedAt?.let { startedAt ->
                val remaining = startedAt + window - nowSec
                if (remaining > 0) {
                    return CooldownActiveException(agentName, remaining, "last run started ${nowSec - startedAt}s ago")
                }
            }
        }
        return null
    }
}
