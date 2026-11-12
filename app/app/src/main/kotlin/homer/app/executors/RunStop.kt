package homer.app.executors

import homer.app.models.Command
import homer.app.models.OnTimeout
import homer.app.models.StopReason
import homer.app.runners.RunDeadline
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** The safety margin a step keeps before the backend's hard run deadline, so it is reaped in time. */
private const val DEADLINE_MARGIN_SECONDS = 10L

/** The floor on a step's effective timeout: the shells count whole seconds, and zero would never wait at all. */
private const val MIN_STEP_SECONDS = 1L

/** The cap on one `onStop:` step when the backend imposes no run deadline of its own. See ADR-0105. */
private const val MAX_STOP_STEP_SECONDS = 300L

/**
 * One step's plan: where its artifacts go, the timeout and interrupt grace it runs under, the run
 * deadline that applies to it, the facts its executor reports back, and — for an `onStop:` step —
 * the stop context it runs with. See ADR-0105.
 */
internal class StepPlan(
    val artifactIndex: Int,
    val timeoutSeconds: Long,
    val interruptGrace: Duration?,
    val deadline: RunDeadline?,
    val facts: StepFacts,
    val onStop: Boolean,
    val stopEnv: Map<String, String> = emptyMap(),
)

/** The step in flight when the run's steps ended: the stop phase names it and resumes its CLI session. */
internal class InterruptedStep(
    val index: Int,
    val command: Command,
    val facts: StepFacts,
)

/** Raised when the soft run deadline passes between steps: the run stops instead of starting another. */
internal class RunDeadlineReached : CancellationException("the run's soft run deadline passed")

/** The plan for a regular step of [indexedCommand], started at [now]. */
internal fun regularStepPlan(
    indexedCommand: IndexedValue<Command>,
    deadline: RunDeadline?,
    now: Long,
    facts: StepFacts,
): StepPlan =
    StepPlan(
        artifactIndex = indexedCommand.index,
        timeoutSeconds = cappedTimeout(indexedCommand.value, deadline, now, onStop = false),
        interruptGrace = interruptGraceFor(indexedCommand.value, deadline),
        deadline = deadline,
        facts = facts,
        onStop = false,
    )

/** The plan for one of the agent's `onStop:` steps, which runs with [stopEnv] and always under a grace. */
internal fun stopStepPlan(
    index: Int,
    command: Command,
    deadline: RunDeadline?,
    now: Long,
    stopEnv: Map<String, String>,
): StepPlan =
    StepPlan(
        artifactIndex = index,
        timeoutSeconds = cappedTimeout(command, deadline, now, onStop = true),
        interruptGrace = command.interruptGraceSeconds.seconds,
        deadline = deadline,
        facts = StepFacts(),
        onStop = true,
        stopEnv = stopEnv,
    )

/**
 * [command]'s own timeout, cut at the deadlines that apply to it and floored at [MIN_STEP_SECONDS]:
 * a regular step at the soft deadline — the deadline is what stops it — and at the hard one less
 * [DEADLINE_MARGIN_SECONDS]; a stop step only at the hard one, and at [MAX_STOP_STEP_SECONDS] when
 * the backend imposes no deadline at all. See ADR-0105.
 */
private fun cappedTimeout(
    command: Command,
    deadline: RunDeadline?,
    now: Long,
    onStop: Boolean,
): Long {
    val hard = deadline?.let { it.hardEpochSec - DEADLINE_MARGIN_SECONDS - now }
    val caps =
        if (onStop) {
            listOfNotNull(command.timeout, hard, MAX_STOP_STEP_SECONDS)
        } else {
            listOfNotNull(command.timeout, hard, deadline?.let { it.softEpochSec - now })
        }
    return caps.min().coerceAtLeast(MIN_STEP_SECONDS)
}

/**
 * The interrupt grace [command] runs under: none for a plain step of a run that never stops
 * gracefully — today's immediate SIGKILL — and [Command.interruptGraceSeconds] once the step opted
 * in with `onTimeout: stop` or the run has a soft deadline of its own. See ADR-0105.
 */
private fun interruptGraceFor(
    command: Command,
    deadline: RunDeadline?,
): Duration? = if (deadline != null || command.onTimeout == OnTimeout.STOP) command.interruptGraceSeconds.seconds else null

/** Whether the backend's soft run deadline has passed: no further step starts, and the run stops. */
@OptIn(ExperimentalTime::class)
internal fun stopDeadlinePassed(deadline: RunDeadline?): Boolean =
    deadline != null && Clock.System.now().epochSeconds >= deadline.softEpochSec

/**
 * Why [cause] ends the run's steps as a graceful stop, or null when it is an ordinary failure. The
 * soft deadline stops the run whether or not the step opted in — it is what cut the step's own
 * timeout short; otherwise only a step declaring `onTimeout: stop` does. See ADR-0105.
 */
internal fun stopReasonFor(
    cause: Throwable,
    command: Command,
    deadline: RunDeadline?,
): StopReason? =
    when {
        stopDeadlinePassed(deadline) -> StopReason.RUN_DEADLINE
        cause is CancellationException && command.onTimeout == OnTimeout.STOP -> StopReason.STEP_TIMEOUT
        else -> null
    }
