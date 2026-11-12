package homer.app.runners.kubernetes

import homer.app.EnvConstants
import homer.app.exceptions.PodStartupException
import homer.app.models.KubernetesWorkload
import homer.app.models.RunnerConfig
import homer.app.runners.RemotePathTranslator
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1Pod
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    // Job mode only; Pod mode makes no batch call (ADR-0100).
    private val batchApi: BatchV1Api? = null,
) : Runner {
    init {
        require(config.workload == KubernetesWorkload.Pod || batchApi != null) {
            "kubernetes runner '$alias': workload Job needs a BatchV1Api"
        }
    }

    override val isRemote: Boolean = true

    override val runnerEnv: Map<String, String> = config.env

    private val podSpecBuilder = PodSpecBuilder(alias, config, instanceId)
    private val startupFailures = KubernetesStartupFailures(api, config, batchApi, log)

    @Suppress("SuspendFunSwallowedCancellation") // records the span error and rethrows
    override suspend fun openSession(params: RunnerSessionParams): RunnerSession =
        withContext(Dispatchers.IO) {
            // Names the pod in Pod mode and the Job in Job mode, where the Job controller names the pod.
            val name = generatePodName(params.processId)
            val jobName = name.takeIf { config.workload == KubernetesWorkload.Job }
            val states = RunnerStateEmitter(alias, params.processId, jobName, clock, stateSink)
            val span = openSpan(params, name, jobName)
            val openStartMs = clock()
            // The pod's creation stamp: provisioning emits it as the runner state's `createdAt`, and
            // the session clamps a skewed `status.startTime` to it (ADR-0106). Read here, once.
            val podCreatedAtSec = clock() / 1000
            val sessionEnv = buildSessionEnv()
            try {
                val podName =
                    if (jobName == null) {
                        provisionPod(params, name, states, podCreatedAtSec)
                    } else {
                        provisionJob(params, jobName, states, podCreatedAtSec)
                    }
                val shell = newShell(params, podName, states, openStartMs)
                span?.setAttribute("pod.start_duration_ms", clock() - openStartMs)
                span?.setStatus(StatusCode.OK)
                newSession(params, podName, jobName, shell, sessionEnv, podCreatedAtSec)
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

    private fun openSpan(
        params: RunnerSessionParams,
        name: String,
        jobName: String?,
    ): Span? =
        tracer
            ?.spanBuilder("runner.session.open")
            ?.setParent(params.parentContext)
            ?.startSpan()
            ?.apply {
                setAttribute("runner.alias", alias)
                setAttribute("pod.namespace", config.namespace)
                if (jobName == null) setAttribute("pod.name", name) else setAttribute("job.name", jobName)
            }

    private fun newShell(
        params: RunnerSessionParams,
        podName: String,
        states: RunnerStateEmitter,
        openStartMs: Long,
    ) = KubernetesShellExecutor(
        execClient = execClient,
        api = api,
        namespace = config.namespace,
        podName = podName,
        maxOutputBytes = params.workspace.maxOutputBytes,
        maxRunSeconds = config.maxRunSeconds,
        onTermination = { t -> states.terminated(t, podName, openStartMs / 1000) },
    )

    /** Creates the pod and waits for it to be ready, emitting each phase to [states]; returns [podName]. */
    private suspend fun provisionPod(
        params: RunnerSessionParams,
        podName: String,
        states: RunnerStateEmitter,
        createdAtSec: Long,
    ): String {
        val pod = podSpecBuilder.build(params, podName)
        states.creating(podName, createdAtSec)
        createPod(pod, podName, states)
        states.pending(podName, createdAtSec)
        withinStartBudget(states, { podName }, { "Pod did not become ready within ${config.podStartTimeoutSeconds}s (elapsed=${it}s)" }) {
            pollUntilReady(podName, states)
        }
        states.ready(podName, createdAtSec)
        return podName
    }

    /**
     * Creates the one-pod Job, finds its pod by the Job's selector and waits for it to be ready, all under one
     * `podStartTimeoutSeconds` budget, emitting each phase to [states]; returns the pod's name. A failed or
     * cancelled startup deletes the Job, unless `keepPodOnFailure` keeps its pod. See ADR-0100.
     */
    @Suppress("SuspendFunSwallowedCancellation") // deletes the Job on any throwable, then rethrows
    private suspend fun provisionJob(
        params: RunnerSessionParams,
        jobName: String,
        states: RunnerStateEmitter,
        createdAtSec: Long,
    ): String {
        val job = podSpecBuilder.buildJob(params, jobName)
        states.creating(null, createdAtSec)
        val check = JobStartupCheck(api, checkNotNull(batchApi), config.namespace, jobName)
        var found: String? = null
        try {
            // Inside the try: a create that landed but whose answer the client cannot parse is deleted by jobName.
            val created = createJob(job, states)
            val selector =
                jobPodSelector(created)
                    ?: startupFailures.failStartup(
                        null,
                        states,
                        "PodCreateFailed",
                        "Job $jobName was created without a pod selector or uid",
                    )
            states.pending(null, createdAtSec)
            val podName =
                withinStartBudget(states, { found }, { check.timeoutMessage(config.podStartTimeoutSeconds, it) }) {
                    val jobPod = pollForJobPod(selector, check, states)
                    found = jobPod
                    states.podFound(jobPod, createdAtSec)
                    pollUntilReady(jobPod, states)
                    jobPod
                }
            states.ready(podName, createdAtSec)
            return podName
        } catch (e: PodStartupException) {
            throw e // past failStartup's cleanup, which may have kept the pod and its Job
        } catch (e: Throwable) {
            // A cancelled or broken startup must not leave the Job controller retrying pod creation.
            withContext(NonCancellable) { WorkloadTeardown(api, config.namespace, jobName, batchApi).delete(found) }
            throw e
        }
    }

    /** Wraps the ready pod in its session: tar-over-exec sync and the remote path layout (ADR-0043). */
    private fun newSession(
        params: RunnerSessionParams,
        podName: String,
        jobName: String?,
        shell: KubernetesShellExecutor,
        sessionEnv: Map<String, String>,
        podCreatedAtSec: Long,
    ): RunnerSession =
        KubernetesRunnerSession(
            parentContext = params.parentContext,
            podName = podName,
            namespace = config.namespace,
            api = api,
            shell = shell,
            keepPodOnFailure = config.keepPodOnFailure,
            keepPodTtlSeconds = config.keepPodTtlSeconds,
            maxRunSeconds = config.maxRunSeconds,
            gracefulStopSeconds = config.gracefulStopSeconds,
            podCreatedAtEpochSec = podCreatedAtSec,
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
            jobName = jobName,
            batchApi = batchApi,
        )

    private fun buildSessionEnv(): Map<String, String> =
        buildMap {
            // config.env is layered via WorkspaceConfigFactory (runner.runnerEnv) — see ADR-0065.
            config.homerApiUrl?.takeIf { it.isNotBlank() }?.let { put(EnvConstants.API_URL, it) }
        }

    private suspend fun createPod(
        pod: V1Pod,
        podName: String,
        states: RunnerStateEmitter,
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
            states.startupFailed(ex)
            throw ex
        }
    }

    private suspend fun createJob(
        job: V1Job,
        states: RunnerStateEmitter,
    ): V1Job =
        try {
            checkNotNull(batchApi).createNamespacedJob(config.namespace, job).execute()
        } catch (e: ApiException) {
            val message = e.responseBody ?: e.message ?: "Job create failed"
            // 409: the name is another Job's, never read or deleted. Otherwise the delete covers a create
            // that landed although its answer failed; a 404 is tolerated.
            if (e.code == HTTP_CONFLICT) {
                startupFailures.reportStartupFailure(states, PodStartupException("PodCreateFailed", message, emptyList(), e))
            }
            startupFailures.failStartup(null, states, "PodCreateFailed", message, e)
        }

    /**
     * Runs [block] under the one `podStartTimeoutSeconds` budget; on expiry fails the startup as
     * `PodStartTimeout` with [timeoutMessage] of the elapsed seconds, diagnosing and cleaning up the pod
     * [podName] names, if there is one yet.
     */
    private suspend fun <T> withinStartBudget(
        states: RunnerStateEmitter,
        podName: () -> String?,
        timeoutMessage: (Long) -> String,
        block: suspend CoroutineScope.() -> T,
    ): T {
        val startMs = clock()
        try {
            return withTimeout(config.podStartTimeoutSeconds.seconds, block)
        } catch (e: TimeoutCancellationException) {
            startupFailures.failStartup(podName(), states, "PodStartTimeout", timeoutMessage((clock() - startMs) / 1000), cause = e)
        }
    }

    /** Polls the Job's pods until the Job controller has created one, running [check] while there is none. */
    private suspend fun pollForJobPod(
        selector: String,
        check: JobStartupCheck,
        states: RunnerStateEmitter,
    ): String {
        while (true) {
            val podName =
                try {
                    findJobPod(api, config.namespace, selector)
                } catch (e: PodStartupException) {
                    startupFailures.failStartup(null, states, e.reason, e.rawMessage, e)
                }
            if (podName != null) return podName
            check.failure()?.let { (reason, message) -> startupFailures.failStartup(null, states, reason, message) }
            delay(JOB_POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollUntilReady(
        podName: String,
        states: RunnerStateEmitter,
    ) {
        while (true) {
            val pod =
                try {
                    readPod(podName)
                } catch (e: PodStartupException) {
                    startupFailures.cleanupFailed(podName, states.jobName)
                    states.startupFailed(e)
                    throw e
                }
            if (pod.isTerminalFailure()) {
                startupFailures.failStartup(podName, states, "PodFailed", pod.terminalMessage() ?: "Pod reached terminal failure phase")
            }
            if (pod.isReady()) return
            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private fun readPod(podName: String): V1Pod =
        try {
            api.readNamespacedPodStatus(podName, config.namespace).execute()
        } catch (e: ApiException) {
            throw PodStartupException("PodStatusUnreadable", e.responseBody ?: (e.message ?: "unreadable"), emptyList(), e)
        }

    /**
     * Namespace this runner targets. Exposed for the startup sweep
     * so it can log which namespace was scanned.
     */
    val namespace: String get() = config.namespace

    /** Deletes this instance's orphan pods, and in Job mode its orphan Jobs: see [sweepWorkloadOrphans]. Never throws. */
    fun sweepOrphans(activeProcessIds: Set<Int>): SweepResult =
        sweepWorkloadOrphans(api, batchApi.takeIf { config.workload == KubernetesWorkload.Job }, namespace, instanceId, activeProcessIds)

    data class SweepResult(
        val scanned: Int,
        val deleted: List<SweepStatus.DeletedPod>,
        val errors: List<String>,
        /** Null in Pod mode; in Job mode the orphan Jobs deleted, possibly none. */
        val deletedJobs: List<SweepStatus.DeletedJob>? = null,
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
        private const val JOB_POLL_INTERVAL_MS = 1000L
        private const val HTTP_CONFLICT = 409
        private const val POD_NAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val random = SecureRandom()
        private val log = LoggerFactory.getLogger(KubernetesRunner::class.java)
    }
}
