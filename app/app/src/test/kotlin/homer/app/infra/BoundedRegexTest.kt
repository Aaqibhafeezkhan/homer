package homer.app.infra

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

class BoundedRegexTest {
    private lateinit var logger: LogbackLogger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setup() {
        logger = LoggerFactory.getLogger(BoundedRegexTest::class.java) as LogbackLogger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun teardown() {
        logger.detachAppender(appender)
    }

    private fun warnings(): List<String> = appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }

    @Test
    fun `find returns a match for a normal pattern`() {
        val m = BoundedRegex.find(Regex("h(el+)o"), "well, hello there")
        assertNotNull(m)
        assertEquals("hello", m.value)
    }

    @Test
    fun `find returns null when there is no match`() {
        assertNull(BoundedRegex.find(Regex("xyz"), "hello"))
    }

    @Test
    fun `matches returns true and false correctly`() {
        assertTrue(BoundedRegex.matches(Regex("[a-z]+"), "abc"))
        assertFalse(BoundedRegex.matches(Regex("[a-z]+"), "abc123"))
    }

    @Test
    fun `catastrophic backtracking is aborted by the timeout`() {
        // The JVM linearizes simple nested quantifiers, but a backreference cannot be
        // linearized: (a+)+\1b against a run of 'a' with no 'b' backtracks exponentially.
        val evil = Regex("(a+)+\\1b")
        val input = "a".repeat(30)
        assertThrows<BoundedRegex.RegexTimeoutException> {
            BoundedRegex.find(evil, input, timeoutMs = 50)
        }
    }

    @Test
    fun `concurrent catastrophic matches beyond the pool bound are all shed within the bound`() {
        // More callers than workers + queue, so the pool's own bound is exercised too:
        // every caller comes back (shed or timed out) instead of accumulating.
        val callers = Executors.newFixedThreadPool(CALLERS)
        try {
            val started = System.nanoTime()
            val outcomes =
                (1..CALLERS)
                    .map {
                        callers.submit(
                            Callable {
                                try {
                                    BoundedRegex.find(Regex("(a+)+\\1b"), "a".repeat(30), timeoutMs = 50)
                                    "matched"
                                } catch (_: BoundedRegex.RegexTimeoutException) {
                                    "shed"
                                }
                            },
                        )
                    }.map { it.get(30, TimeUnit.SECONDS) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(CALLERS, outcomes.count { it == "shed" })
            assertTrue(elapsedMs < 10_000, "all $CALLERS callers should be shed, took ${elapsedMs}ms")
            // The workers are free again, not left spinning on the shed matches.
            assertEquals("hello", BoundedRegex.find(Regex("h(el+)o"), "well, hello there", timeoutMs = 50)?.value)
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `findOrOmit returns the match for a normal pattern`() {
        val match = BoundedRegex.findOrOmit("h(el+)o", "well, hello there", "GREETING", logger)
        assertNotNull(match)
        assertEquals("hello", match.value)
    }

    @Test
    fun `findOrOmit returns null for a pattern that matches nothing`() {
        assertNull(BoundedRegex.findOrOmit("xyz", "hello", "GREETING", logger))
        assertTrue(warnings().isEmpty(), "a plain non-match is not a timeout")
    }

    @Test
    fun `findOrOmit omits the value on timeout and logs only the env name`() {
        val subject = "a".repeat(30)

        val match = BoundedRegex.findOrOmit("(a+)+\\1b", subject, "SECRET_VAR", logger)

        assertNull(match)
        val logged = warnings()
        assertTrue(logged.any { it.contains("SECRET_VAR") }, "expected a WARN naming the env var, got $logged")
        assertTrue(logged.none { it.contains(subject) }, "the WARN must not carry the input value: $logged")
    }

    @Test
    fun `findOrOmit omits the value when the pattern does not compile`() {
        assertNull(BoundedRegex.findOrOmit("(", "anything", "SECRET_VAR", logger))
        assertTrue(warnings().any { it.contains("SECRET_VAR") }, "expected a WARN naming the env var")
    }

    @Test
    fun `findOrOmit does not report a shed match when the pattern does not compile`() {
        // Only a shed match (timeout or saturation) trips the shed-then-skip cap; an
        // uncompilable pattern omits its own value and must leave the cap untouched so
        // a caller does not drop the later, valid regex transformers of its list.
        var shed = false

        assertNull(BoundedRegex.findOrOmit("(", "anything", "SECRET_VAR", logger) { shed = true })

        assertFalse(shed, "an uncompilable pattern is not a shed match")
    }

    @Test
    fun `findOrOmit reports a shed match through onTimeout, and a plain miss not at all`() {
        var timedOut = false

        assertNull(BoundedRegex.findOrOmit("xyz", "hello", "SECRET_VAR", logger) { timedOut = true })
        assertFalse(timedOut, "a non-match is not a shed match")

        assertNull(BoundedRegex.findOrOmit("(a+)+\\1b", "a".repeat(30), "SECRET_VAR", logger) { timedOut = true })
        assertTrue(timedOut, "a timed-out match must be reported to the caller")
    }

    @Test
    fun `a pool-saturation shed is logged as shed, not as a timeout`() {
        // Fill the pool exactly (workers + queue) with matches blocked on a latch, so no
        // occupier can finish before the test releases it: saturation cannot drain away
        // mid-test however loaded the runner is. The findOrOmit call afterwards is then
        // rejected outright and must report saturation rather than diagnose a slow pattern.
        // Saturation is waited for by observing a shed probe, not by sleeping: a fixed
        // settle wait races the occupier submissions under load and the probe could be
        // served by a still-free worker. runCatching keeps the occupiers' futures from
        // throwing on drain.
        val openPool = CountDownLatch(1)
        val occupiers = Executors.newFixedThreadPool(WORKERS_PLUS_QUEUE)
        try {
            val futures =
                (1..WORKERS_PLUS_QUEUE).map {
                    occupiers.submit(
                        Callable { runCatching { BoundedRegex.find(Regex("a+"), LatchedCharSequence(openPool), timeoutMs = 30_000) } },
                    )
                }
            // Poll until a probe submission is shed: the pool then stays saturated, every
            // occupier holding its worker or queue slot until the latch opens. A probe that
            // waits past its own bound in a queue slot that is not yet full is retried —
            // only an outright rejection proves saturation. A cancelled probe keeps its
            // queue slot until a worker polls it, so the first shed probe can also free a
            // slot again: the findOrOmit assertion observes the shed through the same
            // retry, instead of trusting one fixed submission to be rejected outright.
            waitUntilSaturated()
            assertSaturatedPoolShedsAsShed()
            // Drain the occupiers so the shared pool is free again for the other tests.
            openPool.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            openPool.countDown()
            occupiers.shutdownNow()
        }
    }

    /** Submits probes until one is rejected outright, which proves the pool saturated. */
    private fun waitUntilSaturated() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            try {
                BoundedRegex.find(Regex("x"), "x", timeoutMs = 50)
            } catch (_: BoundedRegex.RegexShedException) {
                return
            } catch (_: BoundedRegex.RegexTimeoutException) {
                // queued past its bound but not yet rejected outright: retry
            }
            if (System.nanoTime() > deadline) throw AssertionError("the regex pool never saturated")
        }
    }

    /**
     * Asserts a findOrOmit submission past the saturated pool reports shed, not a slow
     * pattern. The submission itself is retried under the same deadline: a worker that
     * drains a cancelled probe between tasks frees a queue slot, so one fixed call can
     * be served a slot and time out there instead of being rejected outright.
     */
    private fun assertSaturatedPoolShedsAsShed() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            appender.list.clear()
            val match = BoundedRegex.findOrOmit("h(el+)o", "well, hello there", "SATURATED_VAR", logger)
            val logged = warnings()
            if (logged.any { it.contains("SATURATED_VAR") && it.contains("shed") }) {
                assertNull(match, "a submission past the pool and queue is shed")
                assertTrue(
                    logged.none { it.contains("exceeded the time bound") },
                    "a shed match is not a timeout: $logged",
                )
                return
            }
            if (System.nanoTime() > deadline) {
                throw AssertionError("the saturated pool never shed a submission, last warnings: $logged")
            }
        }
    }

    private companion object {
        /** More than the pool's worker threads plus its queue, so submissions are shed too. */
        private const val CALLERS = 32

        /** Exactly the pool's worker threads (4) plus its queue capacity (16): full, none rejected yet. */
        private const val WORKERS_PLUS_QUEUE = 20
    }
}

/**
 * Input whose every character read blocks on [latch], so a match started on it cannot
 * finish — nor fail by timeout, within the occupier's long bound — before the latch opens.
 */
private class LatchedCharSequence(
    private val latch: CountDownLatch,
) : CharSequence {
    override val length = 1

    override fun get(index: Int): Char {
        latch.await()
        return 'a'
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = this

    override fun toString() = "a"
}
