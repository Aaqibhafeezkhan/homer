package homer.app.models

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScalarOrStringListSerializerTest {
    private val serializers = listOf(CmdArgsSerializer, StringOrStringListSerializer)

    @Test
    fun `any list of strings round-trips through the list form of either serializer`() =
        runTest {
            checkAll(Arb.list(Arb.string(0..20), 0..8)) { values ->
                serializers.forEach { serializer ->
                    val encoded = Json.encodeToString(serializer, values)
                    assertEquals(values, Json.decodeFromString(serializer, encoded), "${serializer.descriptor.serialName}: $encoded")
                }
            }
        }

    @Test
    fun `each serializer keeps its own contextual descriptor name`() {
        assertEquals(
            listOf("homer.app.models.CmdArgs", "homer.app.models.StringOrStringList"),
            serializers.map { it.descriptor.serialName },
        )
        serializers.forEach { assertEquals(SerialKind.CONTEXTUAL, it.descriptor.kind) }
    }
}
