package homer.agentdebug.execution

/**
 * Tracks the read position of a single output file while it is being tailed,
 * and emits newly-appended lines exactly once.
 *
 * Single-owner by construction: a [TailReader] instance is created per
 * (file, prefix) pair, advanced only by its tail coroutine via [consume],
 * and read once by [flushRemaining] after that coroutine has been joined.
 * Because the handoff is ordered by `cancelAndJoin` there is no concurrent
 * access, so the position needs no synchronization.
 */
class TailReader(
    private val prefix: String,
) {
    private var position = 0

    /**
     * Print every complete line that appeared since the last call (never the
     * trailing partial line), then advance the position past those lines.
     */
    fun consume(content: String) {
        if (content.length <= position) return
        val newChunk = content.substring(position)
        // Split on '\n' only (not lines(), which also splits on the 2-char
        // "\r\n" and would desync the position accounting below). Drop the
        // last element: either an incomplete trailing line, or the empty
        // string after a trailing newline.
        val completeLines = newChunk.split('\n').dropLast(1)
        completeLines.forEach { line -> println("$prefix ${line.removeSuffix("\r")}") }
        // Track how far we've consumed (complete lines only)
        val consumed = completeLines.sumOf { it.length + 1 }
        position += consumed
    }

    /**
     * Print whatever remains past the consumed position (e.g. a final line not
     * terminated by a newline). Read-only with respect to [position].
     */
    fun flushRemaining(content: String) {
        if (content.length <= position) return
        val remaining = content.substring(position).trimEnd('\n').trimEnd('\r')
        if (remaining.isNotEmpty()) {
            remaining.split('\n').forEach { line -> println("$prefix ${line.removeSuffix("\r")}") }
        }
    }
}
