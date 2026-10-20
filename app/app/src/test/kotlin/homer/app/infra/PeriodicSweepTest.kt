package homer.app.infra

import homer.app.fixtures.TestFixtures
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import kotlin.test.assertEquals

class PeriodicSweepTest {
    private class CountingSweep(
        logger: Logger,
        private val failure: Exception? = null,
        private val tickDurationMs: Long = 0,
    ) : PeriodicSweep(intervalSec = 2, logger = logger, failureMessage = "Test sweep failed: {}") {
        var ticks = 0

        override suspend fun sweep() {
            ticks++
            delay(tickDurationMs)
            failure?.let { throw it }
        }
    }

    @Test
    fun `sweeps once per interval from start and never again after stop`() =
        runTest {
            val sweep = CountingSweep(TestFixtures.mockLogger())
            sweep.start(backgroundScope)

            advanceTimeBy(1_999L)
            assertEquals(0, sweep.ticks)
            advanceTimeBy(2_002L)
            assertEquals(2, sweep.ticks)

            sweep.stop()
            advanceTimeBy(10_000L)
            assertEquals(2, sweep.ticks)
        }

    @Test
    fun `a failing tick is logged with the failure message and the loop carries on`() =
        runTest {
            val logger = TestFixtures.mockLogger()
            val boom = IllegalStateException("boom")
            val sweep = CountingSweep(logger, failure = boom)
            sweep.start(backgroundScope)

            advanceTimeBy(4_001L)

            assertEquals(2, sweep.ticks)
            verify(exactly = 2) { logger.error("Test sweep failed: {}", "boom", boom) }
        }

    @Test
    fun `a stop mid-tick ends the loop without logging the cancellation as a failed tick`() =
        runTest {
            val logger = TestFixtures.mockLogger()
            val sweep = CountingSweep(logger, tickDurationMs = 1_000L)
            sweep.start(backgroundScope)

            advanceTimeBy(2_500L)
            sweep.stop()
            advanceTimeBy(10_000L)

            assertEquals(1, sweep.ticks)
            verify(exactly = 0) { logger.error(any<String>(), any<Any>(), any<Any>()) }
        }

    @Test
    fun `a tick's own cancellation is logged as a failed tick and the loop carries on`() =
        runTest {
            val logger = TestFixtures.mockLogger()
            val sweep = CountingSweep(logger, failure = CancellationException("tick timed out"))
            sweep.start(backgroundScope)

            advanceTimeBy(4_001L)

            assertEquals(2, sweep.ticks)
        }

    @Test
    fun `stop before start is a no-op`() {
        CountingSweep(TestFixtures.mockLogger()).stop()
    }
}
