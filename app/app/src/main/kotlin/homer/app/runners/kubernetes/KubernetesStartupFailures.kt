package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import homer.app.models.RunnerConfig
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import org.slf4j.Logger

/** Diagnosis, teardown and reporting of a Kubernetes workload that failed to start. */
internal class KubernetesStartupFailures(
    private val api: CoreV1Api,
    private val config: RunnerConfig.Kubernetes,
    private val batchApi: BatchV1Api?,
    private val logger: Logger,
) {
    /**
     * Startup-failure teardown, the same choice as session close: keeps the pod [podName] (and labels the Job
     * [jobName] kept) when `keepPodOnFailure` applies, else deletes the Job, then the pod.
     */
    fun cleanupFailed(
        podName: String?,
        jobName: String?,
    ) {
        val teardown = WorkloadTeardown(api, config.namespace, jobName, batchApi)
        runCatching {
            if (podName != null && config.keepPodOnFailure && podStartedOnNode(podName)) {
                teardown.keep(podName, config.keepPodTtlSeconds, config.maxRunSeconds)
            } else {
                teardown.delete(podName)
            }
        }.onFailure { logger.debug("Failed to clean up pod after startup failure: {}", it.message) }
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

    /** Fails the startup; it diagnoses and cleans up the pod [podName] when there is one, and in Job mode the Job. */
    suspend fun failStartup(
        podName: String?,
        states: RunnerStateEmitter,
        fallbackReason: String,
        fallbackMessage: String,
        cause: Throwable? = null,
    ): Nothing {
        val snap = diagnose(podName, states.jobName, fallbackReason, fallbackMessage)
        cleanupFailed(podName, states.jobName)
        reportStartupFailure(
            states,
            PodStartupException(
                reason = snap.reason.ifBlank { fallbackReason },
                rawMessage = snap.message.ifBlank { fallbackMessage },
                events = snap.events,
                cause = cause,
            ),
        )
    }

    suspend fun reportStartupFailure(
        states: RunnerStateEmitter,
        ex: PodStartupException,
    ): Nothing {
        states.startupFailed(ex)
        throw ex
    }

    /** The pod's diagnostics; without a pod, the Job's conditions and events under the given reason and message. */
    private fun diagnose(
        podName: String?,
        jobName: String?,
        reason: String,
        message: String,
    ): PodDiagnostics.Snapshot {
        if (podName != null) return PodDiagnostics.tryCollect(api, config.namespace, podName, reason)
        val events = jobName?.let { PodDiagnostics(api, config.namespace).collectJob(it, checkNotNull(batchApi)).events }
        return PodDiagnostics.Snapshot(reason, message, events.orEmpty())
    }
}
