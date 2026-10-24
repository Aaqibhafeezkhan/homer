package homer.app.services

import com.cronutils.model.time.ExecutionTime
import homer.app.infra.AgentInputResolver
import homer.app.infra.CronSupport
import homer.app.models.Agent
import homer.app.models.CronConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

internal class CronSchedulerService(
    private val agentsService: AgentsService,
    private val agentRunStarter: AgentRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val maintenanceMode: MaintenanceModeService,
    private val logger: Logger,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) },
) {
    private data class RunInfo(
        val processId: Int,
        val runAt: Long,
    )

    private val executionTimeCache = ConcurrentHashMap<CronConfig, Pair<ZoneId, ExecutionTime>>()

    private fun executionTimeFor(config: CronConfig): Pair<ZoneId, ExecutionTime> =
        executionTimeCache.getOrPut(config) { CronSupport.executionTimeFor(config) }

    private val lastRunMap: ConcurrentHashMap<String, RunInfo> = ConcurrentHashMap()

    data class ScheduleStatus(
        val nextRunAt: Long?,
        val lastRunAt: Long?,
        val lastRunProcessId: Int?,
    )

    fun start() =
        scope.launch {
            while (isActive) {
                // One clock read decides both the delay and the minute ticked, so a
                // late resume cannot retarget the tick at whatever minute it lands in.
                // The interval is always 1..MS_PER_MINUTE, so the loop never spins.
                val startedAt = now().toInstant().toEpochMilli()
                val untilNextMinute = MS_PER_MINUTE - (startedAt % MS_PER_MINUTE)
                delay(untilNextMinute)
                // The tick belongs to the boundary the delay targeted, not to the
                // instant the (possibly slow) tick work happens to run at.
                val tickAt = Instant.ofEpochMilli(startedAt + untilNextMinute).atZone(ZoneOffset.UTC)
                tick(tickAt.truncatedTo(ChronoUnit.MINUTES))
            }
        }

    fun stop() = scope.cancel()

    fun getScheduleStatus(agent: Agent): ScheduleStatus? {
        val cron = agent.cron ?: return null
        // Schedules are validated at agent load (AgentsService), so this should not
        // throw; guard anyway so one bad agent can never 500 the whole agents listing.
        val next =
            try {
                getNextRunTime(cron)?.toEpochSecond()
            } catch (e: Exception) {
                logger.error("Failed to compute next run time for agent '{}' (invalid schedule)", agent.name, e)
                null
            }
        val last = lastRunMap[agent.name]
        return ScheduleStatus(next, last?.runAt, last?.processId)
    }

    internal suspend fun tick(now: ZonedDateTime) {
        val due = agentsService.agents.values.filter { it.cron != null && matchesCronSafe(it, it.cron, now) }
        if (maintenanceMode.isEnabled()) {
            // Skipped, not queued: a tick that does not fire is never replayed (matching is
            // per minute), so a long maintenance window builds no backlog. See ADR-0092.
            if (due.isNotEmpty()) {
                logger.info("Maintenance mode is on: skipping {} scheduled fire(s)", due.size)
            }
            return
        }
        due.forEach { fireAgent(it, now) }
    }

    // Validated at load, but a throw inside the tick filter would terminate the
    // scheduler coroutine and silently stop every schedule — so never let one escape.
    private fun matchesCronSafe(
        agent: Agent,
        cron: CronConfig,
        now: ZonedDateTime,
    ): Boolean =
        try {
            matchesCron(cron, now)
        } catch (e: Exception) {
            logger.error("Skipping cron evaluation for agent '{}' (invalid schedule)", agent.name, e)
            false
        }

    /**
     * Fires [agent] immediately with cron semantics — the `/schedules` Run button's
     * path. Uses the injected clock so `lastRunMap`'s `runAt` matches a real tick.
     */
    internal suspend fun fireNow(agent: Agent) = fireAgent(agent, now())

    internal suspend fun fireAgent(
        agent: Agent,
        now: ZonedDateTime,
    ) {
        try {
            val processId =
                agentRunStarter.start(
                    agent = agent,
                    invocationChain = emptyList(),
                    source = "cron",
                    owner = "system:cron",
                ) {
                    // Cron has no caller-supplied inputs. enforceRequired makes a
                    // schedule on an inputs-requiring agent fail loudly here
                    // instead of running with missing env vars.
                    agentInputResolver.resolveFromParams(emptyMap(), agent)
                }
            lastRunMap[agent.name] = RunInfo(processId, now.toEpochSecond())
            logger.info("Cron triggered agent '{}', processId={}", agent.name, processId)
        } catch (e: CancellationException) {
            // The scheduler stopping, not a failed fire: the tick must not go on to the next agent.
            throw e
        } catch (e: Exception) {
            logger.error("Failed to fire scheduled agent '{}'", agent.name, e)
        }
    }

    internal fun matchesCron(
        config: CronConfig,
        now: ZonedDateTime,
    ): Boolean {
        val (tz, executionTime) = executionTimeFor(config)
        return executionTime.isMatch(now.withZoneSameInstant(tz))
    }

    fun getNextRunTime(config: CronConfig): ZonedDateTime? {
        val (tz, executionTime) = executionTimeFor(config)
        return executionTime.nextExecution(now().withZoneSameInstant(tz)).orElse(null)
    }

    companion object {
        private const val MS_PER_MINUTE = 60_000L
    }
}
