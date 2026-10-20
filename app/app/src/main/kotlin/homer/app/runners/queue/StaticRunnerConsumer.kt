package homer.app.runners.queue

import homer.app.models.RunnerFilter
import homer.app.runners.Runner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Claim-queue consumer for one configured runner (`local`, `ssh`, `kubernetes`).
 * Holds `concurrency` permits (null = unbounded); takes the oldest matching
 * request only when it holds a free permit; opens the real session in a child
 * coroutine so a slow backend (pod start) never serialises other claims. The
 * permit is released when the run closes the session. See ADR-0066.
 */
class StaticRunnerConsumer(
    private val runner: Runner,
    override val tags: Set<String>,
    override val concurrency: Int?,
    private val queue: RunnerClaimQueue,
) : ClaimConsumerInfo {
    init {
        require(concurrency == null || concurrency >= 1) { "concurrency must be >= 1 when set; got $concurrency" }
    }

    override val alias: String get() = runner.alias

    private val permits: Semaphore? = concurrency?.let { Semaphore(it) }
    private val inUseCount = AtomicInteger(0)

    override val inUse: Int get() = inUseCount.get()

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SuspendFunSwallowedCancellation") // releases the permit and rethrows
    fun start(scope: CoroutineScope): Job {
        queue.registerConsumer(this)
        val job =
            scope.launch {
                while (isActive) {
                    permits?.acquire()
                    val request =
                        try {
                            queue.take(this@StaticRunnerConsumer)
                        } catch (e: CancellationException) {
                            permits?.release()
                            throw e
                        }
                    inUseCount.incrementAndGet()
                    // ATOMIC: a claimed request must reach serve() even if the scope was cancelled
                    // meanwhile, so it is failed rather than abandoned.
                    launch(start = CoroutineStart.ATOMIC) { serve(request) }
                }
            }
        // A stopped consumer must not be named in expiry reasons as if it could still claim.
        job.invokeOnCompletion { queue.unregisterConsumer(this) }
        return job
    }

    @Suppress("SuspendFunSwallowedCancellation") // fails the request and rethrows a cancellation
    private suspend fun serve(request: ClaimRequest) {
        val released = AtomicBoolean(false)

        fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                inUseCount.decrementAndGet()
                permits?.release()
            }
        }
        val session =
            try {
                runner.openSession(request.params)
            } catch (e: Throwable) {
                releaseOnce()
                request.fail(e)
                if (e is CancellationException) throw e
                logger.warn("Process {}: runner '{}' failed to open a session: {}", request.params.processId, alias, e.message)
                return
            }
        // A tag-filtered claim has no per-alias entry in WorkspaceConfigFactory's
        // env layering (the run's workspace env was composed before the serving
        // runner was known), so the runner's own env must reach the run through
        // the session instead. An alias-filtered claim's runner env was already
        // layered by the factory. See ADR-0066.
        val delivered =
            if (request.filter is RunnerFilter.Tags && runner.runnerEnv.isNotEmpty()) {
                TagRoutedSession(session, runner.runnerEnv)
            } else {
                session
            }
        val tracked = PermitReleasingSession(delivered) { releaseOnce() }
        if (!request.complete(tracked)) {
            // The run stopped waiting (killed or timed out) while we were opening: free the backend.
            logger.debug("Process {}: run stopped waiting before runner '{}' opened; closing session", request.params.processId, alias)
            tracked.close()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(StaticRunnerConsumer::class.java)
    }
}
