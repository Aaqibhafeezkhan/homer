package homer.app.runners.queue

/**
 * In-memory `(flow root, group) → runner instance` map: which instance last
 * served a run of that affinity key. A run whose key has an entry prefers that
 * instance in the claim queue; an unknown key records the instance that claims
 * it. Bounded in entries (a full map evicts the least recently used one, it
 * never grows or rejects) and in age (an entry past the TTL is not used).
 * Deliberately not persisted: a restart costs the next run of a key one cold
 * start. The bounds are constants, not config. See ADR-0099, ADR-0047 rules 4–5.
 */
class RunnerAffinityMemory(
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMs: Long = TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Key(
        val rootProcessId: Int,
        val group: String,
    )

    private class Entry(
        val instance: String,
        val recordedAtMs: Long,
    )

    /** Access-ordered, so a read also refreshes recency; [entries] holds at most [maxEntries]. */
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)

    init {
        require(maxEntries >= 1) { "maxEntries must be >= 1; got $maxEntries" }
        require(ttlMs >= 1) { "ttlMs must be >= 1; got $ttlMs" }
    }

    /**
     * Records that [instance] served a run of ([rootProcessId], [group]). A full
     * map evicts its least recently used entry; nothing else is dropped.
     */
    @Synchronized
    fun record(
        rootProcessId: Int,
        group: String,
        instance: String,
    ) {
        val key = Key(rootProcessId, group)
        entries.remove(key)
        entries[key] = Entry(instance, clock())
        while (entries.size > maxEntries) {
            val oldest = entries.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /** The instance recorded for ([rootProcessId], [group]), or null when unknown or past the TTL. */
    @Synchronized
    fun preferredFor(
        rootProcessId: Int,
        group: String,
    ): String? {
        val key = Key(rootProcessId, group)
        val entry = entries[key] ?: return null
        if (clock() - entry.recordedAtMs >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.instance
    }

    /** Test-only visibility into the map's bound. */
    internal fun size(): Int = entries.size

    companion object {
        /** Entries kept across every flow and group together. */
        const val MAX_ENTRIES = 1024

        /**
         * How long a recorded preference stays usable. Long enough to cover a
         * flow's build/fix/verify rounds (a flow parks on human gates and CI
         * waits), short enough that an instance reused for something else ages
         * out. A stale entry only costs the next run a fallback.
         */
        const val TTL_MS = 6 * 60 * 60 * 1000L
    }
}
