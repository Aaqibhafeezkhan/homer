package homer.app.infra

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.PathNotFoundException
import homer.app.models.SessionSpendConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Reads one session-spend response body into a [SessionSpendOutcome] for
 * [LlmCostResolver]: the session-cumulative [SessionSpendConfig.costPath] figure,
 * completed by `callsPath` when set, or — with the three request-window keys set —
 * the sum of the attempt's own in-window rows (ADR-0061). The body is
 * operator-endpoint output: every read degrades to a fixed-vocabulary failure,
 * never an exception.
 */
internal class SessionSpendPricing(
    val config: SessionSpendConfig,
) {
    // Compiled once; validated by SessionSpendConfig.init so these cannot throw here.
    private val costPath: JsonPath = JsonPath.compile(config.costPath)
    private val callsPath: JsonPath? = config.callsPath?.let { JsonPath.compile(it) }

    /** Set when all three window keys are configured: price each attempt from its own requests. */
    private val window: WindowPaths? =
        if (config.requestsPath != null && config.startedPath != null && config.requestSpendPath != null) {
            WindowPaths(
                requests = JsonPath.compile(config.requestsPath),
                started = JsonPath.compile(config.startedPath),
                requestSpend = JsonPath.compile(config.requestSpendPath),
            )
        } else {
            null
        }

    /** The compiled [SessionSpendConfig.requestsPath], `startedPath` and `requestSpendPath`. */
    private class WindowPaths(
        val requests: JsonPath,
        val started: JsonPath,
        val requestSpend: JsonPath,
    )

    /** True when a reading can tell whether it is complete (callsPath or the request window), so it is worth polling for. */
    val pollsForCompleteness: Boolean get() = callsPath != null || window != null

    /** Prices one response [body]; [startedAtMs]/[endedAtMs] bound the attempt's request window. */
    fun price(
        body: String,
        expectedCalls: Int?,
        startedAtMs: Long?,
        endedAtMs: Long?,
    ): SessionSpendOutcome {
        val document = parseDocument(body) ?: return SessionSpendOutcome.Failed("response is not JSON")
        // With the request-window keys configured, price the attempt from its own requests
        // instead of the session-cumulative costPath. See ADR-0061 (Amendment 2026-10-06).
        if (window != null) {
            return windowOutcome(document, window, expectedCalls, startedAtMs, endedAtMs)
        }

        return sessionOutcome(document, expectedCalls)
    }

    /** [body] as a JsonPath document; null when it is not a JSON object or array. */
    private fun parseDocument(body: String): DocumentContext? {
        // Jayway's default provider parses leniently — even single-token garbage like
        // "<html>nope</html>" comes back as a bare scalar "document" instead of throwing.
        // A costPath can only ever match inside an object or array, so requiring the
        // strict-parsed top level to be one of those rejects non-JSON bodies without
        // rejecting any body an object/array-shaped costPath could actually match.
        val strictParse = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        if (strictParse !is JsonObject && strictParse !is JsonArray) return null
        return try {
            JsonPath.using(JSONPATH_CONF).parse(body)
        } catch (_: Exception) {
            null
        }
    }

    /** Prices an attempt from the session-cumulative [costPath], completed by [callsPath] when set. */
    private fun sessionOutcome(
        document: DocumentContext,
        expectedCalls: Int?,
    ): SessionSpendOutcome {
        val value: Any? =
            try {
                document.read<Any?>(costPath)
            } catch (_: PathNotFoundException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return SessionSpendOutcome.Failed("lookup failed")
            }
        val total = sumNumbers(value) ?: return SessionSpendOutcome.Failed("costPath yielded no number")
        if (total < 0.0) return SessionSpendOutcome.Failed("costPath yielded a negative number")
        return callsOutcome(document, total, expectedCalls)
    }

    /** Completes a session [total] with the [callsPath] count, when one is configured. */
    private fun callsOutcome(
        document: DocumentContext,
        total: Double,
        expectedCalls: Int?,
    ): SessionSpendOutcome {
        val path = callsPath ?: return SessionSpendOutcome.Complete(total)
        val ingestedValue = readOrNull(document, path)
        // A missing path or a non-number says nothing about progress — it is a broken
        // callsPath, not zero ingested. Fail the attempt so the schedule retries it and,
        // if it never improves, the CLI figure is charged with this reason.
        val ingested =
            (ingestedValue as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }?.toInt()
                ?: return SessionSpendOutcome.Failed("callsPath yielded no number")
        return countedOutcome(total, ingested, expectedCalls)
    }

    /** Reads [path] from [document]; null when it is missing or unreadable. */
    private fun readOrNull(
        document: DocumentContext,
        path: JsonPath,
    ): Any? =
        try {
            document.read<Any?>(path)
        } catch (_: PathNotFoundException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    /** Complete once [ingested] reaches [expectedCalls]; Partial otherwise, and always when nothing is expected. */
    private fun countedOutcome(
        cost: Double,
        ingested: Int,
        expectedCalls: Int?,
    ): SessionSpendOutcome =
        if (expectedCalls != null && ingested >= expectedCalls) {
            SessionSpendOutcome.Complete(cost)
        } else {
            SessionSpendOutcome.Partial(cost, ingested)
        }

    /**
     * Prices one attempt from the [SessionSpendConfig.requestsPath] rows whose start time
     * falls inside the attempt window, and uses the row count as the ingested figure for the
     * completeness comparison — the whole point of the window: on a resumed (`--continue`)
     * session the session-wide `$.calls` already satisfies `expectedCalls` before this
     * attempt's requests are ingested, so the count must be the window's, not the session's.
     * See ADR-0061 (Amendment 2026-10-06).
     */
    private fun windowOutcome(
        document: DocumentContext,
        paths: WindowPaths,
        expectedCalls: Int?,
        startedAtMs: Long?,
        endedAtMs: Long?,
    ): SessionSpendOutcome {
        // Without the attempt's own boundaries the window is unknowable: every row would
        // look in-window and the figure would be the session total again. Degrade rather
        // than misprice. The executor always passes both.
        if (startedAtMs == null || endedAtMs == null) return SessionSpendOutcome.Failed("attempt window unavailable")
        val rows = readRequestRows(document, paths.requests) ?: return SessionSpendOutcome.Failed("requestsPath yielded no rows")
        val grace = config.windowGraceMs
        val from = startedAtMs - grace
        val until = endedAtMs + grace
        // An absent/unreadable `started` says nothing about which attempt a row belongs to —
        // including it would risk charging this attempt an earlier one's spend, and skipping
        // it would drop a possibly in-window row from the charge (CWE-840), so the whole
        // window is refused rather than priced from an unplaceable row.
        val timestamps =
            rows.map { row ->
                parseEpochMs(readRowField(row, paths.started))
                    ?: return SessionSpendOutcome.Failed("requestsPath rows have no readable timestamp")
            }
        val inWindow =
            rows
                .zip(timestamps)
                .filter { (_, ts) -> ts in from..until }
                .map { (row, _) -> row }
        if (inWindow.isEmpty()) return SessionSpendOutcome.Failed("requestsPath yielded no rows")
        return windowCost(inWindow, paths.requestSpend, expectedCalls)
    }

    /** Sums the spend of the in-window [rows]; Failed when one carries no number or the sum is negative. */
    private fun windowCost(
        rows: List<*>,
        requestSpendPath: JsonPath,
        expectedCalls: Int?,
    ): SessionSpendOutcome {
        val spends =
            rows.map { row ->
                val v = readRowField(row, requestSpendPath)
                (v as? Number)?.toDouble() ?: return SessionSpendOutcome.Failed("requestsPath yielded no number")
            }
        val cost = sumNumbers(spends) ?: return SessionSpendOutcome.Failed("requestsPath yielded no number")
        if (cost < 0.0) return SessionSpendOutcome.Failed("requestsPath yielded a negative number")
        return countedOutcome(cost, rows.size, expectedCalls)
    }

    /** The [requestsPath] rows as a list; null when the path is missing, unreadable or not an array. */
    private fun readRequestRows(
        document: DocumentContext,
        requestsPath: JsonPath,
    ): List<*>? =
        try {
            document.read<Any?>(requestsPath) as? List<*>
        } catch (_: PathNotFoundException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    /** Reads one [field] of a [SessionSpendConfig.requestsPath] row; a row that is not an object yields null. */
    private fun readRowField(
        row: Any?,
        field: JsonPath,
    ): Any? =
        try {
            JsonPath.using(JSONPATH_CONF).parse(row).read<Any?>(field)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    /**
     * Parses a `started` field to epoch milliseconds. Accepts an ISO-8601 instant or offset
     * date-time (what a spend API's row carries), or a number already in milliseconds or
     * seconds. Anything else — including a value with no zone, which would be ambiguous — is
     * unreadable and returns null: reading it as UTC would risk placing the row in the wrong
     * attempt's window and charging that attempt another one's spend (see ADR-0061).
     */
    private fun parseEpochMs(value: Any?): Long? {
        if (value is Number) {
            val d = value.toDouble()
            if (!d.isFinite() || d < 0.0) return null
            // A magnitude below this cannot be epoch milliseconds in any plausible deployment,
            // so it is seconds.
            return if (d < 1e11) (d * 1000.0).toLong() else d.toLong()
        }
        val text = (value as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return runCatching {
            java.time.Instant
                .parse(text)
                .toEpochMilli()
        }.recoverCatching {
            java.time.OffsetDateTime
                .parse(text)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    /** One number, or a list whose every element is a number → their sum. Anything else → null. */
    private fun sumNumbers(value: Any?): Double? =
        when (value) {
            is Number -> {
                value.toDouble().takeIf { it.isFinite() }
            }

            is List<*> -> {
                if (value.isEmpty() || value.any { it !is Number }) {
                    null
                } else if (value.any { (it as Number).toDouble() < 0.0 }) {
                    // A negative element makes the whole figure untrustworthy even if the
                    // sum happens to land non-negative; surface the same "negative number"
                    // fallback reason a negative scalar produces (checked by the caller).
                    NEGATIVE_SENTINEL
                } else {
                    value.sumOf { (it as Number).toDouble() }.takeIf { it.isFinite() }
                }
            }

            else -> {
                null
            }
        }

    private companion object {
        const val NEGATIVE_SENTINEL = -1.0
        val JSONPATH_CONF: Configuration =
            Configuration
                .defaultConfiguration()
                .addOptions(Option.SUPPRESS_EXCEPTIONS)
    }
}

/** What one session-spend request-and-parse round produced. [Failed] carries a fixed-vocabulary reason. */
internal sealed interface SessionSpendOutcome {
    /** The endpoint has ingested every call the attempt made (or there is no way to tell). */
    data class Complete(
        val cost: Double,
    ) : SessionSpendOutcome

    /** A usable figure covering only [calls] of the attempt's calls. */
    data class Partial(
        val cost: Double,
        val calls: Int,
    ) : SessionSpendOutcome {
        /** More calls ingested wins; on a tie the higher figure does. Any reading beats none. */
        fun betterThan(other: Partial?): Boolean = other == null || calls > other.calls || (calls == other.calls && cost > other.cost)

        /**
         * With no call count to reach ([expectedCalls] null), a reading showing at least one
         * call that the [previous] one confirms is settled.
         */
        fun settles(
            expectedCalls: Int?,
            previous: Partial?,
        ): Boolean = expectedCalls == null && calls >= 1 && this == previous
    }

    data class Failed(
        val reason: String,
    ) : SessionSpendOutcome
}
