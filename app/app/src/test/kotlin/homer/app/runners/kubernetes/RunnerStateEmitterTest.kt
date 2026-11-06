package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import homer.app.models.PodPhase
import homer.app.models.PodTermination
import homer.app.models.RunnerState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunnerStateEmitterTest {
    @Test
    fun `every write rebuilt from an empty sink keeps the Job name`() =
        runTest {
            val written = mutableListOf<RunnerState>()
            val states = RunnerStateEmitter("k8s", 7, "homer-7-abc", { 9_000L }, { _, update -> written.add(update(null)) })

            states.creating(null, 1)
            states.pending(null, 1)
            states.podFound("homer-7-abc-x", 1)
            states.ready("homer-7-abc-x", 1)
            states.terminated(PodTermination("DeadlineExceeded", "late"), "homer-7-abc-x", 1)
            states.startupFailed(PodStartupException("PodStartTimeout", "slow", emptyList()))

            assertEquals(listOf("homer-7-abc"), written.map { it.jobName }.distinct())
            assertEquals(
                listOf(PodPhase.CREATING, PodPhase.PENDING, PodPhase.PENDING, PodPhase.READY, PodPhase.FAILED, PodPhase.FAILED),
                written.map { it.podPhase },
            )
            assertEquals(9L, written[3].readyAt)
            assertEquals("PodStartTimeout", written.last().startupFailure?.reason)
        }

    @Test
    fun `Pod mode writes no Job name and a failing sink is not fatal`() =
        runTest {
            var last: RunnerState? = null
            RunnerStateEmitter("k8s", 7, null, { 0L }, { _, update -> last = update(null) }).creating("homer-7-abc", 1)
            assertNull(last?.jobName)
            assertEquals("homer-7-abc", last?.podName)

            RunnerStateEmitter("k8s", 7, null, { 0L }, { _, _ -> error("sink down") }).ready("homer-7-abc", 1)
        }
}
