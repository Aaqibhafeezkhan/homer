package homer.app.runners.external

import homer.app.apiJson
import homer.app.runners.DownloadMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
                RunnerOp.Download("op-4", remoteRelative = "artifacts", mode = DownloadMode.OVERLAY),
                RunnerOp.CleanWorkspace("op-5"),
                RunnerOp.Close("op-6", reason = "finished"),
                RunnerOp.Cancel("op-3"),
            )
        ops.forEach { op ->
            val json = apiJson.encodeToString(RunnerOp.serializer(), op)
            assertTrue(json.contains("\"type\""), json)
            assertEquals(op, apiJson.decodeFromString(RunnerOp.serializer(), json))
        }
        assertTrue(apiJson.encodeToString(RunnerOp.serializer(), ops[4]).contains("\"type\": \"cmd\""))
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
    fun `ids are 32 hex characters and unique`() {
        val ids = (1..100).map { RunnerProtocol.newId() }.toSet()
        assertEquals(100, ids.size)
        ids.forEach { assertTrue(Regex("^[0-9a-f]{32}$").matches(it), it) }
    }
}
