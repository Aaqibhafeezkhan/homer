package homer.app.runners.kubernetes

import homer.app.EnvConstants
import homer.app.exceptions.PodStartupException
import homer.app.models.PodPhase
import homer.app.models.PodStartupFailure
import homer.app.models.RunnerConfig
import homer.app.models.RunnerState
import homer.app.runners.RemotePathTranslator
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import kotlin.time.Duration.Companion.seconds

class KubernetesRunner(
    override val alias: String,
    private val config: RunnerConfig.Kubernetes,
    private val api: CoreV1Api,
    private val execClient: KubernetesExecClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stateSink: RunnerStateSink = RunnerStateSink.NOOP,
    private val tracer: Tracer? = null,
    private val instanceId: String = "",
    override val sysEnvBackedEnvNames: Set<String> = emptySet(),
) : Runner {
    override val isRemote: Boolean = true

    override val runnerEnv: Map<String, String> = config.env

    private val podSpecBuilder = PodSpecBuilder(alias, config, instanceId)

    @Suppress("SuspendFunSwallowedCancellation") // records the span error and rethrows
    override suspend fun openSession(params: RunnerSessionParams): RunnerSession =
        withContext(Dispatchers.IO) {
            val podName = generatePodName(params.processId)
            val span =
                tracer
                    ?.spanBuilder("runner.session.open")
                    ?.setParent(params.parentContext)
                    ?.startSpan()
                    ?.apply {
                        setAttribute("runner.alias", alias)
                        setAttribute("pod.namespace", config.namespace)
                        setAttribute("pod.name", podName)
                    }
            val openStartMs = clock()
            val sessionEnv = buildSessionEnv()
            try {
                provisionPod(params, podName)
                val shell =
                    KubernetesShellExecutor(
                        execClient = execClient,
                        api = api,
                        namespace = config.namespace,
                        podName = podName,
                        maxOutputBytes = params.workspace.maxOutputBytes,
                    )
                span?.setAttribute("pod.start_duration_ms", clock() - openStartMs)
                span?.setStatus(StatusCode.OK)
                newSession(params, podName, shell, sessionEnv)
            } catch (e: PodStartupException) {
                span?.setAttribute("pod.startup_failure.reason", e.reason)
                span?.recordException(e)
                span?.setStatus(StatusCode.ERROR, e.rawMessage)
                throw e
            } catch (e: Throwable) {
                span?.recordException(e)
                span?.setStatus(StatusCode.ERROR, e.message ?: "runner.session.open failed")
                throw e
            } finally {
                span?.end()
            }
        }

    /** Creates the pod and waits for it to be ready, emitting each phase to [stateSink]. */
    private suspend fun provisionPod(
        params: RunnerSessionParams,
        podName: String,
    ) {
        val pod = podSpecBuilder.build(params, podName)
        val createdAtSec = clock() / 1000
        emitState(params.processId) {
            RunnerState(
                alias = alias,
                podName = podName,
                podPhase = PodPhase.CREATING,
                createdAt = createdAtSec,
            )
        }
        createPod(pod, podName, params.processId)
        emitState(params.processId) { prev ->
            (prev ?: base(podName, createdAtSec)).copy(podPhase = PodPhase.PENDING)
        }
        waitForReady(podName, params.processId)
        emitState(params.processId) { prev ->
            val readyAtSec = clock() / 1000
            (prev ?: base(podName, createdAtSec)).copy(
                podPhase = PodPhase.READY,
                readyAt = readyAtSec,
            )
        }
    }

    /** Wraps the ready pod in its session: tar-over-exec sync and the remote path layout (ADR-0043). */
    private fun newSession(
        params: RunnerSessionParams,
        podName: String,
        shell: KubernetesShellExecutor,
        sessionEnv: Map<String, String>,
    ): RunnerSession =
        KubernetesRunnerSession(
            parentContext = params.parentContext,
            podName = podName,
            namespace = config.namespace,
            api = api,
            shell = shell,
            keepPodOnFailure = config.keepPodOnFailure,
            keepPodTtlSeconds = config.keepPodTtlSeconds,
            runnerAlias = alias,
            sync = KubernetesWorkspaceSync(execClient, config.namespace, podName),
            translator =
                RemotePathTranslator(
                    serverProjectRoot = params.workspace.projectRoot.toString(),
                    remoteProjectRoot = KubernetesRunnerSession.REMOTE_PROJECT_ROOT,
                    remoteAgentDir = "${KubernetesRunnerSession.REMOTE_PROJECT_ROOT}/.homer/agent",
                    // HMR_API_URL already rides session.env (buildSessionEnv).
                    apiUrl = null,
                ),
            initialEnv = params.env,
            syncExcludes = config.syncExcludes,
            extraUploads = config.extraUploads,
            env = sessionEnv,
            tracer = tracer,
        )

    private fun buildSessionEnv(): Map<String, String> =
        buildMap {
            // config.env is layered via WorkspaceConfigFactory (runner.runnerEnv) — see ADR-0065.
            config.homerApiUrl?.takeIf { it.isNotBlank() }?.let { put(EnvConstants.API_URL, it) }
        }

    private fun base(
        podName: String,
        createdAtSec: Long,
    ) = RunnerState(
        alias = alias,
        podName = podName,
        podPhase = PodPhase.PENDING,
        createdAt = createdAtSec,
    )

    @Suppress("SuspendFunSwallowedCancellation") // best-effort status write; a kill surfaces at the pod wait's delay
    private suspend fun emitState(
        processId: Int,
        update: (RunnerState?) -> RunnerState,
    ) {
        runCatching { stateSink.update(processId, update) }
            .onFailure { log.debug("Failed to emit runner state for pid={}: {}", processId, it.message) }
    }

    private suspend fun createPod(
        pod: V1Pod,
        podName: String,
        processId: Int,
    ) {
        try {
            api.createNamespacedPod(config.namespace, pod).execute()
        } catch (e: ApiException) {
            val snap = PodDiagnostics.tryCollect(api, config.namespace, podName, "PodCreateFailed")
            val ex =
                PodStartupException(
                    snap.reason,
                    snap.message.ifBlank { e.responseBody ?: e.message ?: "pod create failed" },
                    snap.events,
                    e,
                )
            emitFailure(processId, ex)
            throw ex
        }
    }

    private suspend fun waitForReady(
        podName: String,
        processId: Int,
    ) {
        val startMs = clock()
        val budget = config.podStartTimeoutSeconds.seconds
        try {
            withTimeout(budget) {
                while (true) {
                    val pod =
                        try {
                            readPod(podName)
                        } catch (e: PodStartupException) {
                            cleanupFailedPod(podName)
                            emitFailure(processId, e)
                            throw e
                        }
                    if (pod.isTerminalFailure()) {
                        failStartup(podName, processId, "PodFailed", pod.terminalMessage() ?: "Pod reached terminal failure phase")
                    }
                    if (pod.isReady()) return@withTimeout
                    delay(READY_POLL_INTERVAL_MS)
                }
            }
        } catch (e: TimeoutCancellationException) {
            val elapsedSec = (clock() - startMs) / 1000
            failStartup(
                podName,
                processId,
                "PodStartTimeout",
                "Pod did not become ready within ${config.podStartTimeoutSeconds}s (elapsed=${elapsedSec}s)",
                cause = e,
            )
        }
    }

    private fun readPod(podName: String): V1Pod =
        try {
            api.readNamespacedPodStatus(podName, config.namespace).execute()
        } catch (e: ApiException) {
            throw PodStartupException("PodStatusUnreadable", e.responseBody ?: (e.message ?: "unreadable"), emptyList(), e)
        }

    /** Startup-failure teardown: same keep-or-delete choice as session close. */
    private fun cleanupFailedPod(podName: String) {
        runCatching {
            if (config.keepPodOnFailure && podStartedOnNode(podName)) {
                PodCleanup.keepWithTtl(api, config.namespace, podName, config.keepPodTtlSeconds)
            } else {
                PodCleanup.delete(api, config.namespace, podName)
            }
        }.onFailure { log.debug("Failed to clean up pod after startup failure: {}", it.message) }
    }

    // activeDeadlineSeconds is enforced only relative to status.startTime; keeping a
    // never-scheduled pod would leak it past both the TTL and the kept-skipping sweep.
    private fun podStartedOnNode(podName: String): Boolean =
        runCatching {
            api
                .readNamespacedPod(podName, config.namespace)
                .execute()
                .status
                ?.startTime != null
        }.getOrDefault(false)

    private suspend fun failStartup(
        podName: String,
        processId: Int,
        fallbackReason: String,
        fallbackMessage: String,
        cause: Throwable? = null,
    ): Nothing {
        val snap = PodDiagnostics.tryCollect(api, config.namespace, podName, fallbackReason)
        cleanupFailedPod(podName)
        val ex =
            PodStartupException(
                reason = snap.reason.ifBlank { fallbackReason },
                rawMessage = snap.message.ifBlank { fallbackMessage },
                events = snap.events,
                cause = cause,
            )
        emitFailure(processId, ex)
        throw ex
    }

    private suspend fun emitFailure(
        processId: Int,
        ex: PodStartupException,
    ) {
        val endedAtSec = clock() / 1000
        emitState(processId) { prev ->
            (
                prev ?: RunnerState(
                    alias = alias,
                    podPhase = PodPhase.FAILED,
                )
            ).copy(
                podPhase = PodPhase.FAILED,
                endedAt = endedAtSec,
                startupFailure =
                    PodStartupFailure(
                        reason = ex.reason,
                        message = ex.rawMessage,
                        events = ex.events,
                    ),
            )
        }
    }

    /**
     * Namespace this runner targets. Exposed for the startup sweep
     * so it can log which namespace was scanned.
     */
    val namespace: String get() = config.namespace

    /**
     * List every Homer-managed pod belonging to THIS instance in this runner's
     * namespace and delete any whose `homer.app/process-id` label is not in the
     * `activeProcessIds` set. When [instanceId] is set the list selector is scoped
     * to `homer.app/instance=<id>` so a boot never deletes another Homer instance's
     * pods in a shared namespace; when unset (blank) it falls back to the historic
     * namespace-wide selector. Returns a summary for telemetry / health reporting.
     * Never throws; individual failures are reported via `SweepResult.errors`.
     */
    fun sweepOrphans(activeProcessIds: Set<Int>): SweepResult {
        val selector =
            buildString {
                append("${PodLabels.MANAGED_BY}=${PodLabels.MANAGED_BY_VALUE}")
                if (instanceId.isNotBlank()) append(",${PodLabels.INSTANCE}=$instanceId")
            }
        val pods: V1PodList =
            try {
                api
                    .listNamespacedPod(config.namespace)
                    .labelSelector(selector)
                    .execute()
            } catch (e: ApiException) {
                return SweepResult(
                    scanned = 0,
                    deleted = emptyList(),
                    errors = listOf("list pods failed: ${e.code} ${e.responseBody ?: e.message}"),
                )
            }

        val items = pods.items.orEmpty()
        val deleted = mutableListOf<SweepStatus.DeletedPod>()
        val errors = mutableListOf<String>()
        for (pod in items) {
            val orphan = orphanOf(pod, activeProcessIds) ?: continue
            deleteOrphan(orphan, deleted, errors)
        }
        return SweepResult(scanned = items.size, deleted = deleted, errors = errors)
    }

    private data class OrphanPod(
        val name: String,
        val processId: Int?,
        val runnerAlias: String?,
    )

    /** [pod] as an orphan to delete: null when it is unnamed, belongs to an active process, or is kept for debugging. */
    private fun orphanOf(
        pod: V1Pod,
        activeProcessIds: Set<Int>,
    ): OrphanPod? {
        val meta = pod.metadata ?: return null
        val name = meta.name ?: return null
        val labels = meta.labels.orEmpty()
        val pidLabel = labels[PodLabels.PROCESS_ID]
        val pid = pidLabel?.toIntOrNull()
        if (pid != null && pid in activeProcessIds) return null
        if (labels[PodLabels.KEPT] == "true") {
            log.info("Sweep skipping kept-for-debug pod {} (process {}) — bounded by its own TTL", name, pid)
            return null
        }
        return OrphanPod(name, pid, labels[PodLabels.RUNNER])
    }

    /** Deletes [orphan], recording it in [deleted]; a pod already gone is no error. */
    private fun deleteOrphan(
        orphan: OrphanPod,
        deleted: MutableList<SweepStatus.DeletedPod>,
        errors: MutableList<String>,
    ) {
        val name = orphan.name
        try {
            api.deleteNamespacedPod(name, config.namespace).gracePeriodSeconds(0).execute()
            deleted.add(
                SweepStatus.DeletedPod(
                    namespace = config.namespace,
                    name = name,
                    processId = orphan.processId,
                    runnerAlias = orphan.runnerAlias,
                ),
            )
        } catch (e: ApiException) {
            if (e.code == 404) return
            errors.add("delete $name failed: ${e.code} ${e.responseBody ?: e.message}")
        }
    }

    data class SweepResult(
        val scanned: Int,
        val deleted: List<SweepStatus.DeletedPod>,
        val errors: List<String>,
    )

    private fun generatePodName(processId: Int): String {
        val suffix =
            buildString {
                repeat(6) { append(POD_NAME_CHARS[random.nextInt(POD_NAME_CHARS.length)]) }
            }
        return "homer-$processId-$suffix".take(63)
    }

    companion object {
        private const val READY_POLL_INTERVAL_MS = 500L
        private const val POD_NAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val random = SecureRandom()
        private val log = LoggerFactory.getLogger(KubernetesRunner::class.java)
    }
}
