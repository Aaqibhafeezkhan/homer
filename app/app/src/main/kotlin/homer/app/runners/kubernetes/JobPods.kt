package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job

/** The label the Job controller puts on its pods (Kubernetes 1.27+). */
internal const val JOB_CONTROLLER_UID_LABEL = "batch.kubernetes.io/controller-uid"

/**
 * The selector of [job]'s pods, never its name: the controller's `spec.selector.matchLabels` as `k=v,k=v`,
 * else [JOB_CONTROLLER_UID_LABEL] of its uid; null when the created Job carries neither. See ADR-0100.
 */
internal fun jobPodSelector(job: V1Job): String? {
    val matchLabels =
        job.spec
            ?.selector
            ?.matchLabels
            .orEmpty()
    if (matchLabels.isNotEmpty()) return matchLabels.entries.joinToString(",") { "${it.key}=${it.value}" }
    return job.metadata?.uid?.let { "$JOB_CONTROLLER_UID_LABEL=$it" }
}

/** The first pod matching [selector] in [namespace], or null while the Job controller has created none. */
internal fun findJobPod(
    api: CoreV1Api,
    namespace: String,
    selector: String,
): String? =
    try {
        api
            .listNamespacedPod(namespace)
            .labelSelector(selector)
            .execute()
            .items
            .orEmpty()
            .firstNotNullOfOrNull { it.metadata?.name }
    } catch (e: ApiException) {
        throw PodStartupException("PodStatusUnreadable", e.responseBody ?: (e.message ?: "unreadable"), emptyList(), e)
    }
