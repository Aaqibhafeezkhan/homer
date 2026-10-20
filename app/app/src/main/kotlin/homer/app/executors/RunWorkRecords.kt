package homer.app.executors

import java.nio.file.Path

/**
 * What [Process] records on its run around [AgentExecutor]: the guarded `WORKING` write of "Status
 * precedence rules" (app/CLAUDE.md), the artifacts dir, the shared-workspace probe of the cleanup guard,
 * and the step records it hands to [AgentExecutor]. Implemented by `ProcessManager`. See ADR-0083.
 */
interface RunWorkRecords :
    StepRecords,
    GuardedStatusWrite {
    suspend fun setArtifactsDir(
        processId: Int,
        artifactsDir: Path,
    )

    /** True when a still-live run other than [excludeProcessId] references [artifactsDir]. */
    suspend fun isWorkspaceSharedWithActiveRun(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean
}
