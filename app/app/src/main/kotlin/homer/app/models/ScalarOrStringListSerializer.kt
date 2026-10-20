package homer.app.models

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Base of the serializers that accept a field as either a scalar string (mapped by [fromScalar])
 * or a list of strings (passed through unchanged); always serialized as a list. [subject] names
 * the field and [elementPrefix] leads the element errors.
 *
 * Uses a CONTEXTUAL descriptor so kaml does not pre-validate scalar vs list before delegating to
 * this deserializer.
 */
abstract class ScalarOrStringListSerializer(
    serialName: String,
    private val subject: String,
    private val elementPrefix: String,
) : KSerializer<List<String>> {
    private val listDelegate = ListSerializer(String.serializer())

    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildSerialDescriptor(serialName, SerialKind.CONTEXTUAL)

    /** The list a scalar [content] stands for. */
    protected abstract fun fromScalar(content: String): List<String>

    /** Whether a JSON scalar is accepted; only a string by default. */
    protected open fun acceptsJsonScalar(element: JsonPrimitive): Boolean = element.isString

    override fun serialize(
        encoder: Encoder,
        value: List<String>,
    ) {
        listDelegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<String> =
        when (decoder) {
            is JsonDecoder -> {
                fromJson(decoder.decodeJsonElement())
            }

            is YamlInput -> {
                fromYaml(decoder.node)
            }

            else -> {
                listDelegate.deserialize(decoder)
            }
        }

    private fun fromJson(element: JsonElement): List<String> =
        when (element) {
            is JsonPrimitive -> {
                if (acceptsJsonScalar(element)) {
                    fromScalar(element.content)
                } else {
                    throw SerializationException("$subject must be a string or array of strings")
                }
            }

            is JsonArray -> {
                element.map {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        ?: throw SerializationException("${elementPrefix}array elements must be strings")
                }
            }

            else -> {
                throw SerializationException("$subject must be a string or array of strings")
            }
        }

    private fun fromYaml(node: YamlNode): List<String> =
        when (node) {
            is YamlScalar -> {
                fromScalar(node.content)
            }

            is YamlList -> {
                node.items.map {
                    (it as? YamlScalar)?.content
                        ?: throw SerializationException("${elementPrefix}list elements must be strings")
                }
            }

            else -> {
                throw SerializationException("$subject must be a string or list of strings")
            }
        }
}
