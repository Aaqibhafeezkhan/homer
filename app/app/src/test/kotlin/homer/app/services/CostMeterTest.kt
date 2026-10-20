package homer.app.services

import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.ValidationException
import homer.app.models.CostLimits
import homer.app.services.persistence.CostRepository
import homer.app.services.persistence.DatabaseService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class CostMeterTest {
    /**
     * Mutable clock under test control so we can simulate window sliding without
     * waiting in real time.
     */
    private class FakeClock(
        initialEpochSec: Long = 1_700_000_000L, // 2023-11-14T22:13:20Z
    ) : Clock {
        var nowSec: Long = initialEpochSec

        override fun now(): Instant = Instant.fromEpochSeconds(nowSec)
    }

    private lateinit var repo: CostRepository

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        val db = DatabaseService(dir.resolve("d.db").toString(), LoggerFactory.getLogger("t"))
        db.migrate()
        repo = CostRepository(db)
    }

    @Test
    fun `recordCost accumulates within window and snapshot reflects sum`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, clock = clock)
            meter.recordCost("a", 0.10)
            meter.recordCost("a", 0.05)
            meter.recordCost("b", 0.20)

            val snap = meter.snapshot()
            assertEquals(0.35, snap.globalUsdLast60s, 1e-9)
            assertEquals(0.15, snap.perAgentUsdLast24h["a"]!!, 1e-9)
            assertEquals(0.20, snap.perAgentUsdLast24h["b"]!!, 1e-9)
        }

    @Test
    fun `entries older than 60s drop out of the global window`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, clock = clock)
            meter.recordCost("a", 1.0) // recorded at t=0
            clock.nowSec += 30
            meter.recordCost("a", 2.0) // recorded at t=30

            // At t=30: both entries within 60 s window
            assertEquals(3.0, meter.snapshot().globalUsdLast60s, 1e-9)

            // Slide forward another 40 s — first entry (recorded 70 s ago) drops.
            clock.nowSec += 40
            assertEquals(2.0, meter.snapshot().globalUsdLast60s, 1e-9)
        }

    @Test
    fun `entries older than 24h drop out of the per-agent window`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, clock = clock)
            meter.recordCost("a", 1.0) // t=0
            clock.nowSec += 12 * 3600 // +12 h
            meter.recordCost("a", 2.0) // t=12h

            // At t=12h: both within 24 h window for agent
            assertEquals(3.0, meter.snapshot().perAgentUsdLast24h["a"]!!, 1e-9)

            // Slide another 13 h — first entry is now 25 h old and drops.
            clock.nowSec += 13 * 3600
            assertEquals(2.0, meter.snapshot().perAgentUsdLast24h["a"]!!, 1e-9)
        }

    @Test
    fun `checkCaps throws when global window meets or exceeds cap`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, limits = CostLimits(globalUsdPerMin = 1.0), clock = clock)
            meter.recordCost("a", 0.5)
            meter.recordCost("a", 0.5)

            val ex =
                assertThrows<CostCapExceededException> {
                    meter.checkCaps("a")
                }
            assertEquals("globalUsdPerMin", ex.capName)
            assertEquals(1.0, ex.limitUsd, 1e-9)
            assertEquals(1.0, ex.currentUsd, 1e-9)
        }

    @Test
    fun `checkCaps throws when per-agent window meets or exceeds cap`() =
        runTest {
            val clock = FakeClock()
            val meter =
                CostMeter(
                    repository = repo,
                    limits = CostLimits(perAgentUsdPerDay = mapOf("expensive" to 10.0)),
                    clock = clock,
                )
            meter.recordCost("expensive", 7.5)
            meter.recordCost("expensive", 2.5)

            val ex =
                assertThrows<CostCapExceededException> {
                    meter.checkCaps("expensive")
                }
            assertTrue(ex.capName.contains("expensive"))
            assertEquals(10.0, ex.limitUsd, 1e-9)
        }

    @Test
    fun `checkCaps does not throw for an uncapped agent even if global is configured`() =
        runTest {
            val clock = FakeClock()
            val meter =
                CostMeter(
                    repository = repo,
                    limits =
                        CostLimits(
                            globalUsdPerMin = 5.0,
                            perAgentUsdPerDay = mapOf("metered" to 1.0),
                        ),
                    clock = clock,
                )
            // Spend $0.50 under "free" — global below 5.0 cap, no per-agent entry for "free"
            meter.recordCost("free", 0.50)
            meter.checkCaps("free") // does not throw
        }

    @Test
    fun `checkCaps trips global before agent path is even consulted`() =
        runTest {
            val clock = FakeClock()
            val meter =
                CostMeter(
                    repository = repo,
                    limits =
                        CostLimits(
                            globalUsdPerMin = 1.0,
                            perAgentUsdPerDay = mapOf("a" to 1000.0),
                        ),
                    clock = clock,
                )
            meter.recordCost("a", 1.5)
            val ex =
                assertThrows<CostCapExceededException> {
                    meter.checkCaps("a")
                }
            assertEquals("globalUsdPerMin", ex.capName)
        }

    @Test
    fun `recordCost ignores zero and negative deltas`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, clock = clock)
            meter.recordCost("a", 0.0)
            meter.recordCost("a", -1.0)
            assertEquals(0.0, meter.snapshot().globalUsdLast60s, 1e-9)
            assertNull(meter.snapshot().perAgentUsdLast24h["a"])
        }

    @Test
    fun `null caps disable enforcement`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, limits = CostLimits(), clock = clock)
            meter.recordCost("a", 1_000_000.0)
            meter.checkCaps("a") // no caps -> no throw
        }

    @Test
    fun `expired entries are pruned eagerly during checkCaps`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, limits = CostLimits(globalUsdPerMin = 1.0), clock = clock)
            meter.recordCost("a", 5.0) // would trip cap immediately

            // Slide past the 60 s window so the entry is stale
            clock.nowSec += 120
            meter.checkCaps("a") // does not throw, entry pruned
            assertEquals(0.0, meter.snapshot().globalUsdLast60s, 1e-9)
        }

    @Test
    fun `recordCost notes a global overshoot when the window passes the cap (audit P2 #21)`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, limits = CostLimits(globalUsdPerMin = 1.0), clock = clock)
            // Two runs both pass a pre-flight check at low spend, then collectively
            // push the 60 s window past the 1.0 cap — the accepted soft-throttle race.
            meter.recordCost("a", 0.6)
            assertEquals(0L, meter.overshootCount) // 0.6 <= 1.0, no overshoot yet
            meter.recordCost("a", 0.6) // window now 1.2 > 1.0
            assertTrue(
                meter.overshootCount >= 1,
                "expected an overshoot once the window exceeded the cap, got ${meter.overshootCount}",
            )
        }

    @Test
    fun `recordCost does not note an overshoot when the window lands exactly on the cap`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, limits = CostLimits(globalUsdPerMin = 1.0), clock = clock)
            meter.recordCost("a", 0.5)
            meter.recordCost("a", 0.5) // exactly 1.0 == cap, not strictly over
            assertEquals(0L, meter.overshootCount)
        }

    @Test
    fun `recordCost notes a per-agent overshoot when that window passes its cap`() =
        runTest {
            val clock = FakeClock()
            val meter =
                CostMeter(
                    repository = repo,
                    limits = CostLimits(perAgentUsdPerDay = mapOf("x" to 10.0)),
                    clock = clock,
                )
            meter.recordCost("x", 8.0)
            assertEquals(0L, meter.overshootCount)
            meter.recordCost("x", 5.0) // 13.0 > 10.0
            assertTrue(
                meter.overshootCount >= 1,
                "expected a per-agent overshoot, got ${meter.overshootCount}",
            )
        }

    @Test
    fun `recordCost notes no overshoot when no caps are configured`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, limits = CostLimits(), clock = clock)
            meter.recordCost("a", 1_000_000.0)
            assertEquals(0L, meter.overshootCount)
        }

    @Test
    fun `snapshot reports configured caps even when no spend recorded`() =
        runTest {
            val clock = FakeClock()
            val meter =
                CostMeter(
                    repository = repo,
                    limits =
                        CostLimits(
                            globalUsdPerMin = 4.0,
                            perAgentUsdPerDay = mapOf("a" to 99.0),
                        ),
                    clock = clock,
                )
            val snap = meter.snapshot()
            assertEquals(4.0, snap.globalCapUsdPerMin)
            assertEquals(99.0, snap.perAgentCapUsdPerDay["a"])
            assertEquals(0.0, snap.globalUsdLast60s, 1e-9)
        }

    @Test
    fun `windows survive meter re-creation against the same database`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, limits = CostLimits(globalUsdPerMin = 1.0), clock = clock)
            meter.recordCost("a", 5.0)

            // A "restarted" meter over the same repository still sees the spend.
            val restarted = CostMeter(repository = repo, limits = CostLimits(globalUsdPerMin = 1.0), clock = clock)
            assertThrows<CostCapExceededException> { restarted.checkCaps("a") }
            assertEquals(5.0, restarted.snapshot().globalUsdLast60s, 1e-9)
        }

    @Test
    fun `monthly buckets by UTC month and defaults to the current one`() =
        runTest {
            val clock = FakeClock() // November 2023 (UTC)
            val meter = CostMeter(repository = repo, clock = clock)
            meter.recordCost("a", 1.0)
            clock.nowSec = LocalDateTime.of(2023, 12, 1, 0, 0).toEpochSecond(ZoneOffset.UTC)
            meter.recordCost("a", 2.0)
            meter.recordCost("b", 4.0)

            val nov = meter.monthly("2023-11")
            assertEquals("2023-11", nov.month)
            assertEquals(mapOf("a" to 1.0), nov.perAgentUsd)
            assertEquals(1.0, nov.totalUsd, 1e-9)

            val current = meter.monthly(null) // clock now sits in December
            assertEquals("2023-12", current.month)
            assertEquals(6.0, current.totalUsd, 1e-9)
            assertEquals(listOf("2023-12", "2023-11"), current.availableMonths)
        }

    @Test
    fun `monthly returns empty stats for a month with no entries`() =
        runTest {
            val meter = CostMeter(repository = repo, clock = FakeClock())
            val m = meter.monthly("2020-01")
            assertEquals("2020-01", m.month)
            assertTrue(m.perAgentUsd.isEmpty())
            assertEquals(0.0, m.totalUsd, 1e-9)
        }

    @Test
    fun `monthly rejects a malformed month parameter`() =
        runTest {
            val meter = CostMeter(repository = repo, clock = FakeClock())
            assertThrows<ValidationException> { meter.monthly("2026-7") }
            assertThrows<ValidationException> { meter.monthly("2026-13") }
            assertThrows<ValidationException> { meter.monthly("garbage") }
        }

    @Test
    fun `totals sums the full history per agent`() =
        runTest {
            val clock = FakeClock()
            val meter = CostMeter(repository = repo, clock = clock)
            meter.recordCost("a", 1.0)
            clock.nowSec += 90L * 24 * 3600 // three months later — far outside every window
            meter.recordCost("a", 2.0)
            meter.recordCost("b", 0.5)

            val t = meter.totals()
            assertEquals(3.0, t.perAgentUsd["a"]!!, 1e-9)
            assertEquals(0.5, t.perAgentUsd["b"]!!, 1e-9)
            assertEquals(3.5, t.totalUsd, 1e-9)
        }
}
