package homer.runner

import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.RunnerOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The poller's failure handling, driven by a hand-moved clock and scripted `next` failures so
 * no assertion depends on timing: the heartbeat deadline trips only when the test moves the
 * clock past it.
 */
class SessionWorkerPollLoopTest {
    @TempDir
    lateinit var tmp: Path

    private val api = FakeRunnerApi()
    private val now = AtomicLong(0)
    private val heartbeat = 30.seconds
    private val pastDeadline = heartbeat.inWholeMilliseconds + 1

    private fun worker() =
        SessionWorker(api, ClaimResponse("sess", 7, "demo", listOf("linux")), tmp.resolve("root"), heartbeat) { now.get() }

    /** Runs a worker whose first `next` throws [failure]; later calls answer with a queued Close. */
    private fun runFailingOnce(failure: () -> Throwable): SessionWorker =
        runBlocking(Dispatchers.Default) {
            withTimeout(20.seconds) {
                api.nextFailure = {
                    api.nextFailure = null
                    failure()
                }
                api.ops.send(RunnerOp.Close("cl", "done"))
                worker().also { it.run() }
            }
        }

    private fun runFailingAlways(failure: () -> Throwable): SessionWorker =
        runBlocking(Dispatchers.Default) {
            withTimeout(20.seconds) {
                api.nextFailure = failure
                worker().also { it.run() }
            }
        }

    @Test
    fun `a 5xx within the heartbeat deadline is retried and the session carries on`() {
        val w = runFailingOnce { RunnerApiException(503, "bad gateway") }
        assertFalse(w.lost)
        // The poller may already be in its next poll when Close ends the session, so at least 2.
        assertTrue(api.nextCallCount.get() >= 2, "one failed poll, then the poll that delivered Close")
    }

    @Test
    fun `a 5xx past the heartbeat deadline loses the session`() {
        val w =
            runFailingAlways {
                now.set(pastDeadline)
                RunnerApiException(502, "bad gateway")
            }
        assertTrue(w.lost)
        assertEquals(1, api.nextCallCount.get())
    }

    @Test
    fun `a non-404 client error is retried even past the heartbeat deadline`() {
        val w =
            runFailingOnce {
                now.set(pastDeadline)
                RunnerApiException(400, "bad request")
            }
        assertFalse(w.lost)
        assertTrue(api.nextCallCount.get() >= 2)
    }

    @Test
    fun `a connection error is retried within the deadline and loses the session past it`() {
        assertFalse(runFailingOnce { IOException("connection refused") }.lost)

        val lost =
            runFailingAlways {
                now.set(pastDeadline)
                IOException("connection refused")
            }
        assertTrue(lost.lost)
    }

    @Test
    fun `a 404 loses the session at once, whatever the clock says`() {
        assertTrue(runFailingAlways { RunnerApiException(404, "unknown session") }.lost)
        assertEquals(1, api.nextCallCount.get())
    }

    @Test
    fun `an empty poll that returns fast is paced instead of re-polled at once`() =
        runBlocking(Dispatchers.Default) {
            withTimeout(20.seconds) {
                api.nextPollMs = 1
                val w = worker()
                val run = async { w.run() }
                while (api.nextCallCount.get() < 3) delay(10)
                api.ops.send(RunnerOp.Close("cl", "done"))
                run.await()
                assertFalse(w.lost)
            }
        }

    @Test
    fun `pollFailureVerdict maps each failure to lost or retry`() {
        val within = heartbeat.inWholeMilliseconds
        assertEquals(
            PollVerdict.Lost("Homer no longer knows session s1: gone"),
            pollFailureVerdict(RunnerApiException(404, "gone"), "s1", 0, heartbeat),
        )
        assertEquals(PollVerdict.Retry("conflict"), pollFailureVerdict(RunnerApiException(409, "conflict"), "s1", pastDeadline, heartbeat))
        assertEquals(PollVerdict.Retry("busy"), pollFailureVerdict(RunnerApiException(503, "busy"), "s1", within, heartbeat))
        assertEquals(
            PollVerdict.Lost("Homer unreachable for 30 s (busy)"),
            pollFailureVerdict(RunnerApiException(503, "busy"), "s1", pastDeadline, heartbeat),
        )
        assertEquals(PollVerdict.Retry(null), pollFailureVerdict(IOException("refused"), "s1", within, heartbeat))
        assertEquals(
            PollVerdict.Lost("Homer unreachable for 30 s (refused)"),
            pollFailureVerdict(IOException("refused"), "s1", pastDeadline, heartbeat),
        )
    }
}
