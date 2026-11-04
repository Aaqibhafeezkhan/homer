package homer.app.runners.queue

import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/**
 * One run waiting for a session. State moves PENDING → CLAIMED (a consumer took
 * it) or PENDING → CANCELLED/EXPIRED (the run stopped waiting); the CAS is what
 * settles the race between a consumer and a timeout. A consumer that claims a
 * request (via the queue's `take`) must always eventually call [complete] or
 * [fail] — otherwise the waiting run only fails at its second claim-TTL wait,
 * not immediately. See ADR-0066.
 */
class ClaimRequest internal constructor(
    val params: RunnerSessionParams,
    val filter: RunnerFilter,
    val enqueuedAtMs: Long,
    /** Null for a run whose agent declares no `runnerAffinity`; such a request routes exactly as before. */
    val affinity: ClaimAffinity? = null,
) {
    internal enum class State { PENDING, CLAIMED, CANCELLED, EXPIRED }

    internal val deferred = CompletableDeferred<RunnerSession>()
    private val state = AtomicReference(State.PENDING)

    val isPending: Boolean get() = state.get() == State.PENDING

    internal fun tryClaim(): Boolean = state.compareAndSet(State.PENDING, State.CLAIMED)

    internal fun tryFinish(to: State): Boolean = state.compareAndSet(State.PENDING, to)

    /**
     * Hands the opened session to the waiting run. Returns false when the run
     * stopped waiting first; the caller then owns [session] and must close it.
     */
    fun complete(session: RunnerSession): Boolean = deferred.complete(session)

    /** Fails the waiting run (the consumer could not open a session). */
    fun fail(cause: Throwable) {
        deferred.completeExceptionally(cause)
    }
}

/**
 * A run's affinity inside the queue: the memory key it records a claim under, the
 * instance it prefers (resolved when the request was submitted), and how long
 * that preference orders consumers. The key's flow root is Homer-stamped run
 * state, never an agent-writable value, and the instance identity is
 * alias-qualified, so a preference can never cross a domain. See ADR-0099.
 */
class ClaimAffinity(
    val rootProcessId: Int,
    val group: String,
    /** The recorded instance, or null when the key is unknown — then this request only records. */
    val preferredInstance: String?,
    private val waitMs: Long,
) {
    /** When the preference stops ordering consumers: [enqueuedAtMs] plus the bounded wait. */
    fun deadlineMs(enqueuedAtMs: Long): Long = enqueuedAtMs + waitMs

    /** Whether the preference still orders consumers at [nowMs]; a request without one never orders anything. */
    fun isActive(
        enqueuedAtMs: Long,
        nowMs: Long,
    ): Boolean = preferredInstance != null && nowMs - enqueuedAtMs < waitMs
}
