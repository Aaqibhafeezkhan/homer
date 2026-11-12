package homer.app.executors

import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.exceptions.UnresolvedLockKeyPlaceholderException
import homer.app.models.RunnerFilter
import homer.app.models.truncateFailureReason

/**
 * Why a run ended FAILED before any of its commands started, ready to store: [reason] is the
 * sentence shown to a reader of the run, [holderRunId] the run that blocked it when the cause
 * names one. The holder is kept out of [reason] so the API can withhold the id from a caller
 * who may not see that run. See ADR-0058.
 */
data class PreStartFailure(
    val reason: String,
    val holderRunId: Int? = null,
)

/**
 * Maps the typed pre-start causes to a stored failure reason: the `SKIP_CURRENT` lock skip,
 * the `REJECT` parallel-run cap, the wait-queue bound, the claim timeout, the claim-queue
 * bound and the unresolved lock-key placeholder. Any other exception maps to null — it failed
 * the run while it was running, or is a cause the reader gains nothing from — and stores no
 * reason.
 *
 * The text is built from the exception's typed fields and never from its message: a message
 * can carry a runner alias (admin-only on the status API, ADR-0077), a lock key or a value
 * read from the run's inputs (§12 rule 7). See ADR-0047.
 */
object PreStartFailureReason {
    fun of(e: Exception): PreStartFailure? = describe(e)?.let { it.copy(reason = sanitize(it.reason)) }

    private fun describe(e: Exception): PreStartFailure? =
        when (e) {
            is AnotherProcessStillActive -> {
                PreStartFailure(
                    "Lock skip (lockStrategy.onCollision: SKIP_CURRENT): another run still holds this agent's lock, " +
                        "so this run never started.",
                    holderRunId = e.activeProcessId,
                )
            }

            is MaxParallelRunsExceededException -> {
                PreStartFailure(
                    "Parallel-run cap (maxParallelRuns: ${e.maxParallelRuns}, onMaxParallelRuns: REJECT): agent " +
                        "'${e.agentName}' was already at its limit, so this run never started.",
                )
            }

            is MaxQueuedRunsExceededException -> {
                PreStartFailure(
                    "Parallel-run wait queue full (maxQueuedRuns: ${e.maxQueuedRuns}): agent '${e.agentName}' " +
                        "already had that many runs waiting for a slot, so this run never started.",
                )
            }

            is RunnerClaimTimeoutException -> {
                PreStartFailure(
                    "No runner claimed this run within ${e.timeout.inWholeSeconds} s (${describeFilter(e.filter)}): " +
                        "${e.publicReason}.",
                )
            }

            is ClaimQueueFullException -> {
                PreStartFailure(
                    "Runner claim queue full (maxPendingRunnerClaims: ${e.maxPending}): that many runs were " +
                        "already waiting to be claimed, so this run never started.",
                )
            }

            is UnresolvedLockKeyPlaceholderException -> {
                PreStartFailure(
                    "Lock key ${describePlaceholders(e.placeholders)} (lockStrategy.key), so this run never started.",
                )
            }

            else -> {
                null
            }
        }

    /**
     * The unresolved placeholder names as the stored sentence names them: `placeholder {{A}}
     * has no value` for one, `placeholders {{A}}, {{B}} have no value` for several. The names
     * come from the agent definition (a `\w+` template reference), never from the rendered key
     * — which may hold an input value — and never from a value. See ADR-0098.
     */
    private fun describePlaceholders(placeholders: List<String>): String =
        if (placeholders.size == 1) {
            "placeholder {{${placeholders.single()}}} has no value"
        } else {
            "placeholders ${placeholders.joinToString(", ") { "{{$it}}" }} have no value"
        }

    /**
     * The run's runner filter without its alias: `describe()` names one, and a runner alias is
     * admin-only on the status API (ADR-0077) while this text is served to every caller who can
     * see the run. The tags of a tag filter are already public on the run (ADR-0060).
     */
    private fun describeFilter(filter: RunnerFilter): String =
        when (filter) {
            is RunnerFilter.Alias -> "pinned runner filter"
            is RunnerFilter.Tags -> "tags ${filter.tags.sorted()}"
        }

    /**
     * [text] with control characters removed and the length cap applied: the reason reaches a
     * console and a JSON field, so it carries one line and stays bounded whatever a filter's
     * tags or an agent name hold. The repository write applies the cap again. See ADR-0047.
     */
    fun sanitize(text: String): String = truncateFailureReason(text.filterNot { it.isISOControl() })
}
