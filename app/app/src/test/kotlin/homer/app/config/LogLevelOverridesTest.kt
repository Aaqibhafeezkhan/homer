package homer.app.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogLevelOverridesTest {
    @Test
    fun `returns empty map when env var is absent`() {
        assertTrue(logLevelSystemProps(emptyMap()).isEmpty())
    }

    @Test
    fun `returns empty map when env var is blank`() {
        assertTrue(logLevelSystemProps(mapOf("HOMER_LOG_LEVELS" to "   ")).isEmpty())
    }

    @Test
    fun `maps a single logger=level pair to the slf4j-simple property`() {
        val props = logLevelSystemProps(mapOf("HOMER_LOG_LEVELS" to "homer.app.executors.ProcessExecutor=debug"))
        assertEquals(
            mapOf("org.slf4j.simpleLogger.log.homer.app.executors.ProcessExecutor" to "debug"),
            props,
        )
    }

    @Test
    fun `maps multiple comma-separated pairs and lowercases the level`() {
        val props =
            logLevelSystemProps(
                mapOf("HOMER_LOG_LEVELS" to "homer.app.executors.ProcessExecutor=TRACE, io.ktor=warn"),
            )
        assertEquals(
            mapOf(
                "org.slf4j.simpleLogger.log.homer.app.executors.ProcessExecutor" to "trace",
                "org.slf4j.simpleLogger.log.io.ktor" to "warn",
            ),
            props,
        )
    }

    @Test
    fun `skips entries with an unknown level`() {
        val props =
            logLevelSystemProps(
                mapOf("HOMER_LOG_LEVELS" to "homer.app=verbose,homer.app.services=debug"),
            )
        assertEquals(
            mapOf("org.slf4j.simpleLogger.log.homer.app.services" to "debug"),
            props,
        )
    }

    @Test
    fun `skips malformed pairs and empty logger names`() {
        val props =
            logLevelSystemProps(
                mapOf("HOMER_LOG_LEVELS" to "no-equals,=debug,homer.app=info"),
            )
        assertEquals(
            mapOf("org.slf4j.simpleLogger.log.homer.app" to "info"),
            props,
        )
    }
}
