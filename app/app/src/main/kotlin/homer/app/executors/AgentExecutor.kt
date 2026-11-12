package homer.app.executors

import homer.app.EnvConstants
import homer.app.config.ExecutionConfig
import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.OutputCapExceededException
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.SecretRedactor
import homer.app.infra.TelemetryService
import homer.app.infra.TextCompiler
import homer.app.infra.WhenEvaluator
import homer.app.infra.Workspace
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.ExecutionInfo
import homer.app.models.RunStop
import homer.app.models.StopReason
import homer.app.models.StopStepsOutcome
import homer.app.models.kind
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunDeadline
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
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
    /** The flow's first run id, handed to the runner so `runnerAffinity` can key on it. See ADR-0099. */
    private val rootProcessId: Int? = null,
) {
    // Not a constructor parameter — no caller has ever needed a different TextCompiler.
    private val textCompiler: TextCompiler = DefaultTextCompiler()

    @OptIn(ExperimentalTime::class)
    @Suppress("SuspendFunSwallowedCancellation") // rethrows; failsTheRun tells a kill from a failure
    suspend fun execute() {
        logger.debug("Process {}: opening session on runner '{}'", processId, runner.alias)
        val session = runner.openSession(sessionParams())
        activeSessions.add(session)
        if (session.env.isNotEmpty()) {
            workspace.appendToEnv(session.env)
        }
        // The backend's own run deadline, read once the workspace exists (the Kubernetes session
        // reads the pod's start time there). Null for every runner that imposes none.
        var runDeadline: RunDeadline? = null
        // The step in flight: a graceful stop names it and resumes its CLI session.
        val inFlight = StepInFlight()
        try {
            prepareBackendWorkspace(session, workspace, stepRecords, processId, logger)
            runDeadline = session.runDeadline
            runSteps(session, runDeadline, inFlight)
        } catch (e: Throwable) {
            // A kill is not a failure; the output-cap subclass of CancellationException is.
            // A plain CancellationException can also arrive from a child (e.g. a runner-enforced
            // timeout) without our own job being cancelled — detect a real kill by checking
            // whether our coroutine is still active, not just the exception type.
            if (failsTheRun(e)) {
                // The run's own graceful stop: a step that timed out with `onTimeout: stop`, or the
                // backend's soft run deadline. A kill — API, MCP, reload, lock collision — never
                // reaches it, and neither does an ordinary step failure. See ADR-0105.
                inFlight.step?.let { runStopPhase(session, runDeadline, it, e) }
                runCatching { session.markFailed() }
            }
            throw e
        } finally {
            withContext(NonCancellable) {
                syncDownAndClose(session, workspace, processId, logger, activeSessions)
            }
        }
    }

    /** The parameters the runner opens this run's session with. */
    private fun sessionParams() =
        RunnerSessionParams(
            processId = processId,
            agentName = agent.name,
            workspace = workspace.config,
            env = workspace.config.env,
            parentContext = parentContext,
            rootProcessId = rootProcessId,
        )

    /** Runs the agent's selected steps in order, recording the step in flight on [inFlight]. */
    @OptIn(ExperimentalTime::class)
    private suspend fun runSteps(
        session: RunnerSession,
        runDeadline: RunDeadline?,
        inFlight: StepInFlight,
    ) {
        val selectedIndices = commandIndices?.toSet()
        // Materialized so a boundary can name its successor: `index + 1` is wrong when
        // a skipped (unselected) index sits between them.
        val steps =
            agent.commands.withIndex().filter { (index, _) -> selectedIndices == null || index in selectedIndices }
        var pendingStart: Long? = null
        for ((step, indexedCommand) in steps.withIndex()) {
            // Past the soft deadline no unstarted step starts: the run stops instead.
            if (stopDeadlinePassed(runDeadline)) throw RunDeadlineReached()
            val startedAt = pendingStart ?: Clock.System.now().epochSeconds
            val facts = StepFacts()
            inFlight.step = InterruptedStep(indexedCommand.index, indexedCommand.value, facts)
            val handover =
                runStep(
                    session = session,
                    step = step,
                    indexedCommand = indexedCommand,
                    nextCommand = steps.getOrNull(step + 1)?.value,
                    carriedStart = startedAt,
                    plan = regularStepPlan(indexedCommand, runDeadline, startedAt, facts),
                )
            pendingStart = handover.nextStart
            if (handover.finishRun) return
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
        plan: StepPlan,
    ): StepHandover {
        val (_, command) = indexedCommand
        val startTime = carriedStart ?: Clock.System.now().epochSeconds
        // Only a phase's first step is set here: a later step's start rides the
        // previous step's boundary transaction, so a step boundary is one write.
        if (step == 0) {
            stepRecords.setCurrentCommand(processId, command.label, startTime)
        }
        val commandSpan = startCommandSpan(telemetryService, parentContext, session, plan.artifactIndex, command)

        val (artifactNames, artifactNameFailure, stdOut, stdErr) =
            stepArtifacts(agent, textCompiler, workspace, plan.artifactIndex)
        // Attempt-0 paths, so the console live tail (LogTailService) can follow this
        // command as soon as it starts; LlmHarnessExecutor's retry loop updates this per
        // attempt below.
        val executionContextRedactor = SecretRedactor.from(workspace.config.env, workspace.config.secretEnvNames)
        stepRecords.setCommandArtifacts(
            processId,
            plan.artifactIndex,
            CommandArtifactPaths(stdOut, stdErr, kind = command.kind, redactor = executionContextRedactor),
        )
        val executionContext = commandExecutionContext(session, command, plan, artifactNames, stdOut, stdErr)

        workspace.appendToEnv(mapOf(EnvConstants.AGENT_NAME to agent.name))
        // The stop context is appended for an `onStop:` step only, and only after the agent name,
        // so a stop step sees it and no other step does. See ADR-0105.
        if (plan.stopEnv.isNotEmpty()) {
            workspace.appendToEnv(plan.stopEnv)
        }

        // Conditional execution: evaluate `when` predicate (if any) against the
        // post-transformation env. A Skip result records its own ExecutionInfo
        // and continues; an EvalError (like artifactNameFailure above) — including
        // one the evaluator derived from a predicate timeout — is stored and
        // re-thrown inside the try/catch below so it goes through the standard
        // telemetry / ExecutionInfo path.
        val whenResult = evaluateWhen(workspace, command, session, plan.artifactIndex)

        if (whenResult is WhenEvaluator.Result.Skip) {
            return StepHandover(
                recordSkip(command, startTime, nextCommand, executionContext, commandSpan, whenResult, plan.onStop),
                finishRun = false,
            )
        }
        return runCommand(
            StepRun(plan.artifactIndex, command, nextCommand, startTime, commandSpan, stdOut, stdErr, executionContext, plan.onStop),
            artifactNameFailure,
            whenResult,
            plan.deadline,
        )
    }

    /** The context one command runs under: where its artifacts go, its timeout, and the facts it reports. */
    private fun commandExecutionContext(
        session: RunnerSession,
        command: Command,
        plan: StepPlan,
        artifactNames: ArtifactNameResolver,
        stdOut: Path,
        stdErr: Path,
    ): ExecutionContext =
        ExecutionContext(
            workspace = workspace,
            config = config,
            shell = session.shell,
            stdOut = stdOut,
            stdErr = stdErr,
            processId = processId,
            agentName = agent.name,
            cmdIndex = plan.artifactIndex,
            owner = owner,
            artifactNames = artifactNames,
            timeoutOverrideSeconds = plan.timeoutSeconds,
            interruptGrace = plan.interruptGrace,
            onStop = plan.onStop,
            facts = plan.facts,
        )

    /** Records a step its `when` predicate skipped; returns the next step's start time, if one starts now. */
    @OptIn(ExperimentalTime::class)
    private suspend fun recordSkip(
        command: Command,
        startTime: Long,
        nextCommand: Command?,
        executionContext: ExecutionContext,
        commandSpan: Span,
        whenResult: WhenEvaluator.Result.Skip,
        onStop: Boolean = false,
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
                    onStop = onStop,
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
        deadline: RunDeadline?,
    ): StepHandover {
        val (index, command, nextCommand, startTime, commandSpan, stdOut, stdErr, executionContext) = run
        val artifactsDir = workspace.config.artifacts
        var nextStart: Long? = null
        // Failure-shaped default so an Error escaping the try can't record a success-looking ExecutionInfo.
        var outcome: CommandOutcome = CommandOutcome.Failure(errMsg = null, exitCode = -1, outputTruncated = false)
        // Null until the executor actually returns a result (e.g. an EvalError or an
        // exception before executor.execute means the command never ran) — the finally
        // block falls back to the attempt-0 stdOut/stdErr above in that case.
        var executedResult: CommandResult? = null
        // Why a graceful stop interrupted this step, decided where the step's own exception is
        // still in hand — the row is written below, before the stop phase runs. See ADR-0105.
        var stoppedBy: StopReason? = null

        try {
            artifactNameFailure.onFailure { throw it }
            val redactor = executionContext.secretRedactor()
            if (whenResult is WhenEvaluator.Result.EvalError) {
                throw evalError(command, whenResult, redactor)
            }
            // Get appropriate executor for this command type
            val executor = executorFactory.getExecutor(command)
            val result = executor.execute(command, executionContext, commandSpan)
            executedResult = result

            commandSpan.setAttribute("cmd.type", command::class.simpleName ?: "")
            commandSpan.setAttribute("output.exitCode", result.exitCode.toString())
            val envFromResult = recordResultEnv(command, result, redactor, commandSpan)
            val isFinishCode = finishCodeOrThrow(command, result, redactor)

            workspace.appendToEnv(envFromResult)
            commandSpan.setStatus(StatusCode.OK)
            outcome = CommandOutcome.Success(exitCode = result.exitCode, isFinishCode = isFinishCode)
        } catch (e: Exception) {
            outcome = failureOutcome(e)
            stoppedBy = stopReasonFor(e, command, deadline)
            withContext(NonCancellable) {
                commandSpan.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
                commandSpan.recordException(e)
            }
            throw e
        } finally {
            withContext(NonCancellable) {
                nextStart = completeCommand(run, outcome, executedResult, stoppedBy, stepRecords, processId, artifactsDir)
            }
        }

        return StepHandover(nextStart, finishRun = !command.continueIfFail && outcome.isFinishCode)
    }

    /**
     * The run's graceful stop: runs the agent's `onStop:` steps once, in this same session and
     * before the teardown, when [cause] is a stop trigger — a step that timed out with
     * `onTimeout: stop`, or the backend's soft run deadline. Records what happened on the run and
     * returns; the caller rethrows, so the run ends `FAILED` with its stop recorded. The remaining
     * `commands` steps are never started, because the step loop was left by the throw. See ADR-0105.
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun runStopPhase(
        session: RunnerSession,
        deadline: RunDeadline?,
        interrupted: InterruptedStep,
        cause: Throwable,
    ) {
        val reason = stopReasonFor(cause, interrupted.command, deadline) ?: return
        val stopEnv =
            StopContext.env(
                reason = reason,
                stepIndex = interrupted.index,
                stepLabel = interrupted.command.label,
                hardDeadlineEpochSec = deadline?.hardEpochSec,
                sessionId = interrupted.facts.cliSessionId,
            )
        val outcome =
            if (agent.onStop.isEmpty()) {
                StopStepsOutcome.NONE
            } else {
                runStopSteps(session, deadline, stopEnv)
            }
        stepRecords.setStop(
            processId,
            RunStop(
                reason = reason,
                stepIndex = interrupted.index,
                stepLabel = interrupted.command.label,
                at = Clock.System.now().epochSeconds,
                stopSteps = outcome,
            ),
        )
    }

    /**
     * Runs the agent's `onStop:` steps in order, each like a regular step — `when:`,
     * `continueIfFail` and `finishExitCode` all honoured — with the stop context in its env.
     * The first stop step that fails or times out ends the rest.
     */
    @OptIn(ExperimentalTime::class)
    @Suppress("SuspendFunSwallowedCancellation") // rethrows; failsTheRun tells a kill from a failure
    private suspend fun runStopSteps(
        session: RunnerSession,
        deadline: RunDeadline?,
        stopEnv: Map<String, String>,
    ): StopStepsOutcome {
        val steps = agent.onStop
        for ((ordinal, command) in steps.withIndex()) {
            // The stop steps share the run's artifact directory with the agent's own steps, so
            // they are numbered past them rather than from zero.
            val index = agent.commands.size + ordinal
            val startedAt = Clock.System.now().epochSeconds
            try {
                val handover =
                    runStep(
                        session = session,
                        step = ordinal,
                        indexedCommand = IndexedValue(index, command),
                        nextCommand = steps.getOrNull(ordinal + 1),
                        carriedStart = startedAt,
                        plan = stopStepPlan(index, command, deadline, startedAt, stopEnv),
                    )
                if (handover.finishRun) break
            } catch (e: Throwable) {
                // A kill during the stop phase ends the run as any kill does, leaving no stop
                // record; a stop step's own failure ends the rest of them.
                if (!failsTheRun(e)) throw e
                logger.warn(
                    "Process {}: stop step '{}' failed ({}); the remaining stop steps are skipped",
                    processId,
                    command.label,
                    e.message,
                )
                return StopStepsOutcome.FAILED
            }
        }
        return StopStepsOutcome.FINISHED
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentExecutor::class.java)
    }
}

/** The step the run has in flight, shared between the step loop and the catch that stops the run. */
private class StepInFlight {
    var step: InterruptedStep? = null
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
internal sealed interface CommandOutcome {
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

/** A step's artifact names, the attempt-0 render (a failure re-thrown inside the step's try), and the paths used. */
internal data class StepArtifacts(
    val names: ArtifactNameResolver,
    val failure: Result<Pair<Path, Path>>,
    val stdOut: Path,
    val stdErr: Path,
)

/** One step's command and the per-step state built before it runs. */
internal data class StepRun(
    val index: Int,
    val command: Command,
    val nextCommand: Command?,
    val startTime: Long,
    val commandSpan: Span,
    val stdOut: Path,
    val stdErr: Path,
    val executionContext: ExecutionContext,
    // Whether this row is one of the agent's `onStop:` steps. See ADR-0105.
    val onStop: Boolean = false,
)
