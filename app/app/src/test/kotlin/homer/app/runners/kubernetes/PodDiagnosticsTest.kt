package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PodDiagnosticsTest {
    /** A CoreV1Api answering the pod-status read with [podJson] (404 when null) and the event list with [events]. */
    private fun api(
        podJson: String?,
        events: List<String> = emptyList(),
    ): CoreV1Api {
        val http =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val (code, body) =
                        when {
                            request.url.encodedPath.endsWith("/events") -> {
                                200 to """{"apiVersion":"v1","kind":"EventList","items":[${events.joinToString(",")}]}"""
                            }

                            podJson == null -> {
                                404 to """{"kind":"Status","apiVersion":"v1","status":"Failure","code":404}"""
                            }

                            else -> {
                                200 to podJson
                            }
                        }
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        return CoreV1Api(ApiClient(http).setBasePath("http://kubernetes.invalid"))
    }

    private fun pod(status: String): String = """{"apiVersion":"v1","kind":"Pod","metadata":{"name":"p"},"status":$status}"""

    private fun container(
        name: String,
        state: String,
    ): String = """{"name":"$name","image":"img","imageID":"","ready":false,"restartCount":0,"state":$state}"""

    private fun event(
        type: String,
        reason: String,
        message: String,
        lastTimestamp: String,
    ): String =
        """{"metadata":{"name":"e-$reason"},"involvedObject":{"name":"p"},"type":"$type","reason":"$reason",""" +
            """"message":"$message","lastTimestamp":"$lastTimestamp"}"""

    private fun collect(
        api: CoreV1Api,
        fallbackReason: String = "Timeout",
    ) = PodDiagnostics(api, "ns").collect("p", fallbackReason)

    @Test
    fun `a container that exited non-zero reports its terminated reason and message`() {
        val status =
            """{"phase":"Running","containerStatuses":[""" +
                container("ok", """{"running":{}}""") + "," +
                container("main", """{"terminated":{"exitCode":1,"reason":"Error","message":"boom"}}""") + "]}"

        val snapshot = collect(api(pod(status)))

        assertEquals("Error", snapshot.reason)
        assertEquals("boom", snapshot.message)
    }

    @Test
    fun `a failed container without reason or message gets the generic container text`() {
        val status = """{"containerStatuses":[""" + container("main", """{"terminated":{"exitCode":137}}""") + "]}"

        val snapshot = collect(api(pod(status)))

        assertEquals("ContainerFailed", snapshot.reason)
        assertEquals("Container 'main' failed", snapshot.message)
    }

    @Test
    fun `a container waiting in a failure reason reports the waiting reason and message`() {
        val status =
            """{"containerStatuses":[""" +
                container("main", """{"waiting":{"reason":"ImagePullBackOff","message":"pull access denied"}}""") + "]}"

        val snapshot = collect(api(pod(status)))

        assertEquals("ImagePullBackOff", snapshot.reason)
        assertEquals("pull access denied", snapshot.message)
    }

    @Test
    fun `a waiting container falls back to the generic message when the waiting state has none`() {
        val status = """{"containerStatuses":[""" + container("main", """{"waiting":{"reason":"CrashLoopBackOff"}}""") + "]}"

        val snapshot = collect(api(pod(status)))

        assertEquals("CrashLoopBackOff", snapshot.reason)
        assertEquals("Container 'main' failed", snapshot.message)
    }

    @Test
    fun `a failed init container wins over a failed main container`() {
        val status =
            """{"initContainerStatuses":[""" +
                container("init", """{"terminated":{"exitCode":2,"reason":"InitError","message":"init broke"}}""") +
                """],"containerStatuses":[""" +
                container("main", """{"terminated":{"exitCode":1,"reason":"Error","message":"boom"}}""") + "]}"

        val snapshot = collect(api(pod(status)))

        assertEquals("InitError", snapshot.reason)
        assertEquals("init broke", snapshot.message)
    }

    @Test
    fun `a benign waiting reason is not a failure`() {
        val status =
            """{"phase":"Pending","containerStatuses":[""" + container("main", """{"waiting":{"reason":"ContainerCreating"}}""") + "]}"

        val snapshot = collect(api(pod(status)))

        assertEquals("Timeout", snapshot.reason)
        assertEquals("No diagnostic details available", snapshot.message)
    }

    @Test
    fun `a pod in the Failed phase reports its own reason and message`() {
        val snapshot = collect(api(pod("""{"phase":"Failed","reason":"Evicted","message":"node out of memory"}""")))

        assertEquals("Evicted", snapshot.reason)
        assertEquals("node out of memory", snapshot.message)
    }

    @Test
    fun `a pod in the Failed phase without details gets the generic pod text`() {
        val snapshot = collect(api(pod("""{"phase":"Failed"}""")))

        assertEquals("PodFailed", snapshot.reason)
        assertEquals("Pod reached Failed phase", snapshot.message)
    }

    @Test
    fun `without a failure the fallback reason comes with the latest event, events sorted by time`() {
        val events =
            listOf(
                event("Warning", "BackOff", "late", "2026-01-01T00:00:10Z"),
                event("Normal", "Pulled", "early", "2026-01-01T00:00:01Z"),
            )

        val snapshot = collect(api(pod("""{"phase":"Running"}"""), events), fallbackReason = "Stalled")

        assertEquals("Stalled", snapshot.reason)
        assertEquals("Warning/BackOff: late", snapshot.message)
        assertEquals(listOf("Normal/Pulled: early", "Warning/BackOff: late"), snapshot.events)
    }

    @Test
    fun `an unreadable pod falls back to the fallback reason`() {
        val snapshot = collect(api(podJson = null))

        assertEquals("Timeout", snapshot.reason)
        assertEquals("No diagnostic details available", snapshot.message)
        assertEquals(emptyList(), snapshot.events)
    }

    @Test
    fun `tryCollect returns the collected snapshot`() {
        val snapshot = PodDiagnostics.tryCollect(api(pod("""{"phase":"Failed"}""")), "ns", "p", "Timeout")

        assertEquals("PodFailed", snapshot.reason)
    }
}
