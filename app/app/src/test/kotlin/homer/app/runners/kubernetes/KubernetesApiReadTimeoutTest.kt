package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.kubernetes.client.Exec as KubernetesExec

/**
 * The read bound against a server that accepts the connection and then says nothing (ADR-0100): a
 * non-streaming read ends instead of hanging, and the exec channel sharing that client is left
 * alone (ADR-0043). Both cases run on the short test bound below; that the production client
 * carries 30 s is asserted in `homer.app.di.RunnerWiringTest`.
 */
class KubernetesApiReadTimeoutTest {
    private val server = MockWebServer()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `a stalled pod status read fails within the read timeout instead of hanging`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

        val thrown = timedOutStatusRead(apiClient())

        assertTrue(thrown.cause is SocketTimeoutException, "expected a SocketTimeoutException, got ${thrown.cause}")
    }

    @Test
    fun `a timed-out read leaks no credential into the error text`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val client = apiClient()
        client.setApiKeyPrefix("Bearer")
        client.setApiKey(TOKEN)

        val thrown = timedOutStatusRead(client)

        assertNull(thrown.responseBody, "a read that never got an answer has no body to quote")
        assertFalse(thrown.message.orEmpty().contains(TOKEN), thrown.message.orEmpty())
        assertFalse(thrown.message.orEmpty().contains("Authorization"), thrown.message.orEmpty())
    }

    @Test
    fun `an exec silent for longer than the read timeout still completes`() {
        // Exec resolves the container name with a REST read before it upgrades.
        server.enqueue(MockResponse.Builder().body(POD_WITH_ONE_CONTAINER).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(silentThenExit()).build())
        val exec = KubernetesApiExecClient(KubernetesExec(apiClient()))

        val process = exec.start(NAMESPACE, POD, listOf("true"), stdin = false)

        // A cut stream would fail the process with EXEC_NO_EXIT_STATUS_SENTINEL instead.
        assertEquals(0, assertTimeoutPreemptively(Duration.ofSeconds(10)) { process.waitFor() })
    }

    /** A client bounded the way the runner's client is, against [server]. */
    private fun apiClient(): ApiClient = ApiClient().setBasePath(server.url("/").toString().trimEnd('/')).setReadTimeout(READ_TIMEOUT_MS)

    /** The pod-status read of a server that never answers, under a budget a bound read cannot exceed. */
    private fun timedOutStatusRead(client: ApiClient): ApiException =
        assertThrows<ApiException> {
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                CoreV1Api(client).readNamespacedPodStatus(POD, NAMESPACE).execute()
            }
        }

    /** Answers the upgrade, stays silent for longer than the read timeout, then sends the exit status. */
    private fun silentThenExit(): WebSocketListener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                Thread {
                    Thread.sleep(SILENT_MS)
                    webSocket.send(EXIT_STATUS_FRAME)
                    // Close the stream once the exit status is out, or the open server-side
                    // WebSocket outlives the test and MockWebServer.close() times out on it.
                    webSocket.close(1000, "exit")
                }.start()
            }
        }

    private companion object {
        const val NAMESPACE = "homer"
        const val POD = "homer-1-abc"
        const val TOKEN = "s3cr3t-bearer-token"

        /** Short enough to keep the suite in seconds; the production value is 30 s. */
        const val READ_TIMEOUT_MS = 500

        /** Longer than [READ_TIMEOUT_MS], so a stream cut by the bound would show. */
        const val SILENT_MS = 1_500L

        const val POD_WITH_ONE_CONTAINER =
            """{"kind":"Pod","apiVersion":"v1","metadata":{"name":"homer-1-abc"},"spec":{"containers":[{"name":"main"}]}}"""

        /** Stream id 3 carries the exit status: a `V1Status` reading `Success` is exit code 0. */
        val EXIT_STATUS_FRAME =
            (byteArrayOf(3) + """{"kind":"Status","apiVersion":"v1","status":"Success"}""".toByteArray()).toByteString()
    }
}
