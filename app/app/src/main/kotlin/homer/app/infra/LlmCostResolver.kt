package homer.app.infra

import homer.app.models.CostSource
import homer.app.models.ResolvedCost
import homer.app.models.SESSION_ID_PATTERN
import homer.app.models.SessionSpendConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException

/**
 * Prices one Claude attempt: the operator's session-spend endpoint first, the CLI's own
 * figure second. Every failure of the first rung degrades to the second with a reason,
 * never an exception — cost is metadata, the run must complete. Each request is bounded
 * by [SessionSpendConfig.timeoutMs] and [MAX_BODY_BYTES].
 *
 * With [SessionSpendConfig.callsPath] set the endpoint also reports how many of the
 * session's calls it has ingested, and the lookup is retried on the fixed
 * [DEFAULT_POLL_OFFSETS_MS] schedule until that count reaches the calls the attempt made
 * — spend ingestion is asynchronous, so the figure available at CLI exit is often partial.
 * A harness that cannot count its model calls passes `expectedCalls = null`: the figure is
 * then accepted once two successive readings agree and show at least one call (ADR-0070).
 * Without it, one request as before.
 *
 * With the request-window keys set ([SessionSpendConfig.requestsPath], `startedPath`,
 * `requestSpendPath`) the figure is instead the sum of the rows whose start time falls in
 * the attempt's own `[startedAtMs, endedAtMs]` window (plus `windowGraceMs` on each side),
 * and the window's row count drives the completeness poll. This is what prices a
 * `--continue`-resumed session per attempt rather than charging each run the session's
 * cumulative total. See ADR-0061.
 */
class LlmCostResolver(
    private val config: SessionSpendConfig?,
    engine: HttpClientEngine = CIO.create(),
    private val pollOffsetsMs: List<Long> = DEFAULT_POLL_OFFSETS_MS,
) {
    private val logger = LoggerFactory.getLogger(LlmCostResolver::class.java)

    val configured: Boolean get() = config != null

    private val client: HttpClient? =
        config?.let { cfg ->
            HttpClient(engine) {
                expectSuccess = false
                // A 3xx would otherwise be followed automatically, issuing a further GET
                // to a response-supplied URL carrying cfg.headers (credentials). Treat any
                // redirect as a failure of this rung instead — it degrades via isSuccess().
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = cfg.timeoutMs
                    connectTimeoutMillis = cfg.timeoutMs
                    socketTimeoutMillis = cfg.timeoutMs
                }
            }
        }

    private val pricing: SessionSpendPricing? = config?.let(::SessionSpendPricing)

    suspend fun resolve(
        sessionId: String?,
        cliCostUsd: Double,
        expectedCalls: Int?,
        startedAtMs: Long? = null,
        endedAtMs: Long? = null,
    ): ResolvedCost {
        val responsePricing = pricing ?: return ResolvedCost(cliCostUsd, CostSource.CLI)
        val http = client ?: return ResolvedCost(cliCostUsd, CostSource.CLI)
        if (sessionId == null) return fallback(cliCostUsd, "no session id in output")
        // The id is read from a file the agent's process wrote — attacker-controlled (§12
        // rule 2). Charset gate first, then URL-encode, so it can only ever address one
        // path segment of the configured endpoint.
        if (!SESSION_ID_PATTERN.matches(sessionId)) return fallback(cliCostUsd, "invalid session id")
        val url = responsePricing.config.url.replace(SessionSpendConfig.SESSION_ID_PLACEHOLDER, sessionId.encodeURLPathPart())

        return pollForCost(cliCostUsd, expectedCalls) { attempt(responsePricing, http, url, expectedCalls, startedAtMs, endedAtMs) }
    }

    /**
     * Runs [attemptOnce] on the poll schedule until a figure is complete or settled, and
     * charges the best partial figure — or the CLI's — once the schedule runs out.
     */
    private suspend fun pollForCost(
        cliCostUsd: Double,
        expectedCalls: Int?,
        attemptOnce: suspend () -> SessionSpendOutcome,
    ): ResolvedCost {
        // Without a completeness signal (callsPath, or a requests window whose row count
        // stands in for one) there is nothing to poll for: one attempt, no waiting — exactly
        // the pre-callsPath contract.
        val offsets = pollSchedule()
        var partial: SessionSpendOutcome.Partial? = null
        var previous: SessionSpendOutcome.Partial? = null
        var lastFailure: String? = null
        for ((index, offset) in offsets.withIndex()) {
            // A plain delay: a caller cancellation while waiting must propagate, never be
            // priced as a session figure.
            if (index > 0) delay(offset - offsets[index - 1])
            when (val outcome = attemptOnce()) {
                is SessionSpendOutcome.Complete -> {
                    return ResolvedCost(outcome.cost, CostSource.SESSION)
                }

                is SessionSpendOutcome.Partial -> {
                    // No call count to reach: a reading the previous one confirms is settled. See ADR-0070.
                    if (outcome.settles(expectedCalls, previous)) {
                        return ResolvedCost(outcome.cost, CostSource.SESSION)
                    }
                    previous = outcome
                    // Keep the best reading, not the latest: a later response may have
                    // ingested fewer calls (a different replica, a rolled-back row), and
                    // charging that would under-count what we already know was spent.
                    if (outcome.betterThan(partial)) partial = outcome
                    logRepoll(outcome, expectedCalls)
                }

                is SessionSpendOutcome.Failed -> {
                    lastFailure = outcome.reason
                }
            }
        }
        return chargeBestPartial(cliCostUsd, expectedCalls, partial, lastFailure)
    }

    /** The poll offsets [pollForCost] runs on: a single immediate attempt without a completeness signal. */
    private fun pollSchedule(): List<Long> = if (pricing?.pollsForCompleteness == true) pollOffsetsMs else listOf(0L)

    private fun logRepoll(
        outcome: SessionSpendOutcome.Partial,
        expectedCalls: Int?,
    ) {
        logger.debug(
            "llmPrice.sessionSpend has ingested {} of {} calls of the session; re-polling",
            outcome.calls,
            expectedCalls ?: "an unknown number of",
        )
    }

    /** What [pollForCost] charges once the schedule ran out without a complete or settled figure. */
    private fun chargeBestPartial(
        cliCostUsd: Double,
        expectedCalls: Int?,
        partial: SessionSpendOutcome.Partial?,
        lastFailure: String?,
    ): ResolvedCost {
        // The schedule ran out. A partial figure still beats the CLI's rate-card guess —
        // charge it, marked, so the under-count is visible rather than silent.
        val best = partial ?: return fallback(cliCostUsd, lastFailure ?: "lookup failed")
        return chargeIncomplete(best, expectedCalls)
    }

    private fun chargeIncomplete(
        best: SessionSpendOutcome.Partial,
        expectedCalls: Int?,
    ): ResolvedCost {
        val reason =
            if (expectedCalls == null) {
                "incomplete: figure not stable across polls"
            } else {
                "incomplete: ${best.calls} of $expectedCalls calls ingested"
            }
        logger.warn("llmPrice.sessionSpend figure is incomplete ({}); charging it anyway", reason)
        return ResolvedCost(best.cost, CostSource.SESSION, reason)
    }

    @Suppress("SuspendFunSwallowedCancellation") // failureFor rethrows a caller's cancellation
    private suspend fun attempt(
        pricing: SessionSpendPricing,
        http: HttpClient,
        url: String,
        expectedCalls: Int?,
        startedAtMs: Long?,
        endedAtMs: Long?,
    ): SessionSpendOutcome {
        val cfg = pricing.config
        // execute's block can't use a non-local return (it isn't inlined), so an early
        // exit inside it is signalled via this var and picked up once execute has
        // returned — by which point the response has already been released either way.
        var httpFailureReason: String? = null
        val rawBody: String? =
            try {
                // HttpTimeout cancels the *call* job, not necessarily whatever the response
                // body is suspended on — with a real socket that surfaces as a socket
                // timeout, but MockEngine (and conceivably other engines) have no socket to
                // time out, so a stalled read could hang forever. withTimeout makes the
                // bound engine-independent: it cancels this coroutine directly.
                withTimeout(cfg.timeoutMs) {
                    // prepareGet + execute (rather than a plain get()) guarantees the
                    // response is released on every exit from the block below — early
                    // return, exception, or normal completion — not just the happy path.
                    http
                        .prepareGet(url) {
                            cfg.headers.forEach { (k, v) -> header(k, v) }
                        }.execute { response ->
                            if (!response.status.isSuccess()) {
                                httpFailureReason = "http ${response.status.value}"
                                return@execute null
                            }
                            val bytes = response.bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1).readByteArray()
                            if (bytes.size > MAX_BODY_BYTES) {
                                httpFailureReason = "response over 1 MiB"
                                return@execute null
                            }
                            bytes.decodeToString()
                        }
                }
            } catch (e: Throwable) {
                return failureFor(e)
            }
        val body: String = rawBody ?: return SessionSpendOutcome.Failed(httpFailureReason ?: "lookup failed")
        return pricing.price(body, expectedCalls, startedAtMs, endedAtMs)
    }

    /**
     * Maps a throwable from the request-and-read round to its fixed-vocabulary reason, or
     * rethrows it when it is a caller's cancellation. The `is` checks run in the order the
     * catch clauses they replace did.
     */
    private suspend fun failureFor(e: Throwable): SessionSpendOutcome.Failed =
        when (e) {
            // Usually our own withTimeout above firing. But if a caller wraps resolve()
            // in a shorter withTimeout of its own, its TimeoutCancellationException can
            // reach here first (we're suspended inside our inner withTimeout when the
            // outer one fires) — that must propagate, not get priced as "timeout".
            // After our own timeout fires the enclosing coroutine is still active;
            // after an outer one it is not.
            is TimeoutCancellationException -> {
                if (!currentCoroutineContext().isActive) throw e
                SessionSpendOutcome.Failed("timeout")
            }

            is HttpRequestTimeoutException, is SocketTimeoutException, is ConnectTimeoutException -> {
                SessionSpendOutcome.Failed("timeout")
            }

            is UnresolvedAddressException, is java.io.IOException -> {
                SessionSpendOutcome.Failed("connection failed")
            }

            // HttpTimeout can also cancel the request job with a bare
            // CancellationException (not TimeoutCancellationException, so not caught
            // above) instead of converting it to HttpRequestTimeoutException. Our own
            // timeout leaves the caller's coroutine context active; genuine caller
            // cancellation does not — only the latter must propagate.
            is CancellationException -> {
                if (currentCoroutineContext().isActive) SessionSpendOutcome.Failed("timeout") else throw e
            }

            // Catch-all: cost is metadata, never a reason to fail the run (§12
            // availability rule). Specific branches above keep the precise reasons;
            // anything else — a client-internals bug, an engine-specific throwable,
            // even an Error — still degrades instead of propagating.
            else -> {
                SessionSpendOutcome.Failed("lookup failed")
            }
        }

    private fun fallback(
        cliCostUsd: Double,
        reason: String,
    ): ResolvedCost {
        // Reason strings are fixed vocabulary + status code: never the URL, body or headers.
        logger.warn("llmPrice.sessionSpend lookup failed ({}); charging the CLI-reported cost", reason)
        return ResolvedCost(cliCostUsd, CostSource.CLI, reason)
    }

    companion object {
        /**
         * Attempt offsets after the CLI exit, when `callsPath` is set: now, then 5, 15 and
         * 30 s. Fixed rather than configurable — see ADR-0061.
         */
        val DEFAULT_POLL_OFFSETS_MS = listOf(0L, 5_000L, 15_000L, 30_000L)
        val DISABLED = LlmCostResolver(null, engine = CIO.create())
        const val MAX_BODY_BYTES = 1024 * 1024L
    }
}
