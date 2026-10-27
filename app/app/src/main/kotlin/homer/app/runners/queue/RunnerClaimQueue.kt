package homer.app.runners.queue

import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * The one queue every run passes through. A run enqueues a [ClaimRequest] with a
 * [RunnerFilter] and waits; a consumer with a free permit takes the oldest
 * request it matches. One claim TTL covers waiting for a permit and waiting for
 * a consumer to exist. Bounded by [maxPending] (§12 rule 4). See ADR-0066.
 * A new request wakes one parked consumer, the matching one that claimed least
 * recently (ties: the oldest waiter), and is reserved for it. See ADR-0096.
 */
class RunnerClaimQueue(
    private val maxPending: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val pending = ArrayDeque<ClaimRequest>()
    private val waiters = mutableListOf<Waiter>()
    private val consumers = CopyOnWriteArrayList<ClaimConsumerInfo>()

    /** Request → the woken waiter it is held for; under [mutex]. Bounded by [pending]. */
    private val reservedBy = HashMap<ClaimRequest, Waiter>()

    /** Consumer → sequence number of its last claim or reservation (0 = never); one entry per registered consumer. */
    private val lastClaim = ConcurrentHashMap<ClaimConsumerInfo, Long>()
    private var claimSeq = 0L
    private var parkSeq = 0L
    private val handoffOrder = compareBy<Waiter>({ lastClaim[it.consumer] ?: 0L }, { it.order })

    private class Waiter(
        val consumer: ClaimConsumerInfo,
        val order: Long,
    ) {
        val wake = CompletableDeferred<Unit>()
    }

    fun registerConsumer(info: ClaimConsumerInfo) {
        consumers.add(info)
        lastClaim[info] = 0L
    }

    fun unregisterConsumer(info: ClaimConsumerInfo) {
        consumers.remove(info)
        lastClaim.remove(info)
    }

    fun consumers(): List<ClaimConsumerInfo> = consumers.toList()

    suspend fun pendingCount(): Int = mutex.withLock { pending.count { it.isPending } }

    /** The clock that stamps [PendingClaim.enqueuedAtMs]; ages must be measured against it. */
    fun nowMs(): Long = clock()

    /** Waiting requests, oldest first; the deque is FIFO so its order is enqueue order. */
    suspend fun pendingSnapshot(): List<PendingClaim> =
        mutex.withLock { pending.filter { it.isPending }.map { PendingClaim(it.filter, it.enqueuedAtMs) } }

    /**
     * Enqueue and wait up to [timeout] for a session. Throws [ClaimQueueFullException]
     * at once at the bound and [RunnerClaimTimeoutException] at expiry; cancelling
     * the caller withdraws the request.
     */
    @Suppress("SuspendFunSwallowedCancellation") // cleans up under NonCancellable and rethrows
    suspend fun awaitClaim(
        params: RunnerSessionParams,
        filter: RunnerFilter,
        timeout: Duration,
    ): RunnerSession {
        val request = submit(params, filter)
        try {
            val session = withTimeoutOrNull(timeout) { request.deferred.await() }
            if (session != null) return session
            if (request.tryFinish(ClaimRequest.State.EXPIRED)) {
                remove(request)
                throw RunnerClaimTimeoutException(
                    timeout,
                    filter,
                    expiryReason(filter, withAliases = true),
                    expiryReason(filter, withAliases = false),
                )
            }
            // Claimed before the deadline but the consumer is still opening the session (a
            // pod start, an ssh connect). The TTL bounds the wait for a claim, not the open;
            // this second wait only backstops a consumer that never calls complete()/fail(),
            // so the run cannot hang forever. Documented in SERVER.md "Runner claim queue".
            val late = withTimeoutOrNull(timeout) { request.deferred.await() }
            if (late != null) return late
            // Make a late complete() return false so the consumer closes what it opened; if a value
            // raced in before cancel(), nobody else owns it, so close it here.
            request.deferred.cancel()
            closeIfDelivered(request)
            throw RunnerClaimTimeoutException(timeout, filter, "claimed but no session was delivered")
        } catch (e: CancellationException) {
            request.tryFinish(ClaimRequest.State.CANCELLED)
            remove(request)
            request.deferred.cancel()
            // Two interleavings once the run stops waiting: either cancel() above ran before
            // the consumer's complete(), so the consumer sees complete() == false and closes
            // the session itself; or complete() already delivered a value and this coroutine
            // was cancelled while still suspended in await() (prompt-cancellation guarantee
            // drops that value) — in that case nobody else owns the session, so close it here.
            closeIfDelivered(request)
            throw e
        }
    }

    /** Closes the session [request] was completed with, if any: called once nobody else owns it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun closeIfDelivered(request: ClaimRequest) {
        if (request.deferred.isCompleted && request.deferred.getCompletionExceptionOrNull() == null) {
            runCatching { request.deferred.getCompleted().close() }
        }
    }

    /**
     * Take the oldest pending request [consumer] matches, suspending until one exists. Suspends
     * indefinitely on an empty/non-matching queue — relies on the caller's coroutine scope being
     * cancelled at shutdown, not on any timeout here. A parked call is woken only for a request
     * reserved for it, and passes that reservation on if it leaves without claiming. See ADR-0096.
     */
    suspend fun take(consumer: ClaimConsumerInfo): ClaimRequest {
        // The waiter this call last parked; null once it has claimed. Its reservation survives a
        // normal wake so the next scan can claim it.
        var previous: Waiter? = null
        try {
            while (true) {
                val waiter =
                    mutex.withLock {
                        // Nothing suspends after the claim: the claim route relies on it.
                        claimMatch(consumer, previous)?.let {
                            previous = null
                            return it
                        }
                        park(consumer, previous)
                    }
                previous = waiter
                waiter.wake.await()
            }
        } finally {
            // Cancelled (or timed out) while parked or before rescanning: drop the waiter and hand
            // its reservation on. NonCancellable: the caller's own cancellation got us here.
            previous?.let { withContext(NonCancellable) { mutex.withLock { abandon(it) } } }
        }
    }

    /**
     * Claims the first pending request [consumer] matches that is unreserved or reserved for
     * [previous], under [mutex]. tryClaim() is folded into the scan so a match that loses its CAS
     * to a concurrent expiry/cancellation (which runs outside the mutex) is skipped for the next.
     */
    private fun claimMatch(
        consumer: ClaimConsumerInfo,
        previous: Waiter?,
    ): ClaimRequest? {
        dropSettled()
        val match =
            pending.firstOrNull {
                reservedBy[it].let { owner -> owner == null || owner === previous } &&
                    consumer.matches(it.filter) &&
                    it.tryClaim()
            } ?: return null
        pending.remove(match)
        reservedBy.remove(match)
        lastClaim.computeIfPresent(consumer) { _, _ -> ++claimSeq }
        previous?.let { releaseReservation(it) }
        return match
    }

    /** Parks a new waiter under [mutex]; a reservation [previous] could not claim goes to the next consumer. */
    private fun park(
        consumer: ClaimConsumerInfo,
        previous: Waiter?,
    ): Waiter {
        previous?.let { releaseReservation(it) }
        return Waiter(consumer, ++parkSeq).also { waiters.add(it) }
    }

    private fun abandon(waiter: Waiter) {
        waiters.remove(waiter)
        releaseReservation(waiter)
    }

    private fun releaseReservation(waiter: Waiter) {
        val held = reservedBy.entries.firstOrNull { it.value === waiter }?.key ?: return
        reservedBy.remove(held)
        dispatch(held)
    }

    /** Wakes at most one parked waiter for [request]: the matching one that claimed least recently. */
    private fun dispatch(request: ClaimRequest) {
        if (!request.isPending || request in reservedBy) return
        val next = waiters.filter { it.consumer.matches(request.filter) }.minWithOrNull(handoffOrder) ?: return
        waiters.remove(next)
        reservedBy[request] = next
        // A reservation ranks as a claim, or one consumer's parked waiters take a whole burst.
        // computeIfPresent: never re-add an unregistered consumer's record.
        lastClaim.computeIfPresent(next.consumer) { _, _ -> ++claimSeq }
        next.wake.complete(Unit)
    }

    private fun dropSettled() {
        pending.removeAll { !it.isPending }
        reservedBy.keys.removeAll { !it.isPending }
    }

    /** Currently-parked `take()` calls with no match yet; test-only visibility into leak fixes. */
    internal suspend fun waiterCount(): Int = mutex.withLock { waiters.size }

    /** Consumers with a claim record; test-only visibility into the record map's bound. */
    internal fun claimRecordCount(): Int = lastClaim.size

    private suspend fun submit(
        params: RunnerSessionParams,
        filter: RunnerFilter,
    ): ClaimRequest =
        mutex.withLock {
            dropSettled()
            if (pending.size >= maxPending) throw ClaimQueueFullException(maxPending)
            val request = ClaimRequest(params, filter, clock())
            pending.addLast(request)
            dispatch(request)
            request
        }

    private suspend fun remove(request: ClaimRequest) {
        withContext(NonCancellable) {
            mutex.withLock {
                pending.remove(request)
                reservedBy.remove(request)
            }
        }
    }

    private fun expiryReason(
        filter: RunnerFilter,
        withAliases: Boolean,
    ): String {
        val matching = consumers.filter { it.matches(filter) }
        if (matching.isEmpty()) {
            return when (filter) {
                is RunnerFilter.Alias -> {
                    if (withAliases) {
                        "runner '${filter.alias}' has no registered instances"
                    } else {
                        "no registered runner matches it"
                    }
                }

                is RunnerFilter.Tags -> {
                    if (withAliases) {
                        "no registered runner matches ${filter.describe()}"
                    } else {
                        "no registered runner matches those tags"
                    }
                }
            }
        }
        // The alias-free rendering names the observation, not the runners: it is stored on the
        // run and served to every caller who can see it, and a runner alias is admin-only there
        // (ADR-0077). Both renderings read the same snapshot, so they cannot disagree.
        if (!withAliases) return "every matching runner was at its concurrency limit"
        return matching.joinToString(", ") {
            "runner '${it.alias}' at concurrency ${it.inUse}/${it.concurrency ?: "unbounded"}"
        }
    }
}
