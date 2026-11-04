package homer.app.runners.queue

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunnerAffinityMemoryTest {
    private var now = 1_000L

    private fun memory(
        maxEntries: Int = 8,
        ttlMs: Long = 60_000,
    ) = RunnerAffinityMemory(maxEntries = maxEntries, ttlMs = ttlMs, clock = { now })

    @Test
    fun `an unknown key resolves to nothing and a recorded one to its instance`() {
        val memory = memory()
        assertNull(memory.preferredFor(1, "gpu"))

        memory.record(1, "gpu", "dom:X")
        assertEquals("dom:X", memory.preferredFor(1, "gpu"))
    }

    @Test
    fun `recording the same key again replaces the instance`() {
        val memory = memory()
        memory.record(1, "gpu", "dom:X")
        memory.record(1, "gpu", "dom:Y")

        assertEquals("dom:Y", memory.preferredFor(1, "gpu"))
        assertEquals(1, memory.size(), "the key must not accumulate entries")
    }

    @Test
    fun `one group under two flow roots resolves to two entries`() {
        val memory = memory()
        memory.record(1, "gpu", "dom:X")
        memory.record(2, "gpu", "dom:Y")

        assertEquals("dom:X", memory.preferredFor(1, "gpu"))
        assertEquals("dom:Y", memory.preferredFor(2, "gpu"))
        assertEquals(2, memory.size())
    }

    @Test
    fun `a full map evicts its least recently used entry and stays at the bound`() {
        val memory = memory(maxEntries = 2)
        memory.record(1, "a", "dom:X")
        memory.record(2, "b", "dom:Y")
        // A read refreshes recency, so the untouched key is the one a full map drops.
        assertEquals("dom:X", memory.preferredFor(1, "a"))
        memory.record(3, "c", "dom:Z")

        assertEquals(2, memory.size())
        assertEquals("dom:X", memory.preferredFor(1, "a"))
        assertEquals("dom:Z", memory.preferredFor(3, "c"))
        assertNull(memory.preferredFor(2, "b"))
    }

    @Test
    fun `an entry past its TTL is not returned and is dropped`() {
        val memory = memory(ttlMs = 60_000)
        memory.record(1, "gpu", "dom:X")
        now += 59_999
        assertEquals("dom:X", memory.preferredFor(1, "gpu"), "the entry is still inside its TTL")

        now += 1
        assertNull(memory.preferredFor(1, "gpu"))
        assertEquals(0, memory.size())
    }

    @Test
    fun `a recorded entry is never rejected at the bound`() {
        val memory = memory(maxEntries = 1)
        memory.record(1, "gpu", "dom:X")
        memory.record(2, "gpu", "dom:Y")

        assertEquals(1, memory.size())
        assertEquals("dom:Y", memory.preferredFor(2, "gpu"))
    }

    @Test
    fun `the bounds are enforced at construction`() {
        assertThrows<IllegalArgumentException> { RunnerAffinityMemory(maxEntries = 0) }
        assertThrows<IllegalArgumentException> { RunnerAffinityMemory(ttlMs = 0) }
    }
}
