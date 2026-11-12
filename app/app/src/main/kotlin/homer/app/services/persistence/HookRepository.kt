package homer.app.services.persistence

import homer.app.models.AgentHook
import homer.app.models.ProcessStatus
import homer.app.services.HookStatus
import homer.app.services.PendingHook
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** One hook row to record for a status transition, before it has an id. */
data class NewPendingHook(
    val processId: Int,
    val declaringAgent: String,
    val triggeringStatus: ProcessStatus,
    val targetAgentName: String,
    val owner: String?,
    val createdAt: Long,
)

/**
 * Hook rows one status write shed because the PENDING cap was reached: [count] entries of
 * [agentName]'s run were not queued for [status]. The caller logs it after the write commits —
 * a log line inside the transaction would be file IO under the write lock (SQLite rule 3).
 */
data class ShedHooks(
    val count: Int,
    val agentName: String,
    val status: ProcessStatus,
)

/**
 * What one status write recorded: the hook [rows] to fire once it commits, and the entries
 * the PENDING cap [shed] instead of queueing. Both empty for a write that matched no entry.
 * The rows come out of the insert that wrote them, so the delivery path never re-reads what
 * it just wrote (SQLite rule 4).
 */
data class RecordedHooks(
    val rows: List<PendingHook> = emptyList(),
    val shed: List<ShedHooks> = emptyList(),
)

/** Outcome of the boot reconciliation's UNKNOWN write: the reconciled run ids and the hook rows they recorded. */
data class InterruptedResult(
    val processIds: List<Int>,
    val hooks: RecordedHooks = RecordedHooks(),
)

/**
 * The agent-level hooks a status transition must record, and the cap on pending rows.
 * [hooksFor] resolves the current agent catalog snapshot at call time, so a reload between a
 * run's start and its transition is honoured; it reads no database, so it is safe to call
 * inside the transition's transaction. See ADR-0107.
 */
class HookRecording(
    val hooksFor: (agentName: String, status: ProcessStatus) -> List<AgentHook>,
    val maxPending: Int,
)

/**
 * Records [this] recording's hook rows for run [processId] **inside the caller's transaction**
 * and returns their ids plus the entries the cap shed; an absent recording records nothing. A
 * nullable receiver so each of the four status writes reads as one line. See ADR-0107.
 */
internal fun HookRecording?.record(
    processId: Int,
    agentName: String,
    status: ProcessStatus,
    owner: String?,
    now: Long,
): RecordedHooks {
    val recording = this ?: return RecordedHooks()
    val hooks = recording.hooksFor(agentName, status)
    if (hooks.isEmpty()) return RecordedHooks()
    return insertPendingHooksTx(
        hooks.map { NewPendingHook(processId, agentName, status, it.agent, owner, now) },
        recording.maxPending,
    )
}

/**
 * INSERTs one row per entry of [hooks] **inside the caller's transaction**, stopping at the
 * global PENDING cap [maxPending] and returning the ids it wrote plus the entries it shed. A
 * hook over the cap is dropped, never queued: the status transition it rides must not fail, and
 * the cap is what keeps a burst of runs from queueing unbounded rows behind one busy target.
 * The caller logs each shed entry after the commit, from [RecordedHooks.shed]. §12 rule 4.
 *
 * A free function, not a [HookRepository] member, because `ProcessRepository` records hook
 * rows inside its own status-transition transactions and must not gain a second database
 * handle to do it.
 */
internal fun insertPendingHooksTx(
    hooks: List<NewPendingHook>,
    maxPending: Int,
): RecordedHooks {
    if (hooks.isEmpty()) return RecordedHooks()
    // Counted once, not per row: the cap check is a COUNT, and a fan-out of N hooks would
    // otherwise run it N times under the write lock.
    var pending =
        PendingHooks
            .selectAll()
            .where { PendingHooks.status eq HookStatus.PENDING.name }
            .count()
            .toInt()
    val room = (maxPending - pending).coerceAtLeast(0)
    val accepted = hooks.take(room)
    // The rows come back as they were written — the fire after the commit reads these, not a
    // second SELECT per row (SQLite rule 4). batchInsert returns one generated id per row, in
    // submission order.
    val rows =
        if (accepted.isEmpty()) {
            emptyList()
        } else {
            PendingHooks
                .batchInsert(accepted) { hook ->
                    this[PendingHooks.processId] = hook.processId
                    this[PendingHooks.declaringAgent] = hook.declaringAgent
                    this[PendingHooks.status] = HookStatus.PENDING.name
                    this[PendingHooks.agentName] = hook.targetAgentName
                    this[PendingHooks.owner] = hook.owner
                    this[PendingHooks.createdAt] = hook.createdAt
                    this[PendingHooks.triggeringStatus] = hook.triggeringStatus.name
                }.mapIndexed { i, generated ->
                    val hook = accepted[i]
                    PendingHook(
                        id = generated[PendingHooks.id],
                        processId = hook.processId,
                        declaringAgent = hook.declaringAgent,
                        triggeringStatus = hook.triggeringStatus,
                        targetAgentName = hook.targetAgentName,
                        owner = hook.owner,
                        status = HookStatus.PENDING,
                        fireAttempts = 0,
                        nextAttemptAt = null,
                        firedProcessId = null,
                        firedAt = null,
                        error = null,
                        createdAt = hook.createdAt,
                    )
                }
        }
    // One shed entry per declaring agent and status, so the log line can name both. A caller
    // passes one transition's entries, but grouping keeps the report right whatever it passes.
    val shed =
        hooks
            .drop(accepted.size)
            .groupBy { it.declaringAgent to it.triggeringStatus }
            .map { (declaring, group) -> ShedHooks(group.size, declaring.first, declaring.second) }
    return RecordedHooks(rows = rows, shed = shed)
}

/**
 * Durable access to pending agent-level status hooks. A row is written in the same
 * transaction as the status write that caused it, claimed (PENDING→DISPATCHING) before the
 * hook run starts, and settled once. The claim CAS, the global PENDING cap, the boot
 * recovery of a row left mid-dispatch and the settled-row prune are [FiredRecords]', shared
 * with dispatches and continuations. See ADR-0107.
 */
class HookRepository(
    private val db: DatabaseService,
    nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val records = FiredRecords(db, PendingHooks, HookStatus.entries.map { it.name }, nowSec)

    /** Claims a PENDING hook for firing (PENDING→DISPATCHING). False if another claim got there first. */
    suspend fun claim(id: Int): Boolean = records.claim(id)

    suspend fun get(id: Int): PendingHook? =
        db.read {
            PendingHooks
                .selectAll()
                .where { PendingHooks.id eq id }
                .singleOrNull()
                ?.toPendingHook()
        }

    /** Every hook in [status], oldest first. The boot replay reads PENDING. */
    suspend fun list(status: HookStatus): List<PendingHook> =
        db.read {
            PendingHooks
                .selectAll()
                .where { PendingHooks.status eq status.name }
                .orderBy(PendingHooks.id to SortOrder.ASC)
                .map { it.toPendingHook() }
        }

    /** PENDING hooks whose transient-failure retry is due at [now]. Rows never released have a null `next_attempt_at`. */
    suspend fun findDueRetries(now: Long): List<PendingHook> =
        db.read {
            PendingHooks
                .selectAll()
                .where {
                    (PendingHooks.status eq HookStatus.PENDING.name) and
                        (PendingHooks.nextAttemptAt.isNotNull()) and
                        (PendingHooks.nextAttemptAt lessEq now)
                }.orderBy(PendingHooks.id to SortOrder.ASC)
                .map { it.toPendingHook() }
        }

    suspend fun markDispatched(
        id: Int,
        processId: Int,
        at: Long,
    ): Boolean =
        transition(id, HookStatus.DISPATCHING) {
            it[PendingHooks.status] = HookStatus.DISPATCHED.name
            it[PendingHooks.firedProcessId] = processId
            it[PendingHooks.firedAt] = at
        }

    /** Returns a DISPATCHING hook to PENDING for a later retry, counting the attempt. See ADR-0023. */
    suspend fun releaseForRetry(
        id: Int,
        error: String,
        attempts: Int,
        nextAttemptAt: Long,
    ): Boolean =
        transition(id, HookStatus.DISPATCHING) {
            it[PendingHooks.status] = HookStatus.PENDING.name
            it[PendingHooks.error] = error
            it[PendingHooks.fireAttempts] = attempts
            it[PendingHooks.nextAttemptAt] = nextAttemptAt
        }

    /** Settles a claimed hook that can never fire (a target gone, an owner without the grant). */
    suspend fun markFailed(
        id: Int,
        error: String,
        at: Long,
    ): Boolean =
        transition(id, HookStatus.DISPATCHING) {
            it[PendingHooks.status] = HookStatus.FAILED.name
            it[PendingHooks.error] = error
            it[PendingHooks.firedAt] = at
        }

    /** Settles a claimed hook shed before the attempt — a chain past `maxChainDepth`. */
    suspend fun markDropped(
        id: Int,
        error: String,
        at: Long,
    ): Boolean =
        transition(id, HookStatus.DISPATCHING) {
            it[PendingHooks.status] = HookStatus.DROPPED.name
            it[PendingHooks.error] = error
            it[PendingHooks.firedAt] = at
        }

    /**
     * Marks every DISPATCHING hook (interrupted mid-fire by a restart) FAILED, so the crash
     * window between the run start and [markDispatched] cannot cause a duplicate fire. Boot only.
     */
    suspend fun failOrphanedDispatching(at: Long): List<PendingHook> =
        db.query {
            val orphaned =
                PendingHooks
                    .selectAll()
                    .where { PendingHooks.status eq HookStatus.DISPATCHING.name }
                    .map { it.toPendingHook() }
            PendingHooks.update({ PendingHooks.status eq HookStatus.DISPATCHING.name }) {
                it[PendingHooks.status] = HookStatus.FAILED.name
                it[PendingHooks.error] = ORPHANED_ERROR
                it[PendingHooks.firedAt] = at
            }
            orphaned.map { it.copy(status = HookStatus.FAILED, error = ORPHANED_ERROR, firedAt = at) }
        }

    /** Deletes settled hooks created before [cutoff], in batches. Returns the number deleted. See ADR-0107. */
    suspend fun pruneSettled(
        cutoff: Long,
        batchSize: Int = PRUNE_BATCH_SIZE,
    ): Int = records.pruneSettled(cutoff, batchSize, emptyList()) {}

    private suspend fun transition(
        id: Int,
        from: HookStatus,
        body: (UpdateStatement) -> Unit,
    ): Boolean =
        db.query {
            PendingHooks.update(
                { (PendingHooks.id eq id) and (PendingHooks.status eq from.name) },
            ) { body(it) } > 0
        }

    private fun ResultRow.toPendingHook(): PendingHook =
        PendingHook(
            id = this[PendingHooks.id],
            processId = this[PendingHooks.processId],
            declaringAgent = this[PendingHooks.declaringAgent],
            triggeringStatus = ProcessStatus.valueOf(this[PendingHooks.triggeringStatus]),
            targetAgentName = this[PendingHooks.agentName],
            owner = this[PendingHooks.owner],
            status = HookStatus.valueOf(this[PendingHooks.status]),
            fireAttempts = this[PendingHooks.fireAttempts],
            nextAttemptAt = this[PendingHooks.nextAttemptAt],
            firedProcessId = this[PendingHooks.firedProcessId],
            firedAt = this[PendingHooks.firedAt],
            error = this[PendingHooks.error],
            createdAt = this[PendingHooks.createdAt],
        )

    private companion object {
        /** Rows per prune transaction. */
        const val PRUNE_BATCH_SIZE = 500
        const val ORPHANED_ERROR = "Interrupted during dispatch by a server restart"
    }
}
