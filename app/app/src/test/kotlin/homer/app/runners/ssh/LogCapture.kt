package homer.app.runners.ssh

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger

/** Run [body] with [loggerClass]'s WARN output captured. */
internal suspend fun captureWarnings(
    loggerClass: Class<*>,
    body: suspend () -> Unit,
): List<ILoggingEvent> {
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    val logback = LoggerFactory.getLogger(loggerClass) as LogbackLogger
    val level = logback.level
    logback.level = LogbackLevel.WARN
    logback.addAppender(appender)
    try {
        body()
    } finally {
        logback.detachAppender(appender)
        logback.level = level
    }
    return appender.list
}
