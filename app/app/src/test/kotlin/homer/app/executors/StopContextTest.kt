package homer.app.executors

import homer.app.EnvConstants
import homer.app.models.StopReason
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `HMR_STOP_*` environment of a graceful stop's steps (ADR-0105).
 *
 * Every value here is attacker-influenced — the label is author- and template-written, the
 * session id is parsed out of a CLI's own stdout — so the tests that matter are the ones that
 * assert what a crafted value does *not* become (root §12 rule 8).
 */
class StopContextTest {
    /** The one session-id shape a stop step may ever see. */
    private val sessionIdShape = Regex("^[A-Za-z0-9-]{1,128}$")

    private fun env(
        reason: StopReason = StopReason.STEP_TIMEOUT,
        stepIndex: Int = 3,
        stepLabel: String = "Run integration tests",
        hardDeadlineEpochSec: Long? = null,
        sessionId: String? = null,
    ) = StopContext.env(reason, stepIndex, stepLabel, hardDeadlineEpochSec, sessionId)

    @Test
    fun `a step timeout names the reason, the step and its label`() {
        val env = env()

        assertEquals("step_timeout", env[EnvConstants.STOP_REASON])
        assertEquals("3", env[EnvConstants.STOP_STEP_INDEX])
        assertEquals("Run integration tests", env[EnvConstants.STOP_STEP_LABEL])
    }

    @Test
    fun `the hard deadline is exported for a run deadline only`() {
        val runDeadline = env(reason = StopReason.RUN_DEADLINE, hardDeadlineEpochSec = 1_763_000_000L)
        assertEquals("1763000000", runDeadline[EnvConstants.STOP_DEADLINE])

        // A step timeout has no run deadline to name, and the value is absent — not empty.
        val stepTimeout = env(reason = StopReason.STEP_TIMEOUT, hardDeadlineEpochSec = 1_763_000_000L)
        assertNull(stepTimeout[EnvConstants.STOP_DEADLINE])
    }

    @Test
    fun `a run deadline with no known hard deadline exports no deadline`() {
        assertNull(env(reason = StopReason.RUN_DEADLINE, hardDeadlineEpochSec = null)[EnvConstants.STOP_DEADLINE])
    }

    @Test
    fun `a session id passing the charset is exported`() {
        val good = env(sessionId = "3f2b6c1e-8a4d-4c1b-9e2f-7d5a0b6c9e11")

        assertEquals("3f2b6c1e-8a4d-4c1b-9e2f-7d5a0b6c9e11", good[EnvConstants.STOP_SESSION_ID])
        // Absent rather than empty, so a stop step's `[ -n … ]` test means what it looks like.
        assertNull(env(sessionId = null)[EnvConstants.STOP_SESSION_ID])
    }

    @Test
    fun `a crafted session id never reaches the stop steps`() {
        val crafted =
            listOf(
                "",
                " ",
                "id with spaces",
                "id\nHMR_STOP_REASON=run_deadline",
                "id;rm -rf /",
                "../../etc/passwd",
                "id\$(whoami)",
                "id\u0000",
                "ünicode",
                "a".repeat(129),
            )

        for (raw in crafted) {
            assertNull(StopContext.sanitizeSessionId(raw), "expected '$raw' to be refused")
        }
    }

    @Test
    fun `a label keeps its text but loses the characters that would forge an env line`() {
        assertEquals("Save report", StopContext.sanitizeLabel("Save report"))
        assertEquals("ab", StopContext.sanitizeLabel("a\nb"))
        assertEquals("ab", StopContext.sanitizeLabel("a\u0000b"))
        assertEquals("ab", StopContext.sanitizeLabel("a\r\u0007b"))
        assertEquals("Run integrationtests", StopContext.sanitizeLabel("Run integration\ttests"))
    }

    @Test
    fun `an over-long label is cut, and the variable survives`() {
        val long = "x".repeat(StopContext.MAX_LABEL_CHARS + 500)
        val cut = StopContext.sanitizeLabel(long)

        assertEquals(StopContext.MAX_LABEL_CHARS, cut.length)
        assertEquals(cut, env(stepLabel = long)[EnvConstants.STOP_STEP_LABEL])
    }

    @Test
    fun `the exported label never carries a control character, whatever it was built from`() =
        runTest {
            val labels = Arb.string(0..200, Codepoint.ascii())

            checkAll(labels) { raw ->
                val exported = StopContext.sanitizeLabel(raw)

                assertTrue(exported.none { it.isISOControl() }, "control character survived: '$exported'")
                assertTrue(exported.length <= StopContext.MAX_LABEL_CHARS, "label was not cut: ${exported.length}")
            }
        }

    @Test
    fun `the exported session id always matches the one shape the stop steps may see`() =
        runTest {
            val ids = Arb.string(0..140, Codepoint.ascii())

            checkAll(ids) { raw ->
                val exported = StopContext.sanitizeSessionId(raw)

                if (exported != null) {
                    assertEquals(raw, exported, "an accepted id was repaired instead of passed through")
                    assertTrue(sessionIdShape.matches(exported), "accepted an id outside the shape: '$exported'")
                }
            }
        }

    @Test
    fun `the map holds nothing but the five documented keys`() =
        runTest {
            val labels = Arb.string(0..50, Codepoint.ascii())
            val indices = Arb.int(-1_000..1_000_000)

            checkAll(labels, indices) { label, index ->
                val exported = StopContext.env(StopReason.RUN_DEADLINE, index, label, 1L, "abc")

                assertEquals(
                    setOf(
                        EnvConstants.STOP_REASON,
                        EnvConstants.STOP_STEP_INDEX,
                        EnvConstants.STOP_STEP_LABEL,
                        EnvConstants.STOP_DEADLINE,
                        EnvConstants.STOP_SESSION_ID,
                    ),
                    exported.keys,
                )
                assertEquals(index.toString(), exported[EnvConstants.STOP_STEP_INDEX])
            }
        }
}
