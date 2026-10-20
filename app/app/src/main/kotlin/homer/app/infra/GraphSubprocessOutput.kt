package homer.app.infra

import java.io.InputStream

/** Most stdout Homer reads from an `inspect`/`state` subprocess. See ADR-0073. */
internal const val MAX_GRAPH_STDOUT_BYTES: Long = 2L * 1024 * 1024

/** Most error text Homer surfaces from such a subprocess. */
internal const val MAX_GRAPH_STDERR_BYTES: Int = 16 * 1024

/**
 * The whole stream as lenient UTF-8, or null when it holds more than [maxBytes].
 * Never reads past the bound: on a live pipe a lingering grandchild may still be
 * writing. The caller owns (and closes) [input].
 */
internal fun readWhole(
    input: InputStream,
    maxBytes: Long,
): String? {
    require(maxBytes in 0 until Int.MAX_VALUE) { "maxBytes out of range: $maxBytes" }
    val limit = maxBytes.toInt()
    val bytes = input.readNBytes(limit + 1)
    return if (bytes.size > limit) null else String(bytes, Charsets.UTF_8)
}

/**
 * At most [maxBytes] from the stream, marked when cut. The caller owns (and
 * closes) [input], and must drain any remainder so the writer is not left
 * blocked on a full pipe.
 */
internal fun readHead(
    input: InputStream,
    maxBytes: Int,
): String {
    val bytes = input.readNBytes(maxBytes + 1)
    val text = String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8)
    return if (bytes.size > maxBytes) "$text… (truncated)" else text
}
