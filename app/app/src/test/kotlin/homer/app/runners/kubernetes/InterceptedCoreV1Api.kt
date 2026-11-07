package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.SocketTimeoutException

/**
 * An ApiClient whose OkHttp client is short-circuited by an interceptor:
 * requests land in [seen] and get [respond]'s status code and JSON body back,
 * no network. A request [timeoutOn] selects fails with a [SocketTimeoutException] instead — the
 * failure a stalled API server produces once the client's read timeout expires, which client-java's
 * `execute` wraps in an `ApiException` (ADR-0100).
 */
fun interceptedApiClient(
    seen: MutableList<Request>,
    timeoutOn: (Request) -> Boolean = { false },
    respond: (Request) -> Pair<Int, String>,
): ApiClient {
    val http =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                seen += request
                if (timeoutOn(request)) throw SocketTimeoutException("read timed out")
                val (code, body) = respond(request)
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
    return ApiClient(http).setBasePath("http://kubernetes.invalid")
}

/**
 * A real CoreV1Api on [interceptedApiClient], answering `{}` with [respond]'s status code.
 * Use instead of a mocked CoreV1Api when the test cares about what actually
 * goes on the wire (method, path, Content-Type) — a mock can't see that.
 */
fun interceptedCoreV1Api(
    seen: MutableList<Request>,
    timeoutOn: (Request) -> Boolean = { false },
    respond: (Request) -> Int = { 200 },
): CoreV1Api = CoreV1Api(interceptedApiClient(seen, timeoutOn) { respond(it) to "{}" })

/** A real BatchV1Api on [interceptedApiClient]: the Job-mode counterpart of [interceptedCoreV1Api]. */
fun interceptedBatchV1Api(
    seen: MutableList<Request>,
    respond: (Request) -> Pair<Int, String> = { 200 to "{}" },
): BatchV1Api = BatchV1Api(interceptedApiClient(seen, respond = respond))
