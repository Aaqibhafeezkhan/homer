package homer.app.runners.kubernetes

import homer.app.models.PodTermination
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ContainerStateTerminated
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodStatus
import java.net.HttpURLConnection
import java.util.Objects

internal const val DEADLINE_EXCEEDED = "DeadlineExceeded"

/**
 * The reason Homer sets itself when the exec channel into a pod that is still running was lost — not a
 * Kubernetes stop, so [readPodTermination] never returns it; the executor sets it on the run's
 * `runner.termination` and on the step error.
 */
internal const val EXEC_CHANNEL_LOST = "ExecChannelLost"

/** The longest termination reason or message kept. */
internal const val TERMINATION_MESSAGE_MAX_CHARS = 512

private val LINE_BREAKING = Regex("[\\s\\p{Cc}\\p{Z}]+")

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
    return PodTermination(status.reasonOf(phase, terminated, deleting).toOneLine(), status.messageOf(phase, terminated))
}

private fun V1PodStatus.reasonOf(
    phase: String,
    terminated: V1ContainerStateTerminated?,
    deleting: Boolean,
): String = reason ?: terminated?.reason ?: (if (deleting) "PodTerminating" else phase)

/**
 * The kubelet's message for the stop, else the main container's exit code, else `Pod phase <phase>`.
 * Never the main container's `terminated.message`: that is its termination log, which the agent can
 * write, so a secret it read would reach the run's failure text unredacted (ADR-0100 § Security).
 */
private fun V1PodStatus.messageOf(
    phase: String,
    terminated: V1ContainerStateTerminated?,
): String =
    message?.toOneLine()?.ifEmpty { null }
        ?: terminated?.let { "Main container terminated with exit code ${Objects.toString(it.exitCode, "unknown")}" }
        ?: "Pod phase $phase"

/** [this] as one line: whitespace and control-character runs become one space, cut to [TERMINATION_MESSAGE_MAX_CHARS]. */
internal fun String.toOneLine(): String {
    val cut = replace(LINE_BREAKING, " ").trim().take(TERMINATION_MESSAGE_MAX_CHARS)
    return (if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut).trimEnd()
}

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
