package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * A real CoreV1Api whose OkHttp client is short-circuited by an interceptor:
 * requests land in [seen] and get [respond]'s status code back, no network.
 * Use instead of a mocked CoreV1Api when the test cares about what actually
 * goes on the wire (method, path, Content-Type) — a mock can't see that.
 */
fun interceptedCoreV1Api(
    seen: MutableList<Request>,
    respond: (Request) -> Int = { 200 },
): CoreV1Api {
    val http =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                seen += request
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(respond(request))
                    .message("test")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
    return CoreV1Api(ApiClient(http).setBasePath("http://kubernetes.invalid"))
}
