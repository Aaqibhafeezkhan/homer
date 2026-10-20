package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentDirs
import homer.app.services.AgentFilesService.FileOpResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentFilePathResolverTest {
    private lateinit var root: Path
    private lateinit var resolver: AgentFilePathResolver
    private lateinit var name: String

    @BeforeEach
    fun setup() {
        root = Files.createTempDirectory("agent-file-paths-test")
        val agentDir = Files.createDirectories(root.resolve("my_agent"))
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        val agentsService = AgentsService(root.toString(), TestFixtures.mockLogger())
        name = agentsService.agents.keys.first()
        resolver = AgentFilePathResolver(agentsService)
    }

    @AfterEach
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    private fun error(relPath: String) = assertIs<ResolveError>(resolver.resolveSafe(name, relPath)).result

    @Test
    fun `a contained path resolves to where it lands`() {
        val ok = assertIs<ResolveOk>(resolver.resolveSafe(name, "./docs/../notes.txt"))
        assertEquals("notes.txt", ok.landingRel)
        assertEquals(root.toRealPath().resolve("my_agent"), ok.agentDir)
    }

    @Test
    fun `blank, absolute and escaping paths are bad requests`() {
        assertEquals(FileOpResult.BadRequest("Empty path"), error(" "))
        assertEquals(FileOpResult.BadRequest("Path must be relative"), error("/etc/passwd"))
        assertEquals(FileOpResult.BadRequest("Path escapes the agent directory: ../x"), error("../x"))
    }

    @Test
    fun `an unknown agent is not found`() {
        assertEquals(FileOpResult.NotFound("Agent 'nope' not found"), (resolver.resolveSafe("nope", "a.txt") as ResolveError).result)
    }

    @Test
    fun `a path inside a nested agent is denied as not found (ADR-0047)`() {
        val nested = Files.createDirectories(root.resolve("my_agent/nodes/child"))
        Files.write(nested.resolve("agent.yaml"), "name: child\n".toByteArray())
        assertEquals(FileOpResult.NotFound("File not found: nodes/child/notes.txt"), error("nodes/child/notes.txt"))
    }

    @Test
    fun `a symlink alias into a nested agent is denied as not found (CWE-59)`() {
        val nested = Files.createDirectories(root.resolve("my_agent/nodes/child"))
        Files.write(nested.resolve("agent.yaml"), "name: child\n".toByteArray())
        Files.createSymbolicLink(root.resolve("my_agent/alias.txt"), nested.resolve("agent.yaml"))
        assertIs<FileOpResult.NotFound>(error("alias.txt"))
    }

    @Test
    fun `over-deep paths and unreachable definitions are bad requests`() {
        val deep = (1..AgentDirs.MAX_PATH_SEGMENTS + 1).joinToString("/") { "d$it" }
        assertIs<FileOpResult.BadRequest>(error(deep))
        assertIs<FileOpResult.BadRequest>(error(".hidden/agent.yaml"))
    }

    @Test
    fun `definition-name predicates match after normalization`() {
        assertTrue(isAgentDef("./agent.yaml"))
        assertTrue(isAgentDef("x/../agent.json"))
        assertFalse(isAgentDef("nodes/agent.yaml"))
        assertTrue(isDefFileName("nodes/agent.yaml"))
        assertFalse(isDefFileName("nodes/agent.yml"))
    }
}
