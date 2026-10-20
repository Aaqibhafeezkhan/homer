package homer.app.config

/**
 * Configuration for command execution defaults
 */
data class ExecutionConfig(
    /** Maximum output size to include in tracing (bytes) */
    val maxOutputSizeForTracing: Long = 4096,
)
