package homer.app.services

import homer.app.infra.PeriodicSweep
import org.slf4j.Logger

/**
 * Periodically expires PENDING continuations past their TTL, re-fires those whose
 * transient-failure retry is due, prunes settled ones past `settledRetentionSec`, and reaps
 * expired read-by-reference result tokens. See ADR-0023, ADR-0025.
 */
internal class ContinuationSweepService(
    private val continuationService: ContinuationService,
    private val resultTokenService: ResultTokenService,
    intervalSec: Int,
    logger: Logger,
) : PeriodicSweep(intervalSec, logger, "Continuation sweep failed: {}") {
    override suspend fun sweep() {
        continuationService.sweepExpired()
        // After expiry, so a continuation past its TTL lapses instead of
        // burning one more fire attempt on the way out.
        continuationService.sweepRetries()
        continuationService.pruneSettled()
        resultTokenService.sweepExpired()
    }
}
