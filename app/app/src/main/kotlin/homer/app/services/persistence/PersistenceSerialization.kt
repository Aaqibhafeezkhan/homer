package homer.app.services.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Single [Json] instance used to (de)serialize the JSON-text columns of the
 * persistence layer (process history/executions/runner state/input params,
 * question options). `encodeDefaults` so optional fields round-trip; lenient
 * unknown-key handling so an older row written before a field was added still
 * loads after an upgrade.
 */
internal val columnJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

/** A JSON-array column of strings (question options, secret key names, retained-artifact globs). */
internal val stringListSerializer = ListSerializer(String.serializer())

/** A JSON-object column of string pairs (run and dispatch params). */
internal val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())

/** A set of names (secret input keys, secret param names) as its JSON-array column text. */
internal fun encodeKeys(value: Set<String>): String = columnJson.encodeToString(stringListSerializer, value.toList())

internal fun decodeKeys(text: String): Set<String> = columnJson.decodeFromString(stringListSerializer, text).toSet()
