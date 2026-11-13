package homer.app.executors

import homer.app.EnvConstants
import homer.app.infra.AgentDirs
import homer.app.infra.Workspace
import homer.app.models.RunnerState
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.DownloadMode
import homer.app.runners.RunnerSession
import homer.app.runners.TarStreams
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.system.measureTimeMillis

/**
 * Creates the backend workspace, records the serving backend, and uploads the project
 * root, the local agent dir and the session's extra uploads to it. One of the two ends of
 * the run's backend session that [AgentExecutor] brackets its steps with.
 */
internal suspend fun prepareBackendWorkspace(
    session: RunnerSession,
    workspace: Workspace,
    stepRecords: StepRecords,
    processId: Int,
    logger: Logger,
) {
    // Must be read BEFORE createWorkspace(): its overrides rewrite
    // HMR_AGENT_DIR to the backend-side path, but the upload source
    // is the local agent dir.
    val localAgentDir = workspace.config.env[EnvConstants.AGENT_DIR]
    val workspaceOverrides = session.createWorkspace()
    logger.debug("Process {}: backend workspace ready on '{}'", processId, session.runnerAlias)
    // The serving backend is only known now: for a tag-filtered run the queued
    // alias is a filter string ("tags:..."), not a backend (ADR-0066). Merge
    // rather than replace so the Kubernetes runner's pod fields survive.
    stepRecords.setRunnerState(processId) { prev ->
        prev?.copy(alias = session.runnerAlias) ?: RunnerState(alias = session.runnerAlias)
    }
    if (workspaceOverrides.isNotEmpty()) {
        workspace.appendToEnv(workspaceOverrides)
    }
    val uploadMs =
        measureTimeMillis {
            // projectRoot is created per run by Homer: a symlink there is tampering,
            // so it is deliberately NOT resolved. The other two are operator-configured
            // and may legitimately be symlinks (a symlinked agent dir, an extraUploads
            // entry — ADR-0042); TarStreams.pack refuses a symlinked source, so resolve
            // them here and let containment be checked against the real root.
            session.upload(workspace.config.projectRoot, ".")
            localAgentDir?.let { agentDir ->
                val source = TarStreams.resolvedPackSource(Path.of(agentDir))
                // A nested agent is a different agent: a parent's HMR_AGENT_DIR
                // upload carries none of its children's directories (ADR-0068,
                // ADR-0047). Leading `/` anchors each exclude to the upload root;
                // a bare name would match a same-named directory at any depth.
                val nestedExcludes =
                    AgentDirs.nestedAgentDirsUnder(source).map { "/${it.invariantSeparatorsPathString}" }
                session.upload(source, ".homer/agent", nestedExcludes)
            }
            session.extraUploads.forEach { session.upload(TarStreams.resolvedPackSource(Path.of(it.local)), it.remote) }
        }
    logger.debug(
        "Process {}: workspace upload to '{}' took {} ms ({} extra uploads)",
        processId,
        session.runnerAlias,
        uploadMs,
        session.extraUploads.size,
    )
}

/** Syncs the backend's artifacts (and a kept workdir) down, cleans a cleared one, and closes [session]. */
@Suppress("SuspendFunSwallowedCancellation") // best-effort, and only ever runs under NonCancellable
internal suspend fun syncDownAndClose(
    session: RunnerSession,
    workspace: Workspace,
    processId: Int,
    logger: Logger,
    activeSessions: ActiveSessionsRegistry,
) {
    // Sync-down is best-effort: never fail an otherwise-successful run.
    runCatching {
        val elapsedMs =
            measureTimeMillis { session.download("artifacts", workspace.config.artifacts, DownloadMode.OVERLAY) }
        logger.debug("Process {}: artifacts sync-down took {} ms", processId, elapsedMs)
    }.onFailure { logger.error("Sync-down of artifacts failed for process {}: {}", processId, it.message, it) }
    if (!workspace.config.clearAfterFinish) {
        runCatching {
            // MIRROR: a kept workdir must reflect the remote exactly — a file the
            // agent deleted remotely must not survive locally. See ADR-0055.
            val elapsedMs =
                measureTimeMillis { session.download("workdir", workspace.config.workdir, DownloadMode.MIRROR) }
            logger.debug("Process {}: workdir sync-down took {} ms", processId, elapsedMs)
        }.onFailure { logger.error("Sync-down of workdir failed for process {}: {}", processId, it.message, it) }
    } else {
        runCatching { session.cleanWorkspace() }
            .onFailure { logger.warn("Backend workspace cleanup failed for process {}: {}", processId, it.message) }
    }
    activeSessions.remove(session)
    runCatching { session.close() }
}
