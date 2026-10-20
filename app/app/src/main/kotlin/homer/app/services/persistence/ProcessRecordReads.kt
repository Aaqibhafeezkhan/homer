package homer.app.services.persistence

import homer.app.models.ExecutionInfo
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.RunnerState
import homer.app.models.SubRunProgress
import homer.app.services.ProcessManager
import homer.app.services.ProcessManager.CostByTagResult
import homer.app.services.ProcessManager.ProcessListResult

/**
 * The reads of [ProcessManager]'s API: the durable process records as stored, with no state
 * transition and no event. [ProcessManager] exposes them by delegation to
 * [DefaultProcessRecordReads]. See ADR-0020.
 */
interface ProcessRecordReads {
    /** Highest process id ever allocated (monotonic AUTOINCREMENT high-water), or -1 if none. */
    suspend fun lastProcessId(): Int

    suspend fun getProcessStatus(processId: Int): ProcessStatus

    /** (id, agent name) of CREATED runs of the given agents, for `/agents/reload` to kill. */
    suspend fun createdRunsOf(agentNames: Collection<String>): List<Pair<Int, String>>

    suspend fun activeProcessIds(): Set<Int>

    suspend fun getRunnerState(processId: Int): RunnerState?

    suspend fun getProcessHistory(processId: Int): List<Pair<Long, ProcessStatus>>

    suspend fun getProcessExecutions(processId: Int): List<ExecutionInfo>

    suspend fun getProcessInfo(processId: Int): ProcessInfo?

    /** [SubRunProgress] of each of [processIds] that still exists, in one batched read. */
    suspend fun getSubRunProgress(processIds: Collection<Int>): Map<Int, SubRunProgress>

    suspend fun getAllProcesses(
        filterStatuses: Set<ProcessStatus>? = null,
        orderAsc: Boolean = false,
        offset: Int = 0,
        limit: Int = 100,
        agentName: String? = null,
        visibleOwner: String? = null,
        visibleAgents: Set<String>? = null,
        parentProcessId: Int? = null,
        rootProcessId: Int? = null,
        rootsOnly: Boolean = false,
        tags: List<String> = emptyList(),
        // Required (no-null-escape) whenever [tags] is non-empty; see ProcessRepository.getAll.
        screenTags: ((ProcessInfo) -> List<String>)? = null,
        // See ProcessRepository.getAll.
        canSeeRoot: ((owner: String?, agentName: String) -> Boolean)? = null,
    ): ProcessListResult

    /** See [ProcessRepository.costByTag]. */
    suspend fun costByTag(
        key: String,
        screenTags: (ProcessInfo) -> List<String>,
    ): CostByTagResult
}

/** [ProcessRecordReads] straight from [repository]. */
internal class DefaultProcessRecordReads(
    private val repository: ProcessRepository,
) : ProcessRecordReads {
    override suspend fun lastProcessId(): Int = repository.lastProcessId()

    override suspend fun getProcessStatus(processId: Int): ProcessStatus = repository.getStatus(processId)

    override suspend fun createdRunsOf(agentNames: Collection<String>): List<Pair<Int, String>> = repository.createdRunsOf(agentNames)

    override suspend fun activeProcessIds(): Set<Int> = repository.activeProcessIds()

    override suspend fun getRunnerState(processId: Int): RunnerState? = repository.getRunnerState(processId)

    override suspend fun getProcessHistory(processId: Int): List<Pair<Long, ProcessStatus>> = repository.getHistory(processId)

    override suspend fun getProcessExecutions(processId: Int): List<ExecutionInfo> = repository.getExecutions(processId)

    override suspend fun getProcessInfo(processId: Int): ProcessInfo? = repository.getInfo(processId)

    override suspend fun getSubRunProgress(processIds: Collection<Int>): Map<Int, SubRunProgress> = repository.subRunProgress(processIds)

    override suspend fun getAllProcesses(
        filterStatuses: Set<ProcessStatus>?,
        orderAsc: Boolean,
        offset: Int,
        limit: Int,
        agentName: String?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        parentProcessId: Int?,
        rootProcessId: Int?,
        rootsOnly: Boolean,
        tags: List<String>,
        screenTags: ((ProcessInfo) -> List<String>)?,
        canSeeRoot: ((owner: String?, agentName: String) -> Boolean)?,
    ): ProcessListResult =
        repository.getAll(
            filterStatuses,
            orderAsc,
            offset,
            limit,
            agentName,
            visibleOwner,
            visibleAgents,
            parentProcessId,
            rootProcessId,
            rootsOnly,
            tags,
            screenTags,
            canSeeRoot,
        )

    override suspend fun costByTag(
        key: String,
        screenTags: (ProcessInfo) -> List<String>,
    ): CostByTagResult = repository.costByTag(key, screenTags)
}
