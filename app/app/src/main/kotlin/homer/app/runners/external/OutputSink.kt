package homer.app.runners.external

import homer.app.exceptions.RunnerOutputSequenceException
import kotlinx.coroutines.CompletableDeferred
import java.io.OutputStream

/**
 * Ordered, capped writer for one `cmd` op's two output streams. Chunks are
 * appended in `seq` order per stream; a gap or repeat is refused (the instance
 * retries in order). A chunk that would take a stream past [maxBytesPerStream]
 * is refused whole and trips [capTripped], which the executor turns into the same
 * output-cap kill every other runner applies (`OutputCapExceededException`).
 */
class OutputSink(
    private val stdout: OutputStream,
    private val stderr: OutputStream,
    private val maxBytesPerStream: Long,
) : AutoCloseable {
    private val lock = Any()
    private val nextSeq = mutableMapOf(RunnerProtocol.STREAM_STDOUT to 0L, RunnerProtocol.STREAM_STDERR to 0L)
    private val written = mutableMapOf(RunnerProtocol.STREAM_STDOUT to 0L, RunnerProtocol.STREAM_STDERR to 0L)

    val capTripped = CompletableDeferred<Unit>()

    fun append(
        stream: String,
        seq: Long,
        bytes: ByteArray,
    ) {
        // Local bound (§12 rule 4): a chunk over the wire cap must not consume a seq or
        // reach the sequence bookkeeping below — trust the instance's own claim otherwise.
        require(bytes.size <= RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES) {
            "output chunk of ${bytes.size} bytes exceeds ${RunnerProtocol.MAX_OUTPUT_CHUNK_BYTES}"
        }
        val target =
            when (stream) {
                RunnerProtocol.STREAM_STDOUT -> stdout
                RunnerProtocol.STREAM_STDERR -> stderr
                else -> throw IllegalArgumentException("unknown output stream '$stream'")
            }
        synchronized(lock) {
            val expected = nextSeq.getValue(stream)
            if (seq != expected) throw RunnerOutputSequenceException(expected, seq)
            nextSeq[stream] = expected + 1
            if (capTripped.isCompleted) return
            val total = written.getValue(stream) + bytes.size
            // Refuse the whole chunk that would cross the cap, before it reaches the file:
            // Homer owns this write, so the bound is exact here rather than overshot by up
            // to one chunk the way a post-write check would leave it (§12 rule 4).
            if (total > maxBytesPerStream) {
                capTripped.complete(Unit)
                return
            }
            written[stream] = total
            target.write(bytes)
        }
    }

    fun bytesWritten(stream: String): Long = synchronized(lock) { written.getValue(stream) }

    /**
     * How many chunks of [stream] have arrived, i.e. the `seq` the next one must carry. A
     * chunk the cap refused still counts: it consumed its sequence number like any other.
     */
    fun received(stream: String): Long = synchronized(lock) { nextSeq.getValue(stream) }

    override fun close() {
        synchronized(lock) {
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
    }
}
