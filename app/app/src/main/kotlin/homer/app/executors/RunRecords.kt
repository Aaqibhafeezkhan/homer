package homer.app.executors

import homer.app.models.Cooldown
import homer.app.models.ProcessStatus
import kotlinx.coroutines.Job

/**
 * What [ProcessExecutor] records on the run it starts: the row and its live job, the trace id, the
 * status writes of "Status precedence rules" (app/CLAUDE.md) and the purge times. Implemented by
 * `ProcessManager`, which owns the status FSM. See ADR-0083.
 */
interface RunRecords : GuardedStatusWrite {
    // Inserts the run's row (cooldown checked in the same transaction) and returns its id. The
    // port carries ProcessRepository.obtainProcessId's optional parameters one for one.
    // jscpd:ignore-start
    suspend fun obtainProcessId(
        agentName: String,
        inputParams: Map<String, String> = emptyMap(),
        owner: String? = null,
        cooldown: Cooldown? = null,
        secretInputKeys: Set<String>? = null,
        parentProcessId: Int? = null,
        rootProcessId: Int? = null,
        tags: List<String> = emptyList(),
    ): Int
    // jscpd:ignore-end

    suspend fun assignJob(
        processId: Int,
        job: Job,
    )

    suspend fun removeJob(processId: Int)

    suspend fun setTraceId(
        processId: Int,
        traceId: String,
    )

    suspend fun getProcessStatus(processId: Int): ProcessStatus

    suspend fun setPurgeAt(
        processId: Int,
        purgeAt: Long,
        recordPurgeAt: Long,
        retainArtifacts: List<String>,
    )
}
