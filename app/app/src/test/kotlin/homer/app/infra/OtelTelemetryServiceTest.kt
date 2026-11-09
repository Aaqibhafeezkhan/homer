package homer.app.infra

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import io.mockk.mockk
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger
import java.util.logging.Handler as JulHandler
import java.util.logging.LogRecord as JulLogRecord
import java.util.logging.Logger as JulLogger

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

    // ========== The copy processor as wired in the service ==========

    /**
     * The wiring only the real service has: [LangfuseTraceAttributesSpanProcessor] registered
     * ahead of the batch processor in the service's own tracer provider. Deleting the
     * registration — or moving it after the batch processor — must fail here, on what the
     * exporter actually receives: a stamped root, a child started under it from the service's
     * tracer, and the exported child carrying both keys. The batch processor is driven with the
     * logging exporter (as the `LOG` export config builds it), whose output this captures.
     */
    @Test
    fun `a child span of a stamped root is exported with the trace name and tags`() {
        val lines = mutableListOf<String>()
        val exporterLogger = JulLogger.getLogger("io.opentelemetry.exporter.logging.LoggingSpanExporter")
        val handler =
            object : JulHandler() {
                override fun publish(record: JulLogRecord) {
                    lines += record.message
                }

                override fun flush() {}

                override fun close() {}
            }
        exporterLogger.addHandler(handler)
        try {
            val service = OtelTelemetryService(langfuseConfig, OpenTelemetryExport.LOG)
            val agent =
                Agent(
                    name = "wired_agent",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "hi"), timeout = 60)),
                )
            val root = service.tracer.spanBuilder("otel-wired-root").startSpan()
            root.stampLangfuseTrace(agent, listOf("http", "mr:15"))
            val child =
                service.tracer
                    .spanBuilder("otel-wired-child")
                    .setParent(root.storeInContext(Context.current()))
                    .startSpan()
            child.end()
            root.end()
            forceFlush(service)

            val childLine = lines.single { it.contains("otel-wired-child") }
            assertTrue(
                childLine.contains("langfuse.trace.name") && childLine.contains("wired_agent"),
                "the exported child must carry the trace name: $childLine",
            )
            assertTrue(
                childLine.contains("langfuse.trace.tags") && childLine.contains("mr:15"),
                "the exported child must carry the trace tags: $childLine",
            )
        } finally {
            exporterLogger.removeHandler(handler)
        }
    }

    /** The reflection reaches the private SDK handle; the stub tests flush the same way. */
    private fun forceFlush(service: OtelTelemetryService) {
        val field = OtelTelemetryService::class.java.getDeclaredField("openTelemetry")
        field.isAccessible = true
        (field.get(service) as OpenTelemetrySdk).sdkTracerProvider.forceFlush()
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

    // ========== OTLP export against a local stand-in for the Langfuse collector ==========

    /**
     * What actually leaves the process. The ingestion header is the difference between Langfuse
     * v4 showing a span within seconds and within minutes, and a collector is the only place it
     * can be observed — it reaches no log and no span. Same stub pattern as
     * `DefaultLangfuseClientTest`.
     */
    @Test
    fun `an exported batch carries the ingestion version and the Basic authorization header`() =
        withCollectorStub { server, requests ->
            exportOneSpan(
                OtelTelemetryService(
                    langfuseConfig.copy(serverUrl = "http://127.0.0.1:${server.address.port}"),
                    OpenTelemetryExport.LANGFUSE,
                ),
            )

            val request = awaitRequests(requests).single()
            assertEquals("/api/public/otel/v1/traces", request.path)
            assertEquals("4", request.headers.getFirst(LANGFUSE_INGESTION_VERSION_HEADER))
            val authorization = request.headers.getFirst("Authorization")
            assertTrue(
                authorization != null && authorization.startsWith("Basic "),
                "the OTLP request must be authenticated",
            )
            // Decoded rather than compared as text, so a failure message never echoes the header.
            assertEquals(
                "pk-test:sk-test",
                String(Base64.getDecoder().decode(authorization.removePrefix("Basic "))),
                "the Basic credentials must be the configured public:secret pair",
            )
        }

    /**
     * The denial half: a span must not reach a collector the configuration did not select. A
     * `serverUrl` in the config is not on its own a licence to export to it.
     */
    @Test
    fun `a logging export and a blank serverUrl both leave the collector untouched`() =
        withCollectorStub { server, requests ->
            val url = "http://127.0.0.1:${server.address.port}"
            exportOneSpan(OtelTelemetryService(langfuseConfig.copy(serverUrl = url), OpenTelemetryExport.LOG))
            exportOneSpan(OtelTelemetryService(LangfuseConfig(), OpenTelemetryExport.LANGFUSE))

            assertEquals(emptyList(), requests, "no collector may be called")
        }

    /** Ends one span and flushes the batch processor, whose schedule delay is otherwise 5 s. */
    private fun exportOneSpan(service: OtelTelemetryService) {
        service.tracer
            .spanBuilder("test-span")
            .startSpan()
            .end()
        forceFlush(service)
    }

    /** The exporter sends on its own thread, so the request can land after `forceFlush` returns. */
    private fun awaitRequests(requests: List<CapturedRequest>): List<CapturedRequest> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (requests.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        return requests
    }

    /** A local stand-in for the Langfuse OTLP collector: records each request and answers 200. */
    private fun <T> withCollectorStub(block: (HttpServer, List<CapturedRequest>) -> T): T {
        val requests = java.util.Collections.synchronizedList(mutableListOf<CapturedRequest>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += CapturedRequest(exchange.requestURI.toString(), exchange.requestHeaders)
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            return block(server, requests)
        } finally {
            server.stop(0)
        }
    }

    private class CapturedRequest(
        val path: String,
        val headers: Headers,
    )
}
