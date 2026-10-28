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

class RunMessagesTest : E2eTestBase() {
    @Test
    fun `a message sent to a running run is delivered and reaches the step`() =
        runBlocking {
            val processId = HomerApi.startRun("inbox-reader")
            Awaits.awaitStatus(processId, setOf("WORKING"))

            val sent = HomerApi.sendMessage(processId, "hello")
            assertEquals(HttpStatusCode.Accepted, sent.status, sent.bodyAsText())

            val info = Awaits.awaitTerminal(processId)
            assertEquals("FINISHED", info.status)
            assertEquals(listOf("DELIVERED"), HomerApi.messages(processId).messages.map { it.status })
            assertTrue(
                HomerApi.artifact(processId, "cmd_0.out").bodyAsText().contains("got=hello"),
                "the message must reach the polling step verbatim",
            )
        }

    @Test
    fun `a message to a run that never reads it ends UNDELIVERED`() =
        runBlocking {
            val processId = HomerApi.startRun("sleeper")
            Awaits.awaitStatus(processId, setOf("WORKING"))

            assertEquals(HttpStatusCode.Accepted, HomerApi.sendMessage(processId, "anyone there?").status)
            HomerApi.kill(processId)
            Awaits.awaitStatus(processId, setOf("KILLED"))

            val undelivered =
                Awaits.awaitNotNull("message of process $processId to end UNDELIVERED") {
                    HomerApi.messages(processId).messages.singleOrNull { it.status == "UNDELIVERED" }
                }
            assertEquals("anyone there?", undelivered.text)
        }

    @Test
    fun `sending to a finished run is 409`() =
        runBlocking {
            val processId = HomerApi.startRun("echo", query = mapOf("message" to "hi"))
            assertEquals("FINISHED", Awaits.awaitTerminal(processId).status)

            assertEquals(HttpStatusCode.Conflict, HomerApi.sendMessage(processId, "too late").status)
            assertTrue(HomerApi.messages(processId).messages.isEmpty(), "a refused message must not be stored")
        }
}
