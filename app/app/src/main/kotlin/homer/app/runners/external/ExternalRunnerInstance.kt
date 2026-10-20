package homer.app.runners.external

import homer.app.models.RunnerFilter
import homer.app.runners.queue.ClaimConsumerInfo
import java.util.concurrent.atomic.AtomicInteger

/**
 * One registered pull-mode runner instance: a claim-queue consumer whose permits
 * are its declared `concurrency`. It claims through the `claim` long-poll, so
 * [matches] also refuses while saturated or draining — the queue's `take` must
 * never hand it a request it cannot serve. See ADR-0066.
 */
class ExternalRunnerInstance(
    val id: String,
    val name: String,
    override val alias: String,
    override val tags: Set<String>,
    val declaredConcurrency: Int,
    val version: String?,
    val registeredAt: Long,
) : ClaimConsumerInfo {
    override val concurrency: Int? get() = declaredConcurrency

    private val activeSessions = AtomicInteger(0)
    private val openPolls = AtomicInteger(0)

    override val inUse: Int get() = activeSessions.get()

    @Volatile
    var lastSeenAt: Long = registeredAt

    @Volatile
    var draining: Boolean = false

    /**
     * At most `concurrency` claim polls may be open at once, counting live sessions
     * too — an open poll can turn into one more session, so a poll and a session
     * both hold a share of the same budget (§12 rule 4).
     */
    fun tryAcquirePoll(): Boolean {
        while (true) {
            val current = openPolls.get()
            if (current + activeSessions.get() >= declaredConcurrency) return false
            if (openPolls.compareAndSet(current, current + 1)) return true
        }
    }

    fun releasePoll() {
        openPolls.decrementAndGet()
    }

    fun openPolls(): Int = openPolls.get()

    fun sessionOpened() {
        activeSessions.incrementAndGet()
    }

    fun sessionClosed() {
        activeSessions.decrementAndGet()
    }

    override fun matches(filter: RunnerFilter): Boolean = !draining && activeSessions.get() < declaredConcurrency && super.matches(filter)
}
