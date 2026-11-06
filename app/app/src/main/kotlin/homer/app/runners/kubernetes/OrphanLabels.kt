package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.models.V1ObjectMeta
import org.slf4j.LoggerFactory

/** A Homer-labelled pod or Job the startup sweep deletes. */
internal data class Orphan(
    val name: String,
    val processId: Int?,
    val runnerAlias: String?,
)

/** The label rules both halves of [KubernetesRunner.sweepOrphans] share. */
internal object OrphanLabels {
    /** This instance's Homer objects: `managed-by`, plus `instance` when [instanceId] is set. */
    fun selector(instanceId: String): String =
        buildString {
            append("${PodLabels.MANAGED_BY}=${PodLabels.MANAGED_BY_VALUE}")
            if (instanceId.isNotBlank()) append(",${PodLabels.INSTANCE}=$instanceId")
        }

    /** The object [meta] describes as an orphan: null when it is unnamed, belongs to an active process, or is kept. */
    fun orphanOf(
        meta: V1ObjectMeta?,
        activeProcessIds: Set<Int>,
        kind: String,
    ): Orphan? {
        val name = meta?.name ?: return null
        val labels = meta.labels.orEmpty()
        val pid = labels[PodLabels.PROCESS_ID]?.toIntOrNull()
        if (pid != null && pid in activeProcessIds) return null
        if (labels[PodLabels.KEPT] == "true") {
            log.info("Sweep skipping kept-for-debug {} {} (process {}) — bounded by its own TTL", kind, name, pid)
            return null
        }
        return Orphan(name, pid, labels[PodLabels.RUNNER])
    }

    // The runner's logger, so the sweep's log lines keep their category.
    private val log = LoggerFactory.getLogger(KubernetesRunner::class.java)
}
