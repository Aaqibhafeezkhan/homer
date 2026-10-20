package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.SseCollector
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SseTest : E2eTestBase() {
    @Test
    fun `process stream emits the full status sequence of a run`() =
        runBlocking {
            SseCollector.open("/api/v1/stream/processes").use { sse ->
                val processId = HomerApi.startRun("echo", query = mapOf("message" to "sse-check"))
                Awaits.awaitTerminal(processId)
                sse.awaitEvent("FINISHED status event for process $processId") {
                    it.event == "process.status" && statusOf(it, processId) == "FINISHED"
                }

                val statuses =
                    sse
                        .snapshot()
                        .filter { it.event == "process.status" }
                        .mapNotNull { statusOf(it, processId) }
                assertEquals(
                    listOf("CREATED", "WORKING", "FINISHED"),
                    statuses,
                    "subscriber connected before the trigger must see every transition",
                )
            }
        }

    @Test
    fun `log stream tails stdout with offsets and resumes without duplicates`() =
        runBlocking {
            val processId = HomerApi.startRun("sleeper")
            Awaits.awaitStatus(processId, setOf("WORKING"))

            val logsPath = "/api/v1/stream/processes/$processId/logs"
            val params = mapOf("cmd" to "0", "stream" to "out")

            var lastOffset: String?
            SseCollector.open(logsPath, params, lastEventId = "0").use { sse ->
                val chunk =
                    sse.awaitEvent("log.chunk with the command output") {
                        it.event == "log.chunk" &&
                            contentOf(it).contains("woke up")
                    }
                assertNotNull(chunk.id, "log.chunk must carry its byte offset as the SSE id")
                sse.awaitEvent("log.end after the process finished") { it.event == "log.end" }

                val chunks = sse.snapshot().filter { it.event == "log.chunk" }
                val joined = chunks.joinToString("") { contentOf(it) }
                assertEquals(joined.indexOf("woke up"), joined.lastIndexOf("woke up"), "tail must not duplicate bytes: $joined")
                lastOffset = chunks.last().id
            }

            // Resuming from the final offset must replay nothing.
            SseCollector.open(logsPath, params, lastEventId = lastOffset).use { resumed ->
                resumed.awaitEvent("immediate log.end when resuming at the end") { it.event == "log.end" }
                assertTrue(
                    resumed.snapshot().none { it.event == "log.chunk" },
                    "resume at final offset must not re-send bytes",
                )
            }

            // Resuming from 0 replays the full content exactly once.
            SseCollector.open(logsPath, params, lastEventId = "0").use { replay ->
                replay.awaitEvent("log.end on full replay") { it.event == "log.end" }
                val joined =
                    replay
                        .snapshot()
                        .filter { it.event == "log.chunk" }
                        .joinToString("") { contentOf(it) }
                assertTrue(joined.contains("woke up"), "full replay must contain the output: $joined")
            }
        }

    @Test
    fun `process stream emits one cmd event per step boundary of a multi-step run`() =
        runBlocking {
            SseCollector.open("/api/v1/stream/processes").use { sse ->
                val processId = HomerApi.startRun("multi-step")
                Awaits.awaitTerminal(processId)
                sse.awaitEvent("FINISHED status event for process $processId") {
                    it.event == "process.status" && statusOf(it, processId) == "FINISHED"
                }

                val cmds =
                    sse
                        .snapshot()
                        .filter { it.event == "process.cmd" }
                        .mapNotNull { cmdOf(it, processId) }
                // Exactly one event per boundary plus the run's single step-start set:
                // the first step's start, the first boundary handing over to the second
                // command, the second clearing (the run is over — no phantom next
                // command, and no doubled add-then-clear pair at either boundary).
                assertEquals(
                    listOf("emit-json", "echo-captured", null),
                    cmds.map { it.label },
                    "a step boundary must emit exactly one process.cmd, naming the next step or clearing",
                )
                assertNotNull(cmds[1].startedAt, "a handover boundary carries the next step's start time")
                assertEquals(null, cmds[2].startedAt, "the final boundary clears the start time")
            }
        }

    @Test
    fun `question lifecycle events reach the process stream`() =
        runBlocking {
            SseCollector.open("/api/v1/stream/processes").use { sse ->
                val processId = HomerApi.startRun("asker")

                val asked =
                    sse.awaitEvent("question.asked for process $processId") {
                        it.event == "question.asked" && processIdOf(it) == processId
                    }
                val questionId =
                    E2e.json
                        .parseToJsonElement(asked.data.orEmpty())
                        .jsonObject["questionId"]!!
                        .jsonPrimitive.content

                HomerApi.answer(questionId, "A")
                sse.awaitEvent("question.answered for question $questionId") {
                    it.event == "question.answered" &&
                        E2e.json
                            .parseToJsonElement(it.data.orEmpty())
                            .jsonObject["questionId"]
                            ?.jsonPrimitive
                            ?.content == questionId
                }
                Awaits.awaitTerminal(processId)
            }
            Unit
        }

    @Test
    fun `llm-events answers 404 for a command that is not an LLM harness`() =
        runBlocking {
            val id = HomerApi.startRun("echo", query = mapOf("message" to "llm"))
            Awaits.awaitTerminal(id)
            val res = E2e.client.get("/api/v1/stream/processes/$id/llm-events") { parameter("cmd", "0") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    private fun processIdOf(event: ServerSentEvent): Int? =
        event.data?.let { data ->
            E2e.json
                .parseToJsonElement(data)
                .jsonObject["processId"]
                ?.jsonPrimitive
                ?.int
        }

    /** The `status` field of a process.status event, or null if it is another process's event. */
    private fun statusOf(
        event: ServerSentEvent,
        processId: Int,
    ): String? =
        event.data?.let { data ->
            val obj = E2e.json.parseToJsonElement(data).jsonObject
            if (obj["processId"]?.jsonPrimitive?.int != processId) return null
            obj["status"]?.jsonPrimitive?.content
        }

    /** The `label`/`startedAt` of a process.cmd event for [processId], or null if it is another process's event. */
    private fun cmdOf(
        event: ServerSentEvent,
        processId: Int,
    ): CmdEvent? =
        event.data?.let { data ->
            val obj = E2e.json.parseToJsonElement(data).jsonObject
            if (obj["processId"]?.jsonPrimitive?.int != processId) return null
            // A clear event serializes its null label/startedAt as the JSON literal
            // null (JsonNull), not an absent key — use the OrNull parsers.
            CmdEvent(
                label = obj["label"]?.jsonPrimitive?.contentOrNull,
                startedAt = obj["startedAt"]?.jsonPrimitive?.longOrNull,
            )
        }

    private data class CmdEvent(
        val label: String?,
        val startedAt: Long?,
    )

    private fun contentOf(event: ServerSentEvent): String =
        event.data
            ?.let { data ->
                E2e.json
                    .parseToJsonElement(data)
                    .jsonObject["content"]
                    ?.jsonPrimitive
                    ?.content
            }.orEmpty()
}
