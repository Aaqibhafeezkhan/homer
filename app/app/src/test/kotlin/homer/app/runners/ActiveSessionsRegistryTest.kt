package homer.app.runners

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ActiveSessionsRegistryTest {
    @Test
    fun `closeAll closes every tracked session`() {
        val registry = ActiveSessionsRegistry()
        val s1 = mockk<RunnerSession>(relaxed = true)
        val s2 = mockk<RunnerSession>(relaxed = true)
        registry.add(s1)
        registry.add(s2)

        registry.closeAll()

        verify(exactly = 1) { s1.close() }
        verify(exactly = 1) { s2.close() }
        assertEquals(0, registry.size())
    }

    @Test
    fun `closeAll swallows failures so one broken session does not block others`() {
        val registry = ActiveSessionsRegistry()
        val bad =
            mockk<RunnerSession>(relaxed = true) {
                io.mockk.every { close() } throws RuntimeException("boom")
            }
        val good = mockk<RunnerSession>(relaxed = true)
        registry.add(bad)
        registry.add(good)

        registry.closeAll()

        verify(exactly = 1) { good.close() }
        assertEquals(0, registry.size())
    }

    @Test
    fun `remove drops a session so it is not closed on shutdown`() {
        val registry = ActiveSessionsRegistry()
        val s = mockk<RunnerSession>(relaxed = true)
        registry.add(s)
        registry.remove(s)

        registry.closeAll()

        verify(exactly = 0) { s.close() }
    }
}
