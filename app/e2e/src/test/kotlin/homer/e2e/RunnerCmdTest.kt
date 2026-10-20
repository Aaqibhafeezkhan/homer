package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunnerCmdTest : E2eTestBase() {
    @Test
    fun `runnerCmd on local runner passes checks and executes with input env`() =
        runBlocking {
            val processId = HomerApi.startRun("runnercmd-local", query = mapOf("message" to "hello-runnercmd"))
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FINISHED", info.status)
            assertEquals(0, info.executions[0].resultCode)
            assertTrue(stdout(info, 0).contains("runnercmd got: hello-runnercmd"))
        }

    @Test
    fun `runnerCmd check failure surfaces as the command exit code`() =
        runBlocking {
            val processId = HomerApi.startRun("runnercmd-check-fail")
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FAILED", info.status)
            assertEquals(3, info.executions[0].resultCode)
        }

    private suspend fun stdout(
        info: ProcessInfo,
        cmdIndex: Int,
    ): String = HomerApi.artifactText(info.id, info.executions[cmdIndex].stdOut, "cmd_$cmdIndex.out")
}
