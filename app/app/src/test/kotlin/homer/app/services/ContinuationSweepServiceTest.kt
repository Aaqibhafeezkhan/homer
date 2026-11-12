package homer.app.services

import homer.app.fixtures.TestFixtures
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ContinuationSweepServiceTest {
    private val continuationService = mockk<ContinuationService>(relaxed = true)
    private val hookService = mockk<HookService>(relaxed = true)
    private val resultTokenService = mockk<ResultTokenService>(relaxed = true)

    @Test
    fun `each tick expires, retries and prunes continuations, retries and prunes hooks, then reaps tokens`() =
        runTest {
            ContinuationSweepService(
                continuationService,
                resultTokenService,
                hookService,
                intervalSec = 1,
                logger = TestFixtures.mockLogger(),
            ).start(backgroundScope)

            advanceTimeBy(1_001L)

            coVerifyOrder {
                continuationService.sweepExpired()
                continuationService.sweepRetries()
                continuationService.pruneSettled()
                hookService.sweepRetries()
                hookService.pruneSettled()
                resultTokenService.sweepExpired()
            }
        }
}
