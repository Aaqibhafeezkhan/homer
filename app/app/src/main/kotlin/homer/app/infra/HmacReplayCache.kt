package homer.app.infra

import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded, TTL-based set of recently-seen HMAC signatures for replay detection.
 *
 * A signature is remembered only for the length of its freshness window; once
 * that lapses the verifier's timestamp check rejects it anyway, so entries are
 * pruned on expiry. Single-node only (in-memory), matching the rest of Homer's
 * runtime state. See the 2026-07-12 security review (M13).
 */
class HmacReplayCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    // key -> expiry epoch millis. Only holds entries for keys the attacker cannot
    // forge (a valid MAC is required to reach here), so growth is bounded by the
    // legitimate signer's request rate over the (short) freshness window.
    private val seen = ConcurrentHashMap<String, Long>()

    /**
     * Records [key] with a lifetime of [ttlMillis] and reports whether this is
     * its first use within that window. Returns `true` on first use (caller may
     * proceed), `false` if the key was already recorded and is still live — a
     * replay.
     */
    fun firstUse(
        key: String,
        ttlMillis: Long,
    ): Boolean {
        val now = clock()
        val newExpiry = now + ttlMillis
        val existing = seen.putIfAbsent(key, newExpiry)
        if (existing == null) {
            // Newly recorded. If that pushed us over the cap, reclaim only *expired*
            // entries — never evict a live marker: dropping an unexpired signature
            // would silently re-open its replay window (an attacker could flood the
            // cache to force-evict a victim's marker, then replay the victim's
            // request). If nothing expired can be reclaimed, roll back this insert
            // and fail closed rather than weaken replay protection for someone else.
            if (seen.size > maxEntries) {
                seen.entries.removeIf { it.value <= now }
                if (seen.size > maxEntries) {
                    seen.remove(key, newExpiry)
                    return false
                }
            }
            return true
        }
        // A lingering expired entry (not yet pruned) counts as a first use.
        if (existing <= now) {
            seen[key] = newExpiry
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000
    }
}
