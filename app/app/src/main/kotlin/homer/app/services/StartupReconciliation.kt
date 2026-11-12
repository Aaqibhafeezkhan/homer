package homer.app.services

import homer.app.executors.RunMessageExpiry
import homer.app.infra.PurgeSchedule
import homer.app.services.persistence.ProcessRepository
import org.slf4j.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Boot-time reconciliation of state left behind by a previous (possibly
 * unclean) shutdown. Durable process rows survive a restart, but the coroutines
 * that were driving any still-active run do not — so every process still
 * `CREATED`/`WORKING` is marked `UNKNOWN` (with a history entry) and its OPEN
 * questions are expired (preserving the "questions die with their process"
 * invariant — waiters that reconnect see EXPIRED, not a perpetual OPEN). Every
 * message still QUEUED on a run that is no longer active becomes UNDELIVERED
 * (ADR-0097).
 *
 * `UNKNOWN` is treated as terminal, so these rows never block on a resume that
 * can't happen. The run's own coroutine would have stamped its purge times on
 * the way out; it died with the server, so every UNKNOWN row still missing them
 * — this boot's and any an earlier boot left — is stamped here from its agent's
 * current `workspace` config (the model defaults when the agent is gone),
 * counted from now, and `WorkspacePurgeService` then sweeps it like any other
 * terminal run. Runs once, before traffic is accepted, alongside the Kubernetes
 * orphan-pod sweep. See ADR-0020 and ADR-0048 (2026-10-01 amendment).
 */
@OptIn(ExperimentalTime::class)
internal class StartupReconciliation(
    private val processRepository: ProcessRepository,
    private val questionsService: QuestionsService,
    private val runMessagesService: RunMessageExpiry,
    private val dispatchService: DispatchService,
    private val continuationService: ContinuationService,
    private val hookService: HookService,
    private val agentsService: AgentsService,
    private val logger: Logger,
    private val clock: () -> Long = { Clock.System.now().epochSeconds },
) {
    suspend fun reconcile() {
        val now = clock()
        // The hooks an UNKNOWN transition records are the same per-transition lookup
        // ProcessManager runs, so a hook declared for UNKNOWN fires for a restart-interrupted
        // run too. See ADR-0106.
        val interrupted = processRepository.markInterruptedAsUnknown(now, hookService.recording())
        if (interrupted.processIds.isNotEmpty()) {
            logger.info(
                "Reconciled {} interrupted process(es) to UNKNOWN after restart: {}",
                interrupted.processIds.size,
                interrupted.processIds,
            )
            for (processId in interrupted.processIds) {
                questionsService.expireForProcess(processId)
                runMessagesService.closeForProcess(processId)
            }
        }
        // Backstop for a run whose inbox close failed, or whose server stopped before it ran.
        runMessagesService.closeForEndedRuns()
        val agents = agentsService.agents
        val stamped =
            processRepository.stampUnknownPurgeTimes(now) { agentName ->
                agents[agentName]?.let { PurgeSchedule.of(it.workspace) } ?: PurgeSchedule.DEFAULT
            }
        if (stamped > 0) logger.info("Scheduled the purge of {} UNKNOWN process(es)", stamped)
        // Fail dispatches interrupted mid-fire (avoid duplicate runs), then fire any that were
        // answered before the crash but never started.
        dispatchService.failOrphanedDispatching()
        dispatchService.refirePendingAnswered()
        // Same crash-safety for continuations: fail any left mid-dispatch, then fire any whose
        // watched process reached a terminal state before this boot (the live hook never ran).
        continuationService.failOrphanedDispatching()
        continuationService.refirePendingTerminal()
        // Same again for hooks: fail any left mid-fire, fire the rows the UNKNOWN writes above
        // recorded, then replay whatever else is still PENDING — a server that stopped between
        // a transition's commit and its fire. The claim CAS keeps a replay from double-firing
        // against a row a live transition is firing concurrently. See ADR-0106.
        hookService.failOrphanedDispatching()
        hookService.onRowsRecorded(interrupted.hooks)
        hookService.refirePending()
    }
}
