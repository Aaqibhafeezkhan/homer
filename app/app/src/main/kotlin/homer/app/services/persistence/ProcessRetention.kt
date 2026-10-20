package homer.app.services.persistence

import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.io.path.Path

// How a process row and its workspace leave: the purge times a finished run is stamped with, the
// two purge sweeps' due-row reads and markers, the shared-workspace guard every delete checks
// first, the record purge, and the capacity eviction inside obtainProcessId's INSERT transaction.
// See ADR-0048 and ADR-0053.

suspend fun ProcessRepository.setPurgeAt(
    processId: Int,
    purgeAt: Long,
    recordPurgeAt: Long = purgeAt,
    retainArtifacts: List<String> = emptyList(),
) {
    db.query {
        Processes.update({ Processes.id eq processId }) {
            it[Processes.purgeAt] = purgeAt
            it[Processes.recordPurgeAt] = recordPurgeAt
            // Null when nothing is declared retained, so a row with an empty list
            // reads back identically to one that predates the column. See ADR-0053.
            it[Processes.retainArtifacts] =
                retainArtifacts.takeIf { it.isNotEmpty() }?.let { encodeRetainArtifacts(it) }
        }
    }
}

/**
 * Terminal rows whose *directory* is due for deletion and still have one recorded.
 * `artifacts_dir IS NOT NULL` is the "not yet swept" marker: the sweep nulls it after
 * deleting, so a row kept for its record does not come back round every minute.
 *
 * `workspace_purged_at IS NULL` excludes rows the sweep already handled but which
 * kept `artifacts_dir` set because files were retained — see [markWorkspacePurged].
 * See ADR-0053.
 */
suspend fun ProcessRepository.getWorkspacePurgeable(
    statuses: Set<ProcessStatus>,
    nowSec: Long,
): List<ProcessInfo> =
    purgeable(statuses) {
        (Processes.purgeAt lessEq nowSec) and
            Processes.artifactsDir.isNotNull() and
            Processes.workspacePurgedAt.isNull()
    }

/**
 * Terminal rows whose *record* is due for deletion. A null `record_purge_at` (rows
 * written before v12) falls back to `purge_at`, i.e. the pre-split behaviour.
 *
 * The marker for "the workspace sweep already dealt with this row" is
 * `artifacts_dir IS NULL` **or** `workspace_purged_at IS NOT NULL` — load-bearing,
 * not an optimisation: deleting the record first would orphan a directory whose
 * delete is still failing (nothing-retained case) or whose retained files are meant
 * to keep resolving through the artifact routes (retained case, ADR-0053), with
 * nothing left pointing at either.
 */
suspend fun ProcessRepository.getRecordPurgeable(
    statuses: Set<ProcessStatus>,
    nowSec: Long,
): List<ProcessInfo> =
    purgeable(statuses) {
        (Processes.artifactsDir.isNull() or Processes.workspacePurgedAt.isNotNull()) and
            (
                (Processes.recordPurgeAt.isNotNull() and (Processes.recordPurgeAt lessEq nowSec)) or
                    (Processes.recordPurgeAt.isNull() and (Processes.purgeAt lessEq nowSec))
            )
    }

/**
 * Rows in [statuses] with a purge time set and [due], read through the inputs-free
 * [Processes.recordReadColumns] projection both purge sweeps share.
 */
private suspend fun ProcessRepository.purgeable(
    statuses: Set<ProcessStatus>,
    due: () -> Op<Boolean>,
): List<ProcessInfo> =
    db.read {
        Processes
            .select(Processes.recordReadColumns)
            .where { (Processes.status inList statuses.map { it.name }) and Processes.purgeAt.isNotNull() and due() }
            .map { it.toProcessInfo(jobRegistry, emptyList(), emptyMap()) }
    }

/**
 * Clears the recorded artifacts dir after its workspace has been swept.
 * [retainedTruncated] records a prune that overflowed `maxRetainedArtifactBytes`
 * on every matched file (kept nothing, so this path runs instead of
 * [markWorkspacePurged]) — never silent per ADR-0053. `null` (the default)
 * leaves the column untouched, e.g. the no-retention and nothing-matched paths.
 */
suspend fun ProcessRepository.clearArtifactsDir(
    processId: Int,
    retainedTruncated: Boolean? = null,
) {
    db.query {
        Processes.update({ Processes.id eq processId }) {
            it[artifactsDir] = null
            if (retainedTruncated != null) {
                it[Processes.retainedTruncated] = retainedTruncated
            }
        }
    }
}

/**
 * Marks a row's workspace sweep as done while keeping artifacts_dir set —
 * used when retained files stay in place, so the artifact routes keep
 * resolving them. See ADR-0053.
 */
suspend fun ProcessRepository.markWorkspacePurged(
    processId: Int,
    retainedTruncated: Boolean,
) {
    db.query {
        Processes.update({ Processes.id eq processId }) {
            it[workspacePurgedAt] = System.currentTimeMillis() / 1000
            it[Processes.retainedTruncated] = retainedTruncated
        }
    }
}

/**
 * True when any row other than [excludeProcessId] references the same artifacts
 * directory — i.e. the workspace is shared via a fixed `workspace.path`. With
 * [activeOnly], only still-live (CREATED/WORKING) siblings count: terminal rows
 * are ignored so a finished run cannot block the end-of-run cleanup guard
 * (see ADR-0048, 2026-10-06 amendment). One SQL shape for both sweeps.
 */
suspend fun ProcessRepository.isArtifactsDirShared(
    artifactsDir: String,
    excludeProcessId: Int,
    activeOnly: Boolean = false,
): Boolean =
    db.read {
        Processes
            .select(Processes.id)
            .where {
                val shared = (Processes.artifactsDir eq artifactsDir) and (Processes.id neq excludeProcessId)
                if (activeOnly) shared and (Processes.status inList ACTIVE_STATUS_NAMES) else shared
            }.limit(1)
            .any()
    }

/** The cleanup guard's liveness predicate — [isArtifactsDirShared] restricted to live siblings. */
suspend fun ProcessRepository.isArtifactsDirSharedWithActiveRun(
    artifactsDir: String,
    excludeProcessId: Int,
): Boolean = isArtifactsDirShared(artifactsDir, excludeProcessId, activeOnly = true)

suspend fun ProcessRepository.remove(processId: Int) {
    db.query { deleteProcessRowsTx(processId) }
}

/**
 * At capacity, deletes the oldest terminal row to make room and returns it; below capacity
 * returns null. Runs inside [ProcessRepository.obtainProcessId]'s INSERT transaction, so the
 * count, the eviction and the INSERT are one atomic step. Throws
 * [ProcessManagerAtCapacityException] when the table is full of live rows.
 */
internal fun evictForCapacityTx(maxProcesses: Int): ProcessRepository.EvictedProcess? {
    val count = Processes.selectAll().count()
    if (count < maxProcesses.toLong()) return null
    // Only the columns read below: this runs under the INSERT's write lock on every
    // start at capacity, and the row's history/executions/runner_state blobs are dead weight.
    val victim =
        Processes
            .select(Processes.id, Processes.agentName, Processes.status, Processes.artifactsDir)
            .where { Processes.status inList TERMINAL_STATUS_NAMES }
            .orderBy(Processes.id to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?: throw ProcessManagerAtCapacityException(maxProcesses)
    val victimId = victim[Processes.id]
    val evicted =
        ProcessRepository.EvictedProcess(
            victimId,
            victim[Processes.agentName],
            ProcessStatus.valueOf(victim[Processes.status]),
            victim[Processes.artifactsDir]?.let { Path(it) },
        )
    deleteProcessRowsTx(victimId)
    return evicted
}

// Co-transactional with the row delete, on both record purge and capacity eviction: an
// orphaned tag row discloses what a run ran over after its record is gone, and an orphaned
// input row keeps a blob with no retention path. See ADR-0048.
private fun deleteProcessRowsTx(processId: Int) {
    ProcessInputs.deleteWhere { ProcessInputs.processId eq processId }
    ProcessTags.deleteWhere { ProcessTags.processId eq processId }
    Processes.deleteWhere { Processes.id eq processId }
}
