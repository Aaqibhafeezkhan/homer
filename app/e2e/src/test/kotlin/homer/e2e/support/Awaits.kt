package homer.e2e.support

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay

/** Poll-based waiting — the suite never sleep-and-asserts. */
object Awaits {
    val TERMINAL = setOf("FINISHED", "FAILED", "KILLED", "UNKNOWN")

    /**
     * Poll `GET /status/{id}` until the process reaches one of [targets].
     * Fails with the last observed status body so a timeout is debuggable
     * straight from the JUnit report.
     */
    suspend fun awaitStatus(
        processId: Int,
        targets: Set<String>,
        timeoutMs: Long = 30_000,
        pollMs: Long = 250,
    ): ProcessInfo {
        var last = "(no response yet)"
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val response = E2e.client.get("/api/v1/status/$processId")
            val text = response.bodyAsText()
            last = "${response.status}: $text"
            if (response.status == HttpStatusCode.OK) {
                val info = E2e.json.decodeFromString<ProcessInfo>(text)
                if (info.status in targets) return info
            }
            delay(pollMs)
        }
        throw AssertionError("Process $processId did not reach $targets within ${timeoutMs}ms; last: $last")
    }

    /** Await any terminal status; callers assert which one. */
    suspend fun awaitTerminal(
        processId: Int,
        timeoutMs: Long = 30_000,
    ): ProcessInfo = awaitStatus(processId, TERMINAL, timeoutMs)

    /** Poll [probe] until it returns non-null; fail with [description] on timeout. */
    suspend fun <T : Any> awaitNotNull(
        description: String,
        timeoutMs: Long = 30_000,
        pollMs: Long = 250,
        probe: suspend () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            delay(pollMs)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $description")
    }
}
