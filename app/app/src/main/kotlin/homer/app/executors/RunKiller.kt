package homer.app.executors

/**
 * What the `KILL_OLD_RUN_CURRENT` lock strategy does to the run holding the lock: kills it, so the new
 * run takes the lock. Implemented by `ProcessManager`. See ADR-0083.
 */
interface RunKiller {
    suspend fun killProcess(
        processId: Int,
        reason: String = "",
    )
}
