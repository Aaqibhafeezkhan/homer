package homer.runner

import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.RegisterInstanceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RunnerAgentTest {
    @TempDir
    lateinit var tmp: Path

    private val api = FakeRunnerApi()
    private val fastBackoff = Backoff(baseMs = 10, maxMs = 20)

    private fun options(
        concurrency: Int = 1,
        drain: Duration = 5.seconds,
    ) = RunnerOptions("host-1", listOf("linux"), concurrency, tmp, drain)

    /** A worker that ends when the fake delivers a Close, without touching the filesystem or processes. */
    private fun scriptedWorker(): (ClaimResponse, Duration) -> SessionWorker = { claim, hb -> SessionWorker(api, claim, tmp, hb) }

    private fun <T> live(block: suspend CoroutineScope.() -> T): T =
        runBlocking(Dispatchers.Default) { withTimeout(20.seconds) { block() } }

    @Test
    fun `a 4xx registration is fatal and a connection error is retried`() =
        live {
            api.registerAnswer = { throw RunnerApiException(422, "tags [linux] are not in allowedTags") }
            val rejected = assertThrows<RegistrationRejectedException> { RunnerAgent(api, options(), scriptedWorker(), fastBackoff).run() }
            assertTrue(rejected.message!!.contains("allowedTags"))

            var calls = 0
            api.registerAnswer = { if (calls++ < 2) throw IOException("refused") else RegisterInstanceResponse("inst-9", 30, 60) }
            val agent = RunnerAgent(api, options(), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(300)
            assertEquals("inst-9", agent.instanceId)
            assertEquals(3, calls)
            agent.drain()
            run.await()
        }

    @Test
    fun `a 429 on registration retries with backoff and eventually succeeds`() =
        live {
            var calls = 0
            api.registerAnswer = {
                if (calls++ < 2) throw RunnerApiException(429, "too many instances") else RegisterInstanceResponse("inst-9", 30, 60)
            }
            val agent = RunnerAgent(api, options(), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(300)
            assertEquals("inst-9", agent.instanceId)
            assertEquals(3, calls)
            agent.drain()
            run.await()
        }

    @Test
    fun `drain during the initial registration retry lets run complete without ever registering`() =
        live {
            api.registerAnswer = { throw IOException("refused") }
            val agent = RunnerAgent(api, options(), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(100)
            val started = System.nanoTime()
            agent.drain()
            run.await()
            assertTrue(System.nanoTime() - started < 5_000_000_000L)
            assertEquals(null, agent.instanceId)
        }

    @Test
    fun `claims are served concurrently up to the declared concurrency and drain deregisters then stops claiming`() =
        live {
            val agent = RunnerAgent(api, options(concurrency = 2), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(100)
            api.claims.send(ClaimResponse("s1", 1, "a", emptyList()))
            api.claims.send(ClaimResponse("s2", 2, "a", emptyList()))
            delay(300)
            assertEquals(2, agent.activeSessions, "both claims are in flight at once")

            val drain = async { agent.drain() }
            delay(200)
            assertEquals(listOf("inst-1"), api.deregistrations)
            api.claims.send(ClaimResponse("s3", 3, "a", emptyList()))
            api.ops.send(
                homer.app.runners.external.RunnerOp
                    .Close("c1", "done"),
            )
            api.ops.send(
                homer.app.runners.external.RunnerOp
                    .Close("c2", "done"),
            )
            drain.await()
            run.await()
            assertEquals(0, agent.activeSessions)
            assertTrue(api.claims.tryReceive().isSuccess, "the claim offered after drain began was never taken")
        }

    @Test
    fun `an unhealthy host at startup never registers`() =
        live {
            val agent =
                RunnerAgent(
                    api,
                    options(),
                    scriptedWorker(),
                    fastBackoff,
                ) { throw RunnerUnhealthyException("cannot start a process: boom") }
            assertThrows<RunnerUnhealthyException> { agent.run() }
            assertEquals(emptyList(), api.registrations.toList())
        }

    @Test
    fun `a failed check before a claim stops claiming, deregisters and ends run unhealthy`() =
        live {
            api.deregisterDelayMs = 200 // a real deregister suspends; the rethrow must not cancel it
            val checks = AtomicInteger(0)
            // passes at startup and before the first claim, fails before the second
            val agent =
                RunnerAgent(api, options(), scriptedWorker(), fastBackoff) {
                    if (checks.incrementAndGet() > 2) throw RunnerUnhealthyException("cannot start a process: boom")
                }
            api.claims.send(null) // first claim answers 204
            assertThrows<RunnerUnhealthyException> { agent.run() }
            assertEquals(1, api.claimCalls.get())
            assertEquals(listOf("inst-1"), api.deregistrations.toList())
        }

    @Test
    fun `a session in flight on another loop finishes before an unhealthy run ends`() =
        live {
            val failing = AtomicBoolean(false)
            val agent =
                RunnerAgent(api, options(concurrency = 2), scriptedWorker(), fastBackoff) {
                    if (failing.get()) throw RunnerUnhealthyException("cannot start a process: boom")
                }
            val run = async { runCatching { agent.run() } }
            delay(100)
            api.claims.send(ClaimResponse("s1", 1, "a", emptyList()))
            delay(300)
            assertEquals(1, agent.activeSessions, "one loop holds the session")

            failing.set(true) // the other loop's next check fails
            delay(300)
            assertEquals(listOf("inst-1"), api.deregistrations.toList())
            assertFalse(run.isCompleted, "run() waits for the in-flight session")

            api.ops.send(
                homer.app.runners.external.RunnerOp
                    .Close("c1", "done"),
            )
            assertTrue(run.await().exceptionOrNull() is RunnerUnhealthyException)
            assertEquals(0, agent.activeSessions)
        }

    @Test
    fun `a second drain waits for the drain a failed check started`() =
        live {
            val failing = AtomicBoolean(false)
            val agent =
                RunnerAgent(api, options(concurrency = 2), scriptedWorker(), fastBackoff) {
                    if (failing.get()) throw RunnerUnhealthyException("cannot start a process: boom")
                }
            val run = async { runCatching { agent.run() } }
            api.claims.send(ClaimResponse("s1", 1, "a", emptyList()))
            awaitUntil { agent.activeSessions == 1 }
            failing.set(true) // the other loop's next check fails and starts the drain
            awaitUntil { api.deregistrations.isNotEmpty() }

            val second = async { agent.drain() } // the shutdown hook's drain on SIGTERM
            delay(300)
            assertFalse(second.isCompleted, "a second drain returned while a session was in flight")

            api.ops.send(
                homer.app.runners.external.RunnerOp
                    .Close("c1", "done"),
            )
            second.await()
            assertEquals(0, agent.activeSessions)
            assertTrue(run.await().exceptionOrNull() is RunnerUnhealthyException)
        }

    @Test
    fun `drain retries a deregister that failed in transport once`() =
        live {
            val agent = RunnerAgent(api, options(), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(100)
            api.deregisterFailures += IOException("connection reset")

            agent.drain()
            run.await()

            assertEquals(2, api.deregisterCalls.get())
            assertEquals(listOf("inst-1"), api.deregistrations)
        }

    @Test
    fun `drain gives up on deregister after two transport failures and still exits`() =
        live {
            val agent = RunnerAgent(api, options(), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(100)
            api.deregisterFailures += IOException("connection reset")
            api.deregisterFailures += IOException("connection reset")

            agent.drain()
            run.await()

            assertEquals(2, api.deregisterCalls.get())
            assertEquals(emptyList(), api.deregistrations)
        }

    @Test
    fun `drain does not retry a deregister Homer answered with an error`() =
        live {
            val agent = RunnerAgent(api, options(), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(100)
            api.deregisterFailures += RunnerApiException(500, "boom")

            agent.drain()
            run.await()

            assertEquals(1, api.deregisterCalls.get())
        }

    @Test
    fun `drain past its timeout gives up on a stuck session`() =
        live {
            val agent = RunnerAgent(api, options(drain = 300.milliseconds), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(100)
            api.claims.send(ClaimResponse("s1", 1, "a", emptyList()))
            delay(200)
            val started = System.nanoTime()
            agent.drain()
            run.await()
            assertTrue(System.nanoTime() - started < 5_000_000_000L)
        }

    @Test
    fun `a 404 on claim means Homer dropped the instance so the agent registers again`() =
        live {
            val registrations = AtomicInteger()
            api.registerAnswer = { RegisterInstanceResponse("inst-${registrations.incrementAndGet()}", 30, 60) }
            api.claimFailure = { RunnerApiException(404, "unknown instance") }
            val agent = RunnerAgent(api, options(), scriptedWorker(), fastBackoff)
            val run = async { agent.run() }
            delay(200)
            api.claimFailure = null
            delay(100)
            assertTrue(registrations.get() >= 2, "re-registered after the 404")
            assertEquals("inst-${registrations.get()}", agent.instanceId)
            agent.drain()
            run.await()
        }

    /** Suspends until [condition] holds; the test's own timeout bounds the wait. */
    private suspend fun awaitUntil(condition: () -> Boolean) {
        while (!condition()) delay(5)
    }

    @Test
    fun `a failing claim is retried with backoff whatever the failure, without registering again`() =
        live {
            for (failure in listOf(RunnerApiException(500, "server error"), IOException("connection refused"))) {
                val fake = FakeRunnerApi()
                fake.claimFailure = { failure }
                val agent = RunnerAgent(fake, options(), scriptedWorker(), fastBackoff)
                val run = async { agent.run() }
                awaitUntil { fake.claimCalls.get() >= 3 }

                assertEquals(1, fake.registrations.size, "only a 404 registers again ($failure)")
                assertEquals("inst-1", agent.instanceId)
                agent.drain()
                run.await()
            }
        }

    @Test
    fun `a session that fails ends only itself and the loop claims the next one`() =
        live {
            val started = AtomicInteger()
            val failing: (ClaimResponse, Duration) -> SessionWorker = { _, _ ->
                started.incrementAndGet()
                throw IllegalStateException("worker could not start")
            }
            val agent = RunnerAgent(api, options(), failing, fastBackoff)
            val run = async { agent.run() }
            api.claims.send(ClaimResponse("s1", 1, "a", emptyList()))
            api.claims.send(ClaimResponse("s2", 2, "a", emptyList()))
            awaitUntil { started.get() == 2 }

            awaitUntil { agent.activeSessions == 0 }
            agent.drain()
            run.await()
        }
}
