package homer.app.services

import homer.app.exceptions.QueryParamValidationException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.models.CronConfig
import homer.app.models.Param
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CronSchedulerServiceTest {
    private lateinit var agentsService: AgentsService
    private lateinit var agentRunStarter: AgentRunStarter
    private lateinit var agentInputResolver: AgentInputResolver
    private lateinit var maintenanceMode: MaintenanceModeService
    private lateinit var service: CronSchedulerService

    @BeforeEach
    fun setup() {
        agentsService = mockk()
        agentRunStarter = mockk()
        agentInputResolver = mockk()
        // Relaxed: maintenance mode is off, which is what every pre-existing case assumes.
        maintenanceMode = mockk(relaxed = true)
        service =
            CronSchedulerService(
                agentsService = agentsService,
                agentRunStarter = agentRunStarter,
                agentInputResolver = agentInputResolver,
                maintenanceMode = maintenanceMode,
                logger = TestFixtures.mockLogger(),
            )
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // --- matchesCron ---

    @Test
    fun `matchesCron returns true when expression matches current time`() {
        val config = CronConfig(expression = "* * * * *", timezone = "UTC")
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        assertTrue(service.matchesCron(config, now))
    }

    @Test
    fun `matchesCron returns false when expression does not match current minute`() {
        // Use a fixed time at minute 30, and a cron that only fires at minute 15
        val config = CronConfig(expression = "15 * * * *", timezone = "UTC")
        val now = ZonedDateTime.of(2025, 1, 1, 12, 30, 0, 0, ZoneId.of("UTC"))
        assertFalse(service.matchesCron(config, now))
    }

    @Test
    fun `matchesCron returns true when expression matches given time`() {
        val config = CronConfig(expression = "30 12 * * *", timezone = "UTC")
        val now = ZonedDateTime.of(2025, 1, 1, 12, 30, 0, 0, ZoneId.of("UTC"))
        assertTrue(service.matchesCron(config, now))
    }

    @Test
    fun `matchesCron handles timezone correctly`() {
        // UTC time is 08:00, but in America/New_York (UTC-5) it's 03:00
        val config = CronConfig(expression = "0 3 * * *", timezone = "America/New_York")
        val nowUtc = ZonedDateTime.of(2025, 1, 1, 8, 0, 0, 0, ZoneId.of("UTC"))
        assertTrue(service.matchesCron(config, nowUtc))
    }

    // --- getNextRunTime ---

    @Test
    fun `getNextRunTime returns non-null next execution for wildcard expression`() {
        val config = CronConfig(expression = "* * * * *", timezone = "UTC")
        val next = service.getNextRunTime(config)
        assertNotNull(next)
        assertTrue(next.isAfter(ZonedDateTime.now(ZoneId.of("UTC"))))
    }

    @Test
    fun `getNextRunTime returns next execution in the correct timezone`() {
        val config = CronConfig(expression = "0 9 * * *", timezone = "Europe/Warsaw")
        val next = service.getNextRunTime(config)
        assertNotNull(next)
        assertEquals(ZoneId.of("Europe/Warsaw"), next.zone)
    }

    // --- tick ---

    @Test
    fun `tick fires agent whose cron expression matches`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "scheduled_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            every { agentsService.agents } returns mapOf("scheduled_agent" to agent)
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 42

            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            service.tick(now)

            coVerify(exactly = 1) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `tick swallows an invalid schedule instead of killing the scheduler`() =
        runTest {
            // A malformed schedule cannot reach the scheduler through agent load, but
            // if one ever did, the tick filter must not throw and terminate the loop.
            val bad =
                AgentFixtures.simpleAgent(name = "bad_cron").copy(
                    cron = CronConfig(expression = "not a cron", timezone = "UTC"),
                )
            val good =
                AgentFixtures.simpleAgent(name = "good_cron").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            every { agentsService.agents } returns mapOf("bad_cron" to bad, "good_cron" to good)
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 7

            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            service.tick(now) // must not throw

            // The valid agent still fires; the invalid one is skipped, not fatal.
            coVerify(exactly = 1) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `getScheduleStatus returns null next-run for an invalid schedule instead of throwing`() {
        val agent =
            AgentFixtures.simpleAgent(name = "bad_status").copy(
                cron = CronConfig(expression = "not a cron", timezone = "UTC"),
            )
        val status = service.getScheduleStatus(agent)
        assertNotNull(status)
        assertNull(status.nextRunAt, "invalid schedule yields no next run time, not an exception")
    }

    @Test
    fun `tick fires an agent whose required input is covered by a default`() =
        runTest {
            // Regression: an input with a `default` is satisfiable on a tick.
            val agent =
                AgentFixtures.simpleAgent(name = "defaulted_input_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                    inputs = listOf(Param(paramName = "id", envName = "ID", required = false, default = "42")),
                )
            every { agentsService.agents } returns mapOf("defaulted_input_agent" to agent)
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 42

            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            service.tick(now)

            coVerify(exactly = 1) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(42, service.getScheduleStatus(agent)?.lastRunProcessId)
        }

    @Test
    fun `tick skips agent without cron config`() =
        runTest {
            val agent = AgentFixtures.simpleAgent(name = "manual_agent")
            every { agentsService.agents } returns mapOf("manual_agent" to agent)

            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            service.tick(now)

            coVerify(exactly = 0) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `tick skips agent whose cron expression does not match current time`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "daily_agent").copy(
                    cron = CronConfig(expression = "15 * * * *"),
                )
            every { agentsService.agents } returns mapOf("daily_agent" to agent)

            // Use a time that doesn't match minute 15
            val now = ZonedDateTime.of(2025, 1, 1, 12, 30, 0, 0, ZoneId.of("UTC"))
            service.tick(now)

            coVerify(exactly = 0) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `tick fires multiple matching agents`() =
        runTest {
            val agent1 =
                AgentFixtures.simpleAgent(name = "agent_a").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            val agent2 =
                AgentFixtures.simpleAgent(name = "agent_b").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            val agent3 = AgentFixtures.simpleAgent(name = "agent_c") // no cron
            every { agentsService.agents } returns
                mapOf(
                    "agent_a" to agent1,
                    "agent_b" to agent2,
                    "agent_c" to agent3,
                )
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 10

            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            service.tick(now)

            coVerify(exactly = 2) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a tick cancelled while firing stops instead of firing the next agent`() =
        runTest {
            val agent1 = AgentFixtures.simpleAgent(name = "agent_a").copy(cron = CronConfig(expression = "* * * * *"))
            val agent2 = AgentFixtures.simpleAgent(name = "agent_b").copy(cron = CronConfig(expression = "* * * * *"))
            every { agentsService.agents } returns mapOf("agent_a" to agent1, "agent_b" to agent2)
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CancellationException("scheduler stopped")

            assertFailsWith<CancellationException> { service.tick(ZonedDateTime.now(ZoneId.of("UTC"))) }

            coVerify(exactly = 1) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    // --- fireAgent ---

    @Test
    fun `fireAgent starts the run via AgentRunStarter with source cron`() =
        runTest {
            val agentWithoutInputs =
                AgentFixtures.simpleAgent(name = "tracked_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 42

            service.fireAgent(agentWithoutInputs, ZonedDateTime.now(ZoneOffset.UTC))

            coVerify { agentRunStarter.start(agentWithoutInputs, emptyList(), "cron", any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(42, service.getScheduleStatus(agentWithoutInputs)?.lastRunProcessId)
        }

    @Test
    fun `fireAgent stamps owner as system-cron`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "owner_tracked_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 42

            service.fireAgent(agent, ZonedDateTime.now(ZoneOffset.UTC))

            coVerify { agentRunStarter.start(agent, emptyList(), "cron", any(), any(), "system:cron", any(), any(), any(), any()) }
        }

    @Test
    fun `fireAgent with required inputs does not start a run and logs the failure`() =
        runTest {
            val agentWithRequiredInput =
                AgentFixtures.simpleAgent(name = "required_input_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                    inputs = listOf(Param(paramName = "id", envName = "ID", required = true)),
                )
            every { agentInputResolver.resolveFromParams(any(), any(), any()) } throws
                QueryParamValidationException("Parameter 'id' is missing", paramName = "id")
            // the resolveInputs lambda throws inside start; simulate by making start
            // invoke its lambda like the real implementation does.
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), captureLambda()) } coAnswers {
                lambda<suspend () -> Map<String, String>>().captured.invoke()
                42
            }

            service.fireAgent(agentWithRequiredInput, ZonedDateTime.now(ZoneOffset.UTC))

            assertNull(service.getScheduleStatus(agentWithRequiredInput)?.lastRunProcessId)
        }

    @Test
    fun `fireAgent records lastRunInfo after successful execution`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "tracked_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 99

            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            service.fireAgent(agent, now)

            val status = service.getScheduleStatus(agent)
            assertNotNull(status)
            assertEquals(99, status.lastRunProcessId)
            assertEquals(now.toEpochSecond(), status.lastRunAt)
        }

    @Test
    fun `fireAgent logs error and continues when agentRunStarter throws`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "error_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("boom")

            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            // Should not throw
            service.fireAgent(agent, now)

            val status = service.getScheduleStatus(agent)
            assertNotNull(status)
            assertNull(status.lastRunAt)
        }

    // --- fireNow ---

    @Test
    fun `fireNow delegates to the cron fire path and records the process id`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "fire_now_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 77
            val fixedNow = ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)
            val scheduled =
                CronSchedulerService(
                    agentsService = agentsService,
                    agentRunStarter = agentRunStarter,
                    agentInputResolver = agentInputResolver,
                    maintenanceMode = maintenanceMode,
                    logger = TestFixtures.mockLogger(),
                    now = { fixedNow },
                )

            scheduled.fireNow(agent)

            coVerify { agentRunStarter.start(agent, emptyList(), "cron", any(), any(), "system:cron", any(), any(), any(), any()) }
            val status = scheduled.getScheduleStatus(agent)
            assertNotNull(status)
            assertEquals(77, status.lastRunProcessId)
            assertEquals(fixedNow.toEpochSecond(), status.lastRunAt)
        }

    @Test
    fun `fireNow with a required input and no default starts nothing and throws nothing out`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "fire_now_required_input_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                    inputs = listOf(Param(paramName = "id", envName = "ID", required = true)),
                )
            every { agentInputResolver.resolveFromParams(any(), any(), any()) } throws
                QueryParamValidationException("Parameter 'id' is missing", paramName = "id")
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), captureLambda()) } coAnswers {
                lambda<suspend () -> Map<String, String>>().captured.invoke()
                42
            }
            val scheduled =
                CronSchedulerService(
                    agentsService = agentsService,
                    agentRunStarter = agentRunStarter,
                    agentInputResolver = agentInputResolver,
                    maintenanceMode = maintenanceMode,
                    logger = TestFixtures.mockLogger(),
                    now = { ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC) },
                )

            // Must not throw (no 500 to the caller), and no run is recorded.
            scheduled.fireNow(agent)

            assertNull(scheduled.getScheduleStatus(agent)?.lastRunProcessId)
        }

    // --- maintenance mode (ADR-0092) ---

    @Test
    fun `tick fires nothing while maintenance mode is on`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "paused_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            every { agentsService.agents } returns mapOf("paused_agent" to agent)
            every { maintenanceMode.isEnabled() } returns true

            service.tick(ZonedDateTime.now(ZoneId.of("UTC")))

            coVerify(exactly = 0) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertNull(service.getScheduleStatus(agent)?.lastRunProcessId)
        }

    @Test
    fun `a schedule missed while the mode was on is not replayed when it goes off`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "paused_agent").copy(
                    cron = CronConfig(expression = "30 12 * * *"),
                )
            every { agentsService.agents } returns mapOf("paused_agent" to agent)
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 42

            // The scheduled minute passes while the mode is on...
            every { maintenanceMode.isEnabled() } returns true
            service.tick(ZonedDateTime.of(2025, 1, 1, 12, 30, 0, 0, ZoneId.of("UTC")))
            // ...and the next tick, once it is off, does not make it up.
            every { maintenanceMode.isEnabled() } returns false
            service.tick(ZonedDateTime.of(2025, 1, 1, 12, 31, 0, 0, ZoneId.of("UTC")))

            coVerify(exactly = 0) { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `fireNow still reaches the start path while the mode is on`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "fire_now_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            every { maintenanceMode.isEnabled() } returns true
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 77

            service.fireNow(agent)

            // The operator's explicit Run queues at the gate rather than being skipped.
            coVerify(
                exactly = 1,
            ) { agentRunStarter.start(agent, emptyList(), "cron", any(), any(), "system:cron", any(), any(), any(), any()) }
        }

    // --- getScheduleStatus ---

    @Test
    fun `getScheduleStatus returns null for agent with no cron`() {
        val agent = AgentFixtures.simpleAgent()
        assertNull(service.getScheduleStatus(agent))
    }

    @Test
    fun `getScheduleStatus returns nextRunAt for unrun scheduled agent`() {
        val agent =
            AgentFixtures.simpleAgent(name = "unrun_agent").copy(
                cron = CronConfig(expression = "* * * * *"),
            )
        val status = service.getScheduleStatus(agent)
        assertNotNull(status)
        assertNotNull(status.nextRunAt)
        assertNull(status.lastRunAt)
        assertNull(status.lastRunProcessId)
    }

    @Test
    fun `getScheduleStatus returns lastRunAt and lastRunProcessId after fire`() =
        runTest {
            val agent =
                AgentFixtures.simpleAgent(name = "fired_agent").copy(
                    cron = CronConfig(expression = "* * * * *"),
                )
            coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 77

            val now = ZonedDateTime.now(ZoneId.of("UTC"))
            service.fireAgent(agent, now)

            val status = service.getScheduleStatus(agent)
            assertNotNull(status)
            assertEquals(77, status.lastRunProcessId)
            assertEquals(now.toEpochSecond(), status.lastRunAt)
            assertNotNull(status.nextRunAt)
        }

    // --- start / tick alignment ---

    /**
     * Starts a scheduler on [TestScope.backgroundScope] whose clock is the test
     * scheduler's virtual time offset by [base], and returns the list it appends
     * every fired tick instant to. A non-zero [tickWorkMs] makes the stubbed run
     * start consume that much simulated time, i.e. a slow `tick()`.
     *
     * [now] optionally wraps that clock — it receives the default virtual-time
     * clock and returns the function the service reads time through — so a test
     * can observe reads without restating the fixture.
     */
    private fun TestScope.startAlignedScheduler(
        base: ZonedDateTime,
        tickWorkMs: Long = 0,
        now: ((() -> ZonedDateTime) -> (() -> ZonedDateTime))? = null,
    ): MutableList<ZonedDateTime> {
        val baseMillis = base.toInstant().toEpochMilli()

        fun virtualNow() = Instant.ofEpochMilli(baseMillis + testScheduler.currentTime).atZone(ZoneOffset.UTC)

        val clock: () -> ZonedDateTime = if (now == null) ::virtualNow else now(::virtualNow)

        val fires = mutableListOf<ZonedDateTime>()
        val agent =
            AgentFixtures.simpleAgent(name = "every_minute").copy(
                cron = CronConfig(expression = "* * * * *", timezone = "UTC"),
            )
        every { agentsService.agents } returns mapOf("every_minute" to agent)
        coEvery { agentRunStarter.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            fires += virtualNow()
            if (tickWorkMs > 0) delay(tickWorkMs)
            1
        }

        val svc =
            CronSchedulerService(
                agentsService = agentsService,
                agentRunStarter = agentRunStarter,
                agentInputResolver = agentInputResolver,
                maintenanceMode = maintenanceMode,
                logger = TestFixtures.mockLogger(),
                scope = backgroundScope,
                now = clock,
            )
        svc.start()
        return fires
    }

    private fun assertMinuteAligned(fires: List<ZonedDateTime>) {
        fires.forEach {
            assertEquals(0, it.second, "tick at $it is not on a minute boundary")
            assertEquals(0, it.nano, "tick at $it carries sub-second time")
        }
    }

    @Test
    fun `start first tick lands on the next minute boundary, not before`() =
        runTest {
            // 12:00:30 — half a minute into the minute.
            val base = ZonedDateTime.of(2025, 1, 1, 12, 0, 30, 0, ZoneOffset.UTC)
            val fires = startAlignedScheduler(base)

            advanceTimeBy(29_999)
            runCurrent()
            assertTrue(fires.isEmpty(), "nothing may fire before the next minute boundary")

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(base.plusSeconds(30)), fires)
        }

    @Test
    fun `start keeps ticks on minute boundaries when a tick takes seconds`() =
        runTest {
            val base = ZonedDateTime.of(2025, 1, 1, 12, 0, 45, 0, ZoneOffset.UTC)
            val fires = startAlignedScheduler(base, tickWorkMs = 5_000)

            advanceTimeBy(600_000) // ten simulated minutes
            runCurrent()

            assertTrue(fires.isNotEmpty())
            assertMinuteAligned(fires)
            // Spacing stays exactly one minute despite the 5 s of work each tick does.
            fires.zipWithNext { a, b -> assertEquals(60, ChronoUnit.SECONDS.between(a, b)) }
        }

    @Test
    fun `start fires every wall-clock minute over a long run, skipping none`() =
        runTest {
            val base = ZonedDateTime.of(2025, 1, 1, 12, 0, 45, 0, ZoneOffset.UTC)
            val fires = startAlignedScheduler(base, tickWorkMs = 5_000)

            advanceTimeBy(3_600_000) // one simulated hour
            runCurrent()

            assertEquals(60, fires.size, "one hour must receive exactly 60 ticks")
            assertMinuteAligned(fires)
            // Consecutive minutes: no gap and no repeat.
            fires.forEachIndexed { i, fired ->
                assertEquals(base.withSecond(0).plusMinutes(i.toLong() + 1), fired)
            }
        }

    @Test
    fun `start reads the clock once per iteration so a lagged resume cannot retarget the tick`() =
        runTest {
            // A second clock read after the delay is what let a late resume land in the
            // next minute and hand the tick a different minute than the delay targeted.
            val base = ZonedDateTime.of(2025, 1, 1, 12, 0, 45, 0, ZoneOffset.UTC)

            var reads = 0
            val fires =
                startAlignedScheduler(base) { virtualNow ->
                    {
                        reads++
                        virtualNow()
                    }
                }

            advanceTimeBy(180_000) // three simulated minutes
            runCurrent()

            assertEquals(3, fires.size)
            assertMinuteAligned(fires)
            assertTrue(
                reads <= fires.size + 1,
                "the loop read the clock $reads times for ${fires.size} ticks; it must read it once per tick",
            )
        }

    // --- getNextRunTime / getScheduleStatus clock injection ---

    @Test
    fun `next run time is computed from the injected clock, not the wall clock`() {
        val fixed = ZonedDateTime.of(2025, 1, 1, 12, 0, 30, 0, ZoneOffset.UTC)
        val clocked =
            CronSchedulerService(
                agentsService = agentsService,
                agentRunStarter = agentRunStarter,
                agentInputResolver = agentInputResolver,
                maintenanceMode = maintenanceMode,
                logger = TestFixtures.mockLogger(),
                now = { fixed },
            )
        val config = CronConfig(expression = "*/5 * * * *", timezone = "UTC")
        val agent = AgentFixtures.simpleAgent(name = "every_five").copy(cron = config)

        val next = clocked.getNextRunTime(config)
        assertEquals(ZonedDateTime.of(2025, 1, 1, 12, 5, 0, 0, ZoneId.of("UTC")), next)
        assertEquals(next!!.toEpochSecond(), clocked.getScheduleStatus(agent)?.nextRunAt)
    }

    // --- stop ---

    @Test
    fun `stop cancels the scheduler scope`() {
        val supervisorJob = SupervisorJob()
        val testScope = CoroutineScope(Dispatchers.Default + supervisorJob)
        val svc =
            CronSchedulerService(
                agentsService = agentsService,
                agentRunStarter = agentRunStarter,
                agentInputResolver = agentInputResolver,
                maintenanceMode = maintenanceMode,
                logger = TestFixtures.mockLogger(),
                scope = testScope,
            )
        svc.start()
        assertTrue(supervisorJob.isActive)
        svc.stop()
        assertFalse(supervisorJob.isActive)
    }
}
