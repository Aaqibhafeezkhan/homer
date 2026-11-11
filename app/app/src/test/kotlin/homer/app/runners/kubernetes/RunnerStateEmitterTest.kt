package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import homer.app.models.PodPhase
import homer.app.models.PodStartupFailure
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

    @Test
    fun `creating pins every field and drops what a previous state carried`() =
        runTest {
            // Job mode: the Job controller names the pod, so the state carries none yet.
            assertEquals(
                RunnerState(alias = "k8s", podPhase = PodPhase.CREATING, createdAt = 1, jobName = JOB),
                emitted(carried()) { it.creating(null, 1) },
            )
            // Pod mode: the runner named the pod itself.
            assertEquals(
                RunnerState(alias = "k8s", podName = "pod-1", podPhase = PodPhase.CREATING, createdAt = 1),
                emitted(carried(), job = null) { it.creating("pod-1", 1) },
            )
        }

    @Test
    fun `pending keeps the previous state's pod name and timestamps`() =
        runTest {
            val prev =
                RunnerState(alias = "k8s", podName = "old-pod", podPhase = PodPhase.READY, createdAt = 111, readyAt = 222, jobName = JOB)

            assertEquals(
                RunnerState(alias = "k8s", podName = "old-pod", podPhase = PodPhase.PENDING, createdAt = 111, readyAt = 222, jobName = JOB),
                emitted(prev) { it.pending("new-pod", 999) },
            )
            assertEquals(
                RunnerState(alias = "k8s", podName = "old-pod", podPhase = PodPhase.PENDING, createdAt = 111, readyAt = 222),
                emitted(prev.copy(jobName = null), job = null) { it.pending("new-pod", 999) },
            )
        }

    @Test
    fun `pending with no previous state carries the arguments`() =
        runTest {
            assertEquals(
                RunnerState(alias = "k8s", podName = "new-pod", podPhase = PodPhase.PENDING, createdAt = 999, jobName = JOB),
                emitted(null) { it.pending("new-pod", 999) },
            )
            assertEquals(
                RunnerState(alias = "k8s", podName = "new-pod", podPhase = PodPhase.PENDING, createdAt = 999),
                emitted(null, job = null) { it.pending("new-pod", 999) },
            )
        }

    @Test
    fun `podFound names the pod the Job controller created and keeps the previous state`() =
        runTest {
            val jobPending = emitted(null) { it.pending(null, 1) }

            assertEquals(
                RunnerState(alias = "k8s", podName = FOUND_POD, podPhase = PodPhase.PENDING, createdAt = 1, jobName = JOB),
                emitted(jobPending) { it.podFound(FOUND_POD, 1) },
            )
            assertEquals(
                RunnerState(alias = "k8s", podName = "pod-1", podPhase = PodPhase.PENDING, createdAt = 1),
                emitted(null, job = null) { it.podFound("pod-1", 1) },
            )
        }

    @Test
    fun `ready stamps readyAt from the clock and keeps the previous state`() =
        runTest {
            // In Job mode the pod name reached the state through podFound, before the pod became ready.
            val jobFound = emitted(null) { it.podFound(FOUND_POD, 1) }

            assertEquals(
                RunnerState(alias = "k8s", podName = FOUND_POD, podPhase = PodPhase.READY, createdAt = 1, readyAt = 9, jobName = JOB),
                emitted(jobFound) { it.ready(FOUND_POD, 1) },
            )
            assertEquals(
                RunnerState(alias = "k8s", podName = "pod-1", podPhase = PodPhase.READY, createdAt = 1, readyAt = 9),
                emitted(null, job = null) { it.ready("pod-1", 1) },
            )
        }

    @Test
    fun `startupFailed stamps endedAt, FAILED and the failure`() =
        runTest {
            val ex = PodStartupException("PodStartTimeout", "slow", listOf("Warning/Failed: x"))
            val failed = PodStartupFailure("PodStartTimeout", "slow", listOf("Warning/Failed: x"))

            assertEquals(
                RunnerState(alias = "k8s", podPhase = PodPhase.FAILED, endedAt = 9, startupFailure = failed, jobName = JOB),
                emitted(null) { it.startupFailed(ex) },
            )
            assertEquals(
                RunnerState(alias = "k8s", podPhase = PodPhase.FAILED, endedAt = 9, startupFailure = failed),
                emitted(null, job = null) { it.startupFailed(ex) },
            )
            // A previous state survives, apart from the phase, the end time and the failure.
            val pending = RunnerState(alias = "k8s", podName = "old-pod", podPhase = PodPhase.PENDING, createdAt = 1, jobName = JOB)
            assertEquals(
                RunnerState(
                    alias = "k8s",
                    podName = "old-pod",
                    podPhase = PodPhase.FAILED,
                    createdAt = 1,
                    endedAt = 9,
                    startupFailure = failed,
                    jobName = JOB,
                ),
                emitted(pending) { it.startupFailed(ex) },
            )
        }

    @Test
    fun `terminated stamps the termination, FAILED and endedAt`() =
        runTest {
            val stop = PodTermination("DeadlineExceeded", "late")

            assertEquals(
                RunnerState(alias = "k8s", podName = "pod-1", podPhase = PodPhase.FAILED, createdAt = 1, endedAt = 9, termination = stop),
                emitted(null, job = null) { it.terminated(stop, "pod-1", 1) },
            )
            // In Job mode the pod name reached the state through podFound, before the pod was stopped.
            val jobFound = emitted(null) { it.podFound(FOUND_POD, 1) }
            assertEquals(
                RunnerState(
                    alias = "k8s",
                    podName = FOUND_POD,
                    podPhase = PodPhase.FAILED,
                    createdAt = 1,
                    endedAt = 9,
                    termination = stop,
                    jobName = JOB,
                ),
                emitted(jobFound) { it.terminated(stop, FOUND_POD, 1) },
            )
        }

    /** A previous state carrying every field a later phase must either keep or clear. */
    private fun carried(): RunnerState =
        RunnerState(
            alias = "k8s",
            podName = "old-pod",
            podPhase = PodPhase.READY,
            createdAt = 111,
            readyAt = 222,
            endedAt = 333,
            startupFailure = PodStartupFailure("PodStartTimeout", "slow", listOf("Warning/Failed: x")),
            termination = PodTermination("DeadlineExceeded", "late"),
            jobName = JOB,
        )

    /**
     * What [emit] wrote, through a sink holding [prev] (null when it holds none). [job] is the Job
     * name — set in Job mode, null in Pod mode (ADR-0100) — and [clock] fixes the emitted timestamps.
     */
    private suspend fun emitted(
        prev: RunnerState?,
        job: String? = JOB,
        clock: () -> Long = { 9_000L },
        emit: suspend (RunnerStateEmitter) -> Unit,
    ): RunnerState {
        var written: RunnerState? = null
        val states = RunnerStateEmitter("k8s", 7, job, clock, { _, update -> written = update(prev) })
        emit(states)
        return checkNotNull(written) { "the emitter wrote no state" }
    }

    private companion object {
        private const val JOB = "homer-7-abc"
        private const val FOUND_POD = "homer-7-abc-x"
    }
}
