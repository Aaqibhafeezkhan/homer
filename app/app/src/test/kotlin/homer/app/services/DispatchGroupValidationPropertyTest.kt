package homer.app.services

import homer.app.exceptions.DispatchGroupAnswersTooLargeException
import homer.app.exceptions.DispatchGroupTooLargeException
import homer.app.exceptions.ValidationException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentDispatchConfig
import homer.app.models.DispatchConfig
import homer.app.models.QuestionsConfig
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.DispatchRepository.GroupCreateOutcome
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group's two bounds, as properties rather than examples: the size gate is a comparison
 * against a configurable bound, and the aggregate is a serialization of an arbitrary answer set.
 * Example tests pin the cases the implementation was written around; these pin the ones it was
 * not. See root §6.
 */
class DispatchGroupValidationPropertyTest {
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
        maxGroupSize: Int,
        questionsService: QuestionsService = questions,
    ) = DispatchGroupService(
        repo,
        questionsService,
        agents,
        authorizer,
        inputResolver,
        processManager,
        broadcaster,
        LoggerFactory.getLogger("t"),
        DispatchConfig(maxGroupSize = maxGroupSize),
    ) { 1000L }

    init {
        every { questions.registrationFor(7) } returns QuestionsService.Registration("asker", closed = false)
        val agent = mockk<Agent>(relaxed = true)
        every { agent.dispatch } returns AgentDispatchConfig(enabled = true)
        every { agents.agents } returns mapOf("h" to agent)
        coEvery { repo.createGroup(any(), any(), any(), any()) } returns GroupCreateOutcome.Created(1)
    }

    @Test
    fun `create accepts exactly the groups at or under the bound and refuses every group over it`() =
        runTest {
            checkAll(Arb.int(1..12), Arb.int(0..20)) { maxGroupSize, n ->
                val specs = List(n) { DispatchGroupService.QuestionSpec("q$it") }
                val call: suspend () -> DispatchGroupService.CreateResult = {
                    service(maxGroupSize).create(7, specs, "h", "answer", emptyMap(), null)
                }

                if (n == 0) {
                    // Empty is not a size refusal: a group with no questions is a malformed
                    // request, not one over a bound.
                    val refused = assertFailsWith<ValidationException> { call() }
                    assertEquals("questions", refused.field, "n=$n")
                } else if (n > maxGroupSize) {
                    val refused = assertFailsWith<DispatchGroupTooLargeException> { call() }
                    assertEquals(maxGroupSize, refused.maxGroupSize, "n=$n")
                } else {
                    assertEquals(n, call().questionIds.size, "n=$n max=$maxGroupSize")
                }
            }
        }

    @Test
    fun `aggregateAnswers is null exactly while any member is unanswered and otherwise round-trips the answers in order`() =
        runTest {
            // 0 encodes "still unanswered"; every other code an answer text.
            checkAll(Arb.list(Arb.int(0..3), 0..8)) { codes ->
                val answers = codes.map { if (it == 0) null else "a$it" }
                coEvery { repo.groupMembers(1) } returns
                    answers.mapIndexed { i, a -> DispatchRepository.GroupMember("q$i", a) }

                val payload = service(8).aggregateAnswers(1)

                if (answers.isEmpty() || answers.any { it == null }) {
                    assertNull(payload, "answers=$answers")
                } else {
                    assertTrue(payload != null, "answers=$answers")
                    assertEquals(answers, Json.decodeFromString(ListSerializer(String.serializer()), payload))
                }
            }
        }

    // The text bound is the questions service's, applied per member before anything is written, so
    // one rejected text refuses the whole group. The validator here is a real [QuestionsService]
    // configured to a small bound, not a stub mirroring the check: the property is about where the
    // boundary actually falls, and blank is a separate refusal on the same field.
    @Test
    fun `create admits exactly the groups whose every question text is inside the length bound`() =
        runTest {
            val real = PersistenceTestSupport.questionsService(config = QuestionsConfig(maxTextLength = 8))
            real.registerProcess(7, "asker")
            val svc = service(maxGroupSize = 4, questionsService = real)

            checkAll(Arb.list(Arb.string(0..12), 1..4)) { texts ->
                val specs = texts.map { DispatchGroupService.QuestionSpec(it) }
                val call: suspend () -> DispatchGroupService.CreateResult = {
                    svc.create(7, specs, "h", "answer", emptyMap(), null)
                }

                if (texts.any { it.isBlank() || it.length > 8 }) {
                    val refused = assertFailsWith<ValidationException> { call() }
                    assertEquals("text", refused.field, "texts=$texts")
                } else {
                    assertEquals(texts.size, call().questionIds.size, "texts=$texts")
                }
            }
        }

    // `QuestionsService.answer` bounds no answer's length, so the aggregate is the one place a
    // group's payload can outgrow what a start should carry. The decision is pinned to the size of
    // the serialized payload — not to any one answer, nor to their raw sum — so an off-by-one, or
    // a check against the wrong quantity, fails here rather than shipping.
    @Test
    fun `aggregateAnswers admits exactly the payloads inside the byte bound and refuses every one over it`() =
        runTest {
            checkAll(Arb.list(Arb.int(0..DispatchGroupService.MAX_ANSWER_BYTES / 2), 1..3)) { lengths ->
                val answers = lengths.map { "x".repeat(it) }
                coEvery { repo.groupMembers(1) } returns
                    answers.mapIndexed { i, a -> DispatchRepository.GroupMember("q$i", a) }
                val payload = Json.encodeToString(ListSerializer(String.serializer()), answers)

                if (payload.toByteArray(Charsets.UTF_8).size > DispatchGroupService.MAX_ANSWER_BYTES) {
                    val refused =
                        assertFailsWith<DispatchGroupAnswersTooLargeException> { service(8).aggregateAnswers(1) }
                    assertEquals(DispatchGroupService.MAX_ANSWER_BYTES, refused.maxBytes, "lengths=$lengths")
                } else {
                    val read = service(8).aggregateAnswers(1)
                    assertEquals(
                        answers,
                        Json.decodeFromString(ListSerializer(String.serializer()), read!!),
                        "lengths=$lengths",
                    )
                }
            }
        }
}
