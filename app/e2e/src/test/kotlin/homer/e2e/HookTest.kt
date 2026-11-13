package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessInfo
import homer.e2e.support.ProcessList
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box coverage of agent-level status hooks (ADR-0107): a status transition of a run of
 * the declaring agent starts the hooked target exactly once with the `HMR_HOOK_*` metadata,
 * and a hook whose owner has no grant on the target is refused — the target never starts.
 */
class HookTest : E2eTestBase() {
    @Test
    fun `a finished run fires its declared hook once, with the hook metadata in the target's env`() =
        runBlocking {
            val declarerId = HomerApi.startRun("hook-declarer")
            assertEquals("FINISHED", Awaits.awaitTerminal(declarerId).status, "the declaring run ends FINISHED")

            val fired: ProcessInfo =
                Awaits.awaitNotNull("the FINISHED hook of run $declarerId to start hook-echo") {
                    HomerApi
                        .decode<ProcessList>(HomerApi.statusAll(mapOf("agentName" to "hook-echo")))
                        .processes
                        .singleOrNull { it.parentProcessId == declarerId }
                }
            assertEquals(
                declarerId,
                fired.rootProcessId,
                "the hook run's flow root is the declaring run, which this owner may see",
            )
            assertEquals("FINISHED", Awaits.awaitTerminal(fired.id).status, "the hook run ends FINISHED")

            // The fire settled the row dispatched, so exactly one target run exists for this
            // declarer — a second fire would need a second claim of the same row.
            assertEquals(
                1,
                HomerApi
                    .decode<ProcessList>(HomerApi.statusAll(mapOf("agentName" to "hook-echo")))
                    .processes
                    .count { it.parentProcessId == declarerId },
                "the hook must start its target exactly once",
            )

            val stdout = HomerApi.artifact(fired.id, "cmd_0.out").bodyAsText()
            assertTrue(stdout.contains("hook_process=$declarerId"), stdout)
            assertTrue(stdout.contains("hook_agent=hook-declarer"), stdout)
            assertTrue(stdout.contains("hook_status=FINISHED"), stdout)
            assertTrue(stdout.contains("hook_depth=1"), stdout)
            assertTrue(stdout.contains("parent=$declarerId"), stdout)
            assertTrue(stdout.contains("root=$declarerId"), stdout)
        }

    @Test
    fun `a hook whose owner lacks the grant on the target is refused and starts nothing`() =
        runBlocking {
            val declarerId =
                HomerApi.startRun(
                    "hook-deny-declarer",
                    headers = mapOf(HttpHeaders.Authorization to "Bearer hook-scoped-token"),
                )
            assertEquals("FINISHED", Awaits.awaitTerminal(declarerId).status, "the declaring run still ends FINISHED")

            // The refusal settles on the hook row, which no API surface reads; black-box, the
            // denial is the target's absence, polled past the fire window that the status
            // commit opens.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val runs =
                    HomerApi
                        .decode<ProcessList>(HomerApi.statusAll(mapOf("agentName" to "hook-echo-deny")))
                        .processes
                assertTrue(runs.none { it.parentProcessId == declarerId }, "a grant-denied hook started its target")
                delay(250)
            }
        }
}
