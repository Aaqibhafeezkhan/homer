package homer.app.services

import homer.app.infra.PeriodicSweep
import org.slf4j.Logger

/**
 * Periodically expires PENDING continuations past their TTL, re-fires those whose
 * transient-failure retry is due, prunes settled ones past `settledRetentionSec`, and reaps
 * expired read-by-reference result tokens. The same tick re-fires agent-level status hooks
 * whose transient-failure retry is due and prunes their settled rows. See ADR-0023, ADR-0025,
 * ADR-0107.
 */
internal class ContinuationSweepService(
    private val continuationService: ContinuationService,
    private val resultTokenService: ResultTokenService,
    private val hookService: HookService,
    intervalSec: Int,
    logger: Logger,
) : PeriodicSweep(intervalSec, logger, "Continuation sweep failed: {}") {
    override suspend fun sweep() {
        continuationService.sweepExpired()
        // After expiry, so a continuation past its TTL lapses instead of
        // burning one more fire attempt on the way out.
        continuationService.sweepRetries()
        continuationService.pruneSettled()
        hookService.sweepRetries()
        hookService.pruneSettled()
        resultTokenService.sweepExpired()
    }
}
