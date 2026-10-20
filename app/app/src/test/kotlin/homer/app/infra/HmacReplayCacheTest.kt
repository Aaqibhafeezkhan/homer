package homer.app.infra

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacReplayCacheTest {
    @Test
    fun `first use is accepted, immediate reuse within ttl is a replay`() {
        val cache = HmacReplayCache(clock = { 1_000L })
        assertTrue(cache.firstUse("k:sig", ttlMillis = 10_000))
        assertFalse(cache.firstUse("k:sig", ttlMillis = 10_000))
    }

    @Test
    fun `distinct keys do not collide`() {
        val cache = HmacReplayCache(clock = { 1_000L })
        assertTrue(cache.firstUse("k:sigA", ttlMillis = 10_000))
        assertTrue(cache.firstUse("k:sigB", ttlMillis = 10_000))
    }

    @Test
    fun `reuse after the ttl lapses is accepted again`() {
        var now = 1_000L
        val cache = HmacReplayCache(clock = { now })
        assertTrue(cache.firstUse("k:sig", ttlMillis = 5_000))
        now = 6_001L // past expiry (1_000 + 5_000)
        assertTrue(cache.firstUse("k:sig", ttlMillis = 5_000))
    }

    @Test
    fun `stays bounded and evicts under the entry cap`() {
        var now = 0L
        val cache = HmacReplayCache(clock = { now }, maxEntries = 8)
        // Insert well past the cap with short ttls; expired entries are pruned so
        // the cache never grows unbounded.
        repeat(100) { i ->
            now = i.toLong()
            assertTrue(cache.firstUse("k:sig$i", ttlMillis = 3))
        }
        // A fresh, never-seen signature is still accepted after all that churn.
        now = 1_000L
        assertTrue(cache.firstUse("k:final", ttlMillis = 3))
    }

    @Test
    fun `does not evict a live marker under the cap and fails closed on a new signature`() {
        val cache = HmacReplayCache(clock = { 1_000L }, maxEntries = 4)
        // Fill the cache with live (unexpired) markers at a fixed time.
        repeat(4) { i -> assertTrue(cache.firstUse("victim:sig$i", ttlMillis = 10_000)) }
        // A new distinct signature cannot be recorded without evicting a live marker,
        // so it is rejected (fail closed) rather than dropping a victim's entry.
        assertFalse(cache.firstUse("attacker:flood", ttlMillis = 10_000))
        // Every victim marker survived the flood — a replay of each is still detected.
        repeat(4) { i -> assertFalse(cache.firstUse("victim:sig$i", ttlMillis = 10_000)) }
    }

    @Test
    fun `reclaims expired entries to admit a new signature at the cap`() {
        var now = 1_000L
        val cache = HmacReplayCache(clock = { now }, maxEntries = 4)
        repeat(4) { i -> assertTrue(cache.firstUse("k:sig$i", ttlMillis = 5_000)) }
        // Once the live markers expire, a new signature is admitted again (the cap
        // reclaims expired entries, it does not permanently wedge).
        now = 6_001L
        assertTrue(cache.firstUse("k:new", ttlMillis = 5_000))
    }
}
