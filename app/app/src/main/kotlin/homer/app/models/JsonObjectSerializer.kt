package homer.app.models

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serializes/deserializes [JsonObject] from both JSON (via [JsonDecoder]) and
 * YAML (via [YamlInput]). Used for [Agent.inputSchema] so an agent author can
 * declare a JSON Schema inline in either a `.yaml` or `.json` agent file.
 *
 * Serialization (write) delegates to [JsonObject.serializer()], which only
 * works in a JSON context; that is fine because agent files are read-only at
 * runtime — we never re-serialize an Agent back to disk.
 */
object JsonObjectSerializer : KSerializer<JsonObject> {
    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("homer.app.models.JsonObjectSerializer", SerialKind.CONTEXTUAL)

    override fun serialize(
        encoder: Encoder,
        value: JsonObject,
    ) {
        JsonObject.serializer().serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): JsonObject =
        when (decoder) {
            is JsonDecoder -> {
                val element = decoder.decodeJsonElement()
                element as? JsonObject
                    ?: throw SerializationException("inputSchema must be a JSON object, got ${element::class.simpleName}")
            }

            is YamlInput -> {
                val node = decoder.node
                yamlNodeToJsonElement(node) as? JsonObject
                    ?: throw SerializationException("inputSchema must be a YAML mapping, got ${node::class.simpleName}")
            }

            else -> {
                throw SerializationException("Unsupported decoder: ${decoder::class.simpleName}")
            }
        }

    private fun yamlNodeToJsonElement(node: com.charleskorn.kaml.YamlNode): JsonElement =
        when (node) {
            is YamlNull -> {
                JsonNull
            }

            is YamlScalar -> {
                yamlScalarToJsonPrimitive(node)
            }

            is YamlMap -> {
                JsonObject(
                    node.entries
                        .mapKeys { (k, _) -> k.content }
                        .mapValues { (_, v) -> yamlNodeToJsonElement(v) },
                )
            }

            is YamlList -> {
                JsonArray(node.items.map { yamlNodeToJsonElement(it) })
            }

            else -> {
                throw SerializationException("Unsupported YAML node type: ${node::class.simpleName}")
            }
        }

    private fun yamlScalarToJsonPrimitive(node: YamlScalar): JsonPrimitive {
        val content = node.content
        // Try to preserve numeric and boolean types. YAML scalars lose their
        // original type tag, so we infer: booleans first, then long, then double,
        // then fall back to string. Limitation: a quoted YAML scalar that looks
        // like a bool/number (e.g. "true", "123") is indistinguishable from the
        // unquoted form here and is coerced to that type — acceptable because the
        // result feeds a JSON Schema where such values are interchangeable.
        if (content == "true" || content == "false") return JsonPrimitive(content.toBoolean())
        content.toLongOrNull()?.let { return JsonPrimitive(it) }
        content.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        return JsonPrimitive(content)
    }
}
