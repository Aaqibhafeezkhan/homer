package homer.app.models

import kotlinx.serialization.Serializable

/** Body of `POST /api/v1/callback/dispatches`. */
@Serializable
data class CreateDispatchRequest(
    val text: String,
    val options: List<String> = emptyList(),
    val agent: String,
    val answerParam: String = "answer",
    val params: Map<String, String> = emptyMap(),
    val ttlSec: Int? = null,
)

@Serializable
data class CreateDispatchResponse(
    val dispatchId: Int,
    val questionId: String,
)

/**
 * Body of `POST /api/v1/callback/runs` (fire-and-forget script-driven dispatch).
 * The optional `continueAgent` block registers a completion continuation that
 * resumes the parent once the started sub-process reaches a terminal state.
 * See ADR-0023.
 *
 * `params` go to the dispatched sub-agent; `continueParams` go to the
 * continuation run (the resumed parent) — e.g. the LangGraph interrupt id,
 * which must reach the resumed parent, not the sub. Ignored without
 * `continueAgent`.
 */
@Serializable
data class CreateRunRequest(
    val agent: String,
    val params: Map<String, String> = emptyMap(),
    val continueAgent: String? = null,
    val continueThread: String? = null,
    val continueParams: Map<String, String> = emptyMap(),
    val resumeParam: String = "HMR_LG_RESUME",
    val resultJsonPath: String? = null,
    val ttlSec: Int? = null,
)

/** Response of `POST /api/v1/callback/runs`; `continuationId` is set only when a continuation was registered. */
@Serializable
data class CreateRunResponse(
    val processId: Int,
    val continuationId: Int? = null,
)

/**
 * Body of `POST /api/v1/callback/joins` (parallel fan-out join). Starts every sub in [subs] and
 * registers ONE join continuation that resumes [continueAgent] a single time after the last sub
 * reaches a terminal state, carrying all results keyed by interrupt id. See ADR-0026.
 *
 * `subs[*].params` go to each dispatched sub; `continueParams` (plus the thread id) go to the
 * continuation run — the resumed parent. The comma-joined interrupt ids ride in `continueParams`
 * as `HMR_LG_INTERRUPT`, signalling the runtime to resume with the full result map.
 */
@Serializable
data class CreateJoinRequest(
    val subs: List<JoinSubSpec>,
    val continueAgent: String,
    val continueThread: String? = null,
    val continueParams: Map<String, String> = emptyMap(),
    val resumeParam: String = "HMR_LG_RESUME",
    val ttlSec: Int? = null,
)

/**
 * One sub of a [CreateJoinRequest]: the agent to start fire-and-forget, its params, an optional
 * JsonPath transform applied to its result, and the LangGraph interrupt id its result is keyed
 * under in the resume map.
 */
@Serializable
data class JoinSubSpec(
    val agent: String,
    val params: Map<String, String> = emptyMap(),
    val resultJsonPath: String? = null,
    val interruptId: String,
)

/** Response of `POST /api/v1/callback/joins`: the started sub process ids and the registered join id. */
@Serializable
data class CreateJoinResponse(
    val processIds: List<Int>,
    val continuationId: Int,
)

/**
 * Reference to a large continuation result that exceeded the inline cap. Carried
 * in the resume payload as `resultRef` (with `result` null); the parked parent
 * fetches the full result via `GET /api/v1/callback/results/{token}`. See ADR-0025.
 */
@Serializable
data class ResultRef(
    val processId: Int,
    val path: String,
    val token: String,
    val sizeBytes: Long,
)

@Serializable
data class DispatchResponse(
    val id: Int,
    val questionId: String,
    val status: String,
    val agentName: String,
    val answerParam: String,
    val params: Map<String, String>,
    val originProcessId: Int,
    val originAgentName: String,
    val expiresAt: Long? = null,
    val createdAt: Long,
    val dispatchedAt: Long? = null,
    val dispatchedProcessId: Int? = null,
    val error: String? = null,
)

@Serializable
data class DispatchListResponse(
    val dispatches: List<DispatchResponse>,
    val total: Int, // Visible rows matching the filters, before pagination
    val offset: Int,
    val limit: Int,
)

/** Compact dispatch info embedded in a question (console inbox annotation). */
@Serializable
data class DispatchSummary(
    val id: Int,
    val agentName: String,
    val status: String,
    val dispatchedProcessId: Int? = null,
    val error: String? = null,
)
