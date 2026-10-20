package homer.app.executors

import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.infra.TelemetryService
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessStatus
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger

class ProcessExecutor(
    private val telemetryService: TelemetryService,
    private val processManager: RunRecords,
    private val questionsService: QuestionExpiry,
    private val coroutineScope: CoroutineScope,
    private val logger: Logger,
    // Invoked once the terminal status is durably committed (after the CAS), with
    // the actual final status — fires completion continuations. Lazy/post-construction
    // wired in DI to break the ContinuationService → AgentRunStarter → ProcessExecutor
    // cycle; never throws into the status commit (failures are swallowed here). See ADR-0023.
    private val onTerminal: suspend (Int, ProcessStatus) -> Unit = { _, _ -> },
) {
    suspend fun execute(
        process: Process,
        parentContext: Context,
    ): ProcessCreationResult {
        // cooldown goes through obtainProcessId so the check is atomic with the row
        // INSERT; AgentRunStarter's pre-flight check cannot bind on its own. ADR-0051.
        val processId =
            processManager.obtainProcessId(
                process.name,
                process.queryParams,
                owner = process.owner,
                cooldown = process.cooldown,
                secretInputKeys = process.secretInputKeys,
                parentProcessId = process.parentProcessId,
                rootProcessId = process.rootProcessId,
                tags = process.fromInputTags,
            )

        logger.info("Process created {} for agent: {}", processId, process.name)
        processManager.assignJob(processId, coroutineScope.launch { runJob(processId, process, parentContext) })

        return ProcessCreationResult(processId)
    }

    /** The launched job: runs [process] under its own span and commits exactly one terminal status. */
    @Suppress("SuspendFunSwallowedCancellation") // the run's boundary: a kill is committed as KILLED, see Status precedence
    private suspend fun runJob(
        processId: Int,
        process: Process,
        parentContext: Context,
    ) {
        val processSpan =
            telemetryService.tracer
                .spanBuilder("agent_execution")
                .setParent(parentContext)
                .startSpan()

        // Stamp the run's trace id while the span exists. It is the same trace as
        // the root `agent_request` span, and it outlives the artifacts — so a run
        // still links to its Langfuse trace after the workspace is purged.
        // isSampled, not just isValid: a `telemetry: OFF` run has a valid trace id
        // that is never exported, and storing it would render a dead Langfuse link.
        processSpan.spanContext
            .takeIf { it.isValid && it.isSampled }
            ?.let { processManager.setTraceId(processId, it.traceId) }
        // Guarded: a kill can land before this first write (a failed callback join kills
        // the sub it just started), and an unguarded write would reset KILLED to CREATED.
        processManager.compareAndSetStatus(processId, setOf(ProcessStatus.CREATED), ProcessStatus.CREATED)
        try {
            processSpan.setAttribute("process.id", processId.toLong())
            processSpan.setAttribute("process.status", ProcessStatus.CREATED.name)
            processSpan.addEvent("process.status.created")
            process.execute(processId, processSpan)
            commitFinished(processId, process, processSpan)
        } catch (e: Exception) {
            withContext(NonCancellable) { commitFailed(processId, process, processSpan, e) }
        } finally {
            withContext(NonCancellable) { releaseJob(processId, processSpan) }
        }
    }

    /** Happy path: CAS to FINISHED; a terminal status that landed first wins and is mirrored in telemetry. */
    private suspend fun commitFinished(
        processId: Int,
        process: Process,
        processSpan: Span,
    ) {
        // Audit #9: guard WORKING/CREATED → FINISHED so a concurrent
        // killProcess (which atomically moves the record to KILLED) is
        // not overwritten after execute() returned.
        val transitionedToFinished =
            processManager.compareAndSetStatus(
                processId,
                setOf(ProcessStatus.WORKING, ProcessStatus.CREATED),
                ProcessStatus.FINISHED,
            )
        schedulePurge(processId, process)
        if (transitionedToFinished) {
            processSpan.setAttribute("process.status", ProcessStatus.FINISHED.name)
            processSpan.addEvent("process.status.finished")
            processSpan.setStatus(StatusCode.OK)
            logger.info("Process {} completed successfully", processId)
        } else {
            val finalStatus = processManager.getProcessStatus(processId)
            processSpan.setAttribute("process.status", finalStatus.name)
            processSpan.addEvent("process.status.${finalStatus.name.lowercase()}")
            processSpan.setStatus(
                StatusCode.ERROR,
                "Process completed but final status is $finalStatus",
            )
            logger.warn(
                "Process {} completed but final status is {} (concurrent transition)",
                processId,
                finalStatus,
            )
        }
    }

    /** Exception path: CAS to FAILED unless KILLED, then telemetry from the committed final status. */
    private suspend fun commitFailed(
        processId: Int,
        process: Process,
        processSpan: Span,
        e: Exception,
    ) {
        // CAS like the FINISHED write above: a concurrent killProcess
        // landing between a status read and this write must not be
        // overwritten with FAILED.
        processManager.compareAndSetStatus(
            processId,
            ProcessStatus.entries.toSet() - ProcessStatus.KILLED,
            ProcessStatus.FAILED,
        )

        // Get final status for telemetry
        val finalStatus = processManager.getProcessStatus(processId)

        schedulePurge(processId, process)

        if (e is AnotherProcessStillActive || e is MaxParallelRunsExceededException) {
            // Slot/lock collision - process never ran
            processSpan.setAttribute("process.status", ProcessStatus.FAILED.name)
            processSpan.addEvent("process.status.failed")
            processSpan.setStatus(
                StatusCode.ERROR,
                "Agent execution cancelled: ${e.message}",
            )
            processSpan.recordException(e)
            logger.error("Process {} cancelled: {}", processId, e.message)
        } else if (finalStatus == ProcessStatus.KILLED) {
            // Process was killed (user or lock manager)
            processSpan.setAttribute("process.status", ProcessStatus.KILLED.name)
            processSpan.addEvent("process.status.killed")
            processSpan.setStatus(
                StatusCode.ERROR,
                "Process killed: ${e.message}",
            )
            processSpan.recordException(e)
            logger.error("Process {} killed: {}", processId, e.message, e)
        } else {
            // Normal failure
            processSpan.setAttribute("process.status", ProcessStatus.FAILED.name)
            processSpan.addEvent("process.status.failed")
            processSpan.setStatus(
                StatusCode.ERROR,
                "Agent execution failed: ${e.message}",
            )
            processSpan.recordException(e)
            logger.error("Process {} failed: {}", processId, e.message, e)
        }
    }

    /** Every exit is terminal: expire open questions, fire continuations, end the span, drop the job. */
    @Suppress("SuspendFunSwallowedCancellation") // runs under NonCancellable; a hook failure must not stop the release
    private suspend fun releaseJob(
        processId: Int,
        processSpan: Span,
    ) {
        // Every exit from this job is a terminal status, so open
        // questions can never be answered anymore — expire them
        // and resume their waiters.
        questionsService.expireForProcess(processId)
        // Fire completion continuations off the durably-committed
        // final status (re-read for the actual KILLED-wins value).
        // Caught here so a hook failure can never corrupt the
        // process's terminal status. See ADR-0023.
        try {
            onTerminal(processId, processManager.getProcessStatus(processId))
        } catch (e: Exception) {
            logger.error("Continuation hook for process {} failed: {}", processId, e.message, e)
        }
        processSpan.end()
        processManager.removeJob(processId)
    }

    private suspend fun schedulePurge(
        processId: Int,
        process: Process,
    ) {
        val now = System.currentTimeMillis() / 1000
        processManager.setPurgeAt(
            processId,
            purgeAt = now + process.purgeAfterSeconds,
            recordPurgeAt = now + process.purgeRecordAfterSeconds,
            retainArtifacts = process.retainArtifacts,
        )
    }
}
