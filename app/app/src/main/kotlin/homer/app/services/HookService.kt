package homer.app.services

import homer.app.infra.AgentInputResolver
import homer.app.models.HookConfig
import homer.app.models.ProcessStatus
import homer.app.services.persistence.HookRecording
import homer.app.services.persistence.HookRepository
import homer.app.services.persistence.RecordedHooks
import homer.app.services.persistence.ShedHooks
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where a hook row is in its one-shot delivery. `DROPPED` is settled before any attempt. See ADR-0105. */
enum class HookStatus { PENDING, DISPATCHING, DISPATCHED, FAILED, DROPPED }

/** One durable agent-level hook: a run of [declaringAgent] reached [triggeringStatus]. */
data class PendingHook(
    val id: Int,
    val processId: Int,
    val declaringAgent: String,
    val triggeringStatus: ProcessStatus,
    val targetAgentName: String,
    val owner: String?,
    val status: HookStatus,
    val fireAttempts: Int,
    val nextAttemptAt: Long?,
    val firedProcessId: Int?,
    val firedAt: Long?,
    val error: String?,
    val createdAt: Long,
)

/**
 * Agent-declared status hooks: the row a run's status transition records is fired here, after
 * the transition's transaction has committed. Every hook is a durable row written with the
 * status it rides (so a restart loses none), claimed before its run starts (so nothing fires
 * twice), and settled once. The fire itself is [HookFireService]. See ADR-0105, ADR-0023.
 */
@OptIn(ExperimentalTime::class)
internal class HookService(
    private val repository: HookRepository,
    agentRunStarter: AgentRunStarter,
    private val agents: AgentsService,
    authorizer: Authorizer,
    agentInputResolver: AgentInputResolver,
    processManager: ProcessManager,
    private val logger: Logger,
    private val config: HookConfig = HookConfig(),
    private val nowSec: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val fires =
        HookFireService(
            repository,
            agents,
            authorizer,
            agentRunStarter,
            agentInputResolver,
            processManager,
            logger,
            config.maxChainDepth,
            nowSec,
        )

    /**
     * Fires the hook rows a just-committed status transition recorded, and reports the entries
     * the pending cap shed. Called after the write, never inside it: a fire starts a run, and
     * that run's own row write must not nest inside the triggering transition's transaction
     * (app/CLAUDE.md SQLite rule 3).
     *
     * A lookup or a fire failure is logged and swallowed — the status transition has already
     * committed and a hook must never be able to fail it; a row a failed fire left unclaimed
     * stays PENDING, so the boot replay or the retry sweep picks it up. See ADR-0023.
     */
    suspend fun onRowsRecorded(recorded: RecordedHooks) {
        logShed(recorded.shed)
        for (id in recorded.ids) {
            val hook =
                try {
                    repository.get(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Hook lookup for row {} failed: {}", id, e.message, e)
                    null
                }
            if (hook != null) fireSafely(hook)
        }
    }

    /**
     * Fires [hook] with every non-cancellation failure contained: the caller is a status write
     * that has already committed (or a sweep that still has rows behind this one), so nothing a
     * fire does may reach it. The row keeps the state the failed fire left it in and the retry
     * sweep or the next boot's replay picks it up. See ADR-0023, ADR-0105.
     */
    private suspend fun fireSafely(hook: PendingHook) {
        try {
            fires.fire(hook)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Hook {} fire failed: {}", hook.id, e.message, e)
        }
    }

    /**
     * One warn per transition that had hook entries shed by the pending cap, so an operator can
     * see what a burst cost. The transition itself has already committed: shedding never fails
     * it, and never changes the triggering run's own status. See ADR-0105.
     */
    private fun logShed(shed: List<ShedHooks>) {
        for (entry in shed) {
            logger.warn(
                "Dropped {} hook row(s) for agent '{}' status {} over server.hooks.maxPending ({})",
                entry.count,
                entry.agentName,
                entry.status,
                config.maxPending,
            )
        }
    }

    suspend fun get(id: Int): PendingHook? = repository.get(id)

    /**
     * The per-transition hook lookup this service's rows are recorded with — the same one
     * [ProcessManager] runs, so an agent's hooks are resolved identically wherever a status is
     * written. See ADR-0105.
     */
    fun recording(): HookRecording = hookRecordingFor(agents, config.maxPending)

    /** Re-fires hooks whose transient-failure retry is due. Driven by [ContinuationSweepService]. */
    suspend fun sweepRetries() {
        val due =
            try {
                repository.findDueRetries(nowSec())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Hook retry lookup failed: {}", e.message, e)
                return
            }
        for (hook in due) fireSafely(hook)
    }

    /** Fails hooks left DISPATCHING by a restart (avoids a duplicate fire). Boot only. */
    suspend fun failOrphanedDispatching() {
        val orphaned = repository.failOrphanedDispatching(nowSec())
        if (orphaned.isNotEmpty()) {
            logger.warn("Failed {} hook(s) interrupted mid-dispatch by restart", orphaned.size)
        }
    }

    /**
     * Fires every PENDING hook this boot found. A row survives its transition's commit, so the
     * only way one is still PENDING at boot is that the server stopped between the commit and
     * the fire (or the fire failed transiently and its retry never came). The claim CAS makes
     * this safe alongside a concurrent live fire. Boot only.
     */
    suspend fun refirePending() {
        val pending = repository.list(HookStatus.PENDING)
        if (pending.isNotEmpty()) {
            logger.info("Re-firing {} hook(s) left pending by a previous run", pending.size)
        }
        for (hook in pending) fireSafely(hook)
    }

    /** Deletes settled hooks older than `settledRetentionSec`. See ADR-0105. */
    suspend fun pruneSettled() {
        val pruned = repository.pruneSettled(nowSec() - config.settledRetentionSec)
        if (pruned > 0) {
            logger.info("Pruned {} settled hook(s) older than {} s", pruned, config.settledRetentionSec)
        }
    }
}

/**
 * The per-transition hook lookup a status write records with, reached through
 * [HookService.recording] so every writer shares one definition. [agentsService] is read at call
 * time, so an agent reload between a run's start and its transition is honoured, and it reads no
 * database, so the lookup is safe inside the write transaction. See ADR-0105.
 */
internal fun hookRecordingFor(
    agentsService: AgentsService,
    maxPending: Int,
): HookRecording =
    HookRecording(
        hooksFor = { agentName, status ->
            agentsService.agents[agentName]
                ?.hooks
                ?.filter { it.status == status }
                .orEmpty()
        },
        maxPending = maxPending,
    )
