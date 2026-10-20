package homer.e2e

import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.ErrorBody
import homer.e2e.support.Heartbeat
import homer.e2e.support.HomerApi
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTest : E2eTestBase() {
    @Test
    fun `livez responds 200 without authentication`() =
        runBlocking {
            val response = E2e.unauthClient.get("/api/v1/livez")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `readyz responds 200 in steady state`() =
        runBlocking {
            val response = E2e.unauthClient.get("/api/v1/readyz")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `heartbeat rejects a missing token`() =
        runBlocking {
            val response = E2e.unauthClient.get("/api/v1/heartbeat")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `heartbeat rejects a wrong token`() =
        runBlocking {
            val response =
                E2e.unauthClient.get("/api/v1/heartbeat") {
                    header(HttpHeaders.Authorization, "Bearer definitely-not-the-token")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `heartbeat accepts the configured token`() =
        runBlocking {
            val heartbeat: Heartbeat = HomerApi.decode(E2e.client.get("/api/v1/heartbeat"))
            assertEquals("ok", heartbeat.status)
        }

    @Test
    fun `errors use the documented err-msg body shape`() =
        runBlocking {
            val response = HomerApi.run("no-such-agent-xyz")
            assertEquals(HttpStatusCode.NotFound, response.status)
            val error = E2e.json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertEquals(1, error.err)
            assertTrue(error.msg.isNotBlank(), "error msg must name the problem")
        }
}
