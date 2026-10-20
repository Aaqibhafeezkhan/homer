package homer.app.runners.kubernetes

import homer.app.runners.ConfiguredRunners
import org.slf4j.LoggerFactory

/**
 * On Homer boot, scan every configured Kubernetes runner for pods
 * labeled `app.kubernetes.io/managed-by=homer` and delete any whose
 * `homer.app/process-id` does not correspond to an active process
 * ([ActiveRunIds], in production `ProcessManager`).
 *
 * Rationale (plan §7): after an unclean restart, Homer's in-memory
 * process map is empty but orphan pods may still exist on the
 * cluster. Without a sweep they would linger until the k8s TTL
 * controller reaps them (or forever).
 *
 * Single-replica assumption applies — see plan "Scope" note.
 */
class KubernetesRunnerStartupSweep(
    private val runners: ConfiguredRunners,
    private val activeRuns: ActiveRunIds,
    private val status: SweepStatus,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sweep() {
        val active = activeRuns.activeProcessIds()
        var scanned = 0
        val deleted = mutableListOf<SweepStatus.DeletedPod>()
        val errors = mutableListOf<String>()

        for (alias in runners.aliases()) {
            val runner = runners.get(alias) as? KubernetesRunner ?: continue
            try {
                val result = runner.sweepOrphans(active)
                scanned += result.scanned
                deleted += result.deleted
                errors += result.errors
                log.info(
                    "sweep: namespace={} scanned={} deleted={} errors={}",
                    runner.namespace,
                    result.scanned,
                    result.deleted.size,
                    result.errors.size,
                )
            } catch (e: Exception) {
                val msg = "sweep failed for runner '$alias': ${e.message}"
                log.warn(msg, e)
                errors += msg
            }
        }

        status.set(
            SweepStatus.Snapshot(
                ranAt = clock() / 1000, // epoch seconds, matching every other API timestamp
                scanned = scanned,
                deleted = deleted.toList(),
                errors = errors.toList(),
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(KubernetesRunnerStartupSweep::class.java)
    }
}
