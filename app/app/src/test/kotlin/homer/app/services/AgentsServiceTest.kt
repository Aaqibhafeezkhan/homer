package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for AgentsService
 * Tests agent loading, validation, and parameter validation logic
 */
class AgentsServiceTest {
    private lateinit var logger: Logger
    private lateinit var agentsService: AgentsService
    private lateinit var testAgentsDir: Path
    private val testAgentsPath get() = testAgentsDir.toString()

    @BeforeEach
    fun setup() {
        logger = TestFixtures.mockLogger()

        // Create a real temp directory for tests
        testAgentsDir = Files.createTempDirectory("agents-test")
    }

    @AfterEach
    fun teardown() {
        // Clean up temp directory
        if (::testAgentsDir.isInitialized && Files.exists(testAgentsDir)) {
            testAgentsDir.toFile().deleteRecursively()
        }
        unmockkAll()
    }

    private fun cleanTestDir() {
        // Clean out any existing content in test directory
        if (Files.exists(testAgentsDir)) {
            testAgentsDir.toFile().listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    // ========== Agent Loading Tests ==========

    @Test
    fun `should return empty map when agents directory does not exist`() {
        // Use a non-existent path
        val nonExistentPath = testAgentsDir.resolve("non-existent").toString()

        agentsService = AgentsService(nonExistentPath, logger)

        assertTrue(agentsService.agents.isEmpty(), "Should return empty map")
        verify { logger.warn(match<String> { it.contains("does not exist") }, nonExistentPath) }
    }

    @Test
    fun `should return empty map when path is not a directory`() {
        // Create a file instead of directory
        val filePath = testAgentsDir.resolve("not-a-dir")
        Files.write(filePath, "content".toByteArray())

        agentsService = AgentsService(filePath.toString(), logger)

        assertTrue(agentsService.agents.isEmpty(), "Should return empty map")
        verify { logger.warn(match<String> { it.contains("not a directory") }, filePath.toString()) }
    }

    @Test
    fun `should load valid agent from directory`() {
        // Create an agent directory with agent.json
        val agentDir = testAgentsDir.resolve("test-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertEquals(1, agentsService.agents.size, "Should load one agent")
        assertTrue(agentsService.agents.containsKey("test_agent"), "Should load agent from JSON")
        verify { logger.info(match<String> { it.contains("Loaded agent") }, any(), any()) }
    }

    @Test
    fun `should skip agent with invalid name containing special characters`() {
        // Create an agent directory with invalid agent name
        val agentDir = testAgentsDir.resolve("invalid-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.invalidNameAgentJson().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.isEmpty(), "Should skip invalid agent")
        verify { logger.error(match<String> { it.contains("Invalid agent name") }, any(), any()) }
    }

    @Test
    fun `should skip agent with empty commands array`() {
        // Create an agent directory with empty commands
        val agentDir = testAgentsDir.resolve("empty-commands")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.emptyCommandsAgentJson().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.isEmpty(), "Should skip agent with no commands")
        verify { logger.error(match<String> { it.contains("has no commands") }, any(), any()) }
    }

    @Test
    fun `should skip langgraph agent without dispatch enabled`() {
        val agentDir = testAgentsDir.resolve("lg-no-dispatch")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: lg-no-dispatch
            commands:
              - !<langgraph>
                module: my_graphs.flow
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["lg-no-dispatch"], "langgraph agent without dispatch.enabled must be skipped")
        verify { logger.error(match<String> { it.contains("dispatch.enabled is not true") }, any(), any()) }
    }

    @Test
    fun `should load langgraph agent with dispatch enabled`() {
        val agentDir = testAgentsDir.resolve("lg-dispatch")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: lg-dispatch
            dispatch:
              enabled: true
            commands:
              - !<langgraph>
                module: my_graphs.flow
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(agentsService.agents["lg-dispatch"], "langgraph agent with dispatch.enabled must load")
    }

    @Test
    fun `should skip agent with malformed inputSchema`() {
        val agentDir = testAgentsDir.resolve("bad-schema")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: bad-schema
            commands:
              - !<cmd>
                cmd: echo ok
                timeout: 60
            inputSchema:
              type: object
              properties: oops
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["bad-schema"], "agent with a malformed inputSchema must be skipped")
        verify { logger.error(match<String> { it.contains("malformed inputSchema") }, any(), any(), any()) }
    }

    @Test
    fun `should load agent with valid inputSchema`() {
        val agentDir = testAgentsDir.resolve("good-schema")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: good-schema
            commands:
              - !<cmd>
                cmd: echo ok
                timeout: 60
            inputSchema:
              type: object
              required: [user]
              properties:
                user:
                  type: string
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(agentsService.agents["good-schema"], "agent with a valid inputSchema must load")
    }

    @Test
    fun `should skip agent with an invalid input declaration`() {
        val agentDir = testAgentsDir.resolve("bad-decl")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: bad-decl
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: count
                type: NUMBER
                min: 1
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["bad-decl"], "agent with an invalid input declaration must be skipped")
        verify { logger.error(match<String> { it.contains("invalid input declaration") }, any(), any()) }
    }

    @Test
    fun `should load agent with well-formed typed inputs`() {
        val agentDir = testAgentsDir.resolve("good-decl")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: good-decl
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: count
                type: integer
                min: 1
                max: 100
                default: 10
                required: false
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(agentsService.agents["good-decl"], "agent with well-formed typed inputs must load")
    }

    @Test
    fun `agent using legacy param types logs one deprecation WARN`() {
        val agentDir = testAgentsDir.resolve("legacy-types")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: legacy-types
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: a
                type: ANY
                required: false
              - paramName: b
                required: false
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.containsKey("legacy-types"), "agent using legacy param types must still load")
        verify(exactly = 1) {
            logger.warn(match<String> { it.contains("deprecated") }, "legacy-types")
        }
    }

    @Test
    fun `agent using the queryParams alias logs a deprecation WARN`() {
        val agentDir = testAgentsDir.resolve("legacy-alias")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: legacy-alias
            commands:
              - !<cmd>
                cmd: "echo hi"
            queryParams:
              - paramName: n
                type: string
                required: false
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.containsKey("legacy-alias"), "agent using queryParams must still load")
        verify {
            logger.warn(match<String> { it.contains("queryParams") }, "legacy-alias")
        }
    }

    // ========== Run tags (tags.fromInputs / tags.runtime), ADR-0060 ==========

    @Test
    fun `deprecated telemetry traceTags still loads and logs a deprecation WARN`() {
        val agentDir = testAgentsDir.resolve("trace-legacy")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: trace-legacy
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: mr
                required: false
            telemetry:
              traceTags: [mr]
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        val loaded = agentsService.agents["trace-legacy"]
        assertNotNull(loaded, "agent using the deprecated telemetry.traceTags alias must still load")
        // The alias unions into the effective fromInputs set that drives run tagging.
        assertEquals(listOf("mr"), loaded.effectiveFromInputTags)
        verify(exactly = 1) {
            logger.warn(match<String> { it.contains("traceTags is deprecated") }, "trace-legacy")
        }
    }

    @Test
    fun `agent with a runtime tag key that also appears in fromInputs is skipped`() {
        val agentDir = testAgentsDir.resolve("tag-overlap")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: tag-overlap
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: mr
                required: false
            tags:
              fromInputs: [mr]
              runtime: [mr]
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["tag-overlap"], "runtime key overlapping fromInputs must skip the agent")
        verify { logger.error(match<String> { it.contains("must be disjoint") }, "tag-overlap", "mr") }
    }

    @Test
    fun `agent with a malformed runtime tag key is skipped`() {
        val agentDir = testAgentsDir.resolve("tag-badkey")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: tag-badkey
            commands:
              - !<cmd>
                cmd: "echo hi"
            tags:
              runtime: ["bad key"]
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["tag-badkey"], "a runtime key off the charset must skip the agent")
        verify { logger.error(match<String> { it.contains("is not") }, "tag-badkey", "bad key") }
    }

    @Test
    fun `agent with a valid disjoint tags block loads`() {
        val agentDir = testAgentsDir.resolve("tag-ok")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: tag-ok
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: mr
                required: false
            tags:
              fromInputs: [mr]
              runtime: [decision]
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        val loaded = agentsService.agents["tag-ok"]
        assertNotNull(loaded, "a well-formed, disjoint tags block must load")
        assertEquals(listOf("mr"), loaded.effectiveFromInputTags)
        assertEquals(listOf("decision"), loaded.tags.runtime)
    }

    @Test
    fun `should handle malformed JSON gracefully`() {
        // Create an agent directory with malformed JSON
        val agentDir = testAgentsDir.resolve("malformed")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.malformedAgentJson().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.isEmpty(), "Should handle malformed JSON")
        verify {
            logger.error(
                match<String> { it.contains("Error loading agent") },
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `should return empty map when agents directory is empty`() {
        // testAgentsDir is already created and empty in setup()
        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.isEmpty(), "Should return empty map for empty directory")
        verify { logger.info("Agents loaded: {}", 0) }
    }

    @Test
    fun `should reject agent name with spaces`() {
        val agentDir = testAgentsDir.resolve("space-name-test")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "test agent",
              "commands": [
                {"type": "cmd", "cmd": "echo hello", "timeout": 60}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(
            agentsService.agents.isEmpty(),
            "Agent with space in name should not load now that spaces are banned",
        )
        verify { logger.error(match<String> { it.contains("Invalid agent name") }, any(), any()) }
    }

    // ========== YAML Support Tests ==========

    @Test
    fun `should load agent from YAML file when JSON does not exist`() {
        // Use a dedicated directory for this test to avoid interference
        val yamlTestDir = Files.createTempDirectory("yaml-agents-test")
        try {
            // Create an agent directory with only agent.yaml
            val agentDir = yamlTestDir.resolve("yaml-agent")
            Files.createDirectories(agentDir)
            val yamlContent = AgentFixtures.validAgentYaml()
            val yamlFile = agentDir.resolve("agent.yaml")
            Files.write(yamlFile, yamlContent.toByteArray())

            // Verify file was actually created
            assertTrue(Files.exists(yamlFile), "YAML file should exist")
            assertTrue(Files.isDirectory(agentDir), "Agent directory should exist")

            // Verify file content
            val written = Files.readString(yamlFile)
            assertEquals(yamlContent, written, "YAML content should match")

            agentsService = AgentsService(yamlTestDir.toString(), logger)

            // Debug info
            val actualAgents = agentsService.agents
            if (actualAgents.isEmpty()) {
                val dirs = yamlTestDir.toFile().listFiles()
                throw AssertionError(
                    "YAML agent failed to load.\n" +
                        "Test dir: $yamlTestDir\n" +
                        "Subdirs: ${dirs?.map { it.name }}\n" +
                        "YAML file exists: ${Files.exists(yamlFile)}\n" +
                        "YAML content:\n$written",
                )
            }

            assertEquals(1, actualAgents.size, "Should load one agent from YAML")
            assertTrue(actualAgents.containsKey("test_agent_yaml"), "Should load YAML agent. Actual keys: ${actualAgents.keys}")
            verify { logger.info(match<String> { it.contains("Loaded agent") }, any(), any()) }
        } finally {
            yamlTestDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should prefer JSON over YAML when both exist`() {
        // Create an agent directory with both JSON and YAML files
        val agentDir = testAgentsDir.resolve("both-formats")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        Files.write(agentDir.resolve("agent.yaml"), AgentFixtures.validAgentYaml().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertEquals(1, agentsService.agents.size, "Should load one agent")
        assertTrue(agentsService.agents.containsKey("test_agent"), "Should load from JSON, not YAML")
    }

    @Test
    fun `should handle malformed YAML gracefully`() {
        // Create an agent directory with malformed YAML
        val agentDir = testAgentsDir.resolve("malformed-yaml")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.yaml"), AgentFixtures.malformedAgentYaml().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.isEmpty(), "Should handle malformed YAML")
        verify {
            logger.error(
                match<String> { it.contains("Error loading agent") },
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `should load agent description from JSON`() {
        val agentDir = testAgentsDir.resolve("agent-with-desc")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        val agent = agentsService.agents["test_agent"]
        assertEquals("A test agent for unit testing", agent?.description, "Should load description from JSON")
    }

    @Test
    fun `loaded agent env carries HMR_AGENT_DIR with the absolute source directory`() {
        val agentDir = testAgentsDir.resolve("dir-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        val agent = agentsService.agents["test_agent"]
        assertNotNull(agent, "agent should load")
        assertEquals(
            agentDir.toAbsolutePath().normalize().toString(),
            agent.env["HMR_AGENT_DIR"],
            "HMR_AGENT_DIR must point to the agent's own absolute config directory",
        )
    }

    @Test
    fun `should load agent description from YAML`() {
        val yamlTestDir = Files.createTempDirectory("yaml-desc-test")
        try {
            val agentDir = yamlTestDir.resolve("yaml-agent")
            Files.createDirectories(agentDir)
            Files.write(agentDir.resolve("agent.yaml"), AgentFixtures.validAgentYaml().toByteArray())

            agentsService = AgentsService(yamlTestDir.toString(), logger)

            val agent = agentsService.agents["test_agent_yaml"]
            assertEquals("A test agent for unit testing in YAML format", agent?.description, "Should load description from YAML")
        } finally {
            yamlTestDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should have null description when not specified in agent definition`() {
        val agentDir = testAgentsDir.resolve("no-desc-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "no_desc_agent",
              "commands": [
                {"type": "cmd", "cmd": "echo hello", "timeout": 60}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        val agent = agentsService.agents["no_desc_agent"]
        assertEquals(null, agent?.description, "Description should be null when not specified")
    }

    // ========== reload() Tests ==========

    @Test
    fun `reload returns added agents`() {
        agentsService = AgentsService(testAgentsPath, logger)
        assertTrue(agentsService.agents.isEmpty())

        val agentDir = testAgentsDir.resolve("new-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())

        val result = agentsService.reload()

        assertEquals(listOf("test_agent"), result.added)
        assertEquals(emptyList<String>(), result.updated)
        assertEquals(emptyList<String>(), result.removed)
        assertEquals(emptyList<String>(), result.errors)
        assertNotNull(agentsService.agents["test_agent"])
    }

    @Test
    fun `reload returns updated agents when definition changes`() {
        val agentDir = testAgentsDir.resolve("existing-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        agentsService = AgentsService(testAgentsPath, logger)
        assertEquals("A test agent for unit testing", agentsService.agents["test_agent"]?.description)

        val updatedJson =
            """
            {
              "name": "test_agent",
              "description": "Updated description",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), updatedJson.toByteArray())

        val result = agentsService.reload()

        assertEquals(emptyList<String>(), result.added)
        assertEquals(listOf("test_agent"), result.updated)
        assertEquals(emptyList<String>(), result.removed)
        assertEquals("Updated description", agentsService.agents["test_agent"]?.description)
    }

    // ========== Nested discovery (ADR-0068) ==========

    private fun jsonAgent(
        name: String,
        description: String = "nested",
    ) = """
        {
          "name": "$name",
          "description": "$description",
          "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}]
        }
        """.trimIndent()

    private fun writeJsonAgent(
        relative: String,
        name: String,
        description: String = "nested",
    ): Path {
        val dir = testAgentsDir.resolve(relative)
        Files.createDirectories(dir)
        Files.write(dir.resolve("agent.json"), jsonAgent(name, description).toByteArray())
        return dir
    }

    @Test
    fun `loads an agent nested inside a non-agent directory`() {
        writeJsonAgent("pipeline/nodes/analysis", "analysis")

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(agentsService.agents["analysis"], "a definition at depth 3 must load")
        assertEquals(
            testAgentsDir.resolve("pipeline/nodes/analysis").toAbsolutePath().normalize(),
            agentsService.getAgentDir("analysis")?.toAbsolutePath()?.normalize(),
        )
    }

    @Test
    fun `a symlinked agent json beside a real agent yaml is not read (CWE-59)`() {
        // The discovery gate and the definition read share one no-symlink rule, so
        // the real agent.yaml is loaded, never the link's target.
        val dir = testAgentsDir.resolve("linked")
        Files.createDirectories(dir)
        Files.write(dir.resolve("agent.yaml"), "name: real_yaml\ncommands:\n  - !<cmd>\n    cmd: echo hi\n".toByteArray())
        val outside = Files.createTempDirectory("agents-outside")
        try {
            Files.write(outside.resolve("agent.json"), jsonAgent("planted").toByteArray())
            try {
                Files.createSymbolicLink(dir.resolve("agent.json"), outside.resolve("agent.json"))
            } catch (_: UnsupportedOperationException) {
                assumeTrue(false, "symlinks not supported on this filesystem")
            } catch (_: java.io.IOException) {
                assumeTrue(false, "symlinks not supported on this filesystem")
            }

            agentsService = AgentsService(testAgentsPath, logger)

            assertNull(agentsService.agents["planted"], "the symlinked definition's target must not load")
            assertNotNull(agentsService.agents["real_yaml"], "the real definition beside it loads")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loads a child nested inside a parent agent directory`() {
        writeJsonAgent("web-pipeline", "web_pipeline")
        writeJsonAgent("web-pipeline/nodes/sources", "sources")

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(agentsService.agents["web_pipeline"])
        assertNotNull(agentsService.agents["sources"], "a child inside a parent agent must load too")
    }

    @Test
    fun `a broken parent does not stop its child loading`() {
        val parent = writeJsonAgent("broken-parent", "broken_parent")
        Files.write(parent.resolve("agent.json"), "not: [valid".toByteArray())
        writeJsonAgent("broken-parent/nodes/child", "child")

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["broken_parent"], "the broken parent is skipped")
        assertNotNull(agentsService.agents["child"], "its child loads independently")
        assertTrue(agentsService.reload().errors.isNotEmpty())
    }

    @Test
    fun `two nested directories with the same leaf name but different agent names both load`() {
        writeJsonAgent("one/analysis", "analysis_one")
        writeJsonAgent("two/analysis", "analysis_two")

        agentsService = AgentsService(testAgentsPath, logger)

        assertEquals(2, agentsService.agents.size, "leaf names are not identity: ${agentsService.agents.keys}")
        assertEquals(
            testAgentsDir.resolve("one/analysis").toAbsolutePath().normalize(),
            agentsService.getAgentDir("analysis_one")?.toAbsolutePath()?.normalize(),
        )
        assertEquals(
            testAgentsDir.resolve("two/analysis").toAbsolutePath().normalize(),
            agentsService.getAgentDir("analysis_two")?.toAbsolutePath()?.normalize(),
        )
    }

    @Test
    fun `the same agent name at two depths fails both claimants`() {
        writeJsonAgent("one/shared", "shared_name")
        writeJsonAgent("two/deeper/shared", "shared_name")

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.isEmpty(), "a collision still fails ALL claimants: ${agentsService.agents.keys}")
        val errors = agentsService.reload().errors
        assertTrue(
            errors.any { it.contains("shared_name") && it.contains("multiple directories") },
            "expected a collision error, got: $errors",
        )
    }

    @Test
    fun `moving an agent directory deeper reloads as updated not removed plus added`() {
        writeJsonAgent("flat-agent", "moving_agent")
        agentsService = AgentsService(testAgentsPath, logger)
        assertNotNull(agentsService.agents["moving_agent"])

        testAgentsDir.resolve("flat-agent").toFile().deleteRecursively()
        writeJsonAgent("pipeline/nodes/flat-agent", "moving_agent")

        val result = agentsService.reload()

        assertEquals(emptyList<String>(), result.added, "a move deeper is not an add")
        assertEquals(emptyList<String>(), result.removed, "a move deeper is not a remove")
        assertEquals(listOf("moving_agent"), result.updated)
        assertEquals(
            testAgentsDir.resolve("pipeline/nodes/flat-agent").toAbsolutePath().normalize(),
            agentsService.getAgentDir("moving_agent")?.toAbsolutePath()?.normalize(),
        )
    }

    @Test
    fun `HMR_AGENT_DIR points at the nested directory`() {
        writeJsonAgent("pipeline/nodes/analysis", "analysis")

        agentsService = AgentsService(testAgentsPath, logger)

        assertEquals(
            testAgentsDir
                .resolve("pipeline/nodes/analysis")
                .toAbsolutePath()
                .normalize()
                .toString(),
            agentsService.agents["analysis"]?.env?.get("HMR_AGENT_DIR"),
        )
    }

    @Test
    fun `hidden directories are not discovered`() {
        writeJsonAgent(".git/hooks", "hidden_agent")

        agentsService = AgentsService(testAgentsPath, logger)

        assertTrue(agentsService.agents.isEmpty(), "hidden directories hold tooling, not agents")
    }

    @Test
    fun `reload returns removed agents when directory is deleted`() {
        val agentDir = testAgentsDir.resolve("temp-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        agentsService = AgentsService(testAgentsPath, logger)
        assertNotNull(agentsService.agents["test_agent"])

        agentDir.toFile().deleteRecursively()

        val result = agentsService.reload()

        assertEquals(emptyList<String>(), result.added)
        assertEquals(emptyList<String>(), result.updated)
        assertEquals(listOf("test_agent"), result.removed)
        assertNull(agentsService.agents["test_agent"])
    }

    @Test
    fun `reload preserves old agent version when file becomes unparseable`() {
        val agentDir = testAgentsDir.resolve("parseable-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        agentsService = AgentsService(testAgentsPath, logger)
        val originalAgent = agentsService.agents["test_agent"]
        assertNotNull(originalAgent)

        Files.write(agentDir.resolve("agent.json"), AgentFixtures.malformedAgentJson().toByteArray())

        val result = agentsService.reload()

        assertEquals(emptyList<String>(), result.added)
        assertEquals(emptyList<String>(), result.updated)
        assertEquals(emptyList<String>(), result.removed)
        assertTrue(result.errors.isNotEmpty(), "Should report parse error")
        assertEquals(originalAgent, agentsService.agents["test_agent"], "Old version should be preserved")
    }

    @Test
    fun `construction logs the loaded agent count once (audit P2 #23)`() {
        val agentDir = testAgentsDir.resolve("a1")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        verify(exactly = 1) { logger.info(match<String> { it.contains("Agents loaded") }, any<Int>()) }
    }

    // ========== Reserved Header Validation Tests ==========

    @Test
    fun `agent with reserved header param name fails to load`() {
        val agentDir = testAgentsDir.resolve("reserved-header-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "reserved_header_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "Authorization", "src": "header"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["reserved_header_agent"],
            "Agent binding reserved header 'Authorization' should not load",
        )
        verify {
            logger.error(
                match<String> { it.contains("reserved") },
                "reserved_header_agent",
                "Authorization",
            )
        }
    }

    @Test
    fun `agent with X-Homer prefix header fails to load`() {
        val agentDir = testAgentsDir.resolve("xhomer-header-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "xhomer_header_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "X-Homer-Trace", "src": "header"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["xhomer_header_agent"],
            "Agent binding reserved 'X-Homer-' prefix header should not load",
        )
        verify {
            logger.error(
                match<String> { it.contains("reserved") },
                "xhomer_header_agent",
                "X-Homer-Trace",
            )
        }
    }

    @Test
    fun `agent with non-reserved header loads`() {
        val agentDir = testAgentsDir.resolve("trace-id-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "trace_id_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "X-Trace-Id", "envName": "TRACE_ID", "src": "header"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(
            agentsService.agents["trace_id_agent"],
            "Agent binding non-reserved header 'X-Trace-Id' should load",
        )
    }

    @Test
    fun `agent capturing a result into a reserved envName fails to load`() {
        val agentDir = testAgentsDir.resolve("reserved-result-env-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "reserved_result_env_agent",
              "commands": [
                {
                  "type": "cmd",
                  "cmd": "echo hello",
                  "timeout": 60,
                  "resultEnvParam": [{"src": "STD_OUT", "envName": "PATH"}]
                }
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["reserved_result_env_agent"],
            "Agent capturing a result into reserved 'PATH' should not load",
        )
    }

    @Test
    fun `agent capturing a result into a malformed envName fails to load`() {
        val agentDir = testAgentsDir.resolve("malformed-result-env-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "malformed_result_env_agent",
              "commands": [
                {
                  "type": "cmd",
                  "cmd": "echo hello",
                  "timeout": 60,
                  "resultEnvParam": [{"src": "STD_OUT", "envName": "BAD-NAME"}]
                }
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["malformed_result_env_agent"],
            "Agent capturing a result into a malformed envName should not load",
        )
    }

    @Test
    fun `agent capturing a result into a valid envName loads`() {
        val agentDir = testAgentsDir.resolve("valid-result-env-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "valid_result_env_agent",
              "commands": [
                {
                  "type": "cmd",
                  "cmd": "echo hello",
                  "timeout": 60,
                  "resultEnvParam": [{"src": "STD_OUT", "envName": "GREETING"}]
                }
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(
            agentsService.agents["valid_result_env_agent"],
            "Agent capturing a result into non-reserved 'GREETING' should load",
        )
    }

    @Test
    fun `agent with a malformed cron expression fails to load`() {
        val agentDir = testAgentsDir.resolve("bad-cron-expr-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "bad_cron_expr_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "cron": {"expression": "not a cron", "timezone": "UTC"}
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["bad_cron_expr_agent"],
            "Agent with a malformed cron expression should not load",
        )
    }

    @Test
    fun `agent with an invalid cron timezone fails to load`() {
        val agentDir = testAgentsDir.resolve("bad-cron-tz-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "bad_cron_tz_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "cron": {"expression": "0 9 * * *", "timezone": "Mars/Phobos"}
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["bad_cron_tz_agent"],
            "Agent with an invalid cron timezone should not load",
        )
    }

    @Test
    fun `agent with a valid cron loads`() {
        val agentDir = testAgentsDir.resolve("good-cron-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "good_cron_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "cron": {"expression": "0 9 * * *", "timezone": "UTC"}
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(
            agentsService.agents["good_cron_agent"],
            "Agent with a valid cron should load",
        )
    }

    @Test
    fun `legacy queryParams binding reserved header fails to load`() {
        val agentDir = testAgentsDir.resolve("legacy-reserved-header-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "legacy_reserved_header_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "queryParams": [
                {"paramName": "Authorization", "src": "header"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["legacy_reserved_header_agent"],
            "Legacy agent binding reserved header 'Authorization' via queryParams should not load",
        )
        verify {
            logger.error(
                match<String> { it.contains("reserved") },
                "legacy_reserved_header_agent",
                "Authorization",
            )
        }
    }

    @Test
    fun `two directories declaring the same agent name both fail to load`() {
        // Unordered directory iteration means last-writer-wins is nondeterministic;
        // a writable dir could shadow a trusted agent. A name collision must be a
        // loud, order-independent load error — neither wins.
        listOf("dir-alpha", "dir-beta").forEach { d ->
            val agentDir = testAgentsDir.resolve(d)
            Files.createDirectories(agentDir)
            Files.write(
                agentDir.resolve("agent.json"),
                """
                {
                  "name": "shared_name",
                  "commands": [{"type": "cmd", "cmd": "echo hi", "timeout": 60}]
                }
                """.trimIndent().toByteArray(),
            )
        }

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["shared_name"],
            "a duplicate agent.name must not silently shadow — neither directory's agent loads",
        )
        verify {
            logger.error(match<String> { it.contains("shared_name") && it.contains("multiple directories") })
        }
    }

    // ========== Reserved envName Validation Tests (audit P1 #20) ==========

    @Test
    fun `agent input targeting HMR_ prefix envName fails to load`() {
        val agentDir = testAgentsDir.resolve("hmr-envname-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "hmr_envname_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "agent", "envName": "HMR_AGENT_NAME"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["hmr_envname_agent"],
            "Agent binding caller input to 'HMR_AGENT_NAME' must not load",
        )
        verify {
            logger.error(
                match<String> { it.contains("reserved envName") },
                "hmr_envname_agent",
                "agent",
                "HMR_AGENT_NAME",
            )
        }
    }

    @Test
    fun `agent input targeting host-controlled envName like PATH fails to load`() {
        val agentDir = testAgentsDir.resolve("path-envname-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "path_envname_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "binpath", "envName": "PATH"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["path_envname_agent"],
            "Agent letting a caller override PATH must not load — that is RCE-as-a-feature",
        )
        verify {
            logger.error(
                match<String> { it.contains("reserved envName") },
                "path_envname_agent",
                "binpath",
                "PATH",
            )
        }
    }

    @Test
    fun `agent input targeting LD_PRELOAD style prefix envName fails to load`() {
        val agentDir = testAgentsDir.resolve("ldpreload-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "ldpreload_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "lib", "envName": "LD_PRELOAD"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["ldpreload_agent"],
            "LD_* prefix is reserved",
        )
    }

    @Test
    fun `agent input targeting BASH_ENV fails to load`() {
        val agentDir = testAgentsDir.resolve("bashenv-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "bashenv_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "startup", "envName": "BASH_ENV"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(
            agentsService.agents["bashenv_agent"],
            "BASH_ENV lets a caller run code before every `when` predicate — must not load",
        )
        verify {
            logger.error(
                match<String> { it.contains("reserved envName") },
                "bashenv_agent",
                "startup",
                "BASH_ENV",
            )
        }
    }

    @Test
    fun `agent input with neutral envName loads`() {
        val agentDir = testAgentsDir.resolve("neutral-envname-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "neutral_envname_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "id", "envName": "HMR_USER_ID"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        // HMR_USER_* falls under the reserved HMR_ prefix; ensure operators know.
        assertNull(
            agentsService.agents["neutral_envname_agent"],
            "HMR_ is the reserved Homer namespace; user values should use a non-HMR envName",
        )
    }

    @Test
    fun `agent input with plain envName like USER_ID loads`() {
        val agentDir = testAgentsDir.resolve("plain-envname-agent")
        Files.createDirectories(agentDir)
        val agentJson =
            """
            {
              "name": "plain_envname_agent",
              "commands": [{"type": "cmd", "cmd": "echo hello", "timeout": 60}],
              "inputs": [
                {"paramName": "id", "envName": "USER_ID"}
              ]
            }
            """.trimIndent()
        Files.write(agentDir.resolve("agent.json"), agentJson.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(
            agentsService.agents["plain_envname_agent"],
            "USER_ID (no reserved prefix or exact match) must load",
        )
    }

    // ========== envName Format & Duplicate Validation Tests (audit H3) ==========

    @Test
    fun `agent with envName not a valid env identifier is skipped at load`() {
        // inputs: [{paramName: "X-Trace-Id", src: header}]  -> default envName "X-Trace-Id" is invalid
        val service =
            loadAgentYaml(
                """
                name: bad-envname
                commands:
                  - !<cmd> { cmd: "echo hi" }
                inputs:
                  - paramName: X-Trace-Id
                    src: header
                """.trimIndent(),
            )
        assertNull(service.agents["bad-envname"])
    }

    @Test
    fun `agent with duplicate envName is skipped at load`() {
        val service =
            loadAgentYaml(
                """
                name: dup-envname
                commands:
                  - !<cmd> { cmd: "echo hi" }
                inputs:
                  - paramName: a
                    envName: SAME
                  - paramName: b
                    envName: SAME
                """.trimIndent(),
            )
        assertNull(service.agents["dup-envname"])
    }

    @Test
    fun `agent with duplicate src+paramName is skipped at load`() {
        val service =
            loadAgentYaml(
                """
                name: dup-param
                commands:
                  - !<cmd> { cmd: "echo hi" }
                inputs:
                  - paramName: id
                    envName: ID_A
                  - paramName: id
                    envName: ID_B
                """.trimIndent(),
            )
        assertNull(service.agents["dup-param"])
    }

    @Test
    fun `same paramName under different src is allowed`() {
        val service =
            loadAgentYaml(
                """
                name: same-param-diff-src
                commands:
                  - !<cmd> { cmd: "echo hi" }
                inputs:
                  - paramName: id
                    envName: QUERY_ID
                  - paramName: id
                    src: body
                    envName: BODY_ID
                """.trimIndent(),
            )
        assertNotNull(service.agents["same-param-diff-src"])
    }

    // ========== Cron + Required Inputs Validation Tests ==========

    @Test
    fun `cron agent with a required input that has no default loads with its schedule disabled`() {
        val service =
            loadAgentYaml(
                """
                name: cron-required
                cron: { expression: "0 0 * * *" }
                commands:
                  - !<cmd> { cmd: "echo hi" }
                inputs:
                  - paramName: id
                    envName: ID
                    required: true
                """.trimIndent(),
            )
        // The agent still loads: it stays runnable by hand with the input supplied.
        assertNotNull(service.agents["cron-required"])
        assertNull(
            service.agents["cron-required"]?.cron,
            "a schedule that can never fire must not be registered",
        )
        val errors = service.reload().errors
        assertTrue(
            errors.any {
                it.contains("cron-required") && it.contains("cron schedule disabled") && it.contains("'id'")
            },
            "Expected the disable reason to name the input; got: $errors",
        )
    }

    @Test
    fun `cron agent whose input has a default keeps its schedule`() {
        // A `default` covers a cron fire (and requires `required: false` — ParamValidator).
        val service =
            loadAgentYaml(
                """
                name: cron-default
                cron: { expression: "0 0 * * *" }
                commands:
                  - !<cmd> { cmd: "echo hi" }
                inputs:
                  - paramName: id
                    envName: ID
                    type: string
                    required: false
                    default: "42"
                """.trimIndent(),
            )
        val agent = service.agents["cron-default"]
        assertNotNull(agent)
        assertEquals(
            "0 0 * * *",
            agent.cron?.expression,
            "a default satisfies a cron fire, so the schedule must stay registered",
        )
        val errors = service.reload().errors
        assertTrue(errors.isEmpty(), "a defaulted input must not be reported; got: $errors")
    }

    @Test
    fun `cron agent with an inputSchema required field and no default has its schedule disabled`() {
        val service =
            loadAgentYaml(
                """
                name: cron-schema
                cron: { expression: "0 0 * * *" }
                commands:
                  - !<cmd> { cmd: "echo hi" }
                inputSchema:
                  type: object
                  required: [user]
                  properties:
                    user:
                      type: string
                """.trimIndent(),
            )
        assertNotNull(service.agents["cron-schema"])
        assertNull(service.agents["cron-schema"]?.cron)
        val errors = service.reload().errors
        assertTrue(
            errors.any {
                it.contains("cron-schema") && it.contains("cron schedule disabled") && it.contains("'user'")
            },
            "Expected the disable reason to name the schema field; got: $errors",
        )
    }

    // ========== Input-Steering Warning Tests ==========

    @Test
    fun `input-steered lock key logs a warning but loads`() {
        val service =
            loadAgentYaml(
                """
                name: steered
                lockStrategy: { key: "{{TENANT}}", onCollision: KILL_OLD_RUN_CURRENT }
                commands:
                  - !<cmd> { cmd: "echo hi" }
                inputs:
                  - paramName: tenant
                    envName: TENANT
                """.trimIndent(),
            )
        assertNotNull(service.agents["steered"])
        verify {
            logger.warn(
                match<String> { it.contains("caller-supplied inputs steer run identity") },
                "steered",
                match<String> { it.contains("TENANT") },
            )
        }
    }

    @Test
    fun `input-steered Claude option logs a warning but loads`() {
        val service =
            loadAgentYaml(
                """
                name: claude-steered
                commands:
                  - !<claude>
                    prompt: !<prompt> { prompt: "hi" }
                    options: { settings: "{{CFG}}" }
                inputs:
                  - paramName: cfg
                    envName: CFG
                """.trimIndent(),
            )
        assertNotNull(service.agents["claude-steered"])
        verify {
            logger.warn(
                match<String> { it.contains("caller-supplied inputs steer run identity") },
                "claude-steered",
                match<String> { it.contains("settings") && it.contains("CFG") },
            )
        }
    }

    // ========== Input-bound LLM executable (cmd) Tests ==========

    @Test
    fun `claude command whose cmd references an input fails to load`() {
        val service =
            loadAgentYaml(
                """
                name: claude-bin-input
                commands:
                  - !<claude>
                    label: ask
                    cmd: "{{BIN}}"
                    prompt: !<prompt> { prompt: "hi" }
                    options: {}
                inputs:
                  - paramName: bin
                    envName: BIN
                """.trimIndent(),
            )
        assertNull(service.agents["claude-bin-input"], "an input must not choose the claude executable")
        val errors = service.reload().errors
        assertTrue(
            errors.any { it.contains("claude-bin-input") && it.contains("'ask'") && it.contains("BIN") },
            "expected a load error naming the command and the input, got: $errors",
        )
    }

    @Test
    fun `codex command whose cmd references an input fails to load`() {
        val service =
            loadAgentYaml(
                """
                name: codex-bin-input
                commands:
                  - !<codex>
                    label: review
                    cmd: "/opt/{{TOOL}}/bin/codex"
                    prompt: !<prompt> { prompt: "hi" }
                inputs:
                  - paramName: tool
                    envName: TOOL
                """.trimIndent(),
            )
        assertNull(service.agents["codex-bin-input"], "an input must not choose the codex executable")
        val errors = service.reload().errors
        assertTrue(
            errors.any { it.contains("codex-bin-input") && it.contains("'review'") && it.contains("TOOL") },
            "expected a load error naming the command and the input, got: $errors",
        )
    }

    @Test
    fun `claude command whose cmd references a non-input env var loads`() {
        val service =
            loadAgentYaml(
                """
                name: claude-bin-env
                env:
                  CLAUDE_BIN: /usr/local/bin/claude
                commands:
                  - !<claude>
                    cmd: "{{CLAUDE_BIN}}"
                    prompt: !<prompt> { prompt: "{{TOPIC}}" }
                    options: {}
                inputs:
                  - paramName: topic
                    envName: TOPIC
                """.trimIndent(),
            )
        assertNotNull(service.agents["claude-bin-env"])
    }

    @Test
    fun `should mark writable=true for normally-writable agent directory`() {
        val agentDir = testAgentsDir.resolve("writable-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)

        val agent = agentsService.agents.values.single()
        assertTrue(agent.writable, "Default-permission agent dir should be writable")
    }

    @Test
    fun `should mark writable=false for read-only agent directory`() {
        assumeTrue(
            TestFixtures.posixPermissionsEnforced(),
            "POSIX read-only bits not enforced (root or non-POSIX FS); writable detection cannot be exercised",
        )
        val agentDir = testAgentsDir.resolve("readonly-agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())

        // chmod 555 -- read+execute, no write
        val perms =
            java.nio.file.attribute.PosixFilePermissions
                .fromString("r-xr-xr-x")
        Files.setPosixFilePermissions(agentDir, perms)

        try {
            agentsService = AgentsService(testAgentsPath, logger)
            val agent = agentsService.agents.values.single()
            assertFalse(agent.writable, "Read-only dir should yield writable=false")
        } finally {
            // Restore so teardown can clean up
            Files.setPosixFilePermissions(
                agentDir,
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwxr-xr-x"),
            )
        }
    }

    // ========== Helper Methods ==========

    private fun setupValidAgentsDirectory() {
        // Minimal setup for tests that don't test loading
        // testAgentsDir is already created in setup() - nothing else needed
    }

    /** Writes a single `agent.yaml` into a fresh dir under [testAgentsDir] and scans it. */
    private fun loadAgentYaml(yaml: String): AgentsService {
        val agentDir = testAgentsDir.resolve("agent")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.yaml"), yaml.toByteArray())
        return AgentsService(testAgentsPath, logger)
    }

    // ========== Config-File Size Cap Tests (audit P1 #14) ==========

    @Test
    fun `agent definition file over maxConfigFileBytes is rejected with error and not loaded`() {
        cleanTestDir()
        val agentDir = testAgentsDir.resolve("bloated")
        Files.createDirectory(agentDir)
        // 2 KiB payload — well-formed-ish, but big enough to exceed our 1 KiB cap.
        val pad = "x".repeat(2048)
        Files.writeString(
            agentDir.resolve("agent.yaml"),
            """
            name: bloated
            description: $pad
            commands:
              - !<cmd>
                cmd: ["echo", "hello"]
            """.trimIndent(),
        )

        agentsService =
            AgentsService(
                testAgentsPath,
                logger,
                maxConfigFileBytes = 1024L,
            )

        val result = agentsService.reload()

        assertTrue(
            "bloated" !in agentsService.agents,
            "Oversized agent definition should not be loaded",
        )
        assertTrue(
            result.errors.any { it.contains("bloated") && it.contains("cap is 1024") },
            "Expected error citing the agent and the cap; got: ${result.errors}",
        )
    }

    // ========== RunnerCmd Validation Tests ==========

    @Test
    fun `agent with runnerCmd is skipped when its own runner is not local`() {
        val yaml =
            """
            name: bad-runner
            runner: some-ssh
            commands:
              - !<runnerCmd>
                runner: local
                execute: echo hi
            """.trimIndent()
        val agentDir = testAgentsDir.resolve("bad-runner")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.yaml"), yaml.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger, runnerAliases = setOf("local", "some-ssh"))
        val result = agentsService.reload()

        assertTrue(result.errors.any { it.contains("runner: local") })
        assertNull(agentsService.agents["bad-runner"])
    }

    @Test
    fun `agent with runnerCmd referencing an unknown runner alias is skipped`() {
        val yaml =
            """
            name: bad-alias
            commands:
              - !<runnerCmd>
                runner: nope
                execute: echo hi
            """.trimIndent()
        val agentDir = testAgentsDir.resolve("bad-alias")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.yaml"), yaml.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger, runnerAliases = setOf("local", "build-box"))
        val result = agentsService.reload()

        assertTrue(result.errors.any { it.contains("nope") })
        assertNull(agentsService.agents["bad-alias"])
    }

    @Test
    fun `agent with an invalid runnerCmd sync path is skipped`() {
        val yaml =
            """
            name: bad-path
            commands:
              - !<runnerCmd>
                runner: local
                execute: echo hi
                syncToRemote:
                  - ../etc
                syncFromRemote:
                  - /etc/passwd
            """.trimIndent()
        val agentDir = testAgentsDir.resolve("bad-path")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.yaml"), yaml.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger)
        val result = agentsService.reload()

        assertTrue(result.errors.isNotEmpty())
        assertNull(agentsService.agents["bad-path"])
    }

    @Test
    fun `agent with a valid runnerCmd loads`() {
        val yaml =
            """
            name: good-runnercmd
            commands:
              - !<runnerCmd>
                runner: build-box
                execute: echo hi
                syncToRemote:
                  - src/
            """.trimIndent()
        val agentDir = testAgentsDir.resolve("good-runnercmd")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.yaml"), yaml.toByteArray())

        agentsService = AgentsService(testAgentsPath, logger, runnerAliases = setOf("local", "build-box"))
        val result = agentsService.reload()

        assertNotNull(agentsService.agents["good-runnercmd"])
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `agent definition file at maxConfigFileBytes still loads`() {
        cleanTestDir()
        val agentDir = testAgentsDir.resolve("threshold")
        Files.createDirectory(agentDir)
        Files.writeString(
            agentDir.resolve("agent.yaml"),
            """
            name: threshold
            commands:
              - !<cmd>
                cmd: ["echo", "ok"]
            """.trimIndent(),
        )

        // Cap chosen so the small file fits comfortably.
        agentsService =
            AgentsService(
                testAgentsPath,
                logger,
                maxConfigFileBytes = 4096L,
            )

        agentsService.reload()

        assertTrue(
            "threshold" in agentsService.agents,
            "Agent under the cap should load",
        )
    }

    // ========== Telemetry redaction seeding ==========

    @Test
    fun `agent env keys sourced from @SYSENV are recorded so telemetry redacts them`() {
        val agentDir = testAgentsDir.resolve("sysenv-agent")
        Files.createDirectories(agentDir)
        Files.write(
            agentDir.resolve("agent.yaml"),
            """
            name: sysenv-agent
            env:
              REPO_TOKEN: "@SYSENV:PATH"
              API_URL: "https://example.test"
            commands:
              - !<cmd>
                cmd: echo hi
            """.trimIndent().toByteArray(),
        )

        agentsService = AgentsService(testAgentsPath, logger)

        val agent = assertNotNull(agentsService.agents["sysenv-agent"])
        // Both the env key and the host variable it referenced: the key covers this
        // whole-value case, and `PATH` is what covers a composite like
        // `"Bearer @SYSENV:PATH"`, where the resolved value is not the bare secret.
        assertEquals(setOf("REPO_TOKEN", "PATH"), agent.sysEnvBackedEnvNames)
        // The recorded key must resolve against the RESOLVED env, or the redactor
        // would look up a value that is no longer there.
        assertEquals(System.getenv("PATH"), agent.env["REPO_TOKEN"])
    }

    // ========== telemetry.traceTags declaration validation (ADR-0050) ==========

    private fun writeTelemetryAgent(
        dir: String,
        body: String,
    ) {
        val agentDir = testAgentsDir.resolve(dir)
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("agent.yaml"), body.trimIndent().toByteArray())
    }

    @Test
    @Suppress("DEPRECATION") // asserts the legacy traceTags alias itself
    fun `traceTags naming a declared input loads`() {
        writeTelemetryAgent(
            "tt-ok",
            """
            name: tt-ok
            telemetry:
              traceTags: [mr]
            inputs:
              - paramName: mr
                envName: MR_IID
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger)

        val agent = assertNotNull(agentsService.agents["tt-ok"])
        assertEquals(listOf("mr"), agent.telemetry.traceTags)
    }

    @Test
    fun `traceTags naming no declared input fails the agent at load`() {
        // The cost of naming inputs indirectly: a rename must fail loudly here rather
        // than silently stop tagging, which is the whole argument for validating it.
        writeTelemetryAgent(
            "tt-unknown",
            """
            name: tt-unknown
            telemetry:
              traceTags: [merge_request]
            inputs:
              - paramName: mr
                envName: MR_IID
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["tt-unknown"])
        // The deprecated traceTags alias unions into effectiveFromInputTags, so the unified
        // load error reports the offending entry as a tags.fromInputs problem. See ADR-0060.
        verify { logger.error(match<String> { it.contains("tags.fromInputs") }, any(), any(), any()) }
    }

    @Test
    fun `traceTags naming a secret input fails the agent at load`() {
        // A trace tag leaves the process in the clear; tagging a declared secret with
        // it would undo the redaction the author asked for.
        writeTelemetryAgent(
            "tt-secret",
            """
            name: tt-secret
            telemetry:
              traceTags: [token]
            inputs:
              - paramName: token
                envName: API_TOKEN
                secret: true
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["tt-secret"])
    }

    @Test
    fun `traceTags naming an input whose paramName contains a colon fails at load`() {
        // The tag is emitted as `paramName:value`, so a colon in the name makes it
        // ambiguous to read back. paramName is otherwise unconstrained (headers).
        writeTelemetryAgent(
            "tt-colon",
            """
            name: tt-colon
            telemetry:
              traceTags: ["a:b"]
            inputs:
              - paramName: "a:b"
                envName: AB
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["tt-colon"])
    }

    @Test
    fun `denial_of_service - fromInputs naming an input whose paramName is off the key charset fails at load`() {
        // The paramName becomes the stored tag key; a space is outside ^[A-Za-z0-9._-]$,
        // so the tag could never be matched by a `?tag=` query (CWE-400).
        writeTelemetryAgent(
            "ft-space",
            """
            name: ft-space
            tags:
              fromInputs: ["my input"]
            inputs:
              - paramName: "my input"
                envName: MY_INPUT
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["ft-space"])
    }

    @Test
    fun `denial_of_service - fromInputs naming an input whose paramName exceeds 64 characters fails at load`() {
        val longName = "p".repeat(65)
        writeTelemetryAgent(
            "ft-long",
            """
            name: ft-long
            tags:
              fromInputs: ["$longName"]
            inputs:
              - paramName: "$longName"
                envName: LONG_INPUT
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNull(agentsService.agents["ft-long"])
    }

    @Test
    fun `fromInputs naming a header-shaped paramName at the key bound loads`() {
        val name = "X-" + "a".repeat(62)
        writeTelemetryAgent(
            "ft-bound",
            """
            name: ft-bound
            tags:
              fromInputs: ["$name"]
            inputs:
              - paramName: "$name"
                envName: BOUND_INPUT
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger)

        assertNotNull(agentsService.agents["ft-bound"])
    }

    // ========== Runner alias / runnerTags load-time checks (ADR-0066) ==========

    @Test
    fun `agent with an unknown runner alias is skipped`() {
        writeTelemetryAgent(
            "bad-runner",
            """
            name: bad-runner
            runner: nowhere
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger, runnerAliases = setOf("local", "k8s"))
        val result = agentsService.reload()

        assertNull(agentsService.agents["bad-runner"])
        assertTrue(result.errors.any { it.contains("unknown runner 'nowhere'") })
    }

    @Test
    fun `agent with runnerTags and a runnerCmd is skipped`() {
        writeTelemetryAgent(
            "tagged-runnercmd",
            """
            name: tagged-runnercmd
            runnerTags: [linux]
            commands:
              - !<runnerCmd>
                runner: local
                execute: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger)
        val result = agentsService.reload()

        assertNull(agentsService.agents["tagged-runnercmd"])
        assertTrue(result.errors.any { it.contains("agents with runnerCmd must run on runner: local") })
    }

    @Test
    fun `agent with runnerTags nobody serves loads with a warning`() {
        writeTelemetryAgent(
            "gpu-job",
            """
            name: gpu-job
            runnerTags: [linux, gpu]
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger, runnerTagSets = { listOf(setOf("linux")) })
        agentsService.reload()

        assertNotNull(agentsService.agents["gpu-job"])
        verify { logger.warn(match<String> { it.contains("requests runner tags") }, any(), any(), any()) }
    }

    @Test
    fun `agent with runnerTags somebody serves loads silently`() {
        writeTelemetryAgent(
            "linux-job",
            """
            name: linux-job
            runnerTags: [linux]
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        agentsService = AgentsService(testAgentsPath, logger, runnerTagSets = { listOf(setOf("linux", "gpu")) })
        agentsService.reload()

        assertNotNull(agentsService.agents["linux-job"])
        verify(exactly = 0) { logger.warn(match<String> { it.contains("requests runner tags") }, any(), any(), any()) }
    }

    @Test
    fun `runnerTags must all be served by one runner, not spread across several`() {
        writeTelemetryAgent(
            "split-job",
            """
            name: split-job
            runnerTags: [linux, gpu]
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        // Each tag is served by some runner, but no single runner serves both.
        agentsService = AgentsService(testAgentsPath, logger, runnerTagSets = { listOf(setOf("linux"), setOf("gpu")) })
        agentsService.reload()

        assertNotNull(agentsService.agents["split-job"])
        verify { logger.warn(match<String> { it.contains("requests runner tags") }, any(), any(), any()) }
    }

    @Test
    fun `runnerTags agent still loads when the tag-set provider throws`() {
        writeTelemetryAgent(
            "boom-job",
            """
            name: boom-job
            runnerTags: [linux, gpu]
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        // The check is warn-only (ADR-0066 deviation (9)): a provider that throws
        // must degrade to a warning, never skip the agent.
        agentsService = AgentsService(testAgentsPath, logger, runnerTagSets = { error("boom") })
        agentsService.reload()

        assertNotNull(agentsService.agents["boom-job"])
        verify {
            logger.warn(match<String> { it.contains("Could not read the configured runner tag sets") }, any(), any(), any())
        }
        verify(exactly = 0) {
            logger.error(match<String> { it.contains("Error loading agent from") }, any(), any(), any())
        }
    }

    @Test
    fun `reload keeps the agent when the tag-set provider throws on a later scan`() {
        writeTelemetryAgent(
            "late-boom",
            """
            name: late-boom
            runnerTags: [linux, gpu]
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        // The provider is resolved lazily, so it can throw at any scan, not just
        // construction — and reload() is the path the console's reload mutation and
        // AgentFilesService drive. A regression that makes reload() treat a provider
        // throw as fatal for the diff would drop the agent here while construction
        // still succeeded.
        agentsService = AgentsService(testAgentsPath, logger, runnerTagSets = { error("boom") })
        assertNotNull(agentsService.agents["late-boom"])
        val result = agentsService.reload()

        assertNotNull(agentsService.agents["late-boom"])
        assertTrue(result.errors.isEmpty(), "reload must not report an error for a warn-only check, got: ${result.errors}")
        assertTrue(result.removed.isEmpty(), "the agent must not be diffed as removed, got: ${result.removed}")
        verify {
            logger.warn(match<String> { it.contains("Could not read the configured runner tag sets") }, any(), any(), any())
        }
        verify(exactly = 0) {
            logger.error(match<String> { it.contains("Error loading agent from") }, any(), any(), any())
        }
    }

    @Test
    fun `a provider that recovers re-enables the unmatched-tags warning on the next scan`() {
        writeTelemetryAgent(
            "flaky-provider",
            """
            name: flaky-provider
            runnerTags: [linux, gpu]
            commands:
              - !<cmd>
                cmd: echo hi
            """,
        )

        var throwOnScan = true
        agentsService =
            AgentsService(testAgentsPath, logger, runnerTagSets = { if (throwOnScan) error("boom") else listOf(setOf("linux")) })

        // First scan: the provider throws, so the unmatched-tags warning cannot be
        // evaluated and is skipped.
        assertNotNull(agentsService.agents["flaky-provider"])
        verify(exactly = 0) {
            logger.warn(match<String> { it.contains("requests runner tags") }, any(), any(), any())
        }

        throwOnScan = false
        agentsService.reload()

        // The throw is not memoized: the next scan reads the tag sets again and
        // reports the agent it cannot serve.
        assertNotNull(agentsService.agents["flaky-provider"])
        verify { logger.warn(match<String> { it.contains("requests runner tags") }, any(), any(), any()) }
    }
}
