package homer.app.exceptions

import homer.app.models.RunnerFilter
import kotlin.time.Duration

/**
 * Base exception for all Homer application exceptions
 */
sealed class HomerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when an agent execution fails
 */
class AgentException(
    message: String,
    val agentName: String,
    cause: Throwable? = null,
) : HomerException("Agent '$agentName': $message", cause)

/**
 * Exception thrown when a command execution fails
 */
class CommandExecutionException(
    message: String,
    val exitCode: Int,
    val command: String,
) : HomerException("Command '$command' failed with exit code $exitCode: $message")

/**
 * Exception thrown for configuration-related errors
 */
class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : HomerException("Configuration error: $message", cause)

/**
 * Exception thrown for validation errors
 */
class ValidationException(
    message: String,
    val field: String? = null,
) : HomerException(
        if (field != null) {
            "Validation error for '$field': $message"
        } else {
            "Validation error: $message"
        },
    )

/**
 * Raised when a cookie/session (`session:`) authenticated request is missing
 * the required `X-Homer-CSRF` header. Browsers auto-attach the session cookie
 * to cross-site requests (GET navigations / `<img>` loads under `SameSite=Lax`,
 * all cross-site requests under `cookieSameSite=None`), but cannot set a custom
 * request header cross-origin, so requiring one blocks the drive-by CSRF surface
 * regardless of SameSite. Maps to 403 Forbidden. Token/HMAC callers are exempt
 * (their credential is not browser-ambient). See ADR-0016.
 */
class CsrfValidationException(
    message: String,
) : HomerException(message)

/**
 * Exception thrown for workspace-related errors
 */
class WorkspaceException(
    message: String,
    cause: Throwable? = null,
) : HomerException("Workspace error: $message", cause)

class AnotherProcessStillActive(
    activeProcessId: Int,
) : HomerException("The process $activeProcessId is still active, stopping current")

class MaxParallelRunsExceededException(
    agentName: String,
    maxParallelRuns: Int,
) : HomerException("Agent '$agentName' has reached the maximum of $maxParallelRuns parallel runs")

/**
 * Raised under the `WAIT` strategy when an agent already has `maxQueuedRuns`
 * runs suspended waiting for a parallel-run slot. Each waiting run holds a
 * non-evictable process row, so an unbounded wait queue on one agent could
 * exhaust global process capacity and starve every other agent; the opt-in
 * bound fails the surplus run instead of parking it indefinitely.
 */
class MaxQueuedRunsExceededException(
    agentName: String,
    maxQueuedRuns: Int,
) : HomerException("Agent '$agentName' has reached the maximum of $maxQueuedRuns queued runs")

/**
 * Raised when an incoming agent invocation would exceed the configured maximum
 * RunAgent chain depth. Prevents accidental (or malicious) recursion from
 * burning unbounded tokens. The chain is threaded across HTTP hops via the
 * X-Homer-Invocation-Chain header (HMR_INVOCATION_CHAIN env var inside the
 * called process).
 */
class InvocationDepthExceededException(
    val agentName: String,
    val maxDepth: Int,
    val chain: List<String>,
) : HomerException(
        "Agent '$agentName' invocation rejected: chain depth ${chain.size + 1} exceeds " +
            "configured maximum of $maxDepth (chain: ${chain.joinToString(" → ")})",
    )

/**
 * Raised when an incoming agent invocation already names the target agent in
 * its upstream chain (A → B → A). Distinct from
 * [InvocationDepthExceededException]: a cycle can fire well below the depth
 * cap and would otherwise burn the rest of the budget on a guaranteed-loop.
 * Same HTTP mapping (422) — the request shape is unprocessable either way.
 */
class InvocationCycleDetectedException(
    val agentName: String,
    val chain: List<String>,
) : HomerException(
        "Agent '$agentName' invocation rejected: cycle detected — '$agentName' is already " +
            "in the upstream chain (chain: ${chain.joinToString(" → ")})",
    )

/**
 * Raised when ProcessManager is at capacity and no terminal process can be
 * evicted to make room for a new one. The previous behavior (warn-and-continue)
 * silently drove the in-memory process map past its bound and risked OOM under
 * sustained burst load. Surfacing this as a typed exception lets the HTTP
 * boundary respond 503, signalling backpressure to clients/load balancers.
 */
class ProcessManagerAtCapacityException(
    val maxProcesses: Int,
) : HomerException(
        "ProcessManager is at capacity ($maxProcesses processes) and no terminal " +
            "processes are available for eviction; refusing new process",
    )

/**
 * Raised when an LLM harness command (`!<claude>`/`!<codex>`) exited 0 but its stdout
 * cannot be interpreted as the harness's documented JSON event stream.
 * Fail-closed: a malformed output means we cannot trust the run, so the
 * surrounding process is marked FAILED rather than silently treated as success.
 */
class MalformedLlmOutputException(
    val reason: String,
    cause: Throwable? = null,
) : HomerException("LLM output validation failed: $reason", cause)

/**
 * Raised when [server.requireClaudeCodeVersion] is configured and the
 * `claude_code_version` reported by the Claude CLI's system event does not
 * match (or is absent). Operators pin the version they tested against so a
 * silent CLI upgrade cannot reshape stdout in ways the executor still parses
 * but the downstream agents do not expect. Distinct from
 * [MalformedLlmOutputException] because retrying cannot change the CLI
 * version — the executor rethrows this immediately without consuming retry
 * attempts.
 */
class ClaudeCliVersionMismatchException(
    val expected: String,
    val actual: String?,
) : HomerException(
        "Claude CLI version mismatch: expected '$expected', actual '${actual ?: "<missing>"}' " +
            "(set server.requireClaudeCodeVersion to the version you have validated, or null to disable)",
    )

/**
 * Raised when an agent's `cooldown` has not elapsed since its previous run.
 * Homer bounds concurrency (`lockStrategy`, `maxParallelRuns`) and spend
 * (`costLimits`) but had no frequency bound, so an agent that fails fast could
 * be re-fired by the next cron tick against identical state, indefinitely.
 * Mapped to 429 Too Many Requests, like the other backpressure refusals.
 */
class CooldownActiveException(
    val agentName: String,
    val retryAfterSec: Long,
    reason: String,
) : HomerException(
        "Agent '$agentName' is in cooldown ($reason); retry in ${retryAfterSec}s",
    )

/**
 * Raised when a Claude invocation would push the rolling cost window over a
 * configured cap (`server.costLimits.globalUsdPerMin` or per-agent
 * `costLimits.perAgentUsdPerDay`). Mapped to 429 Too Many Requests at the HTTP
 * boundary so callers/clients see backpressure rather than a generic 500.
 */
class CostCapExceededException(
    val capName: String,
    val limitUsd: Double,
    val currentUsd: Double,
) : HomerException(
        "Cost cap '$capName' exceeded: limit \$$limitUsd, current spend \$$currentUsd",
    )

/**
 * A request-validation failure — query and path parameters, a body that does not decode,
 * headers, tag format and the like. StatusPages answers [httpStatusCode] (default 400) with
 * the message as is, unlike [ValidationException], which prefixes it.
 */
class QueryParamValidationException(
    message: String,
    val httpStatusCode: io.ktor.http.HttpStatusCode = io.ktor.http.HttpStatusCode.BadRequest,
    val paramName: String? = null,
) : HomerException(message)

/**
 * A validation failure answered as a bare `400` with no body: the contract of the SSE
 * stream endpoints (`/api/v1/stream/processes/{id}/logs` and `/llm-events`).
 */
class BodylessBadRequestException : HomerException("Bad request")

/**
 * Thrown when a request body is well-formed JSON but violates the agent's
 * `inputSchema`. Maps to 422 Unprocessable Entity.
 */
class PayloadSchemaValidationException(
    message: String,
    val errors: List<String> = emptyList(),
) : HomerException(message)

/**
 * Raised when a question id does not exist. Cross-process access through the
 * callback API also maps here on purpose — a question belonging to another
 * process is indistinguishable from a nonexistent one, so question ids cannot
 * be probed. Maps to 404 Not Found.
 */
class QuestionNotFoundException(
    val questionId: String,
) : HomerException("Question '$questionId' not found")

/**
 * Raised when answering a question that is no longer OPEN (already answered
 * or expired). Maps to 409 Conflict.
 */
class QuestionNotOpenException(
    val questionId: String,
    val status: String,
) : HomerException("Question '$questionId' is $status, not OPEN")

/**
 * Raised when a command asks a question for a process that is not accepting
 * them (terminal status, or never registered). Maps to 409 Conflict.
 */
class QuestionsClosedForProcessException(
    val processId: Int,
) : HomerException("Process $processId is not accepting questions")

/**
 * Raised when a process already has `server.questions.maxOpenPerProcess`
 * open questions. Maps to 429 Too Many Requests.
 */
class TooManyOpenQuestionsException(
    val processId: Int,
    val maxOpen: Int,
) : HomerException("Process $processId already has $maxOpen open questions")

/**
 * Raised when a process already holds `server.questions.maxConcurrentWaitsPerProcess`
 * concurrent answer-waits. Bounds the connections/coroutines a workspace
 * command can pin open (each hold can last up to `maxWaitSec`). Maps to
 * 429 Too Many Requests.
 */
class TooManyConcurrentWaitsException(
    val processId: Int,
    val maxWaits: Int,
) : HomerException("Process $processId already has $maxWaits answer-waits in flight")

/** Raised when a dispatch targets an agent that does not exist, or one the hop is not authorized
 *  to reach — the two are indistinguishable by design (ADR-0047 rule 3). Maps to 404. */
class DispatchAgentNotFoundException(
    val agent: String,
) : HomerException("Dispatch target agent '$agent' not found")

/** Raised when ask-and-dispatch targets an agent that exists but has not opted in
 *  (`agent.dispatch.enabled` is not true). Maps to 403 Forbidden. */
class AgentNotDispatchableException(
    val agent: String,
) : HomerException("Agent '$agent' is not an allowed dispatch target (set dispatch.enabled: true)")

/** Raised when a dispatch id does not exist. Maps to 404. */
class DispatchNotFoundException(
    val id: Int,
) : HomerException("Dispatch '$id' not found")

/** Raised when cancelling a dispatch that is not PENDING. Maps to 409. */
class DispatchNotPendingException(
    val id: Int,
    val status: String,
) : HomerException("Dispatch '$id' is $status, not PENDING")

/** Raised at the global PENDING-dispatch cap. Maps to 429. */
class TooManyPendingDispatchesException(
    val maxPending: Int,
) : HomerException("Already at the maximum of $maxPending pending dispatches")

/** Raised when a continuation id does not exist. Maps to 404. */
class ContinuationNotFoundException(
    val id: Int,
) : HomerException("Continuation '$id' not found")

/** Raised when cancelling a continuation that is not PENDING. Maps to 409. */
class ContinuationNotPendingException(
    val id: Int,
    val status: String,
) : HomerException("Continuation '$id' is $status, not PENDING")

/** Raised at the global PENDING-continuation cap. Maps to 429. */
class TooManyPendingContinuationsException(
    val maxPending: Int,
) : HomerException("Already at the maximum of $maxPending pending continuations")

/**
 * Raised when no runner claimed a queued run within its claim TTL. [reason] is what the
 * queue observed at expiry (a saturated runner, no matching tags, ...). See ADR-0066.
 */
class RunnerClaimTimeoutException(
    timeout: Duration,
    filter: RunnerFilter,
    reason: String,
) : HomerException("No runner claimed the run within ${timeout.inWholeSeconds} s (${filter.describe()}): $reason")

/** Raised when the claim queue is at `server.maxPendingRunnerClaims`; the run fails without waiting. §12 rule 4. */
class ClaimQueueFullException(
    maxPending: Int,
) : HomerException("Runner claim queue is full ($maxPending pending claims)")

/** A pull-mode runner instance announced something the domain does not accept (tag shape, `allowedTags`, `concurrency` cap). Maps to 422. */
class RunnerRegistrationRejectedException(
    domain: String,
    reason: String,
    cause: Throwable? = null,
) : HomerException("Runner domain '$domain' rejected the registration: $reason", cause)

/** Raised at `maxInstances` for a domain. Maps to 429. */
class TooManyRunnerInstancesException(
    domain: String,
    maxInstances: Int,
) : HomerException("Runner domain '$domain' already has $maxInstances registered instances")

/** Raised when an instance opens more concurrent claim polls than its declared `concurrency`. Maps to 429. */
class TooManyRunnerPollsException(
    instance: String,
    concurrency: Int,
) : HomerException("Runner instance '$instance' already has $concurrency claim polls open")

/**
 * Raised when a session already holds `ExternalRunnerSession.MAX_NEXT_POLLS`
 * concurrent `next` long-polls. Bounds the connections and coroutines one
 * authenticated instance can park on a single session. Maps to 429.
 */
class TooManySessionPollsException(
    instance: String,
    maxPolls: Int,
) : HomerException("Runner instance '$instance' already has $maxPolls next polls open on this session")

/**
 * A transfer request arrived while another one for the same op (for output: the
 * same op and stream) was still in flight on the session. Bounds the threads and
 * connections one instance can hold per transfer. Maps to 429.
 */
class TooManyRunnerTransfersException(
    instance: String,
) : HomerException("Runner instance '$instance' already has this transfer in flight")

/** An output chunk arrived out of order (gap or repeat). Maps to 409. */
class RunnerOutputSequenceException(
    expected: Long,
    got: Long,
) : HomerException("Output chunk sequence mismatch: expected $expected, got $got")

/** A posted tar exceeded the domain's `maxTransferBytes`. Maps to 413. */
class RunnerTransferTooLargeException(
    maxBytes: Long,
) : HomerException("Transfer exceeds the domain limit of $maxBytes bytes")

/**
 * A runner transfer handler (tar pack, tar extract, output chunk) outran the
 * domain's `transferTimeoutSec`. The handler owns this bound itself: the one in
 * `ExternalRunnerSession.await` is a different component's (§12 rule 4). Maps to 408.
 */
class RunnerTransferTimeoutException(
    domain: String,
    timeoutSec: Long,
) : HomerException("Runner domain '$domain' transfer exceeded ${timeoutSec}s")
