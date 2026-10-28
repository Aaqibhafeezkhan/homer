package homer.app.models

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val yaml = Yaml.default

    @Test
    @Suppress("DEPRECATION") // asserts the legacy alias itself
    fun `legacy queryParams YAML deserializes and effectiveInputs equals queryParams`() {
        val src =
            """
            name: legacy
            commands:
              - !<cmd>
                cmd: "echo hi"
            queryParams:
              - paramName: id
                envName: ID
            """.trimIndent()
        val agent = yaml.decodeFromString(Agent.serializer(), src)
        assertNull(agent.inputs)
        assertNotNull(agent.queryParams)
        assertEquals(agent.queryParams, agent.effectiveInputs)
    }

    @Test
    fun `claude escalate YAML deserializes and defaults to no tiers`() {
        val src =
            """
            name: cascade
            commands:
              - !<claude>
                prompt: !<prompt>
                  prompt: "hi"
                options:
                  model: cheap
                escalate:
                  - model: strong
                    options: []
                    when:
                      any:
                        - '[ "${'$'}HMR_LLM_OUTCOME" != success ]'
              - !<claude>
                prompt: !<prompt>
                  prompt: "hi"
                options: {}
            """.trimIndent()
        val commands = yaml.decodeFromString(Agent.serializer(), src).commands.filterIsInstance<Command.Claude>()
        val tier = commands[0].escalate.single()
        assertEquals("strong", tier.model)
        assertEquals(emptyList(), tier.options)
        assertEquals(When.Operator.ANY, tier.whenClause.operator)
        assertEquals(emptyList(), commands[1].escalate)
    }

    @Test
    @Suppress("DEPRECATION") // asserts the legacy alias stays unset
    fun `canonical inputs YAML deserializes`() {
        val src =
            """
            name: modern
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: id
                envName: ID
                src: query
            """.trimIndent()
        val agent = yaml.decodeFromString(Agent.serializer(), src)
        assertNull(agent.queryParams)
        assertNotNull(agent.inputs)
        assertEquals(ParamSource.QUERY, agent.inputs.single().src)
    }

    @Test
    fun `body and header sources deserialize`() {
        val src =
            """
            name: mixed
            commands:
              - !<cmd>
                cmd: "echo hi"
            inputs:
              - paramName: user
                src: body
              - paramName: X-Trace-Id
                envName: TRACE
                src: header
            """.trimIndent()
        val agent = yaml.decodeFromString(Agent.serializer(), src)
        val srcs = agent.inputs!!.map { it.src }
        assertEquals(listOf(ParamSource.BODY, ParamSource.HEADER), srcs)
    }

    @Test
    fun `JSON deserialization accepts both names`() {
        val legacy = """{"name":"l","commands":[{"type":"cmd","cmd":"x"}],"queryParams":[{"paramName":"id"}]}"""
        val modern = """{"name":"m","commands":[{"type":"cmd","cmd":"x"}],"inputs":[{"paramName":"id","src":"body"}]}"""
        val a = json.decodeFromString(Agent.serializer(), legacy)
        val b = json.decodeFromString(Agent.serializer(), modern)
        assertEquals("id", a.effectiveInputs!!.single().paramName)
        assertEquals(ParamSource.BODY, b.effectiveInputs!!.single().src)
    }

    @Test
    fun `inputSchema round-trips from YAML into a JsonObject`() {
        val src =
            """
            name: webhook-agent
            commands:
              - !<cmd>
                cmd: [echo, ok]
                timeout: 60
            inputSchema:
              type: object
              required: [user]
              properties:
                user:
                  type: string
            """.trimIndent()

        val agent = Yaml.default.decodeFromString(Agent.serializer(), src)

        val schema: JsonObject? = agent.inputSchema
        assertNotNull(schema, "inputSchema should deserialize into a JsonObject")
        assertEquals("object", (schema["type"] as JsonPrimitive).content)
    }

    @Test
    fun `inputSchema defaults to null when absent`() {
        val src =
            """
            name: plain-agent
            commands:
              - !<cmd>
                cmd: [echo, ok]
                timeout: 60
            """.trimIndent()

        val agent = Yaml.default.decodeFromString(Agent.serializer(), src)
        assertNull(agent.inputSchema)
    }

    @Test
    fun `group round-trips through YAML and JSON`() {
        val src =
            """
            name: grouped
            group: Platform
            commands:
              - !<cmd>
                cmd: [echo, ok]
            """.trimIndent()

        val fromYaml = yaml.decodeFromString(Agent.serializer(), src)
        assertEquals("Platform", fromYaml.group)
        val fromJson = json.decodeFromString(Agent.serializer(), json.encodeToString(Agent.serializer(), fromYaml))
        assertEquals("Platform", fromJson.group)
    }

    @Test
    fun `group defaults to null when absent`() {
        val src =
            """
            name: ungrouped
            commands:
              - !<cmd>
                cmd: [echo, ok]
            """.trimIndent()

        assertNull(yaml.decodeFromString(Agent.serializer(), src).group)
    }
}
