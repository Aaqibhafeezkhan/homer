package homer.app.services.persistence

import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.ProcessManager
import java.nio.file.Path

/**
 * The retention part of [ProcessManager]'s API: the purge times a finished run is stamped with,
 * the due-row reads and markers of the `WorkspacePurgeService` sweeps, and the shared-workspace
 * checks guarding every directory delete. [ProcessManager] exposes it by delegation to
 * [DefaultProcessRetentionRecords]. See ADR-0048 and ADR-0053.
 */
interface ProcessRetentionRecords {
    suspend fun setPurgeAt(
        processId: Int,
        purgeAt: Long,
        recordPurgeAt: Long = purgeAt,
        retainArtifacts: List<String> = emptyList(),
    )

    /** Marks [processId]'s workspace sweep done with its retained files kept: [ProcessRepository.markWorkspacePurged]. */
    suspend fun markWorkspacePurged(
        processId: Int,
        retainedTruncated: Boolean,
    )

    /** Processes whose workspace directory is due for deletion and still recorded. Filtered in SQL. */
    suspend fun getWorkspacePurgeableProcesses(
        statuses: Set<ProcessStatus>,
        nowSec: Long,
    ): List<ProcessInfo>

    /** Processes whose record is due for deletion (`recordPurgeAt`, defaulting to `purgeAt`). */
    suspend fun getRecordPurgeableProcesses(
        statuses: Set<ProcessStatus>,
        nowSec: Long,
    ): List<ProcessInfo>

    /**
     * Clears the recorded artifacts dir once its workspace has been swept.
     * [retainedTruncated] records a prune that truncated to nothing — see
     * [ProcessRepository.clearArtifactsDir].
     */
    suspend fun clearArtifactsDir(
        processId: Int,
        retainedTruncated: Boolean? = null,
    )

    /** True when another process row still references [artifactsDir] (shared fixed-path workspace). */
    suspend fun isArtifactsDirShared(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean

    /**
     * True when a still-live run other than [excludeProcessId] references [artifactsDir]
     * — the guard `Workspace.cleanup` uses. See ADR-0048 (2026-10-06 amendment).
     */
    suspend fun isWorkspaceSharedWithActiveRun(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean
}

/** [ProcessRetentionRecords] straight from [repository]. */
internal class DefaultProcessRetentionRecords(
    private val repository: ProcessRepository,
) : ProcessRetentionRecords {
    override suspend fun setPurgeAt(
        processId: Int,
        purgeAt: Long,
        recordPurgeAt: Long,
        retainArtifacts: List<String>,
    ) {
        repository.setPurgeAt(processId, purgeAt, recordPurgeAt, retainArtifacts)
    }

    override suspend fun markWorkspacePurged(
        processId: Int,
        retainedTruncated: Boolean,
    ) {
        repository.markWorkspacePurged(processId, retainedTruncated)
    }

    override suspend fun getWorkspacePurgeableProcesses(
        statuses: Set<ProcessStatus>,
        nowSec: Long,
    ): List<ProcessInfo> = repository.getWorkspacePurgeable(statuses, nowSec)

    override suspend fun getRecordPurgeableProcesses(
        statuses: Set<ProcessStatus>,
        nowSec: Long,
    ): List<ProcessInfo> = repository.getRecordPurgeable(statuses, nowSec)

    override suspend fun clearArtifactsDir(
        processId: Int,
        retainedTruncated: Boolean?,
    ) {
        repository.clearArtifactsDir(processId, retainedTruncated)
    }

    override suspend fun isArtifactsDirShared(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean = repository.isArtifactsDirShared(artifactsDir.toString(), excludeProcessId)

    override suspend fun isWorkspaceSharedWithActiveRun(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean = repository.isArtifactsDirSharedWithActiveRun(artifactsDir.toString(), excludeProcessId)
}
