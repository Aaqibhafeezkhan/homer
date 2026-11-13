package homer.app.models

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentHookTest {
    private val yaml = Yaml.default
    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeYaml(hooks: String): Agent =
        yaml.decodeFromString(
            Agent.serializer(),
            // Built by concatenation, not by interpolating into the indented block: the
            // interpolated lines would keep their own indentation and break the block's.
            """
            name: declarer
            commands:
              - !<cmd>
                cmd: "echo hi"
            hooks:
            """.trimIndent() + "\n" + hooks + "\n",
        )

    @Test
    fun `a one-key YAML entry decodes to its status and target`() {
        val agent = decodeYaml("  - FINISHED: notifier")

        assertEquals(listOf(AgentHook(ProcessStatus.FINISHED, "notifier")), agent.hooks)
    }

    @Test
    fun `several entries may name the same status and keep their order`() {
        val agent = decodeYaml("  - CREATED: audit\n  - CREATED: notifier")

        assertEquals(
            listOf(
                AgentHook(ProcessStatus.CREATED, "audit"),
                AgentHook(ProcessStatus.CREATED, "notifier"),
            ),
            agent.hooks,
        )
    }

    @Test
    fun `every run status is accepted as a hook key`() {
        for (status in ProcessStatus.entries) {
            val agent = decodeYaml("  - ${status.name}: notifier")
            assertEquals(status, agent.hooks.single().status, status.name)
        }
    }

    @Test
    fun `the JSON form decodes the same way`() {
        val agent =
            json.decodeFromString(
                Agent.serializer(),
                """
                {"name":"declarer","commands":[{"type":"cmd","cmd":["echo","hi"]}],"hooks":[{"FAILED":"notifier"}]}
                """.trimIndent(),
            )

        assertEquals(listOf(AgentHook(ProcessStatus.FAILED, "notifier")), agent.hooks)
    }

    @Test
    fun `a hook round-trips through YAML as its one-key form`() {
        val agent = decodeYaml("  - KILLED: notifier")

        val encoded = yaml.encodeToString(Agent.serializer(), agent)
        val redecoded = yaml.decodeFromString(Agent.serializer(), encoded)

        assertEquals(listOf(AgentHook(ProcessStatus.KILLED, "notifier")), redecoded.hooks)
    }

    @Test
    fun `a key that is not a run status is rejected and names the key`() {
        val ex = assertThrows<SerializationException> { decodeYaml("  - STARTED: notifier") }

        assertEquals("hook key 'STARTED' is not a run status", ex.message)
    }

    @Test
    fun `an entry with two keys is rejected`() {
        val ex = assertThrows<SerializationException> { decodeYaml("  - CREATED: a\n    FINISHED: b") }

        assertTrue(ex.message!!.contains("exactly one key"), ex.message)
    }

    @Test
    fun `an empty entry is rejected`() {
        val ex = assertThrows<SerializationException> { decodeYaml("  - {}") }

        assertTrue(ex.message!!.contains("exactly one key"), ex.message)
    }

    @Test
    fun `a target outside the agent-name character set is rejected without echoing the name into the status`() {
        // The value is an untrusted definition's string; the error text reaches the load log
        // and the definition-write response, so it names the offending value only as itself.
        val ex = assertThrows<IllegalArgumentException> { decodeYaml("  - CREATED: \"not an agent\"") }

        assertTrue(ex.message!!.contains("not a valid agent name"), ex.message)
    }

    @Test
    fun `a hook may not target the declaring agent`() {
        val ex = assertThrows<IllegalArgumentException> { decodeYaml("  - CREATED: declarer") }

        assertTrue(ex.message!!.contains("must not target the declaring agent"), ex.message)
    }

    @Test
    fun `more than the per-agent cap of hooks is rejected`() {
        val entries = (1..MAX_AGENT_HOOKS + 1).joinToString("\n") { "  - CREATED: notifier-$it" }

        val ex = assertThrows<IllegalArgumentException> { decodeYaml(entries) }

        assertTrue(ex.message!!.contains("at most $MAX_AGENT_HOOKS hooks"), ex.message)
    }

    @Test
    fun `exactly the per-agent cap of hooks is accepted`() {
        val entries = (1..MAX_AGENT_HOOKS).joinToString("\n") { "  - CREATED: notifier-$it" }

        assertEquals(MAX_AGENT_HOOKS, decodeYaml(entries).hooks.size)
    }

    @Test
    fun `an agent without hooks defaults to none`() {
        val agent =
            yaml.decodeFromString(
                Agent.serializer(),
                """
                name: plain
                commands:
                  - !<cmd>
                    cmd: "echo hi"
                """.trimIndent(),
            )

        assertEquals(emptyList(), agent.hooks)
    }
}
