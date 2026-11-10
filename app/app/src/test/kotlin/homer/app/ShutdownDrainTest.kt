package homer.app

import homer.app.fixtures.TestFixtures
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.Runner
import homer.app.runners.RunnerRegistry
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.context.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the shutdown drain semantics: when `ApplicationStopping` fires, the engine joins the
 * application scope's run jobs within the configured timeout before the JVM exits — and keeps
 * joining the runs those runs start (a `!<agent>` hop's child, a dispatch, a continuation fired
 * by a run that just ended), which one snapshot of the scope would miss and leave `UNKNOWN` at
 * the next boot. The runner-consumer loops are excluded from what it waits for, so a run queued
 * for a static runner can still be claimed while the drain runs. See ADR-0102, ADR-0066.
 * Audit P0 #3.
 *
 * These tests exercise the production `drainInFlightJobsThenCloseSessions` directly, against
 * real wall time (not the kotlinx.coroutines.test virtual clock): the production drain calls
 * `runBlocking` inside the Ktor `ApplicationStopping` event hook, so there is no virtual-time
 * harness around that call site and the contract must hold against real `Dispatchers.Default`
 * delays.
 */
class ShutdownDrainTest {
    @Test
    fun `drain waits for in-flight jobs before returning`() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        applicationScope.launch {
            started.complete(Unit)
            delay(200)
            finished.complete(Unit)
        }
        runBlocking { started.await() }

        drainInFlightJobsThenCloseSessions(applicationScope, ActiveSessionsRegistry(), 5.seconds)

        assertTrue(finished.isCompleted, "In-flight job must have finished before drain returned")
        applicationScope.coroutineContext.job.cancel()
    }

    @Test
    fun `a child started by a finishing run is joined`() {
        // The `!<agent>` hop shape: the parent returns as soon as its child is started, so the
        // child is a fresh scope job the first read of the scope never saw. ADR-0102.
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val parentStarted = CompletableDeferred<Unit>()
        val childFinished = AtomicBoolean(false)

        applicationScope.launch {
            parentStarted.complete(Unit)
            delay(100)
            applicationScope.launch {
                delay(100)
                childFinished.set(true)
            }
        }
        runBlocking { parentStarted.await() }

        drainInFlightJobsThenCloseSessions(applicationScope, ActiveSessionsRegistry(), 5.seconds)

        assertTrue(childFinished.get(), "a child started during the drain must be waited for")
        applicationScope.coroutineContext.job.cancel()
    }

    @Test
    fun `a run fired from a hook after the join is joined`() {
        // The continuation shape: the watched run ends, a hook (the terminal-status path that
        // fires continuations) starts the successor in the scope a moment later. The drain's
        // re-read catches it instead of returning on the now-empty first read.
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hookScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watchedEnded = CompletableDeferred<Unit>()
        val firedFinished = AtomicBoolean(false)

        applicationScope.launch {
            delay(100)
            watchedEnded.complete(Unit)
        }
        hookScope.launch {
            watchedEnded.await()
            applicationScope.launch {
                delay(100)
                firedFinished.set(true)
            }
        }

        drainInFlightJobsThenCloseSessions(applicationScope, ActiveSessionsRegistry(), 5.seconds)

        assertTrue(firedFinished.get(), "a run fired by a run that ended during the drain must be waited for")
        hookScope.coroutineContext.job.cancel()
        applicationScope.coroutineContext.job.cancel()
    }

    @Test
    fun `the deadline is not reset per round`() {
        // A per-round timeout would let a chain of short-lived runs hold the shutdown open
        // indefinitely: the whole drain shares one deadline, fixed at its start. ADR-0047 rule 5.
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val successorFinished = AtomicBoolean(false)

        applicationScope.launch {
            firstStarted.complete(Unit)
            delay(300)
            applicationScope.launch {
                delay(5_000)
                successorFinished.set(true)
            }
        }
        runBlocking { firstStarted.await() }

        val start = System.nanoTime()
        drainInFlightJobsThenCloseSessions(applicationScope, ActiveSessionsRegistry(), 400.milliseconds)
        val elapsed = (System.nanoTime() - start).nanoseconds

        assertTrue(elapsed < 650.milliseconds, "the drain shares one deadline across rounds, took $elapsed")
        assertFalse(successorFinished.get(), "the successor must not have been waited for")
        applicationScope.coroutineContext.job.cancel()
    }

    @Test
    fun `a run started after the deadline is not waited for`() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val holderStarted = CompletableDeferred<Unit>()
        val lateFinished = AtomicBoolean(false)

        applicationScope.launch {
            holderStarted.complete(Unit)
            // Outlives the deadline below, and starts its successor well after it.
            delay(600)
            applicationScope.launch {
                delay(100)
                lateFinished.set(true)
            }
        }
        runBlocking { holderStarted.await() }

        val start = System.nanoTime()
        drainInFlightJobsThenCloseSessions(applicationScope, ActiveSessionsRegistry(), 150.milliseconds)
        val elapsed = (System.nanoTime() - start).nanoseconds

        assertTrue(elapsed < 450.milliseconds, "the drain must end at its deadline, took $elapsed")
        assertFalse(lateFinished.get(), "a run started after the deadline is left to the UNKNOWN reconcile")
        applicationScope.coroutineContext.job.cancel()
    }

    @Test
    fun `sessions owned by in-flight jobs stay open until the drain join completes`() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ActiveSessionsRegistry()
        val jobFinished = AtomicBoolean(false)
        val closedWhileJobRunning = AtomicBoolean(false)
        val session = mockk<RunnerSession>(relaxed = true)
        every { session.close() } answers {
            if (!jobFinished.get()) closedWhileJobRunning.set(true)
        }
        registry.add(session)

        val started = CompletableDeferred<Unit>()
        runBlocking {
            applicationScope.launch {
                started.complete(Unit)
                delay(200)
                jobFinished.set(true)
                // Mirror the executor's finally: a finishing run releases its own session.
                registry.remove(session)
                session.close()
            }
            started.await()
        }

        drainInFlightJobsThenCloseSessions(applicationScope, registry, 5.seconds)

        assertTrue(jobFinished.get(), "Drain must wait for the in-flight job")
        assertFalse(
            closedWhileJobRunning.get(),
            "Session must not be closed while its owning job is still draining",
        )
    }

    @Test
    fun `sessions surviving a drain timeout are closed as orphans`() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ActiveSessionsRegistry()
        val session = mockk<RunnerSession>(relaxed = true)
        registry.add(session)

        val started = CompletableDeferred<Unit>()
        runBlocking {
            applicationScope.launch {
                started.complete(Unit)
                // Far longer than the drain budget below.
                delay(10_000)
            }
            started.await()
        }

        drainInFlightJobsThenCloseSessions(applicationScope, registry, 200.milliseconds)

        verify(exactly = 1) { session.close() }

        // Clean up so the test runner doesn't leak the long-delaying job
        applicationScope.coroutineContext.job.cancel()
    }

    @Test
    fun `consumer jobs are excluded, so an idle drain returns well under the budget`() {
        // The consumers' `while (isActive)` loops never complete on their own: counted as run
        // jobs they would make every drain burn the full budget. They are excluded by Job
        // identity and stopped after the drain instead. See ADR-0066, ADR-0102.
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = RunnerRegistry(listOf(stubRunner("local")))
        registry.start(applicationScope)

        val start = System.nanoTime()
        drainInFlightJobsThenCloseSessions(
            applicationScope,
            ActiveSessionsRegistry(),
            5.seconds,
            registry.consumerJobs().toSet(),
        )
        val elapsed = (System.nanoTime() - start).nanoseconds

        assertTrue(
            elapsed < 1.seconds,
            "Drain should return well under the 5s timeout once the consumers are excluded, took $elapsed",
        )

        registry.stop()
        applicationScope.coroutineContext.job.cancel()
    }

    @Test
    fun `a run queued for a static runner is claimed while the drain is running`() =
        runBlocking {
            // A run queued at SIGTERM must still reach a consumer: cancelling them before the
            // drain is what made such a run burn the window and end UNKNOWN. ADR-0066, ADR-0102.
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = RunnerRegistry(listOf(stubRunner("local")))
            registry.start(applicationScope)

            // Something for the drain to join, so it is still running when the claim lands.
            val holderStarted = CompletableDeferred<Unit>()
            applicationScope.launch {
                holderStarted.complete(Unit)
                delay(1_000)
            }
            holderStarted.await()

            val drain =
                async(Dispatchers.IO) {
                    drainInFlightJobsThenCloseSessions(
                        applicationScope,
                        ActiveSessionsRegistry(),
                        5.seconds,
                        registry.consumerJobs().toSet(),
                    )
                }

            val session = withTimeout(5.seconds) { registry.forAlias("local", 5.seconds).openSession(sessionParams(1)) }

            assertNotNull(session)
            assertFalse(drain.isCompleted, "the claim must be served inside the drain window, not after it")

            drain.await()
            registry.stop()
            applicationScope.coroutineContext.job.cancel()
        }

    @Test
    fun `a claim no consumer can serve does not push the drain past its deadline`() =
        runBlocking {
            // No consumer is started for this registry, so nothing can claim the run: the drain
            // sheds it at its deadline instead of waiting out the claim TTL. §12 rule 5, ADR-0066.
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = RunnerRegistry(listOf(stubRunner("local")))
            val parkedStarted = CompletableDeferred<Unit>()
            val claimed = AtomicBoolean(false)
            val run =
                applicationScope.launch {
                    parkedStarted.complete(Unit)
                    registry.forAlias("local", 30.seconds).openSession(sessionParams(1))
                    claimed.set(true)
                }
            parkedStarted.await()
            // Let the claim land in the queue before the drain takes its first read.
            delay(50)

            val start = System.nanoTime()
            drainInFlightJobsThenCloseSessions(applicationScope, ActiveSessionsRegistry(), 200.milliseconds)
            val elapsed = (System.nanoTime() - start).nanoseconds

            assertTrue(elapsed < 1.seconds, "the drain must not wait on a run no runner can claim, took $elapsed")
            assertFalse(claimed.get(), "no consumer exists, so the claim is never served")

            run.cancel()
            applicationScope.coroutineContext.job.cancel()
        }

    private fun sessionParams(processId: Int): RunnerSessionParams =
        RunnerSessionParams(processId, "demo", TestFixtures.mockWorkspaceConfig(), emptyMap(), Context.current())

    private fun stubRunner(
        alias: String,
        open: suspend (RunnerSessionParams) -> RunnerSession = { mockk(relaxed = true) },
    ): Runner =
        object : Runner {
            override val alias: String = alias
            override val isRemote: Boolean = false
            override val runnerEnv: Map<String, String> = emptyMap()
            override val sysEnvBackedEnvNames: Set<String> = emptySet()

            override suspend fun openSession(params: RunnerSessionParams): RunnerSession = open(params)
        }
}
