package homer.app.runners.external

import homer.app.EnvConstants
import homer.app.infra.ShellExecutor
import homer.app.models.ExtraUploadSpec
import homer.app.runners.DownloadMode
import homer.app.runners.RemotePathTranslator
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import homer.app.runners.TarStreams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What an `upload` op sends: the tar route packs [local]; a regular file is packed
 * under `remoteRelative`'s last segment. [excludes] are root-relative paths the pack
 * must leave out — they ride here rather than on the op, because packing stays
 * server-side (the op protocol gains no field).
 */
class UploadSource(
    val local: Path,
    val remoteRelative: String,
    val excludes: List<String> = emptyList(),
)

/** Where a `download` op lands locally; the tar route fills [materialized] for MIRROR. */
class DownloadTarget(
    val local: Path,
    val mode: DownloadMode,
    val remoteRelative: String,
) {
    @Volatile
    var materialized: Set<Path>? = null
}

/**
 * A `RunnerSession` whose primitives run on a pull-mode instance: each one is a
 * `RunnerOp` the instance fetches through `next` and answers with an `OpResult`.
 * Tars and output ride their own routes and are matched to the op by id. Every
 * wait is bounded by the spec deadlines; a lost instance fails the pending op
 * through [fail]. `root` from `createWorkspace` is only used for env values and
 * is never opened on Homer's host (§12). See ADR-0066.
 */
class ExternalRunnerSession(
    val id: String,
    val instance: ExternalRunnerInstance,
    private val domain: ExternalRunnerDomain,
    private val params: RunnerSessionParams,
    private val onClosed: (ExternalRunnerSession) -> Unit,
) : RunnerSession {
    val mailbox = OpMailbox()

    private val sinks = ConcurrentHashMap<String, OutputSink>()
    private val uploads = ConcurrentHashMap<String, UploadSource>()
    private val downloads = ConcurrentHashMap<String, DownloadTarget>()
    private val closed = AtomicBoolean(false)
    private val openNextPolls = AtomicInteger(0)
    private val openTransfers = ConcurrentHashMap<String, Any>()
    private val heartbeat: Duration = domain.config.heartbeatTimeoutSec.seconds
    private val transfer: Duration = domain.config.transferTimeoutSec.seconds

    @Volatile
    var remoteWorkdir: String = ""
        private set

    @Volatile
    var closedAt: Long? = null
        private set

    override val shell: ShellExecutor = ExternalShellExecutor(this, params.workspace.maxOutputBytes, heartbeat)
    override val runnerAlias: String get() = domain.alias
    override val metadata: Map<String, String> get() = mapOf("runner.instance" to instance.name)
    override val extraUploads: List<ExtraUploadSpec> get() = domain.config.extraUploads

    override suspend fun createWorkspace(): Map<String, String> {
        val result = await(mailbox.submit(RunnerOp.CreateWorkspace(RunnerProtocol.newId())), heartbeat, "createWorkspace")
        val root =
            result.root
                ?.takeIf { it.startsWith("/") && Char.MIN_VALUE !in it }
                ?.trimEnd('/')
                ?.ifEmpty { "/" }
                ?: throw ExternalRunnerException("Runner instance '${instance.name}' returned no absolute workspace root")
        val translator =
            RemotePathTranslator(
                serverProjectRoot = params.workspace.projectRoot.toString(),
                remoteProjectRoot = root,
                remoteAgentDir = "$root/.homer/agent",
                apiUrl = domain.config.homerApiUrl,
            )
        val overrides = translator.overrides(params.env)
        remoteWorkdir = overrides[EnvConstants.WORK_DIR] ?: "$root/workdir"
        return overrides + extraUploads.mapNotNull { spec -> spec.env?.let { it to "$root/${spec.remote}" } }
    }

    override suspend fun upload(
        local: Path,
        remoteRelative: String,
        excludes: List<String>,
    ) {
        val op = RunnerOp.Upload(RunnerProtocol.newId(), remoteRelative, isDir = local.isDirectory())
        uploads[op.opId] = UploadSource(local, remoteRelative, excludes)
        try {
            await(mailbox.submit(op), transfer, "upload of '$remoteRelative'")
        } finally {
            uploads.remove(op.opId)
        }
    }

    override suspend fun download(
        remoteRelative: String,
        local: Path,
        mode: DownloadMode,
    ) {
        val op = RunnerOp.Download(RunnerProtocol.newId(), remoteRelative, mode)
        val target = DownloadTarget(local, mode, remoteRelative)
        downloads[op.opId] = target
        try {
            val result = await(mailbox.submit(op), transfer, "download of '$remoteRelative'")
            // Prune only after the instance confirmed a complete, present download (ADR-0055).
            if (mode == DownloadMode.MIRROR && !result.missing) target.materialized?.let { TarStreams.prune(local, it) }
        } finally {
            downloads.remove(op.opId)
        }
    }

    override suspend fun cleanWorkspace() {
        await(mailbox.submit(RunnerOp.CleanWorkspace(RunnerProtocol.newId())), heartbeat, "cleanWorkspace")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mailbox.post(RunnerOp.Close(RunnerProtocol.newId(), "session closed"))
        mailbox.fail(ExternalRunnerException("session closed"))
        closedAt = domain.now()
        onClosed(this)
    }

    /**
     * The instance is gone: every pending op, and every later one, fails the run.
     * Takes the same `closed` CAS as [close] so a later close cannot fire [onClosed]
     * a second time, but posts no Close op — nothing is left to collect it.
     */
    fun fail(cause: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        mailbox.fail(cause)
        closedAt = domain.now()
        onClosed(this)
    }

    /**
     * At most [MAX_NEXT_POLLS] `next` long-polls may be parked on one session:
     * each holds a connection and a coroutine for a full poll window, and the
     * session's own instance bound (`tryAcquirePoll`) covers `claim` only, so
     * without this an authenticated instance could park unbounded polls here
     * (§12 rule 4). The bound covers the wait, never the result a poll carries —
     * the route applies `completed` before acquiring.
     */
    fun tryAcquireNextPoll(): Boolean {
        while (true) {
            val current = openNextPolls.get()
            if (current >= MAX_NEXT_POLLS) return false
            if (openNextPolls.compareAndSet(current, current + 1)) return true
        }
    }

    fun releaseNextPoll() {
        openNextPolls.decrementAndGet()
    }

    /**
     * At most one request per transfer may be in flight on this session — per op for
     * a tar, per op and stream for output (§12 rule 4). Each holds a `Dispatchers.IO`
     * thread and a connection for up to `transferTimeoutSec`, and nothing else stops
     * an instance repeating one request concurrently; with this the total follows
     * the ops Homer itself issued, not the caller. `homer-runner` overlaps them only
     * when it retries an output post that timed out on its side, and it retries a
     * shed one too. [key] is the op id, plus `/stream` for output. Returns the token
     * [endTransfer] takes, or null when [key] is already in flight.
     */
    fun tryBeginTransfer(key: String): Any? {
        val token = Any()
        return if (openTransfers.putIfAbsent(key, token) == null) token else null
    }

    /** Releases [key] only while [token] still holds it, so a second release is a no-op. */
    fun endTransfer(
        key: String,
        token: Any,
    ) {
        openTransfers.remove(key, token)
    }

    fun registerSink(
        opId: String,
        sink: OutputSink,
    ) {
        sinks[opId] = sink
    }

    fun unregisterSink(opId: String) {
        sinks.remove(opId)
    }

    fun sink(opId: String): OutputSink? = sinks[opId]

    fun uploadSource(opId: String): UploadSource? = uploads[opId]

    fun downloadTarget(opId: String): DownloadTarget? = downloads[opId]

    fun cancelCmd(opId: String) = mailbox.post(RunnerOp.Cancel(opId))

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun await(
        deferred: CompletableDeferred<OpResult>,
        deadline: Duration,
        what: String,
    ): OpResult {
        val result =
            withTimeoutOrNull(deadline) { deferred.await() }
                ?: if (deferred.isCompleted) {
                    deferred.getCompleted()
                } else {
                    throw ExternalRunnerException(
                        "Runner instance '${instance.name}' did not answer $what within ${deadline.inWholeSeconds} s",
                    )
                }
        if (!result.ok) {
            throw ExternalRunnerException(
                "$what failed on runner instance '${instance.name}': ${sanitizedError(result.error)}",
            )
        }
        return result
    }

    /**
     * The instance writes [OpResult.error] and this text reaches logs and the run's failure
     * message, so it is attacker-controlled data sanitised at the point of use (§12 rule 2):
     * newlines become spaces so it cannot forge a log line, and it is capped so it cannot
     * flood the log. Internal, not private: [ExternalShellExecutor] carries the same field
     * of the same instance into its own exception text and must use this one sanitiser.
     */
    internal fun sanitizedError(error: String?): String {
        val raw = error ?: return "unknown error"
        // Cap before flattening: the instance-supplied text can be arbitrarily long.
        val capped = if (raw.length > MAX_ERROR_CHARS) raw.take(MAX_ERROR_CHARS) + "..." else raw
        return capped.replace('\n', ' ').replace('\r', ' ')
    }

    companion object {
        /** Longest instance-supplied error text carried into an exception message. */
        const val MAX_ERROR_CHARS = 512

        /**
         * Three, not two: the reference runner keeps one dedicated poller open, and a
         * result-carrying `next` that loses the race for the following op stays parked
         * for a full poll window while the worker already executes that op and posts its
         * own result. Poller + one parked delivery + one delivering is the legitimate
         * maximum; a fourth concurrent poll is a misbehaving instance.
         */
        const val MAX_NEXT_POLLS = 3
    }
}
