package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ContainerStatus
import io.kubernetes.client.openapi.models.V1Pod

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

    private fun listEvents(podName: String): List<String> =
        runCatching {
            api
                .listNamespacedEvent(namespace)
                .fieldSelector("involvedObject.name=$podName")
                .execute()
        }.getOrNull()
            ?.items
            ?.sortedBy { it.lastTimestamp ?: it.eventTime ?: it.firstTimestamp }
            ?.map { "${it.type ?: "?"}/${it.reason ?: "?"}: ${it.message ?: ""}".trim() }
            ?: emptyList()

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

    /** The reason and message of the failed container [failed]: its terminated state's, else its waiting state's. */
    private fun failureOf(failed: V1ContainerStatus): Pair<String, String> {
        val t = failed.state?.terminated
        val w = failed.state?.waiting
        val reason = t?.reason ?: w?.reason ?: "ContainerFailed"
        val msg = t?.message ?: w?.message ?: "Container '${failed.name}' failed"
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
