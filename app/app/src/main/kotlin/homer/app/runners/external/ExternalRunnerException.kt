package homer.app.runners.external

/**
 * A pull-mode instance failed or vanished mid-run. Not a `HomerException`: it is
 * thrown inside the launched run, where `ProcessExecutor` turns it into a FAILED
 * process, and never reaches the HTTP boundary.
 */
class ExternalRunnerException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
