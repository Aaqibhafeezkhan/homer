package homer.app.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun `claudeDropSystemSubtypes defaults to thinking_tokens`() {
        assertEquals(listOf("thinking_tokens"), Server().claudeDropSystemSubtypes)
    }

    @Test
    fun `claudeDropSystemSubtypes accepts an empty list and plain identifiers`() {
        assertEquals(emptyList(), Server(claudeDropSystemSubtypes = emptyList()).claudeDropSystemSubtypes)
        assertEquals(
            listOf("thinking_tokens", "Other_2"),
            Server(claudeDropSystemSubtypes = listOf("thinking_tokens", "Other_2")).claudeDropSystemSubtypes,
        )
    }

    @Test
    fun `claudeDropSystemSubtypes rejects init`() {
        val ex = assertThrows<IllegalArgumentException> { Server(claudeDropSystemSubtypes = listOf("thinking_tokens", "init")) }
        assertTrue(ex.message!!.contains("init"), ex.message)
        assertTrue(ex.message!!.contains("requireClaudeCodeVersion"), ex.message)
    }

    @Test
    fun `claudeDropSystemSubtypes rejects entries outside the identifier character set`() {
        for (bad in listOf("", " ", "thinking tokens", "thinking-tokens", "\"quoted\"", "a.b")) {
            val ex =
                assertThrows<IllegalArgumentException>("expected rejection of '$bad'") { Server(claudeDropSystemSubtypes = listOf(bad)) }
            assertTrue(ex.message!!.contains("server.claudeDropSystemSubtypes"), ex.message)
        }
    }

    @Test
    fun `runner queue settings default to 300 s claim TTL and 1000 pending claims`() {
        assertEquals(300, Server().runnerClaimTimeoutSec)
        assertEquals(1000, Server().maxPendingRunnerClaims)
        assertEquals(LocalRunnerConfig(), Server().localRunner)
    }

    @Test
    fun `runner queue settings reject values below 1`() {
        val ttl = assertThrows<IllegalArgumentException> { Server(runnerClaimTimeoutSec = 0) }
        assertTrue(ttl.message!!.contains("server.runnerClaimTimeoutSec"), ttl.message)
        val pending = assertThrows<IllegalArgumentException> { Server(maxPendingRunnerClaims = 0) }
        assertTrue(pending.message!!.contains("server.maxPendingRunnerClaims"), pending.message)
    }

    @Test
    fun `dispatch settledRetentionSec defaults to 7 days and is floored at one hour`() {
        assertEquals(604_800L, DispatchConfig().settledRetentionSec)
        assertEquals(3600L, DispatchConfig(settledRetentionSec = 3600).settledRetentionSec)
        val ex = assertThrows<IllegalArgumentException> { DispatchConfig(settledRetentionSec = 3599) }
        assertTrue(ex.message!!.contains("server.dispatch.settledRetentionSec"), ex.message)
    }
}
