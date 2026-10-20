package homer.app.infra

import io.opentelemetry.api.trace.Tracer

/**
 * Interface for telemetry service providing OpenTelemetry tracing capabilities
 */
interface TelemetryService {
    val tracer: Tracer
}
