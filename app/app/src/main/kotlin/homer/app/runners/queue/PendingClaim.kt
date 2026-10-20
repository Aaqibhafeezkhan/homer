package homer.app.runners.queue

import homer.app.models.RunnerFilter

/** One still-waiting claim as the operator endpoint sees it. See ADR-0066. */
data class PendingClaim(
    val filter: RunnerFilter,
    val enqueuedAtMs: Long,
)
