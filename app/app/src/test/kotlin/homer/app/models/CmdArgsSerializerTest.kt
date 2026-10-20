package homer.app.models

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class CmdArgsSerializerTest {
    @Serializable
    private data class Wrapper(
        @kotlinx.serialization.Serializable(with = CmdArgsSerializer::class)
        val cmd: List<String>,
    )

    private val yaml = Yaml.default
    private val json = Json

    @Test
    fun `yaml scalar is tokenized`() {
        val decoded = yaml.decodeFromString(Wrapper.serializer(), "cmd: echo hello world")
        assertEquals(listOf("echo", "hello", "world"), decoded.cmd)
    }

    @Test
    fun `yaml scalar preserves quoted arguments with spaces`() {
        val decoded =
            yaml.decodeFromString(
                Wrapper.serializer(),
                """cmd: echo "hello world"""",
            )
        assertEquals(listOf("echo", "hello world"), decoded.cmd)
    }

    @Test
    fun `yaml inline list is passed through`() {
        val decoded = yaml.decodeFromString(Wrapper.serializer(), """cmd: [echo, "hello world"]""")
        assertEquals(listOf("echo", "hello world"), decoded.cmd)
    }

    @Test
    fun `yaml block list is passed through`() {
        val decoded =
            yaml.decodeFromString(
                Wrapper.serializer(),
                """
                cmd:
                  - echo
                  - hello world
                """.trimIndent(),
            )
        assertEquals(listOf("echo", "hello world"), decoded.cmd)
    }

    @Test
    fun `json string is tokenized`() {
        val decoded = json.decodeFromString(Wrapper.serializer(), """{"cmd": "echo hello world"}""")
        assertEquals(listOf("echo", "hello", "world"), decoded.cmd)
    }

    @Test
    fun `json array is passed through`() {
        val decoded =
            json.decodeFromString(
                Wrapper.serializer(),
                """{"cmd": ["echo", "hello world"]}""",
            )
        assertEquals(listOf("echo", "hello world"), decoded.cmd)
    }

    @Test
    fun `json non-string array element throws`() {
        assertThrows<SerializationException> {
            json.decodeFromString(Wrapper.serializer(), """{"cmd": ["echo", 42]}""")
        }
    }

    @Test
    fun `json non-string scalar is tokenized from its literal`() {
        val decoded = json.decodeFromString(Wrapper.serializer(), """{"cmd": 42}""")
        assertEquals(listOf("42"), decoded.cmd)
    }

    @Test
    fun `json object throws`() {
        val error =
            assertThrows<SerializationException> {
                json.decodeFromString(Wrapper.serializer(), """{"cmd": {"run": "echo"}}""")
            }
        assertEquals("cmd must be a string or array of strings", error.message)
    }

    @Test
    fun `json non-string array element names the array rule`() {
        val error =
            assertThrows<SerializationException> {
                json.decodeFromString(Wrapper.serializer(), """{"cmd": ["echo", ["nested"]]}""")
            }
        assertEquals("cmd array elements must be strings", error.message)
    }

    @Test
    fun `yaml non-scalar list element throws`() {
        val error =
            assertThrows<SerializationException> {
                yaml.decodeFromString(Wrapper.serializer(), "cmd: [echo, [nested]]")
            }
        assertEquals("cmd list elements must be strings", error.message)
    }

    @Test
    fun `yaml map throws`() {
        val error =
            assertThrows<SerializationException> {
                yaml.decodeFromString(Wrapper.serializer(), "cmd: {run: echo}")
            }
        assertEquals("cmd must be a string or list of strings", error.message)
    }

    @Test
    fun `serialize writes the list form`() {
        assertEquals(
            """{"cmd":["echo","hello world"]}""",
            json.encodeToString(Wrapper.serializer(), Wrapper(listOf("echo", "hello world"))),
        )
    }
}
