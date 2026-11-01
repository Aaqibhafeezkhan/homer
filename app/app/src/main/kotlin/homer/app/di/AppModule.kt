package homer.app.di

import homer.app.config.ExecutionConfig
import homer.app.executors.CommandExecutorFactory
import homer.app.executors.DefaultLockManager
import homer.app.executors.DefaultParallelRunsManager
import homer.app.executors.LangGraphExecutor
import homer.app.executors.LockManager
import homer.app.executors.ParallelRunsManager
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.executors.RunAgentExecutor
import homer.app.executors.RunnerCmdExecutor
import homer.app.executors.llm.ClaudeHarness
import homer.app.executors.llm.CodexHarness
import homer.app.executors.llm.OpencodeHarness
import homer.app.infra.AgentInputResolver
import homer.app.infra.DefaultLangfuseClient
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.FileOperations
import homer.app.infra.GraphStateRunner
import homer.app.infra.InputSchemaValidator
import homer.app.infra.LangfuseClient
import homer.app.infra.LlmCostResolver
import homer.app.infra.LocalFileOperations
import homer.app.infra.OtelTelemetryService
import homer.app.infra.PasswordVerifier
import homer.app.infra.ProcessGraphStateRunner
import homer.app.infra.ProcessJobRegistry
import homer.app.infra.QuestionsWorkspaceSetup
import homer.app.infra.TelemetryService
import homer.app.infra.TextCompiler
import homer.app.infra.WorkspaceConfigFactory
import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.runners.kubernetes.KubernetesRunnerStartupSweep
import homer.app.runners.kubernetes.RunnerStateSink
import homer.app.runners.kubernetes.SweepStatus
import homer.app.runners.local.LocalRunner
import homer.app.services.AgentFilesService
import homer.app.services.AgentRunStarter
import homer.app.services.AgentSubRunStarter
import homer.app.services.AgentsService
import homer.app.services.ArtifactService
import homer.app.services.Authorizer
import homer.app.services.ContinuationService
import homer.app.services.ContinuationSweepService
import homer.app.services.CooldownGate
import homer.app.services.CostMeter
import homer.app.services.CronSchedulerService
import homer.app.services.DispatchService
import homer.app.services.DispatchSweepService
import homer.app.services.GraphInspectRunner
import homer.app.services.LangGraphInspectService
import homer.app.services.LangGraphRunStatusService
import homer.app.services.LlmEventStreamService
import homer.app.services.LlmTelemetryService
import homer.app.services.LogTailService
import homer.app.services.MaintenanceModeService
import homer.app.services.McpToolFactory
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessGraphInspectRunner
import homer.app.services.ProcessManager
import homer.app.services.QuestionsMcpToolFactory
import homer.app.services.QuestionsService
import homer.app.services.ResultTokenService
import homer.app.services.RunMessagesService
import homer.app.services.StartupReconciliation
import homer.app.services.WorkspacePurgeService
import homer.app.services.persistence.ContinuationRepository
import homer.app.services.persistence.CostRepository
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.QuestionsRepository
import homer.app.services.persistence.ResultTokenRepository
import homer.app.services.persistence.RunMessagesRepository
import homer.app.services.persistence.ServerMetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import kotlin.io.path.Path

/**
 * Main Koin DI module for the application
 * Defines all service dependencies and their lifecycles
 */
val appModule =
    module {
        // Logger factory - provides logger for each class
        factory { (name: String) ->
            LoggerFactory.getLogger(name)
        }

        // Configuration
        single { ExecutionConfig() }

        // Core Services - Interfaces to Implementations
        single<TelemetryService> {
            val appConfig = get<AppConfig>()
            OtelTelemetryService(appConfig.langfuse, appConfig.openTelemetryExport)
        }

        single<TextCompiler> { DefaultTextCompiler() }

        single<LangfuseClient> {
            DefaultLangfuseClient(get<AppConfig>().langfuse, get())
        }

        // File Operations
        single<FileOperations> { LocalFileOperations() }

        // Additional Services
        single {
            LlmTelemetryService(
                telemetryService = get<TelemetryService>(),
                logger = get { parametersOf("homer.app.services.LlmTelemetryService") },
            )
        }

        single { ProcessEventBroadcaster() }

        single { InputSchemaValidator() }

        single {
            val appConfig = get<AppConfig>()
            AgentsService(
                agentsSearchPath = appConfig.agentsSearchPath,
                logger = get { parametersOf("homer.app.services.AgentsService") },
                broadcaster = get(),
                maxConfigFileBytes = appConfig.server.maxConfigFileBytes,
                inputSchemaValidator = get(),
                inputResolver = get(),
                runnerAliases = setOf(LocalRunner.ALIAS) + get<AppConfig>().runners.keys,
                runnerTagSets = {
                    val cfg = get<AppConfig>()
                    buildList {
                        if (cfg.server.localRunner.tags
                                .isNotEmpty()
                        ) {
                            add(
                                cfg.server.localRunner.tags
                                    .toSet(),
                            )
                        }
                        cfg.runners.values.forEach { r ->
                            when (r) {
                                is RunnerConfig.Kubernetes -> {
                                    if (r.tags.isNotEmpty()) add(r.tags.toSet())
                                }

                                is RunnerConfig.Ssh -> {
                                    if (r.tags.isNotEmpty()) add(r.tags.toSet())
                                }

                                is RunnerConfig.External -> {
                                    r.allowedTags?.let { add(it.toSet()) }
                                }
                            }
                        }
                        get<RunnerRegistry>().externalDomains.forEach { addAll(it.instanceTagSets()) }
                    }
                },
            )
        }

        single {
            AgentInputResolver(
                inputSchemaValidator = get(),
            )
        }

        single {
            val appConfig = get<AppConfig>()
            AgentFilesService(
                agentsService = get(),
                logger = get { parametersOf("homer.app.services.AgentFilesService") },
                maxConfigFileBytes = appConfig.server.maxConfigFileBytes,
            )
        }

        single<GraphInspectRunner> { ProcessGraphInspectRunner() }

        single { LangGraphInspectService(runner = get()) }

        single<GraphStateRunner> { ProcessGraphStateRunner() }

        single {
            LangGraphRunStatusService(
                fileOperations = get(),
                stateRunner = get(),
                appConfig = get(),
                continuationRepository = get(),
                processManager = get(),
            )
        }

        single {
            val config = get<AppConfig>()
            WorkspaceConfigFactory(
                workspaceRoot = config.workspaceRootPath,
                globalEnv = config.globalEnv,
                textCompiler = get(),
                parentEnvAllowlist = config.parentEnvAllowlist,
                hostEnvSupplier = { System.getenv() },
                maxOutputBytes = config.server.maxOutputBytes,
                sysEnvBackedGlobalEnvNames = config.sysEnvBackedGlobalEnvNames,
                sysEnvBackedRunnerEnvNames = config.sysEnvBackedRunnerEnvNames,
            )
        }

        single<LockManager> {
            DefaultLockManager(get<ProcessManager>(), get { parametersOf("homer.app.executors.DefaultLockManager") })
        }

        single<ParallelRunsManager> {
            DefaultParallelRunsManager()
        }

        single { PasswordVerifier() }

        single { Authorizer.from(get()) }

        single { ActiveSessionsRegistry() }
        single { SweepStatus() }

        single {
            KubernetesRunnerStartupSweep(
                runners = get<RunnerRegistry>(),
                activeRuns = get<ProcessManager>(),
                status = get(),
            )
        }

        single<RunnerStateSink> {
            val pm = get<ProcessManager>()
            RunnerStateSink { processId, update -> pm.setRunnerState(processId, update) }
        }

        single<RunnerRegistry> {
            val appConfig = get<AppConfig>()
            val stateSink = get<RunnerStateSink>()
            val tracer = get<TelemetryService>().tracer
            // Stable per-instance id scopes the k8s startup sweep to this instance's
            // pods. Resolved once, only when a kubernetes runner is configured, so a
            // non-k8s deployment never touches server_meta. configureServer calls
            // migrate() before the first injection, and building this registry is
            // reachable from the AgentsService scan — the first injection — so the
            // read is safe however early the registry is built. See ADR-0020.
            val k8sInstanceId =
                if (appConfig.runners.values.any { it is RunnerConfig.Kubernetes }) {
                    get<ServerMetaRepository>().getOrCreateInstanceId()
                } else {
                    ""
                }
            buildRunnerRegistry(appConfig, stateSink, tracer, k8sInstanceId)
        }

        single {
            ProcessFactory(
                workspaceConfigFactory = get(),
                executorFactory = get(),
                telemetryService = get<TelemetryService>(),
                lockManager = get(),
                parallelRunsManager = get(),
                config = get(),
                processManager = get<ProcessManager>(),
                runnerRegistry = get(),
                activeSessions = get(),
                questionsService = get<QuestionsService>(),
                questionsWorkspaceSetup = get(),
            )
        }

        // Persistence layer (SQLite-backed durable state). See ADR-0020.
        single {
            DatabaseService(
                databasePath = get<AppConfig>().databasePath,
                logger = get { parametersOf("homer.app.services.persistence.DatabaseService") },
            )
        }

        single { ProcessJobRegistry() }

        single { ProcessRepository(db = get(), jobRegistry = get()) }

        single { QuestionsRepository(db = get()) }

        single { RunMessagesRepository(db = get()) }

        single { DispatchRepository(db = get()) }

        single { ContinuationRepository(db = get()) }

        single { ResultTokenRepository(db = get()) }

        single { CostRepository(db = get()) }

        single {
            ServerMetaRepository(
                db = get(),
                logger = get { parametersOf("homer.app.services.persistence.ServerMetaRepository") },
            )
        }

        single {
            val repository = get<ServerMetaRepository>()
            // The persisted flag is read once, blocking, while the service is built — before
            // traffic, after migrate(). See ADR-0020, ADR-0092.
            MaintenanceModeService(
                repository = repository,
                config = get<AppConfig>().server.maintenance,
                initialEnabled = repository.readMaintenanceMode(),
                logger = get { parametersOf("homer.app.services.MaintenanceModeService") },
            )
        }

        single {
            val config = get<AppConfig>()
            val questionsService = get<QuestionsService>()
            ProcessManager(
                repository = get(),
                jobRegistry = get(),
                logger = get { parametersOf("homer.app.services.ProcessManager") },
                broadcaster = get(),
                workspaceRoot = Path(get<TextCompiler>().replaceArguments(config.workspaceRootPath, config.globalEnv)),
                onProcessEvicted = questionsService::removeForProcess,
                maxProcesses = config.server.maxProcesses,
            )
        }

        single {
            StartupReconciliation(
                processRepository = get(),
                questionsService = get(),
                runMessagesService = get<RunMessagesService>(),
                dispatchService = get(),
                continuationService = get(),
                agentsService = get(),
                logger = get { parametersOf("homer.app.services.StartupReconciliation") },
            )
        }

        single {
            CostMeter(repository = get(), limits = get<AppConfig>().costLimits)
        }

        single {
            CooldownGate(repository = get())
        }

        single {
            AgentRunStarter(
                serverId = homer.app.serverId,
                processExecutor = get(),
                processFactory = get(),
                telemetryService = get<TelemetryService>(),
                costMeter = get(),
                maxInvocationDepth = get<AppConfig>().server.maxAgentInvocationDepth,
                cooldownGate = get(),
                maintenanceGate = get(),
            )
        }

        single {
            McpToolFactory(
                agentsService = get(),
                authorizer = get(),
                agentInputResolver = get(),
                agentRunStarter = get(),
                processManager = get(),
                artifactService = get(),
                processEventBroadcaster = get(),
                mcpConfig = get<AppConfig>().server.mcp,
                logger = get { parametersOf("homer.app.services.McpToolFactory") },
            )
        }

        single {
            QuestionsService(
                repository = get(),
                logger = get { parametersOf("homer.app.services.QuestionsService") },
                broadcaster = get(),
                config = get<AppConfig>().server.questions,
            )
        }

        single {
            RunMessagesService(
                repository = get(),
                broadcaster = get(),
                config = get<AppConfig>().server.messages,
                logger = get { parametersOf("homer.app.services.RunMessagesService") },
            )
        }

        single {
            DispatchService(
                repository = get(),
                questionsService = get(),
                agentsService = get(),
                authorizer = get(),
                agentRunStarter = get(),
                agentInputResolver = get(),
                processManager = get(),
                broadcaster = get(),
                logger = get { parametersOf("homer.app.services.DispatchService") },
                config = get<AppConfig>().server.dispatch,
            )
        }

        single {
            DispatchSweepService(
                dispatchService = get(),
                intervalSec = get<AppConfig>().server.dispatch.sweepIntervalSec,
                logger = get { parametersOf("homer.app.services.DispatchSweepService") },
            )
        }

        single {
            ContinuationService(
                repository = get(),
                questionsService = get(),
                agentsService = get(),
                authorizer = get(),
                agentRunStarter = get(),
                agentInputResolver = get(),
                processManager = get(),
                resultTokenService = get(),
                logger = get { parametersOf("homer.app.services.ContinuationService") },
                config = get<AppConfig>().server.dispatch,
            )
        }

        single {
            ResultTokenService(
                repository = get(),
                logger = get { parametersOf("homer.app.services.ResultTokenService") },
                config = get<AppConfig>().server.dispatch,
            )
        }

        single {
            ContinuationSweepService(
                continuationService = get(),
                resultTokenService = get(),
                intervalSec = get<AppConfig>().server.dispatch.sweepIntervalSec,
                logger = get { parametersOf("homer.app.services.ContinuationSweepService") },
            )
        }

        single {
            QuestionsWorkspaceSetup(mcpEnabled = get<AppConfig>().server.mcp.enabled)
        }

        single {
            QuestionsMcpToolFactory(
                questionsService = get(),
                questionsConfig = get<AppConfig>().server.questions,
                dispatchService = get(),
                runMessagesService = get(),
                logger = get { parametersOf("homer.app.services.QuestionsMcpToolFactory") },
            )
        }

        single {
            LogTailService(
                processManager = get(),
                logger = get { parametersOf("homer.app.services.LogTailService") },
            )
        }

        single {
            LlmEventStreamService(
                processManager = get(),
                // Translation reads no adapter config, so bare adapters suffice here.
                harnesses = listOf(ClaudeHarness(), CodexHarness(), OpencodeHarness()).associateBy { it.id },
                logger = get { parametersOf("homer.app.services.LlmEventStreamService") },
                pollIntervalMs =
                    get<AppConfig>()
                        .server.claudeStreamPollMs
                        .coerceIn(50, 2000)
                        .toLong(),
            )
        }

        single {
            val appConfig = get<AppConfig>()
            ArtifactService(
                processManager = get(),
                fileOperations = get(),
                logger = get { parametersOf("homer.app.services.ArtifactService") },
                maxArtifactBytes = appConfig.server.maxArtifactBytes,
            )
        }

        single<CoroutineScope>(named("applicationScope")) {
            // Shared scope for all background coroutines that must drain on
            // shutdown (ProcessExecutor.coroutineScope.launch jobs and the LLM
            // cost settlements of ADR-0089).
            // SupervisorJob so one failed child doesn't cancel its siblings.
            // Audit P0 #3.
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        single {
            ProcessExecutor(
                telemetryService = get<TelemetryService>(),
                processManager = get<ProcessManager>(),
                questionsService = get<QuestionsService>(),
                runMessages = get<RunMessagesService>(),
                coroutineScope = get<CoroutineScope>(named("applicationScope")),
                logger = get { parametersOf("homer.app.executors.ProcessExecutor") },
                // Resolved at call time, not construction, to break the cycle
                // ContinuationService → AgentRunStarter → ProcessExecutor. See ADR-0023.
                onTerminal = { pid, status -> get<ContinuationService>().onProcessTerminal(pid, status) },
            )
        }

        single {
            CronSchedulerService(
                agentsService = get(),
                agentRunStarter = get(),
                agentInputResolver = get(),
                maintenanceMode = get(),
                logger = get { parametersOf("homer.app.services.CronSchedulerService") },
            )
        }

        single {
            val config = get<AppConfig>()
            WorkspacePurgeService(
                processManager = get(),
                questionsService = get(),
                workspaceRoot = Path(get<TextCompiler>().replaceArguments(config.workspaceRootPath, config.globalEnv)),
                maxRetainedArtifactBytes = config.server.maxRetainedArtifactBytes,
                logger = get { parametersOf("homer.app.services.WorkspacePurgeService") },
            )
        }

        single {
            RunAgentExecutor(
                subRuns =
                    AgentSubRunStarter(
                        // Resolved lazily — breaks the DI cycle RunAgentExecutor →
                        // AgentRunStarter → ProcessFactory → CommandExecutorFactory →
                        // RunAgentExecutor. See ADR-0030.
                        agentRunStarter = { get() },
                        agentsService = get(),
                        authorizer = get(),
                        processManager = get(),
                    ),
                agentInputResolver = get(),
                textCompiler = get(),
            )
        }

        single { LangGraphExecutor(fileOperations = get(), appConfig = get()) }

        single {
            RunnerCmdExecutor(
                runners = get<RunnerRegistry>(),
                activeSessions = get(),
                fileOperations = get(),
            )
        }

        single {
            LlmCostResolver(get<AppConfig>().llmPrice?.sessionSpend)
        }

        single {
            CommandExecutorFactory(
                get(),
                get<LlmTelemetryService>(),
                get(),
                get<ProcessManager>(),
                get<CostMeter>(),
                settlementScope = get<CoroutineScope>(named("applicationScope")),
                llmCostResolver = get(),
                runAgentExecutor = get<RunAgentExecutor>(),
                langGraphExecutor = get<LangGraphExecutor>(),
                runnerCmdExecutor = get<RunnerCmdExecutor>(),
                requireClaudeCodeVersion = get<AppConfig>().server.requireClaudeCodeVersion,
                claudeDropSystemSubtypes = get<AppConfig>().server.claudeDropSystemSubtypes.toSet(),
            )
        }
    }
