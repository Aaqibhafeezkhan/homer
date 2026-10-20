package homer.app.models

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ParamTypeTest {
    @Test
    fun `wireName is the spelling an agent file uses for every type`() {
        ParamType.entries.forEach { type ->
            assertEquals("\"${type.wireName}\"", Json.encodeToString(ParamType.serializer(), type), "wireName of $type")
        }
    }

    @Test
    fun `typed types are lowercase on the wire while legacy ones keep their enum name`() {
        val typed = listOf(ParamType.STR, ParamType.INT, ParamType.NUM, ParamType.BOOL)
        val legacy = listOf(ParamType.STRING, ParamType.NUMBER, ParamType.ANY)

        assertEquals(listOf("string", "integer", "number", "boolean"), typed.map { it.wireName })
        assertEquals(listOf("STRING", "NUMBER", "ANY"), legacy.map { it.wireName })
    }
}
