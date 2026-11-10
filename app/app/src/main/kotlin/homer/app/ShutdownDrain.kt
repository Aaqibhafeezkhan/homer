package homer.app

import homer.app.runners.ActiveSessionsRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Same category as the rest of the root package, so the drain's lines stay where operators look for them. */
private val logger = LoggerFactory.getLogger("homer.app.Application")

/**
 * Shutdown drain: join the application scope's run jobs, bounded by [drainTimeout], then close
 * whatever runner sessions remain. Without the drain, k8s rolling updates or a SIGTERM during
 * local dev cancel mid-flight Claude runs, leaving partial artifacts and incurring billed cost
 * without a result.
 *
 * The scope is **re-read** after every join rather than snapshotted once: a run that finishes
 * inside the window starts another one — a `!<agent>` hop's child, a dispatch, a continuation
 * fired by the run that just ended — and a single snapshot leaves those to be killed at exit and
 * found `UNKNOWN` at the next boot. [excluded] holds the runner-consumer loops, which never
 * complete on their own and must keep claiming queued runs until the drain ends.
 * See ADR-0101.
 */
internal fun drainInFlightJobsThenCloseSessions(
    applicationScope: CoroutineScope,
    activeSessions: ActiveSessionsRegistry,
    drainTimeout: Duration,
    excluded: Set<Job> = emptySet(),
) {
    val drained =
        runBlocking {
            // One deadline for the whole drain, fixed here: a per-round timeout would let a
            // chain of short-lived runs hold the shutdown open indefinitely. ADR-0047 rule 5.
            withTimeoutOrNull(drainTimeout) {
                var rounds = 0
                var emptyReads = 0
                while (emptyReads < EMPTY_READS_TO_END) {
                    val children = runJobs(applicationScope, excluded)
                    if (children.isEmpty()) {
                        // A finishing run starts its child a moment later, so one empty read is
                        // not proof the scope is idle; poll once more before concluding.
                        emptyReads++
                        delay(DRAIN_POLL)
                    } else {
                        emptyReads = 0
                        rounds++
                        if (rounds == 1) {
                            logger.info("Draining {} in-flight run job(s), timeout={}s", children.size, drainTimeout.inWholeSeconds)
                        } else {
                            logger.debug("Drain round {}: {} more run job(s) to join", rounds, children.size)
                        }
                        children.joinAll()
                    }
                }
                true
            } ?: false
        }
    if (drained) {
        logger.info("Drain complete")
    } else {
        logger.warn(
            "Drain timed out after {}s with {} job(s) still running; engine will stop anyway",
            drainTimeout.inWholeSeconds,
            runJobs(applicationScope, excluded).size,
        )
    }

    // Must run AFTER the join: closing a remote session deletes the pod /
    // connection out from under a run the drain is waiting for, failing it.
    // Runs that finish inside the window release their own session in the
    // executor's finally; only what is still registered here is an orphan.
    activeSessions.closeAll()
}

/**
 * The application scope's run jobs: every child but the [excluded] runner-consumer loops, which
 * are joined by nobody. Counts and ids only — no claim payload or env value reaches a log line.
 */
private fun runJobs(
    applicationScope: CoroutineScope,
    excluded: Set<Job>,
): List<Job> =
    applicationScope.coroutineContext.job.children
        .filterNot { it in excluded }
        .toList()

/** A child started by a run that just finished lands within one poll, so two empty re-reads end the drain. */
private const val EMPTY_READS_TO_END = 2

/** Short poll between two scope re-reads, far below any sane `shutdownDrainSec`. */
private val DRAIN_POLL = 50.milliseconds
