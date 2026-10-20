package homer.app.routes

import homer.app.exceptions.ValidationException
import homer.app.runners.external.RunnerProtocol
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [outputChunkPosition], the query parse of `POST /api/v1/runner/sessions/{sid}/ops/{opId}/output`. */
class RunnerOutputChunkPositionTest {
    @Test
    fun `stdout or stderr with an integer seq is accepted`() {
        assertEquals(RunnerProtocol.STREAM_STDOUT to 0L, outputChunkPosition(RunnerProtocol.STREAM_STDOUT, "0"))
        assertEquals(RunnerProtocol.STREAM_STDERR to 42L, outputChunkPosition(RunnerProtocol.STREAM_STDERR, "42"))
    }

    @Test
    fun `an unknown or missing stream is refused before the seq is read`() {
        for (stream in listOf(null, "", "STDOUT", "both")) {
            val e = assertFailsWith<ValidationException>("$stream") { outputChunkPosition(stream, "x") }
            assertEquals("Validation error for 'stream': stream must be stdout or stderr", e.message)
        }
    }

    @Test
    fun `a missing or non-integer seq is refused`() {
        for (seq in listOf(null, "", "1.5", "one")) {
            val e = assertFailsWith<ValidationException>("$seq") { outputChunkPosition(RunnerProtocol.STREAM_STDOUT, seq) }
            assertEquals("Validation error for 'seq': seq must be an integer", e.message)
        }
    }

    @Test
    fun `seq is accepted exactly when it parses as a long`() =
        runTest {
            checkAll(Arb.long()) { n ->
                assertEquals(RunnerProtocol.STREAM_STDERR to n, outputChunkPosition(RunnerProtocol.STREAM_STDERR, n.toString()))
            }
            checkAll(Arb.string(0..8)) { seq ->
                val parsed = seq.toLongOrNull()
                if (parsed != null) {
                    assertEquals(RunnerProtocol.STREAM_STDOUT to parsed, outputChunkPosition(RunnerProtocol.STREAM_STDOUT, seq))
                } else {
                    assertFailsWith<ValidationException>(seq) { outputChunkPosition(RunnerProtocol.STREAM_STDOUT, seq) }
                }
            }
        }
}
