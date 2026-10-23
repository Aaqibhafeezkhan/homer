package homer.app.services

import homer.app.exceptions.MaintenanceQueueFullException
import homer.app.exceptions.MaintenanceWaitTimeoutException
import homer.app.models.MaintenanceConfig
import homer.app.models.MaintenanceResponse
import homer.app.services.persistence.ServerMetaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * The permit a start holds between leaving the maintenance gate and its run existing, so the
 * release that follows the mode going off drains at most `releaseConcurrency` starts at a time
 * instead of resuming every parked caller at once. Releasing twice is harmless. See ADR-0092.
 */
internal fun interface MaintenanceSlot {
    fun release()
}

/**
 * Admin maintenance mode: the admission gate every non-cron agent start passes through
 * while an operator replaces Homer. The flag is durable (a `server_meta` key) and the
 * starts it holds back wait in a bounded FIFO queue, released in order once the mode goes
 * off. Cron is skipped at its own tick instead — a missed schedule is never replayed.
 * See ADR-0092 and root §12 rules 4–5.
 *
 * A queued start is only *parked* here: the caller runs its own preflight (cycle, depth,
 * cost cap, cooldown) and input resolution after [awaitRelease] returns, so a released
 * start is validated against the state in force at dequeue, not at enqueue.
 */
internal class MaintenanceModeService(
    private val repository: ServerMetaRepository,
    private val config: MaintenanceConfig,
    initialEnabled: Boolean,
    private val logger: Logger,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    /** A parked start: [released] is completed when its turn comes, [done] when it leaves the gate. */
    private class Waiter(
        val owner: String?,
        val released: CompletableDeferred<Unit> = CompletableDeferred(),
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val enabled = AtomicBoolean(initialEnabled)

    /** Epoch seconds the mode was turned on; null while off, and for a mode already on at boot. */
    private val since = AtomicReference<Long?>(null)

    private val lock = Any()
    private val queue = ArrayDeque<Waiter>()
    private val perPrincipal = HashMap<String, Int>()
    private val releaseSlots = Semaphore(config.releaseConcurrency)

    fun isEnabled(): Boolean = enabled.get()

    fun queueDepth(): Int = synchronized(lock) { queue.size }

    /** The toggle endpoint's read model: the flag, when it was turned on, and the current depth. */
    fun snapshot(): MaintenanceResponse = MaintenanceResponse(enabled = enabled.get(), since = since.get(), queued = queueDepth())

    /**
     * Persists and applies the flag. The write lands first: a failed write leaves the mode
     * where it was, so the stored state and the in-memory one never disagree.
     */
    suspend fun setEnabled(enabled: Boolean) {
        repository.writeMaintenanceMode(enabled)
        // The flag flips under the same lock [awaitRelease] enqueues under, so a start can
        // never slip into the queue after the release drain has already found it empty.
        synchronized(lock) {
            this.enabled.set(enabled)
            since.set(if (enabled) now() else null)
        }
        if (enabled) {
            logger.warn("Maintenance mode enabled: cron fires are skipped and new starts are queued")
        } else {
            logger.warn("Maintenance mode disabled: releasing {} queued start(s)", queueDepth())
            releaseQueuedStarts()
        }
    }

    /**
     * Parks the caller while the mode is on, returning once it is off and the caller's turn
     * in arrival order comes up. Throws [MaintenanceQueueFullException] when the queue or this
     * caller's share of it is at its bound, and [MaintenanceWaitTimeoutException] when the wait
     * runs out — in both cases the start never runs and is never replayed.
     *
     * The returned [MaintenanceSlot] must be released once the caller's run exists; it is what
     * holds the release from the queue to `releaseConcurrency` starts at a time.
     */
    suspend fun awaitRelease(owner: String?): MaintenanceSlot {
        if (!enabled.get()) return NO_SLOT
        val waiter = enqueue(owner) ?: return NO_SLOT
        logger.info("Maintenance mode is on: start queued (depth {})", queueDepth())
        var parked = true
        try {
            val released = withTimeoutOrNull(config.queueWaitSec.seconds) { waiter.released.await() }
            if (released == null) {
                remove(waiter)
                throw MaintenanceWaitTimeoutException(config.queueWaitSec.seconds)
            }
            parked = false
            return MaintenanceSlot { waiter.done.complete(Unit) }
        } finally {
            // A caller that timed out, was cancelled or was released by stop() must not pin a
            // release slot the drain may already have handed it — and the drain does hand one
            // out to a waiter whose caller is gone, so this has to run on cancellation too.
            // Its queue entry and per-principal count go with it: a disconnected caller is no
            // longer waiting, and a phantom entry would overstate `queued` and eat the
            // caller's share of the queue until the mode went off. A waiter the drain already
            // popped makes the removal a no-op.
            if (parked) {
                remove(waiter)
                waiter.done.complete(Unit)
            }
        }
    }

    /**
     * Fails every parked start with the wait-timeout error and stops the release drain, so a
     * shutdown does not leave callers holding a request until the drain window expires.
     */
    fun stop() {
        val parked =
            synchronized(lock) {
                val all = queue.toList()
                queue.clear()
                perPrincipal.clear()
                all
            }
        parked.forEach { it.released.completeExceptionally(MaintenanceWaitTimeoutException(config.queueWaitSec.seconds)) }
        scope.cancel()
    }

    /** Adds [owner]'s start to the tail of the queue, or returns null when the mode just went off. */
    private fun enqueue(owner: String?): Waiter? {
        val waiter = Waiter(owner)
        try {
            synchronized(lock) {
                if (!enabled.get()) return null
                if (queue.size >= config.maxQueuedStarts) {
                    throw MaintenanceQueueFullException(config.maxQueuedStarts)
                }
                val held = perPrincipal[ownerKey(owner)] ?: 0
                if (held >= config.maxQueuedStartsPerPrincipal) {
                    throw MaintenanceQueueFullException(config.maxQueuedStartsPerPrincipal)
                }
                queue.addLast(waiter)
                perPrincipal[ownerKey(owner)] = held + 1
            }
        } catch (e: MaintenanceQueueFullException) {
            // Logged outside the lock: the appenders are synchronous, and a shed flood is
            // exactly when arriving callers must not serialize behind log I/O while holding
            // the queue lock. Throwing costs nothing; the I/O is what had to leave.
            logger.warn("Maintenance start queue is full ({}); shedding the start", e.bound)
            throw e
        }
        return waiter
    }

    /** Drops a waiter whose caller left the gate (timeout or cancellation) before its turn came;
     *  a waiter the drain already popped is a no-op. */
    private fun remove(waiter: Waiter) {
        synchronized(lock) {
            if (queue.remove(waiter)) decrement(waiter.owner)
        }
    }

    /**
     * Releases parked starts in arrival order, at most [MaintenanceConfig.releaseConcurrency] of
     * them at a time, so the end of the mode does not resume every caller in one burst. Stops if
     * the mode goes on again, leaving the rest queued for the next release.
     *
     * Both the slot and the wake-up are taken **in the loop**, not by a coroutine per waiter:
     * waiters racing each other for the slot woke in whatever order their threads reached it, and
     * the queue's arrival order is the whole point. Only the wait for the released start's run to
     * exist runs off the loop, so the loop stays free to release the next one up to the bound.
     */
    private fun releaseQueuedStarts() {
        scope.launch {
            while (true) {
                val waiter = nextWaiter() ?: break
                releaseSlots.acquire()
                waiter.released.complete(Unit)
                scope.launch {
                    try {
                        // The slot is held until the released start's run exists, not merely until
                        // its caller wakes: that is what bounds the flood at the runners.
                        waiter.done.await()
                    } finally {
                        releaseSlots.release()
                    }
                }
            }
        }
    }

    /** Pops the oldest parked start, or null while the mode is on or the queue is empty. */
    private fun nextWaiter(): Waiter? =
        synchronized(lock) {
            if (enabled.get()) return null
            queue.removeFirstOrNull()?.also { decrement(it.owner) }
        }

    /** Caller holds [lock]. */
    private fun decrement(owner: String?) {
        val key = ownerKey(owner)
        val left = (perPrincipal[key] ?: 0) - 1
        if (left <= 0) perPrincipal.remove(key) else perPrincipal[key] = left
    }

    /** Callers with no principal (unsecured mode, system paths) share one per-principal bucket. */
    private fun ownerKey(owner: String?): String = owner ?: ""

    private companion object {
        /** The slot for a start the mode never parked: releasing it does nothing. */
        val NO_SLOT = MaintenanceSlot { }
    }
}

/**
 * The park-and-release protocol every start path shares: park behind the gate, run [start] once
 * the mode is off and this start's turn comes, and give the release slot back in `finally`, so a
 * cancelled caller never pins it. The caller's preflight and input resolution belong inside
 * [start], so a released start is validated against the state in force at dequeue — and the slot
 * is held until [start]'s run exists. `AgentRunStarter.start` and the direct-build start paths in
 * `routes/RunHandlers.kt` both go through this, so the discipline cannot drift between them.
 * See ADR-0092.
 */
internal suspend fun <T> MaintenanceModeService.gated(
    owner: String?,
    start: suspend () -> T,
): T {
    val slot = awaitRelease(owner)
    try {
        return start()
    } finally {
        slot.release()
    }
}
