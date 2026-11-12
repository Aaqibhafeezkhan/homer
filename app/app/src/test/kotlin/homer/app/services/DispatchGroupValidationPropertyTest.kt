package homer.app.services

import homer.app.exceptions.DispatchGroupTooLargeException
import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.models.Agent
import homer.app.models.AgentDispatchConfig
import homer.app.models.DispatchConfig
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.DispatchRepository.GroupCreateOutcome
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
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

    private fun service(maxGroupSize: Int) = DispatchGroupService(
        repo,
        questions,
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
}
