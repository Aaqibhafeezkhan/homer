package homer.app.runners

import homer.app.EnvConstants
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RemotePathTranslatorTest {
    private val translator =
        RemotePathTranslator(
            serverProjectRoot = "/app/workspace/deploy/p1",
            remoteProjectRoot = "/home/homer/ws/deploy/p1",
            remoteAgentDir = "/home/homer/ws/deploy/p1/.homer/agent",
            apiUrl = "https://homer.internal",
        )

    @Test
    fun `prefix-swaps workspace-rooted env values and returns only changed keys`() {
        val out =
            translator.overrides(
                mapOf(
                    EnvConstants.WORK_DIR to "/app/workspace/deploy/p1/workdir",
                    EnvConstants.ARTIFACTS_DIR to "/app/workspace/deploy/p1/artifacts",
                    EnvConstants.MCP_CONFIG to "/app/workspace/deploy/p1/.homer/mcp.json",
                    "PATH" to "/app/workspace/deploy/p1/.homer/bin:/usr/bin",
                    "UNRELATED" to "keepme",
                ),
            )
        assertEquals("/home/homer/ws/deploy/p1/workdir", out[EnvConstants.WORK_DIR])
        assertEquals("/home/homer/ws/deploy/p1/artifacts", out[EnvConstants.ARTIFACTS_DIR])
        assertEquals("/home/homer/ws/deploy/p1/.homer/mcp.json", out[EnvConstants.MCP_CONFIG])
        assertEquals("/home/homer/ws/deploy/p1/.homer/bin:/usr/bin", out["PATH"])
        // Unchanged keys are not returned (appendToEnv only needs the deltas).
        assertFalse("UNRELATED" in out)
    }

    @Test
    fun `always overrides HMR_AGENT_DIR to the remote agent dir`() {
        val out = translator.overrides(mapOf(EnvConstants.AGENT_DIR to "/srv/agents/deploy"))
        assertEquals("/home/homer/ws/deploy/p1/.homer/agent", out[EnvConstants.AGENT_DIR])
    }

    @Test
    fun `overrides HMR_API_URL when configured`() {
        val out = translator.overrides(emptyMap())
        assertEquals("https://homer.internal", out[EnvConstants.API_URL])
    }

    @Test
    fun `omits HMR_API_URL when not configured`() {
        val t = RemotePathTranslator("/a", "/b", "/b/.homer/agent", apiUrl = null)
        assertNull(t.overrides(emptyMap())[EnvConstants.API_URL])
    }

    @Test
    fun `blank apiUrl is omitted`() {
        val t =
            RemotePathTranslator(
                "/app/workspace/deploy/p1",
                "/home/homer/ws/deploy/p1",
                "/home/homer/ws/deploy/p1/.homer/agent",
                apiUrl = "   ",
            )
        val out = t.overrides(emptyMap())
        assertFalse(EnvConstants.API_URL in out)
    }

    @Test
    fun `substitutes every occurrence of the local project root in a value`() {
        val out =
            translator.overrides(
                mapOf(
                    "CLASSPATH" to
                        "/app/workspace/deploy/p1/a:/app/workspace/deploy/p1/b",
                ),
            )
        assertEquals(
            "/home/homer/ws/deploy/p1/a:/home/homer/ws/deploy/p1/b",
            out["CLASSPATH"],
        )
    }
}
