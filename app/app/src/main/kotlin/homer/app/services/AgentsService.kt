package homer.app.services

import homer.app.infra.AgentDefinitionValidator
import homer.app.infra.AgentInputChecks
import homer.app.infra.AgentInputResolver
import homer.app.infra.AgentReloadDiff
import homer.app.infra.AgentScan
import homer.app.infra.AgentScanner
import homer.app.infra.InputSchemaValidator
import homer.app.infra.ParamValidator
import homer.app.infra.ReloadComputation
import homer.app.infra.SysEnvResolver
import homer.app.models.Agent
import homer.app.models.AgentReloadResult
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads agent definitions from [agentsSearchPath] and publishes them as one atomic
 * snapshot. Scanning ([AgentScanner], validating through [AgentDefinitionValidator])
 * and the reload diff ([AgentReloadDiff]) are collaborators built from the
 * constructor's parameters, so the DI wiring sees one service.
 */
class AgentsService(
    private val agentsSearchPath: String,
    private val logger: Logger,
    sysEnvResolver: SysEnvResolver = SysEnvResolver(),
    private val broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster(),
    maxConfigFileBytes: Long = DEFAULT_MAX_CONFIG_FILE_BYTES,
    inputSchemaValidator: InputSchemaValidator = InputSchemaValidator(),
    paramValidator: ParamValidator = ParamValidator(),
    inputResolver: AgentInputResolver = AgentInputResolver(inputSchemaValidator, paramValidator),
    runnerAliases: Set<String> = setOf("local"),
    /**
     * Tag sets the configured runners serve, read at load time for the unmatched-tags
     * warning. Warn-only, so a throw here degrades to a warning; see ADR-0066 deviation (9).
     */
    runnerTagSets: () -> Collection<Set<String>> = { emptyList() },
) {
    private val scanner: AgentScanner =
        AgentInputChecks(logger, paramValidator).let { inputChecks ->
            AgentScanner(
                agentsSearchPath,
                logger,
                sysEnvResolver,
                maxConfigFileBytes,
                AgentDefinitionValidator(logger, runnerAliases, runnerTagSets, inputResolver, inputSchemaValidator, inputChecks),
                inputChecks,
            )
        }

    private val stateRef = AtomicReference(scanner.scan().toState())

    init {
        logger.info("Agents loaded: {}", stateRef.get().agents.size)
    }

    val agents: Map<String, Agent>
        get() = stateRef.get().agents

    val rootPath: Path
        get() = Path.of(agentsSearchPath)

    /**
     * Resolves the on-disk directory for a loaded agent. Returns null if the agent
     * is not currently loaded (unknown name, or its files failed to parse at scan time).
     */
    fun getAgentDir(name: String): Path? {
        val sourceDir = stateRef.get().sourceDirs[name] ?: return null
        return Path.of(agentsSearchPath).resolve(sourceDir)
    }

    /**
     * Rescan agent definitions on disk and atomically swap in the new state.
     *
     * Disk I/O (`scanner.scan()`) runs outside any lock so concurrent callers can
     * scan in parallel; the new state is published via `compareAndSet` against
     * the snapshot the scan was based on. If another writer wins the race we
     * rescan and retry — both the scan AND the diff must be recomputed against
     * the now-current state, otherwise a stale scan can attribute "removed" to
     * directories the winning writer just legitimately added.
     *
     * The previous implementation was `@Synchronized` and serialized the entire
     * method (including disk I/O) on the AgentsService instance. Two simultaneous
     * reloads would queue; with @Synchronized removed but no atomic swap, a
     * naive get-then-set could let two concurrent reloads clobber each other's
     * additions/removals. This compareAndSet loop is the minimal fix.
     */
    fun reload(): AgentReloadResult {
        var attempt = 0
        while (true) {
            // Rescan and recompute the diff against the now-current state every
            // attempt (see KDoc): a stale scan would misattribute "removed".
            val scan = scanner.scan()
            val currentState = stateRef.get()
            val computation = AgentReloadDiff.compute(scan, currentState)

            if (stateRef.compareAndSet(currentState, computation.newState)) {
                return publishReload(computation, attempt)
            }

            attempt++
            if (attempt >= MAX_RELOAD_CAS_ATTEMPTS) {
                return giveUpAfterContention(scan, attempt)
            }
        }
    }

    /** Log the won-after-retries note (if any), the completion summary, and emit the reload event. */
    private fun publishReload(
        computation: ReloadComputation,
        attempt: Int,
    ): AgentReloadResult {
        val result = computation.result
        if (attempt > 0) {
            // Only emitted under genuine concurrent-reload contention, so it
            // never spams the happy path — a single line summarising the race.
            logger.info("AgentsService.reload() won after {} CAS retr(y/ies) under concurrent reload", attempt)
        }
        logger.info(
            "Reload complete: added={}, updated={}, removed={}, errors={}, total={}",
            result.added.size,
            result.updated.size,
            result.removed.size,
            result.errors.size,
            computation.newState.agents.size,
        )
        broadcaster.emit(ProcessEvent.AgentsReloaded(result.added, result.updated, result.removed))
        return result
    }

    /**
     * Stale-scan fallback: after losing the CAS race [MAX_RELOAD_CAS_ATTEMPTS] times we stop
     * retrying and publish nothing. The winning writer already swapped in a coherent snapshot,
     * so we return the diff of our (possibly slightly stale) scan against the latest published
     * state — an accurate report of what this reload would have done relative to it.
     */
    private fun giveUpAfterContention(
        scan: AgentScan,
        attempt: Int,
    ): AgentReloadResult {
        logger.warn(
            "AgentsService.reload() lost CAS race {} times; another writer is reloading concurrently. Giving up.",
            attempt,
        )
        val current = stateRef.get()
        val giveUpRemoved = AgentReloadDiff.removedAgentNames(current.agents, current.sourceDirs, scan)
        return AgentReloadDiff.result(current.agents, scan, giveUpRemoved)
    }

    private companion object {
        const val MAX_RELOAD_CAS_ATTEMPTS = 8
        const val DEFAULT_MAX_CONFIG_FILE_BYTES: Long = 1L * 1024 * 1024
    }
}
