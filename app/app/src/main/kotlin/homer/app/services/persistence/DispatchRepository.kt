package homer.app.services.persistence

import homer.app.services.Dispatch
import homer.app.services.DispatchStatus
import homer.app.services.QuestionsService.Question
import homer.app.services.QuestionsService.QuestionStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.union
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Durable access to ask-and-dispatch records. SQLite is authoritative (ADR-0020);
 * the question + dispatch rows are created in one transaction so the pending cap
 * and the question insert can never race apart. See the ask-and-dispatch ADR.
 */
class DispatchRepository(
    private val db: DatabaseService,
    nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val records = FiredRecords(db, Dispatches, DispatchStatus.entries.map { it.name }, nowSec)

    suspend fun create(
        question: Question,
        dispatch: Dispatch,
        maxPending: Int,
    ): Int? =
        db.query {
            if (records.pendingCapReachedTx(maxPending)) return@query null
            Questions.insert {
                it[id] = question.id
                it[processId] = question.processId
                it[agentName] = question.agentName
                it[text] = question.text
                it[optionsJson] = columnJson.encodeToString(stringListSerializer, question.options)
                it[status] = question.status.name
                it[createdAt] = question.createdAt
                it[dispatchBound] = 1
            }
            Dispatches.insert {
                it[questionId] = dispatch.questionId
                it[status] = dispatch.status.name
                it[agentName] = dispatch.agentName
                it[answerParam] = dispatch.answerParam
                it[params] = columnJson.encodeToString(stringMapSerializer, dispatch.params)
                it[originProcessId] = dispatch.originProcessId
                it[originAgentName] = dispatch.originAgentName
                it[expiresAt] = dispatch.expiresAt
                it[createdAt] = dispatch.createdAt
                it[owner] = dispatch.owner
                it[secretInputKeys] = dispatch.secretInputKeys?.let { encodeKeys(it) }
                it[secretParamNames] = dispatch.secretParamNames?.let { encodeKeys(it) }
            }[Dispatches.id]
        }

    suspend fun get(id: Int): Dispatch? =
        db.read {
            Dispatches
                .selectAll()
                .where { Dispatches.id eq id }
                .singleOrNull()
                ?.toDispatch()
        }

    suspend fun findByQuestionId(questionId: String): Dispatch? =
        db.read {
            Dispatches
                .selectAll()
                .where { Dispatches.questionId eq questionId }
                .singleOrNull()
                ?.toDispatch()
        }

    suspend fun findByQuestionIds(ids: Collection<String>): Map<String, Dispatch> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            db.read {
                Dispatches
                    .selectAll()
                    .where { Dispatches.questionId inList ids }
                    .associate { it[Dispatches.questionId] to it.toDispatch() }
            }
        }

    /** One page of dispatches, newest first, and the count of all rows matching the filters: [FiredRecords.page]. */
    suspend fun page(
        status: DispatchStatus?,
        agentName: String?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
    ): RecordPage<Dispatch> =
        records.page(status?.name, agentName, visibleOwner, visibleAgents, offset, limit) { rows -> rows.map { it.toDispatch() } }

    /**
     * Deletes settled dispatches (every status but PENDING and DISPATCHING) created before
     * [cutoff], with their dispatch-bound questions, in batches of at most [batchSize]
     * ([FiredRecords.pruneSettled]). Returns the number of dispatches deleted. See ADR-0021.
     */
    suspend fun pruneSettled(
        cutoff: Long,
        batchSize: Int = PRUNE_BATCH_SIZE,
    ): Int =
        records.pruneSettled(cutoff, batchSize, listOf(Dispatches.questionId)) { batch ->
            // A settled dispatch's question is normally gone already; this takes any left behind.
            val questionIds = batch.map { it[Dispatches.questionId] }
            Questions.deleteWhere { (Questions.id inList questionIds) and (Questions.dispatchBound eq 1) }
        }

    /**
     * Claims a PENDING dispatch for firing (PENDING→DISPATCHING). False if it was no longer
     * PENDING **or** its TTL has lapsed. The expiry predicate is load-bearing: the periodic
     * sweep flips expired rows to CANCELLED only every sweepIntervalSec, so without it a
     * lapsed-but-unswept dispatch would still fire when its question is answered.
     */
    suspend fun claim(id: Int): Boolean = records.claim(id)

    suspend fun markDispatched(
        id: Int,
        processId: Int,
        at: Long,
    ): Boolean =
        transition(id, DispatchStatus.DISPATCHING) {
            it[Dispatches.status] = DispatchStatus.DISPATCHED.name
            it[Dispatches.dispatchedProcessId] = processId
            it[Dispatches.dispatchedAt] = at
        }

    suspend fun markFailed(
        id: Int,
        error: String,
        at: Long,
    ): Boolean =
        transition(id, DispatchStatus.DISPATCHING) {
            it[Dispatches.status] = DispatchStatus.FAILED.name
            it[Dispatches.error] = error
            it[Dispatches.dispatchedAt] = at
        }

    /**
     * Atomically cancels a PENDING dispatch and deletes its (OPEN) question. Returns the
     * cancelled dispatch, or null if it was no longer PENDING.
     */
    suspend fun cancelWithQuestion(id: Int): Dispatch? =
        db.query {
            val row = Dispatches.selectAll().where { Dispatches.id eq id }.singleOrNull() ?: return@query null
            val dispatch = row.toDispatch()
            if (dispatch.status != DispatchStatus.PENDING) return@query null
            Dispatches.update({ (Dispatches.id eq id) and (Dispatches.status eq DispatchStatus.PENDING.name) }) {
                it[status] = DispatchStatus.CANCELLED.name
            }
            deleteBoundQuestion(dispatch.questionId)
            dispatch.copy(status = DispatchStatus.CANCELLED)
        }

    data class PendingAnswered(
        val dispatch: Dispatch,
        val answer: String,
    )

    suspend fun listPendingAnswered(): List<PendingAnswered> =
        db.read {
            Dispatches
                .join(Questions, JoinType.INNER, Dispatches.questionId, Questions.id)
                .selectAll()
                .where {
                    (Dispatches.status eq DispatchStatus.PENDING.name) and
                        (Questions.status eq QuestionStatus.ANSWERED.name)
                }.map { PendingAnswered(it.toDispatch(), it[Questions.answer] ?: "") }
        }

    /**
     * Cancels PENDING dispatches past [now] and deletes their OPEN questions, atomically.
     * Returns the cancelled dispatches (for event emission). The `db.read` pre-check (served
     * by `dispatches_status_expires`, same as the write below) lets an idle sweep tick skip
     * the write lock entirely; the write re-applies the full predicate, so a row claimed
     * between the pre-check and the write is still left alone.
     */
    suspend fun expirePending(now: Long): List<Dispatch> {
        if (!records.anyDue(records.lapsedAt(now))) return emptyList()
        return db.query {
            val lapsed =
                Dispatches
                    .selectAll()
                    .where { records.lapsedAt(now) }
                    .map { it.toDispatch() }
            // One UPDATE over the same predicate, not one per row (SQLite rule 2): inside this
            // write transaction it matches exactly the rows just read.
            Dispatches.update({ records.lapsedAt(now) }) { it[Dispatches.status] = DispatchStatus.CANCELLED.name }
            deleteBoundQuestions(lapsed.map { it.questionId })
            lapsed.map { it.copy(status = DispatchStatus.CANCELLED) }
        }
    }

    /**
     * Marks all DISPATCHING dispatches (interrupted mid-fire by a restart) FAILED, so the
     * crash window between run-start and markDispatched cannot cause a duplicate re-fire.
     * Returns them.
     */
    suspend fun failOrphanedDispatching(at: Long): List<Dispatch> =
        db.query {
            val orphaned =
                Dispatches.selectAll().where { Dispatches.status eq DispatchStatus.DISPATCHING.name }.map { it.toDispatch() }
            Dispatches.update({ Dispatches.status eq DispatchStatus.DISPATCHING.name }) {
                it[Dispatches.status] = DispatchStatus.FAILED.name
                it[Dispatches.error] = "Interrupted during dispatch by a server restart"
                it[Dispatches.dispatchedAt] = at
            }
            deleteBoundQuestions(orphaned.map { it.questionId })
            orphaned.map {
                it.copy(
                    status = DispatchStatus.FAILED,
                    error = "Interrupted during dispatch by a server restart",
                    dispatchedAt = at,
                )
            }
        }

    /**
     * markDispatched / markFailed both land the dispatch in a terminal state. On a
     * successful transition the dispatch-bound question has served its purpose (its
     * answer is now in the fired run, or the fire failed), so delete it in the same
     * transaction — the question lifecycle is owned by the dispatch, not the long-gone
     * asking process, and leaving these rows behind grew SQLite unbounded. See ADR-0021.
     */
    private suspend fun transition(
        id: Int,
        from: DispatchStatus,
        body: (UpdateStatement) -> Unit,
    ): Boolean =
        db.query {
            val questionId =
                Dispatches
                    .select(Dispatches.questionId)
                    .where { Dispatches.id eq id }
                    .singleOrNull()
                    ?.get(Dispatches.questionId)
            val updated =
                Dispatches.update(
                    { (Dispatches.id eq id) and (Dispatches.status eq from.name) },
                ) { body(it) } > 0
            if (updated && questionId != null) {
                deleteBoundQuestion(questionId)
            }
            updated
        }

    /**
     * Deletes a dispatch-bound question row. Must run inside a [DatabaseService.query]
     * transaction. Guarded on `dispatch_bound = 1` so it can never touch an ordinary
     * process-scoped question.
     */
    private fun deleteBoundQuestion(questionId: String) {
        Questions.deleteWhere { (Questions.id eq questionId) and (Questions.dispatchBound eq 1) }
    }

    private fun deleteBoundQuestions(questionIds: List<String>) {
        for (chunk in questionIds.chunked(SQLITE_PARAM_CHUNK)) {
            Questions.deleteWhere { (Questions.id inList chunk) and (Questions.dispatchBound eq 1) }
        }
    }

    private fun ResultRow.toDispatch(): Dispatch =
        Dispatch(
            id = this[Dispatches.id],
            questionId = this[Dispatches.questionId],
            status = DispatchStatus.valueOf(this[Dispatches.status]),
            agentName = this[Dispatches.agentName],
            answerParam = this[Dispatches.answerParam],
            params = columnJson.decodeFromString(stringMapSerializer, this[Dispatches.params]),
            originProcessId = this[Dispatches.originProcessId],
            originAgentName = this[Dispatches.originAgentName],
            expiresAt = this[Dispatches.expiresAt],
            createdAt = this[Dispatches.createdAt],
            dispatchedAt = this[Dispatches.dispatchedAt],
            dispatchedProcessId = this[Dispatches.dispatchedProcessId],
            error = this[Dispatches.error],
            owner = this[Dispatches.owner],
            secretInputKeys =
                this[Dispatches.secretInputKeys]?.let { decodeKeys(it) },
            secretParamNames =
                this[Dispatches.secretParamNames]?.let { decodeKeys(it) },
        )

    private companion object {
        /** Rows per prune transaction. */
        const val PRUNE_BATCH_SIZE = 500
    }
}
