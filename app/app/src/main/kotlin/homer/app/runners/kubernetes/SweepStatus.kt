package homer.app.runners.kubernetes

import java.util.concurrent.atomic.AtomicReference

/**
 * Result of the startup orphan-pod sweep. One `Snapshot` is produced
 * per boot and made visible via this singleton for health endpoints
 * and future admin UI surfaces.
 */
class SweepStatus {
    data class DeletedPod(
        val namespace: String,
        val name: String,
        val processId: Int?,
        val runnerAlias: String?,
    )

    data class Snapshot(
        /** Unix epoch seconds; a millisecond-producing clock is divided at the boundary. */
        val ranAt: Long,
        val scanned: Int,
        val deleted: List<DeletedPod>,
        val errors: List<String>,
    )

    private val last = AtomicReference<Snapshot?>(null)

    fun set(snapshot: Snapshot) {
        last.set(snapshot)
    }

    fun get(): Snapshot? = last.get()
}
