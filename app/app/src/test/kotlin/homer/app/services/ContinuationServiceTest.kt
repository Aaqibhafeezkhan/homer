package homer.app.services

import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.ContinuationNotFoundException
import homer.app.exceptions.ContinuationNotPendingException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.TooManyPendingContinuationsException
import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentDispatchConfig
import homer.app.models.Command
import homer.app.models.DispatchConfig
import homer.app.models.ExecutionInfo
import homer.app.models.Param
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import homer.app.services.persistence.ContinuationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinuationServiceTest {
    private val repo = mockk<ContinuationRepository>(relaxed = true)
    private val questions = mockk<QuestionsService>()
    private val agents = mockk<AgentsService>()
    private val runStarter = mockk<AgentRunStarter>()
    private val inputResolver = mockk<AgentInputResolver>(relaxed = true)
    private val processManager = mockk<ProcessManager>(relaxed = true)
    private val resultTokenService = mockk<ResultTokenService>(relaxed = true)
    private val authorizer =
        mockk<Authorizer>().also {
            every { it.canDispatchHop(any(), any(), any()) } returns true
            // The fire paths gate the derived flow root on the fired run owner's
            // visibility; the denial case has its own test below.
            every { it.canSee(any(), any(), any()) } returns true
        }

    private fun service(config: DispatchConfig = DispatchConfig()) =
        ContinuationService(
            repo,
            questions,
            agents,
            authorizer,
            runStarter,
            inputResolver,
            processManager,
            resultTokenService,
            LoggerFactory.getLogger("t"),
            config,
        ) { 1000L }

    private fun pending(
        id: Int = 5,
        watched: Int = 42,
        transformer: ResultEnvParam? = null,
    ) = Continuation(
        id = id,
        watchedProcessId = watched,
        status = ContinuationStatus.PENDING,
        targetAgentName = "h",
        params = mapOf("HMR_LG_THREAD" to "thread-1"),
        resumeParam = "HMR_LG_RESUME",
        resultTransformer = transformer,
        originProcessId = 7,
        originAgentName = "asker",
        expiresAt = null,
        createdAt = 1,
    )

    // --- create validation ---

    @Test
    fun `validate reports an unauthorized continue target as not found`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "asker", "h") } returns false

            assertFailsWith<DispatchAgentNotFoundException> {
                service().validate(7, "h", resumeParam = "HMR_LG_RESUME", params = emptyMap(), ttlSec = null)
            }
        }

    // ADR-0047 rule 3: see DispatchServiceTest -- an unauthorized hop gets the unknown-agent answer.
    @Test
    fun `validate reports an unauthorized non-dispatchable target exactly like an unknown agent`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val disabled = mockk<Agent>().also { every { it.dispatch } returns null }
            every { agents.agents } returns mapOf("disabled" to disabled)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "asker", any()) } returns false

            val hidden =
                assertFailsWith<DispatchAgentNotFoundException> {
                    service().validate(7, "disabled", resumeParam = "HMR_LG_RESUME", params = emptyMap(), ttlSec = null)
                }
            val unknown =
                assertFailsWith<DispatchAgentNotFoundException> {
                    service().validate(7, "missing", resumeParam = "HMR_LG_RESUME", params = emptyMap(), ttlSec = null)
                }
            assertEquals(unknown.message?.replace("missing", "disabled"), hidden.message)
        }

    @Test
    fun `validateJoin reports an unauthorized non-dispatchable sub as not found`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val target = mockk<Agent>(relaxed = true).also { every { it.dispatch } returns AgentDispatchConfig(enabled = true) }
            val sub = mockk<Agent>().also { every { it.dispatch } returns null }
            every { agents.agents } returns mapOf("h" to target, "sub" to sub)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "asker", "h") } returns true
            every { authorizer.canDispatchHop("alice", "asker", "sub") } returns false

            assertFailsWith<DispatchAgentNotFoundException> {
                service().validateJoin(7, "h", subAgents = listOf("sub"), resumeParam = "HMR_LG_RESUME", params = emptyMap(), ttlSec = null)
            }
        }

    @Test
    fun `create rejects when asker registration is closed`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = true)
            assertFailsWith<QuestionsClosedForProcessException> {
                service().create(
                    7,
                    watchedProcessId = 42,
                    target = "h",
                    params = emptyMap(),
                    resumeParam = "HMR_LG_RESUME",
                    resultTransformer = null,
                    ttlSec = null,
                )
            }
        }

    @Test
    fun `create rejects a blank resumeParam`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            assertFailsWith<ValidationException> {
                service().create(7, 42, "h", emptyMap(), resumeParam = " ", resultTransformer = null, ttlSec = null)
            }
        }

    @Test
    fun `create rejects a resumeParam targeting a non-control reserved env name`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            assertFailsWith<ValidationException> {
                service().create(7, 42, "h", emptyMap(), resumeParam = "PATH", resultTransformer = null, ttlSec = null)
            }
        }

    @Test
    fun `create rejects when resumeParam collides with a param key`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            assertFailsWith<ValidationException> {
                service().create(
                    7,
                    42,
                    "h",
                    mapOf("HMR_LG_RESUME" to "x"),
                    resumeParam = "HMR_LG_RESUME",
                    resultTransformer = null,
                    ttlSec = null,
                )
            }
        }

    @Test
    fun `create rejects unknown target agent`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            every { agents.agents } returns emptyMap()
            assertFailsWith<DispatchAgentNotFoundException> {
                service().create(7, 42, "missing", emptyMap(), "HMR_LG_RESUME", null, null)
            }
        }

    @Test
    fun `create rejects a non-dispatchable target agent`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val noBlock = mockk<Agent>().also { every { it.dispatch } returns null }
            val disabled = mockk<Agent>().also { every { it.dispatch } returns AgentDispatchConfig(enabled = false) }
            every { agents.agents } returns mapOf("noblock" to noBlock, "disabled" to disabled)
            assertFailsWith<AgentNotDispatchableException> { service().create(7, 42, "noblock", emptyMap(), "HMR_LG_RESUME", null, null) }
            assertFailsWith<AgentNotDispatchableException> { service().create(7, 42, "disabled", emptyMap(), "HMR_LG_RESUME", null, null) }
        }

    @Test
    fun `create maps cap rejection to TooManyPendingContinuationsException`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.create(any(), any()) } returns null
            assertFailsWith<TooManyPendingContinuationsException> {
                service(DispatchConfig(maxPending = 1)).create(7, 42, "h", emptyMap(), "HMR_LG_RESUME", null, null)
            }
        }

    @Test
    fun `create reports an unauthorized resume target as not found`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "asker", "h") } returns false
            assertFailsWith<DispatchAgentNotFoundException> {
                service().create(7, 42, "h", emptyMap(), "HMR_LG_RESUME", null, null)
            }
            coVerify(exactly = 0) { repo.create(any(), any()) }
        }

    @Test
    fun `create rejects when the target's required input cannot be satisfied by params or resumeParam`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("echo"))),
                    dispatch = AgentDispatchConfig(enabled = true),
                    inputs = listOf(Param(paramName = "required_field", envName = "REQ", required = true)),
                )
            every { agents.agents } returns mapOf("h" to agent)
            // Real AgentInputResolver here (not the class's mocked `inputResolver`) so this
            // exercises actual registration-time satisfiability, not just the wiring.
            val service =
                ContinuationService(
                    repo,
                    questions,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    resultTokenService,
                    LoggerFactory.getLogger("t"),
                ) { 1000L }
            assertFailsWith<ValidationException> {
                service.create(
                    7,
                    42,
                    "h",
                    params = mapOf("other" to "x"),
                    resumeParam = "HMR_LG_RESUME",
                    resultTransformer = null,
                    ttlSec = null,
                )
            }
            coVerify(exactly = 0) { repo.create(any(), any()) }
        }

    @Test
    fun `create registers a PENDING continuation and returns its id`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.create(any(), any()) } returns 11
            val id = service().create(7, 42, "h", mapOf("HMR_LG_THREAD" to "t"), "HMR_LG_RESUME", null, ttlSec = 60)
            assertEquals(11, id)
            coVerify {
                repo.create(
                    match {
                        it.watchedProcessId == 42 && it.targetAgentName == "h" && it.expiresAt == 1060L && it.originAgentName == "asker"
                    },
                    100,
                )
            }
        }

    @Test
    fun `create copies the origin process's owner onto the continuation row`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "ci" }
            coEvery { processManager.getProcessStatus(42) } returns ProcessStatus.WORKING
            val continuationSlot = slot<Continuation>()
            coEvery { repo.create(capture(continuationSlot), any()) } returns 11

            service().create(7, 42, "h", mapOf("HMR_LG_THREAD" to "t"), "HMR_LG_RESUME", null, ttlSec = 60)

            assertEquals("ci", continuationSlot.captured.owner)
        }

    @Test
    fun `create fires immediately when the watched process is already terminal`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.create(any(), any()) } returns 11
            // The sub finished in the gap between start and insert; the live hook already ran.
            coEvery { processManager.getProcessStatus(42) } returns ProcessStatus.FINISHED
            coEvery { repo.claim(11) } returns true
            coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), any()) } returns 99

            val id = service().create(7, 42, "h", mapOf("HMR_LG_THREAD" to "t"), "HMR_LG_RESUME", null, null)

            assertEquals(11, id)
            coVerify { repo.claim(11) }
            coVerify { repo.markDispatched(11, 99, 1000L) }
        }

    @Test
    fun `create does not fire when the watched process is still running`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.create(any(), any()) } returns 11
            coEvery { processManager.getProcessStatus(42) } returns ProcessStatus.WORKING

            service().create(7, 42, "h", emptyMap(), "HMR_LG_RESUME", null, null)

            coVerify(exactly = 0) { repo.claim(any()) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // --- onProcessTerminal ---

    private fun stubFireable(continuation: Continuation) {
        val agent = mockk<Agent>(relaxed = true)
        every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
        every { agents.agents } returns mapOf("h" to agent)
        coEvery { repo.findByWatchedProcessId(continuation.watchedProcessId) } returns listOf(continuation)
        coEvery { repo.claim(continuation.id) } returns true
        coEvery {
            runStarter.start(
                eq(agent),
                eq(emptyList()),
                eq("continuation"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns
            99
    }

    @Test
    fun `onProcessTerminal links the fired run to the continuation's origin process`() =
        runTest {
            stubFireable(pending())

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), eq(7), any(), any()) }
        }

    @Test
    fun `the fired run joins the origin process's flow`() =
        runTest {
            stubFireable(pending())
            // Process 7 is itself a segment of the flow rooted at 3 — the fired run must
            // join that flow, not start one rooted at 7.
            // The gate reads the root row to check the owner's visibility, so it must
            // resolve too.
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "asker")
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                    parentProcessId = 1,
                    rootProcessId = 3,
                )

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = eq(7),
                    rootProcessId = eq(3),
                    any(),
                )
            }
        }

    @Test
    fun `the fired run drops the derived root when its owner cannot see the root row`() =
        runTest {
            // [information_disclosure / CWE-200] The fired run is attributed to the
            // continuation's owner, so a flow root that owner cannot see must not reach
            // the new row, its HMR_ROOT_PROCESS_ID env or its span. The run is stored
            // rootless instead of mis-rooted.
            stubFireable(pending().copy(owner = "ci"))
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                    parentProcessId = 1,
                    rootProcessId = 3,
                )
            // The flow root runs an agent the continuation owner holds no grant on —
            // the derived id is dropped.
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "asker", owner = "alice")
            every { authorizer.canSee("ci", "alice", "asker") } returns false

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = eq(7),
                    rootProcessId = null,
                    any(),
                )
            }
        }

    @Test
    fun `the fired run roots its own flow when the origin has no root of its own`() =
        runTest {
            stubFireable(pending())
            // A rootless origin IS the flow's root, so the fired run is rooted at the origin
            // rather than at a fresh flow of its own.
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(id = 7, status = ProcessStatus.WORKING, agentName = "asker")

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = eq(7),
                    rootProcessId = eq(7),
                    any(),
                )
            }
        }

    @Test
    fun `the fired run derives no root when the origin row is unreadable`() =
        runTest {
            stubFireable(pending())
            // An evicted or purged origin leaves the fired run parented but rootless, so it
            // reads as a root rather than being linked to a guessed id.
            coEvery { processManager.getProcessInfo(7) } returns null

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = eq(7),
                    rootProcessId = null,
                    any(),
                )
            }
        }

    @Test
    fun `a continuation chain three segments deep shares one flow root`() =
        runTest {
            stubFireable(pending())
            // Segment 1 (id 7) is the flow's root. Each fired segment registers the next
            // continuation, so the chain runs 7 -> 100 -> 101 -> 102 and every hop below the
            // root must keep deriving the same root (7) rather than re-rooting at itself.
            val rows = mutableMapOf<Int, ProcessInfo>()
            coEvery { processManager.getProcessInfo(any()) } answers {
                val id = firstArg<Int>()
                rows[id] ?: ProcessInfo(id = id, status = ProcessStatus.FINISHED, agentName = "h")
            }
            val started = mutableListOf<Pair<Int?, Int?>>()
            var nextProcessId = 100
            coEvery {
                runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                val parent = arg<Int?>(7)
                val root = arg<Int?>(8)
                started += parent to root
                val id = nextProcessId++
                rows[id] =
                    ProcessInfo(
                        id = id,
                        status = ProcessStatus.FINISHED,
                        agentName = "h",
                        parentProcessId = parent,
                        rootProcessId = root,
                    )
                id
            }

            var originId = 7
            repeat(3) {
                coEvery { repo.findByWatchedProcessId(42) } returns listOf(pending().copy(originProcessId = originId))
                service().onProcessTerminal(42, ProcessStatus.FINISHED)
                originId = nextProcessId - 1
            }

            // Three segments, each parented to the segment before it, all reporting root 7.
            assertEquals(listOf<Pair<Int?, Int?>>(7 to 7, 100 to 7, 101 to 7), started)
        }

    @Test
    fun `onProcessTerminal fires the target run with thread plus resume payload and marks dispatched`() =
        runTest {
            stubFireable(pending())
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    any(),
                    any(),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolve),
                )
            } returns
                99

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify { repo.claim(5) }
            coVerify { repo.markDispatched(5, 99, 1000L) }
            resolve.captured.invoke()
            verify {
                inputResolver.resolveFromParams(
                    match<Map<String, String>> {
                        it["HMR_LG_THREAD"] == "thread-1" &&
                            Json
                                .parseToJsonElement(it["HMR_LG_RESUME"]!!)
                                .jsonObject["status"]!!
                                .jsonPrimitive.content == "FINISHED" &&
                            Json
                                .parseToJsonElement(it["HMR_LG_RESUME"]!!)
                                .jsonObject["processId"]!!
                                .jsonPrimitive.content == "42"
                    },
                    any(),
                    true,
                )
            }
        }

    @Test
    fun `onProcessTerminal fires with the continuation's owner`() =
        runTest {
            stubFireable(pending().copy(owner = "ci"))

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify { runStarter.start(any(), any(), eq("continuation"), any(), any(), eq("ci"), any(), any(), any(), any()) }
        }

    @Test
    fun `onProcessTerminal does not let stored params inject host-controlled env names`() =
        runTest {
            stubFireable(pending().copy(params = mapOf("PATH" to "/tmp/evil:/usr/bin", "HMR_LG_THREAD" to "thread-1")))
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    any(),
                    any(),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolve),
                )
            } returns
                99

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            val env = resolve.captured.invoke()
            assertNull(env["PATH"])
            assertEquals("thread-1", env["HMR_LG_THREAD"])
        }

    @Test
    fun `onProcessTerminal passes reserved control params through to the run env`() =
        runTest {
            // pending() carries params {HMR_LG_THREAD: thread-1} and resumeParam HMR_LG_RESUME —
            // both reserved, so resolveDebug drops them; they must still reach the run env.
            stubFireable(pending())
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    any(),
                    any(),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolve),
                )
            } returns
                99

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            val env = resolve.captured.invoke()
            assertEquals("thread-1", env["HMR_LG_THREAD"])
            assertEquals(
                "FINISHED",
                Json
                    .parseToJsonElement(env["HMR_LG_RESUME"]!!)
                    .jsonObject["status"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `onProcessTerminal still delivers a non-reserved declared resume param`() =
        runTest {
            // A continuation resuming under a non-reserved param (e.g. the requestor example's
            // WORKER_RESULT) flows through the declared-input path, not the reserved passthrough.
            val continuation = pending().copy(params = emptyMap(), resumeParam = "WORKER_RESULT")
            stubFireable(continuation)
            // The declared input resolves WORKER_RESULT from the appended resume payload.
            every { inputResolver.resolveFromParams(any(), any(), any()) } returns
                mapOf("WORKER_RESULT" to "the-result")
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    any(),
                    any(),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolve),
                )
            } returns
                99

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            val env = resolve.captured.invoke()
            assertEquals("the-result", env["WORKER_RESULT"])
        }

    @Test
    fun `onProcessTerminal fires on every terminal status`() =
        runTest {
            for (status in listOf(ProcessStatus.FINISHED, ProcessStatus.FAILED, ProcessStatus.KILLED, ProcessStatus.UNKNOWN)) {
                stubFireable(pending())
                service().onProcessTerminal(42, status)
                coVerify { repo.markDispatched(5, 99, 1000L) }
            }
        }

    @Test
    fun `onProcessTerminal is idempotent - a lost claim does not fire`() =
        runTest {
            val fireableAgent = mockk<Agent>(relaxed = true)
            every { fireableAgent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to fireableAgent)
            coEvery { repo.findByWatchedProcessId(42) } returns listOf(pending())
            coEvery { repo.claim(5) } returns false
            service().onProcessTerminal(42, ProcessStatus.FINISHED)
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `onProcessTerminal applies the result transformer into the resume payload`(
        @TempDir dir: Path,
    ) = runTest {
        dir.resolve("cmd_0.out").writeText("""{"answer":"yes"}""")
        dir.resolve("cmd_0.err").writeText("")
        val transformer = ResultEnvParam(SrcType.STD_OUT, "RESULT", ResultTransformer.JsonPath("$.answer"))
        stubFireable(pending(transformer = transformer))
        // stdOut/stdErr are stored relative to the artifacts dir; resolution against it must happen.
        coEvery { processManager.getProcessExecutions(42) } returns
            listOf(ExecutionInfo("cmd", 0, 1, 0, Path.of("cmd_0.out"), Path.of("cmd_0.err"), null))
        coEvery { processManager.getProcessInfo(42) } returns mockk(relaxed = true) { every { artifactsDir } returns dir }
        val resolve = slot<suspend () -> Map<String, String>>()
        coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), capture(resolve)) } returns
            99

        service().onProcessTerminal(42, ProcessStatus.FINISHED)

        resolve.captured.invoke()
        verify {
            inputResolver.resolveFromParams(
                match<Map<String, String>> {
                    Json
                        .parseToJsonElement(it["HMR_LG_RESUME"]!!)
                        .jsonObject["result"]!!
                        .jsonPrimitive.content == "yes"
                },
                any(),
                true,
            )
        }
    }

    @Test
    fun `onProcessTerminal resumes an over-cap result by reference with a minted token`(
        @TempDir dir: Path,
    ) = runTest {
        val size = 64 * 1024 + 1
        dir.resolve("cmd_0.out").writeText("x".repeat(size))
        dir.resolve("cmd_0.err").writeText("")
        val transformer = ResultEnvParam(SrcType.STD_OUT, "RESULT", ResultTransformer.Pass)
        stubFireable(pending(transformer = transformer))
        coEvery { processManager.getProcessExecutions(42) } returns
            listOf(ExecutionInfo("cmd", 0, 1, 0, Path.of("cmd_0.out"), Path.of("cmd_0.err"), null))
        coEvery { processManager.getProcessInfo(42) } returns mockk(relaxed = true) { every { artifactsDir } returns dir }
        coEvery { resultTokenService.mint(42, "cmd_0.out", transformer, size.toLong(), "h") } returns "tok-xyz"
        val resolve = slot<suspend () -> Map<String, String>>()
        coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), capture(resolve)) } returns
            99

        service().onProcessTerminal(42, ProcessStatus.FINISHED)

        resolve.captured.invoke()
        // result is null AND a resultRef carrying the minted token is present; the token is
        // audience-bound to the continuation's target agent ("h").
        coVerify { resultTokenService.mint(42, "cmd_0.out", transformer, size.toLong(), "h") }
        verify {
            inputResolver.resolveFromParams(
                match<Map<String, String>> {
                    val payload = Json.parseToJsonElement(it["HMR_LG_RESUME"]!!).jsonObject
                    val resultIsNull = payload["result"]!! is kotlinx.serialization.json.JsonNull
                    val ref = payload["resultRef"]!!.jsonObject
                    resultIsNull &&
                        ref["token"]!!.jsonPrimitive.content == "tok-xyz" &&
                        ref["path"]!!.jsonPrimitive.content == "cmd_0.out" &&
                        ref["processId"]!!.jsonPrimitive.content == "42" &&
                        ref["sizeBytes"]!!.jsonPrimitive.content == size.toString()
                },
                any(),
                true,
            )
        }
    }

    @Test
    fun `onProcessTerminal does not mint a token for a transformed result the fetch endpoint cannot serve`(
        @TempDir dir: Path,
    ) = runTest {
        // A FAILED sub's last execution is the command that broke — e.g. a Claude step whose
        // NDJSON stdout runs to several MB. GET /callback/results refuses to transform anything
        // over RESULT_TRANSFORM_MAX_BYTES, so a ref minted here could never be redeemed; resume
        // with result = null and no ref so the parent's own status check decides.
        val size = RESULT_TRANSFORM_MAX_BYTES.toInt() + 1
        dir.resolve("cmd_1.out").writeText("x".repeat(size))
        dir.resolve("cmd_1.err").writeText("")
        val transformer = ResultEnvParam(SrcType.STD_OUT, "RESULT", ResultTransformer.Pass)
        stubFireable(pending(transformer = transformer))
        coEvery { processManager.getProcessExecutions(42) } returns
            listOf(ExecutionInfo("cmd", 0, 1, -1, Path.of("cmd_1.out"), Path.of("cmd_1.err"), null))
        coEvery { processManager.getProcessInfo(42) } returns mockk(relaxed = true) { every { artifactsDir } returns dir }
        val resolve = slot<suspend () -> Map<String, String>>()
        coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), capture(resolve)) } returns
            99

        service().onProcessTerminal(42, ProcessStatus.FAILED)

        resolve.captured.invoke()
        coVerify(exactly = 0) { resultTokenService.mint(any(), any(), any(), any(), any()) }
        verify {
            inputResolver.resolveFromParams(
                match<Map<String, String>> {
                    val payload = Json.parseToJsonElement(it["HMR_LG_RESUME"]!!).jsonObject
                    payload["status"]!!.jsonPrimitive.content == "FAILED" &&
                        payload["result"]!! is kotlinx.serialization.json.JsonNull &&
                        payload["resultRef"] == null
                },
                any(),
                true,
            )
        }
    }

    @Test
    fun `onProcessTerminal marks FAILED when the run fails for a permanent reason`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByWatchedProcessId(42) } returns listOf(pending())
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                ValidationException("input 'X' is required")
            service().onProcessTerminal(42, ProcessStatus.FINISHED)
            coVerify { repo.markFailed(5, any(), 1000L) }
            coVerify(exactly = 0) { repo.releaseForRetry(any(), any(), any(), any()) }
        }

    // --- transient-failure retry ---

    @Test
    fun `a transient fire failure is scheduled for retry instead of marked FAILED`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByWatchedProcessId(42) } returns listOf(pending())
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CostCapExceededException("global", 1.0, 2.0)

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            // First attempt: one minute of backoff on the fixed clock (1000L).
            coVerify { repo.releaseForRetry(5, any(), 1, 1060L) }
            coVerify(exactly = 0) { repo.markFailed(any(), any(), any()) }
        }

    @Test
    fun `retry backoff doubles with each spent attempt`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByWatchedProcessId(42) } returns listOf(pending().copy(fireAttempts = 3))
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                ProcessManagerAtCapacityException(10)

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            // 4th attempt → 60 << 3 = 480s.
            coVerify { repo.releaseForRetry(5, any(), 4, 1480L) }
        }

    @Test
    fun `retry gives up and marks FAILED once the attempt budget is spent`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByWatchedProcessId(42) } returns listOf(pending().copy(fireAttempts = 7))
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CostCapExceededException("global", 1.0, 2.0)

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            val reason = slot<String>()
            coVerify { repo.markFailed(5, capture(reason), 1000L) }
            assertTrue(reason.captured.contains("gave up after 8 attempts"), reason.captured)
            coVerify(exactly = 0) { repo.releaseForRetry(any(), any(), any(), any()) }
        }

    @Test
    fun `sweepRetries re-fires a due retry whose watched process is terminal`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findDueRetries(1000L) } returns listOf(pending().copy(fireAttempts = 1))
            coEvery { repo.claim(5) } returns true
            coEvery { processManager.getProcessStatus(42) } returns ProcessStatus.FINISHED
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().sweepRetries()

            coVerify { repo.markDispatched(5, 99, 1000L) }
        }

    @Test
    fun `sweepRetries leaves a due retry alone while its watched process is still running`() =
        runTest {
            coEvery { repo.findDueRetries(1000L) } returns listOf(pending())
            coEvery { processManager.getProcessStatus(42) } returns ProcessStatus.WORKING

            service().sweepRetries()

            coVerify(exactly = 0) { repo.claim(any()) }
        }

    @Test
    fun `fire marks FAILED when the target agent is no longer dispatch-enabled`() =
        runTest {
            // Registered while dispatchable, but the target was reloaded to dispatch.enabled=false
            // before the watched process terminated. The stale continuation must not fire.
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = false)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByWatchedProcessId(42) } returns listOf(pending())
            coEvery { repo.claim(5) } returns true

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify { repo.markFailed(5, match { it.contains("no longer an allowed dispatch target") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `onProcessTerminal marks FAILED when the target agent is gone`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.findByWatchedProcessId(42) } returns listOf(pending())
            coEvery { repo.claim(5) } returns true
            service().onProcessTerminal(42, ProcessStatus.FINISHED)
            coVerify { repo.markFailed(5, match { it.contains("h") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `fire marks FAILED and does not start when the hop is unauthorized`() =
        runTest {
            stubFireable(pending())
            every { authorizer.canDispatchHop(null, "asker", "h") } returns false

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify { repo.markFailed(5, match { it.contains("not authorized") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `fire starts the run when authorized`() =
        runTest {
            stubFireable(pending())
            every { authorizer.canDispatchHop(null, "asker", "h") } returns true

            service().onProcessTerminal(42, ProcessStatus.FINISHED)

            coVerify { repo.markDispatched(5, 99, 1000L) }
        }

    @Test
    fun `onProcessTerminal swallows a lookup failure`() =
        runTest {
            coEvery { repo.findByWatchedProcessId(42) } throws RuntimeException("db down")
            service().onProcessTerminal(42, ProcessStatus.FINISHED)
            coVerify(exactly = 0) { repo.claim(any()) }
        }

    @Test
    fun `onProcessTerminal swallows a join lookup failure`() =
        runTest {
            coEvery { repo.findByWatchedProcessId(42) } returns emptyList()
            coEvery { repo.findJoinsByWatchedProcessId(42) } throws RuntimeException("db down")
            service().onProcessTerminal(42, ProcessStatus.FINISHED)
            coVerify(exactly = 0) { repo.claim(any()) }
        }

    // --- join continuations ---

    private fun joinPending(
        id: Int = 5,
        watches: List<JoinWatch>,
    ) = Continuation(
        id = id,
        watchedProcessId = 0,
        status = ContinuationStatus.PENDING,
        targetAgentName = "h",
        params = mapOf("HMR_LG_THREAD" to "thread-1"),
        resumeParam = "HMR_LG_RESUME",
        resultTransformer = null,
        originProcessId = 7,
        originAgentName = "asker",
        expiresAt = null,
        createdAt = 1,
        kind = ContinuationKind.JOIN,
        watches = watches,
    )

    private fun dispatchableTarget() {
        every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
        val agent = mockk<Agent>(relaxed = true)
        every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
        every { agents.agents } returns mapOf("h" to agent)
    }

    @Test
    fun `validateJoin rejects an empty sub list`() =
        runTest {
            dispatchableTarget()
            assertFailsWith<ValidationException> {
                service().validateJoin(7, "h", subAgents = emptyList(), resumeParam = "HMR_LG_RESUME", params = emptyMap(), ttlSec = null)
            }
        }

    @Test
    fun `validateJoin rejects a sub list larger than maxJoinSubs`() =
        runTest {
            dispatchableTarget()
            val tooMany = (1..5).map { "sub$it" }
            val ex =
                assertFailsWith<ValidationException> {
                    service(DispatchConfig(maxJoinSubs = 4))
                        .validateJoin(7, "h", subAgents = tooMany, resumeParam = "HMR_LG_RESUME", params = emptyMap(), ttlSec = null)
                }
            assertTrue(ex.message?.contains("maxJoinSubs") == true || ex.message?.contains("4") == true)
        }

    @Test
    fun `validateJoin rejects a non-dispatchable sub agent`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val target = mockk<Agent>().also { every { it.dispatch } returns AgentDispatchConfig(enabled = true) }
            val sub = mockk<Agent>().also { every { it.dispatch } returns null }
            every { agents.agents } returns mapOf("h" to target, "sub" to sub)
            assertFailsWith<AgentNotDispatchableException> {
                service().validateJoin(7, "h", subAgents = listOf("sub"), resumeParam = "HMR_LG_RESUME", params = emptyMap(), ttlSec = null)
            }
        }

    @Test
    fun `validateJoin reports an unauthorized sub as not found`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val target = mockk<Agent>(relaxed = true).also { every { it.dispatch } returns AgentDispatchConfig(enabled = true) }
            val sub = mockk<Agent>(relaxed = true).also { every { it.dispatch } returns AgentDispatchConfig(enabled = true) }
            every { agents.agents } returns mapOf("h" to target, "sub" to sub)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "asker", "h") } returns true
            every { authorizer.canDispatchHop("alice", "asker", "sub") } returns false

            assertFailsWith<DispatchAgentNotFoundException> {
                service().validateJoin(
                    7,
                    "h",
                    subAgents = listOf("sub"),
                    resumeParam = "HMR_LG_RESUME",
                    params = emptyMap(),
                    ttlSec = null,
                )
            }
        }

    @Test
    fun `createJoin registers a JOIN over N watches and returns its id`() =
        runTest {
            dispatchableTarget()
            val watches = listOf(JoinWatch(10, "int-a"), JoinWatch(11, "int-b"))
            coEvery { repo.createJoin(any(), any(), any()) } returns 12
            coEvery { processManager.getProcessStatus(any()) } returns ProcessStatus.WORKING

            val id =
                service().createJoin(
                    7,
                    watches,
                    target = "h",
                    params = mapOf("HMR_LG_THREAD" to "t"),
                    resumeParam = "HMR_LG_RESUME",
                    ttlSec = 60,
                )

            assertEquals(12, id)
            coVerify {
                repo.createJoin(
                    match { it.kind == ContinuationKind.JOIN && it.watchedProcessId == 0 && it.expiresAt == 1060L },
                    match { it.size == 2 },
                    100,
                )
            }
            coVerify(exactly = 0) { repo.claim(any()) }
        }

    @Test
    fun `createJoin copies the origin process's owner onto the continuation row`() =
        runTest {
            dispatchableTarget()
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "ci" }
            val watches = listOf(JoinWatch(10, "int-a"))
            coEvery { processManager.getProcessStatus(any()) } returns ProcessStatus.WORKING
            val continuationSlot = slot<Continuation>()
            coEvery { repo.createJoin(capture(continuationSlot), any(), any()) } returns 12

            service().createJoin(7, watches, target = "h", params = emptyMap(), resumeParam = "HMR_LG_RESUME", ttlSec = null)

            assertEquals("ci", continuationSlot.captured.owner)
        }

    @Test
    fun `createJoin maps cap rejection to TooManyPendingContinuationsException`() =
        runTest {
            dispatchableTarget()
            coEvery { repo.createJoin(any(), any(), any()) } returns null
            assertFailsWith<TooManyPendingContinuationsException> {
                service(
                    DispatchConfig(maxPending = 1),
                ).createJoin(7, listOf(JoinWatch(10, "int-a")), "h", emptyMap(), "HMR_LG_RESUME", null)
            }
        }

    @Test
    fun `createJoin reports an unauthorized resume target as not found`() =
        runTest {
            dispatchableTarget()
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "asker", "h") } returns false
            assertFailsWith<DispatchAgentNotFoundException> {
                service().createJoin(7, listOf(JoinWatch(10, "int-a")), "h", emptyMap(), "HMR_LG_RESUME", null)
            }
            coVerify(exactly = 0) { repo.createJoin(any(), any(), any()) }
        }

    @Test
    fun `createJoin fires immediately when all watched are already terminal`() =
        runTest {
            dispatchableTarget()
            val watches = listOf(JoinWatch(10, "int-a"), JoinWatch(11, "int-b"))
            coEvery { repo.createJoin(any(), any(), any()) } returns 12
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            coEvery { processManager.getProcessStatus(11) } returns ProcessStatus.FINISHED
            coEvery { repo.claim(12) } returns true
            coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().createJoin(7, watches, "h", emptyMap(), "HMR_LG_RESUME", null)

            coVerify { repo.claim(12) }
            coVerify { repo.markDispatched(12, 99, 1000L) }
        }

    @Test
    fun `createJoin does not fire while a watched sub is still running`() =
        runTest {
            dispatchableTarget()
            val watches = listOf(JoinWatch(10, "int-a"), JoinWatch(11, "int-b"))
            coEvery { repo.createJoin(any(), any(), any()) } returns 12
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            coEvery { processManager.getProcessStatus(11) } returns ProcessStatus.WORKING

            service().createJoin(7, watches, "h", emptyMap(), "HMR_LG_RESUME", null)

            coVerify(exactly = 0) { repo.claim(any()) }
        }

    private fun stubJoinFireable(continuation: Continuation) {
        val agent = mockk<Agent>(relaxed = true)
        every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
        every { agents.agents } returns mapOf("h" to agent)
        coEvery { repo.findJoinsByWatchedProcessId(any()) } returns listOf(continuation)
        coEvery { repo.claim(continuation.id) } returns true
        coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), any()) } returns 99
    }

    @Test
    fun `onProcessTerminal fires the join once after the last sub terminates, not before`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a"), JoinWatch(11, "int-b")))
            stubJoinFireable(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED

            // First sub done, second still running: must not fire.
            coEvery { processManager.getProcessStatus(11) } returns ProcessStatus.WORKING
            service().onProcessTerminal(10, ProcessStatus.FINISHED)
            coVerify(exactly = 0) { repo.claim(5) }

            // Second sub now done: the join fires exactly once.
            coEvery { processManager.getProcessStatus(11) } returns ProcessStatus.FINISHED
            service().onProcessTerminal(11, ProcessStatus.FINISHED)
            coVerify { repo.claim(5) }
            coVerify { repo.markDispatched(5, 99, 1000L) }
        }

    @Test
    fun `onProcessTerminal join fire passes the continuation's owner`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a"))).copy(owner = "ci")
            stubJoinFireable(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED

            service().onProcessTerminal(10, ProcessStatus.FINISHED)

            coVerify { runStarter.start(any(), any(), eq("continuation"), any(), any(), eq("ci"), any(), any(), any(), any()) }
        }

    @Test
    fun `the fired join run joins the origin process's flow`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a")))
            stubJoinFireable(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            // Process 7 is itself a segment of the flow rooted at 3 — the fired run must
            // join that flow, not start one rooted at 7.
            // The gate reads the root row to check the owner's visibility, so it must
            // resolve too.
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "asker")
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                    parentProcessId = 1,
                    rootProcessId = 3,
                )

            service().onProcessTerminal(10, ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = eq(7),
                    rootProcessId = eq(3),
                    any(),
                )
            }
        }

    @Test
    fun `the fired join run drops the derived root when its owner cannot see the root row`() =
        runTest {
            // Same denial path as the single fire: the fired run carries the
            // continuation owner's grant, not the origin process's.
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a"))).copy(owner = "ci")
            stubJoinFireable(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                    parentProcessId = 1,
                    rootProcessId = 3,
                )
            // The flow root runs an agent the continuation owner holds no grant on —
            // the derived id is dropped.
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "asker", owner = "alice")
            every { authorizer.canSee("ci", "alice", "asker") } returns false

            service().onProcessTerminal(10, ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = eq(7),
                    rootProcessId = null,
                    any(),
                )
            }
        }

    @Test
    fun `the fired join run roots its own flow when the origin has no root of its own`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a")))
            stubJoinFireable(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            // A rootless origin IS the flow's root, so the fired run is rooted at the origin
            // rather than at a fresh flow of its own.
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(id = 7, status = ProcessStatus.WORKING, agentName = "asker")

            service().onProcessTerminal(10, ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = eq(7),
                    rootProcessId = eq(7),
                    any(),
                )
            }
        }

    @Test
    fun `the fired join run derives no root when the origin row is unreadable`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a")))
            stubJoinFireable(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            // An evicted or purged origin leaves the fired run parented but rootless, so it
            // reads as a root rather than being linked to a guessed id.
            coEvery { processManager.getProcessInfo(7) } returns null

            service().onProcessTerminal(10, ProcessStatus.FINISHED)

            coVerify {
                runStarter.start(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = eq(7),
                    rootProcessId = null,
                    any(),
                )
            }
        }

    @Test
    fun `onProcessTerminal join fire is idempotent - a lost claim does not fire`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a")))
            val fireableAgent = mockk<Agent>(relaxed = true)
            every { fireableAgent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to fireableAgent)
            coEvery { repo.findJoinsByWatchedProcessId(10) } returns listOf(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            coEvery { repo.claim(5) } returns false

            service().onProcessTerminal(10, ProcessStatus.FINISHED)

            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `fireJoin marks FAILED and does not start when the hop is unauthorized`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a")))
            stubJoinFireable(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            every { authorizer.canDispatchHop(null, "asker", "h") } returns false

            service().onProcessTerminal(10, ProcessStatus.FINISHED)

            coVerify { repo.markFailed(5, match { it.contains("not authorized") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `fireJoin marks FAILED when the target agent is no longer dispatch-enabled`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a")))
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = false)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findJoinsByWatchedProcessId(any()) } returns listOf(join)
            coEvery { repo.claim(5) } returns true
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED

            service().onProcessTerminal(10, ProcessStatus.FINISHED)

            coVerify { repo.markFailed(5, match { it.contains("no longer an allowed dispatch target") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `join aggregates per-sub results into the resume map keyed by interrupt id`(
        @TempDir dir: Path,
    ) = runTest {
        val a = dir.resolve("a.out").also { it.writeText("""{"v":"alpha"}""") }
        dir.resolve("a.err").writeText("")
        val b = dir.resolve("b.out").also { it.writeText("""{"v":"beta"}""") }
        dir.resolve("b.err").writeText("")
        val tf = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.v"))
        val join =
            joinPending(
                watches =
                    listOf(
                        JoinWatch(10, "int-a", resultTransformer = tf),
                        JoinWatch(11, "int-b", resultTransformer = tf),
                    ),
            )
        stubJoinFireable(join)
        coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
        coEvery { processManager.getProcessStatus(11) } returns ProcessStatus.FINISHED
        coEvery { processManager.getProcessExecutions(10) } returns
            listOf(ExecutionInfo("c", 0, 1, 0, Path.of("a.out"), Path.of("a.err"), null))
        coEvery { processManager.getProcessExecutions(11) } returns
            listOf(ExecutionInfo("c", 0, 1, 0, Path.of("b.out"), Path.of("b.err"), null))
        coEvery { processManager.getProcessInfo(any()) } returns mockk(relaxed = true) { every { artifactsDir } returns dir }
        val resolve = slot<suspend () -> Map<String, String>>()
        coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), capture(resolve)) } returns
            99
        val captured = slot<Map<String, String>>()
        every { inputResolver.resolveFromParams(capture(captured), any(), any()) } returns emptyMap()

        service().onProcessTerminal(11, ProcessStatus.FINISHED)
        resolve.captured.invoke()

        // Outer map: one entry per interrupt id; each value is the single-fanout JSON string.
        val outer = Json.parseToJsonElement(captured.captured["HMR_LG_RESUME"]!!).jsonObject
        assertEquals(setOf("int-a", "int-b"), outer.keys)
        val subA = Json.parseToJsonElement(outer["int-a"]!!.jsonPrimitive.content).jsonObject
        val subB = Json.parseToJsonElement(outer["int-b"]!!.jsonPrimitive.content).jsonObject
        assertEquals("alpha", subA["result"]!!.jsonPrimitive.content)
        assertEquals("10", subA["processId"]!!.jsonPrimitive.content)
        assertEquals("beta", subB["result"]!!.jsonPrimitive.content)
        assertEquals("11", subB["processId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `join over-cap sub resumes that entry by reference with a minted token`(
        @TempDir dir: Path,
    ) = runTest {
        val size = 64 * 1024 + 1
        dir.resolve("big.out").writeText("x".repeat(size))
        dir.resolve("big.err").writeText("")
        val tf = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.Pass)
        val join = joinPending(watches = listOf(JoinWatch(10, "int-a", resultTransformer = tf)))
        stubJoinFireable(join)
        coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
        coEvery { processManager.getProcessExecutions(10) } returns
            listOf(ExecutionInfo("c", 0, 1, 0, Path.of("big.out"), Path.of("big.err"), null))
        coEvery { processManager.getProcessInfo(10) } returns mockk(relaxed = true) { every { artifactsDir } returns dir }
        coEvery { resultTokenService.mint(10, "big.out", tf, size.toLong(), "h") } returns "tok-xyz"
        val resolve = slot<suspend () -> Map<String, String>>()
        coEvery { runStarter.start(any(), any(), eq("continuation"), any(), any(), any(), any(), any(), any(), capture(resolve)) } returns
            99
        val captured = slot<Map<String, String>>()
        every { inputResolver.resolveFromParams(capture(captured), any(), any()) } returns emptyMap()

        service().onProcessTerminal(10, ProcessStatus.FINISHED)
        resolve.captured.invoke()

        coVerify { resultTokenService.mint(10, "big.out", tf, size.toLong(), "h") }
        val outer = Json.parseToJsonElement(captured.captured["HMR_LG_RESUME"]!!).jsonObject
        val sub = Json.parseToJsonElement(outer["int-a"]!!.jsonPrimitive.content).jsonObject
        assertTrue(sub["result"] is kotlinx.serialization.json.JsonNull)
        assertEquals("tok-xyz", sub["resultRef"]!!.jsonObject["token"]!!.jsonPrimitive.content)
    }

    @Test
    fun `join fires on partial failure when one sub is FAILED`() =
        runTest {
            val join = joinPending(watches = listOf(JoinWatch(10, "int-a"), JoinWatch(11, "int-b")))
            stubJoinFireable(join)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            coEvery { processManager.getProcessStatus(11) } returns ProcessStatus.FAILED
            val captured = slot<Map<String, String>>()
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    any(),
                    any(),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolve),
                )
            } returns
                99
            every { inputResolver.resolveFromParams(capture(captured), any(), any()) } returns emptyMap()

            service().onProcessTerminal(11, ProcessStatus.FAILED)
            resolve.captured.invoke()

            coVerify { repo.markDispatched(5, 99, 1000L) }
            val outer = Json.parseToJsonElement(captured.captured["HMR_LG_RESUME"]!!).jsonObject
            val failed = Json.parseToJsonElement(outer["int-b"]!!.jsonPrimitive.content).jsonObject
            assertEquals("FAILED", failed["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `refirePendingTerminal fires only all-terminal joins`() =
        runTest {
            val partial = joinPending(id = 1, watches = listOf(JoinWatch(10, "int-a"), JoinWatch(11, "int-b")))
            val done = joinPending(id = 2, watches = listOf(JoinWatch(20, "int-c")))
            val fireableAgent = mockk<Agent>(relaxed = true)
            every { fireableAgent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to fireableAgent)
            coEvery { repo.list(ContinuationStatus.PENDING) } returns listOf(partial, done)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.FINISHED
            coEvery { processManager.getProcessStatus(11) } returns ProcessStatus.WORKING
            coEvery { processManager.getProcessStatus(20) } returns ProcessStatus.FINISHED
            coEvery { repo.claim(2) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().refirePendingTerminal()

            coVerify(exactly = 0) { repo.claim(1) }
            coVerify { repo.claim(2) }
            coVerify { repo.markDispatched(2, 99, 1000L) }
        }

    // --- cancel ---

    @Test
    fun `cancel cancels a PENDING continuation`() =
        runTest {
            coEvery { repo.get(5) } returns pending()
            coEvery { repo.cancel(5) } returns pending().copy(status = ContinuationStatus.CANCELLED)
            service().cancel(5)
            coVerify { repo.cancel(5) }
        }

    @Test
    fun `cancel rejects a missing continuation`() =
        runTest {
            coEvery { repo.get(5) } returns null
            assertFailsWith<ContinuationNotFoundException> { service().cancel(5) }
        }

    @Test
    fun `cancel rejects a non-pending continuation`() =
        runTest {
            coEvery { repo.get(5) } returns pending().copy(status = ContinuationStatus.DISPATCHED)
            assertFailsWith<ContinuationNotPendingException> { service().cancel(5) }
            coVerify(exactly = 0) { repo.cancel(any()) }
        }

    // --- sweep / boot ---

    @Test
    fun `sweepExpired delegates to expirePending`() =
        runTest {
            coEvery { repo.expirePending(1000L) } returns listOf(pending().copy(status = ContinuationStatus.EXPIRED))
            service().sweepExpired()
            coVerify { repo.expirePending(1000L) }
        }

    @Test
    fun `failOrphanedDispatching delegates to the repository`() =
        runTest {
            service().failOrphanedDispatching()
            coVerify { repo.failOrphanedDispatching(1000L) }
        }

    @Test
    fun `refirePendingTerminal fires only continuations whose watched process is terminal`() =
        runTest {
            val live = pending(id = 1, watched = 10)
            val done = pending(id = 2, watched = 20)
            val fireableAgent = mockk<Agent>(relaxed = true)
            every { fireableAgent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to fireableAgent)
            coEvery { repo.list(ContinuationStatus.PENDING) } returns listOf(live, done)
            coEvery { processManager.getProcessStatus(10) } returns ProcessStatus.WORKING
            coEvery { processManager.getProcessStatus(20) } returns ProcessStatus.FINISHED
            coEvery { repo.claim(1) } returns true
            coEvery { repo.claim(2) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().refirePendingTerminal()

            coVerify(exactly = 0) { repo.claim(1) }
            coVerify { repo.claim(2) }
            coVerify { repo.markDispatched(2, 99, 1000L) }
        }

    @Test
    fun `resume payload is well-formed JSON with status, processId, and null result`() =
        runTest {
            stubFireable(pending())
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    any(),
                    any(),
                    eq("continuation"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolve),
                )
            } returns
                99
            val captured = slot<Map<String, String>>()
            every { inputResolver.resolveFromParams(capture(captured), any(), any()) } returns emptyMap()

            service().onProcessTerminal(42, ProcessStatus.KILLED)
            resolve.captured.invoke()

            val payload = Json.parseToJsonElement(captured.captured["HMR_LG_RESUME"]!!).jsonObject
            assertEquals("KILLED", payload["status"]!!.jsonPrimitive.content)
            assertEquals("42", payload["processId"]!!.jsonPrimitive.content)
            assertTrue(payload["result"] is kotlinx.serialization.json.JsonNull)
        }

    @Test
    fun `pruneSettled prunes rows created more than settledRetentionSec before now`() =
        runTest {
            coEvery { repo.pruneSettled(any()) } returns 2
            service(DispatchConfig(settledRetentionSec = 3_600)).pruneSettled()
            coVerify(exactly = 1) { repo.pruneSettled(1000L - 3_600) }
        }
}
