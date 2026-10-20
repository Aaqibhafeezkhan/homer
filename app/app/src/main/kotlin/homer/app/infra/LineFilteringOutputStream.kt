package homer.app.infra

import java.io.OutputStream

/**
 * Splits the bytes written to it into `\n`-terminated lines and forwards each line
 * to [target] only when [filter] keeps it. Kept bytes are forwarded verbatim; the
 * filter sees a UTF-8 decoded copy. An unterminated line longer than [maxLineBytes]
 * is committed as-is — the buffered prefix is written and the rest streams through
 * unfiltered until its newline — so the buffer stays bounded. A trailing
 * unterminated line is filtered and flushed on [close]. See ADR-0063.
 */
class LineFilteringOutputStream(
    private val target: OutputStream,
    private val filter: OutputLineFilter,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) : OutputStream() {
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var len = 0

    /** True while inside a line that overflowed [maxLineBytes]; cleared at its newline. */
    private var passthrough = false

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        n: Int,
    ) {
        var i = off
        val end = off + n
        while (i < end) {
            var nl = i
            while (nl < end && b[nl] != NEWLINE) nl++
            if (nl == end) {
                append(b, i, end - i)
                return
            }
            append(b, i, nl - i)
            endLine()
            i = nl + 1
        }
    }

    private fun append(
        b: ByteArray,
        off: Int,
        n: Int,
    ) {
        if (n == 0) return
        if (passthrough) {
            target.write(b, off, n)
            return
        }
        if (len + n > maxLineBytes) {
            target.write(buf, 0, len)
            len = 0
            target.write(b, off, n)
            passthrough = true
            return
        }
        if (len + n > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        }
        System.arraycopy(b, off, buf, len, n)
        len += n
    }

    private fun endLine() {
        if (passthrough) {
            target.write(NEWLINE.toInt())
            passthrough = false
            return
        }
        if (filter.keep(String(buf, 0, len, Charsets.UTF_8))) {
            target.write(buf, 0, len)
            target.write(NEWLINE.toInt())
        }
        len = 0
    }

    override fun flush() {
        target.flush()
    }

    override fun close() {
        try {
            if (len > 0) {
                if (filter.keep(String(buf, 0, len, Charsets.UTF_8))) {
                    target.write(buf, 0, len)
                }
                len = 0
            }
        } finally {
            target.close()
        }
    }

    companion object {
        const val DEFAULT_MAX_LINE_BYTES = 1_048_576
        private const val INITIAL_CAPACITY = 8_192
        private const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
