package homer.e2e.support

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.encodeToString

/** Typed helpers over the operator HTTP API. All calls use the authenticated client. */
object HomerApi {
    /** Fire `POST /api/v1/agent/{name}` and return the raw response (callers assert the code). */
    suspend fun run(
        agent: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse =
        E2e.client.post("/api/v1/agent/$agent") {
            query.forEach { (k, v) -> parameter(k, v) }
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) {
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }

    /** Start a run that is expected to be accepted, returning the new processId. */
    suspend fun startRun(
        agent: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Int {
        val response = run(agent, query, body, headers)
        val text = response.bodyAsText()
        check(response.status == HttpStatusCode.OK) {
            "Starting agent '$agent' failed: ${response.status} $text"
        }
        return E2e.json.decodeFromString<RunResponse>(text).processId
    }

    suspend fun status(processId: Int): ProcessInfo = decode(E2e.client.get("/api/v1/status/$processId"))

    suspend fun statusResponse(processId: Int): HttpResponse = E2e.client.get("/api/v1/status/$processId")

    suspend fun statusAll(params: Map<String, String> = emptyMap()): HttpResponse =
        E2e.client.get("/api/v1/status/all") {
            params.forEach { (k, v) -> parameter(k, v) }
        }

    suspend fun kill(processId: Int): HttpResponse = E2e.client.delete("/api/v1/kill/$processId")

    suspend fun retry(processId: Int): HttpResponse = E2e.client.post("/api/v1/processes/$processId/retry")

    suspend fun agents(): AgentsList = decode(E2e.client.get("/api/v1/agents"))

    suspend fun questions(
        status: String? = null,
        processId: Int? = null,
    ): QuestionsList =
        decode(
            E2e.client.get("/api/v1/questions") {
                if (status != null) parameter("status", status)
                if (processId != null) parameter("processId", processId)
            },
        )

    suspend fun answer(
        questionId: String,
        answer: String,
    ): HttpResponse =
        E2e.client.post("/api/v1/questions/$questionId/answer") {
            setBody(TextContent("""{"answer":${E2e.json.encodeToString(answer)}}""", ContentType.Application.Json))
        }

    suspend fun sendMessage(
        processId: Int,
        text: String,
    ): HttpResponse =
        E2e.client.post("/api/v1/status/$processId/messages") {
            setBody(TextContent("""{"text":${E2e.json.encodeToString(text)}}""", ContentType.Application.Json))
        }

    suspend fun messages(processId: Int): RunMessagesList = decode(E2e.client.get("/api/v1/status/$processId/messages"))

    suspend fun continuations(status: String? = null): ContinuationList =
        decode(
            E2e.client.get("/api/v1/continuations") {
                if (status != null) parameter("status", status)
            },
        )

    suspend fun dispatches(status: String? = null): DispatchList =
        decode(
            E2e.client.get("/api/v1/dispatches") {
                if (status != null) parameter("status", status)
            },
        )

    suspend fun artifact(
        processId: Int,
        path: String,
    ): HttpResponse =
        E2e.client.get("/api/v1/artifacts") {
            parameter("processId", processId)
            parameter("path", path)
        }

    suspend fun artifactsList(processId: Int): HttpResponse =
        E2e.client.get("/api/v1/artifacts/list") {
            parameter("processId", processId)
        }

    /** Fetch a command's output artifact by the server-reported path (fallback: conventional name). */
    suspend fun artifactText(
        processId: Int,
        serverPath: String?,
        fallback: String,
    ): String {
        val relative = serverPath?.substringAfterLast("artifacts/") ?: fallback
        val response = artifact(processId, relative)
        val text = response.bodyAsText()
        check(response.status == HttpStatusCode.OK) { "Fetching artifact $relative failed: ${response.status} $text" }
        return text
    }

    /** Decode a response body with the suite's lenient JSON, failing loudly with the raw body. */
    suspend inline fun <reified T> decode(response: HttpResponse): T {
        val text = response.bodyAsText()
        check(response.status.value in 200..299) {
            "Expected 2xx for ${response.call.request.url}, got ${response.status}: $text"
        }
        return E2e.json.decodeFromString(text)
    }
}
