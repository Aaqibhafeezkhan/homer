package homer.e2e.support

import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * Background SSE subscription buffering every received event. Open the
 * collector BEFORE triggering the action whose events you assert on —
 * the broadcaster does not replay. `: ping` keep-alive comments arrive as
 * events with a null `event` field; predicates should match on `event`.
 */
class SseCollector private constructor(
    private val scope: CoroutineScope,
    private val events: CopyOnWriteArrayList<ServerSentEvent>,
) : AutoCloseable {
    companion object {
        /** Connect to [path] (e.g. `/api/v1/stream/processes`) and start buffering. */
        suspend fun open(
            path: String,
            params: Map<String, String> = emptyMap(),
            lastEventId: String? = null,
        ): SseCollector {
            val events = CopyOnWriteArrayList<ServerSentEvent>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connected = CompletableDeferred<Unit>()
            scope.launch {
                try {
                    E2e.client.sse({
                        url("${E2e.baseUrl}$path")
                        params.forEach { (k, v) -> parameter(k, v) }
                        if (lastEventId != null) header(HttpHeaders.LastEventID, lastEventId)
                    }) {
                        connected.complete(Unit)
                        incoming.collect { events += it }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Surface connection failures to open(); mid-stream errors
                    // after a successful connect just end the buffer.
                    connected.completeExceptionally(e)
                }
            }
            connected.await()
            return SseCollector(scope, events)
        }
    }

    fun snapshot(): List<ServerSentEvent> = events.toList()

    /** Wait until an already-received or future event matches [predicate]. */
    suspend fun awaitEvent(
        description: String,
        timeoutMs: Long = 30_000,
        predicate: (ServerSentEvent) -> Boolean,
    ): ServerSentEvent {
        val deadline = System.currentTimeMillis() + timeoutMs
        var scanned = 0
        while (System.currentTimeMillis() < deadline) {
            val snap = events.toList()
            while (scanned < snap.size) {
                val event = snap[scanned]
                scanned += 1
                if (predicate(event)) return event
            }
            delay(50)
        }
        val seen = events.toList().map { "${it.event}: ${it.data}" }
        throw AssertionError("SSE event not observed within ${timeoutMs}ms: $description; received: $seen")
    }

    override fun close() {
        scope.cancel()
    }
}
