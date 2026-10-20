package homer.app.infra

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of live coroutine [Job]s keyed by process id. A `Job` is a
 * runtime handle to *this* instance's running coroutine — it cannot be
 * persisted, so it lives here rather than in SQLite. Used by
 * `ProcessManager.killProcess` to cancel a running process.
 *
 * Starts empty on every boot: the coroutines it referred to are gone after a
 * restart, which is exactly why interrupted processes are reconciled to
 * `UNKNOWN` (see `StartupReconciliation`).
 */
class ProcessJobRegistry {
    private val jobs = ConcurrentHashMap<Int, Job>()

    fun put(
        processId: Int,
        job: Job,
    ) {
        jobs[processId] = job
    }

    fun get(processId: Int): Job? = jobs[processId]

    fun remove(processId: Int) {
        jobs.remove(processId)
    }
}
