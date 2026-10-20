package homer.app.executors

import homer.app.infra.SecretRedactor
import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.LlmAccounting
import homer.app.models.RunnerState
import java.nio.file.Path

/**
 * What [AgentExecutor] and the LLM harness executor record on their run while its steps execute:
 * the step boundaries, the runner state, the run's cost and the files each command attempt writes to.
 * Implemented by `ProcessManager`, which emits the events these writes imply. See ADR-0083.
 */
interface StepRecords {
    /** Sets a step's current command at step start, unless the run is already terminal. */
    suspend fun setCurrentCommand(
        processId: Int,
        label: String?,
        startTime: Long?,
    )

    /** Appends [executionInfo] and moves the current command to [nextLabel] in one write. */
    suspend fun completeStep(
        processId: Int,
        executionInfo: ExecutionInfo,
        nextLabel: String?,
        nextStart: Long?,
    )

    /**
     * Replaces the [pending] accounting a step was recorded with by its [settled] one, once the
     * session lookup answers (ADR-0089). A settlement landing before [completeStep] is kept and
     * applied when the step is recorded.
     */
    suspend fun settleAccounting(
        processId: Int,
        pending: LlmAccounting,
        settled: LlmAccounting,
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

    /** Records the actual stdout/stderr paths an executor wrote to for one command attempt. */
    suspend fun setCommandArtifacts(
        processId: Int,
        cmdIndex: Int,
        paths: CommandArtifactPaths,
    )

    /** The most recently recorded artifact paths for (processId, cmdIndex), or null if none tracked. */
    suspend fun getCommandArtifacts(
        processId: Int,
        cmdIndex: Int,
    ): CommandArtifactPaths?
}

/** The files one command attempt writes to, as [StepRecords.setCommandArtifacts] records them. In-memory only. */
data class CommandArtifactPaths(
    val stdOut: Path,
    val stdErr: Path,
    // The command's tag (`claude`, `script`, …); null for rows registered before this field existed.
    val kind: String? = null,
    val attemptIndex: Int = 0,
    // The run's redactor, so a per-subscriber stream can mask declared secrets. In-memory only.
    val redactor: SecretRedactor = SecretRedactor.NONE,
)
