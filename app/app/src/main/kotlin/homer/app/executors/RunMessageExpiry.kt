package homer.app.executors

/**
 * What [ProcessExecutor] and startup reconciliation tell the run inbox when a run ends: its queued
 * operator messages can never be delivered any more. Implemented by `RunMessagesService`. See ADR-0097.
 */
interface RunMessageExpiry {
    /** Marks [processId]'s QUEUED messages UNDELIVERED. */
    suspend fun closeForProcess(processId: Int)

    /** Marks UNDELIVERED the QUEUED messages of every run no longer CREATED or WORKING; the boot-time backstop. */
    suspend fun closeForEndedRuns()
}
