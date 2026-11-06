package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import org.slf4j.LoggerFactory

/** The pod half of [KubernetesRunner.sweepOrphans]: lists this instance's pods in [namespace] and deletes the orphans. */
internal class OrphanPodSweep(
    private val api: CoreV1Api,
    private val namespace: String,
    private val instanceId: String,
) {
    fun sweep(activeProcessIds: Set<Int>): KubernetesRunner.SweepResult {
        val selector =
            buildString {
                append("${PodLabels.MANAGED_BY}=${PodLabels.MANAGED_BY_VALUE}")
                if (instanceId.isNotBlank()) append(",${PodLabels.INSTANCE}=$instanceId")
            }
        val pods: V1PodList =
            try {
                api
                    .listNamespacedPod(namespace)
                    .labelSelector(selector)
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
            val orphan = orphanOf(pod, activeProcessIds) ?: continue
            deleteOrphan(orphan, deleted, errors)
        }
        return KubernetesRunner.SweepResult(scanned = items.size, deleted = deleted, errors = errors)
    }

    private data class OrphanPod(
        val name: String,
        val processId: Int?,
        val runnerAlias: String?,
    )

    /** [pod] as an orphan to delete: null when it is unnamed, belongs to an active process, or is kept for debugging. */
    private fun orphanOf(
        pod: V1Pod,
        activeProcessIds: Set<Int>,
    ): OrphanPod? {
        val meta = pod.metadata ?: return null
        val name = meta.name ?: return null
        val labels = meta.labels.orEmpty()
        val pidLabel = labels[PodLabels.PROCESS_ID]
        val pid = pidLabel?.toIntOrNull()
        if (pid != null && pid in activeProcessIds) return null
        if (labels[PodLabels.KEPT] == "true") {
            log.info("Sweep skipping kept-for-debug pod {} (process {}) — bounded by its own TTL", name, pid)
            return null
        }
        return OrphanPod(name, pid, labels[PodLabels.RUNNER])
    }

    /** Deletes [orphan], recording it in [deleted]; a pod already gone is no error. */
    private fun deleteOrphan(
        orphan: OrphanPod,
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

    private companion object {
        // The runner's logger, so the sweep's log lines keep their category.
        private val log = LoggerFactory.getLogger(KubernetesRunner::class.java)
    }
}
