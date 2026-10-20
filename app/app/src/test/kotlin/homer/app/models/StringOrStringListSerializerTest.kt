package homer.app.models

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class StringOrStringListSerializerTest {
    @Serializable
    private data class Wrapper(
        @kotlinx.serialization.Serializable(with = StringOrStringListSerializer::class)
        val value: List<String>,
    )

    private val yaml = Yaml.default
    private val json = Json

    @Test
    fun `yaml scalar maps to a single-element list, not tokenized`() {
        val decoded = yaml.decodeFromString(Wrapper.serializer(), """value: /path with spaces/dir""")
        assertEquals(listOf("/path with spaces/dir"), decoded.value)
    }

    @Test
    fun `yaml block list is passed through`() {
        val decoded =
            yaml.decodeFromString(
                Wrapper.serializer(),
                """
                value:
                  - /a
                  - /b
                """.trimIndent(),
            )
        assertEquals(listOf("/a", "/b"), decoded.value)
    }

    @Test
    fun `json string maps to a single-element list`() {
        val decoded = json.decodeFromString(Wrapper.serializer(), """{"value": "/x/mcp.json"}""")
        assertEquals(listOf("/x/mcp.json"), decoded.value)
    }

    @Test
    fun `json array is passed through`() {
        val decoded = json.decodeFromString(Wrapper.serializer(), """{"value": ["/a", "/b"]}""")
        assertEquals(listOf("/a", "/b"), decoded.value)
    }

    @Test
    fun `json non-string array element throws`() {
        assertThrows<SerializationException> {
            json.decodeFromString(Wrapper.serializer(), """{"value": ["/a", 42]}""")
        }
    }

    @Test
    fun `json non-string scalar throws`() {
        val e =
            assertThrows<SerializationException> {
                json.decodeFromString(Wrapper.serializer(), """{"value": 42}""")
            }
        assertEquals("value must be a string or array of strings", e.message)
    }

    @Test
    fun `json object throws`() {
        val e =
            assertThrows<SerializationException> {
                json.decodeFromString(Wrapper.serializer(), """{"value": {"a": "b"}}""")
            }
        assertEquals("value must be a string or array of strings", e.message)
    }

    @Test
    fun `yaml non-scalar list element throws`() {
        val e =
            assertThrows<SerializationException> {
                yaml.decodeFromString(
                    Wrapper.serializer(),
                    """
                    value:
                      - /a
                      - [nested]
                    """.trimIndent(),
                )
            }
        assertEquals("list elements must be strings", e.message)
    }

    @Test
    fun `yaml map throws`() {
        val e =
            assertThrows<SerializationException> {
                yaml.decodeFromString(
                    Wrapper.serializer(),
                    """
                    value:
                      a: b
                    """.trimIndent(),
                )
            }
        assertEquals("value must be a string or list of strings", e.message)
    }

    @Test
    fun `serialize writes the list form`() {
        assertEquals("""{"value":["/a"]}""", json.encodeToString(Wrapper.serializer(), Wrapper(listOf("/a"))))
    }
}
