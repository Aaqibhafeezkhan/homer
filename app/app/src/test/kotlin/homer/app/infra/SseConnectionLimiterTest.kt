package homer.app.infra

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SseConnectionLimiterTest {
    @Test
    fun `allows up to the cap then rejects, and frees a slot on release`() {
        val limiter = SseConnectionLimiter(maxPerCaller = 2)
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"), "third concurrent stream is over the cap")

        limiter.release("a")
        assertTrue(limiter.tryAcquire("a"), "a released slot can be reacquired")
    }

    @Test
    fun `separate callers have separate budgets`() {
        val limiter = SseConnectionLimiter(maxPerCaller = 1)
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
        assertFalse(limiter.tryAcquire("a"))
    }

    @Test
    fun `release of an unknown key is a no-op`() {
        val limiter = SseConnectionLimiter(maxPerCaller = 1)
        limiter.release("never-acquired")
        assertTrue(limiter.tryAcquire("never-acquired"))
    }
}
