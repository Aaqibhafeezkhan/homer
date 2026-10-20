package homer.runner

import homer.app.models.LOOPBACK_HTTP_URL
import homer.app.models.isHttpsOrLoopbackHttpUrl
import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.NextRequest
import homer.app.runners.external.OpResult
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.external.RegisterInstanceResponse
import homer.app.runners.external.RunnerOp
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareDelete
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Upper bound on how much of a non-success response body [HomerClient.toException] reads into the error message. */
private const val ERROR_BODY_PREVIEW_BYTES = 512L

/**
 * Ktor CIO client for the runner protocol. Long-polls run under [pollTimeout]
 * (server `pollSec` plus slack); tar transfers under [transferTimeout]. Error
 * text never includes the token: it is only ever placed in the header.
 *
 * Every call uses `prepareX(...).execute { }`, never the plain `client.get`/`post`/`delete`
 * shortcuts: those run Ktor's SaveBody step, which reads the *entire* response body into
 * memory before the call even returns, defeating [toException]'s bounded read. `execute`'s
 * response is only ever consumed inside its own block, streamed.
 */
class HomerClient(
    baseUrl: String,
    private val token: String,
    engine: HttpClientEngine = CIO.create(),
    pollTimeout: Duration = 45.seconds,
    private val transferTimeout: Duration = 10.minutes,
) : RunnerApi,
    AutoCloseable {
    init {
        requireSecureBaseUrl(baseUrl)
    }

    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = pollTimeout.inWholeMilliseconds
                socketTimeoutMillis = pollTimeout.inWholeMilliseconds
            }
        }

    private fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $token")

    private fun HttpRequestBuilder.transferDeadline() =
        timeout {
            requestTimeoutMillis = transferTimeout.inWholeMilliseconds
            socketTimeoutMillis = transferTimeout.inWholeMilliseconds
        }

    override suspend fun register(request: RegisterInstanceRequest): RegisterInstanceResponse =
        client
            .preparePost("$base/api/v1/runner/register") {
                auth()
                setBody(TextContent(json.encodeToString(RegisterInstanceRequest.serializer(), request), ContentType.Application.Json))
            }.execute { response ->
                if (response.status != HttpStatusCode.Created) throw response.toException()
                json.decodeFromString(RegisterInstanceResponse.serializer(), response.bodyAsText())
            }

    override suspend fun deregister(instanceId: String) {
        client
            .prepareDelete("$base/api/v1/runner/instances/$instanceId") { auth() }
            .execute { response ->
                if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.NotFound) throw response.toException()
            }
    }

    override suspend fun claim(instanceId: String): ClaimResponse? =
        client
            .preparePost("$base/api/v1/runner/instances/$instanceId/claim") { auth() }
            .execute { response ->
                when (response.status) {
                    HttpStatusCode.OK -> json.decodeFromString(ClaimResponse.serializer(), response.bodyAsText())
                    HttpStatusCode.NoContent -> null
                    else -> throw response.toException()
                }
            }

    override suspend fun next(
        sessionId: String,
        completed: OpResult?,
    ): RunnerOp? =
        client
            .preparePost("$base/api/v1/runner/sessions/$sessionId/next") {
                auth()
                if (completed != null) {
                    setBody(
                        TextContent(json.encodeToString(NextRequest.serializer(), NextRequest(completed)), ContentType.Application.Json),
                    )
                }
            }.execute { response ->
                when (response.status) {
                    HttpStatusCode.OK -> json.decodeFromString(RunnerOp.serializer(), response.bodyAsText())
                    HttpStatusCode.NoContent -> null
                    else -> throw response.toException()
                }
            }

    override suspend fun fetchTar(
        sessionId: String,
        opId: String,
        consume: (InputStream) -> Unit,
    ) {
        client
            .prepareGet("$base/api/v1/runner/sessions/$sessionId/ops/$opId/tar") {
                auth()
                transferDeadline()
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) throw response.toException()
                response.bodyAsChannel().toInputStream().use(consume)
            }
    }

    override suspend fun postTar(
        sessionId: String,
        opId: String,
        kind: String,
        file: Path,
    ) {
        client
            .preparePost("$base/api/v1/runner/sessions/$sessionId/ops/$opId/tar?kind=$kind") {
                auth()
                transferDeadline()
                setBody(fileContent(file))
            }.execute { response ->
                if (response.status != HttpStatusCode.Accepted) throw response.toException()
            }
    }

    override suspend fun postOutput(
        sessionId: String,
        opId: String,
        stream: String,
        seq: Long,
        bytes: ByteArray,
    ) {
        client
            .preparePost("$base/api/v1/runner/sessions/$sessionId/ops/$opId/output?stream=$stream&seq=$seq") {
                auth()
                setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
            }.execute { response ->
                if (response.status != HttpStatusCode.Accepted) throw response.toException()
            }
    }

    // ktor-client has no file-backed OutgoingContent of its own (LocalFileContent lives in
    // ktor-server); a ReadChannelContent over the file's stream is the client-side equivalent.
    private fun fileContent(file: Path): OutgoingContent.ReadChannelContent =
        object : OutgoingContent.ReadChannelContent() {
            override val contentType = ContentType("application", "gzip")
            override val contentLength = Files.size(file)

            override fun readFrom(): ByteReadChannel = Files.newInputStream(file).toByteReadChannel()
        }

    // Bounded at the read itself (not by truncating a fully-decoded string): a misbehaving
    // proxy returning a huge error body must not be buffered in full before we discard the rest.
    private suspend fun HttpResponse.toException(): RunnerApiException {
        val preview = bodyAsChannel().readRemaining(ERROR_BODY_PREVIEW_BYTES).readByteArray().decodeToString()
        return RunnerApiException(
            status.value,
            "HTTP ${status.value} ${call.request.method.value} ${call.request.url.encodedPath}: $preview",
        )
    }

    override fun close() = client.close()
}

/**
 * Every request carries the domain token, so the base URL must be `https://`, or plain
 * `http://` matching [LOOPBACK_HTTP_URL], the rule `langfuse.serverUrl` uses too, and it
 * may not carry userinfo — the shared rule refuses that too. The message names all three
 * causes and never echoes the URL, which may hold the userinfo.
 * CWE-319, CWE-522.
 */
internal fun requireSecureBaseUrl(baseUrl: String) {
    require(isHttpsOrLoopbackHttpUrl(baseUrl)) {
        "--url must be https://, or http:// to a loopback host, without embedded credentials: " +
            "the runner token rides every request"
    }
}
