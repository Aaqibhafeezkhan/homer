package homer.app.runners.kubernetes

import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1DeleteOptions
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.util.PatchUtils
import org.slf4j.LoggerFactory

/**
 * Shared pod teardown for session close and startup failures: delete
 * outright, or — when keepPodOnFailure applies — label the pod kept and
 * bound its lifetime with a TTL so the startup sweep skips it. Both
 * operations are 404-tolerant and never throw ApiException. If the
 * keep-with-TTL patch itself fails (non-404), keepWithTtl falls back to
 * deleting the pod rather than leaving it unbounded and unlabeled.
 */
object PodCleanup {
    fun delete(
        api: CoreV1Api,
        namespace: String,
        podName: String,
    ) {
        try {
            api
                .deleteNamespacedPod(podName, namespace)
                .gracePeriodSeconds(0)
                .body(V1DeleteOptions().gracePeriodSeconds(0))
                .execute()
        } catch (e: ApiException) {
            if (e.code != HTTP_NOT_FOUND) {
                log.warn("Failed to delete pod {}/{} (code={}): {}", namespace, podName, e.code, e.responseBody)
            }
        }
    }

    fun keepWithTtl(
        api: CoreV1Api,
        namespace: String,
        podName: String,
        ttlSeconds: Long,
    ) {
        // Both ops use "add", not "replace": nothing ever sets activeDeadlineSeconds on our
        // pods (PodSpecBuilder doesn't, Kubernetes doesn't default it), and RFC 6902 "replace"
        // requires the target to already exist. Since a JSON Patch is applied atomically, a
        // "replace" 422 here would also void the kept-label op in the same request. JSON
        // Patch "add" on an existing object member overwrites it, so "add" is correct whether
        // or not the field is already present.
        // The label path escapes "/" as "~1" per RFC 6901 (JSON Pointer).
        val patch =
            """[
            |{"op":"add","path":"/spec/activeDeadlineSeconds","value":$ttlSeconds},
            |{"op":"add","path":"/metadata/labels/${PodLabels.KEPT.replace("/", "~1")}","value":"true"}
            |]
            """.trimMargin()
        try {
            // Must go through PatchUtils: the generated CoreV1Api declares no patch media
            // types, so execute() sends the body as application/json and the apiserver
            // rejects the PATCH with 415. PatchUtils re-wraps it as json-patch+json.
            PatchUtils.patch(
                V1Pod::class.java,
                { api.patchNamespacedPod(podName, namespace, V1Patch(patch)).buildCall(null) },
                V1Patch.PATCH_FORMAT_JSON_PATCH,
                api.apiClient,
            )
        } catch (e: ApiException) {
            if (e.code != HTTP_NOT_FOUND) {
                // The pod can't be bounded (no TTL, no kept label) — leaving it running
                // would go unreaped until the next startup sweep, so delete beats leaking.
                log.warn("Failed to set TTL on pod {}/{}, falling back to delete: {}", namespace, podName, e.responseBody)
                delete(api, namespace, podName)
            }
        }
    }

    private const val HTTP_NOT_FOUND = 404
    private val log = LoggerFactory.getLogger(PodCleanup::class.java)
}
