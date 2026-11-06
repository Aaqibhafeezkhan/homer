package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
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
            // Untyped: the server can answer the Job itself, which the client's V1Status model rejects.
            val call = batchApi.deleteNamespacedJob(jobName, namespace).propagationPolicy("Background").buildCall(null)
            batchApi.apiClient.execute<Any>(call)
        } catch (e: ApiException) {
            if (e.code != HTTP_NOT_FOUND) {
                log.warn("Failed to delete Job {}/{} (code={}): {}", namespace, jobName, e.code, e.responseBody)
            }
        }
    }

    private const val HTTP_NOT_FOUND = 404
    private val log = LoggerFactory.getLogger(JobCleanup::class.java)
}
