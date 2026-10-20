package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestionsTest : E2eTestBase() {
    @Test
    fun `asker opens a question the operator can answer to unblock the run`() =
        runBlocking {
            val processId = HomerApi.startRun("asker")

            val question =
                Awaits.awaitNotNull("OPEN question from process $processId") {
                    HomerApi.questions(status = "OPEN", processId = processId).questions.firstOrNull()
                }
            assertEquals("Pick one", question.text)
            assertEquals(listOf("A", "B"), question.options)

            val status = HomerApi.status(processId)
            assertEquals(1, status.openQuestions, "open question must be counted on the process status")

            val answered = HomerApi.answer(question.id, "A")
            assertEquals(HttpStatusCode.OK, answered.status)

            val info = Awaits.awaitTerminal(processId)
            assertEquals("FINISHED", info.status)
            assertTrue(
                HomerApi.artifact(processId, "cmd_0.out").bodyAsText().contains("answer=A"),
                "the answer must reach the blocked command verbatim",
            )
        }

    @Test
    fun `answering the same question twice conflicts`() =
        runBlocking {
            val processId = HomerApi.startRun("asker")
            val question =
                Awaits.awaitNotNull("OPEN question from process $processId") {
                    HomerApi.questions(status = "OPEN", processId = processId).questions.firstOrNull()
                }

            assertEquals(HttpStatusCode.OK, HomerApi.answer(question.id, "B").status)
            assertEquals(HttpStatusCode.Conflict, HomerApi.answer(question.id, "A").status)
            Awaits.awaitTerminal(processId)
            Unit
        }

    @Test
    fun `answering an unknown question id is 404 and a blank answer 400`() =
        runBlocking {
            assertEquals(HttpStatusCode.NotFound, HomerApi.answer("no-such-question-id", "A").status)

            val processId = HomerApi.startRun("asker")
            val question =
                Awaits.awaitNotNull("OPEN question from process $processId") {
                    HomerApi.questions(status = "OPEN", processId = processId).questions.firstOrNull()
                }
            assertEquals(HttpStatusCode.BadRequest, HomerApi.answer(question.id, "").status)
            HomerApi.answer(question.id, "A")
            Awaits.awaitTerminal(processId)
            Unit
        }

    @Test
    fun `killing a run with an open question expires it`() =
        runBlocking {
            val processId = HomerApi.startRun("asker")
            val question =
                Awaits.awaitNotNull("OPEN question from process $processId") {
                    HomerApi.questions(status = "OPEN", processId = processId).questions.firstOrNull()
                }

            HomerApi.kill(processId)
            Awaits.awaitStatus(processId, setOf("KILLED"))

            val expired =
                Awaits.awaitNotNull("question ${question.id} to expire after kill") {
                    HomerApi
                        .questions(processId = processId)
                        .questions
                        .firstOrNull { it.id == question.id && it.status == "EXPIRED" }
                }
            assertEquals("EXPIRED", expired.status)
        }
}
