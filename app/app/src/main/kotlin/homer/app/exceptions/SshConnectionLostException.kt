package homer.app.exceptions

/**
 * Thrown when an SSH exec channel drops before yielding an exit status, so the
 * command's outcome is unknown. Never treated as success — sibling of
 * PodFailedDuringExecutionException.
 */
class SshConnectionLostException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
