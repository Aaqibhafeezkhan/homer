package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import homer.app.models.PodPhase
import homer.app.models.PodStartupFailure
import homer.app.models.PodTermination
import homer.app.models.RunnerState
import org.slf4j.LoggerFactory

/**
 * One session's runner-state transitions, written to [sink] for [processId]. Each write rebuilds the
 * state when the sink holds none, keeping [jobName] (null in Pod mode). Best-effort: a failed write is
 * logged and dropped.
 */
internal class RunnerStateEmitter(
    private val alias: String,
    private val processId: Int,
    private val jobName: String?,
    private val clock: () -> Long,
    private val sink: RunnerStateSink,
) {
    suspend fun creating(
        podName: String?,
        createdAtSec: Long,
    ) = emit { RunnerState(alias = alias, podName = podName, podPhase = PodPhase.CREATING, createdAt = createdAtSec, jobName = jobName) }

    suspend fun pending(
        podName: String?,
        createdAtSec: Long,
    ) = emit { prev -> (prev ?: base(podName, createdAtSec)).copy(podPhase = PodPhase.PENDING) }

    /** The Job controller created the pod [podName]. */
    suspend fun podFound(
        podName: String,
        createdAtSec: Long,
    ) = emit { prev -> (prev ?: base(podName, createdAtSec)).copy(podName = podName) }

    suspend fun ready(
        podName: String,
        createdAtSec: Long,
    ) = emit { prev ->
        val readyAtSec = clock() / 1000
        (prev ?: base(podName, createdAtSec)).copy(
            podPhase = PodPhase.READY,
            readyAt = readyAtSec,
        )
    }

    suspend fun startupFailed(ex: PodStartupException) {
        val endedAtSec = clock() / 1000
        emit { prev ->
            (prev ?: RunnerState(alias = alias, podPhase = PodPhase.FAILED, jobName = jobName)).copy(
                podPhase = PodPhase.FAILED,
                endedAt = endedAtSec,
                startupFailure =
                    PodStartupFailure(
                        reason = ex.reason,
                        message = ex.rawMessage,
                        events = ex.events,
                    ),
            )
        }
    }

    /** Kubernetes stopped the pod [podName] mid-run. */
    suspend fun terminated(
        termination: PodTermination,
        podName: String,
        createdAtSec: Long,
    ) = emit { prev ->
        (prev ?: base(podName, createdAtSec)).copy(
            termination = termination,
            podPhase = PodPhase.FAILED,
            endedAt = clock() / 1000,
        )
    }

    private fun base(
        podName: String?,
        createdAtSec: Long,
    ) = RunnerState(
        alias = alias,
        podName = podName,
        podPhase = PodPhase.PENDING,
        createdAt = createdAtSec,
        jobName = jobName,
    )

    @Suppress("SuspendFunSwallowedCancellation") // best-effort status write; a kill surfaces at the pod wait's delay
    private suspend fun emit(update: (RunnerState?) -> RunnerState) {
        runCatching { sink.update(processId, update) }
            .onFailure { log.debug("Failed to emit runner state for pid={}: {}", processId, it.message) }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(KubernetesRunner::class.java)
    }
}
