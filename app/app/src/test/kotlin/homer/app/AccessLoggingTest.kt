package homer.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.util.FileSize
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger

class AccessLoggingTest {
    // ---- isNoisyAccessPath ----

    @Test
    fun `health heartbeat livez and stream paths are excluded from the access log`() {
        assertTrue(isNoisyAccessPath("/api/v1/health"))
        assertTrue(isNoisyAccessPath("/api/v1/heartbeat"))
        assertTrue(isNoisyAccessPath("/api/v1/livez"))
        assertTrue(isNoisyAccessPath("/api/v1/stream/agents"))
        assertTrue(isNoisyAccessPath("/api/v1/stream/processes/42/logs"))
        assertTrue(isNoisyAccessPath("/api/v1/runner/instances/abc/claim"))
        assertTrue(isNoisyAccessPath("/api/v1/runner/sessions/abc/next"))
        // One line per 64 KiB output chunk would drown the log in a single run.
        assertTrue(isNoisyAccessPath("/api/v1/runner/sessions/abc/ops/op-1/output"))
    }

    @Test
    fun `regular API paths are included in the access log`() {
        assertFalse(isNoisyAccessPath("/api/v1/agents"))
        assertFalse(isNoisyAccessPath("/api/v1/status/all"))
        assertFalse(isNoisyAccessPath("/api/v1/status/42"))
        assertFalse(isNoisyAccessPath("/api/v1/agent/factory"))
        assertFalse(isNoisyAccessPath("/api/v1/runner/register"))
        assertFalse(isNoisyAccessPath("/api/v1/runner/sessions/abc/ops/op-1/tar"))
    }

    // ---- redactAccessLogPath ----

    @Test
    fun `access log path redacts the result capability token`() {
        // The result token is a capability carried in the URL path; logging it to
        // api.log verbatim would let a co-located process harvest it.
        assertEquals(
            "/api/v1/callback/results/***",
            redactAccessLogPath("/api/v1/callback/results/2f6b0c8e9a1d4f7b8c3e5a6d7f9b0c1e"),
        )
    }

    @Test
    fun `access log path leaves non-secret paths unchanged`() {
        assertEquals("/api/v1/agents", redactAccessLogPath("/api/v1/agents"))
        // question id is a UUID, not a secret capability
        assertEquals(
            "/api/v1/callback/questions/abc-123",
            redactAccessLogPath("/api/v1/callback/questions/abc-123"),
        )
    }

    // ---- logback.xml configuration ----

    @Test
    fun `HOMER_LOG_LEVEL overrides the root level in the production logback config`(
        @TempDir tmp: Path,
    ) {
        // Isolated context so the global test logging setup is untouched; api.log is
        // pointed at a temp dir so loading the config creates no repo litter.
        System.setProperty("HOMER_LOG_DIR", tmp.toString())
        System.setProperty("HOMER_LOG_LEVEL", "DEBUG")
        val ctx = LoggerContext()
        try {
            val configurator = JoranConfigurator().apply { context = ctx }
            javaClass.getResourceAsStream("/logback.xml").use { configurator.doConfigure(it) }
            val root = ctx.getLogger(Logger.ROOT_LOGGER_NAME)
            assertEquals(LogbackLevel.DEBUG, root.level)
        } finally {
            ctx.stop()
            System.clearProperty("HOMER_LOG_DIR")
            System.clearProperty("HOMER_LOG_LEVEL")
        }
    }

    @Test
    fun `production logback config writes app log to a size-rotated file in HOMER_LOG_DIR`(
        @TempDir tmp: Path,
    ) {
        System.setProperty("HOMER_LOG_DIR", tmp.toString())
        // A bare LoggerContext has no MDC adapter; appending would NPE without one.
        val ctx = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
        try {
            val configurator = JoranConfigurator().apply { context = ctx }
            javaClass.getResourceAsStream("/logback.xml").use { configurator.doConfigure(it) }
            val root = ctx.getLogger(Logger.ROOT_LOGGER_NAME)

            assertTrue(root.getAppender("CONSOLE") is ConsoleAppender<*>, "stdout must keep the full log")
            val file = root.getAppender("APP_FILE") as RollingFileAppender<ILoggingEvent>
            assertEquals(tmp.resolve("app.log").toString(), file.file)
            val rolling = file.rollingPolicy as FixedWindowRollingPolicy
            assertEquals(3, rolling.maxIndex)
            val trigger = file.triggeringPolicy as SizeBasedTriggeringPolicy<ILoggingEvent>
            assertEquals(FileSize.valueOf("10MB").size, trigger.maxFileSize.size)

            ctx.getLogger("homer.app.Probe").info("app-line")
            ctx.getLogger("homer.access").info("200: GET /api/v1/agents")
            ctx.stop()

            val appLog = tmp.resolve("app.log").readText()
            assertTrue(appLog.contains("app-line"))
            assertFalse(appLog.contains("/api/v1/agents"), "access lines must stay out of app.log")
        } finally {
            ctx.stop()
            System.clearProperty("HOMER_LOG_DIR")
        }
    }

    // ---- CallLogging routing to the homer.access logger ----

    @Test
    fun `requests are logged to the access logger but noisy paths are not`() {
        val accessLogger = LoggerFactory.getLogger("homer.access") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val wasAdditive = accessLogger.isAdditive
        accessLogger.isAdditive = false
        accessLogger.addAppender(appender)
        try {
            testApplication {
                application {
                    configureCallLogging()
                    routing {
                        get("/api/v1/agents") { call.respond(HttpStatusCode.OK) }
                        get("/api/v1/health") { call.respond(HttpStatusCode.OK) }
                        get("/api/v1/stream/agents") { call.respond(HttpStatusCode.OK) }
                    }
                }
                client.get("/api/v1/agents")
                client.get("/api/v1/health")
                client.get("/api/v1/stream/agents")
            }

            val messages = appender.list.map { it.formattedMessage }
            assertEquals(
                1,
                messages.size,
                "only the non-noisy request should be logged; got $messages",
            )
            assertTrue(messages.single().contains("/api/v1/agents"))
            assertTrue(messages.single().contains("GET"))
            assertFalse(messages.any { it.contains("/api/v1/health") })
            assertFalse(messages.any { it.contains("/api/v1/stream/agents") })
        } finally {
            accessLogger.detachAppender(appender)
            accessLogger.isAdditive = wasAdditive
        }
    }
}
