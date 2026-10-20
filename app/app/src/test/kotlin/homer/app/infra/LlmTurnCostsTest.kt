package homer.app.infra

import homer.app.models.LlmEvent
import homer.app.models.TurnStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LlmTurnCostsTest {
    private fun turnEnd(
        costUsd: Double?,
        status: TurnStatus = TurnStatus.Success,
    ) = LlmEvent.TurnEnd(status = status, costUsd = costUsd)

    @Test
    fun `a single turn end carries the resolved cost, else its own non-negative one`() {
        assertEquals(listOf(0.9), LlmTurnCosts.allocate(listOf(turnEnd(0.1)), 0.9))
        assertEquals(listOf<Double?>(null), LlmTurnCosts.allocate(listOf(turnEnd(-1.0)), null))
    }

    @Test
    fun `cumulative costs are split into increments scaled to the resolved charge`() {
        assertEquals(listOf(1.0, 3.0), LlmTurnCosts.allocate(listOf(turnEnd(0.25), turnEnd(1.0)), 4.0))
    }

    @Test
    fun `a failed turn end's share moves onto the next successful one`() {
        val shares = LlmTurnCosts.allocate(listOf(turnEnd(0.25, TurnStatus.Failed("boom")), turnEnd(1.0)), 4.0)
        assertEquals(listOf(null, 4.0), shares)
    }

    @Test
    fun `with no successful turn end the last one carries the whole charge`() {
        val shares = LlmTurnCosts.allocate(listOf(turnEnd(0.25, TurnStatus.Failed("a")), turnEnd(1.0, TurnStatus.Failed("b"))), 4.0)
        assertEquals(listOf(null, 4.0), shares)
    }
}
