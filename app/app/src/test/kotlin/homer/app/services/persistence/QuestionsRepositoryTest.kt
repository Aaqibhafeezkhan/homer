package homer.app.services.persistence

import homer.app.fixtures.PersistenceTestSupport
import homer.app.services.QuestionsService.Question
import homer.app.services.QuestionsService.QuestionStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionsRepositoryTest {
    private fun repo(db: DatabaseService = PersistenceTestSupport.tempDatabase()) = QuestionsRepository(db)

    private fun question(
        id: String,
        processId: Int,
        status: QuestionStatus = QuestionStatus.OPEN,
        options: List<String> = emptyList(),
    ) = Question(
        id = id,
        processId = processId,
        agentName = "agent-a",
        text = "Proceed?",
        options = options,
        status = status,
        createdAt = 1000L,
    )

    @Test
    fun `tryInsert enforces maxOpenPerProcess`() =
        runTest {
            val repo = repo()
            assertTrue(repo.tryInsert(question("q1", 1), maxOpenPerProcess = 2))
            assertTrue(repo.tryInsert(question("q2", 1), maxOpenPerProcess = 2))
            assertEquals(false, repo.tryInsert(question("q3", 1), maxOpenPerProcess = 2))
            assertEquals(2, repo.openCountByProcess()[1])
        }

    @Test
    fun `get round-trips options and fields`() =
        runTest {
            val repo = repo()
            repo.tryInsert(question("q1", 7, options = listOf("yes", "no")), maxOpenPerProcess = 10)

            val loaded = repo.get("q1")!!
            assertEquals(7, loaded.processId)
            assertEquals(listOf("yes", "no"), loaded.options)
            assertEquals(QuestionStatus.OPEN, loaded.status)
        }

    @Test
    fun `markAnswered transitions only an OPEN question`() =
        runTest {
            val repo = repo()
            repo.tryInsert(question("q1", 1), maxOpenPerProcess = 10)

            val first = repo.markAnswered("q1", "go", 2000L)
            assertTrue(first is QuestionsRepository.AnswerResult.Answered)
            assertEquals("go", first.question.answer)

            // Second answer conflicts; unknown id is NotFound.
            assertTrue(repo.markAnswered("q1", "again", 2001L) is QuestionsRepository.AnswerResult.NotOpen)
            assertTrue(repo.markAnswered("missing", "x", 2002L) is QuestionsRepository.AnswerResult.NotFound)
        }

    @Test
    fun `expireForProcess flips only OPEN questions and returns them`() =
        runTest {
            val repo = repo()
            repo.tryInsert(question("open", 1), maxOpenPerProcess = 10)
            repo.tryInsert(question("answered", 1), maxOpenPerProcess = 10)
            repo.markAnswered("answered", "done", 1500L)

            val expired = repo.expireForProcess(1)

            assertEquals(listOf("open"), expired.map { it.id })
            assertEquals(QuestionStatus.EXPIRED, repo.get("open")!!.status)
            assertEquals(QuestionStatus.ANSWERED, repo.get("answered")!!.status)
        }

    @Test
    fun `expireIfOpen expires only that question and only while OPEN`() =
        runTest {
            val repo = repo()
            repo.tryInsert(question("target", 1), maxOpenPerProcess = 10)
            repo.tryInsert(question("sibling", 1), maxOpenPerProcess = 10)
            repo.tryInsert(question("answered", 1), maxOpenPerProcess = 10)
            repo.markAnswered("answered", "done", 1500L)

            assertTrue(repo.expireIfOpen("target"))
            assertEquals(false, repo.expireIfOpen("target"))
            assertEquals(false, repo.expireIfOpen("answered"))
            assertEquals(false, repo.expireIfOpen("missing"))

            assertEquals(QuestionStatus.EXPIRED, repo.get("target")!!.status)
            assertEquals(QuestionStatus.OPEN, repo.get("sibling")!!.status)
            assertEquals(QuestionStatus.ANSWERED, repo.get("answered")!!.status)
        }

    @Test
    fun `delete removes only that question`() =
        runTest {
            val repo = repo()
            repo.tryInsert(question("target", 1), maxOpenPerProcess = 10)
            repo.tryInsert(question("sibling", 1), maxOpenPerProcess = 10)

            assertTrue(repo.delete("target"))
            assertEquals(false, repo.delete("target"))

            assertNull(repo.get("target"))
            assertEquals(QuestionStatus.OPEN, repo.get("sibling")!!.status)
        }

    @Test
    fun `removeForProcess deletes all questions of a process`() =
        runTest {
            val repo = repo()
            repo.tryInsert(question("q1", 1), maxOpenPerProcess = 10)
            repo.tryInsert(question("q2", 1), maxOpenPerProcess = 10)
            repo.tryInsert(question("other", 2), maxOpenPerProcess = 10)

            val removed = repo.removeForProcess(1)

            assertEquals(setOf("q1", "q2"), removed.toSet())
            assertNull(repo.get("q1"))
            assertEquals(QuestionStatus.OPEN, repo.get("other")!!.status)
        }

    @Test
    fun `expireForProcess skips dispatch-bound questions`() =
        runTest {
            val repo = repo()
            val normal = sampleQuestion(id = "n1", processId = 5, dispatchBound = false)
            val bound = sampleQuestion(id = "b1", processId = 5, dispatchBound = true)
            repo.tryInsert(normal, maxOpenPerProcess = 10)
            repo.tryInsert(bound, maxOpenPerProcess = 10)

            val expired = repo.expireForProcess(5)

            assertEquals(listOf("n1"), expired.map { it.id })
            assertEquals(QuestionStatus.OPEN, repo.get("b1")!!.status)
        }

    @Test
    fun `removeForProcess skips dispatch-bound questions`() =
        runTest {
            val repo = repo()
            repo.tryInsert(sampleQuestion(id = "n1", processId = 6, dispatchBound = false), 10)
            repo.tryInsert(sampleQuestion(id = "b1", processId = 6, dispatchBound = true), 10)

            val removed = repo.removeForProcess(6)

            assertEquals(listOf("n1"), removed)
            assertEquals(QuestionStatus.OPEN, repo.get("b1")!!.status)
        }

    @Test
    fun `tryInsert cap ignores dispatch-bound questions`() =
        runTest {
            val repo = repo()
            // Insert one dispatch-bound OPEN question — saturates maxOpenPerProcess=1 only if counted
            repo.tryInsert(sampleQuestion(id = "b1", processId = 10, dispatchBound = true), maxOpenPerProcess = 1)
            // A normal tryInsert should still succeed because dispatch-bound ones don't count toward the cap
            assertTrue(repo.tryInsert(sampleQuestion(id = "n1", processId = 10, dispatchBound = false), maxOpenPerProcess = 1))
        }

    @Test
    fun `openCountByProcess ignores dispatch-bound questions`() =
        runTest {
            val repo = repo()
            repo.tryInsert(sampleQuestion(id = "n1", processId = 20, dispatchBound = false), maxOpenPerProcess = 10)
            repo.tryInsert(sampleQuestion(id = "b1", processId = 20, dispatchBound = true), maxOpenPerProcess = 10)
            // Only the normal (non-dispatch-bound) question should be counted
            assertEquals(1, repo.openCountByProcess()[20])
        }

    private fun sampleQuestion(
        id: String,
        processId: Int,
        dispatchBound: Boolean,
    ) = Question(
        id = id,
        processId = processId,
        agentName = "agent",
        text = "t",
        options = emptyList(),
        status = QuestionStatus.OPEN,
        createdAt = 1,
        dispatchBound = dispatchBound,
    )
}
