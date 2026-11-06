package homer.app.runners.kubernetes

import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.PatchUtils
import org.slf4j.LoggerFactory

/** Job teardown in Job mode (ADR-0100). 404-tolerant; never throws ApiException. */
object JobCleanup {
    /** Deletes [jobName] with `Background` propagation, so the garbage collector removes its pod. */
    fun delete(
        batchApi: BatchV1Api,
        namespace: String,
        jobName: String,
    ) {
        try {
            deleteOrThrow(batchApi, namespace, jobName)
        } catch (e: ApiException) {
            if (e.code != HTTP_NOT_FOUND) {
                log.warn("Failed to delete Job {}/{} (code={}): {}", namespace, jobName, e.code, e.responseBody)
            }
        }
    }

    /** [delete] that leaves every [ApiException], 404 included, to the caller: the startup sweep reports them. */
    fun deleteOrThrow(
        batchApi: BatchV1Api,
        namespace: String,
        jobName: String,
    ) {
        // Untyped: the server can answer the Job itself, which the client's V1Status model rejects.
        val call = batchApi.deleteNamespacedJob(jobName, namespace).propagationPolicy("Background").buildCall(null)
        batchApi.apiClient.execute<Any>(call)
    }

    /** Labels [jobName] kept, so the startup sweep skips it; its kept pod's deadline ends it. */
    fun keep(
        batchApi: BatchV1Api,
        namespace: String,
        jobName: String,
    ) {
        val patch = """[{"op":"add","path":"/metadata/labels/${PodLabels.KEPT.replace("/", "~1")}","value":"true"}]"""
        try {
            // PatchUtils sets the json-patch content type (see PodCleanup); Any, not V1Job, so a Job
            // carrying fields this client does not know still parses.
            PatchUtils.patch(
                Any::class.java,
                { batchApi.patchNamespacedJob(jobName, namespace, V1Patch(patch)).buildCall(null) },
                V1Patch.PATCH_FORMAT_JSON_PATCH,
                batchApi.apiClient,
            )
        } catch (e: ApiException) {
            if (e.code != HTTP_NOT_FOUND) {
                log.warn("Failed to label Job {}/{} kept (code={}): {}", namespace, jobName, e.code, e.responseBody)
            }
        }
    }

    private const val HTTP_NOT_FOUND = 404
    private val log = LoggerFactory.getLogger(JobCleanup::class.java)
}

/**
 * Deletes or keeps a session's pod and, in Job mode ([jobName] set), its Job: the one teardown for session close and
 * startup failure. Without a Job it makes exactly the pod calls Pod mode always made, and no batch call (ADR-0100).
 */
internal class WorkloadTeardown(
    private val api: CoreV1Api,
    private val namespace: String,
    private val jobName: String?,
    private val batchApi: BatchV1Api?,
) {
    /** The Job goes first, so its controller never sees its pod lost while the Job lives. */
    fun delete(podName: String?) {
        jobName?.let { JobCleanup.delete(checkNotNull(batchApi), namespace, it) }
        podName?.let { PodCleanup.delete(api, namespace, it) }
    }

    /** Keeps the pod, and labels its Job kept; true when kept. A Job whose pod was not kept is deleted. */
    fun keep(
        podName: String,
        ttlSeconds: Long,
        existingDeadlineSeconds: Long?,
    ): Boolean {
        val kept = PodCleanup.keepWithTtl(api, namespace, podName, ttlSeconds, existingDeadlineSeconds)
        val job = jobName ?: return kept
        val batch = checkNotNull(batchApi)
        if (kept) JobCleanup.keep(batch, namespace, job) else JobCleanup.delete(batch, namespace, job)
        return kept
    }
}
