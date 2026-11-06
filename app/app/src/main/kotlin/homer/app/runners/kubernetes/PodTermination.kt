package homer.app.runners.kubernetes

import homer.app.models.PodTermination
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ContainerStateTerminated
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodStatus
import java.net.HttpURLConnection

internal const val DEADLINE_EXCEEDED = "DeadlineExceeded"

/**
 * Why Kubernetes stopped the pod, read once after a command ended badly; null when the pod looks
 * healthy or the read fails for any reason but a 404, so a read error never masks the command result.
 */
internal fun readPodTermination(
    api: CoreV1Api,
    namespace: String,
    podName: String,
): PodTermination? =
    try {
        api.readNamespacedPodStatus(podName, namespace).execute().terminationOrNull()
    } catch (e: ApiException) {
        if (e.code == HttpURLConnection.HTTP_NOT_FOUND) PodTermination("PodDeleted", "The pod no longer exists") else null
    } catch (_: Exception) {
        null
    }

private fun V1Pod.terminationOrNull(): PodTermination? {
    val status = status ?: return null
    val phase = status.phase ?: return null
    val terminated = status.mainTerminated()
    val deleting = metadata?.deletionTimestamp != null
    if (phase == "Running" && !deleting && terminated == null) return null
    return PodTermination(status.reasonOf(phase, terminated, deleting), status.messageOf(phase, terminated))
}

private fun V1PodStatus.reasonOf(
    phase: String,
    terminated: V1ContainerStateTerminated?,
    deleting: Boolean,
): String = reason ?: terminated?.reason ?: (if (deleting) "PodTerminating" else phase)

private fun V1PodStatus.messageOf(
    phase: String,
    terminated: V1ContainerStateTerminated?,
): String = message ?: terminated?.message ?: "Pod phase $phase"

private fun V1PodStatus.mainTerminated(): V1ContainerStateTerminated? =
    containerStatuses
        .orEmpty()
        .firstOrNull { it.name == PodSpecBuilder.MAIN_CONTAINER_NAME }
        ?.state
        ?.terminated

/** One line for a run's failure message: the limit that was hit, or Kubernetes' own message. */
internal fun PodTermination.summary(maxRunSeconds: Long?): String =
    if (reason == DEADLINE_EXCEEDED && maxRunSeconds != null) {
        "maxRunSeconds ($maxRunSeconds s) exceeded ($reason)"
    } else {
        "$message ($reason)"
    }
