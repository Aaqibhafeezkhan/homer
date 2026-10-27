package homer.app.services.persistence

import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.LlmAccounting
import homer.app.models.ProcessStatus
import homer.app.models.RunnerState
import homer.app.models.truncateFailureReason
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

// The bookkeeping a running process writes onto its own row between its status transitions:
// step boundaries and the current command, runner state, accumulated cost, the artifacts dir and
// the trace id. Each is one write transaction; see the step-boundary rules in
// `services/persistence/CLAUDE.md`.

private const val MIXED_SOURCE = "mixed"

/**
 * Appends one execution entry. Replay-unsafe (the read-modify-write appends
 * unconditionally); safe only because the writer handle is single-attempt.
 * See ADR-0020.
 */
suspend fun ProcessRepository.addExecution(
    processId: Int,
    executionInfo: ExecutionInfo,
) {
    db.query {
        val row =
            Processes
                .select(Processes.executions)
                .where { Processes.id eq processId }
                .singleOrNull() ?: return@query
        val updated = decodeExecutions(row[Processes.executions]) + executionInfo
        Processes.update({ Processes.id eq processId }) { it[executions] = encodeExecutions(updated) }
    }
}

/**
 * Appends [executionInfo] and sets or clears the current command in one
 * transaction. Returns false when the row is gone or terminal — the caller then
 * emits no event. See the step-boundary rules in `services/persistence/CLAUDE.md`.
 */
suspend fun ProcessRepository.completeStep(
    processId: Int,
    executionInfo: ExecutionInfo,
    nextLabel: String?,
    nextStart: Long?,
): Boolean =
    db.query {
        val row =
            Processes
                .select(Processes.executions, Processes.status)
                .where { Processes.id eq processId }
                .singleOrNull() ?: return@query false
        val updated = decodeExecutions(row[Processes.executions]) + executionInfo
        // A terminal row must not be handed a running command (see
        // `services/persistence/CLAUDE.md`, step boundary).
        val live = row[Processes.status] in ACTIVE_STATUS_NAMES
        Processes.update({ Processes.id eq processId }) {
            it[executions] = encodeExecutions(updated)
            it[currentCommand] = if (live) nextLabel else null
            it[currentCommandStart] = if (live) nextStart else null
        }
        live
    }

/** Whether [ProcessRepository.settleAccounting] swapped a step's pending accounting (ADR-0089). */
sealed interface SettlementSwap {
    /** The step's pending accounting was replaced by the settled one. */
    data object Settled : SettlementSwap

    /** The row is live but carries no execution with the pending accounting yet — the step is not recorded yet. */
    data object PendingNotFound : SettlementSwap

    /** The process row is gone (removed or evicted): there is nothing to merge the settlement into. */
    data object RowGone : SettlementSwap
}

/**
 * Swaps the first recorded execution carrying [pending] accounting to [settled]. Returns
 * [SettlementSwap.PendingNotFound] when the row is live but no execution carries [pending] yet,
 * and [SettlementSwap.RowGone] when it is gone (ADR-0089).
 */
suspend fun ProcessRepository.settleAccounting(
    processId: Int,
    pending: LlmAccounting,
    settled: LlmAccounting,
): SettlementSwap =
    db.query {
        val row =
            Processes
                .select(Processes.executions)
                .where { Processes.id eq processId }
                .singleOrNull() ?: return@query SettlementSwap.RowGone
        val executions = decodeExecutions(row[Processes.executions])
        val index = executions.indexOfFirst { it.llmAccounting == pending }
        if (index < 0) return@query SettlementSwap.PendingNotFound
        val updated = executions.toMutableList().also { it[index] = it[index].copy(llmAccounting = settled) }
        Processes.update({ Processes.id eq processId }) { it[Processes.executions] = encodeExecutions(updated) }
        SettlementSwap.Settled
    }

/**
 * Sets the current command for a step about to start. Returns true when the row
 * exists and is still live, so the caller can broadcast. Carries the same terminal-row
 * guard as [completeStep], in one transaction.
 */
suspend fun ProcessRepository.setCurrentCommand(
    processId: Int,
    label: String?,
    startTime: Long?,
): Boolean =
    db.query {
        val row =
            Processes
                .select(Processes.status)
                .where { Processes.id eq processId }
                .singleOrNull() ?: return@query false
        if (row[Processes.status] !in ACTIVE_STATUS_NAMES) return@query false
        Processes.update({ Processes.id eq processId }) {
            it[currentCommand] = label
            it[currentCommandStart] = startTime
        } > 0
    }

suspend fun ProcessRepository.setRunnerState(
    processId: Int,
    update: (RunnerState?) -> RunnerState,
) {
    db.query {
        val row =
            Processes
                .select(Processes.runnerState)
                .where { Processes.id eq processId }
                .singleOrNull() ?: return@query
        val next = update(row[Processes.runnerState]?.let(::decodeRunnerState))
        Processes.update({ Processes.id eq processId }) { it[runnerState] = encodeRunnerState(next) }
    }
}

suspend fun ProcessRepository.getRunnerState(processId: Int): RunnerState? =
    db.read {
        Processes
            .select(Processes.runnerState)
            .where { Processes.id eq processId }
            .singleOrNull()
            ?.get(Processes.runnerState)
            ?.let(::decodeRunnerState)
    }

/**
 * Adds [delta] to the run's accumulated cost. Replay-unsafe (`plus` is not
 * idempotent: a replay double-charges the run and, via `CostRepository`,
 * the cost-cap windows); safe only because the writer handle is
 * single-attempt. See ADR-0020.
 */
suspend fun ProcessRepository.addCost(
    processId: Int,
    delta: Double,
    source: CostSource,
) {
    db.query {
        val row =
            Processes
                .select(Processes.costSource, Processes.costUsd)
                .where { Processes.id eq processId }
                .firstOrNull()
                ?: return@query
        val current = row[Processes.costSource]
        val hadCharge = row[Processes.costUsd] != 0.0
        // First charge stamps its source; a later disagreeing source makes it 'mixed' for good.
        val next =
            when {
                current == MIXED_SOURCE -> MIXED_SOURCE
                !hadCharge -> source.wire
                current == source.wire -> current
                else -> MIXED_SOURCE
            }
        Processes.update({ Processes.id eq processId }) {
            it.update(costUsd) { costUsd plus delta }
            it[costSource] = next
        }
    }
}

suspend fun ProcessRepository.setArtifactsDir(
    processId: Int,
    artifactsDir: String,
) {
    db.query {
        Processes.update({ Processes.id eq processId }) { it[Processes.artifactsDir] = artifactsDir }
    }
}

suspend fun ProcessRepository.setTraceId(
    processId: Int,
    traceId: String,
) {
    db.query {
        Processes.update({ Processes.id eq processId }) { it[Processes.traceId] = traceId }
    }
}

/**
 * Stores why the run ended FAILED before any of its commands started, and the run that
 * blocked it when the cause names one. The write is guarded: it lands only while the row
 * still reads `FAILED` and shows no started work — no recorded execution and no current
 * command. A run that failed while it was running therefore keeps NULL, and so does one
 * whose failure raced a kill that won the status CAS. Returns whether the write landed.
 *
 * [reason] is truncated to `MAX_FAILURE_REASON_LENGTH` here, so the column's bound holds
 * whatever the caller passes. See ADR-0020.
 */
suspend fun ProcessRepository.setFailureReason(
    processId: Int,
    reason: String,
    holderRunId: Int?,
): Boolean =
    db.query {
        val row =
            Processes
                .select(Processes.status, Processes.executions, Processes.currentCommand)
                .where { Processes.id eq processId }
                .singleOrNull() ?: return@query false
        if (row[Processes.status] != ProcessStatus.FAILED.name) return@query false
        // Compared as stored text rather than decoded: every writer encodes through
        // encodeExecutions, so an empty list is exactly this JSON, and the comparison keeps a
        // multi-MiB executions blob off the failure path (SQLite rule 4).
        if (row[Processes.executions] != EMPTY_EXECUTIONS_JSON) return@query false
        if (row[Processes.currentCommand] != null) return@query false
        Processes.update({ Processes.id eq processId }) {
            it[failureReason] = truncateFailureReason(reason)
            it[failureReasonRunId] = holderRunId
        } > 0
    }
