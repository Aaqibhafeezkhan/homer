package homer.app.runners

import homer.app.infra.ShellExecutor
import homer.app.models.ExtraUploadSpec
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * How [RunnerSession.download] reconciles remote content with what already
 * exists at the local target.
 */
enum class DownloadMode {
    /** Keep local files that already exist; only entries missing locally are pulled. */
    OVERLAY,

    /** Remote content replaces existing local files; local-only extras are kept. */
    OVERWRITE,

    /**
     * OVERWRITE, plus local extras absent from the remote listing are deleted —
     * but only after a fully successful download; a partial/failed transfer or a
     * missing remote path leaves the local target untouched. Single-file downloads
     * have no extras scope and are treated as OVERWRITE. See ADR-0055.
     */
    MIRROR,
}

/**
 * A live execution session produced by a Runner. Owns the shell used
 * for command execution and any backing resources (pod, connection, ...)
 * that must be released when the session ends.
 *
 * The workspace/transfer methods are mechanism primitives with no-op
 * defaults: a backend whose execution filesystem is the server's own
 * (local) or shared with it (kubernetes PVC) implements nothing. Sync
 * *policy* — what to transfer and when — belongs to the orchestrator
 * (AgentExecutor), not to sessions. See ADR-0033.
 *
 * close() is idempotent and safe to call from cancellation paths.
 */
interface RunnerSession : AutoCloseable {
    val shell: ShellExecutor

    /**
     * Alias of the runner that produced this session, used to annotate
     * command-execution spans with the backend the command ran on.
     */
    val runnerAlias: String

    /**
     * Additional environment variables contributed by this session. Merged
     * into the workspace env before commands run (e.g. HMR_API_URL for the
     * kubernetes runner, so in-pod RunAgent calls reach Homer's Service).
     */
    val env: Map<String, String>
        get() = emptyMap()

    /**
     * Backend-specific facts about this session's execution resource
     * (e.g. "pod.name" for kubernetes). Stamped onto command-execution
     * spans by AgentExecutor.
     */
    val metadata: Map<String, String>
        get() = emptyMap()

    /**
     * Operator-declared extra uploads this runner requires in every workspace
     * (ADR-0042). Mechanism only — the executors own when to upload them:
     * AgentExecutor alongside the workspace + agent-dir upload,
     * RunnerCmdExecutor after createWorkspace() and before checks.
     */
    val extraUploads: List<ExtraUploadSpec>
        get() = emptyList()

    /**
     * Create the backend-side workspace root. Returns env overrides that
     * make the workspace usable there (e.g. HMR_* paths translated to the
     * remote layout); empty when the backend shares the server filesystem.
     */
    suspend fun createWorkspace(): Map<String, String> = emptyMap()

    /**
     * Copy a local file or directory (recursive) into the backend
     * workspace. [remoteRelative] is resolved against the backend's
     * workspace root ("." = the root itself).
     *
     * [excludes] are paths relative to [local] that must not be transferred,
     * using the same bare-name/leading-`/` matching every backend's walk
     * already implements. The one caller that needs them is the agent-dir
     * upload: a parent agent's `HMR_AGENT_DIR` must not carry a nested agent's
     * directory to the runner (ADR-0068, ADR-0047). **An override that
     * transfers anything must honour [excludes]** — dropping them silently
     * ships the ADR-0047 parent/child leak. The no-op default here copies
     * nothing (the local runner shares the filesystem), so it only warns.
     */
    suspend fun upload(
        local: Path,
        remoteRelative: String,
        excludes: List<String> = emptyList(),
    ) {
        if (excludes.isNotEmpty()) {
            LoggerFactory.getLogger(RunnerSession::class.java).warn(
                "upload() default ignored {} exclude(s) for '{}' — a backend that copies must honour excludes (ADR-0047)",
                excludes.size,
                remoteRelative,
            )
        }
    }

    /**
     * Copy a backend workspace file or directory (recursive) back to [local].
     * [mode] controls how remote content reconciles with what's already at
     * [local] — see [DownloadMode]. Defaults to [DownloadMode.OVERLAY].
     */
    suspend fun download(
        remoteRelative: String,
        local: Path,
        mode: DownloadMode = DownloadMode.OVERLAY,
    ) {
    }

    /** Remove the backend-side workspace root recursively. */
    suspend fun cleanWorkspace() {
    }

    /**
     * Mark this session's run as failed before close(). Lets backends with a
     * keep-on-failure debugging mode (kubernetes keepPodOnFailure) retain the
     * execution resource. Kills are not failures — the caller decides.
     */
    fun markFailed() {
    }

    /** Release the backing resource (close connection, delete pod). */
    override fun close()
}
