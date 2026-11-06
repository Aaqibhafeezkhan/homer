package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList

/**
 * The pod half of [KubernetesRunner.sweepOrphans]: lists this instance's pods in [namespace] and deletes the orphans.
 * With [skipJobOwned] (Job mode) it leaves a Job's pod to the Job sweep.
 */
internal class OrphanPodSweep(
    private val api: CoreV1Api,
    private val namespace: String,
    private val instanceId: String,
    private val skipJobOwned: Boolean = false,
) {
    fun sweep(activeProcessIds: Set<Int>): KubernetesRunner.SweepResult {
        val pods: V1PodList =
            try {
                api
                    .listNamespacedPod(namespace)
                    .labelSelector(OrphanLabels.selector(instanceId))
                    .execute()
            } catch (e: ApiException) {
                return KubernetesRunner.SweepResult(
                    scanned = 0,
                    deleted = emptyList(),
                    errors = listOf("list pods failed: ${e.code} ${e.responseBody ?: e.message}"),
                )
            }

        val items = pods.items.orEmpty()
        val deleted = mutableListOf<SweepStatus.DeletedPod>()
        val errors = mutableListOf<String>()
        for (pod in items) {
            if (skipJobOwned && pod.ownedByJob()) continue
            val orphan = OrphanLabels.orphanOf(pod.metadata, activeProcessIds, "pod") ?: continue
            deleteOrphan(orphan, deleted, errors)
        }
        return KubernetesRunner.SweepResult(scanned = items.size, deleted = deleted, errors = errors)
    }

    private fun V1Pod.ownedByJob(): Boolean = metadata?.ownerReferences.orEmpty().any { it.kind == "Job" }

    /** Deletes [orphan], recording it in [deleted]; a pod already gone is no error. */
    private fun deleteOrphan(
        orphan: Orphan,
        deleted: MutableList<SweepStatus.DeletedPod>,
        errors: MutableList<String>,
    ) {
        val name = orphan.name
        try {
            api.deleteNamespacedPod(name, namespace).gracePeriodSeconds(0).execute()
            deleted.add(
                SweepStatus.DeletedPod(
                    namespace = namespace,
                    name = name,
                    processId = orphan.processId,
                    runnerAlias = orphan.runnerAlias,
                ),
            )
        } catch (e: ApiException) {
            if (e.code == 404) return
            errors.add("delete $name failed: ${e.code} ${e.responseBody ?: e.message}")
        }
    }
}
