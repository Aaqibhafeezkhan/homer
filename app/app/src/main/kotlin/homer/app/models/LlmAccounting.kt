package homer.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Charset/length gate for a Claude session id at every point it crosses a trust boundary: the
 * id is read from agent-controlled stdout (§12 rule 2), then stored in the executions JSON
 * column and the status API, then used to build an outbound URL. [homer.app.infra.scanLlmAccounting]
 * enforces it where the id enters storage; [homer.app.infra.LlmCostResolver] enforces it
 * again before use (defense in depth). See ADR-0061.
 */
val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")

/** Which rung of the pricing ladder produced a cost figure. See ADR-0061. */
@Serializable
enum class CostSource {
    /** The operator's session-spend endpoint answered. */
    @SerialName("session")
    SESSION,

    /** The Claude CLI's own `total_cost_usd` (its built-in rate card), or 0.0 when no result event existed. */
    @SerialName("cli")
    CLI,

    /** The harness reported no cost and no spend endpoint answered; nothing was charged. */
    @SerialName("none")
    NONE,
    ;

    /** Lowercase wire form, also used for the `processes.cost_source` column. */
    val wire: String get() = name.lowercase()
}

/**
 * Output of `LlmCostResolver.resolve`. `fallbackReason` is set when the figure is degraded — a
 * configured higher rung failed, or a session figure was charged before the endpoint had ingested
 * every call of the session.
 */
data class ResolvedCost(
    val costUsd: Double,
    val source: CostSource,
    val fallbackReason: String? = null,
)

/** Per-execution accounting stored in the process record's executions JSON and returned on the status API. */
@Serializable
data class LlmAccounting(
    val sessionId: String?,
    val costUsd: Double,
    val source: CostSource,
    val fallbackReason: String? = null,
    // Token usage summed over every turn end of the winning attempt; null when no turn end was seen.
    val usage: LlmUsage? = null,
)

/**
 * The fixed-vocabulary `fallbackReason` (SERVER.md) for an attempt whose stdout held no result
 * event: the executor's accounting and the telemetry fallback generation must report the same
 * wire value for the same case, so they share one constant.
 */
const val FALLBACK_REASON_NO_RESULT_EVENT = "no result event"

/** The attempt returned on the harness figure; the session lookup settles it in the background (ADR-0089). */
const val FALLBACK_REASON_SESSION_FIGURE_PENDING = "session figure pending"
