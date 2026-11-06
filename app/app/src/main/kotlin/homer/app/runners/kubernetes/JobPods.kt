package homer.app.runners.kubernetes

import homer.app.exceptions.PodStartupException
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1JobCondition

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

/** The field selector of the events about the Job [jobName]. */
internal fun jobEventSelector(jobName: String): String = "involvedObject.kind=Job,involvedObject.name=$jobName"

/** The Job [jobName], or null when it cannot be read. */
internal fun readJobOrNull(
    batchApi: BatchV1Api,
    namespace: String,
    jobName: String,
): V1Job? = runCatching { batchApi.readNamespacedJob(jobName, namespace).execute() }.getOrNull()

/** True for the condition the Job controller sets when the Job has failed. */
internal fun V1JobCondition.isFailed(): Boolean = type == "Failed" && status == "True"
