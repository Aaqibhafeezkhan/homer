package homer.app.infra

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import io.mockk.mockk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger

class OtelTelemetryServiceTest {
    private val langfuseConfig =
        LangfuseConfig(
            serverUrl = "http://localhost:3000",
            publicKey = "pk-test",
            secretKey = "sk-test",
        )

    @Test
    fun `initializes without exception using LOGGING export`() {
        OtelTelemetryService(langfuseConfig, OpenTelemetryExport.LOG)
    }

    @Test
    fun `tracer is non-null`() {
        val service = OtelTelemetryService(langfuseConfig, OpenTelemetryExport.LOG)
        assertNotNull(service.tracer)
    }

    @Test
    fun `tracer can create and end a span`() {
        val service = OtelTelemetryService(langfuseConfig, OpenTelemetryExport.LOG)
        val span = service.tracer.spanBuilder("test-span").startSpan()
        span.end()
    }

    @Test
    fun `LANGFUSE export with blank serverUrl falls back to logging exporter`() {
        val service = OtelTelemetryService(LangfuseConfig(), OpenTelemetryExport.LANGFUSE)
        assertNotNull(service.tracer)
    }

    @Test
    fun `a failed export is reported at WARN with the endpoint`() {
        val cause = RuntimeException("connection refused")
        val events =
            captureExporterLog(
                StubSpanExporter(CompletableResultCode.ofExceptionalFailure(cause)),
            )

        val warnings = events.filter { it.level == LogbackLevel.WARN }
        assertEquals(1, warnings.size, "expected exactly one WARN, got $events")
        val warning = warnings.single()
        assertTrue(
            warning.formattedMessage.contains("3 OpenTelemetry span(s)") &&
                warning.formattedMessage.contains("http://langfuse.invalid/api/public/otel/v1/traces"),
            "WARN must name the batch size and the endpoint: ${warning.formattedMessage}",
        )
        // Cause named in the message, and NOT attached as a throwable: a stack trace
        // from an OTLP HTTP client quotes the request URL, which would carry whatever
        // the operator put in serverUrl straight past the endpoint sanitization.
        assertTrue(
            warning.formattedMessage.contains(cause.javaClass.name) &&
                warning.formattedMessage.contains("connection refused"),
            "WARN must name the cause type and message: ${warning.formattedMessage}",
        )
        assertEquals(null, warning.throwableProxy, "the raw throwable must not be attached")
    }

    @Test
    fun `a failed export does not echo a credential carried in the exception message`() {
        val cause = RuntimeException("POST https://user:pa55w0rd@langfuse.invalid/v1/traces failed")
        val events =
            captureExporterLog(
                StubSpanExporter(CompletableResultCode.ofExceptionalFailure(cause)),
            )

        val warning = events.single { it.level == LogbackLevel.WARN }
        assertTrue("pa55w0rd" !in warning.formattedMessage, "the WARN leaked a credential: ${warning.formattedMessage}")
        assertTrue("<redacted-url>" in warning.formattedMessage, "the URL should be blanked: ${warning.formattedMessage}")
    }

    @Test
    fun `a successful export logs nothing`() {
        val events = captureExporterLog(StubSpanExporter(CompletableResultCode.ofSuccess()))
        assertEquals(emptyList(), events.map { it.formattedMessage })
    }

    private fun captureExporterLog(delegate: SpanExporter): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(FailureLoggingSpanExporter::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            FailureLoggingSpanExporter(delegate, "http://langfuse.invalid/api/public/otel/v1/traces")
                .export(List(3) { mockk<SpanData>() })
            return appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private class StubSpanExporter(
        private val result: CompletableResultCode,
    ) : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode = result

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    // ========== Endpoint sanitization before logging ==========

    @Test
    fun `sanitizeEndpointForLog strips credentials from the userinfo`() {
        assertEquals(
            "https://collector.example.com/api/public/otel/v1/traces",
            sanitizeEndpointForLog("https://user:pa55w0rd@collector.example.com/api/public/otel/v1/traces"),
        )
    }

    @Test
    fun `sanitizeEndpointForLog strips a query token and a fragment`() {
        assertEquals(
            "https://collector.example.com/ingest",
            sanitizeEndpointForLog("https://collector.example.com/ingest?token=s3cret#frag"),
        )
    }

    @Test
    fun `sanitizeEndpointForLog keeps an ordinary endpoint verbatim, port included`() {
        assertEquals(
            "http://localhost:3000/api/public/otel/v1/traces",
            sanitizeEndpointForLog("http://localhost:3000/api/public/otel/v1/traces"),
        )
    }

    @Test
    fun `sanitizeEndpointForLog does not echo an unparseable value`() {
        // Where the string will not parse is exactly where guessing which part is
        // sensitive is least safe, so nothing of it is logged.
        assertEquals("<unparseable endpoint>", sanitizeEndpointForLog("http://ho st:/// not a uri"))
    }

    @Test
    fun `redactUrls blanks a URL carrying credentials inside an exception message`() {
        // The shape an OTLP HTTP client actually produces: the request URL quoted in the
        // message, carrying whatever the operator configured.
        assertEquals(
            "Failed to connect to <redacted-url>",
            redactUrls("Failed to connect to https://user:pa55w0rd@lf.example.test/api/public/otel/v1/traces"),
        )
    }

    @Test
    fun `redactUrls blanks every URL in the message, not just the first`() {
        assertEquals(
            "<redacted-url> then <redacted-url>",
            redactUrls("https://a.example?token=s3cret then http://b.example#f"),
        )
    }

    @Test
    fun `redactUrls keeps text that holds no URL`() {
        assertEquals("connection reset by peer", redactUrls("connection reset by peer"))
    }

    @Test
    fun `redactUrls reports a null message rather than printing null`() {
        assertEquals("<no message>", redactUrls(null))
    }
}
