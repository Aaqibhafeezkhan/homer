package homer.app.fixtures

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

class WaitingFakesTest {
    @Test
    fun `a long wait is capped at the fake wait`() {
        val took = measureTimeMillis { waitUnfinished(10, TimeUnit.SECONDS) }
        assertTrue(took in FAKE_WAIT_MS..<1_000, "took $took ms")
    }

    @Test
    fun `a short wait sleeps its own length, a zero or negative one not at all`() {
        assertTrue(measureTimeMillis { waitUnfinished(20, TimeUnit.MILLISECONDS) } >= 20)
        assertTrue(measureTimeMillis { waitUnfinished(0, TimeUnit.MILLISECONDS) } < FAKE_WAIT_MS)
        assertTrue(measureTimeMillis { waitUnfinished(-5, TimeUnit.MILLISECONDS) } < FAKE_WAIT_MS)
    }
}
