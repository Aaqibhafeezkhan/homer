package homer.app.services

import homer.app.models.ProcessStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

class ProcessEventBroadcaster(
    extraBufferCapacity: Int = DEFAULT_BUFFER,
) {
    private val sharedFlow =
        MutableSharedFlow<IndexedEvent>(
            replay = 0,
            extraBufferCapacity = extraBufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val seq = AtomicLong(0)
    private val droppedTotal = AtomicLong(0)

    @Volatile
    private var lastDropLogAtMs = 0L

    /** Running count of events dropped across all subscribers since startup. */
    val droppedEventCount: Long
        get() = droppedTotal.get()

    fun emit(event: ProcessEvent) {
        sharedFlow.tryEmit(IndexedEvent(seq.getAndIncrement(), event))
    }

    /**
     * A per-subscriber view of the event stream.
     *
     * The underlying [MutableSharedFlow] uses [BufferOverflow.DROP_OLDEST], so
     * when a collector falls behind, the oldest buffered events it has not yet
     * read are silently evicted. `tryEmit` cannot report this — under
     * DROP_OLDEST it always returns `true`. Drops are therefore detected here,
     * consumer-side, by watching for gaps in the monotonic sequence id: a jump
     * means this subscriber missed the events in between. Each subscriber owns
     * its own gap state, so a single slow client is the only one charged for
     * its own loss.
     *
     * Gap detection runs on the raw stream, before any downstream `filter`, so
     * legitimately filtered-out events are never mistaken for drops.
     */
    fun subscribe(): Flow<ProcessEvent> =
        flow {
            var expected = -1L
            sharedFlow.collect { indexed ->
                if (expected >= 0 && indexed.seq > expected) {
                    recordDrops(indexed.seq - expected)
                }
                expected = indexed.seq + 1
                emit(indexed.event)
            }
        }

    private fun recordDrops(count: Long) {
        val total = droppedTotal.addAndGet(count)
        val now = System.currentTimeMillis()
        if (now - lastDropLogAtMs >= DROP_LOG_WINDOW_MS) {
            lastDropLogAtMs = now
            log.warn(
                "ProcessEventBroadcaster dropped {} SSE event(s) for a slow consumer (running total {}); " +
                    "buffer capacity is {} — the consumer is not keeping up with the event rate.",
                count,
                total,
                DEFAULT_BUFFER,
            )
        }
    }

    private data class IndexedEvent(
        val seq: Long,
        val event: ProcessEvent,
    )

    companion object {
        const val DEFAULT_BUFFER = 128
        private const val DROP_LOG_WINDOW_MS = 60_000L
        private val log = LoggerFactory.getLogger(ProcessEventBroadcaster::class.java)
    }
}

sealed class ProcessEvent {
    data class StatusChanged(
        val processId: Int,
        val status: ProcessStatus,
        val agentName: String,
    ) : ProcessEvent()

    data class CurrentCommandChanged(
        val processId: Int,
        val label: String?,
        val startedAt: Long?,
    ) : ProcessEvent()

    data class AgentsReloaded(
        val added: List<String>,
        val updated: List<String>,
        val removed: List<String>,
    ) : ProcessEvent()

    data class QuestionAsked(
        val processId: Int,
        val questionId: String,
        val agentName: String,
    ) : ProcessEvent()

    data class QuestionAnswered(
        val processId: Int,
        val questionId: String,
    ) : ProcessEvent()

    data class QuestionExpired(
        val processId: Int,
        val questionId: String,
    ) : ProcessEvent()

    data class MessageQueued(
        val processId: Int,
        val messageId: String,
    ) : ProcessEvent()

    data class MessageDelivered(
        val processId: Int,
        val messageId: String,
    ) : ProcessEvent()

    data class MessageUndelivered(
        val processId: Int,
        val messageId: String,
    ) : ProcessEvent()
}
