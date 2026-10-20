package homer.app

import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.Runner
import homer.app.runners.RunnerRegistry
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the shutdown drain semantics: when an in-flight
 * ProcessExecutor.launch{} job is running and ApplicationStopping
 * fires, the engine waits for that job to finish (up to the configured
 * timeout) before the JVM exits. Audit P0 #3.
 *
 * This test exercises the contract directly without spinning up a
 * full Ktor server — we construct the applicationScope ourselves,
 * launch a deferred-gated coroutine into it, then run the same
 * `runBlocking { withTimeoutOrNull(...) { children.joinAll() } }`
 * sequence the production Application.kt uses. We deliberately run
 * against real wall time (not the kotlinx.coroutines.test virtual
 * clock) because the production drain calls `runBlocking` inside the
 * Ktor `ApplicationStopping` event hook — there is no virtual-time
 * harness around that call site, so the contract must hold against
 * real `Dispatchers.Default` delays.
 */
class ShutdownDrainTest {
    @Test
    fun `drain waits for in-flight jobs before timing out`() =
        runBlocking {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val started = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()

            applicationScope.launch {
                started.complete(Unit)
                delay(200)
                finished.complete(Unit)
            }
            started.await()

            val children =
                applicationScope.coroutineContext.job.children
                    .toList()
            val drained =
                withTimeoutOrNull(5.seconds) {
                    children.joinAll()
                    true
                }
            assertTrue(drained == true, "Drain should complete within timeout")
            assertTrue(finished.isCompleted, "In-flight job must have finished before drain returned")
        }

    @Test
    fun `drain reports timeout when jobs exceed budget`() =
        runBlocking {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val started = CompletableDeferred<Unit>()

            applicationScope.launch {
                started.complete(Unit)
                // Far longer than the drain budget below.
                delay(10_000)
            }
            started.await()

            val children =
                applicationScope.coroutineContext.job.children
                    .toList()
            val drained =
                withTimeoutOrNull(100.milliseconds) {
                    children.joinAll()
                    true
                }
            assertFalse(drained == true, "Drain should report timeout, not success")

            // Clean up so the test runner doesn't leak the long-delaying job
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

        drainInFlightJobsThenCloseSessions(applicationScope, registry, 100.milliseconds)

        verify(exactly = 1) { session.close() }

        // Clean up so the test runner doesn't leak the long-delaying job
        applicationScope.coroutineContext.job.cancel()
    }

    @Test
    fun `drain returns promptly when the runner registry's consumers were stopped first`() {
        // The claim-queue consumer's `while (isActive)` loop never completes on its
        // own — without RunnerRegistry.stop(), this drain would always burn the
        // full budget below and log a misleading "Drain timed out". See ADR-0066.
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = RunnerRegistry(listOf(stubRunner("local")))
        registry.start(applicationScope)
        registry.stop()

        val activeSessions = ActiveSessionsRegistry()
        val start = System.nanoTime()
        drainInFlightJobsThenCloseSessions(applicationScope, activeSessions, 5.seconds)
        val elapsed = (System.nanoTime() - start).nanoseconds

        assertTrue(
            elapsed < 1.seconds,
            "Drain should return well under the 5s timeout once consumers are stopped, took $elapsed",
        )

        applicationScope.coroutineContext.job.cancel()
    }

    private fun stubRunner(alias: String): Runner =
        object : Runner {
            override val alias: String = alias
            override val isRemote: Boolean = false
            override val runnerEnv: Map<String, String> = emptyMap()
            override val sysEnvBackedEnvNames: Set<String> = emptySet()

            override suspend fun openSession(params: RunnerSessionParams): RunnerSession = error("not used")
        }
}
