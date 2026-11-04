package homer.app.runners.external

import homer.app.exceptions.RunnerRegistrationRejectedException
import homer.app.exceptions.TooManyRunnerInstancesException
import homer.app.fixtures.TestFixtures
import homer.app.models.RunnerAffinity
import homer.app.models.RunnerConfig
import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import homer.app.runners.queue.RunnerAffinityMemory
import homer.app.runners.queue.RunnerClaimQueue
import io.mockk.mockk
import io.opentelemetry.context.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ExternalRunnerDomainTest {
    private var now = 1_000_000L
    private val queue = RunnerClaimQueue(maxPending = 10)

    private fun domain(config: RunnerConfig.External = RunnerConfig.External(token = "t")) =
        ExternalRunnerDomain("workers", config, queue, clock = { now })

    private fun params(
        processId: Int,
        rootProcessId: Int? = null,
    ) = RunnerSessionParams(
        processId = processId,
        agentName = "demo",
        workspace = TestFixtures.mockWorkspaceConfig(),
        env = emptyMap(),
        parentContext = Context.current(),
        rootProcessId = rootProcessId,
    )

    @Test
    fun `register creates an instance and a queue consumer`() {
        val d = domain()
        val inst = d.register(RegisterInstanceRequest(name = "host-1", tags = listOf("linux", "gpu"), concurrency = 2, version = "0.1.0"))

        assertEquals(32, inst.id.length)
        assertEquals("host-1", inst.name)
        assertEquals("workers", inst.alias)
        assertEquals(setOf("linux", "gpu"), inst.tags)
        assertEquals(2, inst.concurrency)
        assertEquals(0, inst.inUse)
        assertEquals(inst, d.instance(inst.id))
        assertEquals(listOf<Any>(inst), queue.consumers())
    }

    @Test
    fun `register without a name uses the id prefix`() {
        val inst = domain().register(RegisterInstanceRequest())
        assertEquals(inst.id.take(8), inst.name)
        assertEquals(1, inst.concurrency)
    }

    @Test
    fun `register rejects a tag outside allowedTags, a bad tag shape and an oversized concurrency`() {
        val d = domain(RunnerConfig.External(token = "t", allowedTags = listOf("linux"), maxConcurrencyPerInstance = 4))
        assertThrows<RunnerRegistrationRejectedException> { d.register(RegisterInstanceRequest(tags = listOf("gpu"))) }
        assertThrows<RunnerRegistrationRejectedException> { d.register(RegisterInstanceRequest(tags = listOf("bad tag"))) }
        assertThrows<RunnerRegistrationRejectedException> { d.register(RegisterInstanceRequest(concurrency = 5)) }
        assertThrows<RunnerRegistrationRejectedException> { d.register(RegisterInstanceRequest(concurrency = 0)) }
        assertTrue(queue.consumers().isEmpty(), "a rejected registration leaves no consumer")
    }

    @Test
    fun `register beyond maxInstances is refused`() {
        val d = domain(RunnerConfig.External(token = "t", maxInstances = 1))
        d.register(RegisterInstanceRequest())
        assertThrows<TooManyRunnerInstancesException> { d.register(RegisterInstanceRequest()) }
    }

    @Test
    fun `matching requires tags, a free permit and not draining`() {
        val inst = domain().register(RegisterInstanceRequest(tags = listOf("linux", "gpu"), concurrency = 1))
        assertTrue(inst.matches(RunnerFilter.Alias("workers")))
        assertTrue(inst.matches(RunnerFilter.Tags(setOf("linux"))))
        assertFalse(inst.matches(RunnerFilter.Tags(setOf("windows"))))
        assertFalse(inst.matches(RunnerFilter.Alias("other")))

        inst.sessionOpened()
        assertFalse(inst.matches(RunnerFilter.Alias("workers")), "saturated instances never claim")
        inst.sessionClosed()
        assertTrue(inst.matches(RunnerFilter.Alias("workers")))

        inst.draining = true
        assertFalse(inst.matches(RunnerFilter.Alias("workers")))
    }

    @Test
    fun `poll slots are bounded by the declared concurrency`() {
        val inst = domain().register(RegisterInstanceRequest(concurrency = 2))
        assertTrue(inst.tryAcquirePoll())
        assertTrue(inst.tryAcquirePoll())
        assertFalse(inst.tryAcquirePoll())
        inst.releasePoll()
        assertTrue(inst.tryAcquirePoll())
    }

    @Test
    fun `poll slots are also bounded by already-active sessions, so a poll cannot over-commit an instance`() {
        val inst = domain().register(RegisterInstanceRequest(concurrency = 2))
        inst.sessionOpened()
        assertTrue(inst.tryAcquirePoll())
        assertFalse(inst.tryAcquirePoll())
        inst.sessionClosed()
        assertTrue(inst.tryAcquirePoll())
    }

    @Test
    fun `register rejects a name with a shape a tag wouldn't allow, so it is never unsafe to log`() {
        val d = domain()
        assertThrows<RunnerRegistrationRejectedException> { d.register(RegisterInstanceRequest(name = "bad name\nforged")) }
        val inst = d.register(RegisterInstanceRequest(name = "host-1.example.com"))
        assertEquals("host-1.example.com", inst.name)
    }

    @Test
    fun `deregister stops claims and removes the instance once idle`() =
        runTest {
            val d = domain()
            val inst = d.register(RegisterInstanceRequest())
            inst.sessionOpened()

            assertTrue(d.deregister(inst.id))
            assertTrue(inst.draining)
            assertTrue(queue.consumers().isEmpty())
            assertEquals(inst, d.instance(inst.id), "in-flight sessions keep the instance addressable")

            inst.sessionClosed()
            d.releaseIfIdle(inst)
            assertNull(d.instance(inst.id))
            assertFalse(d.deregister(inst.id))
        }

    @Test
    fun `deregistering the preferred instance wakes the run parked behind it`() =
        runTest {
            val memory = RunnerAffinityMemory().also { it.record(1, "gpu", "workers:host-1") }
            val affinityQueue = RunnerClaimQueue(maxPending = 10, affinityMemory = memory)
            val d = ExternalRunnerDomain("workers", RunnerConfig.External(token = "t"), affinityQueue, clock = { now })
            val preferred = d.register(RegisterInstanceRequest(name = "host-1", tags = listOf("gpu"), concurrency = 1))
            val other = d.register(RegisterInstanceRequest(name = "host-2", tags = listOf("gpu"), concurrency = 1))

            val run =
                async {
                    affinityQueue.awaitClaim(
                        params(1, rootProcessId = 1),
                        RunnerFilter.Tags(setOf("gpu")),
                        300.seconds,
                        RunnerAffinity("gpu", 600),
                    )
                }
            runCurrent()
            val takeOther = async { affinityQueue.take(other) }
            runCurrent()
            assertTrue(takeOther.isActive, "the other instance passes the request over while the preference is active")

            // The drain route: `deregister` flips draining before it leaves through
            // unregisterConsumer, and the held-back waiter must wake all the same.
            assertTrue(d.deregister(preferred.id))
            val taken = takeOther.await()
            assertEquals(1, taken.params.processId)
            taken.complete(mockk<RunnerSession>(relaxed = true))
            run.await()
        }

    @Test
    fun `sweepExpired drops an instance silent past the heartbeat timeout and reports it`() =
        runTest {
            val d = domain(RunnerConfig.External(token = "t", heartbeatTimeoutSec = 60))
            val quiet = d.register(RegisterInstanceRequest(name = "quiet"))
            val chatty = d.register(RegisterInstanceRequest(name = "chatty"))

            now += 59_000
            d.touch(chatty)
            now += 2_000
            val expired = d.sweepExpired()

            assertEquals(listOf(quiet), expired)
            assertNull(d.instance(quiet.id))
            assertEquals(chatty, d.instance(chatty.id))
            assertEquals(listOf<Any>(chatty), queue.consumers())
        }

    @Test
    fun `couldServe follows allowedTags and instanceTagSets follows registrations`() {
        val any = domain()
        assertTrue(any.couldServe(setOf("anything")))
        val bounded = domain(RunnerConfig.External(token = "t", allowedTags = listOf("linux", "gpu")))
        assertTrue(bounded.couldServe(setOf("linux")))
        assertFalse(bounded.couldServe(setOf("linux", "windows")))

        assertTrue(any.instanceTagSets().isEmpty())
        any.register(RegisterInstanceRequest(tags = listOf("linux")))
        assertEquals(listOf(setOf("linux")), any.instanceTagSets().toList())
    }

    @Test
    fun `domain is a remote Runner whose openSession is never called directly`() =
        runTest {
            val d = domain(RunnerConfig.External(token = "t", env = mapOf("CACHE" to "/c")))
            assertTrue(d.isRemote)
            assertEquals(mapOf("CACHE" to "/c"), d.runnerEnv)
            assertThrows<IllegalStateException> { d.openSession(mockk<RunnerSessionParams>(relaxed = true)) }
        }

    @Test
    fun `a live instance holds its declared name, so a second one is rejected`() {
        val d = domain(RunnerConfig.External(token = "s3cr3t-token"))
        val first = d.register(RegisterInstanceRequest(name = "host-1"))
        assertEquals("workers:host-1", first.affinityInstance)

        val ex = assertThrows<RunnerRegistrationRejectedException> { d.register(RegisterInstanceRequest(name = "host-1")) }
        assertFalse(ex.message!!.contains("s3cr3t-token"), "the rejection must name no token")
        assertEquals(listOf<Any>(first), queue.consumers(), "the rejected registration leaves no consumer")
    }

    @Test
    fun `a name freed by a draining or swept instance can be registered again`() =
        runTest {
            val d = domain(RunnerConfig.External(token = "t", heartbeatTimeoutSec = 60))
            val draining = d.register(RegisterInstanceRequest(name = "host-1"))
            d.deregister(draining.id)
            assertEquals("host-1", d.register(RegisterInstanceRequest(name = "host-1")).name)

            val silent = d.instances().single { it.declaredName == "host-1" }
            now += 61_000
            assertEquals(listOf(silent), d.sweepExpired())
            assertEquals("host-1", d.register(RegisterInstanceRequest(name = "host-1")).name)
        }

    @Test
    fun `the same name in another domain is its own instance`() {
        val a = domain()
        val b = ExternalRunnerDomain("other", RunnerConfig.External(token = "t"), queue, clock = { now })
        assertEquals("workers:host-1", a.register(RegisterInstanceRequest(name = "host-1")).affinityInstance)
        assertEquals("other:host-1", b.register(RegisterInstanceRequest(name = "host-1")).affinityInstance)
    }

    @Test
    fun `a nameless registration exposes no affinity identity`() {
        val inst = domain().register(RegisterInstanceRequest())
        assertNull(inst.declaredName)
        assertNull(inst.affinityInstance)
    }
}
