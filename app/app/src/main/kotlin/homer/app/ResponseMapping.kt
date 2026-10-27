package homer.app

import homer.app.models.Agent
import homer.app.models.ContinuationResponse
import homer.app.models.ContinuationSummary
import homer.app.models.DispatchResponse
import homer.app.models.DispatchSummary
import homer.app.models.ExecutionInfoResponse
import homer.app.models.LangfuseConfig
import homer.app.models.PodStartupFailureResponse
import homer.app.models.ProcessHistoryEntry
import homer.app.models.ProcessInfo
import homer.app.models.ProcessInfoResponse
import homer.app.models.QuestionResponse
import homer.app.models.RunMessageResponse
import homer.app.models.RunnerState
import homer.app.models.RunnerStateResponse
import homer.app.models.stripUrlSecrets
import homer.app.services.Continuation
import homer.app.services.Dispatch
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.RunMessage
import java.net.URI

/**
 * Converts ProcessInfo to ProcessInfoResponse for JSON serialization.
 * [openQuestions] is the per-process open-question count: `/status/all` reads
 * it with its page (`ProcessListResult.openQuestions`), `/status/{id}` from
 * `QuestionsService.openCountByProcess()`, once per request. [agent] is the
 * current agent definition (null if gone since the run); secret input values
 * are redacted per [redactInputParams].
 * [rootVisible] gates [ProcessInfoResponse.rootProcessId]: the id names a run
 * owned by another principal, so it is dropped unless the caller can see that
 * root (the same visibility union the row itself passed) — existence of a
 * foreign flow root is not leaked. See ADR-0058.
 */
fun ProcessInfo.toResponse(
    openQuestions: Int = 0,
    agent: Agent?,
    langfuse: LangfuseConfig = LangfuseConfig(),
    rootVisible: Boolean = true,
    currentCommandKind: String? = null,
    currentCommandIndex: Int? = null,
    // Fail closed, unlike `rootVisible`: the alias is admin-only (CWE-200). See ADR-0077.
    aliasVisible: Boolean = false,
    // Generic backend kind for `this.runner.alias`, precomputed by the caller from
    // the config; always emitted so a non-admin row keeps a backend label.
    runnerType: String? = null,
): ProcessInfoResponse =
    ProcessInfoResponse(
        id = this.id,
        status = this.status.name,
        agentName = this.agentName,
        // Resolved from the current definition, not the stored row: an edit to the
        // agent's group shows on every run of it, past ones included. Null when the
        // agent is gone or ungrouped.
        group = agent?.group,
        history =
            this.history.map { (timestamp, status) ->
                ProcessHistoryEntry(
                    timestamp = timestamp,
                    status = status.name,
                )
            },
        executions =
            this.executions.map { execution ->
                ExecutionInfoResponse(
                    label = execution.label,
                    start = execution.start,
                    end = execution.end,
                    resultCode = execution.resultCode,
                    stdOut = execution.stdOut?.toString(),
                    stdErr = execution.stdErr?.toString(),
                    errMsg = execution.errMsg,
                    skipped = execution.skipped,
                    llmAccounting = execution.llmAccounting,
                    kind = execution.kind,
                )
            },
        currentCommand = this.currentCommand,
        currentCommandStart = this.currentCommandStart,
        currentCommandKind = currentCommandKind,
        currentCommandIndex = currentCommandIndex,
        purgeAt = this.purgeAt,
        inputParams = redactInputParams(this.inputParams, agent, this.secretInputKeys),
        runner = this.runner?.toResponse(type = runnerType ?: "unknown", aliasVisible = aliasVisible),
        costUsd = this.costUsd,
        costSource = this.costSource,
        openQuestions = openQuestions,
        owner = this.owner,
        langfuseTraceUrl = langfuseTraceUrl(this.traceId, langfuse),
        parentProcessId = this.parentProcessId,
        // [sensitive_data_exposure / CWE-200] The root id is only emitted when the
        // caller may open the run it points at; a hidden root reads as "unknown"
        // (null), the same meaning an evicted row already carries.
        rootProcessId = if (rootVisible) this.rootProcessId else null,
        retainedTruncated = this.retainedTruncated,
        tags = screenTags(this.tags, this.inputParams, agent, this.secretInputKeys),
    )

/**
 * Builds the Langfuse UI URL for [traceId], or null when any piece is missing.
 * Langfuse resolves a trace only under its project, and the project id is not
 * derivable from the OTLP credentials — hence the explicit `langfuse.projectId`.
 */
private fun langfuseTraceUrl(
    traceId: String?,
    langfuse: LangfuseConfig,
): String? {
    if (traceId.isNullOrBlank() || langfuse.serverUrl.isBlank() || langfuse.projectId.isBlank()) return null
    // `serverUrl` is operator-supplied and this field is returned to every caller who
    // can see the process, so it gets the same treatment as the log sink: userinfo,
    // query and fragment are dropped rather than reflected. The multi-argument URI
    // constructor also quotes anything illegal in the path it builds. A URL we cannot
    // parse yields no link at all — better than emitting a guess.
    val base = stripUrlSecrets(langfuse.serverUrl.trimEnd('/'))?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (base.scheme == null || base.host == null) return null
    return runCatching {
        URI(
            base.scheme,
            null,
            base.host,
            base.port,
            "${base.path.orEmpty()}/project/${langfuse.projectId}/traces/$traceId",
            null,
            null,
        ).toString()
    }.getOrNull()
}

/**
 * The alias is admin-only (CWE-200, fail closed); non-admins get the generic
 * [type]. See ADR-0077.
 */
fun RunnerState.toResponse(
    type: String,
    aliasVisible: Boolean,
): RunnerStateResponse =
    RunnerStateResponse(
        alias = if (aliasVisible) this.alias else null,
        type = type,
        podName = this.podName,
        podPhase = this.podPhase.name,
        createdAt = this.createdAt,
        readyAt = this.readyAt,
        endedAt = this.endedAt,
        startupFailure =
            this.startupFailure?.let {
                PodStartupFailureResponse(
                    reason = it.reason,
                    message = it.message,
                    events = it.events,
                )
            },
    )

/** [redactedParams] must come from `redactStoredParams`; the raw map may hold credentials. */
fun Continuation.toResponse(redactedParams: Map<String, String>): ContinuationResponse =
    ContinuationResponse(
        id = id,
        watchedProcessId = watchedProcessId,
        status = status.name,
        agentName = targetAgentName,
        resumeParam = resumeParam,
        params = redactedParams,
        originProcessId = originProcessId,
        originAgentName = originAgentName,
        expiresAt = expiresAt,
        createdAt = createdAt,
        firedAt = firedAt,
        firedProcessId = firedProcessId,
        error = error,
    )

fun Continuation.toSummary(): ContinuationSummary =
    ContinuationSummary(id = id, agentName = targetAgentName, status = status.name, firedProcessId = firedProcessId, error = error)

/** [redactedParams] must come from `redactStoredParams`; the raw map may hold credentials. */
fun Dispatch.toResponse(redactedParams: Map<String, String>): DispatchResponse =
    DispatchResponse(
        id = id,
        questionId = questionId,
        status = status.name,
        agentName = agentName,
        answerParam = answerParam,
        params = redactedParams,
        originProcessId = originProcessId,
        originAgentName = originAgentName,
        expiresAt = expiresAt,
        createdAt = createdAt,
        dispatchedAt = dispatchedAt,
        dispatchedProcessId = dispatchedProcessId,
        error = error,
    )

fun Dispatch.toSummary(): DispatchSummary =
    DispatchSummary(id = id, agentName = agentName, status = status.name, dispatchedProcessId = dispatchedProcessId, error = error)

fun QuestionsService.Question.toResponse(): QuestionResponse =
    QuestionResponse(
        id = id,
        processId = processId,
        agentName = agentName,
        text = text,
        options = options,
        status = status.name,
        answer = answer,
        createdAt = createdAt,
        answeredAt = answeredAt,
    )

fun QuestionsService.Question.toResponse(dispatch: DispatchSummary?): QuestionResponse = toResponse().copy(dispatch = dispatch)

fun RunMessage.toResponse(): RunMessageResponse =
    RunMessageResponse(
        id = id,
        processId = processId,
        text = text,
        status = status.name,
        sender = sender,
        createdAt = createdAt,
        deliveredAt = deliveredAt,
    )
