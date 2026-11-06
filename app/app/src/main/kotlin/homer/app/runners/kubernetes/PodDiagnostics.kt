package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.CoreV1Event
import io.kubernetes.client.openapi.models.V1ContainerStatus
import io.kubernetes.client.openapi.models.V1JobCondition
import io.kubernetes.client.openapi.models.V1Pod
import java.util.Objects

/**
 * Collects Kubernetes events + container statuses for a pod so that
 * startup failures / mid-run failures surface a readable message
 * instead of an opaque timeout or socket error.
 */
class PodDiagnostics(
    private val api: CoreV1Api,
    private val namespace: String,
) {
    data class Snapshot(
        val reason: String,
        val message: String,
        val events: List<String>,
    )

    fun collect(
        podName: String,
        fallbackReason: String = "Unknown",
    ): Snapshot {
        val pod = runCatching { api.readNamespacedPodStatus(podName, namespace).execute() }.getOrNull()
        val events = listEvents(podName)
        val (reason, message) = extractFailure(pod) ?: (fallbackReason to (events.lastOrNull() ?: "No diagnostic details available"))
        return Snapshot(reason = reason, message = message, events = events)
    }

    /**
     * The Job [jobName]'s conditions, then its events (newest last), as event lines; the reason is
     * `JobFailed` with the `Failed` condition's message when the Job has failed. See ADR-0100.
     */
    fun collectJob(
        jobName: String,
        batchApi: BatchV1Api,
    ): Snapshot {
        val conditions = readJobOrNull(batchApi, namespace, jobName)?.status?.conditions.orEmpty()
        val events = sortedEvents(api, namespace, jobEventSelector(jobName)).map(::eventLine)
        val failed = conditions.firstOrNull { it.isFailed() }
        return Snapshot(
            reason = if (failed != null) "JobFailed" else "Unknown",
            message = failed?.message ?: events.lastOrNull() ?: "No diagnostic details available",
            events = conditions.map(::conditionLine) + events,
        )
    }

    private fun listEvents(podName: String): List<String> = sortedEvents(api, namespace, "involvedObject.name=$podName").map(::eventLine)

    private fun eventLine(e: CoreV1Event): String = "${e.type ?: "?"}/${e.reason ?: "?"}: ${e.message ?: ""}".trim()

    private fun conditionLine(c: V1JobCondition): String =
        (listOf("Job/${c.type}=${c.status}") + listOfNotNull(c.reason, c.message)).joinToString(": ")

    private fun extractFailure(pod: V1Pod?): Pair<String, String>? {
        val status = pod?.status ?: return null
        val failed =
            (status.initContainerStatuses.orEmpty() + status.containerStatuses.orEmpty())
                .firstOrNull { it.hasFailure() }
        if (failed != null) return failureOf(failed)
        val phase = status.phase
        if (phase == "Failed") return (status.reason ?: "PodFailed") to (status.message ?: "Pod reached Failed phase")
        return null
    }

    /**
     * The reason and message of the failed container [failed]: its terminated state's, else its waiting state's.
     * A terminated state's message is its termination log, which the agent can write, so its exit code stands
     * in for it (ADR-0100 § Security).
     */
    private fun failureOf(failed: V1ContainerStatus): Pair<String, String> {
        val t = failed.state?.terminated
        val w = failed.state?.waiting
        val reason = t?.reason ?: w?.reason ?: "ContainerFailed"
        val msg =
            t?.let { "Container '${failed.name}' terminated with exit code ${Objects.toString(it.exitCode, "unknown")}" }
                ?: w?.message
                ?: "Container '${failed.name}' failed"
        return reason to msg
    }

    private fun V1ContainerStatus.hasFailure(): Boolean {
        val t = state?.terminated
        // The client's @Nonnull exitCode is not enforced, so it can still read null here.
        if ((t?.exitCode ?: 0) != 0) return true
        val w = state?.waiting
        if (w != null && w.reason in FAILURE_WAIT_REASONS) return true
        return false
    }

    companion object {
        private val FAILURE_WAIT_REASONS =
            setOf(
                "CrashLoopBackOff",
                "ImagePullBackOff",
                "ErrImagePull",
                "CreateContainerError",
                "CreateContainerConfigError",
                "InvalidImageName",
            )

        /** The events matching [fieldSelector], oldest first; empty when they cannot be listed. */
        internal fun sortedEvents(
            api: CoreV1Api,
            namespace: String,
            fieldSelector: String,
        ): List<CoreV1Event> =
            runCatching { api.listNamespacedEvent(namespace).fieldSelector(fieldSelector).execute() }
                .getOrNull()
                ?.items
                ?.sortedBy { it.lastTimestamp ?: it.eventTime ?: it.firstTimestamp }
                .orEmpty()

        /** Best-effort wrapper that never throws; turns ApiException into a stub snapshot. */
        fun tryCollect(
            api: CoreV1Api,
            namespace: String,
            podName: String,
            fallbackReason: String,
        ): Snapshot =
            try {
                PodDiagnostics(api, namespace).collect(podName, fallbackReason)
            } catch (_: ApiException) {
                Snapshot(fallbackReason, "Unable to read pod status", emptyList())
            }
    }
}
