package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api

/**
 * The checks one Job-mode startup runs each time its Job has no pod yet (ADR-0100): a `Failed` condition
 * or a `FailedCreate` event other than a quota refusal ends the startup; a quota refusal, which the Job
 * controller retries, is waited out and remembered for the timeout message. Reads are best-effort: one that
 * fails finds nothing, and `podStartTimeoutSeconds` still bounds the wait.
 */
internal class JobStartupCheck(
    private val api: CoreV1Api,
    private val batchApi: BatchV1Api,
    private val namespace: String,
    private val jobName: String,
) {
    private var lastQuotaMessage: String? = null

    /** The startup failure this poll found, as reason to message, or null to keep waiting. */
    fun failure(): Pair<String, String>? = failedCondition()?.let { "JobFailed" to it } ?: failedCreate()?.let { "JobFailedCreate" to it }

    /** The `PodStartTimeout` message, ending with the last quota refusal when one was seen. */
    fun timeoutMessage(
        budgetSec: Long,
        elapsedSec: Long,
    ): String =
        "Job $jobName had no ready pod within ${budgetSec}s (elapsed=${elapsedSec}s)" +
            lastQuotaMessage?.let { "; last FailedCreate: $it" }.orEmpty()

    private fun failedCondition(): String? =
        readJobOrNull(batchApi, namespace, jobName)
            ?.status
            ?.conditions
            ?.firstOrNull { it.isFailed() }
            ?.let { it.message ?: it.reason ?: "Job failed" }

    /** The newest `FailedCreate` decides: a quota refusal is remembered and waited out, anything else fails. */
    private fun failedCreate(): String? {
        val newest =
            PodDiagnostics
                .sortedEvents(api, namespace, jobEventSelector(jobName))
                .lastOrNull { it.reason == FAILED_CREATE }
                ?: return null
        val message = newest.message.orEmpty()
        if (QUOTA_MARKER !in message) return message.ifBlank { "$FAILED_CREATE without a message" }
        lastQuotaMessage = message
        return null
    }

    private companion object {
        const val FAILED_CREATE = "FailedCreate"
        const val QUOTA_MARKER = "exceeded quota"
    }
}
