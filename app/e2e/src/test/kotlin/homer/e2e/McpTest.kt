package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpTest : E2eTestBase() {
    @Test
    fun `tools list exposes opted-in agents plus the management tools`() =
        runBlocking {
            val response = mcpPost("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            assertEquals(HttpStatusCode.OK, response.status)

            val names =
                result(response)["tools"]!!
                    .jsonArray
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue("echo" in names, "mcp-opted-in agent must be a tool: $names")
            assertTrue("fail" !in names, "non-opted-in agents must not be exposed: $names")
            assertTrue(
                names.containsAll(listOf("get_run_info", "list_artifacts", "get_artifact", "cancel_run")),
                "management tools must always be registered: $names",
            )
        }

    @Test
    fun `agent tool call starts a run observable over MCP and REST`() =
        runBlocking {
            val call =
                mcpPost(
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"via-mcp"}}}""",
                )
            assertEquals(HttpStatusCode.OK, call.status)
            val started = result(call)["structuredContent"]!!.jsonObject
            val runId =
                started["runId"]!!
                    .jsonPrimitive.content
                    .toInt()

            assertEquals("FINISHED", Awaits.awaitTerminal(runId).status)
            assertTrue(
                HomerApi.artifact(runId, "cmd_0.out").bodyAsText().contains("got: via-mcp"),
                "the MCP tool argument must reach the run env",
            )

            val info =
                mcpPost(
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_run_info","arguments":{"runId":$runId}}}""",
                )
            val status =
                result(info)["structuredContent"]!!
                    .jsonObject["status"]!!
                    .jsonPrimitive.content
            assertEquals("FINISHED", status, "get_run_info must report the run's terminal status")
        }

    @Test
    fun `request without the dual Accept header is rejected with 406`() =
        runBlocking {
            val response =
                mcpPost(
                    """{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}""",
                    accept = "application/json",
                )
            assertEquals(HttpStatusCode.NotAcceptable, response.status)
        }

    /** POST a JSON-RPC message to /mcp with the Streamable-HTTP dual Accept header. */
    private suspend fun mcpPost(
        body: String,
        accept: String = "application/json, text/event-stream",
    ): HttpResponse =
        E2e.client.post("/mcp") {
            header(HttpHeaders.Accept, accept)
            setBody(TextContent(body, ContentType.Application.Json))
        }

    private suspend fun result(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        val result =
            E2e.json
                .parseToJsonElement(text)
                .jsonObject["result"]
                ?.jsonObject
        return assertNotNull(result, "expected a JSON-RPC result in: $text")
    }
}
