package homer.app.services

import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.DispatchGroupAnswersTooLargeException
import homer.app.exceptions.DispatchGroupTooLargeException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.TooManyOpenQuestionsException
import homer.app.exceptions.TooManyPendingDispatchesException
import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentDispatchConfig
import homer.app.models.DispatchConfig
import homer.app.models.ProcessInfo
import homer.app.models.QuestionsConfig
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.DispatchRepository.GroupCreateOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DispatchGroupServiceTest {
    private val repo = mockk<DispatchRepository>(relaxed = true)
    private val questions = mockk<QuestionsService>(relaxed = true)
    private val agents = mockk<AgentsService>()
    private val inputResolver = mockk<AgentInputResolver>(relaxed = true)
    private val processManager = mockk<ProcessManager>(relaxed = true)
    private val broadcaster = mockk<ProcessEventBroadcaster>(relaxed = true)
    private val authorizer =
        mockk<Authorizer>().also {
            every { it.canDispatchHop(any(), any(), any()) } returns true
        }

    private fun service(
        config: DispatchConfig = DispatchConfig(),
        questionsConfig: QuestionsConfig = QuestionsConfig(),
    ) = DispatchGroupService(
        repo,
        questions,
        agents,
        authorizer,
        inputResolver,
        processManager,
        broadcaster,
        LoggerFactory.getLogger("t"),
        config,
        questionsConfig,
    ) { 1000L }

    /** A live origin registration plus a dispatch-enabled target named `h`. */
    private fun liveOrigin(originAgent: String = "asker") {
        every { questions.registrationFor(7) } returns QuestionsService.Registration(originAgent, closed = false)
        val agent = mockk<Agent>(relaxed = true)
        every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
        every { agents.agents } returns mapOf("h" to agent)
    }

    private fun specs(n: Int) = List(n) { DispatchGroupService.QuestionSpec("q$it") }

    private suspend fun create(
        svc: DispatchGroupService,
        specs: List<DispatchGroupService.QuestionSpec>,
        answerParam: String = "answer",
        params: Map<String, String> = emptyMap(),
        ttlSec: Int? = null,
    ) = svc.create(7, specs, "h", answerParam, params, ttlSec)

    // ── create: the size bounds, checked before any other work ────────────────────────

    // The group is the multiplication §12 rule 4 asks a local, explicit bound for: N questions
    // from one call. Both refusals must land before any row exists, so a refused group is never
    // observably partial.
    @Test
    fun `create refuses an empty group and a group over maxGroupSize, registering nothing`() =
        runTest {
            liveOrigin()

            val empty = assertFailsWith<ValidationException> { create(service(), emptyList()) }
            assertEquals("questions", empty.field)

            val over = assertFailsWith<DispatchGroupTooLargeException> { create(service(), specs(9)) }
            assertEquals(8, over.maxGroupSize)

            val configured = assertFailsWith<DispatchGroupTooLargeException> {
                create(service(DispatchConfig(maxGroupSize = 2)), specs(3))
            }
            assertEquals(2, configured.maxGroupSize)

            coVerify(exactly = 0) { repo.createGroup(any(), any(), any(), any()) }
        }

    // Exactly at the bound is allowed: the boundary is the multiplication the cap permits.
    @Test
    fun `create accepts a group of exactly maxGroupSize questions`() =
        runTest {
            liveOrigin()
            coEvery { repo.createGroup(any(), any(), any(), any()) } returns GroupCreateOutcome.Created(5)

            val result = create(service(DispatchConfig(maxGroupSize = 2)), specs(2))

            assertEquals(5, result.dispatchId)
            assertEquals(2, result.questionIds.size)
        }

    @Test
    fun `create rejects when asker registration is closed`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = true)
            assertFailsWith<QuestionsClosedForProcessException> { create(service(), specs(2)) }
        }

    @Test
    fun `create rejects an unknown and a non-dispatchable target`() =
        runTest {
            every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
            every { agents.agents } returns emptyMap()
            assertFailsWith<DispatchAgentNotFoundException> { create(service(), specs(2)) }

            val disabled = mockk<Agent>(relaxed = true)
            every { disabled.dispatch } returns AgentDispatchConfig(enabled = false)
            every { agents.agents } returns mapOf("h" to disabled)
            assertFailsWith<AgentNotDispatchableException> { create(service(), specs(2)) }
        }

    // An unauthorized hop is reported as an unknown agent, so agent code cannot probe which
    // agents exist (ADR-0047 rule 3).
    @Test
    fun `create reports an unauthorized target as not found`() =
        runTest {
            liveOrigin()
            every { authorizer.canDispatchHop(any(), any(), any()) } returns false
            assertFailsWith<DispatchAgentNotFoundException> { create(service(), specs(2)) }
        }

    @Test
    fun `create refuses question input the questions service rejects and registers nothing`() =
        runTest {
            liveOrigin()
            every { questions.validateQuestionInput(any(), any()) } throws
                ValidationException("question input refused", "options")

            assertFailsWith<ValidationException> { create(service(), specs(2)) }
            coVerify(exactly = 0) { repo.createGroup(any(), any(), any(), any()) }
        }

    @Test
    fun `create rejects an answerParam targeting a non-control reserved env name`() =
        runTest {
            liveOrigin()
            assertFailsWith<ValidationException> { create(service(), specs(2), answerParam = "PATH") }
            coVerify(exactly = 0) { repo.createGroup(any(), any(), any(), any()) }
        }

    @Test
    fun `create refuses a non-positive ttl and stores the ttl-derived expiry`() =
        runTest {
            liveOrigin()
            val dispatchSlot = slot<Dispatch>()
            coEvery { repo.createGroup(any(), capture(dispatchSlot), any(), any()) } returns GroupCreateOutcome.Created(5)

            assertFailsWith<ValidationException> { create(service(), specs(2), ttlSec = 0) }

            create(service(), specs(2), ttlSec = 30)
            assertEquals(1030L, dispatchSlot.captured.expiresAt)
        }

    // ── create: the row it builds ─────────────────────────────────────────────────────

    @Test
    fun `create registers N ordered questions and one GROUP dispatch in a single call`() =
        runTest {
            liveOrigin()
            val questionsSlot = slot<List<QuestionsService.Question>>()
            val dispatchSlot = slot<Dispatch>()
            coEvery {
                repo.createGroup(capture(questionsSlot), capture(dispatchSlot), any(), any())
            } returns GroupCreateOutcome.Created(5)

            val result = create(service(), List(3) { DispatchGroupService.QuestionSpec("q$it", listOf("a", "b")) })

            assertEquals(5, result.dispatchId)
            assertEquals(3, questionsSlot.captured.size)
            assertEquals(listOf("q0", "q1", "q2"), questionsSlot.captured.map { it.text })
            assertTrue(questionsSlot.captured.all { it.options == listOf("a", "b") })
            assertTrue(questionsSlot.captured.all { it.dispatchBound && it.status == QuestionsService.QuestionStatus.OPEN })
            // The member ids come back in question order, so the caller can print them.
            assertEquals(questionsSlot.captured.map { it.id }, result.questionIds)

            val dispatch = dispatchSlot.captured
            assertEquals(DispatchKind.GROUP, dispatch.kind)
            assertEquals(DispatchStatus.PENDING, dispatch.status)
            assertEquals("h", dispatch.agentName)
            assertEquals(7, dispatch.originProcessId)
            assertEquals("asker", dispatch.originAgentName)
            assertEquals(1000L, dispatch.createdAt)
            // A sentinel, not a member id: `dispatches.question_id` is uniquely indexed and a
            // GROUP row names no single question.
            assertTrue(dispatch.questionId.startsWith(DispatchGroupService.GROUP_QUESTION_ID_PREFIX))
            assertTrue(dispatch.questionId !in result.questionIds)
        }

    @Test
    fun `create copies the origin process's owner onto the group row`() =
        runTest {
            liveOrigin()
            coEvery { processManager.getProcessInfo(7) } returns
                mockk<ProcessInfo>(relaxed = true) { every { owner } returns "ci" }
            val dispatchSlot = slot<Dispatch>()
            coEvery { repo.createGroup(any(), capture(dispatchSlot), any(), any()) } returns GroupCreateOutcome.Created(5)

            create(service(), specs(2))

            assertEquals("ci", dispatchSlot.captured.owner)
        }

    @Test
    fun `create emits one QuestionAsked per member question`() =
        runTest {
            liveOrigin()
            coEvery { repo.createGroup(any(), any(), any(), any()) } returns GroupCreateOutcome.Created(5)

            val result = create(service(), specs(3))

            result.questionIds.forEach { id ->
                verify { broadcaster.emit(ProcessEvent.QuestionAsked(7, id, "asker")) }
            }
        }

    // The open-question cap is the bound on this call's own multiplication: a group adds up to
    // maxGroupSize questions at once, so it is counted against the origin's cap, not exempt.
    @Test
    fun `create passes the pending and open-question caps into the repository`() =
        runTest {
            liveOrigin()
            coEvery { repo.createGroup(any(), any(), any(), any()) } returns GroupCreateOutcome.Created(5)

            create(service(DispatchConfig(maxPending = 4), QuestionsConfig(maxOpenPerProcess = 6)), specs(2))

            coVerify { repo.createGroup(any(), any(), 4, 6) }
        }

    @Test
    fun `create maps both cap refusals to their exceptions`() =
        runTest {
            liveOrigin()
            coEvery { repo.createGroup(any(), any(), any(), any()) } returns GroupCreateOutcome.PendingCapReached
            assertFailsWith<TooManyPendingDispatchesException> { create(service(), specs(2)) }

            coEvery { repo.createGroup(any(), any(), any(), any()) } returns GroupCreateOutcome.OpenQuestionCapReached
            val refused =
                assertFailsWith<TooManyOpenQuestionsException> {
                    create(service(DispatchConfig(), QuestionsConfig(maxOpenPerProcess = 6)), specs(2))
                }
            assertEquals(6, refused.maxOpen)
        }

    @Test
    fun `maxGroupSize is the configured bound`() =
        runTest {
            assertEquals(8, service().maxGroupSize)
            assertEquals(3, service(DispatchConfig(maxGroupSize = 3)).maxGroupSize)
        }

    // ── aggregateAnswers: the fire gate ───────────────────────────────────────────────

    @Test
    fun `aggregateAnswers returns null while any member is still open`() =
        runTest {
            coEvery { repo.groupMembers(5) } returns
                listOf(
                    DispatchRepository.GroupMember("q1", "one"),
                    DispatchRepository.GroupMember("q2", null),
                )

            assertNull(service().aggregateAnswers(5))
        }

    @Test
    fun `aggregateAnswers returns the answers in member order`() =
        runTest {
            coEvery { repo.groupMembers(5) } returns
                listOf(
                    DispatchRepository.GroupMember("q1", "one"),
                    DispatchRepository.GroupMember("q2", "two"),
                    DispatchRepository.GroupMember("q3", "three"),
                )

            assertEquals("""["one","two","three"]""", service().aggregateAnswers(5))
        }

    // A group with no members is not "every member answered" — it is a group that can never
    // fire, and treating it as answered would start the target with no answers at all.
    @Test
    fun `aggregateAnswers never reports an empty group as answered`() =
        runTest {
            coEvery { repo.groupMembers(5) } returns emptyList()
            assertNull(service().aggregateAnswers(5))
        }

    // QuestionsService.answer bounds no answer's length, so N unbounded answers are the one way
    // the aggregate can outgrow what a start should carry. The refusal is an exception the fire
    // path settles FAILED — never a silently truncated payload.
    @Test
    fun `aggregateAnswers refuses an aggregate over the byte bound`() =
        runTest {
            val huge = "x".repeat(DispatchGroupService.MAX_ANSWER_BYTES)
            coEvery { repo.groupMembers(5) } returns
                listOf(
                    DispatchRepository.GroupMember("q1", huge),
                    DispatchRepository.GroupMember("q2", huge),
                )

            val refused = assertFailsWith<DispatchGroupAnswersTooLargeException> { service().aggregateAnswers(5) }
            assertEquals(DispatchGroupService.MAX_ANSWER_BYTES, refused.maxBytes)
        }

    @Test
    fun `aggregateAnswers accepts an aggregate exactly at the byte bound`() =
        runTest {
            // The JSON array is ["…"] — two quotes and two brackets around the payload.
            val atBound = "x".repeat(DispatchGroupService.MAX_ANSWER_BYTES - 4)
            coEvery { repo.groupMembers(5) } returns listOf(DispatchRepository.GroupMember("q1", atBound))

            assertEquals(DispatchGroupService.MAX_ANSWER_BYTES, service().aggregateAnswers(5)!!.length)
        }
}
