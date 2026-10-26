package homer.runner

import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.RegisterInstanceRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class RunnerOptions(
    val name: String,
    val tags: List<String>,
    val concurrency: Int,
    val root: Path,
    val drainTimeout: Duration,
    val version: String = RunnerAgent.VERSION,
)

/** Homer answered the registration with a 4xx: the configuration is wrong, retrying cannot help. */
class RegistrationRejectedException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
) {
    fun delayFor(attempt: Int): Duration = min(maxMs, baseMs shl min(attempt, 5)).milliseconds
}

/**
 * The instance lifecycle: register (retrying connection errors and 5xx, failing
 * on 4xx), run `concurrency` claim loops, hand each claim to a [SessionWorker],
 * and on [drain] stop claiming, deregister and give in-flight sessions
 * `drainTimeout` to finish. A `404` on `claim` means Homer dropped the instance
 * (heartbeat expiry); the agent registers again under a new id. A host that fails
 * [healthCheck] never registers or, once running, stops claiming, drains and ends [run]
 * with [RunnerUnhealthyException].
 */
class RunnerAgent(
    private val api: RunnerApi,
    private val options: RunnerOptions,
    private val workerFactory: (
        ClaimResponse,
        Duration,
    ) -> SessionWorker = { claim, heartbeat -> SessionWorker(api, claim, options.root, heartbeat) },
    private val backoff: Backoff = Backoff(),
    private val healthCheck: (Path) -> Unit = RunnerHealth::check,
) {
    @Volatile
    var instanceId: String? = null
        private set

    @Volatile
    private var heartbeatTimeout: Duration = 60.seconds

    private val draining = AtomicBoolean(false)
    private val drained = CompletableDeferred<Unit>()
    private val active = AtomicInteger(0)
    private val loops = CopyOnWriteArrayList<ClaimLoopHandle>()
    private val registerLock = Mutex()
    private val unhealthy = AtomicReference<RunnerUnhealthyException?>(null)

    val activeSessions: Int get() = active.get()

    suspend fun run() =
        coroutineScope {
            checkHealth()
            register()
            repeat(options.concurrency) { index ->
                val flag = AtomicBoolean(false)
                loops += ClaimLoopHandle(launch { claimLoop(index, flag, this@coroutineScope) }, flag)
            }
            loops.map { it.job }.joinAll()
            if (unhealthy.get() == null) logger.info("homer-runner drained; exiting")
        }.also {
            // Outside the scope: throwing inside would cancel the drain child still deregistering.
            unhealthy.get()?.let { throw it }
        }

    private suspend fun checkHealth() = withContext(Dispatchers.IO) { healthCheck(options.root) }

    private suspend fun register() {
        var attempt = 0
        while (!draining.get()) {
            try {
                val response = api.register(RegisterInstanceRequest(options.name, options.tags, options.concurrency, options.version))
                instanceId = response.instanceId
                heartbeatTimeout = response.heartbeatTimeoutSec.seconds
                logger.info(
                    "Registered as instance {} ('{}', tags {}, concurrency {}); poll {} s, heartbeat {} s",
                    response.instanceId,
                    options.name,
                    options.tags,
                    options.concurrency,
                    response.pollSec,
                    response.heartbeatTimeoutSec,
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RunnerApiException) {
                if (e.isClientError && e.status != 429) {
                    throw RegistrationRejectedException("Homer rejected the registration: ${e.message}", e)
                }
                logger.warn("Registration failed ({}); retrying", e.message)
            } catch (e: Exception) {
                logger.warn("Cannot reach Homer ({}); retrying", e.message)
            }
            delay(backoff.delayFor(attempt++))
        }
        // Draining before ever registering (SIGTERM during the initial retry loop): leave
        // instanceId null so run()'s claim loops see it and return immediately.
    }

    private suspend fun claimLoop(
        index: Int,
        flag: AtomicBoolean,
        scope: CoroutineScope,
    ) {
        var attempt = 0
        while (!draining.get()) {
            val id = instanceId ?: return
            try {
                checkHealth()
            } catch (e: RunnerUnhealthyException) {
                // Not drain() inline: it cancels idle loop jobs, this one included.
                unhealthy.compareAndSet(null, e)
                logger.error("Host is unhealthy, no more claims: {}", e.message)
                scope.launch { drain() }
                return
            }
            val claim =
                try {
                    api.claim(id).also { attempt = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onClaimFailure(e, id, index)
                    // Bounded, like every other retry here: a stale claim() answer that keeps
                    // 404ing after a successful re-register would otherwise spin this loop with
                    // no delay at all.
                    delay(backoff.delayFor(attempt++))
                    continue
                } ?: continue
            runSession(claim, flag)
        }
    }

    /** A failed claim: a `404` means Homer dropped the instance, so register again; anything else is logged. */
    private suspend fun onClaimFailure(
        e: Exception,
        id: String,
        index: Int,
    ) {
        when {
            e is RunnerApiException && e.status == 404 -> reregister(id)
            e is RunnerApiException -> logger.warn("Claim loop {}: {}", index, e.message)
            else -> logger.warn("Claim loop {}: Homer unreachable ({})", index, e.message)
        }
    }

    /** Runs one claimed session with the loop's [flag] set; a failed session is logged and ends only itself. */
    private suspend fun runSession(
        claim: ClaimResponse,
        flag: AtomicBoolean,
    ) {
        flag.set(true)
        active.incrementAndGet()
        logger.info("Claimed process {} ('{}') as session {}", claim.processId, claim.agentName, claim.sessionId)
        try {
            workerFactory(claim, heartbeatTimeout).run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Session {} failed", claim.sessionId, e)
        } finally {
            active.decrementAndGet()
            flag.set(false)
        }
    }

    private suspend fun reregister(staleId: String) =
        registerLock.withLock {
            if (draining.get() || instanceId != staleId) return@withLock
            logger.warn("Instance {} is unknown to Homer (heartbeat expired?); registering again", staleId)
            register()
        }

    /**
     * SIGTERM path. Safe to call more than once and from a shutdown hook: a later call waits for
     * the first, so a SIGTERM during a drain a failed check started still gets `drainTimeout`.
     */
    suspend fun drain() {
        if (!draining.compareAndSet(false, true)) return drained.await()
        try {
            drainOnce()
        } finally {
            drained.complete(Unit)
        }
    }

    private suspend fun drainOnce() {
        logger.info("Draining: no new claims; {} session(s) in flight, waiting up to {}", active.get(), options.drainTimeout)
        instanceId?.let { id ->
            // Bounded independently of drainTimeout (which could be much longer, or even
            // shorter than the client's own request timeout): a hung deregister must not eat
            // into, or blow past, the budget in-flight sessions get below.
            withTimeoutOrNull(minOf(options.drainTimeout, DEREGISTER_TIMEOUT)) { deregister(id) }
        }
        // Idle loops sit in a claim long-poll; nothing to finish there. A loop whose claim
        // already returned but whose busy flag is not set yet is cancelled with that claim in
        // flight; Homer fails that run at the heartbeat TTL. Narrow window, accepted.
        loops.forEach { (job, busy) -> if (!busy.get()) job.cancel() }
        val finished = withTimeoutOrNull(options.drainTimeout) { loops.map { it.job }.joinAll() } != null
        if (!finished) {
            logger.warn(
                "Drain timeout {} elapsed with {} session(s) still running; killing them and exiting",
                options.drainTimeout,
                active.get(),
            )
            loops.forEach { it.job.cancel() }
            loops.map { it.job }.joinAll()
        }
    }

    /** One claim loop's job paired with whether it currently holds a claimed session. */
    private data class ClaimLoopHandle(
        val job: Job,
        val busy: AtomicBoolean,
    )

    /**
     * Deregisters, retrying a transport failure once within the caller's budget: at shutdown the
     * client may hand the request a pooled connection Homer already closed. An HTTP answer is not
     * retried. A failed deregister is not fatal — Homer drops the instance at its heartbeat TTL.
     */
    private suspend fun deregister(id: String) {
        repeat(DEREGISTER_ATTEMPTS) { attempt ->
            try {
                api.deregister(id)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RunnerApiException) {
                logger.warn("Deregister failed: {}", e.message)
                return
            } catch (e: Exception) {
                logger.warn("Deregister failed (attempt {} of {}): {}", attempt + 1, DEREGISTER_ATTEMPTS, e.message)
            }
        }
    }

    companion object {
        const val VERSION = "0.1.0"
        private val DEREGISTER_TIMEOUT = 5.seconds
        private const val DEREGISTER_ATTEMPTS = 2
        private val logger = LoggerFactory.getLogger(RunnerAgent::class.java)
    }
}
