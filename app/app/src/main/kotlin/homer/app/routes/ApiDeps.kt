package homer.app.routes

import homer.app.executors.ParallelRunsManager
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.AgentInputResolver
import homer.app.infra.FileOperations
import homer.app.infra.PasswordVerifier
import homer.app.infra.TelemetryService
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.runners.kubernetes.KubernetesRunnerStartupSweep
import homer.app.runners.kubernetes.SweepStatus
import homer.app.services.AgentFilesService
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.ArtifactService
import homer.app.services.Authorizer
import homer.app.services.ContinuationService
import homer.app.services.CostMeter
import homer.app.services.CronSchedulerService
import homer.app.services.DispatchService
import homer.app.services.LangGraphInspectService
import homer.app.services.LangGraphRunStatusService
import homer.app.services.LlmEventStreamService
import homer.app.services.LogTailService
import homer.app.services.McpToolFactory
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.QuestionsMcpToolFactory
import homer.app.services.QuestionsService
import homer.app.services.ResultTokenService
import homer.app.services.WorkspacePurgeService
import io.ktor.server.routing.get
import io.ktor.server.sessions.get
import io.ktor.server.sessions.set
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dependency bundle for the API route extension functions. Centralizes the
 * (large) set of services and configuration values the route handlers need,
 * so the extensions take one parameter instead of twenty. The route bodies
 * reference these fields unqualified via `with(deps) { ... }`, keeping the
 * inside-block diff against the pre-split Application.kt byte-identical.
 */
internal data class ApiDeps(
    val config: AppConfig,
    val authorizer: Authorizer,
    val serverId: String,
    val isDraining: AtomicBoolean,
    val applicationScope: CoroutineScope,
    val drainTimeout: kotlin.time.Duration,
    val processManager: ProcessManager,
    val agentsService: AgentsService,
    val agentFilesService: AgentFilesService,
    val langGraphInspectService: LangGraphInspectService,
    val langGraphRunStatusService: LangGraphRunStatusService,
    val parallelRunsManager: ParallelRunsManager,
    val agentInputResolver: AgentInputResolver,
    val agentRunStarter: AgentRunStarter,
    val mcpToolFactory: McpToolFactory,
    val telemetryService: TelemetryService,
    val processFactory: ProcessFactory,
    val processExecutor: ProcessExecutor,
    val artifactService: ArtifactService,
    val fileOperations: FileOperations,
    val cronSchedulerService: CronSchedulerService,
    val workspacePurgeService: WorkspacePurgeService,
    val processEventBroadcaster: ProcessEventBroadcaster,
    val logTailService: LogTailService,
    val llmEventStreamService: LlmEventStreamService,
    val questionsService: QuestionsService,
    val dispatchService: DispatchService,
    val continuationService: ContinuationService,
    val resultTokenService: ResultTokenService,
    val questionsMcpToolFactory: QuestionsMcpToolFactory,
    val activeSessions: ActiveSessionsRegistry,
    val startupSweep: KubernetesRunnerStartupSweep,
    val sweepStatus: SweepStatus,
    val runnerRegistry: RunnerRegistry,
    val costMeter: CostMeter,
    val passwordScheme: AuthConfig.Password?,
    val passwordVerifier: PasswordVerifier,
    val hmacBodyKey: AttributeKey<ByteArray>,
)
