package homer.e2e

import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.LangGraphGraph
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/v1/agents/{name}/langgraph` runs `homer_langgraph inspect --json` on the
 * server host against the graph module each fixture agent ships (ADR-0073). Nothing
 * here runs a graph, so it needs no LLM credentials.
 */
class LangGraphInspectTest : E2eTestBase() {
    private suspend fun inspect(agent: String): List<LangGraphGraph> = HomerApi.decode(E2e.client.get("/api/v1/agents/$agent/langgraph"))

    @Test
    fun `a real graph's topology is served`() =
        runBlocking {
            val graphs = inspect("langgraph-inspect")

            val graph = graphs.single()
            assertNull(graph.error, "inspect must succeed; got error ${graph.error}")
            val topology = assertNotNull(graph.topology, "a successful inspect serves the topology")
            assertEquals(
                setOf("__start__", "plan", "review", "__end__"),
                topology.nodes.map { it.id }.toSet(),
            )
            val done =
                assertNotNull(
                    topology.edges.firstOrNull { it.source == "review" && it.target == "__end__" },
                    "the review -> END route must be an edge; got ${topology.edges}",
                )
            assertEquals("done", done.label)
            assertTrue(done.conditional, "review -> END is a conditional route")
            val again = topology.edges.single { it.source == "review" && it.target == "plan" }
            assertEquals("again", again.label)
            assertTrue(again.conditional, "review -> plan is a conditional route")
        }

    @Test
    fun `an agent without a LangGraph command serves an empty list`() =
        runBlocking {
            assertEquals(emptyList(), inspect("echo"))
        }

    @Test
    fun `a graph over the node limit is refused, not served`() =
        runBlocking {
            val graph = inspect("langgraph-too-big").single()

            assertNull(graph.topology, "an over-limit topology must not be served")
            val error = assertNotNull(graph.error, "an over-limit topology must surface an error")
            assertTrue("201 nodes" in error && "limit 200" in error, "the error must name the node limit; got $error")
        }

    @Test
    fun `a caller without a grant on the agent cannot tell it from an unknown one`() =
        runBlocking {
            // echoScoped holds grants on `echo` only (fixtures/config/config.json).
            val hidden =
                E2e.client.get("/api/v1/agents/langgraph-inspect/langgraph") {
                    header(HttpHeaders.Authorization, "Bearer echo-scoped-token")
                }
            val unknown =
                E2e.client.get("/api/v1/agents/no-such-agent/langgraph") {
                    header(HttpHeaders.Authorization, "Bearer echo-scoped-token")
                }

            assertEquals(HttpStatusCode.NotFound, hidden.status)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
        }
}
