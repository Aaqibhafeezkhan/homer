package homer.app.exceptions

/**
 * Raised when a worker pod fails to become ready within the configured
 * startup timeout, or terminates before readiness with a non-zero
 * init/main container status.
 */
class PodStartupException(
    val reason: String,
    val rawMessage: String,
    val events: List<String>,
    cause: Throwable? = null,
) : RuntimeException("Pod startup failed ($reason): $rawMessage", cause)
