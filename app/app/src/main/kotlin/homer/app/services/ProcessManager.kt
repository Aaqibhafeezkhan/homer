package homer.app.services

import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.executors.CommandArtifactPaths
import homer.app.executors.RunKiller
import homer.app.executors.RunRecords
import homer.app.executors.RunWorkRecords
import homer.app.infra.PathSafety
import homer.app.infra.ProcessJobRegistry
import homer.app.models.Cooldown
import homer.app.models.ExecutionInfo
import homer.app.models.LlmAccounting
import homer.app.models.PathAsStringSerializer
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.runners.kubernetes.ActiveRunIds
import homer.app.services.persistence.DefaultProcessRecordReads
import homer.app.services.persistence.DefaultProcessRetentionRecords
import homer.app.services.persistence.DefaultProcessRunRecords
import homer.app.services.persistence.ProcessRecordReads
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.ProcessRetentionRecords
import homer.app.services.persistence.ProcessRunRecords
import homer.app.services.persistence.SettlementSwap
import homer.app.services.persistence.completeStep
import homer.app.services.persistence.remove
import homer.app.services.persistence.setCurrentCommand
import homer.app.services.persistence.settleAccounting
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Process lifecycle FSM (`CREATED → WORKING → FINISHED/FAILED/KILLED`, plus
 * `UNKNOWN` for runs interrupted by a restart). Durable state lives in SQLite
 * via [ProcessRepository] — SQLite is authoritative, there is no in-memory
 * cache. The only runtime-only piece is the live coroutine [Job], held in
 * [ProcessJobRegistry] and rebuilt empty on boot. See ADR-0020.
 *
 * The read-modify-write atomicity the old `Mutex` provided is now enforced by
 * the repository's `BEGIN IMMEDIATE` transactions (see `DatabaseService`).
 *
 * This class owns the transitions and the events they emit. The record reads, the retention
 * bookkeeping and the event-free run annotations are the repository's as stored, exposed by
 * delegation: [ProcessRecordReads], [ProcessRetentionRecords], [ProcessRunRecords].
 * [ActiveRunIds] is the Kubernetes startup sweep's port onto those reads (ADR-0083).
 */
class ProcessManager(
    private val repository: ProcessRepository,
    private val jobRegistry: ProcessJobRegistry,
    private val logger: Logger,
    private val broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster(),
    private val maxProcesses: Int = DEFAULT_MAX_PROCESSES,
    // Configured workspace root (`workspaceRootPath`). Eviction's workspace delete
    // is asserted to stay inside it before running, so a poisoned persisted path
    // cannot steer the recursive delete outside the workspace tree.
    private val workspaceRoot: Path = Path.of("."),
    // Capacity eviction skips the WorkspacePurgeService path, so per-process
    // state held elsewhere (question registrations) must be released here.
    private val onProcessEvicted: suspend (Int) -> Unit = {},
) : ProcessRecordReads by DefaultProcessRecordReads(repository),
    ProcessRetentionRecords by DefaultProcessRetentionRecords(repository),
    ProcessRunRecords by DefaultProcessRunRecords(repository),
    RunRecords,
    RunWorkRecords,
    RunKiller,
    ActiveRunIds {
    @OptIn(ExperimentalTime::class)
    val startTime: String =
        Clock.System
            .now()
            .epochSeconds
            .toString()

    // In-memory only, keyed by (processId, cmdIndex) — the actual artifact paths an
    // executor wrote to, for a per-attempt `artifactNameTemplate` (see
    // ArtifactNameResolver / AGENT.md). Deliberately NOT part of the serializable
    // ExecutionInfo/ProcessInfo API: it exists purely to let LogTailService's live
    // console tail follow a command's real file, and it is not durable — a restart
    // loses it exactly like ProcessJobRegistry, which is fine since there is nothing
    // left running to tail. Entries live for the process's lifetime (a finished
    // command's post-hoc tail must still resolve), so they are cleared only in
    // removeProcess, not on a command or process reaching a terminal status.
    private val commandArtifacts = ConcurrentHashMap<Int, ConcurrentHashMap<Int, CommandArtifactPaths>>()

    // Settled accountings whose step is not recorded yet, keyed by the pending accounting the
    // step will carry (ADR-0089). No mutex: both sides are single transactions plus one atomic
    // map step, so the write lock is never held across a `query { }` (app/CLAUDE.md SQLite rule
    // 3); a settlement landing between a step's map read and its row write can buffer unapplied
    // — accepted and recorded in ADR-0089. Cleared with the process.
    private val unappliedSettlements = ConcurrentHashMap<Int, MutableMap<LlmAccounting, LlmAccounting>>()

    override suspend fun setCommandArtifacts(
        processId: Int,
        cmdIndex: Int,
        paths: CommandArtifactPaths,
    ) {
        commandArtifacts.computeIfAbsent(processId) { ConcurrentHashMap() }[cmdIndex] = paths
    }

    override suspend fun getCommandArtifacts(
        processId: Int,
        cmdIndex: Int,
    ): CommandArtifactPaths? = commandArtifacts[processId]?.get(cmdIndex)

    /**
     * The tracked entry with the highest cmdIndex for [processId] — commands run in
     * increasing index order, so the highest registered index is the one currently
     * running (including a debug run restricted to a single, possibly non-zero,
     * `commandIndices` entry, which records nothing for the indices it skips). Null
     * when nothing is tracked for this process.
     */
    fun latestCommandArtifacts(processId: Int): Pair<Int, CommandArtifactPaths>? =
        commandArtifacts[processId]?.maxByOrNull { it.key }?.toPair()

    @OptIn(ExperimentalTime::class)
    override suspend fun killProcess(
        processId: Int,
        reason: String,
    ) {
        val job = jobRegistry.get(processId)
        val killedAgent = repository.killTransition(processId, Clock.System.now().epochSeconds)
        if (killedAgent != null) {
            logger.info("Killing process: {}, reason: {}", processId, reason)
            job?.cancel(CancellationException(reason.ifEmpty { "Killed !" }))
            broadcaster.emit(ProcessEvent.StatusChanged(processId, ProcessStatus.KILLED, killedAgent))
            broadcaster.emit(ProcessEvent.CurrentCommandChanged(processId, null, null))
        }
    }

    // The facade forwards ProcessRepository.obtainProcessId's optional parameters one for one.
    // jscpd:ignore-start
    override suspend fun obtainProcessId(
        agentName: String,
        inputParams: Map<String, String>,
        owner: String?,
        cooldown: Cooldown?,
        secretInputKeys: Set<String>?,
        parentProcessId: Int?,
        rootProcessId: Int?,
        tags: List<String>,
    ): Int {
        // jscpd:ignore-end
        val result =
            try {
                repository.obtainProcessId(
                    agentName,
                    inputParams,
                    maxProcesses,
                    owner,
                    cooldown,
                    secretInputKeys,
                    parentProcessId,
                    rootProcessId,
                    tags,
                )
            } catch (e: ProcessManagerAtCapacityException) {
                logger.warn(
                    "ProcessManager at capacity ({}) and no terminal processes to evict; refusing new process",
                    maxProcesses,
                )
                throw e
            }
        result.evicted?.let { evicted ->
            logger.info(
                "ProcessManager capacity reached; evicted terminal process {} (agent: {}, status: {})",
                evicted.id,
                evicted.agentName,
                evicted.status,
            )
            deleteEvictedWorkspace(evicted)
            // Capacity eviction deletes the row directly in the repository, bypassing
            // removeProcess — without this, an evicted process's tracked artifact paths
            // would leak in memory for the rest of the server's uptime (root CLAUDE.md
            // §12 rule 4).
            commandArtifacts.remove(evicted.id)
            unappliedSettlements.remove(evicted.id)
            onProcessEvicted(evicted.id)
        }
        return result.processId
    }

    /**
     * Deletes an evicted run's workspace directory. Capacity eviction bypasses
     * [WorkspacePurgeService], so without this the process row is dropped while its
     * on-disk workspace orphans and accumulates until the disk fills. Best-effort:
     * the row is already gone, so a failed delete cannot be retried — it is logged,
     * not thrown. Skipped when another process still references the directory (shared
     * fixed-path workspaces), mirroring the purge sweep's guard.
     */
    private suspend fun deleteEvictedWorkspace(evicted: ProcessRepository.EvictedProcess) {
        val artifactsDir = evicted.artifactsDir ?: return
        if (isArtifactsDirShared(artifactsDir, evicted.id)) {
            logger.info(
                "Skipping workspace deletion for evicted process {}: {} is still referenced by another process",
                evicted.id,
                artifactsDir.parent,
            )
            return
        }
        val workspaceDir = artifactsDir.parent ?: return
        // Assert containment before deleting: artifactsDir is read back from the DB,
        // not the freshly-validated workspace config, so a poisoned/crafted path must
        // not be able to steer this recursive delete outside the workspace root.
        runCatching { PathSafety.deleteContainedRecursively(workspaceRoot, workspaceDir) }
            .onFailure { logger.warn("Failed to delete workspace for evicted process {}: {}", evicted.id, it.message) }
    }

    suspend fun setProcessStatus(
        processId: Int,
        status: ProcessStatus,
    ) {
        transitionStatus(processId, status, guard = null)
    }

    override suspend fun compareAndSetStatus(
        processId: Int,
        expected: Set<ProcessStatus>,
        new: ProcessStatus,
    ): Boolean =
        // Guards terminal transitions against the race documented in audit #9:
        // a concurrent killProcess() can move the record to KILLED between the
        // moment ProcessExecutor finishes process.execute() and the moment it
        // writes FINISHED. Returning false here preserves KILLED.
        transitionStatus(processId, new, guard = expected)

    @OptIn(ExperimentalTime::class)
    private suspend fun transitionStatus(
        processId: Int,
        newStatus: ProcessStatus,
        guard: Set<ProcessStatus>?,
    ): Boolean {
        val result = repository.transitionStatus(processId, newStatus, Clock.System.now().epochSeconds, guard)
        if (result.updated) {
            result.agentName?.let { broadcaster.emit(ProcessEvent.StatusChanged(processId, newStatus, it)) }
        }
        return result.updated
    }

    override suspend fun assignJob(
        processId: Int,
        job: Job,
    ) {
        jobRegistry.put(processId, job)
    }

    override suspend fun removeJob(processId: Int) {
        jobRegistry.remove(processId)
    }

    /** Whether this instance runs [processId] right now: an in-memory check, no database read. */
    fun hasLiveJob(processId: Int): Boolean = jobRegistry.get(processId)?.isActive == true

    suspend fun removeProcess(processId: Int) {
        repository.remove(processId)
        jobRegistry.remove(processId)
        commandArtifacts.remove(processId)
        unappliedSettlements.remove(processId)
    }

    /**
     * Commits a step boundary — appends [executionInfo] and sets [nextLabel] as the
     * current command (or clears it when null) — in ONE repository write transaction,
     * emitting at most one [ProcessEvent.CurrentCommandChanged] for the boundary. When
     * the row is gone (concurrent kill/eviction) nothing is written and no event is
     * emitted; when it exists but has already reached a terminal status the execution is
     * still recorded but the current command is left cleared, also with no event, so a
     * boundary landing after a concurrent kill cannot resurrect a running command.
     * See ADR-0020 and the SQLite rules in `app/CLAUDE.md`
     * (one logical state change is one write transaction).
     */
    override suspend fun completeStep(
        processId: Int,
        executionInfo: ExecutionInfo,
        nextLabel: String?,
        nextStart: Long?,
    ) {
        val settled = executionInfo.llmAccounting?.let { unappliedSettlements[processId]?.remove(it) }
        val recorded =
            repository.completeStep(
                processId,
                settled?.let { executionInfo.copy(llmAccounting = it) } ?: executionInfo,
                nextLabel,
                nextStart,
            )
        if (recorded) broadcaster.emit(ProcessEvent.CurrentCommandChanged(processId, nextLabel, nextStart))
    }

    override suspend fun settleAccounting(
        processId: Int,
        pending: LlmAccounting,
        settled: LlmAccounting,
    ) {
        // A row that is gone is not buffered: removeProcess/eviction already ran the only
        // unappliedSettlements removal that would ever clear it, so buffering there would leak
        // the entry for the server's uptime (ADR-0089).
        when (repository.settleAccounting(processId, pending, settled)) {
            is SettlementSwap.Settled -> Unit
            is SettlementSwap.RowGone -> Unit
            is SettlementSwap.PendingNotFound -> bufferSettlement(processId, pending, settled)
        }
    }

    /** Keeps a settlement whose step is not recorded yet; merged when the step is recorded (ADR-0089). */
    private fun bufferSettlement(
        processId: Int,
        pending: LlmAccounting,
        settled: LlmAccounting,
    ) {
        unappliedSettlements.computeIfAbsent(processId) { ConcurrentHashMap() }[pending] = settled
    }

    /**
     * Sets a step's current command at step start and emits [ProcessEvent.CurrentCommandChanged].
     * The write is guarded on the row still being live: a kill landing between the run's
     * WORKING transition and this write leaves the command cleared and emits no event, the
     * same invariant [completeStep] enforces — a terminal run must never be handed back a
     * running command.
     */
    override suspend fun setCurrentCommand(
        processId: Int,
        label: String?,
        startTime: Long?,
    ) {
        if (repository.setCurrentCommand(processId, label, startTime)) {
            broadcaster.emit(ProcessEvent.CurrentCommandChanged(processId, label, startTime))
        }
    }

    data class ProcessListResult(
        val processes: List<ProcessInfo>,
        val total: Int,
        // Open, non-dispatch-bound question count per listed id; an absent id has none.
        val openQuestions: Map<Int, Int> = emptyMap(),
        // The listed rows' root ids the caller may see (ADR-0058). Empty unless the read
        // was given a canSeeRoot check, so a hidden root is the default.
        val visibleRootIds: Set<Int> = emptySet(),
    )

    /**
     * Totals per tag value, plus whether the candidate row set was cut short by the
     * repository's row bound (see [ProcessRepository.costByTag]). See ADR-0062.
     */
    data class CostByTagResult(
        val valuesUsd: Map<String, Double>,
        val truncated: Boolean,
    )

    companion object {
        const val DEFAULT_MAX_PROCESSES = 10_000
    }
}
