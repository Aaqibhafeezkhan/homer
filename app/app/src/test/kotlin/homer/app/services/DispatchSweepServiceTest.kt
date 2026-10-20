package homer.app.services

import homer.app.fixtures.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DispatchSweepServiceTest {
    private val dispatchService = mockk<DispatchService>(relaxed = true)

    private fun service(intervalSec: Int = 1) =
        DispatchSweepService(
            dispatchService = dispatchService,
            intervalSec = intervalSec,
            logger = TestFixtures.mockLogger(),
        )

    @Test
    fun `loop calls sweepExpired after interval elapses and stops after cancel`() =
        runTest {
            val svc = service(intervalSec = 1)
            svc.start(backgroundScope)

            // One full interval passes → exactly one sweep
            advanceTimeBy(1_001L)
            coVerify(exactly = 1) { dispatchService.sweepExpired() }

            // Cancel the loop
            svc.stop()

            // Advance several more intervals — no additional calls expected
            advanceTimeBy(5_000L)
            coVerify(exactly = 1) { dispatchService.sweepExpired() }
        }

    @Test
    fun `loop survives a sweepExpired exception and continues on the next tick`() =
        runTest {
            coEvery { dispatchService.sweepExpired() } throws RuntimeException("boom")

            val svc = service(intervalSec = 1)
            svc.start(backgroundScope)

            // Advance past two full intervals so the loop fires at least twice,
            // proving the catch block kept the while-isActive loop alive.
            advanceTimeBy(2_500L)

            coVerify(atLeast = 2) { dispatchService.sweepExpired() }
        }

    @Test
    fun `each tick prunes settled dispatches after the expiry sweep`() =
        runTest {
            service(intervalSec = 1).start(backgroundScope)

            advanceTimeBy(1_001L)

            coVerifyOrder {
                dispatchService.sweepExpired()
                dispatchService.pruneSettled()
            }
        }
}
