package homer.app.infra

import homer.app.models.SessionSpendConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionSpendPricingTest {
    private fun pricing(
        callsPath: String? = null,
        window: Boolean = false,
        windowGraceMs: Long = 0,
    ) = SessionSpendPricing(
        SessionSpendConfig(
            url = "https://spend.example/api/session/{sessionId}",
            costPath = "$.spend",
            callsPath = callsPath,
            requestsPath = "$.requests".takeIf { window },
            startedPath = "$.started".takeIf { window },
            requestSpendPath = "$.spend".takeIf { window },
            windowGraceMs = windowGraceMs,
        ),
    )

    @Test
    fun `a session figure without callsPath is complete and not worth polling for`() {
        val p = pricing()
        assertFalse(p.pollsForCompleteness)
        assertEquals(SessionSpendOutcome.Complete(0.25), p.price("""{"spend":0.25}""", 3, null, null))
    }

    @Test
    fun `callsPath completes a figure only once every expected call is ingested`() {
        val p = pricing(callsPath = "$.calls")
        assertTrue(p.pollsForCompleteness)
        assertEquals(SessionSpendOutcome.Partial(0.1, 1), p.price("""{"spend":0.1,"calls":1}""", 2, null, null))
        assertEquals(SessionSpendOutcome.Complete(0.2), p.price("""{"spend":0.2,"calls":2}""", 2, null, null))
        assertEquals(SessionSpendOutcome.Failed("callsPath yielded no number"), p.price("""{"spend":0.2}""", 2, null, null))
    }

    @Test
    fun `untrusted bodies degrade to fixed-vocabulary failures`() {
        val p = pricing()
        assertEquals(SessionSpendOutcome.Failed("response is not JSON"), p.price("<html>nope</html>", 1, null, null))
        assertEquals(SessionSpendOutcome.Failed("costPath yielded no number"), p.price("""{"spend":"x"}""", 1, null, null))
        assertEquals(SessionSpendOutcome.Failed("costPath yielded a negative number"), p.price("""{"spend":-1}""", 1, null, null))
    }

    @Test
    fun `window pricing sums only the attempt's own rows`() {
        val p = pricing(window = true)
        assertTrue(p.pollsForCompleteness)
        val body =
            """{"requests":[{"started":"2026-01-01T00:00:00Z","spend":0.5},""" +
                """{"started":"2026-01-01T00:01:00Z","spend":0.25},{"started":1767225720,"spend":0.125}]}"""
        val from =
            java.time.Instant
                .parse("2026-01-01T00:00:30Z")
                .toEpochMilli()
        val until =
            java.time.Instant
                .parse("2026-01-01T00:02:30Z")
                .toEpochMilli()
        assertEquals(SessionSpendOutcome.Complete(0.375), p.price(body, 2, from, until))
    }

    @Test
    fun `window pricing refuses an unknowable window or an unplaceable row`() {
        val p = pricing(window = true)
        assertEquals(SessionSpendOutcome.Failed("attempt window unavailable"), p.price("""{"requests":[]}""", 1, null, 1L))
        assertEquals(
            SessionSpendOutcome.Failed("requestsPath rows have no readable timestamp"),
            p.price("""{"requests":[{"started":"2026-01-01T00:00:00","spend":0.5}]}""", 1, 0L, Long.MAX_VALUE),
        )
    }
}
