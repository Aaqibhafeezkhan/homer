package homer.app.infra

import homer.app.EnvConstants
import homer.app.fixtures.TestFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionsWorkspaceSetupTest {
    private fun workspace(
        root: Path,
        env: Map<String, String> = emptyMap(),
    ): Workspace {
        val projectRoot = root.resolve("project")
        val config =
            WorkspaceConfig(
                serverRoot = root,
                projectRoot = projectRoot,
                artifacts = projectRoot.resolve("artifacts"),
                workdir = projectRoot.resolve("workdir"),
                env = env,
                clearAfterFinish = false,
                lockKey = "test-lock",
            )
        return Workspace(config, TestFixtures.mockSpan())
    }

    @Test
    fun `materialize writes an executable homer-ask helper`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root)

        QuestionsWorkspaceSetup(mcpEnabled = false).materialize(ws)

        val helper = ws.config.projectRoot.resolve(".homer/bin/homer-ask")
        assertTrue(helper.exists(), "helper must be materialized")
        assertTrue(helper.isExecutable(), "helper must be executable")
        val content = helper.readText()
        assertTrue(content.startsWith("#!/bin/sh"), "helper must be a POSIX sh script")
        for (subcommand in listOf("ask)", "check)", "ask-and-wait)")) {
            assertTrue(subcommand in content, "helper must handle '$subcommand'")
        }
        assertTrue("HMR_CALLBACK_TOKEN" in content, "helper must authenticate with the callback token")
    }

    @Test
    fun `materialize writes an executable homer-dispatch helper on PATH`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root, env = mapOf("PATH" to "/usr/bin"))

        QuestionsWorkspaceSetup(mcpEnabled = false).materialize(ws)

        val binDir = ws.config.projectRoot.resolve(".homer/bin")
        val helper = binDir.resolve("homer-dispatch")
        assertTrue(helper.exists(), "helper must be materialized")
        assertTrue(helper.isExecutable(), "helper must be executable")
        assertEquals("$binDir:/usr/bin", ws.config.env["PATH"], "bin dir must be on PATH")
        val content = helper.readText()
        assertTrue(content.startsWith("#!/bin/sh"), "helper must be a POSIX sh script")
        assertTrue("--agent" in content, "helper must accept --agent")
        assertTrue("callback/runs" in content, "helper must POST to the runs callback")
        assertTrue("HMR_CALLBACK_TOKEN" in content, "helper must authenticate with the callback token")
    }

    @Test
    fun `materialize writes an executable homer-result helper on PATH`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root, env = mapOf("PATH" to "/usr/bin"))

        QuestionsWorkspaceSetup(mcpEnabled = false).materialize(ws)

        val helper = ws.config.projectRoot.resolve(".homer/bin/homer-result")
        assertTrue(helper.exists(), "helper must be materialized")
        assertTrue(helper.isExecutable(), "helper must be executable")
        val content = helper.readText()
        assertTrue(content.startsWith("#!/bin/sh"), "helper must be a POSIX sh script")
        assertTrue("--token" in content, "helper must accept --token")
        assertTrue("callback/results" in content, "helper must GET the results callback")
        assertTrue("HMR_CALLBACK_TOKEN" in content, "helper must authenticate with the callback token")
    }

    @Test
    fun `materialize writes an executable homer-join helper on PATH`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root, env = mapOf("PATH" to "/usr/bin"))

        QuestionsWorkspaceSetup(mcpEnabled = false).materialize(ws)

        val helper = ws.config.projectRoot.resolve(".homer/bin/homer-join")
        assertTrue(helper.exists(), "helper must be materialized")
        assertTrue(helper.isExecutable(), "helper must be executable")
        val content = helper.readText()
        assertTrue(content.startsWith("#!/bin/sh"), "helper must be a POSIX sh script")
        assertTrue("callback/joins" in content, "helper must POST to the joins callback")
        assertTrue("HMR_CALLBACK_TOKEN" in content, "helper must authenticate with the callback token")
    }

    @Test
    fun `materialize writes an executable homer-tag helper on PATH`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root, env = mapOf("PATH" to "/usr/bin"))

        QuestionsWorkspaceSetup(mcpEnabled = false).materialize(ws)

        val helper = ws.config.projectRoot.resolve(".homer/bin/homer-tag")
        assertTrue(helper.exists(), "helper must be materialized")
        assertTrue(helper.isExecutable(), "helper must be executable")
        val content = helper.readText()
        assertTrue(content.startsWith("#!/bin/sh"), "helper must be a POSIX sh script")
        assertTrue("callback/tags" in content, "helper must POST to the tags callback")
        assertTrue("HMR_CALLBACK_TOKEN" in content, "helper must authenticate with the callback token")
    }

    @Test
    fun `materialize over an existing helper keeps it intact and executable`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root)
        val setup = QuestionsWorkspaceSetup(mcpEnabled = false)
        setup.materialize(ws)
        val helper = ws.config.projectRoot.resolve(".homer/bin/homer-ask")
        val firstContent = helper.readText()

        setup.materialize(ws)

        assertEquals(firstContent, helper.readText())
        assertTrue(helper.isExecutable(), "helper must stay executable after re-materialization")
    }

    @Test
    fun `materialize prepends the bin dir to an existing PATH`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root, env = mapOf("PATH" to "/opt/tools:/usr/bin"))

        QuestionsWorkspaceSetup(mcpEnabled = false).materialize(ws)

        val binDir = ws.config.projectRoot.resolve(".homer/bin")
        assertEquals("$binDir:/opt/tools:/usr/bin", ws.config.env["PATH"])
    }

    @Test
    fun `materialize leaves PATH unset when the composed env has no PATH`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root)

        QuestionsWorkspaceSetup(mcpEnabled = false).materialize(ws)

        // Forcing a PATH would override a container image's built-in PATH;
        // the helper stays reachable at its absolute path instead.
        assertNull(ws.config.env["PATH"])
    }

    @Test
    fun `mcp disabled means no mcp config file and no env pointer`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root)

        QuestionsWorkspaceSetup(mcpEnabled = false).materialize(ws)

        assertFalse(
            ws.config.projectRoot
                .resolve(".homer/mcp.json")
                .exists(),
        )
        assertNull(ws.config.env[EnvConstants.MCP_CONFIG])
    }

    @Test
    fun `mcp enabled writes a placeholder-only mcp config and points HMR_MCP_CONFIG at it`(
        @TempDir root: Path,
    ) {
        val ws = workspace(root)

        QuestionsWorkspaceSetup(mcpEnabled = true).materialize(ws)

        val mcpConfig = ws.config.projectRoot.resolve(".homer/mcp.json")
        assertEquals(mcpConfig.toString(), ws.config.env[EnvConstants.MCP_CONFIG])
        val server =
            Json
                .parseToJsonElement(mcpConfig.readText())
                .jsonObject["mcpServers"]!!
                .jsonObject["homer-questions"]!!
                .jsonObject
        assertEquals("http", server["type"]!!.jsonPrimitive.content)
        // ${VAR} placeholders expanded by Claude Code at read time — the
        // callback token itself must never be written to disk.
        assertEquals("\${HMR_API_URL}/mcp/questions", server["url"]!!.jsonPrimitive.content)
        assertEquals(
            "Bearer \${HMR_CALLBACK_TOKEN}",
            server["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content,
        )
    }
}
