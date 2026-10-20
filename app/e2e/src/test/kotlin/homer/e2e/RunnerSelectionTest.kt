package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runner selection by `runnerTags` through the claim queue (ADR-0066), exercised
 * against the built-in local runner: the fixture config gives it
 * `server.localRunner.tags: [e2e-local]`.
 */
class RunnerSelectionTest : E2eTestBase() {
    @Test
    fun `runnerTags served by the local runner run the agent`() =
        runBlocking {
            val processId = HomerApi.startRun("runner-tags-local")
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FINISHED", info.status)
            assertEquals(0, info.executions[0].resultCode)
            val stdout = HomerApi.artifactText(processId, info.executions[0].stdOut, "cmd_0.out")
            assertTrue(stdout.contains("claimed by a runner serving e2e-local"), "stdout was: $stdout")
            // The recorded backend is the serving runner, never the "tags:..." filter alias.
            assertEquals("local", info.runner?.alias, "a tag-routed run must record the serving backend")
            // The built-in local runner's alias ("local") is also what the non-tag e2e
            // checks, so it cannot on its own distinguish "resolved to the serving
            // backend" from "left as queued": also assert the queued filter string is
            // gone, which fails on a build that writes it unmodified or never overwrites it.
            assertTrue(
                info.runner?.alias?.startsWith("tags:") != true,
                "the queued filter alias must not survive into the recorded runner state",
            )
        }

    @Test
    fun `runnerTags nobody serves fail the run at the claim TTL without running a command`() =
        runBlocking {
            // The claim happens inside the launched run, so the start itself is accepted.
            val processId = HomerApi.startRun("runner-tags-unserved")
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FAILED", info.status)
            assertTrue(info.executions.isEmpty(), "no command may run without a claimed runner: ${info.executions}")
            // No session ever opened, so no backend served the run: runner stays null.
            assertEquals(null, info.runner, "an unclaimed run must not report a serving backend")
        }
}
