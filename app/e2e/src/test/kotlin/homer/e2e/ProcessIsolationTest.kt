package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessInfo
import homer.e2e.support.ProcessList
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Process-scoped visibility (ADR-0038): a caller sees a process iff admin,
 * the process's owner, or a `read` grant on its agent. `echoScoped`
 * (fixtures/config/config.json) holds `run`+`read` on `echo` only, so it is
 * the isolation probe against a `fail` run started by the admin token.
 */
class ProcessIsolationTest : E2eTestBase() {
    private val scopedAuth = "Bearer echo-scoped-token"

    @Test
    fun `scoped token cannot see or list another agent's run`() =
        runBlocking {
            val otherId = Awaits.awaitTerminal(HomerApi.startRun("fail")).id

            val statusResponse =
                E2e.client.get("/api/v1/status/$otherId") {
                    header(HttpHeaders.Authorization, scopedAuth)
                }
            assertEquals(HttpStatusCode.NotFound, statusResponse.status, "existence must be hidden, not 403")

            val list: ProcessList =
                HomerApi.decode(
                    E2e.client.get("/api/v1/status/all") {
                        header(HttpHeaders.Authorization, scopedAuth)
                        parameter("agentName", "fail")
                    },
                )
            assertFalse(list.processes.any { it.id == otherId }, "scoped token must not see agent 'fail' runs it doesn't own")
        }

    // ADR-0047 rule 3: no grant on `fail` means `fail` must be indistinguishable from an unknown agent.
    @Test
    fun `scoped token cannot tell an agent it holds no grant on from an unknown one`() =
        runBlocking {
            for (route in listOf("/api/v1/agent/%s", "/api/v1/agents/%s/debug")) {
                val hidden = E2e.client.post(route.format("fail")) { header(HttpHeaders.Authorization, scopedAuth) }
                val unknown = E2e.client.post(route.format("no-such-agent")) { header(HttpHeaders.Authorization, scopedAuth) }
                assertEquals(HttpStatusCode.NotFound, hidden.status, "$route must hide 'fail'")
                assertEquals(HttpStatusCode.NotFound, unknown.status, "$route on an unknown agent")
                assertEquals(unknown.bodyAsText().replace("no-such-agent", "fail"), hidden.bodyAsText(), route)
            }
        }

    @Test
    fun `scoped token gets 403 for an action it lacks on an agent it can see`() =
        runBlocking {
            val response = E2e.client.post("/api/v1/agents/echo/debug") { header(HttpHeaders.Authorization, scopedAuth) }
            assertEquals(HttpStatusCode.Forbidden, response.status, "echoScoped holds run+read on echo, not debug")
        }

    @Test
    fun `scoped token sees and lists its own echo run`() =
        runBlocking {
            val echoId =
                HomerApi.startRun(
                    "echo",
                    query = mapOf("message" to "scoped-read"),
                    headers = mapOf(HttpHeaders.Authorization to scopedAuth),
                )
            Awaits.awaitTerminal(echoId)

            val statusResponse =
                E2e.client.get("/api/v1/status/$echoId") {
                    header(HttpHeaders.Authorization, scopedAuth)
                }
            assertEquals(HttpStatusCode.OK, statusResponse.status)
            val info = E2e.json.decodeFromString<ProcessInfo>(statusResponse.bodyAsText())
            assertEquals("echoScoped", info.owner, "owner must be stamped with the bearer token's config key")

            val list: ProcessList =
                HomerApi.decode(
                    E2e.client.get("/api/v1/status/all") {
                        header(HttpHeaders.Authorization, scopedAuth)
                        parameter("agentName", "echo")
                    },
                )
            assertTrue(list.processes.any { it.id == echoId }, "scoped token must see its own echo run in /status/all")
        }

    /**
     * ADR-0098: a failed run's reason is served to every caller who can see the run, but the
     * id of the run that blocked it is appended only to a caller who may open that run too
     * (ADR-0058's existence-hiding applied to the holder link). `lock-skip-scoped` shares
     * `lock-skip`'s lock key, so the blocker sits on an agent `skipScoped` holds no grant on.
     */
    @Test
    fun `a scoped reader gets the failure reason but not the blocking run's id`() =
        runBlocking {
            val holderId = HomerApi.startRun("lock-skip")
            Awaits.awaitStatus(holderId, setOf("WORKING"))
            val skippedId = HomerApi.startRun("lock-skip-scoped")
            val failed = Awaits.awaitTerminal(skippedId)
            assertEquals("FAILED", failed.status)
            // Awaited, not read off the terminal poll: the reason write trails the FAILED status.
            val reason =
                Awaits.awaitNotNull("the skipped run's failure reason") {
                    Awaits.awaitTerminal(skippedId).failureReason
                }
            assertTrue(reason.contains("(run #$holderId)"), "the admin caller may open the holder: $reason")

            val scoped =
                E2e.client.get("/api/v1/status/$skippedId") {
                    header(HttpHeaders.Authorization, "Bearer skip-scoped-token")
                }
            assertEquals(HttpStatusCode.OK, scoped.status, "skipScoped holds a read grant on lock-skip-scoped")
            val scopedInfo = E2e.json.decodeFromString<ProcessInfo>(scoped.bodyAsText())
            assertEquals(
                reason.substringBefore(" (run #"),
                scopedInfo.failureReason,
                "the reason itself is not admin-only, only the holder id is",
            )
            assertFalse(
                scopedInfo.failureReason.orEmpty().contains("(run #$holderId)"),
                "the blocking run's id must be withheld from a caller who cannot see it",
            )
        }

    @Test
    fun `admin sees runs regardless of owner`() =
        runBlocking {
            val otherId = Awaits.awaitTerminal(HomerApi.startRun("fail")).id
            val echoId =
                HomerApi.startRun(
                    "echo",
                    query = mapOf("message" to "admin-visibility"),
                    headers = mapOf(HttpHeaders.Authorization to scopedAuth),
                )
            Awaits.awaitTerminal(echoId)

            val adminAll: ProcessList = HomerApi.decode(HomerApi.statusAll())
            assertTrue(adminAll.processes.any { it.id == otherId }, "admin must see the 'fail' run it doesn't own")
            assertTrue(adminAll.processes.any { it.id == echoId }, "admin must see the echo run owned by the scoped token")
        }
}
