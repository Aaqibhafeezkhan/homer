package homer.app.infra

import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.stripUrlSecrets
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.semconv.ServiceAttributes
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

class OtelTelemetryService(
    langfuseConfig: LangfuseConfig,
    export: OpenTelemetryExport,
) : TelemetryService {
    private val logger = LoggerFactory.getLogger(OtelTelemetryService::class.java)
    private val openTelemetry: OpenTelemetry
    override val tracer: Tracer

    init {
        // Create resource with service name
        val resource =
            Resource.getDefault().merge(
                Resource.create(
                    Attributes
                        .builder()
                        .put(ServiceAttributes.SERVICE_NAME, "homer-app")
                        .build(),
                ),
            )

        // Create Basic Auth header for Langfuse
        val credentials = "${langfuseConfig.publicKey}:${langfuseConfig.secretKey}"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())
        val authHeader = "Basic $encodedCredentials"

        val exporter =
            if (export == OpenTelemetryExport.LANGFUSE && langfuseConfig.serverUrl.isNotBlank()) {
                val endpoint = "${langfuseConfig.serverUrl}/api/public/otel/v1/traces"
                // Log the sanitized form only: `serverUrl` is operator-supplied, and a
                // `https://user:pass@host` or `?token=` form would otherwise put a
                // credential in app.log — which, in the container layout, sits in the
                // workspace an agent can read. The exporter still gets the real URL.
                val loggableEndpoint = sanitizeEndpointForLog(endpoint)
                logger.info("Exporting OpenTelemetry traces to {}", loggableEndpoint)
                FailureLoggingSpanExporter(
                    OtlpHttpSpanExporter
                        .builder()
                        .setEndpoint(endpoint)
                        .addHeader("Authorization", authHeader)
                        .build(),
                    loggableEndpoint,
                )
            } else {
                if (export == OpenTelemetryExport.LANGFUSE) {
                    logger.warn("langfuse.serverUrl is blank; falling back to the logging span exporter")
                }
                logger.info("Exporting OpenTelemetry traces to the application log (openTelemetryExport={})", export)
                LoggingSpanExporter.create()
            }

        // Create tracer provider with batch processor
        val tracerProvider =
            SdkTracerProvider
                .builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build()

        // Build OpenTelemetry SDK
        openTelemetry =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(tracerProvider)
                .build()

        // Get tracer instance
        tracer = openTelemetry.getTracer("homer.app", "1.0.0")

        // Shutdown hook to flush traces on exit
        Runtime.getRuntime().addShutdownHook(
            Thread {
                tracerProvider.close()
            },
        )
    }
}

/**
 * Blanks any URL in [message]. OTLP HTTP client exceptions routinely quote the request
 * URL, which carries whatever the operator put in `langfuse.serverUrl` — so sanitizing
 * the endpoint string and then logging the raw throwable beside it would defeat the
 * point. The endpoint is already logged separately in its sanitized form, so nothing
 * readable is lost by blanking the copy inside the message.
 */
internal fun redactUrls(message: String?): String =
    message?.replace(Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S*"), "<redacted-url>") ?: "<no message>"

/** [stripUrlSecrets] for the log sink, where an unparseable value still needs a string. */
internal fun sanitizeEndpointForLog(raw: String): String = stripUrlSecrets(raw) ?: "<unparseable endpoint>"

/**
 * Reports a failed span export through Homer's own logger. The SDK's own report
 * goes out on `java.util.logging`, which nothing here bridges into logback: it
 * bypasses the `io.opentelemetry` logger, names no endpoint, and is throttled to
 * once a minute under a sustained outage.
 */
internal class FailureLoggingSpanExporter(
    private val delegate: SpanExporter,
    private val endpoint: String,
) : SpanExporter {
    private val logger = LoggerFactory.getLogger(FailureLoggingSpanExporter::class.java)

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        val result = delegate.export(spans)
        return result.whenComplete {
            if (!result.isSuccess) {
                // Type and a URL-stripped message, never the raw throwable: OTLP HTTP
                // client exceptions quote the request URL, so attaching it would undo
                // the endpoint sanitization two lines up. Not demoted to DEBUG either —
                // docker-compose.dev.yml runs at DEBUG, so that still writes it.
                val cause = result.failureThrowable
                logger.warn(
                    "Failed to export {} OpenTelemetry span(s) to {}; this batch is lost (cause={}: {})",
                    spans.size,
                    endpoint,
                    cause?.javaClass?.name ?: "<unknown>",
                    redactUrls(cause?.message),
                )
            }
        }
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()
}
