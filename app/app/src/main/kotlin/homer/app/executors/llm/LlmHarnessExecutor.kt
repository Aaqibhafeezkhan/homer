package homer.app.executors.llm

import homer.app.exceptions.MalformedLlmOutputException
import homer.app.executors.CommandArtifactPaths
import homer.app.executors.CommandExecutor
import homer.app.executors.ExecutionContext
import homer.app.executors.StepRecords
import homer.app.executors.secretRedactor
import homer.app.infra.LangfuseClient
import homer.app.infra.LlmCostResolver
import homer.app.infra.SecretRedactor
import homer.app.infra.TranslatedStream
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.CostSource
import homer.app.models.LlmAccounting
import homer.app.models.LlmPrompt
import homer.app.models.LlmRetry
import homer.app.models.kind
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The one executor for every LLM harness command. Owns prompt compilation, the retry
 * loop, per-attempt cost accounting (ADR-0061), fail-closed inspection and telemetry;
 * the [harness] owns only argv and translation. See ADR-0070.
 */
class LlmHarnessExecutor<C>(
    private val harness: LlmHarness<C>,
    private val langfuseClient: LangfuseClient,
    private val telemetry: LlmStepTelemetry,
    private val stepRecords: StepRecords,
    private val costRecorder: CostRecorder,
    /** Where a session lookup settles after the attempt returned; outlives the run (ADR-0089). */
    private val settlementScope: CoroutineScope,
    llmCostResolver: LlmCostResolver = LlmCostResolver.DISABLED,
    private val random: Random = Random.Default,
) : CommandExecutor<C> where C : Command, C : Command.LlmCommand {
    private val logger = LoggerFactory.getLogger("homer.app.executors.llm.${harness.id}")
    private val escalation = TierEscalation(harness, logger)
    private val output = LlmAttemptOutput(harness, logger)
    private val accounting =
        LlmAttemptAccounting(LlmAttemptPricing(harness, llmCostResolver, logger), stepRecords, costRecorder, settlementScope, logger)
    private val settlementHandOffs = Semaphore(MAX_CONCURRENT_SETTLEMENT_HANDOFFS)

    override suspend fun execute(
        command: C,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val env = context.workspace.config.env
        // Built before the prompt is compiled, as before escalation existed: an invalid option fails first.
        val argv0 = harness.argv(command, env)
        val compiledPrompt = compilePrompt(command, env)
        val redactor = context.secretRedactor()
        span.setAttribute("input.value", redactor.redact(compiledPrompt))

        // Tier 0 is the step as declared; each `escalate` entry is a further tier, run only when its
        // `when` judges the previous tier's outcome bad. No `escalate` = exactly one pass. See ADR-0079.
        var attemptBase = 0
        for (tier in 0..command.escalate.size) {
            val current = if (tier == 0) command else harness.escalated(command, command.escalate[tier - 1])
            val argv = escalation.tierArgv(tier, current, argv0, env, span, redactor)

            val processEnv =
                buildMap {
                    // W3C traceparent so the CLI's own telemetry joins this trace; skipped when tracing is off.
                    val sc = span.spanContext
                    if (sc.isValid) put("OTEL_TRACE_PARENT", "00-${sc.traceId}-${sc.spanId}-${sc.traceFlags.asHex()}")
                    putAll(harness.processEnv(current))
                }

            val (outcome, settlement) =
                try {
                    runWithRetry(current, argv, compiledPrompt, processEnv, context, span, redactor, attemptBase)
                } catch (e: MalformedLlmOutputException) {
                    if (!escalation.escalates(command, tier, OUTCOME_MALFORMED, MALFORMED_EXIT_CODE, context, span, redactor)) throw e
                    attemptBase += current.retryPolicy().attempts
                    continue
                }
            report(outcome, settlement, current, compiledPrompt, span, redactor)
            val escalating = escalation.escalates(command, tier, outcome.outcomeName, outcome.result.exitCode, context, span, redactor)
            if (!escalating) return outcome.result
            attemptBase += current.retryPolicy().attempts
        }
        error("escalation loop ended without a result; should be unreachable")
    }

    private suspend fun compilePrompt(
        command: C,
        env: Map<String, String>,
    ): String =
        when (val p = command.prompt) {
            is LlmPrompt.Prompt -> langfuseClient.compilePrompt(p.prompt, env)
            is LlmPrompt.Langfuse -> langfuseClient.getPrompt(p.promptName, env, p.version, p.label)
        }

    /**
     * Invokes the harness CLI, validating its output, and retrying transient failures
     * (non-zero exit OR [MalformedLlmOutputException]) per the command's [LlmRetry]
     * configuration. With the default `attempts = 1` the loop runs exactly once.
     *
     * The last attempt's outcome is the one that surfaces: a successful run is returned
     * directly; if every attempt failed, the most recent [MalformedLlmOutputException]
     * is rethrown, otherwise the most recent non-zero [CommandResult] is returned (so
     * downstream `continueIfFail` / `finishExitCode` semantics are preserved).
     */
    private suspend fun runWithRetry(
        command: C,
        argv: List<String>,
        compiledPrompt: String,
        processEnv: Map<String, String>,
        context: ExecutionContext,
        span: Span,
        redactor: SecretRedactor,
        // Escalation tiers continue the attempt numbering so each tier writes its own artifacts.
        attemptBase: Int = 0,
    ): Attempted<RetryOutcome.Returned> {
        val retry = command.retryPolicy()
        span.setAttribute("retry.attempts", retry.attempts.toLong())

        // Cost cap is enforced at the request boundary (handleAgentExecution /
        // handleRetry) so a refusal can return HTTP 429. The executor only
        // records cost; it does not re-check caps mid-run.

        var last: Attempted<RetryOutcome>? = null

        for (attemptIndex in 0 until retry.attempts) {
            if (attemptIndex > 0) backOffBeforeRetry(retry, attemptIndex, span)
            last = runAttempt(command, argv, compiledPrompt, processEnv, context, span, redactor, attemptBase, attemptIndex)
            // A usable result or a terminal cap ends the loop; a failure goes on to the next attempt.
            if (last.outcome is RetryOutcome.Success || last.outcome is RetryOutcome.CappedStop) break
        }

        return Attempted(returnedOrThrow(last?.outcome, retry, span), last?.settlement)
    }

    /** An attempt's [outcome] and, when it returned on a preview, the background [settlement] of its cost (ADR-0089). */
    private data class Attempted<O : RetryOutcome>(
        val outcome: O,
        val settlement: Deferred<LlmAccounting>?,
    )

    /** Runs attempt [attemptIndex] of [runWithRetry], accounting its spend whatever happens to it. */
    private suspend fun runAttempt(
        command: C,
        argv: List<String>,
        compiledPrompt: String,
        processEnv: Map<String, String>,
        context: ExecutionContext,
        span: Span,
        redactor: SecretRedactor,
        attemptBase: Int,
        attemptIndex: Int,
    ): Attempted<RetryOutcome> {
        // Per-attempt paths: with the default artifactNameTemplate these are
        // identical across attempts (today's truncate-in-place behavior); a
        // template referencing HMR_CMD_ATTEMPT gives each attempt its own file.
        val attemptOut = context.artifactNames.stdOut(attemptBase + attemptIndex)
        val attemptErr = context.artifactNames.stdErr(attemptBase + attemptIndex)
        trackAttemptArtifacts(command, context, redactor, attemptOut, attemptErr, attemptBase + attemptIndex)
        val dropFilter = harness.stdOutFilter(command)
        // The attempt's own wall-clock window, captured here (Homer server time, never a
        // runner-local clock) and handed to the resolver so it can price this attempt from
        // only its own requests. See ADR-0061.
        val attempt = Attempt(command, compiledPrompt, context, span, redactor, attemptOut, System.currentTimeMillis())
        val result =
            try {
                runShell(attempt, argv, processEnv, attemptErr, dropFilter)
            } finally {
                withContext(NonCancellable) {
                    dropFilter?.let { output.appendDropSummary(it, attemptErr, span) }
                }
            }
        // Account EVERY attempt before deciding what it means: a killed or crashed CLI
        // still made billable requests. Lenient scan + resolver; strict validation of a
        // usable success stays below and only for exit 0. See ADR-0061.
        val (accounting, settlement) = accountFinished(attempt, endedAtMs = System.currentTimeMillis())

        if (result.exitCode != 0) return Attempted(RetryOutcome.OtherFailure(result.copy(llmAccounting = accounting)), settlement)
        return Attempted(output.exitZeroOutcome(command, result, accounting, attemptOut, attemptIndex, span), settlement)
    }

    /** One attempt's fixed inputs, shared by the steps of [runAttempt]; [startedAtMs] opens its wall-clock window. */
    private inner class Attempt(
        val command: C,
        val compiledPrompt: String,
        val context: ExecutionContext,
        val span: Span,
        val redactor: SecretRedactor,
        val out: Path,
        val startedAtMs: Long,
    )

    /**
     * Keeps the console live tail (LogTailService) following this attempt's actual
     * file. context.processId is -1 for callers with no associated process (e.g.
     * agentdebug), which nothing tracks against.
     */
    private suspend fun trackAttemptArtifacts(
        command: C,
        context: ExecutionContext,
        redactor: SecretRedactor,
        attemptOut: Path,
        attemptErr: Path,
        attemptNumber: Int,
    ) {
        if (context.processId < 0) return
        stepRecords.setCommandArtifacts(
            context.processId,
            context.cmdIndex,
            CommandArtifactPaths(
                attemptOut,
                attemptErr,
                kind = command.kind,
                attemptIndex = attemptNumber,
                redactor = redactor,
            ),
        )
    }

    /** The attempt's shell call; a throw is accounted, with its telemetry handed off, before it is rethrown. */
    @Suppress("SuspendFunSwallowedCancellation") // accounts the attempt and rethrows
    private suspend fun runShell(
        attempt: Attempt,
        argv: List<String>,
        processEnv: Map<String, String>,
        attemptErr: Path,
        dropFilter: LlmStdOutFilter?,
    ): CommandResult =
        try {
            attempt.context.shell.cmd(
                command = argv,
                stdOut = attempt.out,
                stdErr = attemptErr,
                stdIn = attempt.compiledPrompt,
                env = attempt.context.workspace.config.env + processEnv,
                timeout = attempt.command.timeout.seconds,
                stdOutFilter = dropFilter,
            )
        } catch (e: Throwable) {
            // `e` must be the exception rethrown below — AgentExecutor branches
            // on its type (OutputCapExceededException vs CancellationException).
            // See ADR-0061.
            // [denial_of_service / CWE-400] Capture cancellation state before
            // entering NonCancellable — inside it isActive is always true, so
            // this is the only place a genuine cancellation (parent job killed)
            // can still be told apart from the shell's own throw; a genuinely
            // cancelled attempt must not hold this block open for a remote
            // lookup, though its spend is real and is still accounted.
            val callerActive = currentCoroutineContext().isActive
            val endedAtMs = System.currentTimeMillis()
            accountAborted(attempt, allowRemoteLookup = callerActive, endedAtMs, abortedBy = "the shell aborted the attempt")
            throw e
        }

    /** Accounts a finished attempt; a cancellation while the inline path waits on the resolver still accounts it, locally. */
    @Suppress("SuspendFunSwallowedCancellation") // accounts the attempt and rethrows
    private suspend fun accountFinished(
        attempt: Attempt,
        endedAtMs: Long,
    ): LlmAttemptAccounting.Accounted =
        try {
            accounting.finished(attempt.out, attempt.context, attempt.span, attempt.startedAtMs, endedAtMs)
        } catch (e: CancellationException) {
            // A cancellation arriving while the resolver waits out its poll schedule
            // must not lose the attempt's spend: re-account it locally, uncancellable
            // and without the remote lookup, then rethrow. See ADR-0061.
            accountAborted(attempt, allowRemoteLookup = false, endedAtMs, abortedBy = "the run was cancelled")
            throw e
        }

    /**
     * Accounts an aborted attempt uncancellably and hands its telemetry off, since `execute`'s
     * hand-off never runs on this path. A failure is logged, never thrown: the caller rethrows
     * the attempt's own exception.
     */
    @Suppress("SuspendFunSwallowedCancellation") // NonCancellable inside; its caller rethrows the original
    private suspend fun accountAborted(
        attempt: Attempt,
        allowRemoteLookup: Boolean,
        endedAtMs: Long,
        abortedBy: String,
    ) {
        runCatching {
            withContext(NonCancellable) {
                val accounted =
                    accounting.account(attempt.out, attempt.context, attempt.span, allowRemoteLookup, attempt.startedAtMs, endedAtMs)
                emitAbortTelemetry(attempt.out, accounted, attempt.compiledPrompt, attempt.command, attempt.span, attempt.redactor)
            }
        }.onFailure {
            logger.warn("cost accounting failed after {} ({}); the original exception is rethrown", abortedBy, it.message)
        }
    }

    /** Waits out [retryDelayMs] before retry [attemptIndex], logging and recording the retry. */
    private suspend fun backOffBeforeRetry(
        retry: LlmRetry,
        attemptIndex: Int,
        span: Span,
    ) {
        val delayMs = retryDelayMs(retry, attemptIndex, random)
        logger.warn(
            "{} attempt {}/{} failed; retrying in {} ms",
            harness.id,
            attemptIndex,
            retry.attempts,
            delayMs,
        )
        span.addEvent("llm.retry.attempt_$attemptIndex")
        delay(delayMs)
    }

    /**
     * Telemetry for an attempt whose shell threw or whose accounting was cancelled: `execute`'s
     * hand-off never runs on those paths, so the step would lose its spans. The read runs inside
     * the timeout, so a stdout flood cannot hold a cancelled run's teardown open (CWE-400).
     */
    @Suppress("SuspendFunSwallowedCancellation") // catches its own timeout; runs under NonCancellable
    private suspend fun emitAbortTelemetry(
        attemptOut: Path,
        accounting: LlmAccounting,
        prompt: String,
        command: C,
        span: Span,
        redactor: SecretRedactor,
    ) {
        runCatching {
            withTimeout(ABORT_TELEMETRY_TIMEOUT_MS) {
                telemetry.process(
                    output.readLeniently(attemptOut, span),
                    prompt,
                    span,
                    redactor,
                    LlmStepInfo(label = command.label, configuredModel = command.model),
                    resolvedCostUsd = telemetryCostUsd(accounting),
                )
            }
        }.onFailure {
            logger.warn("telemetry hand-off failed after the attempt aborted ({}); the original exception is rethrown", it.message)
        }
    }

    /**
     * Hands [outcome]'s stream to telemetry; a failure there is recorded on [span], never thrown,
     * but a cancellation is. The stream is read here, inline, because attempts may share and
     * truncate the stdout file; with a [settlement] running the hand-off itself waits for it in
     * the background so the trace carries the settled figure, not the preview (ADR-0089).
     */
    private suspend fun report(
        outcome: RetryOutcome.Returned,
        settlement: Deferred<LlmAccounting>?,
        command: C,
        compiledPrompt: String,
        span: Span,
        redactor: SecretRedactor,
    ) {
        try {
            // A non-zero exit's stream is read leniently: a killed CLI's trailing partial line
            // must not take the attempt's tool and message spans with it. Exit 0 stays strict.
            val stream =
                when (outcome) {
                    is RetryOutcome.Success -> outcome.stream
                    is RetryOutcome.CappedStop -> outcome.stream
                    is RetryOutcome.OtherFailure -> output.readLeniently(outcome.result.stdOut, span)
                }
            val step = LlmStepInfo(label = command.label, configuredModel = command.model)
            handOff(settlement, outcome.result.llmAccounting, stream, compiledPrompt, span, redactor, step)
        } catch (e: CancellationException) {
            // A kill during the hand-off must stop the step, not let it finish as a success.
            throw e
        } catch (e: Exception) {
            span.setAttribute("telemetry.error", e.message ?: "Unknown error")
            span.recordException(e)
        }
    }

    @Suppress("SuspendFunSwallowedCancellation") // a cancelled settlement still gets its trace, on the harness figure
    private suspend fun handOff(
        settlement: Deferred<LlmAccounting>?,
        pending: LlmAccounting?,
        stream: TranslatedStream,
        compiledPrompt: String,
        span: Span,
        redactor: SecretRedactor,
        step: LlmStepInfo,
    ) {
        if (settlement == null || !settlementHandOffs.tryAcquire()) { // CWE-770/CWE-400: shed, never queue a job (ADR-0047 §12 rules 4-5).
            if (settlement != null) {
                logger.warn("settlement hand-off shed at the concurrency bound ({})", MAX_CONCURRENT_SETTLEMENT_HANDOFFS)
                span.setAttribute("telemetry.llm.handoff_shed", true)
            }
            telemetry.process(stream, compiledPrompt, span, redactor, step, resolvedCostUsd = telemetryCostUsd(pending))
            return
        }
        settlementScope
            .launch(start = CoroutineStart.LAZY) {
                val waited = runCatching { withTimeoutOrNull(SETTLEMENT_HANDOFF_TIMEOUT_MS) { settlement.await() } }
                val settled = waited.getOrNull() ?: pending
                val settlementSpanBuilder = telemetry.tracer.spanBuilder("llm_cost_settlement").setParent(Context.current().with(span))
                val settlementSpan = settlementSpanBuilder.startSpan()
                // A wait that timed out, not a settlement that failed: only the former is a shed.
                if (waited.isSuccess && waited.getOrNull() == null) settlementSpan.setAttribute("telemetry.llm.handoff_shed", true)
                settled?.let { accounting.annotateSettled(settlementSpan, it) }
                runCatching { telemetry.process(stream, compiledPrompt, span, redactor, step, resolvedCostUsd = telemetryCostUsd(settled)) }
                    .onFailure {
                        logger.warn("telemetry hand-off failed after the cost settled ({})", it.message)
                        settlementSpan.setAttribute("telemetry.error", it.message ?: "Unknown error")
                        settlementSpan.recordException(it)
                    }
                settlementSpan.end()
            }.also { it.invokeOnCompletion { settlementHandOffs.release() } } // released on completion, never in a `finally`
            .start()
    }

    private companion object {
        // Bounds the abort-path hand-off, whose per-span ordering delay must not hold a genuine
        // cancellation open. Comfortably above a normal emit (a few spans × 50 ms).
        const val ABORT_TELEMETRY_TIMEOUT_MS = 5_000L

        const val MAX_CONCURRENT_SETTLEMENT_HANDOFFS = 8
        const val SETTLEMENT_HANDOFF_TIMEOUT_MS = 45_000L

        const val OUTCOME_MALFORMED = "malformed"
        const val MALFORMED_EXIT_CODE = -1
    }
}
