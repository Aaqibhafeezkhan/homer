package homer.app.models

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhenSerializerTest {
    private val yaml = Yaml.default
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    @Test
    fun `yaml string shorthand decodes as ALL with one expression`() {
        val yamlText =
            """
            !<cmd>
            cmd: echo hi
            when: '[ -n "${'$'}REGION" ]'
            """.trimIndent()
        val cmd = yaml.decodeFromString(Command.serializer(), yamlText) as Command.Cmd
        val w = cmd.whenClause!!
        assertEquals(When.Operator.ALL, w.operator)
        assertEquals(listOf("[ -n \"\$REGION\" ]"), w.expressions)
    }

    @Test
    fun `yaml all decodes as ALL`() {
        val yamlText =
            """
            !<cmd>
            cmd: echo hi
            when:
              all:
                - '[ -n "${'$'}A" ]'
                - '[ -n "${'$'}B" ]'
            """.trimIndent()
        val cmd = yaml.decodeFromString(Command.serializer(), yamlText) as Command.Cmd
        val w = cmd.whenClause!!
        assertEquals(When.Operator.ALL, w.operator)
        assertEquals(listOf("[ -n \"\$A\" ]", "[ -n \"\$B\" ]"), w.expressions)
    }

    @Test
    fun `yaml any decodes as ANY`() {
        val yamlText =
            """
            !<cmd>
            cmd: echo hi
            when:
              any:
                - '[ "${'$'}X" = "1" ]'
                - '[ "${'$'}X" = "2" ]'
            """.trimIndent()
        val cmd = yaml.decodeFromString(Command.serializer(), yamlText) as Command.Cmd
        val w = cmd.whenClause!!
        assertEquals(When.Operator.ANY, w.operator)
        assertEquals(2, w.expressions.size)
    }

    @Test
    fun `json string shorthand`() {
        val dollar = "${'$'}"
        val jsonText = """{"type":"cmd","cmd":"echo hi","when":"[ -n \"${dollar}REGION\" ]"}"""
        val cmd = json.decodeFromString(Command.serializer(), jsonText) as Command.Cmd
        val w = cmd.whenClause!!
        assertEquals(When.Operator.ALL, w.operator)
        assertEquals(1, w.expressions.size)
        assertEquals("[ -n \"${dollar}REGION\" ]", w.expressions[0])
    }

    @Test
    fun `json all`() {
        val jsonText = """{"type":"cmd","cmd":"echo hi","when":{"all":["a","b"]}}"""
        val cmd = json.decodeFromString(Command.serializer(), jsonText) as Command.Cmd
        val w = cmd.whenClause!!
        assertEquals(When.Operator.ALL, w.operator)
        assertEquals(listOf("a", "b"), w.expressions)
    }

    @Test
    fun `json any`() {
        val jsonText = """{"type":"cmd","cmd":"echo hi","when":{"any":["a","b"]}}"""
        val cmd = json.decodeFromString(Command.serializer(), jsonText) as Command.Cmd
        val w = cmd.whenClause!!
        assertEquals(When.Operator.ANY, w.operator)
        assertEquals(listOf("a", "b"), w.expressions)
    }

    @Test
    fun `missing when is null`() {
        val cmd =
            json.decodeFromString(
                Command.serializer(),
                """{"type":"cmd","cmd":"echo hi"}""",
            ) as Command.Cmd
        assertNull(cmd.whenClause)
    }

    @Test
    fun `empty list throws`() {
        assertThrows<IllegalArgumentException> {
            json.decodeFromString(
                Command.serializer(),
                """{"type":"cmd","cmd":"echo hi","when":{"all":[]}}""",
            )
        }
    }

    @Test
    fun `blank expression throws`() {
        assertThrows<IllegalArgumentException> {
            json.decodeFromString(
                Command.serializer(),
                """{"type":"cmd","cmd":"echo hi","when":{"all":["  "]}}""",
            )
        }
    }

    @Test
    fun `both all and any throws`() {
        assertThrows<SerializationException> {
            json.decodeFromString(
                Command.serializer(),
                """{"type":"cmd","cmd":"echo hi","when":{"all":["a"],"any":["b"]}}""",
            )
        }
    }

    @Test
    fun `neither all nor any throws`() {
        assertThrows<SerializationException> {
            json.decodeFromString(
                Command.serializer(),
                """{"type":"cmd","cmd":"echo hi","when":{}}""",
            )
        }
    }

    @Test
    fun `round-trip ALL produces structured form`() {
        val cmd =
            Command.Cmd(
                cmd = listOf("echo", "hi"),
                whenClause = When(When.Operator.ALL, listOf("a", "b")),
            )
        val out = json.encodeToString(Command.serializer(), cmd)
        // structured form, not the shorthand string
        assert(out.contains("\"all\":[\"a\",\"b\"]")) { "Expected 'all' array in $out" }
        assert(!out.contains("\"any\"")) { "Should not contain 'any' in $out" }

        // and re-decoding yields equivalent value
        val decoded = json.decodeFromString(Command.serializer(), out) as Command.Cmd
        assertEquals(cmd.whenClause, decoded.whenClause)
    }

    @Test
    fun `round-trip ANY produces structured form`() {
        val cmd =
            Command.Cmd(
                cmd = listOf("echo", "hi"),
                whenClause = When(When.Operator.ANY, listOf("a")),
            )
        val out = json.encodeToString(Command.serializer(), cmd)
        assert(out.contains("\"any\":[\"a\"]")) { "Expected 'any' array in $out" }
        assert(!out.contains("\"all\"")) { "Should not contain 'all' in $out" }
    }

    @Test
    fun `yaml bare integer scalar throws`() {
        val yamlText =
            """
            !<cmd>
            cmd: echo hi
            when: 42
            """.trimIndent()
        assertThrows<SerializationException> {
            yaml.decodeFromString(Command.serializer(), yamlText)
        }
    }

    @Test
    fun `yaml bare boolean scalar throws`() {
        val yamlText =
            """
            !<cmd>
            cmd: echo hi
            when: true
            """.trimIndent()
        assertThrows<SerializationException> {
            yaml.decodeFromString(Command.serializer(), yamlText)
        }
    }

    @Test
    fun `yaml bare false scalar throws`() {
        val yamlText =
            """
            !<cmd>
            cmd: echo hi
            when: false
            """.trimIndent()
        assertThrows<SerializationException> {
            yaml.decodeFromString(Command.serializer(), yamlText)
        }
    }

    @Test
    fun `yaml bare float scalar throws`() {
        val yamlText =
            """
            !<cmd>
            cmd: echo hi
            when: 3.14
            """.trimIndent()
        assertThrows<SerializationException> {
            yaml.decodeFromString(Command.serializer(), yamlText)
        }
    }

    @Test
    fun `string shorthand round-trip emits structured all form`() {
        val yamlText =
            """
            !<cmd>
            cmd: echo hi
            when: '[ -n "${'$'}X" ]'
            """.trimIndent()
        val cmd = yaml.decodeFromString(Command.serializer(), yamlText) as Command.Cmd
        val jsonOut = json.encodeToString(Command.serializer(), cmd)
        assert(jsonOut.contains("\"all\":[")) { "Expected structured 'all' form in $jsonOut" }
    }
}
