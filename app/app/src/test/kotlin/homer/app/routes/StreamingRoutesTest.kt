package homer.app.routes

import homer.app.exceptions.BodylessBadRequestException
import homer.app.models.LlmEvent
import homer.app.models.ProcessStatus
import homer.app.services.LlmStreamEvent
import homer.app.services.LogTailService
import homer.app.services.ProcessEvent
import homer.app.services.ProcessManager
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StreamingRoutesTest {
    @Test
    fun `streamedProcessId names the process of every event the processes stream carries`() {
        assertEquals(1, streamedProcessId(ProcessEvent.StatusChanged(1, ProcessStatus.WORKING, "a")))
        assertEquals(2, streamedProcessId(ProcessEvent.CurrentCommandChanged(2, "build", 10L)))
        assertEquals(3, streamedProcessId(ProcessEvent.QuestionAsked(3, "q", "a")))
        assertEquals(4, streamedProcessId(ProcessEvent.QuestionAnswered(4, "q")))
        assertEquals(5, streamedProcessId(ProcessEvent.QuestionExpired(5, "q")))
        assertNull(streamedProcessId(ProcessEvent.AgentsReloaded(listOf("a"), emptyList(), emptyList())))
    }

    @Test
    fun `processEventFrame renders each processes-stream event`() {
        assertEquals(
            SseFrame("process.status", """{"processId":1,"status":"WORKING","agentName":"a"}"""),
            processEventFrame(ProcessEvent.StatusChanged(1, ProcessStatus.WORKING, "a")),
        )
        assertEquals(
            SseFrame("process.cmd", """{"processId":2,"label":"build","startedAt":10}"""),
            processEventFrame(ProcessEvent.CurrentCommandChanged(2, "build", 10L)),
        )
        assertEquals(
            SseFrame("question.asked", """{"processId":3,"questionId":"q","agentName":"a"}"""),
            processEventFrame(ProcessEvent.QuestionAsked(3, "q", "a")),
        )
        assertEquals(
            SseFrame("question.answered", """{"processId":4,"questionId":"q"}"""),
            processEventFrame(ProcessEvent.QuestionAnswered(4, "q")),
        )
        assertEquals(
            SseFrame("question.expired", """{"processId":5,"questionId":"q"}"""),
            processEventFrame(ProcessEvent.QuestionExpired(5, "q")),
        )
        assertNull(processEventFrame(ProcessEvent.AgentsReloaded(emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun `a cleared current command renders null label and start`() {
        assertEquals(
            SseFrame("process.cmd", """{"processId":2,"label":null,"startedAt":null}"""),
            processEventFrame(ProcessEvent.CurrentCommandChanged(2, null, null)),
        )
    }

    @Test
    fun `logStream defaults to out and accepts out or err in any case`() {
        assertEquals(LogTailService.Stream.OUT, logStream(null))
        assertEquals(LogTailService.Stream.OUT, logStream("OUT"))
        assertEquals(LogTailService.Stream.ERR, logStream("Err"))
        assertNull(logStream("bogus"))
        assertNull(logStream(""))
    }

    @Test
    fun `logStream names a stream only for out or err`() =
        runTest {
            checkAll(Arb.string(0..8)) { param ->
                val expected =
                    when (param.lowercase()) {
                        "out" -> LogTailService.Stream.OUT
                        "err" -> LogTailService.Stream.ERR
                        else -> null
                    }
                assertEquals(expected, logStream(param), "param: $param")
            }
        }

    @Test
    fun `logEventFrame carries a chunk's offset as its id and ends with an empty frame`() {
        assertEquals(
            SseFrame("log.chunk", """{"content":"hi\n"}""", id = "42"),
            logEventFrame(LogTailService.LogEvent.Chunk(offset = 42L, content = "hi\n")),
        )
        assertEquals(SseFrame("log.end", "{}"), logEventFrame(LogTailService.LogEvent.End))
    }

    @Test
    fun `optionalCmdIndex is null when absent and rejects a non-index`() {
        assertNull(optionalCmdIndex(null))
        assertEquals(0, optionalCmdIndex("0"))
        assertEquals(7, optionalCmdIndex("7"))
        assertFailsWith<BodylessBadRequestException> { optionalCmdIndex("-1") }
        assertFailsWith<BodylessBadRequestException> { optionalCmdIndex("x") }
        assertFailsWith<BodylessBadRequestException> { optionalCmdIndex("") }
    }

    @Test
    fun `optionalCmdIndex returns exactly the non-negative integers`() =
        runTest {
            checkAll(Arb.int()) { n ->
                if (n >= 0) {
                    assertEquals(n, optionalCmdIndex(n.toString()))
                } else {
                    assertFailsWith<BodylessBadRequestException> { optionalCmdIndex(n.toString()) }
                }
            }
            checkAll(Arb.string(0..8)) { param ->
                val parsed = param.toIntOrNull()
                if (parsed != null && parsed >= 0) {
                    assertEquals(parsed, optionalCmdIndex(param), "param: $param")
                } else {
                    assertFailsWith<BodylessBadRequestException>("param: $param") { optionalCmdIndex(param) }
                }
            }
        }

    @Test
    fun `llmEventFrame carries the stream cursor as its id and ends with an empty frame`() {
        val frame =
            llmEventFrame(
                7,
                LlmStreamEvent.Event(
                    cmdIndex = 1,
                    attemptIndex = 2,
                    lineOffset = 30L,
                    eventIndex = 4,
                    event = LlmEvent.TransientError("slow down"),
                ),
            )
        assertEquals("llm.event", frame.name)
        assertEquals("1:2:30:4", frame.id)
        assertEquals(true, frame.data.startsWith("""{"processId":7,"cmdIndex":1,"attemptIndex":2,"event":{"""), frame.data)
        assertEquals(true, "slow down" in frame.data, frame.data)
        assertEquals(SseFrame("llm.end", "{}"), llmEventFrame(7, LlmStreamEvent.End))
    }
}
