package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.services.AgentFilesService.FileOpResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AgentDefinitionWriteGateTest {
    private val gate = AgentDefinitionWriteGate(TestFixtures.mockLogger())

    @Test
    fun `a write landing on a non-definition file passes without a grant check`() {
        val denial = gate.denial("notes.txt", "notes.txt", "anything") { error("no grant check expected") }
        assertNull(denial)
    }

    @Test
    fun `a definition declaring an ungranted name is denied as not-found`() {
        val denial = gate.denial("nodes/agent.yaml", "alias.txt", AgentFixtures.validAgentYaml()) { false }
        assertEquals(FileOpResult.NotFound("File not found: alias.txt"), denial)
    }

    @Test
    fun `a granted valid definition passes`() {
        val granted = mutableListOf<String>()
        val denial =
            gate.denial("nodes/agent.yaml", "nodes/agent.yaml", AgentFixtures.validAgentYaml()) {
                granted.add(it)
                true
            }
        assertNull(denial)
        assertEquals(listOf("test_agent_yaml"), granted)
    }

    @Test
    fun `an unparsable nested definition is denied as not-found, fail closed`() {
        val denial = gate.denial("nodes/agent.yaml", "nodes/agent.yaml", "not: valid agent yaml") { true }
        assertIs<FileOpResult.NotFound>(denial)
    }

    @Test
    fun `an unparsable root definition reports its parse errors`() {
        val denial = gate.denial("agent.yaml", "agent.yaml", "not: valid agent yaml") { true }
        assertIs<FileOpResult.SchemaInvalid>(denial)
    }

    @Test
    fun `a granted json definition without commands or with a bad name is schema-invalid`() {
        val denial = gate.denial("agent.json", "agent.json", """{"name": "bad name", "commands": []}""") { true }
        assertEquals(
            FileOpResult.SchemaInvalid(
                listOf(
                    "Invalid agent name 'bad name': only letters, digits, '-', '_' allowed",
                    "Agent must have at least one command",
                ),
            ),
            denial,
        )
    }
}
