package homer.app.runners.external

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Ops for one external session, in order, plus the result each one is waiting
 * for. `next` is a `select` over the channel and a timeout rather than
 * `withTimeoutOrNull { receive() }`: the latter can drop an element that arrived
 * exactly as the timeout fired. See ADR-0066.
 */
class OpMailbox {
    private val ops = Channel<RunnerOp>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<OpResult>>()

    @Volatile
    private var failure: Throwable? = null

    fun submit(op: RunnerOp): CompletableDeferred<OpResult> {
        val deferred = CompletableDeferred<OpResult>()
        failure?.let {
            deferred.completeExceptionally(it)
            return deferred
        }
        pending[op.opId] = deferred
        if (ops.trySend(op).isFailure) {
            pending.remove(op.opId)
            deferred.completeExceptionally(failure ?: ExternalRunnerException("session mailbox is closed"))
        }
        return deferred
    }

    fun post(op: RunnerOp) {
        ops.trySend(op)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun next(timeout: Duration): RunnerOp? =
        select {
            ops.onReceiveCatching { it.getOrNull() }
            onTimeout(timeout.inWholeMilliseconds) { null }
        }

    fun complete(result: OpResult): Boolean = pending.remove(result.opId)?.complete(result) ?: false

    fun fail(cause: Throwable) {
        failure = cause
        ops.close()
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    fun pendingCount(): Int = pending.size
}
