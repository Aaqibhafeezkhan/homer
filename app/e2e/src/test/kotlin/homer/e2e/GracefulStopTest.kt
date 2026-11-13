package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GracefulStopTest : E2eTestBase() {
    @Test
    fun `a step timeout with onTimeout stop ends FAILED with a stop record and runs the onStop steps`() =
        runBlocking {
            val processId = HomerApi.startRun("graceful-stop")
            val info = Awaits.awaitTerminal(processId)

            assertEquals("FAILED", info.status, "a gracefully stopped run still ends FAILED")
            val stop = assertNotNull(info.stop, "the run must carry the stop record")
            assertEquals("step_timeout", stop.reason)
            assertEquals(0, stop.stepIndex)
            assertEquals("overslept", stop.stepLabel)
            assertEquals("finished", stop.stopSteps)

            // Only the interrupted step and the stop step ran: the follow-up step never starts.
            assertEquals(listOf("overslept", "record-stop"), info.executions.map { it.label })
            assertEquals("step_timeout", info.executions[0].stoppedBy)
            assertEquals(false, info.executions[0].onStop)
            assertEquals(true, info.executions[1].onStop)
            assertNull(info.executions[1].stoppedBy)
            assertEquals(0, info.executions[1].resultCode)

            // The stop step received the HMR_STOP_* variables and wrote them to artifacts.
            val stopEnv = HomerApi.artifactText(processId, null, "stop-env.txt")
            assertEquals("reason=step_timeout step=0 label=overslept", stopEnv.trim())
        }
}
