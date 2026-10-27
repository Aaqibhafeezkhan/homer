package homer.app.executors

import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.models.MAX_FAILURE_REASON_LENGTH
import homer.app.models.RunnerFilter
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PreStartFailureReasonTest {
    private fun claimTimeout(
        filter: RunnerFilter = RunnerFilter.Tags(setOf("linux")),
        reason: String = "no registered runner matches those tags",
        publicReason: String = reason,
    ) = RunnerClaimTimeoutException(60.seconds, filter, reason, publicReason)

    @Test
    fun `a SKIP_CURRENT lock skip names the collision and the run that holds the lock`() {
        val failure = PreStartFailureReason.of(AnotherProcessStillActive(42))!!

        assertTrue(failure.reason.contains("SKIP_CURRENT"))
        assertEquals(42, failure.holderRunId)
        // The holder travels as its own field, so the API can withhold it from a caller who
        // may not see that run (ADR-0058).
        assertTrue(!failure.reason.contains("42"))
    }

    @Test
    fun `a REJECT parallel-run cap names the agent and the configured limit`() {
        val failure = PreStartFailureReason.of(MaxParallelRunsExceededException("demo", 3))!!

        assertTrue(failure.reason.contains("maxParallelRuns: 3"))
        assertTrue(failure.reason.contains("'demo'"))
        assertTrue(failure.reason.contains("REJECT"))
        assertNull(failure.holderRunId)
    }

    @Test
    fun `a full parallel-run wait queue names the agent and its bound`() {
        val failure = PreStartFailureReason.of(MaxQueuedRunsExceededException("demo", 5))!!

        assertTrue(failure.reason.contains("maxQueuedRuns: 5"))
        assertTrue(failure.reason.contains("'demo'"))
    }

    @Test
    fun `a claim timeout names the wait and the filter without its alias`() {
        val failure = PreStartFailureReason.of(claimTimeout())!!

        assertTrue(failure.reason.contains("within 60 s"))
        assertTrue(failure.reason.contains("tags [linux]"))
    }

    @Test
    fun `a claim timeout on a pinned runner names no alias`() {
        // The stored reason is served to every caller who can see the run, and a runner alias
        // is admin-only on the status API (ADR-0077) — the exception's own message names it.
        val failure = PreStartFailureReason.of(claimTimeout(filter = RunnerFilter.Alias("k8s-dev")))!!

        assertTrue(failure.reason.contains("pinned runner filter"))
        assertTrue(!failure.reason.contains("k8s-dev"))
    }

    @Test
    fun `a full claim queue names the bound`() {
        val failure = PreStartFailureReason.of(ClaimQueueFullException(100))!!

        assertTrue(failure.reason.contains("maxPendingRunnerClaims: 100"))
        assertNull(failure.holderRunId)
    }

    @Test
    fun `a failure that happened while the run was running stores no reason`() {
        assertNull(PreStartFailureReason.of(IllegalStateException("boom")))
    }

    @Test
    fun `a reason carrying control characters is stored as one line`() {
        val failure = PreStartFailureReason.of(MaxParallelRunsExceededException("demo\nagent\tx", 2))!!

        assertTrue(!failure.reason.any { it.isISOControl() })
        assertTrue(failure.reason.contains("demoagentx"))
    }

    @Test
    fun `an over-long reason is cut to the cap`() {
        val failure = PreStartFailureReason.of(MaxParallelRunsExceededException("a".repeat(1000), 2))!!

        assertEquals(MAX_FAILURE_REASON_LENGTH, failure.reason.length)
        assertTrue(failure.reason.endsWith(" …"))
    }

    @Test
    fun `every mapped reason fits the cap and carries no control character`() =
        runTest {
            // The stored text reaches a console and a JSON field whatever an agent name or a
            // filter's tags hold, so the bound must hold for any input, not just the examples.
            val agentNames = Arb.string(0..600)
            val tags = Arb.string(1..64, Codepoint.alphanumeric())
            checkAll(agentNames, tags) { agentName, tag ->
                val exceptions =
                    listOf(
                        AnotherProcessStillActive(1),
                        MaxParallelRunsExceededException(agentName, 2),
                        MaxQueuedRunsExceededException(agentName, 2),
                        ClaimQueueFullException(2),
                        claimTimeout(
                            filter = RunnerFilter.Tags(setOf(tag)),
                            reason = agentName,
                            publicReason = agentName,
                        ),
                    )
                exceptions.forEach { e ->
                    val reason = PreStartFailureReason.of(e)!!.reason
                    assertTrue(reason.length <= MAX_FAILURE_REASON_LENGTH, "over cap (${reason.length}): $reason")
                    assertTrue(reason.none { it.isISOControl() }, "control character in: $reason")
                }
            }
        }

    @Test
    fun `an arbitrary exception maps to no reason`() =
        runTest {
            checkAll(Arb.int()) { code ->
                assertNull(PreStartFailureReason.of(RuntimeException("failed with $code")))
            }
        }
}
