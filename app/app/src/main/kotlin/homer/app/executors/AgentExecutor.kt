package homer.app.executors

import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.OutputCapExceededException
import homer.app.infra.AgentDirs
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.ResultToEnvMapper
import homer.app.infra.SecretRedactor
import homer.app.infra.TelemetryService
import homer.app.infra.TextCompiler
import homer.app.infra.WhenEvaluator
import homer.app.infra.Workspace
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.ExecutionInfo
import homer.app.models.RunnerState
import homer.app.models.kind
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.DownloadMode
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import homer.app.runners.TarStreams
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo
import kotlin.system.measureTimeMillis
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Orchestrates agent execution by delegating command execution to specific executors
 */
class AgentExecutor(
    private val agent: Agent,
    private val workspace: Workspace,
    private val executorFactory: CommandExecutorFactory,
    private val telemetryService: TelemetryService,
    private val config: ExecutionConfig,
    private val parentContext: Context,
    private val stepRecords: StepRecords,
    private val processId: Int,
    private val runner: Runner,
    private val activeSessions: ActiveSessionsRegistry,
    private val commandIndices: List<Int>? = null,
    private val owner: String? = null,
) {
    // Not a constructor parameter — no caller has ever needed a different TextCompiler.
    private val textCompiler: TextCompiler = DefaultTextCompiler()

    @OptIn(ExperimentalTime::class)
    @Suppress("SuspendFunSwallowedCancellation") // rethrows; failsTheRun tells a kill from a failure
    suspend fun execute() {
        val sessionParams =
            RunnerSessionParams(
                processId = processId,
                agentName = agent.name,
                workspace = workspace.config,
                env = workspace.config.env,
                parentContext = parentContext,
            )
        logger.debug("Process {}: opening session on runner '{}'", processId, runner.alias)
        val session = runner.openSession(sessionParams)
        activeSessions.add(session)
        if (session.env.isNotEmpty()) {
            workspace.appendToEnv(session.env)
        }
        try {
            prepareBackendWorkspace(session)

            val selectedIndices = commandIndices?.toSet()
            // Materialized so a boundary can name its successor: `index + 1` is wrong when
            // a skipped (unselected) index sits between them.
            val steps =
                agent.commands.withIndex().filter { (index, _) -> selectedIndices == null || index in selectedIndices }
            var pendingStart: Long? = null
            for ((step, indexedCommand) in steps.withIndex()) {
                val handover = runStep(session, step, indexedCommand, steps.getOrNull(step + 1)?.value, pendingStart)
                pendingStart = handover.nextStart
                if (handover.finishRun) return
            }
        } catch (e: Throwable) {
            // A kill is not a failure; the output-cap subclass of CancellationException is.
            // A plain CancellationException can also arrive from a child (e.g. a runner-enforced
            // timeout) without our own job being cancelled — detect a real kill by checking
            // whether our coroutine is still active, not just the exception type.
            if (failsTheRun(e)) {
                runCatching { session.markFailed() }
            }
            throw e
        } finally {
            withContext(NonCancellable) { syncDownAndClose(session) }
        }
    }

    /**
     * Runs the [step]-th selected command, [carriedStart] being the start time the previous step's
     * boundary recorded; the handover says when the next step starts and whether the run finishes here.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun runStep(
        session: RunnerSession,
        step: Int,
        indexedCommand: IndexedValue<Command>,
        nextCommand: Command?,
        carriedStart: Long?,
    ): StepHandover {
        val (index, command) = indexedCommand
        val startTime = carriedStart ?: Clock.System.now().epochSeconds
        // Only the run's first step is set here: a later step's start rides the
        // previous step's boundary transaction, so a step boundary is one write.
        if (step == 0) {
            stepRecords.setCurrentCommand(processId, command.label, startTime)
        }
        val commandSpan = startCommandSpan(session, index, command)

        val (artifactNames, artifactNameFailure, stdOut, stdErr) = stepArtifacts(agent, textCompiler, workspace, index)
        // Attempt-0 paths, so the console live tail (LogTailService) can follow this
        // command as soon as it starts; LlmHarnessExecutor's retry loop updates this per
        // attempt below.
        val executionContextRedactor = SecretRedactor.from(workspace.config.env, workspace.config.secretEnvNames)
        stepRecords.setCommandArtifacts(
            processId,
            index,
            CommandArtifactPaths(stdOut, stdErr, kind = command.kind, redactor = executionContextRedactor),
        )

        val executionContext =
            ExecutionContext(
                workspace = workspace,
                config = config,
                shell = session.shell,
                stdOut = stdOut,
                stdErr = stdErr,
                processId = processId,
                agentName = agent.name,
                cmdIndex = index,
                owner = owner,
                artifactNames = artifactNames,
            )

        workspace.appendToEnv(mapOf(EnvConstants.AGENT_NAME to agent.name))

        // Conditional execution: evaluate `when` predicate (if any) against the
        // post-transformation env. A Skip result records its own ExecutionInfo
        // and continues; an EvalError (like artifactNameFailure above) — including
        // one the evaluator derived from a predicate timeout — is stored and
        // re-thrown inside the try/catch below so it goes through the standard
        // telemetry / ExecutionInfo path.
        val whenResult = evaluateWhen(command, session, index)

        if (whenResult is WhenEvaluator.Result.Skip) {
            return StepHandover(recordSkip(command, startTime, nextCommand, executionContext, commandSpan, whenResult), finishRun = false)
        }
        return runCommand(
            StepRun(index, command, nextCommand, startTime, commandSpan, stdOut, stdErr, executionContext),
            artifactNameFailure,
            whenResult,
        )
    }

    /** Records a step its `when` predicate skipped; returns the next step's start time, if one starts now. */
    @OptIn(ExperimentalTime::class)
    private suspend fun recordSkip(
        command: Command,
        startTime: Long,
        nextCommand: Command?,
        executionContext: ExecutionContext,
        commandSpan: Span,
        whenResult: WhenEvaluator.Result.Skip,
    ): Long? {
        var nextStart: Long? = null
        withContext(NonCancellable) {
            val endTime = Clock.System.now().epochSeconds
            // Operator-authored text (when expression, Command.toString()) can carry
            // a secret; it lands in ExecutionInfo.errMsg and the status API.
            // CWE-532
            val redactor = executionContext.secretRedactor()
            // A skip still hands over to the next step: its boundary sets that
            // step's currentCommand, in the same transaction as this append.
            val next = nextBoundary(nextCommand, stop = false)
            if (next != null) nextStart = next.second
            stepRecords.completeStep(
                processId,
                ExecutionInfo(
                    label = command.label,
                    start = startTime,
                    end = endTime,
                    resultCode = -1,
                    stdOut = null,
                    stdErr = null,
                    errMsg = redactor.redact(whenResult.reason),
                    skipped = true,
                    kind = command.kind,
                ),
                next?.first,
                next?.second,
            )
            commandSpan.setAttribute("cmd.skipped", true)
            commandSpan.setStatus(StatusCode.OK)
            commandSpan.end()
        }
        return nextStart
    }

    /** Runs [run]'s command through its executor and records its ExecutionInfo, whatever the outcome. */
    @OptIn(ExperimentalTime::class)
    @Suppress("SuspendFunSwallowedCancellation") // records the outcome and rethrows
    private suspend fun runCommand(
        run: StepRun,
        artifactNameFailure: Result<Pair<Path, Path>>,
        whenResult: WhenEvaluator.Result?,
    ): StepHandover {
        val (index, command, nextCommand, startTime, commandSpan, stdOut, stdErr, executionContext) = run
        var nextStart: Long? = null
        // Failure-shaped default so an Error escaping the try can't record a success-looking ExecutionInfo.
        var outcome: CommandOutcome = CommandOutcome.Failure(errMsg = null, exitCode = -1, outputTruncated = false)
        // Null until the executor actually returns a result (e.g. an EvalError or an
        // exception before executor.execute means the command never ran) — the finally
        // block falls back to the attempt-0 stdOut/stdErr above in that case.
        var executedResult: CommandResult? = null

        try {
            artifactNameFailure.onFailure { throw it }
            val redactor = executionContext.secretRedactor()
            if (whenResult is WhenEvaluator.Result.EvalError) {
                throw CommandExecutionException(
                    message =
                        "when-predicate evaluation failed for '${redactor.redact(whenResult.expression)}': " +
                            redactor.redact(whenResult.stderrSummary),
                    exitCode = whenResult.exitCode,
                    command = redactor.redact(command.toString()),
                )
            }
            // Get appropriate executor for this command type
            val executor = executorFactory.getExecutor(command)
            val result = executor.execute(command, executionContext, commandSpan)
            executedResult = result

            commandSpan.setAttribute("cmd.type", command::class.simpleName ?: "")
            commandSpan.setAttribute("output.exitCode", result.exitCode.toString())
            val envFromResult =
                ResultToEnvMapper(result).mapToEnv(
                    command.resultEnvParam,
                )
            commandSpan.setAttribute(
                "output.env",
                executionContext.secretRedactor().redact(
                    envFromResult.map { "${it.key}=${it.value}" }.joinToString(", "),
                ),
            )

            val isFinishCode = finishCodeOrThrow(command, result, redactor)

            workspace.appendToEnv(envFromResult)
            commandSpan.setStatus(StatusCode.OK)
            outcome = CommandOutcome.Success(exitCode = result.exitCode, isFinishCode = isFinishCode)
        } catch (e: Exception) {
            outcome = failureOutcome(e)
            withContext(NonCancellable) {
                commandSpan.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
                commandSpan.recordException(e)
            }
            throw e
        } finally {
            withContext(NonCancellable) { nextStart = completeCommand(run, outcome, executedResult) }
        }

        return StepHandover(nextStart, finishRun = !command.continueIfFail && outcome.isFinishCode)
    }

    private fun startCommandSpan(
        session: RunnerSession,
        index: Int,
        command: Command,
    ): Span {
        val commandSpan =
            telemetryService.tracer
                .spanBuilder("command_execution_${index + 1}")
                .setParent(parentContext)
                .startSpan()
        commandSpan.setAttribute("runner.alias", session.runnerAlias)
        // The step's own name, next to the index the span is named after: an LLM
        // step's generation carries it too, a script step's span is the only place
        // it appears.
        commandSpan.setAttribute("command.label", command.label)
        session.metadata.forEach { (key, value) -> commandSpan.setAttribute(key, value) }
        return commandSpan
    }

    /**
     * Records [run]'s ExecutionInfo for [outcome] and ends its span; returns the next step's start
     * time when one starts now.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun completeCommand(
        run: StepRun,
        outcome: CommandOutcome,
        executedResult: CommandResult?,
    ): Long? {
        val (index, command, nextCommand, startTime, commandSpan, stdOut, stdErr) = run
        val endTime = Clock.System.now().epochSeconds
        // Record the executor-returned result's actual paths when one exists —
        // with a per-attempt artifactNameTemplate a retried Claude/Codex command's
        // winning attempt writes to a different file than the attempt-0 paths
        // built above. A thrown exception on the final attempt (e.g.
        // MalformedLlmOutputException) means executedResult is never set even
        // though the retry loop ran and wrote to later attempts' files — prefer
        // the tracked per-attempt paths LlmHarnessExecutor kept current
        // on StepRecords over the stale attempt-0 fallback. Only an error before
        // the executor ever ran (e.g. an EvalError) falls through to attempt-0.
        val recordedStdOut =
            executedResult?.stdOut ?: stepRecords.getCommandArtifacts(processId, index)?.stdOut ?: stdOut
        val recordedStdErr =
            executedResult?.stdErr ?: stepRecords.getCommandArtifacts(processId, index)?.stdErr ?: stdErr
        val executionInfo =
            ExecutionInfo(
                label = command.label,
                start = startTime,
                end = endTime,
                resultCode = outcome.exitCode,
                stdOut = recordedStdOut.relativeTo(workspace.config.artifacts),
                stdErr = recordedStdErr.relativeTo(workspace.config.artifacts),
                errMsg = (outcome as? CommandOutcome.Failure)?.errMsg,
                outputTruncated = (outcome as? CommandOutcome.Failure)?.outputTruncated ?: false,
                llmAccounting = executedResult?.llmAccounting,
                kind = command.kind,
            )
        // The step is done, so the current command clears unless the next
        // step starts now. Success and skip share nextBoundary, so the
        // two paths cannot drift.
        val next = nextBoundary(nextCommand, stop = shouldStopAfter(outcome, command))
        stepRecords.completeStep(processId, executionInfo, next?.first, next?.second)
        commandSpan.end()
        return next?.second
    }

    /** Syncs the backend's artifacts (and a kept workdir) down, cleans a cleared one, and closes [session]. */
    @Suppress("SuspendFunSwallowedCancellation") // best-effort, and only ever runs under NonCancellable
    private suspend fun syncDownAndClose(session: RunnerSession) {
        // Sync-down is best-effort: never fail an otherwise-successful run.
        runCatching {
            val elapsedMs =
                measureTimeMillis { session.download("artifacts", workspace.config.artifacts, DownloadMode.OVERLAY) }
            logger.debug("Process {}: artifacts sync-down took {} ms", processId, elapsedMs)
        }.onFailure { logger.error("Sync-down of artifacts failed for process {}: {}", processId, it.message, it) }
        if (!workspace.config.clearAfterFinish) {
            runCatching {
                // MIRROR: a kept workdir must reflect the remote exactly — a file the
                // agent deleted remotely must not survive locally. See ADR-0055.
                val elapsedMs =
                    measureTimeMillis { session.download("workdir", workspace.config.workdir, DownloadMode.MIRROR) }
                logger.debug("Process {}: workdir sync-down took {} ms", processId, elapsedMs)
            }.onFailure { logger.error("Sync-down of workdir failed for process {}: {}", processId, it.message, it) }
        } else {
            runCatching { session.cleanWorkspace() }
                .onFailure { logger.warn("Backend workspace cleanup failed for process {}: {}", processId, it.message) }
        }
        activeSessions.remove(session)
        runCatching { session.close() }
    }

    /** The command's `when` predicate evaluated on [session] against the current env; null when it has none. */
    private suspend fun evaluateWhen(
        command: Command,
        session: RunnerSession,
        index: Int,
    ): WhenEvaluator.Result? {
        val clause = command.whenClause ?: return null
        return WhenEvaluator(
            shell = session.shell,
            artifactsDir = workspace.config.artifacts,
        ).evaluate(clause, workspace.config.env, index)
    }

    /**
     * Creates the backend workspace, records the serving backend, and uploads the project
     * root, the local agent dir and the session's extra uploads to it.
     */
    private suspend fun prepareBackendWorkspace(session: RunnerSession) {
        // Must be read BEFORE createWorkspace(): its overrides rewrite
        // HMR_AGENT_DIR to the backend-side path, but the upload source
        // is the local agent dir.
        val localAgentDir = workspace.config.env[EnvConstants.AGENT_DIR]
        val workspaceOverrides = session.createWorkspace()
        logger.debug("Process {}: backend workspace ready on '{}'", processId, session.runnerAlias)
        // The serving backend is only known now: for a tag-filtered run the queued
        // alias is a filter string ("tags:..."), not a backend (ADR-0066). Merge
        // rather than replace so the Kubernetes runner's pod fields survive.
        stepRecords.setRunnerState(processId) { prev ->
            prev?.copy(alias = session.runnerAlias) ?: RunnerState(alias = session.runnerAlias)
        }
        if (workspaceOverrides.isNotEmpty()) {
            workspace.appendToEnv(workspaceOverrides)
        }
        val uploadMs =
            measureTimeMillis {
                // projectRoot is created per run by Homer: a symlink there is tampering,
                // so it is deliberately NOT resolved. The other two are operator-configured
                // and may legitimately be symlinks (a symlinked agent dir, an extraUploads
                // entry — ADR-0042); TarStreams.pack refuses a symlinked source, so resolve
                // them here and let containment be checked against the real root.
                session.upload(workspace.config.projectRoot, ".")
                localAgentDir?.let { agentDir ->
                    val source = TarStreams.resolvedPackSource(Path.of(agentDir))
                    // A nested agent is a different agent: a parent's HMR_AGENT_DIR
                    // upload carries none of its children's directories (ADR-0068,
                    // ADR-0047). Leading `/` anchors each exclude to the upload root;
                    // a bare name would match a same-named directory at any depth.
                    val nestedExcludes =
                        AgentDirs.nestedAgentDirsUnder(source).map { "/${it.invariantSeparatorsPathString}" }
                    session.upload(source, ".homer/agent", nestedExcludes)
                }
                session.extraUploads.forEach { session.upload(TarStreams.resolvedPackSource(Path.of(it.local)), it.remote) }
            }
        logger.debug(
            "Process {}: workspace upload to '{}' took {} ms ({} extra uploads)",
            processId,
            session.runnerAlias,
            uploadMs,
            session.extraUploads.size,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentExecutor::class.java)
    }
}

/**
 * The next step's current-command label and start time, or null when this
 * step ends the run or has no successor. Both the success path (via
 * [shouldStopAfter]) and the skip path share this one handover.
 */
private fun nextBoundary(
    nextCommand: Command?,
    stop: Boolean,
): Pair<String, Long>? {
    if (stop || nextCommand == null) return null
    return nextCommand.label to Clock.System.now().epochSeconds
}

/**
 * True when the step's outcome ends the run, so no later step starts and the
 * boundary clears the current command. A failure always stops: the catch block
 * rethrows unconditionally, so a Failure outcome ends the run whatever
 * `continueIfFail` says — `continueIfFail` only covers a non-zero exit code
 * reaching the outcome as Success. A `finishExitCode` match stops unless
 * `continueIfFail`.
 */
private fun shouldStopAfter(
    outcome: CommandOutcome,
    command: Command,
): Boolean =
    when (outcome) {
        is CommandOutcome.Failure -> true
        is CommandOutcome.Success -> !command.continueIfFail && outcome.isFinishCode
    }

/** True when [e] ends the run as a failure rather than a kill (see the catch in [AgentExecutor.execute]). */
private suspend fun failsTheRun(e: Throwable): Boolean =
    e !is CancellationException || e is OutputCapExceededException || currentCoroutineContext().isActive

/** The step outcome [e] records: its exit code when it carries one, and whether output hit the cap. */
private fun failureOutcome(e: Exception): CommandOutcome.Failure =
    CommandOutcome.Failure(
        errMsg = e.message,
        exitCode = if (e is CommandExecutionException) e.exitCode else -1,
        outputTruncated = e is OutputCapExceededException,
    )

/**
 * Whether [result] exited with the command's `finishExitCode`; throws when a non-zero
 * exit code must fail the step (no `continueIfFail`, not the finish code).
 */
private fun finishCodeOrThrow(
    command: Command,
    result: CommandResult,
    redactor: SecretRedactor,
): Boolean {
    val isFinishCode = command.finishExitCode != null && result.exitCode == command.finishExitCode
    if (!command.continueIfFail && result.exitCode != 0 && !isFinishCode) {
        throw CommandExecutionException(
            message = "Execution failed",
            exitCode = result.exitCode,
            command = redactor.redact(command.toString()),
        )
    }
    return isFinishCode
}

/**
 * Per-command outcome threaded from the try/catch into the finally block and the
 * post-try early-finish check. Replaces the four mutable flags that previously
 * carried this state.
 */
private sealed interface CommandOutcome {
    val exitCode: Int

    data class Success(
        override val exitCode: Int,
        val isFinishCode: Boolean,
    ) : CommandOutcome

    data class Failure(
        val errMsg: String?,
        override val exitCode: Int,
        val outputTruncated: Boolean,
    ) : CommandOutcome
}

/** What a step hands the loop: the next step's start time when one starts now, and whether the run ends here. */
private data class StepHandover(
    val nextStart: Long?,
    val finishRun: Boolean,
)

/** One step's command and the per-step state built before it runs. */
private data class StepRun(
    val index: Int,
    val command: Command,
    val nextCommand: Command?,
    val startTime: Long,
    val commandSpan: Span,
    val stdOut: Path,
    val stdErr: Path,
    val executionContext: ExecutionContext,
)

/** Step [index]'s artifact names and attempt-0 paths: the default stem's when [agent]'s own template fails to render. */
private fun stepArtifacts(
    agent: Agent,
    textCompiler: TextCompiler,
    workspace: Workspace,
    index: Int,
): StepArtifacts {
    val artifactNames =
        ArtifactNameResolver(
            template = agent.artifactNameTemplate,
            textCompiler = textCompiler,
            artifactsDir = workspace.config.artifacts,
            cmdIndex = index,
            envProvider = { workspace.config.env },
            secretEnvNames = workspace.config.secretEnvNames,
            secretRedactor =
                SecretRedactor.from(
                    workspace.config.env,
                    workspace.config.secretEnvNames,
                    minNeedleLength = SecretRedactor.ARTIFACT_STEM_MIN_NEEDLE_LENGTH,
                ),
        )
    // A rendered-stem validation failure (§12: the workspace env feeding the
    // template is attacker-influenced) must NOT bypass per-command bookkeeping —
    // caught here and re-thrown inside the try below, mirroring the EvalError
    // pattern just below, so it still gets an ExecutionInfo, a closed span, and
    // a cleared currentCommand. Placeholder attempt-0-shaped paths let
    // ExecutionContext/ExecutionInfo be built even though nothing was written.
    val artifactNameFailure = runCatching { artifactNames.stdOut(0) to artifactNames.stdErr(0) }
    val (stdOut, stdErr) =
        artifactNameFailure.getOrElse {
            // The agent's own template failed to render — fall back to the
            // default stem (never attacker-influenced: only HMR_CMD_INDEX).
            val defaultStem =
                textCompiler.replaceArguments(
                    EnvConstants.DEFAULT_ARTIFACT_NAME_TEMPLATE,
                    mapOf(EnvConstants.CMD_INDEX to index.toString()),
                )
            workspace.config.artifacts.resolve("$defaultStem.out") to
                workspace.config.artifacts.resolve("$defaultStem.err")
        }
    return StepArtifacts(artifactNames, artifactNameFailure, stdOut, stdErr)
}

/** A step's artifact names, the attempt-0 render (a failure re-thrown inside the step's try), and the paths used. */
private data class StepArtifacts(
    val names: ArtifactNameResolver,
    val failure: Result<Pair<Path, Path>>,
    val stdOut: Path,
    val stdErr: Path,
)
