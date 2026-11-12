package homer.app.services

import homer.app.EnvConstants
import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.InvocationCycleDetectedException
import homer.app.exceptions.ServerDrainingException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.persistence.HookRepository
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HookFireServiceTest {
    private val repo = mockk<HookRepository>(relaxed = true)
    private val agents = mockk<AgentsService>()
    private val runStarter = mockk<AgentRunStarter>()
    private val inputResolver = mockk<AgentInputResolver>(relaxed = true)
    private val processManager = mockk<ProcessManager>(relaxed = true)
    private val authorizer =
        mockk<Authorizer>().also {
            every { it.canDispatchHop(any(), any(), any()) } returns true
            every { it.canSee(any(), any(), any()) } returns true
        }
    private val target = mockk<Agent>(relaxed = true)

    private val fires =
        HookFireService(
            repo,
            agents,
            authorizer,
            runStarter,
            inputResolver,
            processManager,
            LoggerFactory.getLogger("t"),
            MAX_CHAIN_DEPTH,
        ) { 1000L }

    private fun hook(
        id: Int = 5,
        processId: Int = 42,
        declaringAgent: String = "declarer",
        triggeringStatus: ProcessStatus = ProcessStatus.FINISHED,
        targetAgentName: String = "notifier",
        owner: String? = "alice",
        fireAttempts: Int = 0,
    ) = PendingHook(
        id = id,
        processId = processId,
        declaringAgent = declaringAgent,
        triggeringStatus = triggeringStatus,
        targetAgentName = targetAgentName,
        owner = owner,
        status = HookStatus.PENDING,
        fireAttempts = fireAttempts,
        nextAttemptAt = null,
        firedProcessId = null,
        firedAt = null,
        error = null,
        createdAt = 500,
    )

    /** The triggering run's row: it is the flow root's own run unless [rootProcessId] says otherwise. */
    private fun run(
        rootProcessId: Int? = ROOT_ID,
        failureReason: String? = null,
        tags: List<String> = emptyList(),
        inputParams: Map<String, String> = emptyMap(),
    ) = ProcessInfo(
        id = 42,
        status = ProcessStatus.FINISHED,
        agentName = "declarer",
        owner = "alice",
        rootProcessId = rootProcessId,
        failureReason = failureReason,
        tags = tags,
        inputParams = inputParams,
    )

    /** A run a hook started [hookDepth] hops deep. */
    private fun hookedRun(hookDepth: Int) = run(inputParams = mapOf(EnvConstants.HOOK_DEPTH to hookDepth.toString()))

    /** The flow root's own row, as `visibleRootProcessId` reads it back by id. */
    private fun root() = ProcessInfo(id = ROOT_ID, status = ProcessStatus.WORKING, agentName = "declarer", owner = "alice")

    /**
     * The target is loaded, the claim is won, the triggering run reads back as [run], and the
     * start returns 99; returns the params the fire resolved. [canSeeRoot] is the triggering
     * owner's visibility of the flow root.
     */
    private fun startSucceeds(
        run: ProcessInfo? = run(),
        canSeeRoot: Boolean = true,
    ): CapturingSlot<suspend () -> Map<String, String>> {
        every { agents.agents } returns mapOf("notifier" to target)
        coEvery { repo.claim(any()) } returns true
        coEvery { processManager.getProcessInfo(42) } returns run
        coEvery { processManager.getProcessInfo(ROOT_ID) } returns root()
        every { authorizer.canSee(any(), any(), any()) } returns canSeeRoot
        val resolve = slot<suspend () -> Map<String, String>>()
        coEvery {
            runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(resolve))
        } coAnswers
            {
                resolve.captured.invoke()
                99
            }
        return resolve
    }

    private fun noStart() =
        coVerify(exactly = 0) {
            runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

    @Test
    fun `fire starts the target as the triggering run's owner and settles the row dispatched`() =
        runTest {
            startSucceeds()

            fires.fire(hook())

            coVerify {
                runStarter.start(
                    eq(target),
                    eq(listOf("declarer")),
                    eq("hook"),
                    any(),
                    any(),
                    owner = "alice",
                    any(),
                    parentProcessId = 42,
                    rootProcessId = ROOT_ID,
                    serverDerivedParent = true,
                    resolveInputs = any(),
                )
            }
            coVerify { repo.markDispatched(5, 99, 1000L) }
        }

    @Test
    fun `a lost claim starts nothing`() =
        runTest {
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(5) } returns false

            fires.fire(hook())

            noStart()
            coVerify(exactly = 0) { repo.markDispatched(any(), any(), any()) }
        }

    @Test
    fun `a root the triggering owner may not see is dropped, never passed on`() =
        runTest {
            startSucceeds(canSeeRoot = false)

            fires.fire(hook(owner = null))

            // A root the owner may not see is no root at all: the fire passes null, never the
            // foreign id. §12 rule 3, ADR-0058.
            coVerify {
                runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), isNull(), any(), any())
            }
        }

    @Test
    fun `an owner without the grant on the target is refused and never started`() =
        runTest {
            // Fail closed: the row settles, and nothing about the target reaches the caller.
            // §12 rule 3, ADR-0039.
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(any()) } returns true
            every { authorizer.canDispatchHop("alice", "declarer", "notifier") } returns false

            fires.fire(hook())

            noStart()
            coVerify {
                repo.markFailed(
                    5,
                    "agent 'declarer' is not authorized to start 'notifier'",
                    1000L,
                )
            }
        }

    @Test
    fun `a target agent that is no longer loaded is settled failed`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.claim(any()) } returns true

            fires.fire(hook())

            noStart()
            coVerify { repo.markFailed(5, "Target agent 'notifier' is not loaded", 1000L) }
        }

    @Test
    fun `a hook deeper than the chain bound is dropped, not started and not retried`() =
        runTest {
            startSucceeds(hookedRun(MAX_CHAIN_DEPTH))

            fires.fire(hook())

            noStart()
            coVerify { repo.markDropped(5, match { it.contains("exceeds") }, 1000L) }
            coVerify(exactly = 0) { repo.releaseForRetry(any(), any(), any(), any()) }
        }

    @Test
    fun `a hook exactly at the chain bound is still fired`() =
        runTest {
            startSucceeds(hookedRun(MAX_CHAIN_DEPTH - 1))

            fires.fire(hook())

            coVerify { repo.markDispatched(5, 99, 1000L) }
        }

    @Test
    fun `a triggering run that is its own flow root passes its own id as the root`() =
        runTest {
            // The root comes from the row already read: a run with no root of its own IS the
            // flow root, so its id — the owner's own run — is the child's root, unguarded by
            // the visibility check the derived-root case runs. ADR-0058.
            startSucceeds(run(rootProcessId = null))

            fires.fire(hook())

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    eq(42),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `a run that is no longer readable is dropped instead of fired`() =
        runTest {
            // The row is durable and can outlive the run it was recorded from (eviction, record
            // purge). The stored HMR_HOOK_DEPTH is what carries the chain bound across a
            // restart, so a fire that cannot read it fails closed instead of restarting the
            // chain at depth 1. §12, ADR-0107.
            startSucceeds(run = null)

            fires.fire(hook())

            noStart()
            coVerify {
                repo.markDropped(
                    5,
                    "the triggering run is no longer readable, so its hook-chain depth cannot be checked",
                    1000L,
                )
            }
        }

    @Test
    fun `a run whose stored depth is unreadable is dropped instead of fired`() =
        runTest {
            startSucceeds(run(inputParams = mapOf(EnvConstants.HOOK_DEPTH to "not-a-number")))

            fires.fire(hook())

            noStart()
            coVerify {
                repo.markDropped(5, "the triggering run's stored hook-chain depth is not a number", 1000L)
            }
        }

    @Test
    fun `a cancelling row lookup propagates instead of settling the hook`() =
        runTest {
            // A cancellation is a shutdown in flight, not this hook's failure: settling it
            // FAILED would record a delivery the boot replay never replays. The rethrow is what
            // ADR-0102's drain rule relies on.
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(any()) } returns true
            coEvery { processManager.getProcessInfo(42) } throws CancellationException("shutting down")

            assertThrows<CancellationException> { fires.fire(hook()) }

            coVerify(exactly = 0) { repo.markFailed(any(), any(), any()) }
            coVerify(exactly = 0) { repo.markDropped(any(), any(), any()) }
            coVerify(exactly = 0) { repo.markDispatched(any(), any(), any()) }
        }

    @Test
    fun `a cancelling start propagates instead of settling the hook`() =
        runTest {
            // The row stays DISPATCHING for the boot's failOrphanedDispatching, never settled
            // by a cancelled fire.
            startSucceeds()
            coEvery {
                runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } throws CancellationException("shutting down")

            assertThrows<CancellationException> { fires.fire(hook()) }

            coVerify(exactly = 0) { repo.markFailed(any(), any(), any()) }
            coVerify(exactly = 0) { repo.releaseForRetry(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repo.markDispatched(any(), any(), any()) }
        }

    @Test
    fun `the hook run carries the triggering run's id, agent, status and depth`() =
        runTest {
            val resolve = startSucceeds(hookedRun(hookDepth = 1))

            fires.fire(hook(triggeringStatus = ProcessStatus.KILLED))

            assertEquals("42", resolve.captured.invoke()[EnvConstants.HOOK_PROCESS_ID])
            assertEquals("declarer", resolve.captured.invoke()[EnvConstants.HOOK_AGENT_NAME])
            assertEquals("KILLED", resolve.captured.invoke()[EnvConstants.HOOK_STATUS])
            assertEquals("2", resolve.captured.invoke()[EnvConstants.HOOK_DEPTH])
        }

    @Test
    fun `the stored pre-start failure reason and the run's tags ride the hook params`() =
        runTest {
            val resolve =
                startSucceeds(
                    run(failureReason = "Lock 'db' is held by run 7", tags = listOf("env:prod", "team:core")),
                )

            fires.fire(hook(triggeringStatus = ProcessStatus.FAILED))

            assertEquals("Lock 'db' is held by run 7", resolve.captured.invoke()[EnvConstants.HOOK_FAILURE_REASON])
            assertEquals("env:prod,team:core", resolve.captured.invoke()[EnvConstants.HOOK_TAGS])
        }

    @Test
    fun `a run with no failure reason and no tags sends neither param`() =
        runTest {
            val resolve = startSucceeds(run())

            fires.fire(hook())

            assertNull(resolve.captured.invoke()[EnvConstants.HOOK_FAILURE_REASON])
            assertNull(resolve.captured.invoke()[EnvConstants.HOOK_TAGS])
        }

    @Test
    fun `no input value of the triggering run is ever copied into the hook params`() =
        runTest {
            // The stored inputs are the run's own, secrets included; the hook carries only the
            // server-minted HMR_HOOK_* metadata, so a target that echoes its env cannot leak
            // them. §12 rule 7.
            val resolve =
                startSucceeds(
                    run(
                        inputParams =
                            mapOf(
                                "API_TOKEN" to "s3cr3t",
                                "TICKET_ID" to "123",
                                EnvConstants.HOOK_DEPTH to "0",
                            ),
                    ),
                )

            fires.fire(hook())

            val resolved = resolve.captured.invoke()
            assertEquals(
                setOf(
                    EnvConstants.HOOK_PROCESS_ID,
                    EnvConstants.HOOK_AGENT_NAME,
                    EnvConstants.HOOK_STATUS,
                    EnvConstants.HOOK_DEPTH,
                ),
                resolved.keys,
            )
            assertFalse(resolved.values.any { it.contains("s3cr3t") }, resolved.toString())
        }

    @Test
    fun `a transient refusal is released for a retry on the back-off`() =
        runTest {
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(any()) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CostCapExceededException("global", 1.0, 2.0)

            fires.fire(hook())

            coVerify { repo.releaseForRetry(5, any(), 1, 1060L) }
            coVerify(exactly = 0) { repo.markFailed(any(), any(), any()) }
        }

    @Test
    fun `a fire the drain refused is released for a retry, never settled failed`() =
        runTest {
            // The status transition already happened; the hook is the only record of it, so a
            // draining server parks it for the next sweep rather than dropping it. ADR-0102.
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(any()) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                ServerDrainingException()

            fires.fire(hook())

            coVerify { repo.releaseForRetry(5, any(), 1, 1060L) }
        }

    @Test
    fun `a cooldown refusal doubles the retry delay with each attempt`() =
        runTest {
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(any()) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CooldownActiveException("notifier", 30, "hook")

            fires.fire(hook(fireAttempts = 3))

            coVerify { repo.releaseForRetry(5, any(), 4, 1000L + 480L) }
        }

    @Test
    fun `a refusal that is not transient fails the hook on the first attempt`() =
        runTest {
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(any()) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                InvocationCycleDetectedException("notifier", listOf("declarer", "notifier"))

            fires.fire(hook())

            coVerify { repo.markFailed(5, match { it.contains("cycle") }, 1000L) }
            coVerify(exactly = 0) { repo.releaseForRetry(any(), any(), any(), any()) }
        }

    @Test
    fun `a transient refusal past the attempt ceiling gives up and fails the hook`() =
        runTest {
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(any()) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CostCapExceededException("global", 1.0, 2.0)

            fires.fire(hook(fireAttempts = ContinuationFireService.MAX_FIRE_ATTEMPTS - 1))

            coVerify { repo.markFailed(5, match { it.contains("gave up after") }, 1000L) }
            coVerify(exactly = 0) { repo.releaseForRetry(any(), any(), any(), any()) }
        }

    @Test
    fun `an unexpected failure settles the hook rather than escaping into the status write`() =
        runTest {
            every { agents.agents } returns mapOf("notifier" to target)
            coEvery { repo.claim(any()) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                IllegalStateException("boom")

            fires.fire(hook())

            coVerify { repo.markFailed(5, "boom", 1000L) }
        }

    @Test
    fun `hookTagsParam joins the run's tags in order`() {
        assertEquals("env:prod,team:core", hookTagsParam(listOf("env:prod", "team:core")))
    }

    @Test
    fun `hookTagsParam drops control characters and tags left empty by it`() {
        // A tag is subject metadata an agent can influence; a newline in it must not reach the
        // target's env as a second line. §12 rule 2.
        assertEquals("env:prod", hookTagsParam(listOf("env\n:prod", "\u0007", "\u0000")))
    }

    @Test
    fun `hookTagsParam is null when nothing survives`() {
        assertNull(hookTagsParam(emptyList()))
        assertNull(hookTagsParam(listOf("", "\u0007")))
    }

    @Test
    fun `hookTagsParam caps the value without cutting a tag in half`() {
        val big = "a".repeat(600)
        val value = hookTagsParam(listOf(big, "b".repeat(600)))

        // The second tag does not fit under the cap, so it is left out whole rather than cut.
        assertEquals(big, value)
        assertTrue(value!!.length <= MAX_HOOK_TAGS_CHARS, value.length.toString())
    }

    @Test
    fun `hookTagsParam lets a lone tag use the full tag budget`() {
        // The separator is charged from the second tag on: a single tag of exactly the cap
        // length joins to a value that fits the documented cap exactly.
        val exact = "a".repeat(MAX_HOOK_TAGS_CHARS)
        assertEquals(exact, hookTagsParam(listOf(exact)))
        // A second tag after it no longer fits, so it is left out whole.
        assertEquals(exact, hookTagsParam(listOf(exact, "b")))
    }

    @Test
    fun `hookTagsParam keeps every value it emits under the cap with no tag cut in half`() =
        runTest {
            // Tags are subject metadata an agent can influence, so the examples cannot stand
            // alone: the cap and the no-cut rule hold for any list. §6, §12 rule 4.
            checkAll(Arb.list(Arb.list(Arb.element(TAG_CHARS), 0..12), 0..8)) { rawTags ->
                val tags = rawTags.map { it.joinToString("") }
                val cleaned = tags.map { it.filterNot { ch -> ch.isISOControl() } }.filter { it.isNotEmpty() }
                val value = hookTagsParam(tags)

                if (value != null) {
                    assertTrue(value.length <= MAX_HOOK_TAGS_CHARS, "length ${value.length} of '$value'")
                    // A kept set is a prefix of the cleaned tags, comma-joined: nothing after
                    // the first tag that does not fit survives, and no tag is cut in half.
                    assertEquals(cleaned.joinToString(",").take(value.length), value)
                }
            }
        }

    @Test
    fun `a lone tag joins to itself exactly when it fits the cap`() =
        runTest {
            // One tag pays no separator, so its cleaned form is the value whole, or null.
            checkAll(Arb.list(Arb.element(TAG_CHARS), 0..MAX_HOOK_TAGS_CHARS + 4)) { chars ->
                val tag = chars.joinToString("")
                assertEquals(
                    tag.filterNot { it.isISOControl() }.takeIf { it.isNotEmpty() && it.length <= MAX_HOOK_TAGS_CHARS },
                    hookTagsParam(listOf(tag)),
                )
            }
        }

    private companion object {
        const val MAX_CHAIN_DEPTH = 8
        const val ROOT_ID = 7
        val TAG_CHARS = listOf('a', 'B', '9', '-', '_', ':', ',', ' ', '\t', '\n', '\u0007', '\u0000')
    }
}
