package homer.app.infra

import homer.app.models.CostSource
import homer.app.models.SessionSpendConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmCostResolverTest {
    private fun cfg(
        costPath: String = "$.spend",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 3000,
        callsPath: String? = null,
        requestsPath: String? = null,
        startedPath: String? = null,
        requestSpendPath: String? = null,
        windowGraceMs: Long = 0,
    ) = SessionSpendConfig(
        url = "https://spend.example/api/session/{sessionId}",
        headers = headers,
        costPath = costPath,
        callsPath = callsPath,
        requestsPath = requestsPath,
        startedPath = startedPath,
        requestSpendPath = requestSpendPath,
        windowGraceMs = windowGraceMs,
        timeoutMs = timeoutMs,
    )

    /** A window-configured config: rows at `$.requests`, each row's `started`/`spend`. */
    private fun windowCfg(
        costPath: String = "$.spend",
        callsPath: String? = null,
        windowGraceMs: Long = 0,
    ) = cfg(
        costPath = costPath,
        callsPath = callsPath,
        requestsPath = "$.requests",
        startedPath = "$.started",
        requestSpendPath = "$.spend",
        windowGraceMs = windowGraceMs,
    )

    /** Epoch ms for an ISO-8601 instant. */
    private fun ms(iso: String): Long =
        java.time.Instant
            .parse(iso)
            .toEpochMilli()

    /** Millisecond stand-ins for the production 0/5/15/30 s schedule, so tests run in real time. */
    private val pollMs = listOf(0L, 50L, 100L, 150L)

    /** Replays [bodies] one per request, repeating the last, and records when each request arrived. */
    private fun scriptedEngine(
        bodies: List<String>,
        stamps: MutableList<Long> = mutableListOf(),
    ): Pair<MockEngine, MutableList<Long>> {
        var index = 0
        val engine =
            MockEngine {
                stamps += System.nanoTime()
                val body = bodies[minOf(index++, bodies.lastIndex)]
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return engine to stamps
    }

    private fun jsonEngine(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

    @Test
    fun `unconfigured returns CLI figure with no reason`() =
        runBlocking {
            val r = LlmCostResolver(null).resolve("abc", 0.5, 0)
            assertEquals(ResolvedCostShape(0.5, CostSource.CLI, null), r.shape())
            assertFalse(LlmCostResolver(null).configured)
        }

    @Test
    fun `scalar path returns session cost`() =
        runBlocking {
            val r = LlmCostResolver(cfg(), jsonEngine("""{"spend":0.1795,"calls":5}""")).resolve("56bded02-8563", 9.9, 0)
            assertEquals(ResolvedCostShape(0.1795, CostSource.SESSION, null), r.shape())
        }

    @Test
    fun `list path is summed`() =
        runBlocking {
            val r = LlmCostResolver(cfg("$.data[*].spend"), jsonEngine("""{"data":[{"spend":0.1},{"spend":0.25}]}""")).resolve("s", 9.9, 0)
            assertEquals(CostSource.SESSION, r.source)
            assertEquals(0.35, r.costUsd, 1e-9)
        }

    @Test
    fun `session id is substituted and url-encoded, headers sent verbatim`() =
        runBlocking {
            var seenUrl = ""
            var seenAuth: String? = null
            val engine =
                MockEngine { req ->
                    seenUrl = req.url.toString()
                    seenAuth = req.headers["Authorization"]
                    respond("""{"spend":1}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            LlmCostResolver(cfg(headers = mapOf("Authorization" to "Bearer tok")), engine).resolve("a_b-C9", 0.0, 0)
            assertEquals("https://spend.example/api/session/a_b-C9", seenUrl)
            assertEquals("Bearer tok", seenAuth)
        }

    @Test
    fun `a rows-shaped endpoint can count its own rows with a length() path`() =
        runBlocking {
            // Documented in SERVER.md for endpoints that return per-request rows and no
            // count field; pins that Jayway's length() reads back as a number here.
            val body = """{"requests":[{"spend":0.1},{"spend":0.2},{"spend":0.05}],"spend":0.35}"""
            val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val r =
                LlmCostResolver(cfg(callsPath = "$.requests.length()"), engine, pollOffsetsMs = pollMs)
                    .resolve("s", 9.9, 3)
            assertEquals(CostSource.SESSION, r.source)
            assertEquals(0.35, r.costUsd, 1e-9)
            assertEquals(null, r.fallbackReason)
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun `null session id falls back without a request`() =
        runBlocking {
            var calls = 0
            val engine =
                MockEngine {
                    calls++
                    respond("{}")
                }
            val r = LlmCostResolver(cfg(), engine).resolve(null, 0.7, 0)
            assertEquals(ResolvedCostShape(0.7, CostSource.CLI, "no session id in output"), r.shape())
            assertEquals(0, calls)
        }

    @Test
    fun `path-traversal shaped session id falls back without a request`() =
        runBlocking {
            var calls = 0
            val engine =
                MockEngine {
                    calls++
                    respond("{}")
                }
            val r = LlmCostResolver(cfg(), engine).resolve("../admin", 0.7, 0)
            assertEquals(ResolvedCostShape(0.7, CostSource.CLI, "invalid session id"), r.shape())
            assertEquals(0, calls)
        }

    @Test
    fun `http 404 falls back with status in reason`() =
        runBlocking {
            val r = LlmCostResolver(cfg(), MockEngine { respondError(HttpStatusCode.NotFound) }).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "http 404"), r.shape())
        }

    @Test
    fun `http 500 falls back`() =
        runBlocking {
            val r = LlmCostResolver(cfg(), MockEngine { respondError(HttpStatusCode.InternalServerError) }).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "http 500"), r.shape())
        }

    @Test
    fun `redirect is not followed and degrades with the status in reason`() =
        runBlocking {
            var calls = 0
            val engine =
                MockEngine {
                    calls++
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://evil.example/steal"),
                    )
                }
            val r = LlmCostResolver(cfg(), engine).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "http 302"), r.shape())
            assertEquals(1, calls)
        }

    @Test
    fun `unexpected exception falls back without leaking its message`() =
        runBlocking {
            val engine = MockEngine { throw IllegalStateException("boom") }
            val r = LlmCostResolver(cfg(), engine).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "lookup failed"), r.shape())
            assertFalse(r.fallbackReason!!.contains("boom"))
        }

    @Test
    fun `timeout falls back`() =
        runBlocking {
            val engine =
                MockEngine {
                    delay(10_000)
                    respond("""{"spend":1}""")
                }
            val r = LlmCostResolver(cfg(timeoutMs = 50), engine).resolve("s", 0.3, 0)
            assertEquals(CostSource.CLI, r.source)
            assertEquals(0.3, r.costUsd)
            assertEquals("timeout", r.fallbackReason)
        }

    @Test
    fun `own timeout mid-body still falls back with timeout, not an exception`() =
        runBlocking {
            // Writes a few bytes then never closes the channel — readRemaining suspends
            // forever waiting for more, with no socket underneath for a socket timeout to
            // fire on. If the resolver's own bound ever stops being engine-independent,
            // this hangs instead of failing — the outer withTimeout turns that back into a
            // fast test failure rather than a hung test run.
            val engine =
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    channel.writeStringUtf8("""{"sp""")
                    respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val r =
                withTimeout(5_000) {
                    LlmCostResolver(cfg(timeoutMs = 50), engine).resolve("s", 0.3, 0)
                }
            assertEquals(CostSource.CLI, r.source)
            assertEquals(0.3, r.costUsd)
            assertEquals("timeout", r.fallbackReason)
        }

    @Test
    fun `a caller's own timeout propagates instead of being priced`() {
        // Our own timeout (5_000) is far longer than the caller's (50), so it's the
        // caller's withTimeout that fires while we're suspended inside ours — that
        // TimeoutCancellationException must propagate, not be swallowed as a priced
        // ResolvedCost(..., "timeout"). A @Test method must return Unit, so this can't
        // be `= runBlocking { assertFailsWith { ... } }` like the rest of the file —
        // assertFailsWith's return value (the caught exception) would leak out as the
        // method's inferred return type and JUnit silently skips it (logged as a
        // WARNING, not a failure) instead of running it.
        runBlocking {
            val engine =
                MockEngine {
                    val channel = ByteChannel(autoFlush = true)
                    channel.writeStringUtf8("""{"sp""")
                    respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50) {
                    LlmCostResolver(cfg(timeoutMs = 5_000), engine).resolve("s", 0.3, 0)
                }
            }
        }
    }

    @Test
    fun `non-json body falls back`() =
        runBlocking {
            val r = LlmCostResolver(cfg(), jsonEngine("<html>nope</html>")).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "response is not JSON"), r.shape())
        }

    @Test
    fun `path miss falls back`() =
        runBlocking {
            val r = LlmCostResolver(cfg("$.nothing"), jsonEngine("""{"spend":1}""")).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "costPath yielded no number"), r.shape())
        }

    @Test
    fun `non-numeric value falls back`() =
        runBlocking {
            val r = LlmCostResolver(cfg(), jsonEngine("""{"spend":"lots"}""")).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "costPath yielded no number"), r.shape())
        }

    @Test
    fun `negative value falls back`() =
        runBlocking {
            val r = LlmCostResolver(cfg(), jsonEngine("""{"spend":-1}""")).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "costPath yielded a negative number"), r.shape())
        }

    @Test
    fun `negative element inside a summed list falls back`() =
        runBlocking {
            val r =
                LlmCostResolver(cfg("$.data[*].spend"), jsonEngine("""{"data":[{"spend":-5},{"spend":10}]}"""))
                    .resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "costPath yielded a negative number"), r.shape())
        }

    @Test
    fun `connection failure falls back`() =
        runBlocking {
            val engine = MockEngine { throw java.nio.channels.UnresolvedAddressException() }
            val r = LlmCostResolver(cfg(), engine).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "connection failed"), r.shape())
        }

    @Test
    fun `an IO failure falls back as a connection failure`() =
        runBlocking {
            val engine = MockEngine { throw java.io.IOException("reset by peer") }
            val r = LlmCostResolver(cfg(), engine).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "connection failed"), r.shape())
        }

    @Test
    fun `engine-level timeouts all fall back as timeout`() =
        runBlocking {
            val timeouts: List<() -> Throwable> =
                listOf(
                    { java.net.SocketTimeoutException("read timed out") },
                    {
                        io.ktor.client.network.sockets
                            .ConnectTimeoutException("connect timed out")
                    },
                    {
                        io.ktor.client.plugins
                            .HttpRequestTimeoutException("https://spend.example", 50)
                    },
                )
            for (timeout in timeouts) {
                val engine = MockEngine { throw timeout() }
                val r = LlmCostResolver(cfg(), engine).resolve("s", 0.3, 0)
                assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "timeout"), r.shape())
            }
        }

    @Test
    fun `a bare cancellation of the request while the caller is active falls back as timeout`() =
        runBlocking {
            val engine = MockEngine { throw kotlinx.coroutines.CancellationException("request job cancelled") }
            val r = LlmCostResolver(cfg(), engine).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "timeout"), r.shape())
        }

    @Test
    fun `non-finite value falls back`() =
        runBlocking {
            val r = LlmCostResolver(cfg(), jsonEngine("""{"spend":1e999}""")).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "costPath yielded no number"), r.shape())
        }

    @Test
    fun `oversized body falls back`() =
        runBlocking {
            val big = """{"pad":"${"x".repeat(1_100_000)}","spend":1}"""
            val r = LlmCostResolver(cfg(), jsonEngine(big)).resolve("s", 0.3, 0)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "response over 1 MiB"), r.shape())
        }

    @Test
    fun `reason never leaks header values`() =
        runBlocking {
            val r =
                LlmCostResolver(
                    cfg(headers = mapOf("Authorization" to "Bearer SECRET")),
                    MockEngine {
                        respondError(HttpStatusCode.Unauthorized)
                    },
                ).resolve("s", 0.3, 0)
            assertFalse(r.fallbackReason!!.contains("SECRET"))
            assertFalse(r.fallbackReason.contains("Bearer"))
        }

    @Test
    fun `configured flag reflects config`() {
        assertTrue(LlmCostResolver(cfg(), jsonEngine("{}")).configured)
    }

    // ========== callsPath re-poll schedule ==========

    @Test
    fun `callsPath unset keeps the single lookup even when the figure is partial`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.1649,"calls":1}"""))
            val r = LlmCostResolver(cfg(), engine, pollMs).resolve("s", 9.9, 5)
            assertEquals(ResolvedCostShape(0.1649, CostSource.SESSION, null), r.shape())
            assertEquals(1, stamps.size)
        }

    @Test
    fun `an already complete session is accepted on the first attempt`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.2441,"calls":5}"""))
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 9.9, 5)
            assertEquals(ResolvedCostShape(0.2441, CostSource.SESSION, null), r.shape())
            assertEquals(1, stamps.size)
        }

    @Test
    fun `polling stops at the attempt that reaches the expected call count`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.1649,"calls":1}""",
                        """{"spend":0.2,"calls":3}""",
                        """{"spend":0.2441,"calls":5}""",
                        """{"spend":9.99,"calls":5}""",
                    ),
                )
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 9.9, 5)
            assertEquals(ResolvedCostShape(0.2441, CostSource.SESSION, null), r.shape())
            assertEquals(3, stamps.size)
            // The second attempt waited out the second slot. Generous margin: delay() never
            // returns early, but the clock granularity of a loaded CI box can shave a little.
            val gapMs = (stamps[1] - stamps[0]) / 1_000_000
            assertTrue(gapMs >= 40, "second attempt came ${gapMs}ms after the first, expected the 50ms slot")
        }

    @Test
    fun `a session that never finishes ingesting is charged the best partial figure, marked incomplete`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.1649,"calls":1}""",
                        """{"spend":0.2,"calls":2}""",
                        """{"spend":0.21,"calls":3}""",
                        """{"spend":0.22,"calls":3}""",
                    ),
                )
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 9.9, 5)
            assertEquals(ResolvedCostShape(0.22, CostSource.SESSION, "incomplete: 3 of 5 calls ingested"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `a later reading with fewer ingested calls never replaces the best partial`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.21,"calls":3}""",
                        """{"spend":0.10,"calls":2}""",
                    ),
                )
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 9.9, 5)
            assertEquals(ResolvedCostShape(0.21, CostSource.SESSION, "incomplete: 3 of 5 calls ingested"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `a transient failure is retried by the schedule`() =
        runBlocking {
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    if (attempts == 1) {
                        respondError(HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond(
                            """{"spend":0.2441,"calls":5}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 9.9, 5)
            assertEquals(ResolvedCostShape(0.2441, CostSource.SESSION, null), r.shape())
            assertEquals(2, attempts)
        }

    @Test
    fun `when every attempt fails the CLI figure is charged with the last reason`() =
        runBlocking {
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    respondError(HttpStatusCode.NotFound)
                }
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 0.3, 5)
            assertEquals(ResolvedCostShape(0.3, CostSource.CLI, "http 404"), r.shape())
            assertEquals(4, attempts)
        }

    @Test
    fun `an attempt with no assistant ids accepts the first response`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.0,"calls":0}"""))
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 9.9, 0)
            assertEquals(ResolvedCostShape(0.0, CostSource.SESSION, null), r.shape())
            assertEquals(1, stamps.size)
        }

    @Test
    fun `a caller's cancellation during a poll wait propagates`() {
        // Same shape as the timeout test above: assertFailsWith's value must not become
        // this method's return type, or JUnit skips the test.
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.1649,"calls":1}"""))
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(300) {
                    LlmCostResolver(cfg(callsPath = "$.calls"), engine, listOf(0L, 5_000L)).resolve("s", 9.9, 5)
                }
            }
            assertEquals(1, stamps.size)
        }
    }

    @Test
    fun `a response without the calls path is a failed attempt, not zero ingested`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.1649}"""))
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 9.9, 5)
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "callsPath yielded no number"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `a non-numeric calls value is a failed attempt`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.1649,"calls":"three"}"""))
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 9.9, 5)
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "callsPath yielded no number"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `a null session id with callsPath set falls back at once, without a request or a wait`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.1,"calls":1}"""))
            val startedAt = System.nanoTime()
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve(null, 0.5, 3)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertEquals(ResolvedCostShape(0.5, CostSource.CLI, "no session id in output"), r.shape())
            assertEquals(0, stamps.size)
            assertTrue(elapsedMs < pollMs[1], "resolve took ${elapsedMs}ms, expected no wait at all")
        }

    // ========== no call count (expectedCalls = null, e.g. Codex): stable-figure rule ==========

    @Test
    fun `without a call count the figure is accepted once a second reading confirms it`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.004,"calls":1}""",
                        """{"spend":0.0097,"calls":2}""",
                        """{"spend":0.0097,"calls":2}""",
                        """{"spend":9.99,"calls":9}""",
                    ),
                )
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 0.0, null)
            assertEquals(ResolvedCostShape(0.0097, CostSource.SESSION, null), r.shape())
            assertEquals(3, stamps.size)
        }

    @Test
    fun `without a call count the first reading alone is never accepted`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.0097,"calls":2}"""))
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 0.0, null)
            assertEquals(ResolvedCostShape(0.0097, CostSource.SESSION, null), r.shape())
            assertEquals(2, stamps.size)
        }

    @Test
    fun `without a call count repeated zero-call readings never settle`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.0,"calls":0}"""))
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 0.0, null)
            assertEquals(ResolvedCostShape(0.0, CostSource.SESSION, "incomplete: figure not stable across polls"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `without a call count the same call count at a different cost is not stable`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.004,"calls":2}""",
                        """{"spend":0.0097,"calls":2}""",
                        """{"spend":0.0097,"calls":2}""",
                    ),
                )
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 0.0, null)
            assertEquals(ResolvedCostShape(0.0097, CostSource.SESSION, null), r.shape())
            assertEquals(3, stamps.size)
        }

    @Test
    fun `without a call count a figure still moving when the schedule ends is charged, marked incomplete`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.01,"calls":1}""",
                        """{"spend":0.02,"calls":2}""",
                        """{"spend":0.03,"calls":3}""",
                        """{"spend":0.04,"calls":4}""",
                    ),
                )
            val r = LlmCostResolver(cfg(callsPath = "$.calls"), engine, pollMs).resolve("s", 0.0, null)
            assertEquals(ResolvedCostShape(0.04, CostSource.SESSION, "incomplete: figure not stable across polls"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `without a call count and without callsPath the single lookup is kept`() =
        runBlocking {
            val (engine, stamps) = scriptedEngine(listOf("""{"spend":0.0097,"calls":2}"""))
            val r = LlmCostResolver(cfg(), engine, pollMs).resolve("s", 0.0, null)
            assertEquals(ResolvedCostShape(0.0097, CostSource.SESSION, null), r.shape())
            assertEquals(1, stamps.size)
        }

    // ========== request-window pricing (--continue resumed sessions) ==========

    @Test
    fun `two attempts in one session are each charged only their own window rows`() =
        runBlocking {
            // One session, two runs: the endpoint's rows carry both runs' requests and its
            // session-cumulative spend is the total. Each attempt must be priced from its own
            // window, so the two charges sum to $.spend.
            val body =
                """
                {"spend":0.30,"calls":3,"requests":[
                  {"started":"2026-10-06T10:00:00Z","spend":0.10},
                  {"started":"2026-10-06T10:00:20Z","spend":0.05},
                  {"started":"2026-10-06T10:05:00Z","spend":0.15}
                ]}
                """.trimIndent()
            val engine = jsonEngine(body)
            val r1 =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 0.0, 2, ms("2026-10-06T10:00:00Z"), ms("2026-10-06T10:01:00Z"))
            val r2 =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 0.0, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(CostSource.SESSION, r1.source)
            assertEquals(CostSource.SESSION, r2.source)
            assertNull(r1.fallbackReason)
            assertNull(r2.fallbackReason)
            assertEquals(0.15, r1.costUsd, 1e-9)
            assertEquals(0.15, r2.costUsd, 1e-9)
            assertEquals(0.30, r1.costUsd + r2.costUsd, 1e-9)
        }

    @Test
    fun `a window with only earlier attempts' calls is Partial, never Complete`() =
        runBlocking {
            // The second attempt made 2 calls, but by the first poll the endpoint has only
            // ingested the earlier run's row inside the window (grace 0, so the third row is
            // outside). Session-wide $.calls is 3, which alone would satisfy expectedCalls and
            // accept the figure early — the window count must not.
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.30,"calls":3,"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.15}]}""",
                        """{"spend":0.32,"calls":4,"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.15},{"started":"2026-10-06T10:05:10Z","spend":0.02}]}""",
                    ),
                )
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 0.0, 2, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(CostSource.SESSION, r.source)
            assertNull(r.fallbackReason)
            assertEquals(0.17, r.costUsd, 1e-9)
            assertEquals(2, stamps.size)
        }

    @Test
    fun `a window that never ingests every call is charged the best partial, marked incomplete`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.30,"calls":3,"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.15}]}""",
                    ),
                )
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 0.0, 3, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(0.15, CostSource.SESSION, "incomplete: 1 of 3 calls ingested"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `a window with rows but none inside it falls back with no rows`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.30,"calls":3,"requests":[{"started":"2026-10-06T10:00:00Z","spend":0.10}]}""",
                    ),
                )
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 9.9, 1, ms("2026-10-06T11:00:00Z"), ms("2026-10-06T11:01:00Z"))
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "requestsPath yielded no rows"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `a window grace admits a row just outside the raw boundaries`() =
        runBlocking {
            // Row starts 2 s before the attempt; with a 5 s grace it is in-window.
            val engine = jsonEngine("""{"spend":0.1,"requests":[{"started":"2026-10-06T10:04:58Z","spend":0.1}]}""")
            val r =
                LlmCostResolver(windowCfg(windowGraceMs = 5_000), engine, pollMs)
                    .resolve("s", 0.0, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(0.1, CostSource.SESSION, null), r.shape())
        }

    @Test
    fun `rows without a readable timestamp fall back with the timestamp reason`() =
        runBlocking {
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"spend":0.30,"requests":[{"started":"not-a-date","spend":0.1},{"spend":0.2}]}""",
                    ),
                )
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 9.9, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "requestsPath rows have no readable timestamp"), r.shape())
            assertEquals(4, stamps.size)
        }

    @Test
    fun `one unreadable row timestamp refuses the whole window, not just that row`() =
        runBlocking {
            // CWE-840: skipping the unreadable row would drop a
            // possibly in-window charge silently and the poll could still settle Complete —
            // the window is refused instead, like the all-unreadable case.
            val engine =
                jsonEngine(
                    """{"spend":0.30,"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.15},{"spend":0.2}]}""",
                )
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 9.9, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "requestsPath rows have no readable timestamp"), r.shape())
        }

    @Test
    fun `a numeric row timestamp in seconds or milliseconds is placed in the window`() =
        runBlocking {
            // SERVER.md and the schema advertise epoch milliseconds/seconds alongside
            // ISO-8601: a magnitude below 1e11 is seconds, at or above it milliseconds.
            val sec = ms("2026-10-06T10:05:00Z") / 1000
            val engine =
                jsonEngine(
                    """{"spend":0.30,"requests":[{"started":$sec,"spend":0.10},{"started":${sec * 1000L},"spend":0.05}]}""",
                )
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 0.0, 2, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(0.15, r.costUsd, 1e-9)
            assertEquals(CostSource.SESSION, r.source)
            assertNull(r.fallbackReason)
        }

    @Test
    fun `a negative or non-finite numeric row timestamp is refused`() =
        runBlocking {
            // Both rows alone would make the window unplaceable; either one must refuse the
            // window rather than leave the row uncharged.
            for (bad in listOf("-1", "1e999")) {
                val body = """{"spend":0.30,"requests":[{"started":$bad,"spend":0.15}]}"""
                val r =
                    LlmCostResolver(windowCfg(), jsonEngine(body), pollMs)
                        .resolve("s", 9.9, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
                assertEquals(
                    ResolvedCostShape(9.9, CostSource.CLI, "requestsPath rows have no readable timestamp"),
                    r.shape(),
                    "started=$bad",
                )
            }
        }

    @Test
    fun `a zone-less row timestamp is refused rather than read as UTC`() =
        runBlocking {
            // A local date-time carries no zone, so placing it in an attempt's window would be a
            // guess; the row must not be priced from. SERVER.md / ADR-0061 say such a row is
            // refused rather than misattributed.
            val engine = jsonEngine("""{"spend":0.30,"requests":[{"started":"2026-10-06T10:05:00","spend":0.15}]}""")
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 9.9, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "requestsPath rows have no readable timestamp"), r.shape())
        }

    @Test
    fun `an unreadable row spend falls back with no number`() =
        runBlocking {
            val engine = jsonEngine("""{"spend":0.30,"requests":[{"started":"2026-10-06T10:05:00Z","spend":"lots"}]}""")
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 9.9, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "requestsPath yielded no number"), r.shape())
        }

    @Test
    fun `a requestsPath that matches nothing falls back with no rows`() =
        runBlocking {
            val engine = jsonEngine("""{"spend":0.30}""")
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 9.9, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "requestsPath yielded no rows"), r.shape())
        }

    @Test
    fun `without the attempt window the window config falls back rather than misprice`() =
        runBlocking {
            val engine = jsonEngine("""{"spend":0.30,"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.15}]}""")
            val r = LlmCostResolver(windowCfg(), engine, pollMs).resolve("s", 9.9, 1)
            assertEquals(ResolvedCostShape(9.9, CostSource.CLI, "attempt window unavailable"), r.shape())
        }

    @Test
    fun `a single attempt session is priced from its window like any other`() =
        runBlocking {
            val engine = jsonEngine("""{"spend":0.10,"calls":1,"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.10}]}""")
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 0.0, 1, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(0.10, CostSource.SESSION, null), r.shape())
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun `a window-priced attempt with no call count settles on two agreeing readings`() =
        runBlocking {
            // A harness with no call count (expectedCalls = null): the window figure is accepted
            // once two successive readings agree, the same rule as the callsPath path.
            val (engine, stamps) =
                scriptedEngine(
                    listOf(
                        """{"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.10}]}""",
                        """{"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.10}]}""",
                        """{"requests":[{"started":"2026-10-06T10:05:00Z","spend":9.99}]}""",
                    ),
                )
            val r =
                LlmCostResolver(windowCfg(), engine, pollMs)
                    .resolve("s", 0.0, null, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(0.10, CostSource.SESSION, null), r.shape())
            assertEquals(2, stamps.size)
        }

    @Test
    fun `without the window keys behaviour is byte-for-byte the session total`() =
        runBlocking {
            // Same body, no window keys: the session-cumulative costPath figure is charged, and
            // only one request is issued (no callsPath either).
            val body = """{"spend":0.30,"calls":3,"requests":[{"started":"2026-10-06T10:05:00Z","spend":0.15}]}"""
            val engine = jsonEngine(body)
            val r =
                LlmCostResolver(cfg(), engine, pollMs)
                    .resolve("s", 9.9, 2, ms("2026-10-06T10:05:00Z"), ms("2026-10-06T10:06:00Z"))
            assertEquals(ResolvedCostShape(0.30, CostSource.SESSION, null), r.shape())
            assertEquals(1, engine.requestHistory.size)
        }

    private data class ResolvedCostShape(
        val cost: Double,
        val source: CostSource,
        val reason: String?,
    )

    private fun homer.app.models.ResolvedCost.shape() = ResolvedCostShape(costUsd, source, fallbackReason)
}
