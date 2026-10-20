package homer.app.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger

/**
 * A background loop that runs [sweep] every [intervalSec] seconds from [start] until [stop]. A
 * tick that throws is logged with [failureMessage] (one `{}` for the exception message) and the
 * loop carries on with the next one.
 */
internal abstract class PeriodicSweep(
    private val intervalSec: Int,
    private val logger: Logger,
    private val failureMessage: String,
) {
    private var job: Job? = null

    /** One tick's work. */
    protected abstract suspend fun sweep()

    @Suppress("SuspendFunSwallowedCancellation") // ensureActive rethrows stop()'s; a tick's own is a failed tick
    fun start(scope: CoroutineScope) {
        job =
            scope.launch {
                while (isActive) {
                    delay(intervalSec.toLong() * 1000L)
                    try {
                        sweep()
                    } catch (e: Exception) {
                        ensureActive()
                        logger.error(failureMessage, e.message, e)
                    }
                }
            }
    }

    fun stop() {
        job?.cancel()
    }
}
