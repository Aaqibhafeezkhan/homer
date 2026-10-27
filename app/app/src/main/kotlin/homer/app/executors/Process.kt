package homer.app.executors

import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.infra.PurgeSchedule
import homer.app.infra.QuestionsWorkspaceSetup
import homer.app.infra.SecretRedactor
import homer.app.infra.TelemetryService
import homer.app.infra.Workspace
import homer.app.infra.WorkspaceConfig
import homer.app.infra.WorkspaceConfigFactory
import homer.app.infra.langfuseTraceTags
import homer.app.models.Agent
import homer.app.models.ProcessStatus
import homer.app.models.TagValidation
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

class Process(
    private val workspaceConfigFactory: WorkspaceConfigFactory,
    private val executorFactory: CommandExecutorFactory,
    private val telemetryService: TelemetryService,
    private val config: ExecutionConfig,
    private val lockManager: LockManager,
    private val parallelRunsManager: ParallelRunsManager,
    private val processManager: RunWorkRecords,
    private val runnerRegistry: RunnerRegistry,
    private val activeSessions: ActiveSessionsRegistry,
    private val questionsService: QuestionRegistration,
    private val questionsWorkspaceSetup: QuestionsWorkspaceSetup,
    private val agent: Agent,
    val queryParams: Map<String, String>,
    val owner: String? = null,
    private val extraSecretInputKeys: Set<String> = emptySet(),
    private val parentContext: Context,
    private val commandIndices: List<Int>? = null,
    private val invocationChain: List<String> = emptyList(),
    private val ancestorLockKeys: List<String> = emptyList(),
    // Read by ProcessExecutor and stored with the row at INSERT. See ADR-0058.
    val parentProcessId: Int? = null,
    // The flow's first run, read by ProcessExecutor and stored with the row at
    // INSERT, exported as HMR_ROOT_PROCESS_ID. Null at the root. See ADR-0058.
    val rootProcessId: Int? = null,
    private val workspaceFactory: (WorkspaceConfig, Span, suspend () -> Boolean) -> Workspace =
        { config, span, isSharedWithLiveSibling -> Workspace(config, span, isSharedWithLiveSibling) },
) {
    val name = agent.name

    // Read by ProcessExecutor and handed to obtainProcessId, which enforces it in
    // the same transaction as the INSERT. See ADR-0051.
    val cooldown = agent.cooldown

    // Snapshot of which inputs were secret when this run started; stored with the
    // row so API redaction never depends on the agent's current flags. A replay
    // (retry/resume) adds the SOURCE run's snapshot via extraSecretInputKeys: it is
    // replaying that run's values, so it inherits that run's secrecy. ADR-0052.
    val secretInputKeys: Set<String> = agent.secretEnvNames + extraSecretInputKeys
    private val purgeSchedule = PurgeSchedule.of(agent.workspace)
    val purgeAfterSeconds: Long = purgeSchedule.purgeAfterSeconds
    val purgeRecordAfterSeconds: Long = purgeSchedule.purgeRecordAfterSeconds

    // Artifact globs that survive the workspace purge, captured here so the row
    // stores what was declared when this run ended, not the agent's current
    // definition (same principle as secretInputKeys / ADR-0052).
    val retainArtifacts: List<String> = purgeSchedule.retainArtifacts

    // Screens the stored failure reason (ADR-0052). Built here rather than in the failure
    // path: the workspace config it would otherwise be read from is created inside
    // `execute`, which a pre-start failure never reaches, and building it there would need
    // the runner lookup that may itself be what timed out. §12 rule 7, ADR-0052.
    val failureReasonRedactor: SecretRedactor = SecretRedactor.from(agent.env + queryParams, secretInputKeys + agent.sysEnvBackedEnvNames)

    // `paramName:value` subject tags resolved from this run's declared `fromInputs`,
    // stored co-transactionally with the row INSERT (ADR-0051). Shares the single
    // derivation with the Langfuse trace tags, and passes the ADR-0052 secret union
    // (secretInputKeys) so a historically-secret value is never stored as a tag.
    // See ADR-0060.
    val fromInputTags: List<String> = langfuseTraceTags(agent, queryParams, secretInputKeys)

    suspend fun execute(
        processId: Int,
        processSpan: Span,
    ) {
        // Resolved before createWorkspaceConfig: the runner declares whether it is
        // remote (so allowlisted host env stays off a remote machine) and supplies
        // its own per-runner env layer. See ADR-0003.
        val runner = runnerRegistry.forAgent(agent)
        val workspaceConfig =
            workspaceConfigFor(
                processId,
                runnerSecretEnvNames = runner.sysEnvBackedEnvNames,
                runnerEnv = runner.runnerEnv,
                runnerIsRemote = runner.isRemote,
                runnerAlias = runner.alias,
            )

        // Pass-through: a flow cannot self-collide on a shared lock key. When the
        // agent opts in and its resolved key is already held by an ancestor in
        // this invocation chain, skip acquire AND release entirely.
        val inheritLock = agent.lockStrategy.acceptParentLock && workspaceConfig.lockKey in ancestorLockKeys

        // Cleanup guard probe: live rows on this run's artifacts dir, excluding itself.
        val workspace =
            workspaceFactory(workspaceConfig, processSpan) {
                processManager.isWorkspaceSharedWithActiveRun(workspaceConfig.artifacts, processId)
            }
        val agentExecutor =
            AgentExecutor(
                agent = agent,
                workspace = workspace,
                executorFactory = executorFactory,
                telemetryService = telemetryService,
                config = config,
                parentContext = parentContext,
                stepRecords = processManager,
                processId = processId,
                runner = runner,
                activeSessions = activeSessions,
                commandIndices = commandIndices,
                owner = owner,
            )
        var acquiredSlot: Semaphore? = null
        try {
            acquiredSlot =
                parallelRunsManager.acquireSlot(agent.name, agent.maxParallelRuns, agent.onMaxParallelRuns, agent.maxQueuedRuns)
            if (!inheritLock) {
                lockManager.waitForLockOrThrow(workspaceConfig.lockKey, agent.lockStrategy.onCollision, processId)
            }
            startWorking(processId, processSpan, workspace)
            agentExecutor.execute()
        } finally {
            // NonCancellable: a killed run still cleans its workspace, whose sibling probe suspends.
            withContext(NonCancellable) {
                tearDown(processId, processSpan, workspace, workspaceConfig.lockKey.takeUnless { inheritLock }, acquiredSlot)
            }
        }
    }

    /** This run's workspace config, over the env layer and secret names its runner contributes. */
    private fun workspaceConfigFor(
        processId: Int,
        runnerSecretEnvNames: Set<String>,
        runnerEnv: Map<String, String>,
        runnerIsRemote: Boolean,
        runnerAlias: String,
    ): WorkspaceConfig =
        workspaceConfigFactory.createWorkspaceConfig(
            processId = processId,
            agentEnv = agent.env,
            lockKeyTemplate = agent.lockStrategy.key,
            queryParams = queryParams,
            workspaceAgentConfig = agent.workspace,
            invocationChain = invocationChain,
            ancestorLockKeys = ancestorLockKeys,
            parentProcessId = parentProcessId,
            rootProcessId = rootProcessId,
            // secretInputKeys, NOT a fresh read of the current flags: on a replay it
            // carries the source run's snapshot, and this set is what masks values in
            // span input/output and in the live log stream. Recomputing here is how a
            // historically-secret value reached those sinks unmasked. See ADR-0052.
            // Unioned with the queued runner's names: a tag-filtered run's alias
            // ("tags:...") has no entry in the factory's per-alias map, so the
            // candidates' @SYSENV-backed names must arrive through this set. ADR-0066.
            secretEnvNames = secretInputKeys + agent.sysEnvBackedEnvNames + runnerSecretEnvNames,
            runnerEnv = runnerEnv,
            includeParentEnv = !runnerIsRemote,
            runnerAlias = runnerAlias,
        )

    /**
     * Moves the run to WORKING once its slot and lock are held, then prepares its workspace:
     * the artifacts dir, the callback token in its env and the question tooling.
     */
    private suspend fun startWorking(
        processId: Int,
        processSpan: Span,
        workspace: Workspace,
    ) {
        // Guarded like ProcessExecutor's CREATED write: a kill that landed after the slot and
        // lock were taken must win, so the run stops here instead of running as WORKING.
        if (!processManager.compareAndSetStatus(processId, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING)) {
            throw CancellationException("Process $processId was killed before it started")
        }
        processSpan.setAttribute("process.status", ProcessStatus.CREATED.name)
        // Register the artifacts dir before creating it: the purge sweep's shared-dir
        // check must be able to see this run as an owner of the directory.
        processManager.setArtifactsDir(processId, workspace.config.artifacts)
        workspace.create()
        // The redactor screens runtime tag values against every secret this run holds
        // (secret inputs under the ADR-0052 union, @SYSENV globals, and the token
        // itself — appended to env only below, hence the explicit entry). See ADR-0060.
        val callbackToken =
            questionsService.registerProcess(processId, agent.name) { token ->
                SecretRedactor.from(
                    workspace.config.env + (EnvConstants.CALLBACK_TOKEN to token),
                    workspace.config.secretEnvNames,
                    minNeedleLength = TagValidation.SECRET_NEEDLE_MIN_LENGTH,
                )
            }
        workspace.appendToEnv(mapOf(EnvConstants.CALLBACK_TOKEN to callbackToken))
        questionsWorkspaceSetup.materialize(workspace)
    }

    @Suppress("SuspendFunSwallowedCancellation") // runs under NonCancellable (execute's finally)
    private suspend fun tearDown(
        processId: Int,
        processSpan: Span,
        workspace: Workspace,
        // Null when the lock is inherited from an ancestor: it was never acquired here.
        heldLockKey: String?,
        acquiredSlot: Semaphore?,
    ) {
        // Each teardown step is isolated: a cleanup failure must never skip the
        // lock/slot release below (a leaked lock or slot wedges every later run
        // of this agent), and must not mask the primary exception.
        runCatching { workspace.cleanup() }
            .onFailure { processSpan.recordException(it) }
        heldLockKey?.let { lockKey ->
            runCatching { lockManager.releaseLock(lockKey, processId) }
                .onFailure { processSpan.recordException(it) }
        }
        acquiredSlot?.let { slot ->
            runCatching { parallelRunsManager.releaseSlot(slot) }
                .onFailure { processSpan.recordException(it) }
        }
    }
}

internal class ProcessFactory(
    private val workspaceConfigFactory: WorkspaceConfigFactory,
    private val executorFactory: CommandExecutorFactory,
    private val telemetryService: TelemetryService,
    private val lockManager: LockManager,
    private val parallelRunsManager: ParallelRunsManager,
    private val config: ExecutionConfig,
    private val processManager: RunWorkRecords,
    private val runnerRegistry: RunnerRegistry,
    private val activeSessions: ActiveSessionsRegistry,
    private val questionsService: QuestionRegistration,
    private val questionsWorkspaceSetup: QuestionsWorkspaceSetup,
) {
    fun create(
        agent: Agent,
        queryParams: Map<String, String>,
        parentContext: Context,
        commandIndices: List<Int>? = null,
        invocationChain: List<String> = emptyList(),
        ancestorLockKeys: List<String> = emptyList(),
        owner: String? = null,
        // Defaulted, unlike langfuseTraceTags' secret set: omitting it yields the current
        // definition's flags, which is correct for every fresh-input path. Only a replay
        // of stored inputs has more to carry.
        extraSecretInputKeys: Set<String> = emptySet(),
        parentProcessId: Int? = null,
        rootProcessId: Int? = null,
    ): Process =
        Process(
            workspaceConfigFactory = workspaceConfigFactory,
            executorFactory = executorFactory,
            telemetryService = telemetryService,
            config = config,
            agent = agent,
            queryParams = queryParams,
            owner = owner,
            extraSecretInputKeys = extraSecretInputKeys,
            parentContext = parentContext,
            lockManager = lockManager,
            parallelRunsManager = parallelRunsManager,
            processManager = processManager,
            runnerRegistry = runnerRegistry,
            activeSessions = activeSessions,
            questionsService = questionsService,
            questionsWorkspaceSetup = questionsWorkspaceSetup,
            commandIndices = commandIndices,
            invocationChain = invocationChain,
            ancestorLockKeys = ancestorLockKeys,
            parentProcessId = parentProcessId,
            rootProcessId = rootProcessId,
        )
}
