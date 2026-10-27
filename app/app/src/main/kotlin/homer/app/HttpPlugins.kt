package homer.app

import homer.app.auth.CSRF_HEADER
import homer.app.exceptions.AgentException
import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.BodylessBadRequestException
import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.ClaudeCliVersionMismatchException
import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.ConfigurationException
import homer.app.exceptions.ContinuationNotFoundException
import homer.app.exceptions.ContinuationNotPendingException
import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.CsrfValidationException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.DispatchNotFoundException
import homer.app.exceptions.DispatchNotPendingException
import homer.app.exceptions.HomerException
import homer.app.exceptions.InvocationCycleDetectedException
import homer.app.exceptions.InvocationDepthExceededException
import homer.app.exceptions.MaintenanceQueueFullException
import homer.app.exceptions.MaintenanceWaitTimeoutException
import homer.app.exceptions.MalformedLlmOutputException
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.PayloadSchemaValidationException
import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.QuestionNotFoundException
import homer.app.exceptions.QuestionNotOpenException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.RunNotAcceptingMessagesException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.exceptions.RunnerOutputSequenceException
import homer.app.exceptions.RunnerRegistrationRejectedException
import homer.app.exceptions.RunnerTransferTimeoutException
import homer.app.exceptions.RunnerTransferTooLargeException
import homer.app.exceptions.TooManyConcurrentWaitsException
import homer.app.exceptions.TooManyOpenQuestionsException
import homer.app.exceptions.TooManyPendingContinuationsException
import homer.app.exceptions.TooManyPendingDispatchesException
import homer.app.exceptions.TooManyRunMessagesException
import homer.app.exceptions.TooManyRunnerInstancesException
import homer.app.exceptions.TooManyRunnerPollsException
import homer.app.exceptions.TooManyRunnerTransfersException
import homer.app.exceptions.TooManySessionPollsException
import homer.app.exceptions.ValidationException
import homer.app.exceptions.WorkspaceException
import homer.app.models.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.net.URI

private val logger = LoggerFactory.getLogger("homer.app.Application")

fun Application.configureCors(allowedOrigins: List<String>) {
    install(CORS) {
        if (allowedOrigins.isEmpty()) {
            anyHost()
        } else {
            allowedOrigins.forEach { origin ->
                val uri =
                    try {
                        URI(origin)
                    } catch (e: java.net.URISyntaxException) {
                        throw IllegalArgumentException("Invalid CORS origin '$origin': ${e.reason}", e)
                    }
                val scheme =
                    uri.scheme
                        ?: throw IllegalArgumentException(
                            "CORS origin '$origin' must include a scheme (e.g. http:// or https://)",
                        )
                val host = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
                allowHost(host, schemes = listOf(scheme))
            }
        }

        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        // Cookie-auth clients send this on every request; it is non-safelisted,
        // so a cross-origin console preflight must see it in Allow-Headers or the
        // browser blocks the real request. See ADR-0016.
        allowHeader(CSRF_HEADER)
        // Same reason: a cross-origin caller linking its run to a parent (ADR-0058)
        // sends this non-safelisted header and must pass preflight.
        allowHeader(EnvConstants.PARENT_PROCESS_HEADER)

        // Custom response headers are hidden from cross-origin JS unless listed
        // in Access-Control-Expose-Headers. The console reads both off the
        // artifact endpoint; without this it sees `null` and treats every
        // finished artifact as still streaming (stuck Live badge).
        exposeHeader("X-File-Complete")
        exposeHeader("X-Process-Status")

        // Credentialed CORS only with pinned origins: anyHost() + credentials
        // would reflect any Origin and let any site read the API with the
        // victim's cookie. Empty origins keep anyHost() without credentials
        // (bearer mode is unaffected). See ADR-0016.
        allowCredentials = allowedOrigins.isNotEmpty()
    }
}

/**
 * Default response-hardening headers applied to every Ktor response.
 *
 * - `X-Frame-Options: DENY` — Homer's responses must never be embedded in a
 *   frame; a console served from the same origin (or any origin) inherits
 *   this and is therefore not clickjackable through a Homer-API page.
 * - `X-Content-Type-Options: nosniff` — disables browser MIME sniffing on
 *   our JSON / artifact responses, so a content-type confusion attack can't
 *   coerce a script execution from a JSON body.
 * - `Referrer-Policy: same-origin` — strips the referer on cross-origin
 *   navigations so internal URLs (which may carry process IDs or other
 *   semi-sensitive identifiers) do not leak to third-party sites.
 * - `Content-Security-Policy: default-src 'self'` — Homer's API endpoints
 *   never return HTML and never load remote subresources, so the policy can
 *   be maximally restrictive. Future HTML surfaces will need an explicit
 *   policy override before they ship.
 *
 * `Strict-Transport-Security` is intentionally omitted until the deployment
 * docs commit to HTTPS-only — emitting HSTS over HTTP would be a footgun for
 * single-node local installs. Audit P1 #13.
 */
val SecurityHeaders =
    createApplicationPlugin("SecurityHeaders") {
        onCall { call ->
            // CORS (installed first) completes preflight OPTIONS responses before this
            // runs; appending to an already-committed response throws in the Netty
            // engine and logs every preflight as a spurious ERROR.
            if (call.response.isCommitted) return@onCall
            with(call.response.headers) {
                append("X-Frame-Options", "DENY")
                append("X-Content-Type-Options", "nosniff")
                append("Referrer-Policy", "same-origin")
                append("Content-Security-Policy", "default-src 'self'")
            }
        }
    }

fun Application.configureSecurityHeaders() {
    install(SecurityHeaders)
}

/**
 * High-frequency polling and long-lived SSE endpoints would dominate the access
 * log without adding signal, so they are excluded from it.
 */
internal fun isNoisyAccessPath(path: String): Boolean =
    path.startsWith("/api/v1/stream/") ||
        path == "/api/v1/health" ||
        path == "/api/v1/heartbeat" ||
        path == "/api/v1/livez" ||
        (path.startsWith("/api/v1/runner/") && (path.endsWith("/claim") || path.endsWith("/next") || path.endsWith("/output")))

// The result-read capability token (ADR-0025) travels in the URL path; logging it
// verbatim to api.log/app.log would let a co-located process harvest a live token
// and read another run's result. Mask the token segment wherever a request path is
// logged. (Other callback tokens ride the Authorization header, which is not logged.)
private val RESULT_TOKEN_IN_PATH = Regex("(/api/v1/callback/results/)[^/?]+")

internal fun redactAccessLogPath(path: String): String = RESULT_TOKEN_IN_PATH.replace(path) { "${it.groupValues[1]}***" }

/**
 * Per-request access log. Routed to the dedicated `homer.access` logger, which
 * logback sends to `api.log` only (additivity=false) — see `logback.xml`.
 */
fun Application.configureCallLogging() {
    install(CallLogging) {
        logger = LoggerFactory.getLogger("homer.access")
        level = Level.INFO
        filter { call -> !isNoisyAccessPath(call.request.path()) }
        format { call ->
            "${call.response.status()}: ${call.request.httpMethod.value} ${redactAccessLogPath(call.request.path())}"
        }
    }
}

/**
 * Wire-format Json for the REST API: used by the routing-scoped
 * ContentNegotiation install and by StatusPages, which must serialize
 * explicitly — it responds on the application-level call where the
 * routing-scoped ContentNegotiation does not apply.
 */
internal val apiJson =
    Json {
        prettyPrint = true
        isLenient = true
        encodeDefaults = true
    }

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<HomerException> { call, e ->
            val status = homerExceptionStatus(e)
            if (e is BodylessBadRequestException) return@exception call.respond(status)
            // 5xx messages can carry internal paths / command strings / config detail.
            // Log the detail server-side; return a generic body to the client. 4xx
            // messages are client-actionable, so they pass through unchanged.
            val clientMessage =
                if (status == HttpStatusCode.InternalServerError) {
                    logger.error("Internal error on {} {}", call.request.httpMethod.value, redactAccessLogPath(call.request.path()), e)
                    "Internal server error"
                } else {
                    e.message ?: "Internal server error"
                }
            call.respondText(
                apiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(msg = clientMessage)),
                ContentType.Application.Json,
                status,
            )
        }
        exception<PayloadTooLargeException> { call, e ->
            call.respondText(
                apiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(msg = e.message ?: "Request body too large")),
                ContentType.Application.Json,
                HttpStatusCode.PayloadTooLarge,
            )
        }
    }
}

/**
 * The HTTP status a [HomerException] escaping a route maps to. The `when` stays exhaustive
 * over the sealed hierarchy with no `else`, so a new subclass fails the build until it is
 * mapped here (ADR-0002); the caller's request errors are resolved by [requestErrorStatus],
 * the transfer, capacity and server-side ones by [transferCapacityOrServerStatus].
 */
internal fun homerExceptionStatus(e: HomerException): HttpStatusCode =
    when (e) {
        is QueryParamValidationException,
        is BodylessBadRequestException,
        is ValidationException,
        is CsrfValidationException,
        is AgentNotDispatchableException,
        is QuestionNotFoundException,
        is DispatchAgentNotFoundException,
        is DispatchNotFoundException,
        is ContinuationNotFoundException,
        is AnotherProcessStillActive,
        is QuestionNotOpenException,
        is QuestionsClosedForProcessException,
        is RunNotAcceptingMessagesException,
        is DispatchNotPendingException,
        is ContinuationNotPendingException,
        is RunnerOutputSequenceException,
        is PayloadSchemaValidationException,
        is InvocationDepthExceededException,
        is InvocationCycleDetectedException,
        is RunnerRegistrationRejectedException,
        -> {
            requestErrorStatus(e)
        }

        is RunnerTransferTimeoutException,
        is RunnerTransferTooLargeException,
        is MaxParallelRunsExceededException,
        is MaxQueuedRunsExceededException,
        is CostCapExceededException,
        is CooldownActiveException,
        is TooManyOpenQuestionsException,
        is TooManyRunMessagesException,
        is TooManyConcurrentWaitsException,
        is TooManyPendingDispatchesException,
        is TooManyPendingContinuationsException,
        is ClaimQueueFullException,
        is MaintenanceQueueFullException,
        is TooManyRunnerInstancesException,
        is TooManyRunnerPollsException,
        is TooManySessionPollsException,
        is TooManyRunnerTransfersException,
        is ClaudeCliVersionMismatchException,
        is ConfigurationException,
        is CommandExecutionException,
        is AgentException,
        is WorkspaceException,
        is MalformedLlmOutputException,
        is ProcessManagerAtCapacityException,
        is RunnerClaimTimeoutException,
        is MaintenanceWaitTimeoutException,
        -> {
            transferCapacityOrServerStatus(e)
        }
    }

/**
 * The `4xx` of the [homerExceptionStatus] subclasses that are the caller's request error: a
 * refused, unknown, conflicting or unprocessable request, or — for the rest it hands over — a
 * malformed one.
 */
private fun requestErrorStatus(e: HomerException): HttpStatusCode =
    when (e) {
        is QueryParamValidationException -> {
            e.httpStatusCode
        }

        is CsrfValidationException,
        is AgentNotDispatchableException,
        -> {
            HttpStatusCode.Forbidden
        }

        is QuestionNotFoundException,
        is DispatchAgentNotFoundException,
        is DispatchNotFoundException,
        is ContinuationNotFoundException,
        -> {
            HttpStatusCode.NotFound
        }

        is AnotherProcessStillActive,
        is QuestionNotOpenException,
        is QuestionsClosedForProcessException,
        is RunNotAcceptingMessagesException,
        is DispatchNotPendingException,
        is ContinuationNotPendingException,
        is RunnerOutputSequenceException,
        -> {
            HttpStatusCode.Conflict
        }

        is PayloadSchemaValidationException,
        is InvocationDepthExceededException,
        is InvocationCycleDetectedException,
        is RunnerRegistrationRejectedException,
        -> {
            HttpStatusCode.UnprocessableEntity
        }

        // BodylessBadRequestException, ValidationException.
        else -> {
            HttpStatusCode.BadRequest
        }
    }

/**
 * The status of the [homerExceptionStatus] subclasses that are not the caller's request error: a
 * runner transfer's limits, a capacity limit, an LLM's malformed output, or — for every other
 * one [homerExceptionStatus] hands over — a server-side failure.
 */
private fun transferCapacityOrServerStatus(e: HomerException): HttpStatusCode =
    when (e) {
        is RunnerTransferTimeoutException -> {
            HttpStatusCode.RequestTimeout
        }

        is RunnerTransferTooLargeException -> {
            HttpStatusCode.PayloadTooLarge
        }

        is MaxParallelRunsExceededException,
        is MaxQueuedRunsExceededException,
        is CostCapExceededException,
        is CooldownActiveException,
        is TooManyOpenQuestionsException,
        is TooManyRunMessagesException,
        is TooManyConcurrentWaitsException,
        is TooManyPendingDispatchesException,
        is TooManyPendingContinuationsException,
        is ClaimQueueFullException,
        is MaintenanceQueueFullException,
        is TooManyRunnerInstancesException,
        is TooManyRunnerPollsException,
        is TooManySessionPollsException,
        is TooManyRunnerTransfersException,
        -> {
            HttpStatusCode.TooManyRequests
        }

        is MalformedLlmOutputException -> {
            HttpStatusCode.BadGateway
        }

        is ProcessManagerAtCapacityException,
        is RunnerClaimTimeoutException,
        is MaintenanceWaitTimeoutException,
        -> {
            HttpStatusCode.ServiceUnavailable
        }

        // ClaudeCliVersionMismatchException, ConfigurationException, CommandExecutionException,
        // AgentException, WorkspaceException.
        else -> {
            HttpStatusCode.InternalServerError
        }
    }
