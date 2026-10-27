package homer.app.services.persistence

import homer.app.models.CostSource
import homer.app.models.RunnerState
import homer.app.services.ProcessManager
import java.nio.file.Path

/**
 * The run-time annotations of [ProcessManager]'s API: what a running process records on its own
 * row that no subscriber is told about — trace id, runner state, cost, artifacts dir and run-time
 * tags. [ProcessManager] exposes them by delegation to [DefaultProcessRunRecords]; the writes that
 * emit an event (status, current command) stay on [ProcessManager].
 */
interface ProcessRunRecords {
    /** Stamps this run's root-span trace id, so the console can deep-link to its Langfuse trace. */
    suspend fun setTraceId(
        processId: Int,
        traceId: String,
    )

    suspend fun setRunnerState(
        processId: Int,
        update: (RunnerState?) -> RunnerState,
    )

    suspend fun addCost(
        processId: Int,
        delta: Double,
        source: CostSource,
    )

    suspend fun setArtifactsDir(
        processId: Int,
        artifactsDir: Path,
    )

    /**
     * Stores why the run ended FAILED before any of its commands started, and the run that
     * blocked it when the cause names one; a guarded write that lands only on a row still
     * `FAILED` with no step recorded. See ADR-0020.
     */
    suspend fun setFailureReason(
        processId: Int,
        reason: String,
        holderRunId: Int?,
    ): Boolean

    /**
     * Writes a run-time tag against [processId] via the callback route, enforcing the
     * per-run ceiling [maxTags] atomically with the write. [fromInputsKeys] are the
     * agent's `fromInputs` tag keys, excluded from the ceiling so only runtime-declared
     * tags count against it. See ADR-0060.
     */
    suspend fun addRuntimeTag(
        processId: Int,
        tag: String,
        maxTags: Int,
        fromInputsKeys: Set<String>,
    ): ProcessRepository.RuntimeTagResult
}

/** [ProcessRunRecords] straight onto [repository]; a zero cost delta writes nothing. */
internal class DefaultProcessRunRecords(
    private val repository: ProcessRepository,
) : ProcessRunRecords {
    override suspend fun setTraceId(
        processId: Int,
        traceId: String,
    ) {
        repository.setTraceId(processId, traceId)
    }

    override suspend fun setRunnerState(
        processId: Int,
        update: (RunnerState?) -> RunnerState,
    ) {
        repository.setRunnerState(processId, update)
    }

    override suspend fun addCost(
        processId: Int,
        delta: Double,
        source: CostSource,
    ) {
        if (delta == 0.0) return
        repository.addCost(processId, delta, source)
    }

    override suspend fun setArtifactsDir(
        processId: Int,
        artifactsDir: Path,
    ) {
        repository.setArtifactsDir(processId, artifactsDir.toString())
    }

    override suspend fun setFailureReason(
        processId: Int,
        reason: String,
        holderRunId: Int?,
    ): Boolean = repository.setFailureReason(processId, reason, holderRunId)

    override suspend fun addRuntimeTag(
        processId: Int,
        tag: String,
        maxTags: Int,
        fromInputsKeys: Set<String>,
    ): ProcessRepository.RuntimeTagResult = repository.insertRuntimeTag(processId, tag, maxTags, fromInputsKeys)
}
