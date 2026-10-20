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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * The one queue every run passes through. A run enqueues a [ClaimRequest] with a
 * [RunnerFilter] and waits; a consumer with a free permit takes the oldest
 * request it matches. One claim TTL covers waiting for a permit and waiting for
 * a consumer to exist. Bounded by [maxPending] (§12 rule 4). See ADR-0066.
 */
class RunnerClaimQueue(
    private val maxPending: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val pending = ArrayDeque<ClaimRequest>()
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()
    private val consumers = CopyOnWriteArrayList<ClaimConsumerInfo>()

    fun registerConsumer(info: ClaimConsumerInfo) {
        consumers.add(info)
    }

    fun unregisterConsumer(info: ClaimConsumerInfo) {
        consumers.remove(info)
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
                throw RunnerClaimTimeoutException(timeout, filter, expiryReason(filter))
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
     * cancelled at shutdown, not on any timeout here.
     */
    suspend fun take(consumer: ClaimConsumerInfo): ClaimRequest {
        while (true) {
            val waiter =
                mutex.withLock {
                    pending.removeAll { !it.isPending }
                    // tryClaim() is folded into the scan (rather than done after) so that a
                    // match which loses its CAS to a concurrent expiry/cancellation (which runs
                    // outside this mutex) is skipped in favor of the next matching request,
                    // instead of making this consumer park despite claimable work remaining.
                    val match = pending.firstOrNull { consumer.matches(it.filter) && it.tryClaim() }
                    if (match != null) {
                        pending.remove(match)
                        return match
                    }
                    CompletableDeferred<Unit>().also { waiters.add(it) }
                }
            try {
                waiter.await()
            } finally {
                // A cancelled or timed-out wait is otherwise only pruned by the next submit()'s
                // wake-and-clear — an idle server whose instances keep polling would grow this
                // list forever. NonCancellable: this cleanup must run even when the caller's own
                // cancellation is what got us here.
                withContext(NonCancellable) { mutex.withLock { waiters.remove(waiter) } }
            }
        }
    }

    /** Currently-parked `take()` calls with no match yet; test-only visibility into leak fixes. */
    internal suspend fun waiterCount(): Int = mutex.withLock { waiters.size }

    private suspend fun submit(
        params: RunnerSessionParams,
        filter: RunnerFilter,
    ): ClaimRequest =
        mutex.withLock {
            pending.removeAll { !it.isPending }
            if (pending.size >= maxPending) throw ClaimQueueFullException(maxPending)
            val request = ClaimRequest(params, filter, clock())
            pending.addLast(request)
            waiters.forEach { it.complete(Unit) }
            waiters.clear()
            request
        }

    private suspend fun remove(request: ClaimRequest) {
        withContext(NonCancellable) {
            mutex.withLock { pending.remove(request) }
        }
    }

    private fun expiryReason(filter: RunnerFilter): String {
        val matching = consumers.filter { it.matches(filter) }
        if (matching.isEmpty()) {
            return when (filter) {
                is RunnerFilter.Alias -> "runner '${filter.alias}' has no registered instances"
                is RunnerFilter.Tags -> "no registered runner matches ${filter.describe()}"
            }
        }
        return matching.joinToString(", ") {
            "runner '${it.alias}' at concurrency ${it.inUse}/${it.concurrency ?: "unbounded"}"
        }
    }
}
