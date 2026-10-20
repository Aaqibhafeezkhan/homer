package homer.app.runners.kubernetes

/**
 * The ids of the runs still active (`CREATED`/`WORKING`), whose pods the startup sweep keeps.
 * Implemented by `ProcessManager`. See ADR-0083.
 */
fun interface ActiveRunIds {
    suspend fun activeProcessIds(): Set<Int>
}
