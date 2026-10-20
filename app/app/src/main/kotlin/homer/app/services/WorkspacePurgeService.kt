package homer.app.services

import homer.app.infra.ArtifactRetention
import homer.app.infra.PathSafety
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.nio.file.Path

class WorkspacePurgeService(
    private val processManager: ProcessManager,
    private val questionsService: QuestionsService,
    private val workspaceRoot: Path,
    private val maxRetainedArtifactBytes: Long,
    private val logger: Logger,
) {
    private val terminalStatuses =
        setOf(
            ProcessStatus.FINISHED,
            ProcessStatus.FAILED,
            ProcessStatus.KILLED,
            // Stamped at startup reconciliation, so a restart's casualties are swept too. ADR-0048.
            ProcessStatus.UNKNOWN,
        )

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job =
            scope.launch {
                while (isActive) {
                    delay(60_000L)
                    purgeExpired()
                }
            }
    }

    fun stop() {
        job?.cancel()
    }

    /**
     * Two sweeps, two lifetimes. `workspace.purgeAfter` governs the directory on
     * disk; `workspace.retain.recordFor` governs the process row. They used to be
     * one step, so a link to `/processes/{id}` — the URL people paste into tickets
     * and merge requests — started returning 404 five days later, taking the run's
     * status, steps, exit codes and cost with it. Those comments outlive any
     * sensible artifact retention. See ADR-0048.
     */
    suspend fun purgeExpired() {
        val now = System.currentTimeMillis() / 1000
        purgeWorkspaces(now)
        purgeRecords(now)
    }

    private suspend fun purgeWorkspaces(now: Long) {
        for (process in processManager.getWorkspacePurgeableProcesses(terminalStatuses, now)) {
            val artifactsDir = process.artifactsDir ?: continue
            try {
                purgeWorkspace(process, artifactsDir)
            } catch (e: CancellationException) {
                // stop() mid-sweep ends the sweep; it is no failure of this row.
                throw e
            } catch (e: Exception) {
                logger.error("Failed to purge workspace of process {}: {}", process.id, e.message, e)
            }
        }
    }

    /** Deletes, prunes or (when shared) leaves [process]'s workspace at [artifactsDir]. */
    private suspend fun purgeWorkspace(
        process: ProcessInfo,
        artifactsDir: Path,
    ) {
        var retainedCount = 0
        if (processManager.isArtifactsDirShared(artifactsDir, process.id)) {
            skipSharedWorkspace(process, artifactsDir)
        } else if (process.retainArtifacts.isNotEmpty()) {
            retainedCount = pruneToRetained(process, artifactsDir)
        } else {
            // Assert containment before deleting: artifactsDir is read back
            // from the DB, so a poisoned/crafted path must not steer this
            // recursive delete outside the configured workspace root.
            artifactsDir.parent?.let { PathSafety.deleteContainedRecursively(workspaceRoot, it) }
            // Clear only after a successful delete (or a deliberate skip): the column
            // doubles as the "already swept" marker, so a failed delete leaves it set
            // and the next sweep retries instead of orphaning the directory. Clearing
            // it also drops this row out of another process's shared-dir check, exactly
            // as removing the row used to.
            processManager.clearArtifactsDir(process.id)
        }
        if (retainedCount > 0) {
            logger.info(
                "Purged workspace of process {} (agent: {}), retained {} artifact file(s)",
                process.id,
                process.agentName,
                retainedCount,
            )
        } else {
            logger.info("Purged workspace of process {} (agent: {})", process.id, process.agentName)
        }
    }

    /** Leaves a workspace another process still references, only dropping this row's claim on it. */
    private suspend fun skipSharedWorkspace(
        process: ProcessInfo,
        artifactsDir: Path,
    ) {
        logger.info(
            "Skipping workspace deletion for process {} (agent: {}): {} is still referenced by another process",
            process.id,
            process.agentName,
            artifactsDir.parent,
        )
        if (process.retainArtifacts.isNotEmpty()) {
            // Retention needs a per-run directory: pruning a shared dir would
            // delete files other runs still serve. ADR-0053 documents this.
            logger.warn(
                "retain.artifacts skipped for process {} (agent: {}): workspace is shared",
                process.id,
                process.agentName,
            )
        }
        processManager.clearArtifactsDir(process.id)
    }

    /** Prunes the workspace down to the files `retain.artifacts` keeps; returns how many were kept. */
    private suspend fun pruneToRetained(
        process: ProcessInfo,
        artifactsDir: Path,
    ): Int {
        val result =
            ArtifactRetention.prune(workspaceRoot, artifactsDir, process.retainArtifacts, maxRetainedArtifactBytes)
        if (result.keptFiles == 0) {
            // Every matched file can still have overflowed the cap (kept nothing,
            // truncated = true) — record that so retainedTruncated isn't silently
            // dropped just because this path clears artifactsDir like the
            // nothing-matched case. See ADR-0053.
            processManager.clearArtifactsDir(process.id, retainedTruncated = result.truncated.takeIf { it })
            return 0
        }
        // artifacts_dir stays set — the artifact routes read it to serve the
        // retained files. workspace_purged_at takes over as the swept marker.
        processManager.markWorkspacePurged(process.id, result.truncated)
        return result.keptFiles
    }

    private suspend fun purgeRecords(now: Long) {
        for (process in processManager.getRecordPurgeableProcesses(terminalStatuses, now)) {
            try {
                // A retained row still owns its pruned directory; it dies with the record.
                // Delete before removing the row — a failed delete keeps the row so the
                // next sweep retries, same ordering invariant as the workspace sweep.
                process.artifactsDir?.let { artifactsDir ->
                    if (processManager.isArtifactsDirShared(artifactsDir, process.id)) {
                        logger.info(
                            "Skipping retained-artifact deletion for process {}: {} is still referenced by another process",
                            process.id,
                            artifactsDir.parent,
                        )
                    } else {
                        artifactsDir.parent?.let { PathSafety.deleteContainedRecursively(workspaceRoot, it) }
                    }
                }
                processManager.removeProcess(process.id)
                questionsService.removeForProcess(process.id)
                logger.info("Purged process record {} (agent: {})", process.id, process.agentName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to purge process record {}: {}", process.id, e.message, e)
            }
        }
    }
}
