package homer.app.services.persistence

import homer.app.services.RunMessage
import homer.app.services.RunMessageStatus
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Durable access to the per-run inbox. The insert checks the run is still active in the same
 * write transaction, so a send racing the run's end never leaves a QUEUED row on a terminal run.
 * See ADR-0097.
 */
class RunMessagesRepository(
    private val db: DatabaseService,
) {
    sealed interface InsertResult {
        data object Inserted : InsertResult

        data object RunNotActive : InsertResult

        data object CapReached : InsertResult
    }

    /** Inserts [message] when its run is CREATED or WORKING and holds fewer than [maxPerRun] messages of any status. */
    suspend fun tryInsert(
        message: RunMessage,
        maxPerRun: Int,
    ): InsertResult =
        db.query {
            val status =
                Processes
                    .select(Processes.status)
                    .where { Processes.id eq message.processId }
                    .singleOrNull()
                    ?.get(Processes.status)
            if (status == null || status !in ACTIVE_STATUS_NAMES) return@query InsertResult.RunNotActive
            val stored = RunMessages.selectAll().where { RunMessages.processId eq message.processId }.count()
            if (stored >= maxPerRun.toLong()) return@query InsertResult.CapReached
            RunMessages.insert {
                it[id] = message.id
                it[processId] = message.processId
                it[text] = message.text
                it[RunMessages.status] = message.status.name
                it[sender] = message.sender
                it[createdAt] = message.createdAt
                it[deliveredAt] = message.deliveredAt
            }
            InsertResult.Inserted
        }

    /** Marks the run's QUEUED messages DELIVERED at [deliveredAtSec] and returns them, oldest first. */
    suspend fun take(
        processId: Int,
        deliveredAtSec: Long,
    ): List<RunMessage> = settleQueued(processId, RunMessageStatus.DELIVERED, deliveredAtSec)

    /** Marks the run's QUEUED messages UNDELIVERED and returns them, oldest first. */
    suspend fun markUndelivered(processId: Int): List<RunMessage> = settleQueued(processId, RunMessageStatus.UNDELIVERED, null)

    /**
     * Marks UNDELIVERED every QUEUED message whose run is no longer CREATED or WORKING (or has no
     * row): the boot-time backstop for a run-end close that failed or never ran. Returns the count.
     */
    suspend fun markUndeliveredForEndedRuns(): Int =
        db.query {
            val activeRuns = Processes.select(Processes.id).where { Processes.status inList ACTIVE_STATUS_NAMES }
            RunMessages.update({ (RunMessages.status eq QUEUED) and (RunMessages.processId notInSubQuery activeRuns) }) {
                it[status] = RunMessageStatus.UNDELIVERED.name
            }
        }

    private suspend fun settleQueued(
        processId: Int,
        to: RunMessageStatus,
        at: Long?,
    ): List<RunMessage> {
        // Every PostToolUse hook takes; an empty inbox must not cost the write lock.
        if (!db.read { hasQueued(processId) }) return emptyList()
        return db.query {
            // Re-selected under the write lock: a concurrent take may have settled them meanwhile.
            val queued = queuedRows(processId)
            if (queued.isNotEmpty()) {
                RunMessages.update({ (RunMessages.processId eq processId) and (RunMessages.status eq QUEUED) }) {
                    it[status] = to.name
                    it[deliveredAt] = at
                }
            }
            queued.map { it.copy(status = to, deliveredAt = at) }
        }
    }

    suspend fun list(processId: Int): List<RunMessage> =
        db.read {
            RunMessages
                .selectAll()
                .where { RunMessages.processId eq processId }
                .orderBy(RunMessageRowId to SortOrder.ASC)
                .map { it.toRunMessage() }
        }

    private fun hasQueued(processId: Int): Boolean =
        RunMessages
            .select(RunMessages.id)
            .where { (RunMessages.processId eq processId) and (RunMessages.status eq QUEUED) }
            .limit(1)
            .any()

    private fun queuedRows(processId: Int): List<RunMessage> =
        RunMessages
            .selectAll()
            .where { (RunMessages.processId eq processId) and (RunMessages.status eq QUEUED) }
            .orderBy(RunMessageRowId to SortOrder.ASC)
            .map { it.toRunMessage() }

    private fun ResultRow.toRunMessage() =
        RunMessage(
            id = this[RunMessages.id],
            processId = this[RunMessages.processId],
            text = this[RunMessages.text],
            status = RunMessageStatus.valueOf(this[RunMessages.status]),
            sender = this[RunMessages.sender],
            createdAt = this[RunMessages.createdAt],
            deliveredAt = this[RunMessages.deliveredAt],
        )

    private object RunMessageRowId : Expression<Long>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("run_messages.rowid")
        }
    }

    private companion object {
        val QUEUED = RunMessageStatus.QUEUED.name
    }
}
