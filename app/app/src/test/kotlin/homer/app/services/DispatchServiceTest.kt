package homer.app.services

import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.DispatchGroupAnswersTooLargeException
import homer.app.exceptions.DispatchNotPendingException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.ServerDrainingException
import homer.app.exceptions.TooManyPendingDispatchesException
import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentContinuationConfig
import homer.app.models.AgentDispatchConfig
import homer.app.models.Command
import homer.app.models.DispatchConfig
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.QuestionsConfig
import homer.app.models.Transformer
import homer.app.services.persistence.DispatchRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DispatchServiceTest {
    private val repo = mockk<DispatchRepository>(relaxed = true)
    private val questions = mockk<QuestionsService>(relaxed = true)
    private val agents = mockk<AgentsService>()
    private val runStarter = mockk<AgentRunStarter>()
    private val inputResolver = mockk<AgentInputResolver>(relaxed = true)
    private val processManager = mockk<ProcessManager>(relaxed = true)
    private val broadcaster = mockk<ProcessEventBroadcaster>(relaxed = true)

    /**
     * The group fire gate. Relaxed, so it reports "not every member answered" by default
     * ([DispatchGroupService.aggregateAnswers] returns null) — the group tests stub it.
     */
    private val groupService = mockk<DispatchGroupService>(relaxed = true)
    private val authorizer =
        mockk<Authorizer>().also {
            every { it.canDispatchHop(any(), any(), any()) } returns true
            // The fire paths gate the derived flow root on the fired run owner's
            // visibility; the denial case has its own test below.
            every { it.canSee(any(), any(), any()) } returns true
        }

    private fun service(config: DispatchConfig = DispatchConfig()) =
        DispatchService(
            repo,
            questions,
            agents,
            authorizer,
            runStarter,
            inputResolver,
            processManager,
            broadcaster,
            groupService,
            LoggerFactory.getLogger("t"),
            config,
        ) { 1000L }

    @Test
    fun `create rejects when asker registration is closed`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = true)
            assertFailsWith<QuestionsClosedForProcessException> {
                service().create(
                    7,
                    text = "q",
                    options = emptyList(),
                    agent = "h",
                    answerParam = "answer",
                    params = emptyMap(),
                    ttlSec = null,
                )
            }
        }

    @Test
    fun `create rejects unknown target agent`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            every { agents.agents } returns emptyMap()
            assertFailsWith<DispatchAgentNotFoundException> {
                service().create(7, "q", emptyList(), "missing", "answer", emptyMap(), null)
            }
        }

    @Test
    fun `create rejects a non-dispatchable target agent`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            // Both null (no dispatch block) and explicit enabled=false must reject —
            // only enabled=true opts an agent in as a dispatch target.
            val noBlock = mockk<Agent>().also { every { it.dispatch } returns null }
            val disabled = mockk<Agent>().also { every { it.dispatch } returns AgentDispatchConfig(enabled = false) }
            every { agents.agents } returns mapOf("noblock" to noBlock, "disabled" to disabled)
            assertFailsWith<AgentNotDispatchableException> {
                service().create(7, "q", emptyList(), "noblock", "answer", emptyMap(), null)
            }
            assertFailsWith<AgentNotDispatchableException> {
                service().create(7, "q", emptyList(), "disabled", "answer", emptyMap(), null)
            }
        }

    @Test
    fun `create maps cap rejection to TooManyPendingDispatchesException`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.create(any(), any(), any()) } returns null
            assertFailsWith<TooManyPendingDispatchesException> {
                service(DispatchConfig(maxPending = 1)).create(
                    7,
                    "q",
                    emptyList(),
                    "h",
                    "answer",
                    emptyMap(),
                    null,
                )
            }
        }

    @Test
    fun `create rejects an answerParam targeting a non-control reserved env name`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)

            // Without this, answerParam is the second injection vector: the operator's
            // answer text would land in the target's PATH.
            assertFailsWith<ValidationException> {
                service().create(7, "q", emptyList(), "h", "PATH", emptyMap(), null)
            }
        }

    // ADR-0019: create enforces the question contract `ask` enforces, through
    // QuestionsService.validateQuestionInput — without that call a dispatch registers a
    // question the ask path would have refused.
    @Test
    fun `create refuses question input the questions service rejects and registers nothing`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            val limits = QuestionsConfig()
            // One case per refusal validateQuestionInput raises: blank text, over-limit
            // text, blank option, duplicate options, over-cap option count.
            val refusals =
                listOf(
                    Triple("", emptyList(), "text"),
                    Triple("x".repeat(limits.maxTextLength + 1), emptyList(), "text"),
                    Triple("q", listOf(" "), "options"),
                    Triple("q", listOf("a", "a"), "options"),
                    Triple("q", List(limits.maxOptions + 1) { "o$it" }, "options"),
                )

            for ((text, options, field) in refusals) {
                every { questions.validateQuestionInput(text, options) } throws
                    ValidationException("question input refused", field)
                val refused =
                    assertFailsWith<ValidationException> {
                        service().create(7, text, options, "h", "answer", emptyMap(), null)
                    }
                assertEquals(field, refused.field)
            }
            coVerify(exactly = 0) { repo.create(any(), any(), any()) }
        }

    // ADR-0023: the ttl is refused on the non-positive side of the boundary only, the
    // caller's ttl wins over the configured default, and the expiry is counted from now.
    @Test
    fun `create refuses a non-positive ttl and stores the ttl-derived expiry`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            val stored = slot<Dispatch>()
            coEvery { repo.create(any(), capture(stored), any()) } returns 5

            val zero =
                assertFailsWith<ValidationException> {
                    service().create(7, "q", emptyList(), "h", "answer", emptyMap(), ttlSec = 0)
                }
            assertEquals("ttlSec", zero.field)
            coVerify(exactly = 0) { repo.create(any(), any(), any()) }

            // ttlSec = 1 is the accepted side of the boundary.
            service().create(7, "q", emptyList(), "h", "answer", emptyMap(), ttlSec = 1)
            assertEquals(1001L, stored.captured.expiresAt)

            // A caller-supplied ttl wins over the default: only that branch can produce
            // 1060 here, since the configured default would store 1300.
            service(DispatchConfig(defaultTtlSec = 300))
                .create(7, "q", emptyList(), "h", "answer", emptyMap(), ttlSec = 60)
            assertEquals(1060L, stored.captured.expiresAt)
            service(DispatchConfig(defaultTtlSec = 300))
                .create(7, "q", emptyList(), "h", "answer", emptyMap(), ttlSec = null)
            assertEquals(1300L, stored.captured.expiresAt)

            // No ttl and no default: the dispatch never expires.
            service(DispatchConfig(defaultTtlSec = null))
                .create(7, "q", emptyList(), "h", "answer", emptyMap(), ttlSec = null)
            assertNull(stored.captured.expiresAt)
        }

    // ADR-0052: the target's `secret: true` names are snapshotted when the row is registered,
    // because the definition may be edited before the fire — without the snapshot a stored
    // value whose flag was flipped becomes a cleartext trace tag or an unredacted API field.
    @Test
    fun `create snapshots the target's secret input names at registration`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val target =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("echo"))),
                    dispatch = AgentDispatchConfig(enabled = true),
                    inputs = listOf(Param(paramName = "token", envName = "API_TOKEN", secret = true)),
                )
            every { agents.agents } returns mapOf("h" to target)
            val stored = slot<Dispatch>()
            coEvery { repo.create(any(), capture(stored), any()) } returns 5

            service().create(7, "q", emptyList(), "h", "answer", emptyMap(), null)

            assertEquals(setOf("API_TOKEN"), stored.captured.secretInputKeys)
            assertEquals(setOf("token"), stored.captured.secretParamNames)
        }

    @Test
    fun `create accepts the langgraph resume marker as answerParam`() =
        runTest {
            // The LangGraph HITL gate registers its ask-and-dispatch with
            // answerParam=HMR_LG_RESUME — reserved, but a control marker, so it must pass.
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.create(any(), any(), any()) } returns 5

            val result = service().create(7, "q", emptyList(), "h", "HMR_LG_RESUME", emptyMap(), null)

            assertEquals(5, result.dispatchId)
        }

    @Test
    fun `create rejects when the target's required input cannot be satisfied by params or answerParam`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
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
                DispatchService(
                    repo,
                    questions,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    broadcaster,
                    groupService,
                    LoggerFactory.getLogger("t"),
                ) { 1000L }
            assertFailsWith<ValidationException> {
                service.create(7, "q", emptyList(), "h", answerParam = "answer", params = mapOf("other" to "x"), ttlSec = null)
            }
            coVerify(exactly = 0) { repo.create(any(), any(), any()) }
        }

    @Test
    fun `create counts the origin run's stored inputs for an inheriting same-agent target, and not without the flag`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("h", closed = false)
            val inheriting =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("echo"))),
                    dispatch = AgentDispatchConfig(enabled = true),
                    continuation = AgentContinuationConfig(inheritInputs = true),
                    inputs = listOf(Param(paramName = "ticket", envName = "TICKET_ID", required = true)),
                )
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "h",
                    inputParams = mapOf("TICKET_ID" to "123"),
                )
            coEvery { repo.create(any(), any(), any()) } returns 5
            // A real AgentInputResolver: the point is registration-time satisfiability.
            val service =
                DispatchService(
                    repo,
                    questions,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    broadcaster,
                    groupService,
                    LoggerFactory.getLogger("t"),
                ) { 1000L }

            // The origin run already carries TICKET_ID, so the required input is satisfiable
            // without the caller passing it. ADR-0101.
            every { agents.agents } returns mapOf("h" to inheriting)
            service.create(7, "q", emptyList(), "h", "answer", emptyMap(), null)
            coVerify(exactly = 1) { repo.create(any(), any(), any()) }

            // The same target with the flag off is unsatisfiable, exactly as before.
            every { agents.agents } returns mapOf("h" to inheriting.copy(continuation = null))
            assertFailsWith<ValidationException> {
                service.create(7, "q", emptyList(), "h", "answer", emptyMap(), null)
            }
        }

    @Test
    fun `fire inherits the origin run's inputs, and the dispatch's own params and answer win`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.name } returns "h"
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agent.continuation } returns AgentContinuationConfig(inheritInputs = true)
            every { agent.effectiveInputs } returns
                listOf(
                    Param(paramName = "ticket", envName = "TICKET_ID"),
                    Param(paramName = "region", envName = "REGION"),
                )
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(
                    id = 5,
                    questionId = "q1",
                    status = DispatchStatus.PENDING,
                    agentName = "h",
                    answerParam = "reply",
                    params = mapOf("ticket" to "own"),
                    originProcessId = 7,
                    originAgentName = "h",
                    expiresAt = null,
                    createdAt = 1,
                )
            coEvery { repo.claim(5) } returns true
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "h",
                    inputParams = mapOf("TICKET_ID" to "123", "REGION" to "eu"),
                )
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(resolve))
            } coAnswers
                {
                    resolve.captured.invoke()
                    99
                }
            val params = slot<Map<String, String>>()
            every { inputResolver.resolveFromParams(capture(params), any(), any(), any()) } returns emptyMap()

            service().fire(questionId = "q1", answer = "us-east")

            // The origin run's stored REGION rides in keyed by its paramName; the dispatch's
            // own `ticket` and the operator's answer both win over the stored values. ADR-0101.
            assertEquals("eu", params.captured["region"])
            assertEquals("own", params.captured["ticket"])
            assertEquals("us-east", params.captured["reply"])
        }

    @Test
    fun `fire unions the origin run's inherited secret keys into the starter's secret snapshot`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.name } returns "h"
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agent.continuation } returns AgentContinuationConfig(inheritInputs = true)
            every { agent.effectiveInputs } returns listOf(Param(paramName = "ticket", envName = "TICKET_ID"))
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", emptyMap(), 7, "h", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "h",
                    inputParams = mapOf("TICKET_ID" to "123"),
                    secretInputKeys = setOf("SECRET_TOKEN"),
                )
            val secretKeys = slot<Set<String>>()
            coEvery {
                runStarter.start(any(), any(), any(), any(), any(), any(), capture(secretKeys), any(), any(), any(), any())
            } returns 99

            service().fire(questionId = "q1", answer = "us-east")

            // Without this union an inherited `secret: true` value reaches the fired run's
            // status, logs and tags unmasked. ADR-0052, ADR-0101.
            assertEquals(setOf("SECRET_TOKEN"), secretKeys.captured)

            // The flag off inherits nothing: the snapshot is the dispatch's own (none here).
            every { agent.continuation } returns null
            service().fire(questionId = "q1", answer = "us-east")

            assertEquals(emptySet(), secretKeys.captured)
        }

    @Test
    fun `fire fails the dispatch naming the param when the target rejects an inherited value`() =
        runTest {
            // A real resolver, so the merged map really goes through ParamValidator at fire
            // time: the origin run accepted `TICKET_ID` as ANY, the target now declares it
            // integer. The operator's answer is lost — the dispatch settles FAILED, naming
            // the param and never the value. ADR-0101.
            val strict =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("true"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(paramName = "ticket", envName = "TICKET_ID", src = ParamSource.QUERY, type = ParamType.INT),
                        ),
                    dispatch = AgentDispatchConfig(enabled = true),
                    continuation = AgentContinuationConfig(inheritInputs = true),
                )
            every { agents.agents } returns mapOf("h" to strict)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", emptyMap(), 7, "h", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "h",
                    inputParams = mapOf("TICKET_ID" to "not-a-number"),
                )
            val strictService =
                DispatchService(
                    repo,
                    questions,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    broadcaster,
                    groupService,
                    LoggerFactory.getLogger("t"),
                ) { 1000L }
            // The mocked starter invokes the fire's input resolution, so the real resolver
            // really rejects the inherited value.
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(resolve))
            } coAnswers
                {
                    resolve.captured.invoke()
                    99
                }

            val result = strictService.fire(questionId = "q1", answer = "us-east")

            coVerify { repo.markFailed(5, match { it.contains("'ticket'") && !it.contains("not-a-number") }, 1000L) }
            coVerify(exactly = 0) { repo.markDispatched(5, any(), any()) }
            assertEquals(DispatchStatus.FAILED, result?.status)
        }

    @Test
    fun `fire validates an inherited stored value without running its transformer a second time`() =
        runTest {
            // The origin run stored SUMMARY already post-transformer — its JsonPath
            // transformer extracted `$.summary` when it resolved its inputs. Running the
            // transformer over the extracted string again would reject the value and fail
            // every dispatch that inherits it. Real resolver, mirroring the continuation
            // fire's case, so dropping the fire's skipTransformFor argument fails here.
            // ADR-0101.
            val graph =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("true"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "summary",
                                envName = "SUMMARY",
                                src = ParamSource.QUERY,
                                type = ParamType.ANY,
                                transformer = Transformer.JsonPath("$.summary"),
                            ),
                        ),
                    dispatch = AgentDispatchConfig(enabled = true),
                    continuation = AgentContinuationConfig(inheritInputs = true),
                )
            every { agents.agents } returns mapOf("h" to graph)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", emptyMap(), 7, "h", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "h",
                    inputParams = mapOf("SUMMARY" to "the subject"),
                )
            val realResolverService =
                DispatchService(
                    repo,
                    questions,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    broadcaster,
                    groupService,
                    LoggerFactory.getLogger("t"),
                ) { 1000L }
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(resolve))
            } coAnswers
                {
                    resolve.captured.invoke()
                    99
                }

            realResolverService.fire(questionId = "q1", answer = "us-east")

            coVerify { repo.markDispatched(5, 99, 1000L) }
            coVerify(exactly = 0) { repo.markFailed(5, any(), any()) }
        }

    @Test
    fun `fire transforms the dispatch's own param of an inherited name as caller-supplied input`() =
        runTest {
            // The inherited names whose value survives the merge exclude the dispatch's own
            // params: an own param named like an inherited one wins, and its raw JSON is
            // transformed as caller-supplied input — never passed through as a stored,
            // already-transformed value. Real resolver, so the transformer really runs.
            // ADR-0101.
            val graph =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("true"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "summary",
                                envName = "SUMMARY",
                                src = ParamSource.QUERY,
                                type = ParamType.ANY,
                                transformer = Transformer.JsonPath("$.summary"),
                            ),
                        ),
                    dispatch = AgentDispatchConfig(enabled = true),
                    continuation = AgentContinuationConfig(inheritInputs = true),
                )
            every { agents.agents } returns mapOf("h" to graph)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(
                    5,
                    "q1",
                    DispatchStatus.PENDING,
                    "h",
                    "reply",
                    params = mapOf("summary" to "{\"summary\": \"own raw\"}"),
                    originProcessId = 7,
                    originAgentName = "h",
                    expiresAt = null,
                    createdAt = 1,
                )
            coEvery { repo.claim(5) } returns true
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "h",
                    inputParams = mapOf("SUMMARY" to "the subject"),
                )
            val realResolverService =
                DispatchService(
                    repo,
                    questions,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    broadcaster,
                    groupService,
                    LoggerFactory.getLogger("t"),
                ) { 1000L }
            val resolve = slot<suspend () -> Map<String, String>>()
            var resolved: Map<String, String>? = null
            coEvery {
                runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(resolve))
            } coAnswers
                {
                    resolved = resolve.captured.invoke()
                    99
                }

            realResolverService.fire(questionId = "q1", answer = "us-east")

            assertEquals("own raw", resolved?.get("SUMMARY"))
            coVerify { repo.markDispatched(5, 99, 1000L) }
        }

    @Test
    fun `create reports an unauthorized ask-and-dispatch target as not found`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "asker", "h") } returns false

            assertFailsWith<DispatchAgentNotFoundException> {
                service().create(7, "q", emptyList(), "h", "answer", emptyMap(), null)
            }
            coVerify(exactly = 0) { repo.create(any(), any(), any()) }
        }

    @Test
    fun `create copies the origin process's owner onto the dispatch row`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "ci" }
            val dispatchSlot = slot<Dispatch>()
            coEvery { repo.create(any(), capture(dispatchSlot), any()) } returns 5

            service().create(7, "q", emptyList(), "h", "answer", emptyMap(), null)

            assertEquals("ci", dispatchSlot.captured.owner)
        }

    @Test
    fun `fire claims, starts the target run with params plus answer, and marks dispatched`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", mapOf("p1" to "v1"), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery {
                runStarter.start(
                    eq(agent),
                    eq(emptyList()),
                    eq("dispatch"),
                    any(),
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

            val result = service().fire(questionId = "q1", answer = "us-east")

            coVerify { repo.claim(5) }
            coVerify { repo.markDispatched(5, 99, 1000L) }
            assertEquals(DispatchStatus.DISPATCHED, result?.status)
            assertEquals(99, result?.dispatchedProcessId)
            assertEquals(1000L, result?.dispatchedAt)
        }

    @Test
    fun `fire links the started run to the dispatch's origin process`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", mapOf("p1" to "v1"), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().fire(questionId = "q1", answer = "us-east")

            coVerify { runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), eq(7), any(), any(), any()) }
        }

    @Test
    fun `fire marks the origin as a server-derived parent`() =
        runTest {
            // The origin is the run that asked for this dispatch: while it is still WORKING the
            // fired start is part of admitted work and does not park at the gate. ADR-0092.
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", mapOf("p1" to "v1"), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().fire(questionId = "q1", answer = "us-east")

            coVerify {
                runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), eq(7), any(), serverDerivedParent = true, any())
            }
        }

    @Test
    fun `dispatchNow marks the origin as a server-derived parent`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().dispatchNow(originProcessId = 7, agent = "h", params = emptyMap())

            coVerify {
                runStarter.start(any(), any(), eq("dispatch"), any(), any(), any(), any(), eq(7), any(), serverDerivedParent = true, any())
            }
        }

    @Test
    fun `fire joins the origin process's flow`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", mapOf("p1" to "v1"), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99
            // The asking run is itself a segment of the flow rooted at 3 — the fired run
            // joins that flow rather than rooting a new one at the asker. The gate reads
            // the root row to check the owner's visibility, so it must resolve too.
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

            service().fire(questionId = "q1", answer = "us-east")

            coVerify { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), eq(3), any(), any()) }
        }

    @Test
    fun `fire drops the derived root when the fired run's owner cannot see the root row`() =
        runTest {
            // [information_disclosure / CWE-200] The fired run is attributed to the
            // dispatch's owner, so a flow root that owner cannot see must not reach the
            // new row, its HMR_ROOT_PROCESS_ID env or its span. The run is stored
            // rootless instead of mis-rooted.
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", mapOf("p1" to "v1"), 7, "asker", null, 1, owner = "ci")
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99
            // The flow root runs an agent the dispatch owner holds no grant on — the
            // derived id is dropped.
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "asker", owner = "alice")
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                    parentProcessId = 1,
                    rootProcessId = 3,
                )
            every { authorizer.canSee("ci", "alice", "asker") } returns false

            service().fire(questionId = "q1", answer = "us-east")

            coVerify { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), isNull(), any(), any()) }
        }

    @Test
    fun `dispatchNow links the started run to the origin process`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().dispatchNow(7, "h", emptyMap())

            coVerify { runStarter.start(eq(agent), any(), eq("dispatch"), any(), any(), any(), any(), eq(7), any(), any(), any()) }
        }

    @Test
    fun `dispatchNow joins the origin process's flow`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99
            // A rootless origin row: the origin itself is the flow's root, so the fired
            // run is rooted at the origin rather than at a fresh flow of its own.
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                )

            service().dispatchNow(7, "h", emptyMap())

            coVerify { runStarter.start(eq(agent), any(), any(), any(), any(), any(), any(), any(), eq(7), any(), any()) }
        }

    @Test
    fun `dispatchNow drops the derived root when the origin owner cannot see the root row`() =
        runTest {
            // Same denial path as fire: the fired run carries the dispatch owner's grant,
            // not the origin process's, so a foreign flow-root id reaches no row or env.
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "asker", owner = "alice")
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.WORKING,
                    agentName = "asker",
                    owner = "ci",
                    parentProcessId = 1,
                    rootProcessId = 3,
                )
            every { authorizer.canSee("ci", "alice", "asker") } returns false

            service().dispatchNow(7, "h", emptyMap())

            coVerify { runStarter.start(eq(agent), any(), any(), any(), any(), any(), any(), any(), isNull(), any(), any()) }
        }

    @Test
    fun `fire passes the dispatch's owner to agentRunStarter`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "reply", mapOf("p1" to "v1"), 7, "asker", null, 1, owner = "ci")
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().fire(questionId = "q1", answer = "us-east")

            coVerify {
                runStarter.start(
                    eq(agent),
                    eq(emptyList()),
                    eq("dispatch"),
                    any(),
                    any(),
                    eq("ci"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `fire marks the dispatch FAILED when the hop is unauthorized`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agents.agents } returns mapOf("b" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "b", "reply", emptyMap(), 7, "a", null, 1, owner = "alice")
            coEvery { repo.claim(5) } returns true
            every { authorizer.canDispatchHop("alice", "a", "b") } returns false

            val result = service().fire(questionId = "q1", answer = "ans")

            assertEquals(DispatchStatus.FAILED, result?.status)
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify { repo.markFailed(5, match { it.contains("not authorized") }, 1000L) }
        }

    @Test
    fun `fire starts the run when the hop is authorized`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("b" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "b", "reply", emptyMap(), 7, "a", null, 1, owner = "alice")
            coEvery { repo.claim(5) } returns true
            every { authorizer.canDispatchHop("alice", "a", "b") } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            val result = service().fire(questionId = "q1", answer = "ans")

            assertEquals(DispatchStatus.DISPATCHED, result?.status)
        }

    @Test
    fun `fire does not let stored params inject host-controlled env names`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(
                    5,
                    "q1",
                    DispatchStatus.PENDING,
                    "h",
                    "HMR_LG_RESUME",
                    mapOf("PATH" to "/tmp/evil:/usr/bin", "LD_PRELOAD" to "/tmp/evil.so", "HMR_LG_THREAD" to "t-1"),
                    7,
                    "asker",
                    null,
                    1,
                )
            coEvery { repo.claim(5) } returns true
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    eq(agent),
                    eq(emptyList()),
                    eq("dispatch"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolve),
                )
            } returns 99

            service().fire("q1", "the-answer")

            val env = resolve.captured.invoke()
            assertNull(env["PATH"])
            assertNull(env["LD_PRELOAD"])
            // The legitimate control marker still rides through.
            assertEquals("t-1", env["HMR_LG_THREAD"])
        }

    @Test
    fun `fire passes reserved langgraph control params through to the run env`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            // answerParam is the reserved HMR_LG_RESUME; params carry the thread + interrupt id.
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(
                    5,
                    "q1",
                    DispatchStatus.PENDING,
                    "h",
                    "HMR_LG_RESUME",
                    mapOf("HMR_LG_THREAD" to "t-1", "HMR_LG_INTERRUPT" to "int-1"),
                    7,
                    "asker",
                    null,
                    1,
                )
            coEvery { repo.claim(5) } returns true
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    eq(agent),
                    eq(emptyList()),
                    eq("dispatch"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolve),
                )
            } returns 99

            service().fire("q1", "the-answer")

            // The resolved declared inputs (relaxed mock → empty) PLUS the reserved control params
            // must both reach the run env; the reserved ones are otherwise dropped by resolveDebug.
            val env = resolve.captured.invoke()
            assertEquals("t-1", env["HMR_LG_THREAD"])
            assertEquals("int-1", env["HMR_LG_INTERRUPT"])
            assertEquals("the-answer", env["HMR_LG_RESUME"])
        }

    @Test
    fun `fire records FAILED when the run fails`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CostCapExceededException("global", 1.0, 2.0)

            val result = service().fire("q1", "yes")

            coVerify { repo.markFailed(5, any(), 1000L) }
            assertEquals(DispatchStatus.FAILED, result?.status)
        }

    @Test
    fun `fire releases a drain-refused dispatch for the next boot's re-fire, not FAILED`() =
        runTest {
            // Answering a still-PENDING question whose asking run has already ended reaches
            // the fire path during the drain, and the gate refuses the start. Settling FAILED
            // would consume the operator's answer for a run that never started, so the row
            // goes back to PENDING and the next boot's `refirePendingAnswered` fires it.
            // See ADR-0102.
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                ServerDrainingException()

            val result = service().fire("q1", "yes")

            coVerify { repo.releaseForRetry(5) }
            coVerify(exactly = 0) { repo.markFailed(5, any(), any()) }
            assertEquals(DispatchStatus.PENDING, result?.status)
        }

    @Test
    fun `fire marks FAILED and returns FAILED on an unexpected (non-Homer) exception`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("unexpected")

            val result = service().fire("q1", "yes")

            coVerify { repo.markFailed(5, any(), 1000L) }
            assertEquals(DispatchStatus.FAILED, result?.status)
        }

    // A failure that carries no message still settles the dispatch FAILED, under the
    // named fallback rather than an empty reason.
    @Test
    fun `fire names the fallback reason when a Homer failure carries no message`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            // MockK cannot subclass the sealed HomerException itself; ValidationException is
            // the concrete permitted subclass the catch clause sees.
            val messageLess = mockk<ValidationException>()
            every { messageLess.message } returns null
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                messageLess

            val result = service().fire("q1", "yes")

            coVerify { repo.markFailed(5, "Dispatch failed", 1000L) }
            assertEquals(DispatchStatus.FAILED, result?.status)
            // The returned row carries the same fallback reason the stored row keeps, so the
            // immediate answer and any later poll agree.
            assertEquals("Dispatch failed", result?.error)
        }

    @Test
    fun `fire names the fallback reason when an unexpected failure carries no message`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException()

            val result = service().fire("q1", "yes")

            coVerify { repo.markFailed(5, "Unexpected dispatch failure", 1000L) }
            assertEquals("Unexpected dispatch failure", result?.error)
        }

    @Test
    fun `fire is a no-op when no pending dispatch`() =
        runTest {
            coEvery { repo.findByQuestionId("q1") } returns null
            assertNull(service().fire("q1", "yes"))
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `fire no-ops when the claim is lost to a concurrent cancel`() =
        runTest {
            every { agents.agents } returns mapOf("h" to mockk<Agent>(relaxed = true))
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns false

            assertNull(service().fire("q1", "yes"))
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `fire records FAILED when the target agent no longer exists`() =
        runTest {
            every { agents.agents } returns emptyMap()
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "gone", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true
            val result = service().fire("q1", "yes")
            coVerify { repo.markFailed(5, match { it.contains("gone") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(DispatchStatus.FAILED, result?.status)
        }

    @Test
    fun `fire marks FAILED when the target agent is no longer dispatch-enabled`() =
        runTest {
            // Registered while dispatchable, but the agent was reloaded to dispatch.enabled=false
            // (or replaced) before the operator answered. The stale dispatch must not fire.
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = false)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.claim(5) } returns true

            val result = service().fire("q1", "yes")

            assertEquals(DispatchStatus.FAILED, result?.status)
            coVerify { repo.markFailed(5, match { it.contains("no longer an allowed dispatch target") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `cancel cancels the dispatch and its question atomically and emits expired`() =
        runTest {
            val pending = Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.get(5) } returns pending
            coEvery { repo.cancelWithQuestion(5) } returns pending.copy(status = DispatchStatus.CANCELLED)

            service().cancel(5)

            coVerify { repo.cancelWithQuestion(5) }
            coVerify { broadcaster.emit(ProcessEvent.QuestionExpired(7, "q1")) }
        }

    @Test
    fun `cancel honors the CAS - lost race throws DispatchNotPendingException without emitting`() =
        runTest {
            val pending = Dispatch(5, "q1", DispatchStatus.PENDING, "h", "answer", emptyMap(), 7, "asker", null, 1)
            coEvery { repo.get(5) } returnsMany
                listOf(pending, pending.copy(status = DispatchStatus.DISPATCHING))
            coEvery { repo.cancelWithQuestion(5) } returns null

            assertFailsWith<DispatchNotPendingException> { service().cancel(5) }
            coVerify(exactly = 0) { broadcaster.emit(any()) }
        }

    @Test
    fun `cancel rejects a non-pending dispatch`() =
        runTest {
            coEvery { repo.get(5) } returns
                Dispatch(5, "q1", DispatchStatus.DISPATCHED, "h", "answer", emptyMap(), 7, "asker", null, 1)
            assertFailsWith<DispatchNotPendingException> { service().cancel(5) }
            coVerify(exactly = 0) { repo.cancelWithQuestion(any()) }
        }

    @Test
    fun `validateDispatchNow refuses params the target rejects and starts nothing`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            every { inputResolver.resolveFromParams(eq(mapOf("BAD" to "x")), agent, any()) } throws
                ValidationException("unknown input", "BAD")

            service().validateDispatchNow(7, "h", emptyMap())
            assertFailsWith<ValidationException> { service().validateDispatchNow(7, "h", mapOf("BAD" to "x")) }
            assertFailsWith<DispatchAgentNotFoundException> { service().validateDispatchNow(7, "missing", emptyMap()) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `dispatchNow starts a decoupled run, tags the referer, and returns the new pid`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            val attrs = slot<Attributes>()
            val resolveInputs = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    eq(agent),
                    eq(emptyList()),
                    eq("dispatch"),
                    capture(attrs),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolveInputs),
                )
            } returns 99

            val pid = service().dispatchNow(7, "h", mapOf("foo" to "bar"))

            assertEquals(99, pid)
            // Fully decoupled: fresh empty chain, no dispatch row written.
            coVerify(exactly = 0) { repo.create(any(), any(), any()) }
            // The params must reach the run: invoking the captured resolver feeds them to
            // resolveFromParams against the target agent, with required enforcement.
            resolveInputs.captured.invoke()
            verify {
                inputResolver.resolveFromParams(
                    match<Map<String, String>> { it["foo"] == "bar" },
                    agent,
                    true,
                )
            }
            // Linkage back to the origin is recorded as the referer on the span.
            assertEquals(7L, attrs.captured.get(AttributeKey.longKey("referer.process.id")))
            assertEquals("asker", attrs.captured.get(AttributeKey.stringKey("referer.agent.name")))
        }

    @Test
    fun `dispatchNow satisfies a body-input target and delivers it to the run env`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val agent =
                Agent(
                    name = "h",
                    commands = listOf(Command.Cmd(cmd = listOf("echo"))),
                    dispatch = AgentDispatchConfig(enabled = true),
                    inputs =
                        listOf(
                            Param(
                                paramName = "payload",
                                envName = "PAYLOAD",
                                src = ParamSource.BODY,
                                type = ParamType.ANY,
                                required = true,
                            ),
                        ),
                )
            every { agents.agents } returns mapOf("h" to agent)
            // Real AgentInputResolver so the body routing is actually exercised, not just the wiring.
            val service =
                DispatchService(
                    repo,
                    questions,
                    agents,
                    authorizer,
                    runStarter,
                    AgentInputResolver(),
                    processManager,
                    broadcaster,
                    groupService,
                    LoggerFactory.getLogger("t"),
                ) { 1000L }
            val resolveInputs = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(
                    eq(agent),
                    eq(emptyList()),
                    eq("dispatch"),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    capture(resolveInputs),
                )
            } returns 99

            val pid = service.dispatchNow(7, "h", mapOf("payload" to "hello"))

            assertEquals(99, pid)
            assertEquals(mapOf("PAYLOAD" to "hello"), resolveInputs.captured.invoke())
        }

    @Test
    fun `dispatchNow rejects when the origin registration is closed`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = true)
            assertFailsWith<QuestionsClosedForProcessException> {
                service().dispatchNow(7, "h", emptyMap())
            }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `dispatchNow rejects an unknown target agent`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            every { agents.agents } returns emptyMap()
            assertFailsWith<DispatchAgentNotFoundException> {
                service().dispatchNow(7, "missing", emptyMap())
            }
        }

    @Test
    fun `dispatchNow rejects a non-dispatchable target agent`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("asker", closed = false)
            val noBlock = mockk<Agent>().also { every { it.dispatch } returns null }
            val disabled = mockk<Agent>().also { every { it.dispatch } returns AgentDispatchConfig(enabled = false) }
            every { agents.agents } returns mapOf("noblock" to noBlock, "disabled" to disabled)
            assertFailsWith<AgentNotDispatchableException> { service().dispatchNow(7, "noblock", emptyMap()) }
            assertFailsWith<AgentNotDispatchableException> { service().dispatchNow(7, "disabled", emptyMap()) }
        }

    @Test
    fun `dispatchNow reports an unauthorized hop as not found and starts nothing`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("a", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("b" to agent)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "a", "b") } returns false

            assertFailsWith<DispatchAgentNotFoundException> {
                service().dispatchNow(7, "b", emptyMap())
            }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // ADR-0047 rule 3: an unauthorized hop must not learn that the target exists, nor whether it
    // opted into dispatch -- the answer is the one an unknown agent gets.
    @Test
    fun `dispatchNow reports an unauthorized hop to a non-dispatchable agent exactly like an unknown agent`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("a", closed = false)
            val disabled = mockk<Agent>().also { every { it.dispatch } returns null }
            every { agents.agents } returns mapOf("disabled" to disabled)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "a", "disabled") } returns false
            every { authorizer.canDispatchHop("alice", "a", "missing") } returns false

            val hidden = assertFailsWith<DispatchAgentNotFoundException> { service().dispatchNow(7, "disabled", emptyMap()) }
            val unknown = assertFailsWith<DispatchAgentNotFoundException> { service().dispatchNow(7, "missing", emptyMap()) }
            assertEquals(unknown.message?.replace("missing", "disabled"), hidden.message)
        }

    @Test
    fun `dispatchNow passes the origin owner into start when authorized`() =
        runTest {
            every { questions.registrationFor(7) } returns
                QuestionsService.Registration("a", closed = false)
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("b" to agent)
            coEvery { processManager.getProcessInfo(7) } returns
                mockk(relaxed = true) { every { owner } returns "alice" }
            every { authorizer.canDispatchHop("alice", "a", "b") } returns true
            val ownerSlot = slot<String?>()
            coEvery {
                runStarter.start(any(), any(), any(), any(), any(), captureNullable(ownerSlot), any(), any(), any(), any(), any())
            } returns 99

            service().dispatchNow(7, "b", emptyMap())

            assertEquals("alice", ownerSlot.captured)
        }

    @Test
    fun `fire passes the registration-time secret snapshot to agentRunStarter`() =
        runTest {
            // The target's `secret` flags can change between registration and fire. The
            // snapshot taken at registration is what the fired run must honour — without
            // it, an input flipped to `secret: false` and added to traceTags would export
            // the stored value as a cleartext Langfuse tag. See ADR-0052.
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("q1") } returns
                Dispatch(
                    5,
                    "q1",
                    DispatchStatus.PENDING,
                    "h",
                    "reply",
                    mapOf("TOKEN" to "tok-historical"),
                    7,
                    "asker",
                    null,
                    1,
                    secretInputKeys = setOf("TOKEN"),
                )
            coEvery { repo.claim(5) } returns true
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            service().fire(questionId = "q1", answer = "us-east")

            coVerify {
                runStarter.start(any(), any(), any(), any(), any(), any(), eq(setOf("TOKEN")), any(), any(), any(), any())
            }
        }

    // ── grouped ask-and-dispatch (ADR-0109) ──────────────────────────────────────────

    /** A PENDING GROUP row as [DispatchRepository.findByQuestionId] resolves one of its members. */
    private fun groupDispatch() =
        Dispatch(
            id = 5,
            questionId = DispatchGroupService.GROUP_QUESTION_ID_PREFIX + "sentinel",
            status = DispatchStatus.PENDING,
            agentName = "h",
            answerParam = "answer",
            params = emptyMap(),
            originProcessId = 7,
            originAgentName = "asker",
            expiresAt = null,
            createdAt = 1,
            kind = DispatchKind.GROUP,
            questionIds = listOf("m1", "m2"),
        )

    private fun dispatchEnabledTarget() {
        val agent = mockk<Agent>(relaxed = true)
        every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
        every { agents.agents } returns mapOf("h" to agent)
    }

    // The member question's answer is the trigger, but the group is the gate: while one member is
    // still OPEN the target must not start, and the group must stay claimable.
    @Test
    fun `fire leaves a group pending while any member is unanswered`() =
        runTest {
            dispatchEnabledTarget()
            coEvery { repo.findByQuestionId("m1") } returns groupDispatch()
            coEvery { groupService.aggregateAnswers(5) } returns null

            assertNull(service().fire("m1", "one"))

            coVerify(exactly = 0) { repo.claim(any()) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `fire starts the target once with every answer aggregated in question order`() =
        runTest {
            dispatchEnabledTarget()
            coEvery { repo.findByQuestionId("m2") } returns groupDispatch()
            coEvery { groupService.aggregateAnswers(5) } returns """["one","two"]"""
            coEvery { repo.claim(5) } returns true
            val resolve = slot<suspend () -> Map<String, String>>()
            coEvery {
                runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(resolve))
            } coAnswers
                {
                    resolve.captured.invoke()
                    99
                }
            val params = slot<Map<String, String>>()
            every { inputResolver.resolveFromParams(capture(params), any(), any(), any()) } returns emptyMap()

            val result = service().fire("m2", "two")

            coVerify { repo.claim(5) }
            coVerify { repo.markDispatched(5, 99, 1000L) }
            assertEquals(DispatchStatus.DISPATCHED, result?.status)
            // The aggregate, not the last member's answer: the target gets all of them.
            assertEquals("""["one","two"]""", params.captured["answer"])
        }

    // The gate read is only a pre-check; the claim CAS is the gate. Two members answered
    // concurrently (or a boot re-fire racing a live answer) must still start exactly one run.
    @Test
    fun `fire of a group whose claim is lost starts nothing`() =
        runTest {
            dispatchEnabledTarget()
            coEvery { repo.findByQuestionId("m2") } returns groupDispatch()
            coEvery { groupService.aggregateAnswers(5) } returns """["one","two"]"""
            coEvery { repo.claim(5) } returnsMany listOf(true, false)
            coEvery { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            assertEquals(DispatchStatus.DISPATCHED, service().fire("m2", "two")?.status)
            assertNull(service().fire("m2", "two"))
            coVerify(exactly = 1) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // The group arm runs the same hop gate as a single dispatch: an agent reloaded to
    // dispatch.enabled=false between create and the last answer must not fire.
    @Test
    fun `fire of a group marks FAILED when the target is no longer dispatch-enabled`() =
        runTest {
            val agent = mockk<Agent>(relaxed = true)
            every { agent.dispatch } returns AgentDispatchConfig(enabled = false)
            every { agents.agents } returns mapOf("h" to agent)
            coEvery { repo.findByQuestionId("m2") } returns groupDispatch()
            coEvery { groupService.aggregateAnswers(5) } returns """["one","two"]"""
            coEvery { repo.claim(5) } returns true

            val result = service().fire("m2", "two")

            assertEquals(DispatchStatus.FAILED, result?.status)
            coVerify { repo.markFailed(5, match { it.contains("no longer an allowed dispatch target") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // An aggregate that can never be delivered settles the group FAILED rather than leaving it
    // PENDING on answers no start could carry. The throw must not escape `fire`: it also runs on
    // the boot path, where it would abort startup reconciliation.
    @Test
    fun `fire settles a group FAILED when the aggregated answers are over the payload bound`() =
        runTest {
            dispatchEnabledTarget()
            coEvery { repo.findByQuestionId("m2") } returns groupDispatch()
            coEvery { groupService.aggregateAnswers(5) } throws
                DispatchGroupAnswersTooLargeException(DispatchGroupService.MAX_ANSWER_BYTES)
            coEvery { repo.claim(5) } returns true

            val result = service().fire("m2", "two")

            assertEquals(DispatchStatus.FAILED, result?.status)
            coVerify { repo.markFailed(5, match { it.contains("exceed") }, 1000L) }
            coVerify(exactly = 0) { runStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `cancel of a group emits one expired event per member question`() =
        runTest {
            val pending = groupDispatch()
            coEvery { repo.get(5) } returns pending
            coEvery { repo.cancelWithQuestion(5) } returns pending.copy(status = DispatchStatus.CANCELLED)

            service().cancel(5)

            coVerify { broadcaster.emit(ProcessEvent.QuestionExpired(7, "m1")) }
            coVerify { broadcaster.emit(ProcessEvent.QuestionExpired(7, "m2")) }
        }

    @Test
    fun `pruneSettled prunes rows created more than settledRetentionSec before now`() =
        runTest {
            coEvery { repo.pruneSettled(any()) } returns 2
            service(DispatchConfig(settledRetentionSec = 3_600)).pruneSettled()
            coVerify(exactly = 1) { repo.pruneSettled(1000L - 3_600) }
        }
}
