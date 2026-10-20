package homer.app.models

import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Accepts `when` as either:
 *   - a scalar string (shorthand for `{all: [<string>]}`), or
 *   - an object with exactly one of `all` / `any` keys, each holding a non-empty
 *     list of strings.
 *
 * Always serializes back to the structured form (`{all|any: [...]}`).
 *
 * Uses a CONTEXTUAL descriptor so kaml does not pre-validate the shape before
 * delegating here. Mirrors the pattern in `CmdArgsSerializer`.
 */
object WhenSerializer : KSerializer<When> {
    @Serializable
    private data class WhenStruct(
        val all: List<String>? = null,
        val any: List<String>? = null,
    )

    private val structDelegate = WhenStruct.serializer()

    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("homer.app.models.When", SerialKind.CONTEXTUAL)

    override fun serialize(
        encoder: Encoder,
        value: When,
    ) {
        val struct =
            when (value.operator) {
                When.Operator.ALL -> WhenStruct(all = value.expressions)
                When.Operator.ANY -> WhenStruct(any = value.expressions)
            }
        structDelegate.serialize(encoder, struct)
    }

    override fun deserialize(decoder: Decoder): When =
        when (decoder) {
            is JsonDecoder -> decodeFromJson(decoder)
            is YamlInput -> decodeFromYaml(decoder)
            else -> throw SerializationException("Unsupported decoder: ${decoder::class.simpleName}")
        }

    private fun decodeFromJson(decoder: JsonDecoder): When =
        when (val element = decoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (element.isString) {
                    When(When.Operator.ALL, listOf(element.content))
                } else {
                    throw SerializationException("when must be a string or an object with 'all' or 'any'")
                }
            }

            is JsonObject -> {
                jsonObjectToWhen(element)
            }

            else -> {
                throw SerializationException(
                    "when must be a string or an object with 'all' or 'any', got ${element::class.simpleName}",
                )
            }
        }

    private fun jsonObjectToWhen(obj: JsonObject): When {
        val hasAll = "all" in obj
        val hasAny = "any" in obj
        if (hasAll == hasAny) {
            throw SerializationException(
                "when object must have exactly one of 'all' or 'any' (got keys: ${obj.keys})",
            )
        }
        val key = if (hasAll) "all" else "any"
        val operator = if (hasAll) When.Operator.ALL else When.Operator.ANY
        val list =
            obj[key] as? JsonArray
                ?: throw SerializationException("when.$key must be an array of strings")
        val expressions =
            list.map {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    ?: throw SerializationException("when.$key elements must be strings")
            }
        return When(operator, expressions)
    }

    private fun decodeFromYaml(decoder: YamlInput): When =
        when (val node = decoder.node) {
            is YamlScalar -> {
                rejectTypedScalar(node.content)
                When(When.Operator.ALL, listOf(node.content))
            }

            is YamlMap -> {
                val keys =
                    node.entries.keys
                        .map { it.content }
                        .toSet()
                val hasAll = "all" in keys
                val hasAny = "any" in keys
                if (hasAll == hasAny) {
                    throw SerializationException(
                        "when object must have exactly one of 'all' or 'any' (got keys: $keys)",
                    )
                }
                val key = if (hasAll) "all" else "any"
                val operator = if (hasAll) When.Operator.ALL else When.Operator.ANY
                val listNode =
                    node.get<YamlList>(key)
                        ?: throw SerializationException("when.$key must be a list of strings")
                val expressions =
                    listNode.items.map {
                        (it as? YamlScalar)?.content
                            ?: throw SerializationException("when.$key elements must be strings")
                    }
                When(operator, expressions)
            }

            else -> {
                throw SerializationException(
                    "when must be a string or a map with 'all' or 'any'",
                )
            }
        }

    /**
     * kaml normalizes quoted and unquoted YAML scalars to the same
     * [YamlScalar.content] string, so we cannot tell `when: 42` from
     * `when: "42"` after parsing. Reject anything that looks like a typed
     * YAML primitive (bool/int/float) so the user gets an early, clear
     * error instead of a runtime bash-eval failure. A real bash predicate
     * is never a bare numeric or boolean literal — quote your expression
     * and it will contain spaces / brackets that defeat these checks.
     */
    private fun rejectTypedScalar(content: String) {
        val isBool = content in BOOL_LITERALS
        val isInt = content.toLongOrNull() != null
        val isFloat = content.toDoubleOrNull() != null
        if (isBool || isInt || isFloat) {
            throw SerializationException(
                "when must be a bash expression, got bare YAML " +
                    (
                        if (isBool) {
                            "boolean"
                        } else if (isInt) {
                            "integer"
                        } else {
                            "float"
                        }
                    ) +
                    " '$content'. Wrap the predicate in a shell test, e.g. `when: '[ \"\$X\" = \"$content\" ]'`.",
            )
        }
    }

    private val BOOL_LITERALS = setOf("true", "True", "TRUE", "false", "False", "FALSE")
}
