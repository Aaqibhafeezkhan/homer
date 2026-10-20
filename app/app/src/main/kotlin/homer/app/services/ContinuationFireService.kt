package homer.app.services

import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.HomerException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.infra.AgentInputResolver
import homer.app.infra.ResultToEnvMapper
import homer.app.infra.reservedControlParams
import homer.app.models.Agent
import homer.app.models.CommandResult
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessStatus
import homer.app.models.ResultEnvParam
import homer.app.models.ResultRef
import homer.app.services.persistence.ContinuationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.math.min

/**
 * Fires continuations for [ContinuationService] once what they watch is terminal: claims the row
 * (PENDING→DISPATCHING CAS) so concurrent hooks, race-close and boot replay fire it once,
 * re-checks the target, builds the resume payload from the watched runs' results and starts the
 * target, then settles the row DISPATCHED, deferred for a retry, or FAILED. Never throws into
 * the caller. See ADR-0023 and ADR-0026.
 */
internal class ContinuationFireService(
    private val repository: ContinuationRepository,
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    private val agentRunStarter: AgentRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val processManager: ProcessManager,
    private val resultTokenService: ResultTokenService,
    private val logger: Logger,
    private val nowSec: () -> Long,
) {
    /** Fires a single-watch [continuation] whose watched process ended with [status]. */
    suspend fun fire(
        continuation: Continuation,
        status: ProcessStatus,
    ) {
        if (!repository.claim(continuation.id)) return
        val now = nowSec()
        val agent = dispatchableTarget(continuation, now, "Continuation") ?: return
        startTarget(continuation, agent, now, "Continuation") {
            val extracted =
                extractResult(continuation.watchedProcessId, continuation.resultTransformer, continuation.targetAgentName)
            buildResumePayload(status, continuation.watchedProcessId, extracted.result, extracted.ref)
        }
    }

    /**
     * The target agent of a claimed [continuation] while it is still loaded, a dispatch
     * target, and reachable from the origin agent; otherwise the continuation is marked
     * FAILED and null is returned. [label] prefixes the log line.
     */
    private suspend fun dispatchableTarget(
        continuation: Continuation,
        now: Long,
        label: String,
    ): Agent? {
        val agent = agentsService.agents[continuation.targetAgentName]
        val msg =
            if (agent == null) {
                "Target agent '${continuation.targetAgentName}' not found"
            } else if (!authorizer.canDispatchHop(continuation.owner, continuation.originAgentName, continuation.targetAgentName)) {
                "agent '${continuation.originAgentName}' is not authorized to dispatch to '${continuation.targetAgentName}'"
            } else if (agent.dispatch?.enabled != true) {
                "Target agent '${continuation.targetAgentName}' is no longer an allowed dispatch target"
            } else {
                return agent
            }
        repository.markFailed(continuation.id, msg, now)
        logger.warn("$label {} failed: {}", continuation.id, msg)
        return null
    }

    /**
     * Starts [agent] for a claimed [continuation] with the resume payload [payload] builds
     * under [Continuation.resumeParam], and settles the continuation DISPATCHED, deferred or
     * FAILED. [label] prefixes the log lines and the fallback failure messages.
     */
    private suspend fun startTarget(
        continuation: Continuation,
        agent: Agent,
        now: Long,
        label: String,
        payload: suspend () -> String,
    ) {
        try {
            val inputParams = continuation.params + (continuation.resumeParam to payload())
            val processId =
                agentRunStarter.start(
                    agent,
                    emptyList(),
                    "continuation",
                    owner = continuation.owner,
                    extraSecretInputKeys = continuation.secretInputKeys.orEmpty(),
                    parentProcessId = continuation.originProcessId,
                    // [information_disclosure / CWE-200] The fired run is attributed to the
                    // continuation's owner, so the root it inherits is gated on that owner's
                    // visibility — a foreign flow-root id reaches no row, env or span. §12.
                    rootProcessId =
                        processManager.visibleOriginRootProcessId(
                            continuation.originProcessId,
                            continuation.owner,
                            authorizer,
                        ),
                ) {
                    agentInputResolver.resolveFromParams(inputParams, agent) + reservedControlParams(inputParams)
                }
            repository.markDispatched(continuation.id, processId, now)
            logger.info("$label {} fired -> run {}", continuation.id, processId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomerException) {
            settleFailedFire(continuation, e, "$label failed", now)
        } catch (e: Exception) {
            val msg = e.message ?: "Unexpected ${label.lowercase()} failure"
            repository.markFailed(continuation.id, msg, now)
            logger.warn("$label {} failed to fire (unexpected): {}", continuation.id, msg, e)
        }
    }

    /**
     * Fires a JOIN continuation once, after every watched sub is terminal. Claimed (PENDING→
     * DISPATCHING CAS) up front so concurrent terminal hooks, race-close, and boot replay all
     * collapse to exactly one fire. Aggregates each sub's terminal result (per-sub transformer,
     * over-cap → minted resultRef; absent/purged sub → UNKNOWN + null result, carried as partial)
     * into one map keyed by interrupt id, byte-for-byte the single-fanout payload per entry, then
     * starts the target with that map under [Continuation.resumeParam]. Never throws into the
     * caller. See ADR-0026.
     */
    suspend fun fireJoin(continuation: Continuation) {
        if (!repository.claim(continuation.id)) return
        val now = nowSec()
        val agent = dispatchableTarget(continuation, now, "Join continuation") ?: return
        startTarget(continuation, agent, now, "Join continuation") {
            val perSub =
                buildMap<String, JsonElement> {
                    for (watch in continuation.watches) {
                        val status = processManager.getProcessStatus(watch.watchedProcessId)
                        val extracted =
                            extractResult(watch.watchedProcessId, watch.resultTransformer, continuation.targetAgentName)
                        // Each entry is the same JSON string a single fanout delivers, kept as a string
                        // value so the parent's homer_fanout decodes its own per-branch payload unchanged.
                        put(
                            watch.interruptId,
                            JsonPrimitive(buildResumePayload(status, watch.watchedProcessId, extracted.result, extracted.ref)),
                        )
                    }
                }
            Json.encodeToString(JsonObject.serializer(), JsonObject(perSub))
        }
    }

    /** The inline result and/or read-by-reference [ResultRef] for a continuation's resume payload. */
    private data class ExtractedResult(
        val result: String?,
        val ref: ResultRef?,
    )

    /**
     * The transformed result value extracted from [watchedProcessId]'s final stdout. When the
     * stdout exceeds [MAX_RESULT_BYTES] the inline result is dropped and a read-by-reference
     * [ResultRef] (capability token) is minted instead, so the parked parent can fetch the full
     * result. Both are null when there is no stdout or it can't be read. The last execution's
     * stdout file is the canonical terminal output. Shared by the single-watch [fire] and the
     * per-sub aggregation in [fireJoin].
     */
    private suspend fun extractResult(
        watchedProcessId: Int,
        transformer: ResultEnvParam?,
        consumerAgent: String,
    ): ExtractedResult {
        val none = ExtractedResult(null, null)
        if (transformer == null) return none
        val (execution, artifactsDir) = lastExecution(watchedProcessId) ?: return none
        // ExecutionInfo stdOut/stdErr are stored relative to the process artifacts dir
        // (see AgentExecutor); resolve them the same way LogTailService.resolve does. The
        // relative stdOut path is also what the read-by-reference fetch endpoint resolves.
        val stdOutRel = execution.stdOut ?: return none
        val stdErrRel = execution.stdErr ?: return none
        val stdOut = artifactsDir.resolve(stdOutRel)
        val stdErr = artifactsDir.resolve(stdErrRel)
        val size = runCatching { stdOut.fileSize() }.getOrNull() ?: return none
        if (size > MAX_RESULT_BYTES) return oversizedResult(watchedProcessId, stdOutRel, transformer, size, consumerAgent)
        val result =
            runCatching {
                ResultToEnvMapper(CommandResult(execution.resultCode, stdOut, stdErr))
                    .mapToEnv(listOf(transformer))[transformer.envName]
            }.getOrNull()
        return ExtractedResult(result, null)
    }

    /** [watchedProcessId]'s last execution and the artifacts dir its paths are relative to; null when either is unknown. */
    private suspend fun lastExecution(watchedProcessId: Int): Pair<ExecutionInfo, Path>? {
        val execution = processManager.getProcessExecutions(watchedProcessId).lastOrNull() ?: return null
        val artifactsDir = processManager.getProcessInfo(watchedProcessId)?.artifactsDir ?: return null
        return execution to artifactsDir
    }

    /**
     * A result over [MAX_RESULT_BYTES] resumes by reference: a minted [ResultRef], or nothing
     * at all past [RESULT_TRANSFORM_MAX_BYTES], where the reference could never be redeemed.
     */
    private suspend fun oversizedResult(
        watchedProcessId: Int,
        stdOutRel: Path,
        transformer: ResultEnvParam,
        size: Long,
        consumerAgent: String,
    ): ExtractedResult {
        if (size > RESULT_TRANSFORM_MAX_BYTES) {
            // The fetch endpoint refuses to transform an artifact this large (413), so a ref
            // here could never be redeemed — typically a failed Claude step's NDJSON stdout.
            // Resume with result = null and no ref; the parent's own status check decides.
            logger.warn(
                "Watched process {} result of {} bytes exceeds the transform cap {}; resuming with result = null",
                watchedProcessId,
                size,
                RESULT_TRANSFORM_MAX_BYTES,
            )
            return ExtractedResult(null, null)
        }
        val token =
            resultTokenService.mint(
                watchedProcessId = watchedProcessId,
                path = stdOutRel.toString(),
                transformer = transformer,
                sizeBytes = size,
                consumerAgent = consumerAgent,
            )
        logger.info(
            "Watched process {} result of {} bytes exceeds cap {}; resuming by reference (token minted)",
            watchedProcessId,
            size,
            MAX_RESULT_BYTES,
        )
        return ExtractedResult(
            result = null,
            ref = ResultRef(watchedProcessId, stdOutRel.toString(), token, size),
        )
    }

    private fun buildResumePayload(
        status: ProcessStatus,
        processId: Int,
        result: String?,
        ref: ResultRef?,
    ): String =
        Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                buildMap {
                    put("status", JsonPrimitive(status.name))
                    put("processId", JsonPrimitive(processId))
                    put("result", result?.let { JsonPrimitive(it) } ?: JsonNull)
                    if (ref != null) put("resultRef", Json.encodeToJsonElement(ref))
                },
            ),
        )

    /**
     * Settles a fire that threw a [HomerException]. ADR-0023 promises the parent always
     * wakes, but a fire can fail for a reason that has nothing to do with this
     * continuation and may clear on its own — the target agent is over its cost cap, the
     * process table is full, the target's queue is full. Ending those as FAILED loses the
     * resume permanently, and the only trace was a WARN line; agents that use continuations
     * as their crash-reporting channel would lose the report of the crash.
     *
     * So: transient reasons go back to PENDING with the attempt counted and the next one
     * scheduled with exponential backoff, and the sweep re-fires them. Everything else
     * (target gone, not dispatchable, unauthorized, bad input) is permanent and still
     * FAILs immediately — retrying it would just burn sweep cycles. The retry itself is
     * bounded twice: by [MAX_FIRE_ATTEMPTS], and by the continuation's own TTL.
     */
    private suspend fun settleFailedFire(
        continuation: Continuation,
        e: HomerException,
        fallbackMessage: String,
        now: Long,
    ) {
        val msg = e.message ?: fallbackMessage
        val attempts = continuation.fireAttempts + 1
        if (!isTransient(e) || attempts >= MAX_FIRE_ATTEMPTS) {
            val reason = if (isTransient(e)) "$msg (gave up after $attempts attempts)" else msg
            repository.markFailed(continuation.id, reason, now)
            logger.warn("Continuation {} failed to fire: {}", continuation.id, reason)
            return
        }
        val delaySec = min(RETRY_MAX_DELAY_SEC, RETRY_BASE_DELAY_SEC shl (attempts - 1).coerceAtMost(BACKOFF_SHIFT_CAP))
        repository.releaseForRetry(continuation.id, msg, attempts, now + delaySec)
        logger.info(
            "Continuation {} fire deferred (attempt {}/{}, retry in {}s): {}",
            continuation.id,
            attempts,
            MAX_FIRE_ATTEMPTS,
            delaySec,
            msg,
        )
    }

    /** True for start-time rejections that reflect momentary server/agent state, not this continuation. */
    private fun isTransient(e: HomerException): Boolean =
        e is CooldownActiveException ||
            e is CostCapExceededException ||
            e is ProcessManagerAtCapacityException ||
            e is MaxQueuedRunsExceededException

    private companion object {
        // Inline results over this size resume by reference (result = null) rather than
        // inflating the resume payload / env. See ADR-0023.
        const val MAX_RESULT_BYTES = 64L * 1024

        // A transient fire failure is retried on a doubling delay, bounded by
        // MAX_FIRE_ATTEMPTS and by the continuation's own TTL. The ceiling is an hour so a
        // cost cap on an hourly window gets at least one attempt after the window rolls.
        const val MAX_FIRE_ATTEMPTS = 8
        const val RETRY_BASE_DELAY_SEC = 60L
        const val RETRY_MAX_DELAY_SEC = 3600L
        const val BACKOFF_SHIFT_CAP = 30
    }
}
