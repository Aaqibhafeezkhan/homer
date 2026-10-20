package homer.app.services

import homer.app.infra.PeriodicSweep
import org.slf4j.Logger

/**
 * Periodically expires PENDING dispatches past their TTL and prunes settled ones past
 * `settledRetentionSec`. See the ask-and-dispatch ADR (ADR-0021).
 */
internal class DispatchSweepService(
    private val dispatchService: DispatchService,
    intervalSec: Int,
    logger: Logger,
) : PeriodicSweep(intervalSec, logger, "Dispatch sweep failed: {}") {
    override suspend fun sweep() {
        dispatchService.sweepExpired()
        dispatchService.pruneSettled()
    }
}
