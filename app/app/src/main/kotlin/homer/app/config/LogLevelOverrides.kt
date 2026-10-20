package homer.app.config

/**
 * Translates the `HOMER_LOG_LEVELS` environment variable into slf4j-simple
 * per-logger system-property overrides, so operators can re-target log levels
 * at runtime (e.g. in a container) without editing the bundled
 * `simplelogger.properties`.
 *
 * Format: comma-separated `logger=level` pairs, e.g.
 * `HOMER_LOG_LEVELS=homer.app.executors.ProcessExecutor=debug,io.ktor=warn`.
 *
 * Levels are slf4j-simple's: `trace`, `debug`, `info`, `warn`, `error`, `off`
 * (case-insensitive). Malformed pairs and unknown levels are skipped.
 *
 * Returns the slf4j-simple property map (`org.slf4j.simpleLogger.log.<logger>`
 * -> level). The caller applies each entry only when the property is not
 * already set, so an explicit `-D` flag always wins. A logger constructed
 * before the override is applied (e.g. the top-level `homer.app.Application`
 * logger created at class load) keeps its initial level; overrides take effect
 * for loggers created afterward, which covers the DI-instantiated services and
 * executors.
 */
private const val ENV_LOG_LEVELS = "HOMER_LOG_LEVELS"
private const val SLF4J_LOG_PREFIX = "org.slf4j.simpleLogger.log."
private val VALID_LEVELS = setOf("trace", "debug", "info", "warn", "error", "off")

fun logLevelSystemProps(env: Map<String, String>): Map<String, String> {
    val raw = env[ENV_LOG_LEVELS]?.takeIf { it.isNotBlank() } ?: return emptyMap()
    return raw
        .split(",")
        .mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val logger = parts[0].trim()
            val level = parts[1].trim().lowercase()
            if (logger.isEmpty() || level !in VALID_LEVELS) return@mapNotNull null
            "$SLF4J_LOG_PREFIX$logger" to level
        }.toMap()
}
