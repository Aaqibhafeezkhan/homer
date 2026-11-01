package homer.app.models

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class HeartbeatResponse(
    val status: String = "ok",
    val version: String = "dev",
)

@Serializable
data class ServerStatusResponse(
    val startTime: String,
    val currentTime: String,
    val lastProcessId: Int,
)

@Serializable
data class ExecuteResponse(
    val processId: Int,
)

@Serializable
data class ProcessStatusResponse(
    val status: String,
)

@Serializable
data class ProcessInfoResponse(
    val id: Int,
    val status: String,
    val agentName: String,
    // The agent's group, read from the *current* agent definition at response time —
    // never stored on the process row. Null when the agent is ungrouped or gone.
    val group: String? = null,
    val history: List<ProcessHistoryEntry>,
    val executions: List<ExecutionInfoResponse>,
    val currentCommand: String? = null,
    val currentCommandStart: Long? = null,
    // Tag of the command running now (`cmd`, `script`, `claude`, `codex`, `opencode`,
    // `agent`, `langgraph`, `runnerCmd`), from in-memory tracking; null when nothing
    // runs or after a restart.
    val currentCommandKind: String? = null,
    // Index of the command running now, from in-memory tracking; null when nothing
    // runs or after a restart.
    val currentCommandIndex: Int? = null,
    val purgeAt: Long? = null, // Unix epoch seconds; null if not yet scheduled or process is active
    val inputParams: Map<String, String> = emptyMap(),
    val runner: RunnerStateResponse? = null,
    val costUsd: Double = 0.0,
    // 'cli' | 'session' | 'mixed': which pricing rung produced costUsd. See ADR-0061.
    val costSource: String = "cli",
    // Count of OPEN operator questions waiting on this process (ADR-0019).
    val openQuestions: Int = 0,
    val owner: String? = null,
    // Deep link to this run's Langfuse trace. Null unless the run has a trace id
    // AND `langfuse.serverUrl` + `langfuse.projectId` are both configured —
    // Langfuse derives the project from the API keys and never sends it back, so
    // without the configured id there is no URL to build.
    val langfuseTraceUrl: String? = null,
    // Id of the run that caused this one; null at the root of a flow. See ADR-0058.
    val parentProcessId: Int? = null,
    // Id of the first run of this run's flow; null at the root itself. Filter a
    // whole flow with `?rootProcessId=`. See ADR-0058.
    val rootProcessId: Int? = null,
    // Whether server.maxRetainedArtifactBytes cut the retained-artifact set short
    // at the workspace purge. Null until a retention prune has run. See ADR-0053.
    val retainedTruncated: Boolean? = null,
    // Subject tags carried by this run: `key:value` strings resolved from inputs at
    // start and/or declared at run time. Filter runs by them with `?tag=`. See ADR-0060.
    val tags: List<String> = emptyList(),
    // Why the run ended FAILED before any of its commands started (a claim timeout, a
    // SKIP_CURRENT lock skip, a parallel-run cap or a full queue). Null for every other
    // failure. The run that blocked this one is named inside the text only for a caller who
    // may see it. See ADR-0058.
    val failureReason: String? = null,
)

@Serializable
data class RunnerStateResponse(
    // Alias is admin-only (CWE-200, fail closed): null for a non-admin caller. See ADR-0077.
    val alias: String? = null,
    // Generic backend kind (`local` / `ssh` / `kubernetes` / `external` / `unknown`), always
    // emitted so a non-admin row keeps a backend label. Same mapping as `GET /api/v1/runners`.
    val type: String,
    val podName: String? = null,
    val podPhase: String,
    val createdAt: Long? = null,
    val readyAt: Long? = null,
    val endedAt: Long? = null,
    val startupFailure: PodStartupFailureResponse? = null,
)

@Serializable
data class PodStartupFailureResponse(
    val reason: String,
    val message: String,
    val events: List<String>,
)

@Serializable
data class SweepDeletedPodResponse(
    val namespace: String,
    val name: String,
    val processId: Int? = null,
    // Alias is admin-only (CWE-200, fail closed): null for a non-admin caller. See ADR-0077.
    val runnerAlias: String? = null,
)

@Serializable
data class SweepStatusResponse(
    val ranAt: Long, // Unix epoch seconds, matching every other API timestamp
    val scanned: Int,
    val deleted: List<SweepDeletedPodResponse>,
    val errors: List<String>,
)

@Serializable
data class RunnerSummary(
    // Alias is admin-only (CWE-200, fail closed): null for a non-admin caller. See ADR-0077.
    val alias: String? = null,
    val type: String,
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val runners: List<RunnerSummary>,
    val sweep: SweepStatusResponse? = null,
    // Admin maintenance mode: true while new starts are queued and cron is skipped. Read
    // from the in-memory flag, so polling this costs no database read. See ADR-0092.
    val maintenance: Boolean = false,
)

/** `GET`/`POST /api/v1/maintenance`: the admin maintenance-mode state. See ADR-0092. */
@Serializable
data class MaintenanceResponse(
    val enabled: Boolean,
    // Epoch seconds the mode was turned on; null while off, and null for a mode that was
    // already on when this process booted (the toggle time is not persisted).
    val since: Long? = null,
    // Starts waiting in the queue right now.
    val queued: Int = 0,
)

/** `POST /api/v1/maintenance` body. An unknown key or a non-boolean `enabled` is rejected with 400. */
@Serializable
data class SetMaintenanceRequest(
    val enabled: Boolean,
)

@Serializable
data class LivenessResponse(
    val status: String = "alive",
)

@Serializable
data class ReadinessResponse(
    val status: String,
)

@Serializable
data class ProcessListResponse(
    val processes: List<ProcessInfoResponse>,
    val total: Int, // Total count before pagination
    val offset: Int, // Offset applied
    val limit: Int, // Limit applied
)

@Serializable
data class ProcessHistoryEntry(
    val timestamp: Long,
    val status: String,
)

@Serializable
data class ExecutionInfoResponse(
    val label: String,
    val start: Long,
    val end: Long,
    val resultCode: Int,
    val stdOut: String?,
    val stdErr: String?,
    val errMsg: String?,
    val skipped: Boolean = false,
    val llmAccounting: LlmAccounting? = null,
    val kind: String = "",
)

data class CommandResult(
    val exitCode: Int,
    val stdOut: Path,
    val stdErr: Path,
    // Set only by LlmHarnessExecutor (Claude/Codex): how this command's spend was priced. See ADR-0061.
    val llmAccounting: LlmAccounting? = null,
)

@Serializable
data class AgentsListResponse(
    val agents: List<AgentInfo>,
)

@Serializable
data class CronInfo(
    val expression: String,
    val timezone: String,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    val lastRunProcessId: Int?,
)

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    // The agent's optional group label, null when unset. The console renders one
    // tab per distinct non-null value; ungrouped agents appear under "All" only.
    val group: String? = null,
    // Canonical declared inputs. `queryParams` is the deprecated alias kept for
    // older clients; new-style agents (`inputs:`) populate `inputs` only, so the
    // list endpoint MUST send both or a migrated agent shows no parameters.
    val inputs: List<Param>? = null,
    val queryParams: List<Param>? = null,
    val scriptCount: Int,
    val cron: CronInfo? = null,
    val writable: Boolean = true,
) {
    companion object {
        /**
         * Maps a loaded [Agent] to its API summary. Single source of truth for
         * the `GET /agents` mapping so route and tests can't drift (this mapping
         * previously omitted `inputs`, hiding params for `inputs:`-style agents).
         *
         * A secret input's `default` and `values` are dropped, not masked:
         * any grant-holder reads this summary. See ADR-0071.
         */
        fun from(
            name: String,
            agent: Agent,
            cron: CronInfo? = null,
        ): AgentInfo =
            AgentInfo(
                name = name,
                description = agent.description,
                group = agent.group,
                inputs = agent.inputs?.map { it.redactedForSummary() },
                // The legacy alias is still served to older clients (see the field above).
                queryParams = @Suppress("DEPRECATION") agent.queryParams?.map { it.redactedForSummary() },
                scriptCount = agent.commands.size,
                cron = cron,
                writable = agent.writable,
            )

        /** The [Param] shape served by `GET /agents`: a secret input carries no `default` or `values`. */
        private fun Param.redactedForSummary(): Param = if (secret) copy(default = null, values = null) else this
    }
}

@Serializable
data class ErrorResponse(
    val err: Int = 1,
    val msg: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
) {
    init {
        // Cap inbound field sizes so a pathological login body can't drag the
        // bcrypt verify path or the JSON decoder. Bcrypt itself truncates to
        // 72 bytes after key-stretch, but the bytes still allocate; 256 / 1024
        // are well above any real-world value and small enough to be cheap.
        require(username.length <= MAX_USERNAME_LEN) {
            "LoginRequest.username must be <= $MAX_USERNAME_LEN chars; got ${username.length}"
        }
        require(password.length <= MAX_PASSWORD_LEN) {
            "LoginRequest.password must be <= $MAX_PASSWORD_LEN chars; got ${password.length}"
        }
    }

    companion object {
        const val MAX_USERNAME_LEN: Int = 256
        const val MAX_PASSWORD_LEN: Int = 1024
    }
}

@Serializable
data class LoginResponse(
    val username: String,
)

@Serializable
data class MeResponse(
    val username: String,
    val role: String?,
    val grants: List<Grant>,
)

@Serializable
data class CostsResponse(
    val globalUsdLast60s: Double,
    val globalCapUsdPerMin: Double? = null,
    val perAgentUsdLast24h: Map<String, Double>,
    val perAgentCapUsdPerDay: Map<String, Double>,
    val snapshotAtSec: Long,
    val costTrackedTagKeys: List<String> = emptyList(),
)

@Serializable
data class MonthlyCostsResponse(
    val month: String,
    val perAgentUsd: Map<String, Double>,
    val totalUsd: Double,
    val availableMonths: List<String>,
)

@Serializable
data class TotalCostsResponse(
    val perAgentUsd: Map<String, Double>,
    val totalUsd: Double,
)

@Serializable
data class CostByTagResponse(
    val key: String,
    val valuesUsd: Map<String, Double>,
    val totalUsd: Double,
    // True when more runs carried this tag than the repository's row bound summed —
    // see ADR-0062's amendment.
    val truncated: Boolean = false,
)
