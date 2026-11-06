package homer.app.exceptions

import homer.app.models.PodTermination

/**
 * Raised when a worker pod disappears or transitions to a terminal
 * failure state (eviction, OOMKilled, node drain) while a command was
 * mid-execution. Distinguishes pod-level failure from command-level
 * exit-code failure so agents can surface a clear error.
 */
class PodFailedDuringExecutionException(
    val reason: String,
    message: String,
    val events: List<String>,
    cause: Throwable? = null,
    val termination: PodTermination? = null,
    stoppedSummary: String? = null,
) : RuntimeException(
        stoppedSummary?.let { "Kubernetes stopped the pod: $it" } ?: "Pod failed during execution ($reason): $message",
        cause,
    )
