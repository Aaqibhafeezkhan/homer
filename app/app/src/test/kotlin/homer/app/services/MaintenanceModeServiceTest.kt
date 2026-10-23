package homer.app.services

import homer.app.exceptions.MaintenanceQueueFullException
import homer.app.exceptions.MaintenanceWaitTimeoutException
import homer.app.fixtures.TestFixtures
import homer.app.models.MaintenanceConfig
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.ServerMetaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The maintenance-mode gate: the durable flag, the bounded FIFO wait queue and the release
 * that drains it. See ADR-0092 and root §12 rules 4–5.
 */
class MaintenanceModeServiceTest {
    private lateinit var db: DatabaseService
    private lateinit var repository: ServerMetaRepository
    private lateinit var svc: MaintenanceModeService

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        db = DatabaseService(dir.resolve("d.db").toString(), LoggerFactory.getLogger("t"))
        db.migrate()
        repository = ServerMetaRepository(db, TestFixtures.mockLogger())
    }

    @AfterEach
    fun teardown() {
        if (::svc.isInitialized) svc.stop()
        db.close()
    }

    private fun newService(
        config: MaintenanceConfig = MaintenanceConfig(),
        initialEnabled: Boolean = false,
    ): MaintenanceModeService =
        MaintenanceModeService(
            repository = repository,
            config = config,
            initialEnabled = initialEnabled,
            logger = TestFixtures.mockLogger(),
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        ).also { svc = it }

    // --- persistence ---

    @Test
    fun `the flag is persisted and read back by a repository reopened on the same database`() =
        runBlocking {
            newService().setEnabled(true)

            // A fresh repository over the same file models a restart.
            val afterRestart = ServerMetaRepository(db, TestFixtures.mockLogger())
            assertTrue(afterRestart.readMaintenanceMode())
        }

    @Test
    fun `a service seeded with the persisted flag starts enabled`() {
        runBlocking { repository.writeMaintenanceMode(true) }

        assertTrue(newService(initialEnabled = repository.readMaintenanceMode()).isEnabled())
    }

    // --- the queue ---

    @Test
    fun `awaitRelease returns at once while the mode is off`() =
        runBlocking {
            // Returns without parking: the timeout never fires.
            withTimeout(5_000) { newService().awaitRelease("alice").release() }
        }

    @Test
    fun `awaitRelease parks while the mode is on and returns when it goes off`() =
        runBlocking {
            newService()
            svc.setEnabled(true)

            val parked = async { svc.awaitRelease("alice") }
            // The start must not be released while the mode is still on.
            yield()
            assertFalse(parked.isCompleted)
            assertEquals(1, svc.queueDepth())

            svc.setEnabled(false)
            withTimeout(5_000) { parked.await().release() }
            assertEquals(0, svc.queueDepth())
        }

    @Test
    fun `waiters are released in arrival order`() =
        runBlocking {
            // One release slot at a time, so each waiter's turn is gated by the previous one
            // letting go: the recorded order is the order the drain handed them out, not the
            // order three threads happened to resume in.
            newService(MaintenanceConfig(releaseConcurrency = 1))
            svc.setEnabled(true)

            val order = mutableListOf<String>()
            val waiters =
                listOf("first", "second", "third").map { owner ->
                    async {
                        val slot = svc.awaitRelease(owner)
                        synchronized(order) { order += owner }
                        slot.release()
                    }
                }
            yield()
            assertEquals(3, svc.queueDepth())

            svc.setEnabled(false)
            waiters.awaitAll()
            assertEquals(listOf("first", "second", "third"), order)
        }

    // --- bounds (root §12 rules 4-5) ---

    @Test
    fun `a start past the total cap is shed`() =
        runBlocking {
            newService(MaintenanceConfig(maxQueuedStarts = 2, maxQueuedStartsPerPrincipal = 10))
            svc.setEnabled(true)

            val parked = List(2) { async { svc.awaitRelease("alice") } }
            yield()

            assertFailsWith<MaintenanceQueueFullException> { svc.awaitRelease("alice") }
            assertEquals(2, svc.queueDepth(), "a shed start must not enter the queue")

            svc.setEnabled(false)
            parked.forEach { it.await().release() }
        }

    @Test
    fun `one caller filling its share does not block another caller`() =
        runBlocking {
            newService(MaintenanceConfig(maxQueuedStarts = 10, maxQueuedStartsPerPrincipal = 1))
            svc.setEnabled(true)

            val hog = async { svc.awaitRelease("alice") }
            yield()
            assertFailsWith<MaintenanceQueueFullException> { svc.awaitRelease("alice") }

            // The other principal still has its own share.
            val other = async { svc.awaitRelease("bob") }
            yield()
            assertEquals(2, svc.queueDepth())

            svc.setEnabled(false)
            hog.await().release()
            other.await().release()
        }

    @Test
    fun `callers with no principal share one bucket`() =
        runBlocking {
            newService(MaintenanceConfig(maxQueuedStarts = 10, maxQueuedStartsPerPrincipal = 1))
            svc.setEnabled(true)

            val first = async { svc.awaitRelease(null) }
            yield()
            assertFailsWith<MaintenanceQueueFullException> { svc.awaitRelease(null) }

            svc.setEnabled(false)
            first.await().release()
        }

    @Test
    fun `a start that outlives the wait bound leaves the queue and fails`() =
        runBlocking {
            newService(MaintenanceConfig(queueWaitSec = 1))
            svc.setEnabled(true)

            assertFailsWith<MaintenanceWaitTimeoutException> { svc.awaitRelease("alice") }
            assertEquals(0, svc.queueDepth(), "an expired start must not stay queued")

            // Its per-principal slot is free again, so a later start still fits.
            val again = async { svc.awaitRelease("alice") }
            yield()
            assertEquals(1, svc.queueDepth())
            svc.setEnabled(false)
            again.await().release()
        }

    // --- release ---

    @Test
    fun `release keeps at most releaseConcurrency starts in flight`() =
        runBlocking {
            newService(MaintenanceConfig(releaseConcurrency = 2))
            svc.setEnabled(true)

            // Each released start holds its slot until the test lets it go, so the in-flight
            // count is what the gate's concurrency bound allows.
            val inFlight = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val hold = CompletableDeferred<Unit>()
            val waiters =
                List(5) {
                    async {
                        val slot = svc.awaitRelease("alice")
                        peak.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
                        hold.await()
                        inFlight.decrementAndGet()
                        slot.release()
                    }
                }
            yield()

            svc.setEnabled(false)
            // Let the release drain as far as the bound allows, then check it stopped there.
            // Bounded: an unbounded spin here would hang the suite instead of failing it.
            withTimeout(5_000) { while (peak.get() < 2) yield() }
            repeat(10) { yield() }
            assertEquals(2, peak.get(), "release must not exceed the configured concurrency")

            hold.complete(Unit)
            waiters.awaitAll()
            assertEquals(2, peak.get())
        }

    @Test
    fun `a parked start whose caller goes away does not pin a release slot`() =
        runBlocking {
            // One slot: a cancelled waiter that kept it would wedge every later release, since
            // the drain hands a slot out to a waiter whose caller is already gone.
            newService(MaintenanceConfig(releaseConcurrency = 1))
            svc.setEnabled(true)

            val abandoned = launch { svc.awaitRelease("alice") }
            val next = async { svc.awaitRelease("bob") }
            yield()
            assertEquals(2, svc.queueDepth())

            abandoned.cancelAndJoin()

            svc.setEnabled(false)
            withTimeout(5_000) { next.await().release() }
        }

    @Test
    fun `a cancelled caller leaves the queue and its per-principal share is free again`() =
        runBlocking {
            newService(MaintenanceConfig(maxQueuedStartsPerPrincipal = 1))
            svc.setEnabled(true)

            val abandoned = launch { svc.awaitRelease("alice") }
            yield()
            assertEquals(1, svc.queueDepth())

            abandoned.cancelAndJoin()
            assertEquals(0, svc.queueDepth(), "a cancelled caller must not stay queued")

            // The phantom entry never ate the share: the same caller fits again.
            val next = async { svc.awaitRelease("alice") }
            yield()
            assertEquals(1, svc.queueDepth())

            svc.setEnabled(false)
            withTimeout(5_000) { next.await().release() }
        }

    @Test
    fun `the mode going on again mid-drain leaves the rest queued for the next release`() =
        runBlocking {
            // One slot: the first released start holds it until the test lets go, so the drain
            // is demonstrably still in flight when the mode flips back on.
            newService(MaintenanceConfig(releaseConcurrency = 1))
            svc.setEnabled(true)

            val order = mutableListOf<String>()
            val firstReleased = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val first =
                async {
                    val slot = svc.awaitRelease("first")
                    synchronized(order) { order += "first" }
                    firstReleased.complete(Unit)
                    hold.await()
                    slot.release()
                }
            val rest =
                listOf("second", "third").map { owner ->
                    async {
                        val slot = svc.awaitRelease(owner)
                        synchronized(order) { order += owner }
                        slot.release()
                    }
                }
            yield()
            assertEquals(3, svc.queueDepth())

            svc.setEnabled(false)
            withTimeout(5_000) { firstReleased.await() }
            // Mid-drain: the second waiter may already have been handed its slot, but the
            // third is still parked. The drain stops at the flag, so the third is not
            // released while the mode is on.
            svc.setEnabled(true)
            hold.complete(Unit)
            withTimeout(5_000) { first.await() }
            assertFalse(rest[1].isCompleted, "a waiter still queued must not be released while the mode is on")

            // The next release drains the rest. `second` may have been handed out by the
            // first drain before the mode flipped back on, so `second` and `third` can be
            // released by different drains — the global order across drains is scheduling,
            // not arrival. Every waiter completing is what must hold.
            svc.setEnabled(false)
            withTimeout(5_000) { rest.awaitAll() }
            assertTrue(order.containsAll(listOf("first", "second", "third")))
        }

    @Test
    fun `two switches-off in quick succession release every waiter once and drain the queue`() =
        runBlocking {
            // One release slot at a time, so within one drain the waiters leave in arrival
            // order. Two rapid toggles start two overlapping drains, though, and which
            // drain's acquire suspends first — scheduling, not arrival — decides the global
            // release order. What must hold is that every waiter is handed out exactly once
            // and the queue empties; per-drain order is pinned by the single-toggle test.
            newService(MaintenanceConfig(releaseConcurrency = 1))
            svc.setEnabled(true)

            val order = mutableListOf<String>()
            val waiters =
                listOf("first", "second", "third").map { owner ->
                    async {
                        val slot = svc.awaitRelease(owner)
                        synchronized(order) { order += owner }
                        slot.release()
                    }
                }
            yield()
            assertEquals(3, svc.queueDepth())

            // The second toggle starts a second drain while the first may still be running;
            // both must take waiters from the head of the queue, so none is skipped or
            // handed out twice.
            svc.setEnabled(false)
            svc.setEnabled(false)
            withTimeout(5_000) { waiters.awaitAll() }
            assertEquals(0, svc.queueDepth())
            assertTrue(order.containsAll(listOf("first", "second", "third")))
        }

    @Test
    fun `switching off with nothing queued drains nothing`() =
        runBlocking {
            newService()
            svc.setEnabled(true)
            svc.setEnabled(false)

            assertEquals(0, svc.queueDepth())
            // The gate is simply open again.
            withTimeout(5_000) { svc.awaitRelease("alice").release() }
        }

    @Test
    fun `stop fails the parked starts with the wait-timeout error`() =
        runBlocking {
            // supervisorScope: the parked start fails on purpose, and a failing child of the
            // test's own scope would cancel the test body instead of surfacing at the await.
            supervisorScope {
                newService()
                svc.setEnabled(true)

                val parked = async { svc.awaitRelease("alice") }
                yield()
                assertEquals(1, svc.queueDepth())

                svc.stop()

                assertFailsWith<MaintenanceWaitTimeoutException> { parked.await() }
                assertEquals(0, svc.queueDepth())
            }
        }

    // --- the read model ---

    @Test
    fun `snapshot reports the flag, the depth and when the mode was turned on`() =
        runBlocking {
            newService()
            assertFalse(svc.snapshot().enabled)
            assertNull(svc.snapshot().since)

            svc.setEnabled(true)
            val enabled = svc.snapshot()
            assertTrue(enabled.enabled)
            assertTrue((enabled.since ?: 0) > 0)
            assertEquals(0, enabled.queued)

            val parked = launch { svc.awaitRelease("alice") }
            yield()
            assertEquals(1, svc.snapshot().queued)

            svc.setEnabled(false)
            parked.join()
            assertNull(svc.snapshot().since)
        }
}
