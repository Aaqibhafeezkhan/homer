package homer.app.exceptions

import homer.app.models.PodTermination

/**
 * Raised when a worker pod disappears or transitions to a terminal
 * failure state (eviction, OOMKilled, node drain) while a command was
 * mid-execution, or when the exec channel into a still-running pod is
 * lost. Distinguishes pod-level failure from command-level exit-code
 * failure so agents can surface a clear error. A channel-lost [message]
 * already opens with the self-describing lost-channel sentence — kept on
 * `runner.termination` too — so no prefix is added for it.
 */
class PodFailedDuringExecutionException(
    val reason: String,
    message: String,
    val events: List<String>,
    cause: Throwable? = null,
    val termination: PodTermination? = null,
    stoppedSummary: String? = null,
    channelLost: Boolean = false,
) : RuntimeException(
        when {
            stoppedSummary != null -> "Kubernetes stopped the pod: $stoppedSummary"
            channelLost -> message
            else -> "Pod failed during execution ($reason): $message"
        },
        cause,
    )
