package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import homer.app.models.ErrorResponse
import homer.app.services.ArtifactTooLargeException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

/**
 * The request body as [T]; when it does not decode, throws a [QueryParamValidationException]
 * that StatusPages answers `400 {msg: errorMsg}`. Any failure but a cancellation counts, as
 * in the inline try/catch this replaced.
 */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveOr400(errorMsg: String): T =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw QueryParamValidationException(errorMsg)
    }

/** The path parameter [name] the matched route declares; routing matches no request without it. */
internal fun ApplicationCall.pathParameter(name: String): String =
    checkNotNull(parameters[name]) { "route declares no {$name} path parameter" }

/** Answers a failed `checkArtifactSize`: 413 naming both sizes when too large, else 500. */
internal suspend fun respondArtifactSizeFailure(
    call: ApplicationCall,
    failure: Throwable,
) {
    val ex = failure as? ArtifactTooLargeException
    if (ex != null) {
        call.respond(
            HttpStatusCode.PayloadTooLarge,
            ErrorResponse(msg = "Artifact exceeds server.maxArtifactBytes (${ex.size} > ${ex.maxBytes})"),
        )
    } else {
        call.respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse(msg = failure.message ?: "Artifact size check failed"),
        )
    }
}
