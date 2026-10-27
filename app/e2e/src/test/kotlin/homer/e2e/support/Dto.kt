package homer.e2e.support

import kotlinx.serialization.Serializable

/**
 * Minimal client-side declarations of the wire contract, decoded with
 * `ignoreUnknownKeys = true`. Deliberately NOT the `:app` models: sharing
 * them would hide serialization regressions on both sides. Only the fields
 * the suite asserts on are declared.
 */

@Serializable
data class RunResponse(
    val processId: Int,
)

@Serializable
data class HistoryEntry(
    val timestamp: Long,
    val status: String,
)

@Serializable
data class Execution(
    val label: String,
    val start: Long,
    val end: Long,
    val resultCode: Int,
    val stdOut: String? = null,
    val stdErr: String? = null,
    val errMsg: String? = null,
    val skipped: Boolean = false,
    val kind: String = "",
)

@Serializable
data class RunnerStateView(
    // Null for a non-admin viewer, who gets the generic `type` instead.
    val alias: String? = null,
    val type: String = "unknown",
    val podName: String? = null,
    val podPhase: String = "NONE",
)

@Serializable
data class ProcessInfo(
    val id: Int,
    val status: String,
    val agentName: String,
    val history: List<HistoryEntry> = emptyList(),
    val executions: List<Execution> = emptyList(),
    val inputParams: Map<String, String> = emptyMap(),
    val costUsd: Double = 0.0,
    val openQuestions: Int = 0,
    val owner: String? = null,
    val parentProcessId: Int? = null,
    val rootProcessId: Int? = null,
    val tags: List<String> = emptyList(),
    val runner: RunnerStateView? = null,
)

@Serializable
data class ProcessList(
    val processes: List<ProcessInfo>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

@Serializable
data class ErrorBody(
    val err: Int = 1,
    val msg: String,
)

@Serializable
data class SchemaErrorBody(
    val msg: String,
    val errors: List<String> = emptyList(),
)

@Serializable
data class Heartbeat(
    val status: String,
    val version: String = "dev",
)

@Serializable
data class Health(
    val status: String = "ok",
    // True while the admin maintenance mode is on: new starts queue and cron is skipped.
    val maintenance: Boolean = false,
)

@Serializable
data class MaintenanceState(
    val enabled: Boolean,
    val since: Long? = null,
    val queued: Int = 0,
)

@Serializable
data class AgentParam(
    val paramName: String,
    val envName: String? = null,
    val type: String? = null,
    val required: Boolean = true,
    val secret: Boolean = false,
    val default: String? = null,
    val values: List<String>? = null,
)

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val writable: Boolean = true,
    val inputs: List<AgentParam>? = null,
)

@Serializable
data class AgentsList(
    val agents: List<AgentInfo>,
)

@Serializable
data class CreateAgentResponse(
    val name: String,
)

@Serializable
data class ReloadResult(
    val added: List<String>,
    val updated: List<String>,
    val removed: List<String>,
    val errors: List<String> = emptyList(),
)

@Serializable
data class FileEntry(
    val path: String,
    val size: Long,
    val mtime: Long,
    val isDirectory: Boolean,
)

@Serializable
data class FileList(
    val entries: List<FileEntry>,
)

@Serializable
data class FileContent(
    val path: String,
    val content: String,
)

@Serializable
data class ArtifactEntry(
    val path: String,
    val size: Long,
    val modified: Long,
)

@Serializable
data class ArtifactListing(
    val processId: Int,
    val artifactsDir: String,
    val files: List<ArtifactEntry>,
)

@Serializable
data class DispatchSummary(
    val id: Int,
    val agentName: String,
    val status: String,
    val dispatchedProcessId: Int? = null,
    val error: String? = null,
)

@Serializable
data class Question(
    val id: String,
    val processId: Int,
    val agentName: String,
    val text: String,
    val options: List<String> = emptyList(),
    val status: String,
    val answer: String? = null,
    val createdAt: Long,
    val answeredAt: Long? = null,
    val dispatch: DispatchSummary? = null,
)

@Serializable
data class QuestionsList(
    val questions: List<Question>,
)

@Serializable
data class Dispatch(
    val id: Int,
    val questionId: String,
    val status: String,
    val agentName: String,
    val answerParam: String,
    val params: Map<String, String> = emptyMap(),
    val originProcessId: Int,
    val originAgentName: String,
    val dispatchedProcessId: Int? = null,
    val error: String? = null,
)

@Serializable
data class DispatchList(
    val dispatches: List<Dispatch>,
)

@Serializable
data class Continuation(
    val id: Int,
    val watchedProcessId: Int,
    val status: String,
    val agentName: String,
    val resumeParam: String,
    val params: Map<String, String> = emptyMap(),
    val originProcessId: Int,
    val originAgentName: String,
    val firedProcessId: Int? = null,
    val error: String? = null,
)

@Serializable
data class ContinuationList(
    val continuations: List<Continuation>,
)

@Serializable
data class RunnerInstanceView(
    val name: String,
    val tags: List<String>,
    val activeSessions: Int,
    val draining: Boolean,
)

@Serializable
data class RunnerView(
    val alias: String,
    val kind: String,
    val instances: List<RunnerInstanceView>? = null,
)

@Serializable
data class RunnersView(
    val runners: List<RunnerView>,
)

@Serializable
data class RunnerQueueView(
    val pending: Int,
)

@Serializable
data class TopologyNode(
    val id: String,
)

@Serializable
data class TopologyEdge(
    val source: String,
    val target: String,
    val label: String? = null,
    val conditional: Boolean,
)

@Serializable
data class Topology(
    val nodes: List<TopologyNode>,
    val edges: List<TopologyEdge>,
)

@Serializable
data class LangGraphGraph(
    val label: String,
    val module: String,
    val topology: Topology? = null,
    val error: String? = null,
)

@Serializable
data class RunMessage(
    val id: String,
    val text: String,
    val status: String,
)

@Serializable
data class RunMessagesList(
    val messages: List<RunMessage>,
)
