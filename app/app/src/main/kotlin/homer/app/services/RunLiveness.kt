package homer.app.services

import homer.app.infra.ProcessJobRegistry
import homer.app.models.ProcessStatus

/** How often a stream re-reads a live run's status anyway, in case its job outlives the terminal commit. */
const val SAFETY_STATUS_READ_MS = 5_000L

/**
 * One subscriber's liveness check for a stream's poll loop. While the run's job is live in
 * [ProcessJobRegistry], a tick reads nothing from the database except a safety read every
 * [SAFETY_STATUS_READ_MS] (every tick when the poll interval is longer than that): the executor
 * commits the terminal status and removes the job in the same `finally`. With no live job (not yet
 * assigned, removed, or after a restart) every tick reads.
 */
internal class RunLiveness(
    private val processManager: ProcessManager,
    private val processId: Int,
    pollIntervalMs: Long,
    private val terminal: Set<ProcessStatus>,
) {
    // Counted in ticks, each of which sleeps pollIntervalMs, so virtual time drives it in tests.
    private val safetyEveryTicks = (SAFETY_STATUS_READ_MS / pollIntervalMs.coerceAtLeast(1)).coerceAtLeast(1)
    private var liveTicks = 0L
    private var terminalSeen = false

    /** Whether this tick reads the database. Call it once per tick. */
    fun readDue(): Boolean {
        if (processManager.hasLiveJob(processId) && ++liveTicks < safetyEveryTicks) return false
        liveTicks = 0
        return true
    }

    /** Whether the run is terminal, reading its status only when [readDb]. A terminal status is final, so it is kept. */
    suspend fun isTerminal(readDb: Boolean = readDue()): Boolean {
        if (!terminalSeen && readDb) terminalSeen = processManager.getProcessStatus(processId) in terminal
        return terminalSeen
    }
}
