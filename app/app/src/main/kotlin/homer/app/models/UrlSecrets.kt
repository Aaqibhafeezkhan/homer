package homer.app.models

import java.net.URI
import java.net.URISyntaxException

/**
 * Drops the parts of a URL that carry secrets — userinfo, query and fragment —
 * keeping scheme, host, port and path. Returns null when the value will not parse,
 * which is exactly the case where guessing which part is sensitive is least safe.
 *
 * `langfuse.serverUrl` is operator-supplied and reaches two sinks: the OTLP endpoint
 * log line in `OtelTelemetryService` and the `langfuseTraceUrl` field that
 * `ResponseMapping` sets on every process API response. Both use this, so a
 * credential in the configured URL leaks to neither.
 */
internal fun stripUrlSecrets(raw: String): String? =
    try {
        val uri = URI(raw)
        if (uri.userInfo == null && uri.query == null && uri.fragment == null) {
            raw
        } else {
            // Rebuild from the raw authority minus userinfo, not `URI.host`: for a
            // legal reg-name host such as `homer_internal` (accepted by
            // isHttpsOrLoopbackHttpUrl) `host` is null and a host-based rebuild
            // would drop it. CWE-522.
            val rawAuthority = uri.rawAuthority
            val authority = rawAuthority?.substringAfterLast('@')
            URI(uri.scheme, authority, uri.path, null, null).toString()
        }
    } catch (_: URISyntaxException) {
        null
    }
