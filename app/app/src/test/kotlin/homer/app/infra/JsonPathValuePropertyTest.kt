package homer.app.infra

import com.jayway.jsonpath.JsonPath
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Property test for the shared JsonPath→text conversion: whatever shape an agent's stdout holds,
 * a selected object or array must come back as text that parses to the very same JSON. Example
 * tests mirror the shapes we thought of; this finds the ones we did not (root §6).
 */
class JsonPathValuePropertyTest {
    @Test
    fun `a selected object or array serializes back to the same JSON`() =
        runTest {
            checkAll(objectArb(depth = 3)) { document ->
                val json = encode(document)
                assertRoundTrip(document, json, "$")
                document.entries.firstOrNull { it.value.isStructured() }?.let { (key, value) ->
                    assertRoundTrip(value, json, "$.$key")
                }
            }
            checkAll(arrayArb(depth = 3)) { document ->
                val json = encode(document)
                assertRoundTrip(document, json, "$")
                val index = document.indexOfFirst { it.isStructured() }
                if (index >= 0) assertRoundTrip(document[index], json, "$[$index]")
            }
        }

    private fun assertRoundTrip(
        expected: JsonElement,
        document: String,
        path: String,
    ) {
        val text = jsonPathText(JsonPath.read<Any>(document, path))
        assertNotNull(text, "no text for document: $document, path: $path")
        assertEquals(expected, Json.parseToJsonElement(text), "document: $document, path: $path")
    }

    private fun encode(document: JsonElement): String = Json.encodeToString(JsonElement.serializer(), document)

    private fun JsonElement.isStructured(): Boolean = this is JsonObject || this is JsonArray

    private val keyArb: Arb<String> = Arb.list(Arb.of('a', 'b', 'c', 'd', 'e'), 1..4).map { it.joinToString("") }

    // The alphabet carries a quote, a backslash and a newline so the serialized text is checked
    // against escaping as well as content.
    private val stringArb: Arb<String> =
        Arb.list(Arb.of('a', 'b', 'c', '"', '\\', '\n', ' '), 0..6).map { it.joinToString("") }

    private fun valueArb(depth: Int): Arb<JsonElement> {
        val scalars: Arb<JsonElement> =
            Arb.choice(
                stringArb.map { JsonPrimitive(it) },
                Arb.int(0, 1000).map { JsonPrimitive(it) },
                Arb.boolean().map { JsonPrimitive(it) },
                Arb.constant(JsonNull),
            )
        if (depth == 0) return scalars
        return Arb.choice(scalars, objectArb(depth - 1), arrayArb(depth - 1))
    }

    private fun objectArb(depth: Int): Arb<JsonObject> =
        Arb.list(keyArb, 0..4).flatMap { keys ->
            Arb.list(valueArb(depth), keys.size..keys.size).map { values ->
                JsonObject(keys.zip(values).toMap())
            }
        }

    private fun arrayArb(depth: Int): Arb<JsonArray> = Arb.list(valueArb(depth), 0..4).map { JsonArray(it) }
}
