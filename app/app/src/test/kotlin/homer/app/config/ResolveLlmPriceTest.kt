package homer.app.config

import homer.app.models.LlmPriceConfig
import homer.app.models.SessionSpendConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolveLlmPriceTest {
    private val resolve: (String) -> String = { it.replace("@SYSENV:TOK", "abc123") }

    @Test
    fun `header placeholders are resolved`() {
        val cfg =
            LlmPriceConfig(
                SessionSpendConfig(
                    url = "https://h/{sessionId}",
                    headers = mapOf("Authorization" to "Bearer @SYSENV:TOK"),
                    costPath = "$.spend",
                ),
            )
        val out = resolveLlmPrice(cfg, resolve)
        assertEquals("Bearer abc123", out!!.sessionSpend!!.headers["Authorization"])
    }

    @Test
    fun `null passes through`() {
        assertNull(resolveLlmPrice(null, resolve))
    }
}
