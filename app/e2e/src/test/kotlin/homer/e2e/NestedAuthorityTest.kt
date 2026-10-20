package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Nested-dispatch authority (ADR-0039): a `RunAgent` hop `A -> B` is
 * authorized iff the root principal holds `run` on B, re-resolved live, OR
 * `server.agentGrants[A]` delegates B. `nested-token` (fixtures/config/
 * config.json) holds `run`+`read` on `parent` and `orphan-parent` only —
 * neither grants it `echo` directly, so both fixtures' hops depend entirely
 * on `server.agentGrants`, which delegates only `parent -> echo`.
 *
 * The `/api/v1/callback/runs`/`/joins` unauthorized-hop -> `404` path is
 * already covered by Task 5's integration tests
 * (`CallbackRoutesTest`); wiring a callback token through this black-box
 * suite is out of scope here.
 */
class NestedAuthorityTest : E2eTestBase() {
    private val nestedAuth = "Bearer nested-token"

    @Test
    fun `orphan-parent hop is denied without an agentGrants delegation`() =
        runBlocking {
            val processId =
                HomerApi.startRun(
                    "orphan-parent",
                    headers = mapOf(HttpHeaders.Authorization to nestedAuth),
                )
            val info = Awaits.awaitTerminal(processId)

            assertEquals(1, info.executions.size)
            assertEquals(
                255,
                info.executions[0].resultCode,
                "the RunAgent hop orphan-parent -> echo must be denied: nested-token has no " +
                    "grant on 'echo' and 'orphan-parent' is absent from server.agentGrants",
            )
            // ADR-0047 rule 3: the denial reads exactly like an unknown agent, so it confirms nothing.
            assertEquals(
                "agent 'echo' not found",
                HomerApi.artifactText(processId, info.executions[0].stdErr, "cmd_0.err"),
            )
        }

    @Test
    fun `parent hop is allowed by an agentGrants delegation`() =
        runBlocking {
            val processId =
                HomerApi.startRun(
                    "parent",
                    headers = mapOf(HttpHeaders.Authorization to nestedAuth),
                )
            val info = Awaits.awaitTerminal(processId)

            assertEquals(1, info.executions.size)
            assertEquals(
                0,
                info.executions[0].resultCode,
                "the RunAgent hop parent -> echo must be allowed: server.agentGrants delegates " +
                    "parent -> echo even though nested-token itself holds no grant on 'echo'",
            )
        }
}
