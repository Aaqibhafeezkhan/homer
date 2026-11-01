package homer.app.services

import homer.app.executors.CommandArtifactPaths
import homer.app.executors.llm.CodexHarness
import homer.app.executors.llm.OpencodeHarness
import homer.app.fixtures.TestFixtures
import homer.app.infra.SecretRedactor
import homer.app.models.ExecutionInfo
import homer.app.models.LlmEvent
import homer.app.models.LlmEventJson
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmEventStreamServiceTest {
    private val harnesses = mapOf("codex" to CodexHarness(), "opencode" to OpencodeHarness())

    // No live job registered: every tick reads the status, as for a restarted or finished run.
    private fun processManagerWithoutJob() = mockk<ProcessManager> { every { hasLiveJob(any()) } returns false }

    private fun processInfo(
        id: Int,
        artifactsDir: Path,
        status: ProcessStatus,
        executions: List<ExecutionInfo> = emptyList(),
        inputParams: Map<String, String> = emptyMap(),
        secretInputKeys: Set<String>? = null,
    ) = ProcessInfo(
        id = id,
        status = status,
        agentName = "a",
        artifactsDir = artifactsDir,
        executions = executions,
        inputParams = inputParams,
        secretInputKeys = secretInputKeys,
    )

    private fun finishedCodex(
        tmp: Path,
        out: Path,
    ): ProcessManager {
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.FINISHED)
        coEvery { pm.getProcessStatus(1) } returns ProcessStatus.FINISHED
        coEvery { pm.getCommandArtifacts(1, 0) } returns CommandArtifactPaths(out, tmp.resolve("e"), kind = "codex")
        return pm
    }

    // A live run whose command registrations and status the test drives.
    private fun liveProcess(
        tmp: Path,
        tracked: Map<Int, CommandArtifactPaths>,
        status: AtomicReference<ProcessStatus>,
    ): ProcessManager {
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.WORKING)
        coEvery { pm.getProcessStatus(1) } answers { status.get() }
        coEvery { pm.getCommandArtifacts(1, any()) } answers { tracked[secondArg()] }
        coEvery { pm.latestCommandArtifacts(1) } answers { tracked.maxByOrNull { it.key }?.toPair() }
        return pm
    }

    private fun codexArtifact(
        out: Path,
        attemptIndex: Int = 0,
    ) = CommandArtifactPaths(out, out.resolveSibling("e"), kind = "codex", attemptIndex = attemptIndex)

    private fun scriptArtifact(out: Path) = CommandArtifactPaths(out, out.resolveSibling("e"), kind = "script")

    private fun line(json: String) = json + "\n"

    private fun typeOf(event: LlmEvent): String =
        LlmEventJson
            .encodeToJsonElement(LlmEvent.serializer(), event)
            .jsonObject["type"]!!
            .jsonPrimitive.content

    @Test
    fun `resolve returns null for a non-LLM command`(
        @TempDir tmp: Path,
    ) = runTest {
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.WORKING)
        coEvery { pm.getCommandArtifacts(1, 0) } returns
            CommandArtifactPaths(tmp.resolve("cmd_0.out"), tmp.resolve("cmd_0.err"), kind = "script")
        assertNull(LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger()).resolve(1, 0))
    }

    @Test
    fun `resolve falls back to the execution row for a finished process`(
        @TempDir tmp: Path,
    ) = runTest {
        val pm = processManagerWithoutJob()
        val row = ExecutionInfo("l", 1, 2, 0, Path.of("cmd_0.out"), Path.of("cmd_0.err"), null, kind = "codex")
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.FINISHED, listOf(row))
        coEvery { pm.getCommandArtifacts(1, 0) } returns null
        val a = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger()).resolve(1, 0)
        assertEquals(tmp.resolve("cmd_0.out"), a?.path)
        assertEquals("codex", a?.kind)
    }

    @Test
    fun `resolve fallback redacts the stored secret inputs`(
        @TempDir tmp: Path,
    ) = runTest {
        val pm = processManagerWithoutJob()
        val row = ExecutionInfo("l", 1, 2, 0, Path.of("cmd_0.out"), Path.of("cmd_0.err"), null, kind = "codex")
        coEvery { pm.getProcessInfo(1) } returns
            processInfo(
                1,
                tmp,
                ProcessStatus.FINISHED,
                listOf(row),
                inputParams = mapOf("TOKEN" to "s3cret-value", "PLAIN" to "visible"),
                secretInputKeys = setOf("TOKEN"),
            )
        coEvery { pm.getCommandArtifacts(1, 0) } returns null
        val redactor = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger()).resolve(1, 0)!!.redactor
        assertEquals("*** visible", redactor.redact("s3cret-value visible"))
    }

    @Test
    fun `resolve refuses a tracked path outside the artifacts dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.WORKING)
        coEvery { pm.getCommandArtifacts(1, 0) } returns
            CommandArtifactPaths(Path.of("/etc/passwd"), tmp.resolve("e"), kind = "codex")
        assertNull(LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger()).resolve(1, 0))
    }

    @Test
    fun `resolve refuses an execution row escaping the artifacts dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val pm = processManagerWithoutJob()
        val row = ExecutionInfo("l", 1, 2, 0, Path.of("../outside.out"), Path.of("e"), null, kind = "codex")
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.FINISHED, listOf(row))
        coEvery { pm.getCommandArtifacts(1, 0) } returns null
        assertNull(LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger()).resolve(1, 0))
    }

    @Test
    fun `resolve returns null for a pre-release execution row without a kind`(
        @TempDir tmp: Path,
    ) = runTest {
        val pm = processManagerWithoutJob()
        val row = ExecutionInfo("l", 1, 2, 0, Path.of("cmd_0.out"), Path.of("cmd_0.err"), null)
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.FINISHED, listOf(row))
        coEvery { pm.getCommandArtifacts(1, 0) } returns null
        assertNull(LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger()).resolve(1, 0))
    }

    @Test
    fun `streamCommand translates complete lines, holds a partial one, redacts, and ends on terminal`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        out.writeText(
            """{"type":"thread.started","thread_id":"t1"}""" + "\n" +
                """{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"secret-token here"}}""" + "\n" +
                """{"type":"turn.comp""",
        )
        val status = AtomicReference(ProcessStatus.WORKING)
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.WORKING)
        coEvery { pm.getProcessStatus(1) } answers { status.get() }
        coEvery { pm.getCommandArtifacts(1, 0) } returns
            CommandArtifactPaths(
                out,
                tmp.resolve("e"),
                kind = "codex",
                redactor = SecretRedactor.from(mapOf("K" to "secret-token"), setOf("K")),
            )
        coEvery { pm.latestCommandArtifacts(1) } returns null
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val collected = async { service.streamCommand(1, 0, resume = null).toList() }
        yield()
        out.appendText("""leted","usage":{"input_tokens":1}}""" + "\n")
        status.set(ProcessStatus.FINISHED)
        val events = collected.await()

        val payloads = events.filterIsInstance<LlmStreamEvent.Event>()
        assertEquals(listOf("session_start", "assistant_message", "turn_end"), payloads.map { typeOf(it.event) })
        val text = (payloads[1].event as LlmEvent.AssistantMessage).text
        assertTrue("***" in text && "secret-token" !in text, text)
        assertTrue(payloads.zipWithNext().all { (a, b) -> a.lineOffset < b.lineOffset })
        assertTrue(payloads.all { it.eventIndex == 0 })
        assertEquals(0L, payloads.first().lineOffset)
        assertEquals(
            out
                .toFile()
                .readText()
                .lastIndexOf("{\"type\":\"turn.comp")
                .toLong(),
            payloads.last().lineOffset,
        )
        assertEquals(LlmStreamEvent.End, events.last())
    }

    @Test
    fun `8eaf311b CWE-200 a unicode-escaped secret is masked after decoding, in text and tool input`(
        @TempDir tmp: Path,
    ) = runTest {
        // The agent writes the secret as JSON escapes: the raw line never contains it, the decoded event does.
        val out = tmp.resolve("cmd_0.out")
        out.writeText(
            """{"type":"thread.started","thread_id":"t1"}""" + "\n" +
                """{"type":"item.completed","item":{"id":"m","type":"agent_message","text":"\u0073ecret-token here"}}""" + "\n" +
                """{"type":"item.completed","item":{"id":"c","type":"command_execution","command":"echo \u0073ecret-token",""" +
                """"aggregated_output":"\u0073ecret-token","exit_code":0,"status":"completed"}}""" + "\n" +
                """{"type":"turn.completed","usage":{}}""" + "\n",
        )
        val pm = finishedCodex(tmp, out)
        coEvery { pm.getCommandArtifacts(1, 0) } returns
            CommandArtifactPaths(
                out,
                tmp.resolve("e"),
                kind = "codex",
                redactor = SecretRedactor.from(mapOf("K" to "secret-token"), setOf("K")),
            )
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val events =
            service
                .streamCommand(1, 0, resume = null)
                .toList()
                .filterIsInstance<LlmStreamEvent.Event>()
                .map { it.event }

        assertEquals(listOf("session_start", "assistant_message", "tool_call", "tool_result", "turn_end"), events.map { typeOf(it) })
        val wire = events.joinToString("\n") { LlmEventJson.encodeToString(LlmEvent.serializer(), it) }
        assertTrue("secret-token" !in wire, wire)
        assertTrue("\\u0073ecret" !in wire, wire)
        assertTrue("***" in (events[1] as LlmEvent.AssistantMessage).text)
    }

    @Test
    fun `an opencode stdout file is translated through its own adapter`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        out.writeText(
            line("""{"type":"step_start","sessionID":"ses_1","part":{"id":"p1","type":"step-start"}}""") +
                line("""{"type":"text","sessionID":"ses_1","part":{"messageID":"m1","type":"text","text":"hello"}}""") +
                line(
                    """{"type":"tool_use","sessionID":"ses_1","part":{"id":"c1","tool":"bash",""" +
                        """"state":{"status":"completed","input":{"command":"ls"},"output":"ok"}}}""",
                ) +
                line("""{"type":"step_finish","sessionID":"ses_1","part":{"id":"p2","reason":"stop","cost":0.25}}"""),
        )
        val pm = finishedCodex(tmp, out)
        coEvery { pm.getCommandArtifacts(1, 0) } returns CommandArtifactPaths(out, tmp.resolve("e"), kind = "opencode")
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val events =
            service
                .streamCommand(1, 0, resume = null)
                .toList()
                .filterIsInstance<LlmStreamEvent.Event>()
                .map { it.event }

        assertEquals(
            listOf("session_start", "assistant_message", "tool_call", "tool_result", "turn_end"),
            events.map { typeOf(it) },
        )
        assertEquals("hello", (events[1] as LlmEvent.AssistantMessage).text)
        assertEquals(0.25, (events[4] as LlmEvent.TurnEnd).costUsd)
    }

    @Test
    fun `an event the mask makes undecodable is dropped, never sent unmasked, and logged once`(
        @TempDir tmp: Path,
    ) = runTest {
        // A secret equal to the event discriminator masks the event's own structure.
        val out = tmp.resolve("cmd_0.out")
        val message = """{"type":"item.completed","item":{"id":"m","type":"agent_message","text":"hi"}}""" + "\n"
        out.writeText(
            """{"type":"thread.started","thread_id":"t1"}""" + "\n" + message + message +
                """{"type":"turn.completed","usage":{}}""" + "\n",
        )
        val pm = finishedCodex(tmp, out)
        coEvery { pm.getCommandArtifacts(1, 0) } returns
            CommandArtifactPaths(
                out,
                tmp.resolve("e"),
                kind = "codex",
                redactor = SecretRedactor.from(mapOf("K" to "assistant_message"), setOf("K")),
            )
        val logger = TestFixtures.mockLogger()
        val service = LlmEventStreamService(pm, harnesses, logger, pollIntervalMs = 10)

        val events =
            service
                .streamCommand(1, 0, resume = null)
                .toList()
                .filterIsInstance<LlmStreamEvent.Event>()
                .map { it.event }

        assertEquals(listOf("session_start", "turn_end"), events.map { typeOf(it) })
        verify(exactly = 1) { logger.warn(match<String> { "secret mask" in it }, 1, 0, 0) }
    }

    @Test
    fun `a cursor at the start of the first line with eventIndex 0 skips exactly one event`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        val first = """{"type":"thread.started","thread_id":"t1"}""" + "\n"
        out.writeText(first + """{"type":"turn.completed","usage":{}}""" + "\n")
        val service = LlmEventStreamService(finishedCodex(tmp, out), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        val payloads = service.streamCommand(1, 0, StreamCursor(0, 0, 0, 0)).toList().filterIsInstance<LlmStreamEvent.Event>()
        assertEquals(listOf("turn_end"), payloads.map { typeOf(it.event) })
        assertEquals(first.toByteArray().size.toLong(), payloads.single().lineOffset)
    }

    private fun toolLineFixture(tmp: Path): Pair<Path, Long> {
        val out = tmp.resolve("cmd_0.out")
        val first = """{"type":"thread.started","thread_id":"t1"}""" + "\n"
        val tool =
            """{"type":"item.completed","item":{"id":"c1","type":"command_execution","command":"ls",""" +
                """"aggregated_output":"a","exit_code":0,"status":"completed"}}""" + "\n"
        out.writeText(first + tool + """{"type":"turn.completed","usage":{}}""" + "\n")
        return out to first.toByteArray().size.toLong()
    }

    @Test
    fun `resuming after the first of a line's two events emits the second and the following lines`(
        @TempDir tmp: Path,
    ) = runTest {
        val (out, toolLineStart) = toolLineFixture(tmp)
        val service = LlmEventStreamService(finishedCodex(tmp, out), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        val payloads = service.streamCommand(1, 0, StreamCursor(0, 0, toolLineStart, 0)).toList().filterIsInstance<LlmStreamEvent.Event>()
        assertEquals(listOf("tool_result", "turn_end"), payloads.map { typeOf(it.event) })
        assertEquals(toolLineStart to 1, payloads[0].lineOffset to payloads[0].eventIndex)
    }

    @Test
    fun `resuming after a line's last event emits only the following lines`(
        @TempDir tmp: Path,
    ) = runTest {
        val (out, toolLineStart) = toolLineFixture(tmp)
        val service = LlmEventStreamService(finishedCodex(tmp, out), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        val payloads = service.streamCommand(1, 0, StreamCursor(0, 0, toolLineStart, 1)).toList().filterIsInstance<LlmStreamEvent.Event>()
        assertEquals(listOf("turn_end"), payloads.map { typeOf(it.event) })
    }

    @Test
    fun `sibling events of one line carry increasing event indices under one line offset`(
        @TempDir tmp: Path,
    ) = runTest {
        val (out, toolLineStart) = toolLineFixture(tmp)
        val service = LlmEventStreamService(finishedCodex(tmp, out), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        val ids =
            service
                .streamCommand(1, 0, null)
                .toList()
                .filterIsInstance<LlmStreamEvent.Event>()
                .map { it.lineOffset to it.eventIndex }
        assertEquals(listOf(0L to 0, toolLineStart to 0, toolLineStart to 1), ids.take(3))
        assertTrue(ids.zipWithNext().all { (a, b) -> a.first < b.first || (a.first == b.first && a.second < b.second) })
    }

    @Test
    fun `the file is closed when the collector is cancelled mid-follow`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        out.writeText("""{"type":"thread.started","thread_id":"t1"}""" + "\n")
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.WORKING)
        coEvery { pm.getProcessStatus(1) } returns ProcessStatus.WORKING
        coEvery { pm.getCommandArtifacts(1, 0) } returns CommandArtifactPaths(out, tmp.resolve("e"), kind = "codex")
        coEvery { pm.latestCommandArtifacts(1) } returns null
        val channels = mutableListOf<FileChannel>()
        val service =
            LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10) { path ->
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).also { channels += it }
            }
        val received = mutableListOf<LlmStreamEvent>()
        val job = launch { service.streamCommand(1, 0, null).collect { received += it } }
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertEquals(1, received.size)
        assertEquals(listOf(true), channels.map { it.isOpen })
        job.cancelAndJoin()
        assertEquals(listOf(false), channels.map { it.isOpen })
    }

    @Test
    fun `streamCommand ignores a cursor for another attempt and replays from byte 0`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        val first = """{"type":"thread.started","thread_id":"t1"}""" + "\n"
        out.writeText(first + """{"type":"turn.completed","usage":{}}""" + "\n")
        val service = LlmEventStreamService(finishedCodex(tmp, out), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        val events = service.streamCommand(1, 0, StreamCursor(0, 3, 0, 0)).toList()
        assertEquals(2, events.filterIsInstance<LlmStreamEvent.Event>().size)
    }

    @Test
    fun `streamCommand emits only End for a non-LLM command`(
        @TempDir tmp: Path,
    ) = runTest {
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.WORKING)
        coEvery { pm.getCommandArtifacts(1, 0) } returns
            CommandArtifactPaths(tmp.resolve("cmd_0.out"), tmp.resolve("cmd_0.err"), kind = "script")
        val events = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger()).streamCommand(1, 0, null).toList()
        assertEquals(listOf(LlmStreamEvent.End), events)
    }

    @Test
    fun `a 3 MiB single line without a newline is held, not emitted`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        out.writeText("{\"type\":\"error\",\"message\":\"" + "x".repeat(3 * 1024 * 1024) + "\"")
        val logger = TestFixtures.mockLogger()
        val events =
            LlmEventStreamService(
                finishedCodex(tmp, out),
                harnesses,
                logger,
                pollIntervalMs = 10,
            ).streamCommand(1, 0, null).toList()
        assertEquals(listOf(LlmStreamEvent.End), events)
        verify(exactly = 1) { logger.warn(match<String> { "trailing partial" in it }, 1, 0, 0) }
    }

    @Test
    fun `a line over 4 MiB is dropped and the next line is still emitted`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        val oversized = "{\"type\":\"error\",\"message\":\"" + "x".repeat(5 * 1024 * 1024) + "\"}\n"
        val valid = """{"type":"thread.started","thread_id":"t1"}""" + "\n"
        out.writeText(oversized + valid)
        val logger = TestFixtures.mockLogger()
        val events =
            LlmEventStreamService(
                finishedCodex(tmp, out),
                harnesses,
                logger,
                pollIntervalMs = 10,
            ).streamCommand(1, 0, null).toList()
        val payloads = events.filterIsInstance<LlmStreamEvent.Event>()
        assertEquals(listOf("session_start"), payloads.map { typeOf(it.event) })
        assertEquals(oversized.toByteArray().size.toLong(), payloads.single().lineOffset)
        assertEquals(LlmStreamEvent.End, events.last())
        verify(exactly = 1) { logger.warn(match<String> { "over" in it }, LlmEventStreamService.MAX_LINE_BYTES, 1, 0, 0) }
    }

    @Test
    fun `a multi-byte character split across reads decodes intact`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        // Place a 2-byte char across the MAX_READ_BYTES boundary of the first read.
        val prefix = """{"type":"item.completed","item":{"id":"i","type":"agent_message","text":""""
        val pad = "x".repeat(LlmEventStreamService.MAX_READ_BYTES.toInt() - prefix.length - 1)
        out.writeText(prefix + pad + "é\"}}\n")
        val events =
            LlmEventStreamService(finishedCodex(tmp, out), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
                .streamCommand(1, 0, null)
                .toList()
        val text = (events.filterIsInstance<LlmStreamEvent.Event>().single().event as LlmEvent.AssistantMessage).text
        assertTrue(text.endsWith("xé"))
    }

    @Test
    fun `streamProcess skips non-LLM commands, waits for the first LLM command and ends after the process`(
        @TempDir tmp: Path,
    ) = runTest {
        val tracked = ConcurrentHashMap<Int, CommandArtifactPaths>()
        tracked[0] = scriptArtifact(tmp.resolve("cmd_0.out"))
        tracked[1] = scriptArtifact(tmp.resolve("cmd_1.out"))
        val status = AtomicReference(ProcessStatus.WORKING)
        val service = LlmEventStreamService(liveProcess(tmp, tracked, status), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val received = mutableListOf<LlmStreamEvent>()
        val job = launch { service.streamProcess(1, resume = null).collect { received += it } }
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(emptyList(), received)
        val out2 = tmp.resolve("cmd_2.out")
        out2.writeText(line("""{"type":"thread.started","thread_id":"t"}"""))
        tracked[2] = codexArtifact(out2)
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertEquals(1, received.size)
        out2.appendText(line("""{"type":"turn.completed","usage":{}}"""))
        status.set(ProcessStatus.FINISHED)
        job.join()
        assertEquals(listOf(2, 2), received.filterIsInstance<LlmStreamEvent.Event>().map { it.cmdIndex })
        assertEquals(LlmStreamEvent.End, received.last())
    }

    @Test
    fun `streamProcess drains command 0 before switching to command 2 and re-targets a later attempt`(
        @TempDir tmp: Path,
    ) = runTest {
        val out0 = tmp.resolve("cmd_0.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"a"}""")) }
        val out0b = tmp.resolve("cmd_0_attempt_1.out")
        val tracked = ConcurrentHashMap<Int, CommandArtifactPaths>()
        tracked[0] = codexArtifact(out0, attemptIndex = 0)
        val status = AtomicReference(ProcessStatus.WORKING)
        val service = LlmEventStreamService(liveProcess(tmp, tracked, status), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val collected = async { service.streamProcess(1, null).toList() }
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        // Attempt 0's last bytes land before attempt 1 registers, and must arrive before the switch.
        out0.appendText(line("""{"type":"turn.failed","error":{"message":"x"}}"""))
        out0b.writeText(line("""{"type":"thread.started","thread_id":"b"}"""))
        tracked[0] = codexArtifact(out0b, attemptIndex = 1)
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        val out2 = tmp.resolve("cmd_2.out").also { it.writeText(line("""{"type":"turn.completed","usage":{}}""")) }
        tracked[1] = scriptArtifact(tmp.resolve("cmd_1.out"))
        tracked[2] = codexArtifact(out2)
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        status.set(ProcessStatus.FINISHED)
        val payloads = collected.await().filterIsInstance<LlmStreamEvent.Event>()
        assertEquals(listOf(0 to 0, 0 to 0, 0 to 1, 2 to 0), payloads.map { it.cmdIndex to it.attemptIndex })
    }

    @Test
    fun `streamProcess resumes from a cursor at a later command`(
        @TempDir tmp: Path,
    ) = runTest {
        val out0 = tmp.resolve("cmd_0.out").also { it.writeText(line("""{"type":"turn.completed","usage":{}}""")) }
        val out1 =
            tmp.resolve("cmd_1.out").also {
                it.writeText(line("""{"type":"thread.started","thread_id":"t"}""") + line("""{"type":"turn.completed","usage":{}}"""))
            }
        val tracked = mapOf(0 to codexArtifact(out0), 1 to codexArtifact(out1))
        val pm = liveProcess(tmp, tracked, AtomicReference(ProcessStatus.FINISHED))
        val payloads =
            LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
                .streamProcess(1, StreamCursor(1, 0, 0, 0))
                .toList()
                .filterIsInstance<LlmStreamEvent.Event>()
        assertEquals(listOf(1 to "turn_end"), payloads.map { it.cmdIndex to typeOf(it.event) })
    }

    @Test
    fun `streamProcess follows a run that registered only a later command`(
        @TempDir tmp: Path,
    ) = runTest {
        // A single-command debug run records nothing for the indices it skips.
        val out2 = tmp.resolve("cmd_2.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"t"}""")) }
        val pm = liveProcess(tmp, mapOf(2 to codexArtifact(out2)), AtomicReference(ProcessStatus.FINISHED))
        val payloads =
            LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
                .streamProcess(1, null)
                .toList()
                .filterIsInstance<LlmStreamEvent.Event>()
        assertEquals(listOf(2), payloads.map { it.cmdIndex })
    }

    @Test
    fun `streamProcess on a run without LLM commands emits only End, once the process is terminal`(
        @TempDir tmp: Path,
    ) = runTest {
        val status = AtomicReference(ProcessStatus.WORKING)
        val pm = liveProcess(tmp, mapOf(0 to scriptArtifact(tmp.resolve("cmd_0.out"))), status)
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        val collected = async { service.streamProcess(1, null).toList() }
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()
        assertFalse(collected.isCompleted)
        status.set(ProcessStatus.FINISHED)
        assertEquals(listOf(LlmStreamEvent.End), collected.await())
    }

    @Test
    fun `streamProcess of a process that is gone emits only End`() =
        runTest {
            val pm = processManagerWithoutJob()
            coEvery { pm.getCommandArtifacts(1, any()) } returns null
            coEvery { pm.getProcessInfo(1) } returns null
            val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
            assertEquals(listOf(LlmStreamEvent.End), service.streamProcess(1, null).toList())
        }

    @Test
    fun `streamProcess ignores a tracked path outside the artifacts dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val outside =
            tmp.resolve("other").also { it.toFile().mkdirs() }.resolve("cmd_0.out").also {
                it.writeText(line("""{"type":"thread.started","thread_id":"t"}"""))
            }
        val pm = liveProcess(tmp, mapOf(0 to codexArtifact(outside)), AtomicReference(ProcessStatus.FINISHED))
        val events = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10).streamProcess(1, null).toList()
        assertEquals(listOf(LlmStreamEvent.End), events)
    }

    @Test
    fun `streamCommand re-targets a newer attempt of its command`(
        @TempDir tmp: Path,
    ) = runTest {
        val out0 = tmp.resolve("cmd_0.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"a"}""")) }
        val out0b = tmp.resolve("cmd_0_attempt_1.out")
        val tracked = ConcurrentHashMap<Int, CommandArtifactPaths>()
        tracked[0] = codexArtifact(out0, attemptIndex = 0)
        val status = AtomicReference(ProcessStatus.WORKING)
        val service = LlmEventStreamService(liveProcess(tmp, tracked, status), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val collected = async { service.streamCommand(1, 0, null).toList() }
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        out0.appendText(line("""{"type":"turn.failed","error":{"message":"x"}}"""))
        out0b.writeText(line("""{"type":"thread.started","thread_id":"b"}"""))
        tracked[0] = codexArtifact(out0b, attemptIndex = 1)
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        status.set(ProcessStatus.FINISHED)
        val events = collected.await()
        assertEquals(listOf(0 to 0, 0 to 0, 0 to 1), events.filterIsInstance<LlmStreamEvent.Event>().map { it.cmdIndex to it.attemptIndex })
        assertEquals(LlmStreamEvent.End, events.last())
    }

    @Test
    fun `streamCommand ends once a later command registers while the process is still working`(
        @TempDir tmp: Path,
    ) = runTest {
        val out0 = tmp.resolve("cmd_0.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"a"}""")) }
        val tracked = ConcurrentHashMap<Int, CommandArtifactPaths>()
        tracked[0] = codexArtifact(out0)
        val status = AtomicReference(ProcessStatus.WORKING)
        val service = LlmEventStreamService(liveProcess(tmp, tracked, status), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val collected = async { service.streamCommand(1, 0, null).toList() }
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertFalse(collected.isCompleted)
        out0.appendText(line("""{"type":"turn.completed","usage":{}}"""))
        tracked[1] = scriptArtifact(tmp.resolve("cmd_1.out"))
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertTrue(collected.isCompleted)
        assertEquals(ProcessStatus.WORKING, status.get())
        val events = collected.await()
        assertEquals(listOf("session_start", "turn_end"), events.filterIsInstance<LlmStreamEvent.Event>().map { typeOf(it.event) })
        assertEquals(LlmStreamEvent.End, events.last())
    }

    @Test
    fun `an UNKNOWN process drains and emits End (per-command)`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        out.writeText(line("""{"type":"thread.started","thread_id":"t1"}"""))
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.UNKNOWN)
        coEvery { pm.getProcessStatus(1) } returns ProcessStatus.UNKNOWN
        coEvery { pm.getCommandArtifacts(1, 0) } returns codexArtifact(out)
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val events = service.streamCommand(1, 0, resume = null).toList()

        assertEquals(listOf("session_start"), events.filterIsInstance<LlmStreamEvent.Event>().map { typeOf(it.event) })
        assertEquals(LlmStreamEvent.End, events.last())
    }

    @Test
    fun `an UNKNOWN process drains and emits End (process mode)`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out")
        out.writeText(line("""{"type":"thread.started","thread_id":"t1"}"""))
        val status = AtomicReference(ProcessStatus.UNKNOWN)
        val pm = liveProcess(tmp, mapOf(0 to codexArtifact(out)), status)
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)

        val events = service.streamProcess(1, null).toList()

        assertEquals(listOf("session_start"), events.filterIsInstance<LlmStreamEvent.Event>().map { typeOf(it.event) })
        assertEquals(LlmStreamEvent.End, events.last())
    }

    @Test
    fun `a live run's file follow reads its status only on the safety cadence`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"t"}""")) }
        val pm = liveProcess(tmp, mapOf(0 to codexArtifact(out)), AtomicReference(ProcessStatus.WORKING))
        every { pm.hasLiveJob(1) } returns true
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 100)

        val job = launch { service.streamCommand(1, 0, null).collect {} }
        // 200 ticks of 100 ms while the job is live: one safety read per 50 ticks, none in between.
        testScheduler.advanceTimeBy(4 * SAFETY_STATUS_READ_MS)
        coVerify(exactly = 4) { pm.getProcessStatus(1) }
        // resolve's one read; the follow itself reads no row.
        coVerify(exactly = 1) { pm.getProcessInfo(1) }
        job.cancelAndJoin()
    }

    @Test
    fun `a live run waiting for its first command reads the database only on the safety cadence`(
        @TempDir tmp: Path,
    ) = runTest {
        val tracked = ConcurrentHashMap<Int, CommandArtifactPaths>()
        val pm = liveProcess(tmp, tracked, AtomicReference(ProcessStatus.WORKING))
        every { pm.hasLiveJob(1) } returns true
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 100)

        val received = mutableListOf<LlmStreamEvent>()
        val job = launch { service.streamProcess(1, null).collect { received += it } }
        testScheduler.advanceTimeBy(4 * SAFETY_STATUS_READ_MS)
        coVerify(exactly = 4) { pm.getProcessInfo(1) }
        coVerify(exactly = 4) { pm.getProcessStatus(1) }
        // The start is seen in memory on the next tick, not at the next safety read.
        val out = tmp.resolve("cmd_0.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"t"}""")) }
        tracked[0] = codexArtifact(out)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(1, received.size)
        job.cancelAndJoin()
    }

    @Test
    fun `a live stream ends on the next tick once its job is gone and the status is terminal`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"t"}""")) }
        val live = AtomicBoolean(true)
        val status = AtomicReference(ProcessStatus.WORKING)
        val pm = liveProcess(tmp, mapOf(0 to codexArtifact(out)), status)
        every { pm.hasLiveJob(1) } answers { live.get() }
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 100)

        val collected = async { service.streamProcess(1, null).toList() }
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertFalse(collected.isCompleted)
        status.set(ProcessStatus.FINISHED)
        live.set(false)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertTrue(collected.isCompleted)
        assertEquals(LlmStreamEvent.End, collected.await().last())
    }

    @Test
    fun `a terminal run whose job lingers still ends on the safety read`(
        @TempDir tmp: Path,
    ) = runTest {
        val out = tmp.resolve("cmd_0.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"t"}""")) }
        val pm = liveProcess(tmp, mapOf(0 to codexArtifact(out)), AtomicReference(ProcessStatus.FINISHED))
        every { pm.hasLiveJob(1) } returns true
        val service = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 100)

        val collected = async { service.streamProcess(1, null).toList() }
        testScheduler.advanceTimeBy(SAFETY_STATUS_READ_MS / 2)
        testScheduler.runCurrent()
        assertFalse(collected.isCompleted)
        // One safety read ends both the file follow and the wait for a later command.
        testScheduler.advanceTimeBy(SAFETY_STATUS_READ_MS / 2)
        testScheduler.runCurrent()
        assertTrue(collected.isCompleted)
        assertEquals(LlmStreamEvent.End, collected.await().last())
    }

    @Test
    fun `a restarted run without a job streams its persisted execution rows and ends at once`(
        @TempDir tmp: Path,
    ) = runTest {
        tmp.resolve("cmd_0.out").writeText(line("""{"type":"thread.started","thread_id":"t"}"""))
        val row = ExecutionInfo("l", 1, 2, 0, Path.of("cmd_0.out"), Path.of("cmd_0.err"), null, kind = "codex")
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, tmp, ProcessStatus.UNKNOWN, listOf(row))
        coEvery { pm.getProcessStatus(1) } returns ProcessStatus.UNKNOWN
        coEvery { pm.getCommandArtifacts(1, any()) } returns null
        coEvery { pm.latestCommandArtifacts(1) } returns null

        val events = LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger(), pollIntervalMs = 100).streamProcess(1, null).toList()

        assertEquals(listOf("session_start"), events.filterIsInstance<LlmStreamEvent.Event>().map { typeOf(it.event) })
        assertEquals(LlmStreamEvent.End, events.last())
        assertEquals(0L, testScheduler.currentTime)
    }

    // Linked / non-regular stdout artifacts (CWE-22, CWE-59): an agent runs as Homer's UID
    // and can plant any of these at its artifact name; none may be served as LLM events.

    private val secretLine = line("""{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"HOST-SECRET"}}""")

    private fun artifactsIn(tmp: Path): Path = tmp.resolve("artifacts").createDirectories()

    private fun outsideSecret(tmp: Path): Path = tmp.resolve("host-secret.jsonl").also { it.writeText(secretLine) }

    private fun assertNothingServed(events: List<LlmStreamEvent>) {
        assertEquals(listOf<LlmStreamEvent>(LlmStreamEvent.End), events)
    }

    @Test
    fun `resolve refuses a tracked symlink pointing outside the artifacts dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val link = Files.createSymbolicLink(dir.resolve("cmd_0.out"), outsideSecret(tmp))
        val service = LlmEventStreamService(finishedCodex(dir, link), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        assertNull(service.resolve(1, 0))
        assertNothingServed(service.streamCommand(1, 0, null).toList())
    }

    @Test
    fun `resolve refuses a tracked symlink to another artifact in the same dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val other = dir.resolve("cmd_1.out").also { it.writeText(secretLine) }
        val link = Files.createSymbolicLink(dir.resolve("cmd_0.out"), other)
        val service = LlmEventStreamService(finishedCodex(dir, link), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        assertNull(service.resolve(1, 0))
        assertNothingServed(service.streamCommand(1, 0, null).toList())
    }

    @Test
    fun `resolve refuses a dangling tracked symlink`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val link = Files.createSymbolicLink(dir.resolve("cmd_0.out"), tmp.resolve("not-yet-there"))
        assertNull(LlmEventStreamService(finishedCodex(dir, link), harnesses, TestFixtures.mockLogger()).resolve(1, 0))
    }

    @Test
    fun `resolve refuses an execution row whose stdout is a symlink`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        Files.createSymbolicLink(dir.resolve("cmd_0.out"), outsideSecret(tmp))
        val row = ExecutionInfo("l", 1, 2, 0, Path.of("cmd_0.out"), Path.of("e"), null, kind = "codex")
        val pm = processManagerWithoutJob()
        coEvery { pm.getProcessInfo(1) } returns processInfo(1, dir, ProcessStatus.FINISHED, listOf(row))
        coEvery { pm.getCommandArtifacts(1, 0) } returns null
        assertNull(LlmEventStreamService(pm, harnesses, TestFixtures.mockLogger()).resolve(1, 0))
    }

    @Test
    fun `a symlink planted after resolve is refused at open and the stream ends normally`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val out = dir.resolve("cmd_0.out")
        val status = AtomicReference(ProcessStatus.WORKING)
        val logger = TestFixtures.mockLogger()
        val service =
            LlmEventStreamService(liveProcess(dir, mapOf(0 to codexArtifact(out)), status), harnesses, logger, pollIntervalMs = 10)

        val collected = async { service.streamCommand(1, 0, null).toList() }
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        Files.createSymbolicLink(out, outsideSecret(tmp))
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        status.set(ProcessStatus.FINISHED)

        assertNothingServed(collected.await())
        verify(exactly = 1) { logger.warn(match<String> { "refusing" in it }, 1, 0, 0) }
    }

    @Test
    fun `a hard-linked stdout artifact is refused and warned about once`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val out = Files.createLink(dir.resolve("cmd_0.out"), outsideSecret(tmp))
        val status = AtomicReference(ProcessStatus.WORKING)
        val logger = TestFixtures.mockLogger()
        val service =
            LlmEventStreamService(liveProcess(dir, mapOf(0 to codexArtifact(out)), status), harnesses, logger, pollIntervalMs = 10)
        assertEquals(out, service.resolve(1, 0)?.path)

        val collected = async { service.streamCommand(1, 0, null).toList() }
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        status.set(ProcessStatus.FINISHED)

        assertNothingServed(collected.await())
        verify(exactly = 1) { logger.warn(match<String> { "refusing" in it }, 1, 0, 0) }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a FIFO stdout artifact is refused without being opened`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val out = dir.resolve("cmd_0.out")
        TestFixtures.mkfifoOrSkip(out)
        val service = LlmEventStreamService(finishedCodex(dir, out), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        assertNothingServed(service.streamCommand(1, 0, null).toList())
    }

    @Test
    fun `a regular stdout artifact inside the artifacts dir still streams`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val out = dir.resolve("cmd_0.out").also { it.writeText(line("""{"type":"thread.started","thread_id":"t1"}""")) }
        val service = LlmEventStreamService(finishedCodex(dir, out), harnesses, TestFixtures.mockLogger(), pollIntervalMs = 10)
        val events = service.streamCommand(1, 0, null).toList()
        assertEquals(listOf("session_start"), events.filterIsInstance<LlmStreamEvent.Event>().map { typeOf(it.event) })
    }

    @Test
    fun `cursor parses and prints its id`() {
        assertEquals(StreamCursor(2, 1, 40, 3), StreamCursor.parse("2:1:40:3"))
        assertNull(StreamCursor.parse("2:1:40"))
        assertNull(StreamCursor.parse("garbage"))
        assertNull(StreamCursor.parse("-1:0:0:0"))
        assertNull(StreamCursor.parse("0:0:0:-1"))
        assertNull(StreamCursor.parse(null))
        assertEquals("2:1:40:3", StreamCursor(2, 1, 40, 3).id)
    }
}
