package homer.app.services.persistence

import homer.app.infra.ProcessJobRegistry
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.RunnerState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.io.path.Path

// The `processes` row ↔ [ProcessInfo] mapping shared by the ProcessRepository files: the
// status-name sets their predicates filter on, the JSON-column codecs, and the tag and input
// side-table reads a mapped row needs. Every `*Tx` function runs inside the caller's transaction.

internal val TERMINAL_STATUS_NAMES =
    listOf(
        ProcessStatus.FINISHED.name,
        ProcessStatus.FAILED.name,
        ProcessStatus.KILLED.name,
        ProcessStatus.UNKNOWN.name,
    )

internal val ACTIVE_STATUS_NAMES = listOf(ProcessStatus.CREATED.name, ProcessStatus.WORKING.name)

private val historyEntrySerializer = PairSerializer(Long.serializer(), ProcessStatus.serializer())
private val historySerializer = ListSerializer(historyEntrySerializer)
private val executionsSerializer = ListSerializer(ExecutionInfo.serializer())

internal val EMPTY_HISTORY_JSON = columnJson.encodeToString(historySerializer, emptyList())
internal val EMPTY_EXECUTIONS_JSON = columnJson.encodeToString(executionsSerializer, emptyList())

internal fun encodeHistory(value: List<Pair<Long, ProcessStatus>>) = columnJson.encodeToString(historySerializer, value)

internal fun decodeHistory(text: String) = columnJson.decodeFromString(historySerializer, text)

internal fun encodeHistoryElement(entry: Pair<Long, ProcessStatus>) = columnJson.encodeToString(historyEntrySerializer, entry)

internal fun encodeExecutions(value: List<ExecutionInfo>) = columnJson.encodeToString(executionsSerializer, value)

internal fun decodeExecutions(text: String) = columnJson.decodeFromString(executionsSerializer, text)

internal fun encodeParams(value: Map<String, String>) = columnJson.encodeToString(stringMapSerializer, value)

internal fun decodeParams(text: String) = columnJson.decodeFromString(stringMapSerializer, text)

internal fun encodeRetainArtifacts(value: List<String>) = columnJson.encodeToString(stringListSerializer, value)

internal fun decodeRetainArtifacts(text: String) = columnJson.decodeFromString(stringListSerializer, text)

internal fun encodeRunnerState(value: RunnerState) = columnJson.encodeToString(RunnerState.serializer(), value)

internal fun decodeRunnerState(text: String) = columnJson.decodeFromString(RunnerState.serializer(), text)

// Tags of one run, in tag order — guaranteed by this ORDER BY, served by the
// (process_id, tag) primary key with no sort step. Callers that don't surface
// tags (purge/sweep/reconciliation) skip this read and pass emptyList.
internal fun tagsForTx(processId: Int): List<String> =
    ProcessTags
        .select(ProcessTags.tag)
        .where { ProcessTags.processId eq processId }
        .orderBy(ProcessTags.tag)
        .map { it[ProcessTags.tag] }

// Inputs of one run. A missing row reads as no inputs: every row gets one at INSERT,
// so this is only reachable for a row deleted concurrently, or a pre-v25 row whose
// migration backfill predates this code. Row-scoped reads use this; a page uses
// inputsByProcessTx below.
internal fun inputsForTx(processId: Int): Map<String, String> =
    ProcessInputs
        .select(ProcessInputs.inputParams)
        .where { ProcessInputs.processId eq processId }
        .singleOrNull()
        ?.let { decodeParams(it[ProcessInputs.inputParams]) }
        ?: emptyMap()

// One batched read of inputs for a page of rows, keyed by process id, so the list
// endpoint does not issue an N+1 query per row. Chunked for the same SQLite
// bound-parameter reason as tagsByProcessTx.
private fun inputsByProcessTx(ids: List<Int>): Map<Int, Map<String, String>> {
    if (ids.isEmpty()) return emptyMap()
    val inputsById = mutableMapOf<Int, Map<String, String>>()
    for (chunk in ids.chunked(SQLITE_PARAM_CHUNK)) {
        ProcessInputs
            .select(ProcessInputs.processId, ProcessInputs.inputParams)
            .where { ProcessInputs.processId inList chunk }
            .forEach { row -> inputsById[row[ProcessInputs.processId]] = decodeParams(row[ProcessInputs.inputParams]) }
    }
    return inputsById
}

// One batched read of tags for a page of rows, grouped by process id, to avoid
// an N+1 per-row query in the list endpoint. Ids are chunked because each one
// becomes a bound parameter and SQLite caps those per statement.
// [CWE-400] A caller-supplied candidate set beyond the
// parameter limit must degrade to repeated reads, not a 500.
private fun tagsByProcessTx(ids: List<Int>): Map<Int, List<String>> =
    if (ids.isEmpty()) {
        emptyMap()
    } else {
        val tagsById = mutableMapOf<Int, MutableList<String>>()
        for (chunk in ids.chunked(SQLITE_PARAM_CHUNK)) {
            ProcessTags
                .select(ProcessTags.processId, ProcessTags.tag)
                .where { ProcessTags.processId inList chunk }
                .orderBy(ProcessTags.processId to SortOrder.ASC, ProcessTags.tag to SortOrder.ASC)
                .forEach { row ->
                    val id = row[ProcessTags.processId]
                    // Rows of one id never straddle chunks (id IN one chunk at most), and
                    // this ORDER BY (served by the (process_id, tag) primary key, no sort
                    // step) guarantees each id's rows arrive tag-ordered, so appending
                    // keeps each id's tags in tag order.
                    tagsById.getOrPut(id) { mutableListOf() } += row[ProcessTags.tag]
                }
        }
        tagsById
    }

/**
 * Maps full process rows to [ProcessInfo]s, with their tags and inputs read in one batch each.
 * Lazy, so a caller that folds the rows (costByTag, up to 10 000) decodes them one at a time.
 */
internal fun List<ResultRow>.toProcessInfosTx(jobRegistry: ProcessJobRegistry): Sequence<ProcessInfo> {
    val ids = map { it[Processes.id] }
    val tagsById = tagsByProcessTx(ids)
    val inputsById = inputsByProcessTx(ids)
    return asSequence().map {
        it.toProcessInfo(jobRegistry, tagsById[it[Processes.id]] ?: emptyList(), inputsById[it[Processes.id]] ?: emptyMap())
    }
}

/** Maps one row to a [ProcessInfo]; its live [ProcessInfo.job] comes from [jobRegistry]. */
internal fun ResultRow.toProcessInfo(
    jobRegistry: ProcessJobRegistry,
    tags: List<String>,
    inputParams: Map<String, String>,
): ProcessInfo {
    val id = this[Processes.id]
    return ProcessInfo(
        id = id,
        status = ProcessStatus.valueOf(this[Processes.status]),
        agentName = this[Processes.agentName],
        inputParams = inputParams,
        secretInputKeys = this[Processes.secretInputKeys]?.let { decodeKeys(it) },
        job = jobRegistry.get(id),
        // The blob columns read as absent on the projected record-level reads
        // (`Processes.recordReadColumns`), where their consumers read none of them.
        history = getOrNull(Processes.history)?.let(::decodeHistory).orEmpty(),
        executions = getOrNull(Processes.executions)?.let(::decodeExecutions).orEmpty(),
        currentCommand = this[Processes.currentCommand],
        currentCommandStart = this[Processes.currentCommandStart],
        artifactsDir = this[Processes.artifactsDir]?.let { Path(it) },
        purgeAt = this[Processes.purgeAt],
        runner = getOrNull(Processes.runnerState)?.let(::decodeRunnerState),
        costUsd = this[Processes.costUsd],
        costSource = this[Processes.costSource],
        owner = this[Processes.owner],
        traceId = this[Processes.traceId],
        parentProcessId = this[Processes.parentProcessId],
        rootProcessId = this[Processes.rootProcessId],
        retainArtifacts = this[Processes.retainArtifacts]?.let { decodeRetainArtifacts(it) } ?: emptyList(),
        retainedTruncated = this[Processes.retainedTruncated],
        tags = tags,
    )
}
