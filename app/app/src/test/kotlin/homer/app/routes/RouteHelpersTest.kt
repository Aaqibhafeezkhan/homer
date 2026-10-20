package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import io.ktor.http.parametersOf
import io.ktor.server.application.ApplicationCall
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouteHelpersTest {
    private fun callWhoseReceiveThrows(failure: Throwable): ApplicationCall =
        mockk {
            coEvery { receiveNullable<Any>(any()) } throws failure
        }

    @Test
    fun `receiveOr400 lets a cancellation through instead of answering 400`() {
        val call = callWhoseReceiveThrows(CancellationException("client went away"))

        val thrown = assertFailsWith<CancellationException> { runBlocking { call.receiveOr400<String>("unused") } }

        assertEquals("client went away", thrown.message)
    }

    @Test
    fun `receiveOr400 turns any other failure into a 400 with the given message`() {
        val call = callWhoseReceiveThrows(IllegalStateException("bad body"))

        val thrown = assertFailsWith<QueryParamValidationException> { runBlocking { call.receiveOr400<String>("Invalid JSON body") } }

        assertEquals("Invalid JSON body", thrown.message)
    }

    @Test
    fun `pathParameter returns the declared parameter and names a missing one`() {
        val call = mockk<ApplicationCall> { every { parameters } returns parametersOf("id", "42") }

        assertEquals("42", call.pathParameter("id"))
        val thrown = assertFailsWith<IllegalStateException> { call.pathParameter("agentName") }
        assertEquals("route declares no {agentName} path parameter", thrown.message)
    }
}
