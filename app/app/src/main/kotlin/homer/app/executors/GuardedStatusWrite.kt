package homer.app.executors

import homer.app.models.ProcessStatus

/**
 * The guarded status write of "Status precedence rules" (app/CLAUDE.md) that [ProcessExecutor] and [Process]
 * both make, through [RunRecords] and [RunWorkRecords]. Implemented by `ProcessManager`. See ADR-0083.
 */
interface GuardedStatusWrite {
    /** Moves the run to [new] only while its status is in [expected]; false when another write landed first. */
    suspend fun compareAndSetStatus(
        processId: Int,
        expected: Set<ProcessStatus>,
        new: ProcessStatus,
    ): Boolean
}
