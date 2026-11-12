package homer.app.infra

import homer.app.EnvConstants
import homer.app.fixtures.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentScannerTest {
    private val root: Path = Files.createTempDirectory("agent-scanner-test")

    @AfterEach
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    private fun scanner(maxConfigFileBytes: Long = 1024 * 1024): AgentScanner {
        val logger = TestFixtures.mockLogger()
        val inputChecks = AgentInputChecks(logger, ParamValidator())
        val validator =
            AgentDefinitionValidator(logger, setOf("local"), { emptyList() }, AgentInputResolver(), InputSchemaValidator(), inputChecks)
        return AgentScanner(root.toString(), logger, SysEnvResolver(), maxConfigFileBytes, validator, inputChecks)
    }

    private fun define(
        rel: String,
        yaml: String,
    ) {
        val dir = Files.createDirectories(root.resolve(rel))
        Files.writeString(dir.resolve("agent.yaml"), yaml)
    }

    private fun agentYaml(name: String) = "name: $name\ncommands:\n  - !<cmd>\n    cmd: [\"echo\"]\n"

    private fun hookedYaml(
        name: String,
        target: String,
    ) = agentYaml(name) + "hooks:\n  - FINISHED: $target\n"

    @Test
    fun `loads nested agents keyed by their relative directory and injects HMR_AGENT_DIR`() {
        define("parent", agentYaml("parent"))
        define("parent/nodes/child", agentYaml("child"))

        val scan = scanner().scan()

        assertEquals(mapOf("parent" to "parent", "child" to "parent/nodes/child"), scan.sourceDirs)
        assertEquals(setOf("parent", "parent/nodes/child"), scan.presentDirNames)
        assertEquals(
            root
                .resolve("parent/nodes/child")
                .toAbsolutePath()
                .normalize()
                .toString(),
            scan.parsed["child"]?.env?.get(EnvConstants.AGENT_DIR),
        )
    }

    @Test
    fun `a name declared by two directories fails every claimant`() {
        define("one", agentYaml("dup"))
        define("two", agentYaml("dup"))

        val scan = scanner().scan()

        assertTrue(scan.parsed.isEmpty())
        assertEquals(setOf("one", "two"), scan.presentDirNames)
        assertTrue(scan.errors.all { "declared by multiple directories" in it })
    }

    @Test
    fun `a definition over the size cap is skipped with an error`() {
        define("big", agentYaml("big"))

        val scan = scanner(maxConfigFileBytes = 8).scan()

        assertTrue(scan.parsed.isEmpty())
        assertEquals(1, scan.errors.size)
    }

    @Test
    fun `a missing search path scans as empty`() {
        root.toFile().deleteRecursively()
        assertEquals(AgentScan(emptyMap(), emptyMap(), emptySet(), emptyList()), scanner().scan())
    }

    @Test
    fun `an agent whose hook names an unknown agent is skipped and the rest still load`() {
        define("declarer", hookedYaml("declarer", "ghost"))
        define("plain", agentYaml("plain"))

        val scan = scanner().scan()

        assertEquals(setOf("plain"), scan.parsed.keys)
        // The definition is skipped, not the directory: its files are still on disk.
        assertEquals(setOf("declarer", "plain"), scan.presentDirNames)
        assertTrue(
            scan.errors.any { "Agent 'declarer': hook target 'ghost' is not a loaded agent" in it },
            scan.errors.toString(),
        )
    }

    @Test
    fun `a hook cycle rejects every agent on it`() {
        define("a", hookedYaml("a", "b"))
        define("b", hookedYaml("b", "a"))

        val scan = scanner().scan()

        assertTrue(scan.parsed.isEmpty())
        assertEquals(2, scan.errors.count { "hook cycle" in it }, scan.errors.toString())
    }

    @Test
    fun `a valid hook chain loads and keeps its target`() {
        define("first", hookedYaml("first", "second"))
        define("second", agentYaml("second"))

        val scan = scanner().scan()

        assertEquals(setOf("first", "second"), scan.parsed.keys)
        assertEquals(
            listOf("second"),
            scan.parsed
                .getValue("first")
                .hooks
                .map { it.agent },
        )
        assertEquals(emptyList(), scan.errors)
    }
}
