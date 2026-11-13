package homer.app.runners.external

import homer.app.apiJson
import homer.app.runners.DownloadMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunnerProtocolTest {
    @Test
    fun `ops serialize with a type discriminator and round-trip`() {
        val ops: List<RunnerOp> =
            listOf(
                RunnerOp.CreateWorkspace("op-1"),
                RunnerOp.Upload("op-2", remoteRelative = "."),
                RunnerOp.Upload("op-2b", remoteRelative = ".homer/extra/key.pem", isDir = false),
                RunnerOp.Upload("op-2b", remoteRelative = ".homer/extra/key.pem", isDir = false),
                RunnerOp.Cmd(
                    "op-3",
                    argv = listOf("sh", "-c", "echo hi"),
                    env = mapOf("A" to "1"),
                    stdin = "x",
                    timeoutMs = 5_000,
                    cwd = "/r/1/workdir",
                ),
                RunnerOp.Cmd(
                    "op-3b",
                    argv = listOf("sh", "-c", "echo hi"),
                    env = emptyMap(),
                    stdin = null,
                    timeoutMs = 5_000,
                    cwd = "/r/1/workdir",
                    interruptGraceSeconds = 15,
                ),
                RunnerOp.Download("op-4", remoteRelative = "artifacts", mode = DownloadMode.OVERLAY),
                RunnerOp.CleanWorkspace("op-5"),
                RunnerOp.Close("op-6", reason = "finished"),
                RunnerOp.Cancel("op-3"),
                RunnerOp.Interrupt("op-3"),
            )
        ops.forEach { op ->
            val json = apiJson.encodeToString(RunnerOp.serializer(), op)
            assertTrue(json.contains("\"type\""), json)
            assertEquals(op, apiJson.decodeFromString(RunnerOp.serializer(), json))
        }
        assertTrue(apiJson.encodeToString(RunnerOp.serializer(), ops[4]).contains("\"type\": \"cmd\""))
    }

    @Test
    fun `an old-format cmd decodes without the interrupt grace and an unknown op type is ignored`() {
        // An instance older than the field sends a `cmd` without it: the default must apply.
        val older =
            apiJson.decodeFromString(
                RunnerOp.serializer(),
                """{"type":"cmd","opId":"op-3","argv":["sh"],"env":{},"stdin":null,"timeoutMs":1000,"cwd":"/r"}""",
            )
        assertEquals(RunnerOp.Cmd("op-3", listOf("sh"), emptyMap(), null, 1_000, "/r"), older)
        assertNull((older as RunnerOp.Cmd).interruptGraceSeconds)

        // The interrupt rides its own op, so a `cmd` with no grace and a frame with an unknown
        // key both stay decodable by either side.
        assertEquals(
            RunnerOp.Interrupt("op-3"),
            apiJson.decodeFromString(RunnerOp.serializer(), """{"type":"interrupt","opId":"op-3"}"""),
        )
        assertEquals(
            "op-3",
            runnerProtocolJson.decodeFromString(RunnerOp.serializer(), """{"type":"cancel","opId":"op-3","future":1}""").opId,
        )
    }

    @Test
    fun `results and requests default their optional fields`() {
        val r = apiJson.decodeFromString(OpResult.serializer(), """{"opId":"op-3","exitCode":0}""")
        assertEquals(OpResult(opId = "op-3", exitCode = 0), r)
        assertEquals(true, r.ok)
        assertEquals(false, r.timedOut)
        val n = apiJson.decodeFromString(NextRequest.serializer(), "{}")
        assertEquals(null, n.completed)
        val reg = apiJson.decodeFromString(RegisterInstanceRequest.serializer(), """{"tags":["linux"]}""")
        assertEquals(1, reg.concurrency)
        assertEquals(null, reg.name)
    }

    @Test
    fun `a cmd result with counts stays wire-compatible with an older binary`() {
        // An older runner reports no counts: both fields default, so this Homer reads the result.
        val older = apiJson.decodeFromString(OpResult.serializer(), """{"opId":"op-3","exitCode":0}""")
        assertEquals(OpResult(opId = "op-3", exitCode = 0), older)
        assertNull(older.stdoutChunks)
        assertNull(older.stderrChunks)

        // A newer runner's counts ride the same shape through the `next` body Homer decodes.
        val counted = OpResult("op-3", exitCode = 0, stdoutChunks = 2, stderrChunks = 1)
        val body = apiJson.encodeToString(NextRequest.serializer(), NextRequest(counted))
        assertEquals(counted, apiJson.decodeFromString(NextRequest.serializer(), body).completed)

        // And a payload carrying a field this binary does not know is ignored by the Json the
        // runner routes decode with, so an instance newer than this server is not a 400.
        assertEquals(
            "op-3",
            runnerProtocolJson.decodeFromString(OpResult.serializer(), """{"opId":"op-3","future":1}""").opId,
        )
    }

    @Test
    fun `ids are 32 hex characters and unique`() {
        val ids = (1..100).map { RunnerProtocol.newId() }.toSet()
        assertEquals(100, ids.size)
        ids.forEach { assertTrue(Regex("^[0-9a-f]{32}$").matches(it), it) }
    }
}
