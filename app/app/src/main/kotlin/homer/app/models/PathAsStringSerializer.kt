package homer.app.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Serializes [Path] as its string form so `ExecutionInfo.stdOut`/`stdErr` (the
 * only `Path` fields persisted) survive the JSON-column round-trip. `Path` is a
 * runtime handle with no kotlinx serializer of its own; the durable form is the
 * absolute/relative path string exactly as produced at execution time.
 */
internal object PathAsStringSerializer : KSerializer<Path> {
    override val descriptor: SerialDescriptor =
        // The pre-move name, kept on purpose: a descriptor name is never written to a column, and
        // keeping it leaves nothing to reason about for rows written before the move.
        PrimitiveSerialDescriptor("homer.app.services.persistence.Path", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Path,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Path = Path(decoder.decodeString())
}
