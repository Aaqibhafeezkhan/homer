package homer.app.models

import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import java.nio.file.Path

data class ProcessCreationResult(
    val processId: Int,
)

/** The slice of a run a LangGraph `subs` entry shows (ADR-0044). */
data class SubRunProgress(
    val agentName: String,
    val status: ProcessStatus,
    val executions: List<ExecutionInfo>,
    val currentCommand: String?,
)

data class ProcessInfo(
    val id: Int,
    val status: ProcessStatus,
    val agentName: String,
    val inputParams: Map<String, String> = emptyMap(),
    // envNames the agent declared `secret: true` when this run started. Null for
    // rows predating the column, which fall back to the current agent definition.
    // See ADR-0052.
    val secretInputKeys: Set<String>? = null,
    val job: Job? = null,
    val history: List<Pair<Long, ProcessStatus>> = emptyList(),
    val executions: List<ExecutionInfo> = emptyList(),
    val currentCommand: String? = null,
    val currentCommandStart: Long? = null,
    val artifactsDir: Path? = null,
    // Unix epoch seconds; null = no purge scheduled
    val purgeAt: Long? = null,
    val runner: RunnerState? = null,
    // Sum of the resolved cost (session-spend endpoint or CLI figure) of every
    // accounted claude attempt in this process, including failed and aborted
    // attempts. Zero when the process has no claude commands or none priced above
    // zero. See costSource below and ADR-0061.
    val costUsd: Double = 0.0,
    // 'cli' | 'session' | 'mixed' — which pricing rung produced costUsd. See ADR-0061.
    val costSource: String = "cli",
    // Principal that caused this run (grants-based authorization, ADR-0038). Null
    // for runs not yet stamped with an owner.
    val owner: String? = null,
    // W3C trace id of this run's root span; null when tracing is off or the span
    // was not sampled. Used to deep-link a run to its Langfuse trace.
    val traceId: String? = null,
    // Id of the run that caused this one (RunAgent, dispatch, continuation, or an
    // external caller's X-Homer-Parent-Process-Id). Null at the root of a flow.
    // See ADR-0058.
    val parentProcessId: Int? = null,
    // Id of the first run of this run's flow; null at the root. See ADR-0058.
    val rootProcessId: Int? = null,
    // Artifact globs declared to survive the workspace purge, captured at terminal
    // status. Empty when nothing is retained. See ADR-0053.
    val retainArtifacts: List<String> = emptyList(),
    // Whether the retained set was cut short by server.maxRetainedArtifactBytes.
    // Null until the workspace sweep has run (or when nothing was retained).
    val retainedTruncated: Boolean? = null,
    // Subject tags carried by this run: `key:value` strings resolved from inputs at
    // start and/or declared at run time via the callback route. Empty for the
    // sweep/reconciliation reads that do not surface tags. See ADR-0060.
    val tags: List<String> = emptyList(),
    // Why the run ended FAILED before any of its commands started: the claim timeout, the
    // lock skip, the parallel-run cap or one of the two queue bounds. A fixed-vocabulary
    // sentence built from typed fields, never a raw exception message. Null for every other
    // failure, and for a run that never failed. See ADR-0020.
    val failureReason: String? = null,
    // The run that blocked this one (the lock holder), when the cause names one; emitted only
    // to a caller who may see that run. Not a foreign key: the holder may be evicted first,
    // like every other run link. See ADR-0058.
    val failureReasonRunId: Int? = null,
)

/**
 * The hard cap on a stored pre-start failure reason. The repository write enforces it again,
 * so no caller can grow the column past it. See ADR-0047.
 */
const val MAX_FAILURE_REASON_LENGTH = 500

/** Appended when a reason is cut at [MAX_FAILURE_REASON_LENGTH], so a cut is visible. */
const val FAILURE_REASON_TRUNCATION_MARKER = " …"

/** Cuts [text] to [MAX_FAILURE_REASON_LENGTH] characters, marking the cut with [FAILURE_REASON_TRUNCATION_MARKER]. */
fun truncateFailureReason(text: String): String {
    if (text.length <= MAX_FAILURE_REASON_LENGTH) return text
    var body = text.take(MAX_FAILURE_REASON_LENGTH - FAILURE_REASON_TRUNCATION_MARKER.length)
    // `take` counts UTF-16 chars and can cut inside a surrogate pair, which would store half
    // a character in the column and in the JSON response — drop the orphaned half.
    if (body.lastOrNull()?.isHighSurrogate() == true) body = body.dropLast(1)
    return body + FAILURE_REASON_TRUNCATION_MARKER
}

/**
 * Pod lifecycle surfaced to clients and telemetry. `alias`
 * identifies the runner backend; other fields stay null for
 * the built-in `local` runner where they do not apply.
 */
@Serializable
data class RunnerState(
    val alias: String,
    val podName: String? = null,
    val podPhase: PodPhase = PodPhase.NONE,
    val createdAt: Long? = null,
    val readyAt: Long? = null,
    val endedAt: Long? = null,
    val startupFailure: PodStartupFailure? = null,
)

@Serializable
data class PodStartupFailure(
    val reason: String,
    val message: String,
    val events: List<String>,
)

@Serializable
enum class PodPhase {
    NONE,
    CREATING,
    PENDING,
    RUNNING,
    READY,
    SUCCEEDED,
    FAILED,
    DELETED,
    KEPT_FOR_DEBUG,
}

@Serializable
data class ExecutionInfo(
    val label: String,
    val start: Long,
    val end: Long,
    val resultCode: Int,
    @Serializable(with = PathAsStringSerializer::class)
    val stdOut: Path?,
    @Serializable(with = PathAsStringSerializer::class)
    val stdErr: Path?,
    val errMsg: String?,
    val skipped: Boolean = false,
    val outputTruncated: Boolean = false,
    val llmAccounting: LlmAccounting? = null,
    // The command's tag (`cmd`, `script`, `claude`, `codex`, `opencode`, `agent`,
    // `langgraph`, `runnerCmd`);
    // empty for rows written before this field existed.
    val kind: String = "",
)

@Serializable
enum class ProcessStatus {
    CREATED,
    WORKING,
    FINISHED,
    KILLED,
    FAILED,
    UNKNOWN,
}
