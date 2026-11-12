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

    // ========== callsPath: the numeric gate and the completeness boundary ==========

    @Test
    fun `a zero call count is a partial reading, not a failure`() {
        val p = pricing(callsPath = "$.calls")
        assertEquals(SessionSpendOutcome.Partial(0.1, 0), p.price("""{"spend":0.1,"calls":0}""", 2, null, null))
    }

    @Test
    fun `a negative or non-finite call count fails the reading`() {
        val p = pricing(callsPath = "$.calls")
        assertEquals(
            SessionSpendOutcome.Failed("callsPath yielded no number"),
            p.price("""{"spend":0.1,"calls":-1}""", 2, null, null),
        )
        assertEquals(
            SessionSpendOutcome.Failed("callsPath yielded no number"),
            p.price("""{"spend":0.1,"calls":1e999}""", 2, null, null),
        )
    }

    @Test
    fun `the largest finite call count is still a reading, not a non-finite one`() {
        val p = pricing(callsPath = "$.calls")
        // The finite bound itself: Double.MAX_VALUE is finite, and the count saturates to
        // Int.MAX_VALUE, so the figure is complete rather than refused as unreadable.
        assertEquals(
            SessionSpendOutcome.Complete(0.1),
            p.price("""{"spend":0.1,"calls":1.7976931348623157e308}""", 2, null, null),
        )
    }

    @Test
    fun `a fractional call count is truncated to whole calls`() {
        val p = pricing(callsPath = "$.calls")
        assertEquals(SessionSpendOutcome.Complete(0.1), p.price("""{"spend":0.1,"calls":2.9}""", 2, null, null))
        assertEquals(SessionSpendOutcome.Partial(0.1, 1), p.price("""{"spend":0.1,"calls":1.9}""", 2, null, null))
    }

    @Test
    fun `the reading is complete exactly at the expected call count`() {
        val p = pricing(callsPath = "$.calls")
        assertEquals(SessionSpendOutcome.Complete(0.2), p.price("""{"spend":0.2,"calls":2}""", 2, null, null))
        assertEquals(SessionSpendOutcome.Partial(0.2, 1), p.price("""{"spend":0.2,"calls":1}""", 2, null, null))
    }

    // ========== the window's own boundaries ==========

    /** Epoch millis for an ISO-8601 instant, the form a window row's timestamp takes. */
    private fun at(instant: String): Long =
        java.time.Instant
            .parse(instant)
            .toEpochMilli()

    private fun iso(ms: Long): String =
        java.time.Instant
            .ofEpochMilli(ms)
            .toString()

    /** A window body of `[started, spend]` rows, rendered as the endpoint's `requests` array. */
    private fun windowBody(vararg rows: Pair<Long, Double>): String =
        """{"requests":[${rows.joinToString(",") { (started, spend) -> """{"started":"${iso(started)}","spend":$spend}""" }}]}"""

    @Test
    fun `the window charges the exact grace boundaries and nothing a millisecond beyond them`() {
        val grace = 5_000L
        val started = at("2026-01-01T00:00:00Z")
        val ended = at("2026-01-01T00:01:00Z")
        val p = pricing(window = true, windowGraceMs = grace)

        val body =
            windowBody(
                started - grace to 0.1,
                started - grace - 1 to 0.1,
                ended + grace to 0.1,
                ended + grace + 1 to 0.1,
            )

        // Exactly the two rows on the boundaries, so the sum pins both ends of `from..until`
        // and the grace applied to each.
        assertEquals(SessionSpendOutcome.Complete(0.2), p.price(body, 2, started, ended))
    }

    @Test
    fun `rows only outside the window fail the reading rather than being charged`() {
        val grace = 5_000L
        val started = at("2026-01-01T00:00:00Z")
        val ended = at("2026-01-01T00:01:00Z")
        val p = pricing(window = true, windowGraceMs = grace)

        val body = windowBody(started - grace - 1 to 0.1, ended + grace + 1 to 0.1)

        assertEquals(SessionSpendOutcome.Failed("requestsPath yielded no rows"), p.price(body, 1, started, ended))
    }

    @Test
    fun `the window's own row count is the ingested figure`() {
        val started = at("2026-01-01T00:00:00Z")
        val ended = at("2026-01-01T00:01:00Z")
        val p = pricing(window = true)

        val body = windowBody(started to 0.25, started + 1_000 to 0.5)

        assertEquals(SessionSpendOutcome.Complete(0.75), p.price(body, 2, started, ended))
        assertEquals(SessionSpendOutcome.Partial(0.75, 2), p.price(body, 3, started, ended))
    }

    // ========== windowCost: the zero and negative gates ==========

    @Test
    fun `a window summing to zero is complete at zero, not a negative-number failure`() {
        val started = at("2026-01-01T00:00:00Z")
        val ended = at("2026-01-01T00:01:00Z")
        val p = pricing(window = true)

        val body = windowBody(started to 0.0, started + 1_000 to 0.0)

        assertEquals(SessionSpendOutcome.Complete(0.0), p.price(body, 2, started, ended))
    }

    @Test
    fun `in-window spends summing past the double range fail the reading`() {
        val started = at("2026-01-01T00:00:00Z")
        val ended = at("2026-01-01T00:01:00Z")
        val p = pricing(window = true)

        // Two rows at the largest finite double overflow the sum to infinity: no figure a
        // charge can be made from, so the reading fails instead of pricing the attempt.
        val max = 1.7976931348623157e308
        val body = windowBody(started to max, started + 1_000 to max)

        assertEquals(SessionSpendOutcome.Failed("requestsPath yielded no number"), p.price(body, 2, started, ended))
    }

    @Test
    fun `a negative or non-numeric in-window spend fails the reading`() {
        val started = at("2026-01-01T00:00:00Z")
        val ended = at("2026-01-01T00:01:00Z")
        val p = pricing(window = true)

        assertEquals(
            SessionSpendOutcome.Failed("requestsPath yielded a negative number"),
            p.price(windowBody(started to -0.5), 1, started, ended),
        )
        assertEquals(
            SessionSpendOutcome.Failed("requestsPath yielded no number"),
            p.price("""{"requests":[{"started":"${iso(started)}","spend":"lots"}]}""", 1, started, ended),
        )
    }
}
