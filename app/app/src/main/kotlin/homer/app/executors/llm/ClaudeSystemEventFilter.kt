package homer.app.executors.llm

import homer.app.infra.LlmOutputJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/** One configured `system` subtype that was dropped at least once, with its totals. */
data class DroppedSubtype(
    val subtype: String,
    val events: Long,
    val bytes: Long,
)

/**
 * Drops Claude `--output-format stream-json` lines that are `system` events with a
 * subtype in [subtypes]; everything else is kept. A line is parsed only when it
 * contains a configured subtype as a quoted string, so the common case costs one
 * substring scan. Counters are thread-safe: the pump thread writes, the executor
 * reads after the stream is closed. See ADR-0063.
 */
class ClaudeSystemEventFilter(
    subtypes: Set<String>,
) : LlmStdOutFilter {
    private val subtypes: Set<String> = subtypes.toSet()
    private val needles: List<String> = this.subtypes.map { "\"$it\"" }
    private val events: Map<String, AtomicLong> = this.subtypes.associateWith { AtomicLong() }
    private val bytes: Map<String, AtomicLong> = this.subtypes.associateWith { AtomicLong() }

    override fun keep(line: String): Boolean {
        if (needles.none { line.contains(it) }) return true
        val obj =
            try {
                LlmOutputJson.parseToJsonElement(line) as? JsonObject ?: return true
            } catch (_: SerializationException) {
                return true
            }
        if ((obj["type"] as? JsonPrimitive)?.content != "system") return true
        val subtype = (obj["subtype"] as? JsonPrimitive)?.content ?: return true
        val count = events[subtype] ?: return true
        count.incrementAndGet()
        bytes.getValue(subtype).addAndGet(line.toByteArray(Charsets.UTF_8).size + 1L)
        return false
    }

    override fun dropped(): List<DroppedSubtype> =
        subtypes
            .map { DroppedSubtype(it, events.getValue(it).get(), bytes.getValue(it).get()) }
            .filter { it.events > 0 }
}
