package homer.runner

import homer.app.runners.external.OpResult
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.external.RunnerOp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomerClientTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a plaintext base URL to a non-loopback host is refused before the token can be sent (CWE-319)`() {
        listOf(
            "http://homer.internal",
            "http://10.0.0.5:8080",
            "HTTP://homer.internal/",
            "ftp://homer.internal",
            "homer.internal",
            "",
            "http://127.0.0.2",
            "http://user:pw@localhost:8080",
            "https://user:pw@homer.internal",
            "https:///path",
            "https:homer.internal",
        ).forEach { url ->
            val ex = assertThrows<IllegalArgumentException>(url) { HomerClient(url, "secret-token", MockEngine { respond("") }) }
            assertTrue("secret-token" !in ex.message!!)
        }
        // The refusal names the userinfo cause, since that is often the only rule broken.
        listOf("http://user:pw@homer.internal", "https://user:pw@homer.internal", "http://user:pw@localhost:8080").forEach { url ->
            val withUserinfo = assertThrows<IllegalArgumentException>(url) { requireSecureBaseUrl(url) }
            assertTrue("without embedded credentials" in withUserinfo.message!!, withUserinfo.message)
            assertTrue("pw" !in withUserinfo.message!!, "the URL, which may carry userinfo, is never echoed")
        }
    }

    @Test
    fun `https and loopback http base URLs are accepted`() {
        listOf(
            "https://homer.internal",
            "HTTPS://homer.internal:8443/",
            "https://homer_internal:8443",
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
        ).forEach { requireSecureBaseUrl(it) }
    }

    private fun client(engine: MockEngine) = HomerClient("https://homer.test/", "secret-token", engine)

    @Test
    fun `register posts the request with the bearer token and decodes 201`() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/api/v1/runner/register", request.url.encodedPath)
                    assertEquals("Bearer secret-token", request.headers[HttpHeaders.Authorization])
                    assertTrue(
                        request.body
                            .toByteArray()
                            .decodeToString()
                            .contains("\"tags\":[\"gpu\"]"),
                    )
                    respond(
                        """{"instanceId":"abc","pollSec":30,"heartbeatTimeoutSec":60}""",
                        HttpStatusCode.Created,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val r = client(engine).register(RegisterInstanceRequest(name = "h", tags = listOf("gpu")))
            assertEquals("abc", r.instanceId)
            assertEquals(60, r.heartbeatTimeoutSec)
        }

    @Test
    fun `a 4xx becomes a client-error exception carrying the status and body`() =
        runBlocking {
            val engine = MockEngine { respondError(HttpStatusCode.UnprocessableEntity, """{"msg":"tags [x] are not in allowedTags"}""") }
            val ex = assertThrows<RunnerApiException> { client(engine).register(RegisterInstanceRequest()) }
            assertEquals(422, ex.status)
            assertTrue(ex.isClientError)
            assertTrue(ex.message!!.contains("allowedTags"))
        }

    @Test
    fun `an oversized error body is never fully drained before the exception is thrown`() =
        runBlocking {
            // A lazily-produced, backpressured channel (not a pre-built ByteArray/String): if
            // toException() ever fell back to a call that buffers the whole body first (e.g. a
            // plain client.post instead of preparePost().execute {}), this producer would run to
            // completion before the exception surfaces. Bounded reading leaves it well short.
            val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
            // 4096 chunks (256 MiB if ever fully drained) produced lazily — only as the channel's
            // backpressure accepts them, so this costs no memory unless something actually drains
            // it — makes the "stayed well short" margin robust under load: 50 chunks (3.2 MiB) can
            // finish producing before the bounded reader even engages if the producer coroutine
            // gets scheduled first, which is exactly the flake this widens away from.
            val totalChunks = 4096
            val chunksWritten = AtomicInteger(0)
            val producerScope = CoroutineScope(Dispatchers.Default + Job())
            val writerJob =
                producerScope.writer(autoFlush = true) {
                    repeat(totalChunks) {
                        channel.writeFully(chunk)
                        chunksWritten.incrementAndGet()
                    }
                    channel.flushAndClose()
                }
            try {
                val engine = MockEngine { respond(writerJob.channel, HttpStatusCode.BadGateway) }
                val ex = assertThrows<RunnerApiException> { client(engine).register(RegisterInstanceRequest()) }
                assertEquals(502, ex.status)
                assertTrue(ex.message!!.length < 700)
                assertTrue(
                    chunksWritten.get() < totalChunks / 4,
                    "producer wrote ${chunksWritten.get()} of $totalChunks chunks — the error body was not bounded",
                )
            } finally {
                writerJob.job.cancel()
                producerScope.cancel()
            }
        }

    @Test
    fun `claim and next map 204 to null and 200 to the decoded body`() =
        runBlocking {
            var call = 0
            val engine =
                MockEngine { request ->
                    when (call++) {
                        0 -> {
                            respond("", HttpStatusCode.NoContent)
                        }

                        1 -> {
                            respond(
                                """{"sessionId":"s","processId":7,"agentName":"a","tags":[]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            assertTrue(
                                request.body
                                    .toByteArray()
                                    .decodeToString()
                                    .contains("\"completed\""),
                            )
                            respond(
                                """{"type":"close","opId":"o","reason":"done"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }
            val c = client(engine)
            assertNull(c.claim("i"))
            assertEquals(7, c.claim("i")!!.processId)
            val op = c.next("s", OpResult("prev", exitCode = 0))
            assertTrue(op is RunnerOp.Close && op.reason == "done")
        }

    @Test
    fun `postOutput and postTar send the query parameters and raw bodies`() =
        runBlocking {
            val seen = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    seen += request.url.encodedPathAndQuery
                    assertTrue(request.body.toByteArray().isNotEmpty())
                    respond("", HttpStatusCode.Accepted)
                }
            val c = client(engine)
            c.postOutput("s", "o", "stdout", 3, "x".toByteArray())
            val tar = tmp.resolve("t.tgz").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            c.postTar("s", "o", "dir", tar)
            assertEquals(
                listOf("/api/v1/runner/sessions/s/ops/o/output?stream=stdout&seq=3", "/api/v1/runner/sessions/s/ops/o/tar?kind=dir"),
                seen,
            )
        }

    @Test
    fun `fetchTar streams the body to the consumer and fails on a non-200`() =
        runBlocking {
            var status = HttpStatusCode.OK
            val engine = MockEngine { respond(byteArrayOf(9, 8, 7), status) }
            val c = client(engine)
            var got = byteArrayOf()
            c.fetchTar("s", "o") { got = it.readBytes() }
            assertEquals(listOf<Byte>(9, 8, 7), got.toList())
            status = HttpStatusCode.NotFound
            assertEquals(404, assertThrows<RunnerApiException> { c.fetchTar("s", "o") {} }.status)
        }
}
