package homer.app.services.persistence

import homer.app.services.Dispatch
import homer.app.services.DispatchKind
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
import org.jetbrains.exposed.v1.jdbc.batchInsert
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
 *
 * A SINGLE dispatch is one question and one dispatch row. A GROUP dispatch (ADR-0105) is one
 * dispatch row with `kind = GROUP` and a synthetic `question_id` sentinel, plus N member rows in
 * [DispatchGroupQuestions] that own which questions it is and in what order their answers are
 * aggregated. Every read that resolves a question id, and every teardown path, therefore covers
 * both shapes.
 */
class DispatchRepository(
    private val db: DatabaseService,
    nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val records = FiredRecords(db, Dispatches, DispatchStatus.entries.map { it.name }, nowSec)

    /** One member question of a GROUP dispatch: its id, and its answer once it is ANSWERED. */
    data class GroupMember(
        val questionId: String,
        val answer: String?,
    )

    /** The outcome of [createGroup]: the new dispatch id, or the cap that refused it. */
    sealed interface GroupCreateOutcome {
        data class Created(
            val dispatchId: Int,
        ) : GroupCreateOutcome

        data object PendingCapReached : GroupCreateOutcome

        data object OpenQuestionCapReached : GroupCreateOutcome
    }

    suspend fun create(
        question: Question,
        dispatch: Dispatch,
        maxPending: Int,
    ): Int? =
        db.query {
            if (records.pendingCapReachedTx(maxPending)) return@query null
            Questions.insert { it.setQuestionColumns(question) }
            Dispatches.insert { it.setDispatchColumns(dispatch) }[Dispatches.id]
        }

    /**
     * Inserts a GROUP [dispatch] row, its N [questions] (each one's index in the list is its
     * `position`) and their member rows, all in one transaction — so a refused group leaves no
     * question and no member row behind, and a created one is never observably partial.
     *
     * Refused by the global PENDING cap, or by the asking process's open-question cap counted
     * over its dispatch-bound questions: a group adds up to `dispatch.maxGroupSize` of them in a
     * single call, which is the multiplication this bound exists for. See ADR-0105.
     */
    suspend fun createGroup(
        questions: List<Question>,
        dispatch: Dispatch,
        maxPending: Int,
        maxOpenPerProcess: Int,
    ): GroupCreateOutcome =
        db.query {
            if (records.pendingCapReachedTx(maxPending)) return@query GroupCreateOutcome.PendingCapReached
            if (openDispatchBoundQuestionsTx(dispatch.originProcessId) + questions.size > maxOpenPerProcess) {
                return@query GroupCreateOutcome.OpenQuestionCapReached
            }
            Questions.batchInsert(questions, shouldReturnGeneratedValues = false) { setQuestionColumns(it) }
            val dispatchId = Dispatches.insert { it.setDispatchColumns(dispatch) }[Dispatches.id]
            DispatchGroupQuestions.batchInsert(
                questions.withIndex().toList(),
                shouldReturnGeneratedValues = false,
            ) { (position, question) ->
                this[DispatchGroupQuestions.dispatchId] = dispatchId
                this[DispatchGroupQuestions.questionId] = question.id
                this[DispatchGroupQuestions.position] = position
            }
            GroupCreateOutcome.Created(dispatchId)
        }

    suspend fun get(id: Int): Dispatch? =
        db.read {
            val row = Dispatches.selectAll().where { Dispatches.id eq id }.singleOrNull() ?: return@read null
            attachGroupQuestionIds(listOf(row.toDispatch())).first()
        }

    /**
     * The dispatch a question belongs to: the row whose own `question_id` is [questionId], or —
     * for a member question of a GROUP — the group it belongs to, through
     * `dispatch_group_questions`. See ADR-0105.
     */
    suspend fun findByQuestionId(questionId: String): Dispatch? =
        db.read {
            val row =
                Dispatches
                    .selectAll()
                    .where { Dispatches.questionId eq questionId }
                    .singleOrNull()
                    ?: Dispatches
                        .join(DispatchGroupQuestions, JoinType.INNER, Dispatches.id, DispatchGroupQuestions.dispatchId)
                        .selectAll()
                        .where { DispatchGroupQuestions.questionId eq questionId }
                        .singleOrNull()
                    ?: return@read null
            attachGroupQuestionIds(listOf(row.toDispatch())).first()
        }

    /**
     * The dispatches owning [ids], keyed by the id each was asked under — a member question of a
     * group resolves to its group, so the questions inbox annotates every member with it. Two
     * batched reads, never one per id.
     */
    suspend fun findByQuestionIds(ids: Collection<String>): Map<String, Dispatch> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            db.read {
                val direct =
                    Dispatches
                        .selectAll()
                        .where { Dispatches.questionId inList ids }
                        .map { it[Dispatches.questionId] to it.toDispatch() }
                val memberOf =
                    DispatchGroupQuestions
                        .select(DispatchGroupQuestions.dispatchId, DispatchGroupQuestions.questionId)
                        .where { DispatchGroupQuestions.questionId inList ids }
                        .map { it[DispatchGroupQuestions.questionId] to it[DispatchGroupQuestions.dispatchId] }
                val groups =
                    if (memberOf.isEmpty()) {
                        emptyMap()
                    } else {
                        Dispatches
                            .selectAll()
                            .where { Dispatches.id inList memberOf.map { it.second }.distinct() }
                            .map { it.toDispatch() }
                            .associateBy { it.id }
                    }
                val resolved =
                    direct + memberOf.mapNotNull { (questionId, dispatchId) -> groups[dispatchId]?.let { questionId to it } }
                val withQuestionIds = attachGroupQuestionIds(resolved.map { it.second }).associateBy { it.id }
                resolved.associate { (questionId, dispatch) -> questionId to withQuestionIds.getValue(dispatch.id) }
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
        records.page(status?.name, agentName, visibleOwner, visibleAgents, offset, limit) { rows ->
            attachGroupQuestionIds(rows.map { it.toDispatch() })
        }

    /**
     * The member questions of GROUP dispatch [dispatchId] in `position` order — the order their
     * answers are aggregated in — each with its answer, or null while it is still OPEN. Empty for
     * a SINGLE dispatch. This is the group's fire gate: the target starts only once no answer is
     * null. See ADR-0105.
     */
    suspend fun groupMembers(dispatchId: Int): List<GroupMember> = db.read { groupMembersTx(listOf(dispatchId))[dispatchId].orEmpty() }

    /**
     * Deletes settled dispatches (every status but PENDING and DISPATCHING) created before
     * [cutoff], with their dispatch-bound questions and a group's member rows, in batches of at
     * most [batchSize] ([FiredRecords.pruneSettled]). Returns the number of dispatches deleted.
     * See ADR-0021, ADR-0105.
     */
    suspend fun pruneSettled(
        cutoff: Long,
        batchSize: Int = PRUNE_BATCH_SIZE,
    ): Int =
        records.pruneSettled(cutoff, batchSize, listOf(Dispatches.questionId)) { batch ->
            val dispatchIds = batch.map { it[Dispatches.id] }
            // A settled dispatch's questions are normally gone already; this takes any left behind
            // — the single bound question, and every member question of a group.
            deleteBoundQuestions(batch.map { it[Dispatches.questionId] })
            deleteGroupQuestions(dispatchIds)
            for (chunk in dispatchIds.chunked(SQLITE_PARAM_CHUNK)) {
                DispatchGroupQuestions.deleteWhere { DispatchGroupQuestions.dispatchId inList chunk }
            }
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
     * Releases a claimed (DISPATCHING) dispatch back to PENDING, keeping its dispatch-bound
     * question — the drain-refusal counterpart of [markFailed]: the start never happened, so
     * the answer must survive for the next boot's `refirePendingAnswered` (ADR-0102). Deliberately
     * not [transition], which deletes the question on every settled state.
     */
    suspend fun releaseForRetry(id: Int): Boolean =
        db.query {
            Dispatches
                .update(
                    { (Dispatches.id eq id) and (Dispatches.status eq DispatchStatus.DISPATCHING.name) },
                ) { it[Dispatches.status] = DispatchStatus.PENDING.name } > 0
        }

    /**
     * Atomically cancels a PENDING dispatch and deletes its question(s) — one bound question, or
     * every member question of a group. Returns the cancelled dispatch, or null if it was no
     * longer PENDING.
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
            // Read before the delete: the cancelled row still reports the questions it owned, so
            // the caller can emit one expiry event per member.
            val memberIds = deleteGroupQuestions(listOf(id))[id].orEmpty()
            dispatch.copy(status = DispatchStatus.CANCELLED, questionIds = memberIds)
        }

    data class PendingAnswered(
        val dispatch: Dispatch,
        val answer: String,
    )

    /**
     * Every PENDING dispatch whose answer is already in, for the boot re-fire: a SINGLE one whose
     * question is ANSWERED, and a GROUP one whose every member question is. A group's [answer] is
     * empty — the fire path aggregates its members itself. See ADR-0021, ADR-0105.
     */
    suspend fun listPendingAnswered(): List<PendingAnswered> =
        db.read {
            val singles =
                Dispatches
                    .join(Questions, JoinType.INNER, Dispatches.questionId, Questions.id)
                    .selectAll()
                    .where {
                        (Dispatches.status eq DispatchStatus.PENDING.name) and
                            (Questions.status eq QuestionStatus.ANSWERED.name)
                    }.map { PendingAnswered(it.toDispatch(), it[Questions.answer] ?: "") }
            // A GROUP row's own question_id names no question, so the join above never matches one.
            val pendingGroups =
                Dispatches
                    .selectAll()
                    .where {
                        (Dispatches.status eq DispatchStatus.PENDING.name) and (Dispatches.kind eq DispatchKind.GROUP.name)
                    }.map { it.toDispatch() }
            val members = groupMembersTx(pendingGroups.map { it.id })
            val groups =
                pendingGroups
                    .filter { group -> members[group.id]?.let { m -> m.isNotEmpty() && m.all { it.answer != null } } == true }
                    // The member read above is the ordered set, so the row carries it without a
                    // second lookup.
                    .map { group ->
                        PendingAnswered(group.copy(questionIds = members.getValue(group.id).map { it.questionId }), "")
                    }
            singles + groups
        }

    /**
     * Cancels PENDING dispatches past [now] and deletes their questions, atomically.
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
            val memberIds = deleteGroupQuestions(lapsed.map { it.id })
            lapsed.map { it.copy(status = DispatchStatus.CANCELLED, questionIds = memberIds[it.id].orEmpty()) }
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
            val memberIds = deleteGroupQuestions(orphaned.map { it.id })
            orphaned.map {
                it.copy(
                    status = DispatchStatus.FAILED,
                    error = "Interrupted during dispatch by a server restart",
                    dispatchedAt = at,
                    questionIds = memberIds[it.id].orEmpty(),
                )
            }
        }

    /**
     * markDispatched / markFailed both land the dispatch in a terminal state. On a
     * successful transition the dispatch-bound question has served its purpose (its
     * answer is now in the fired run, or the fire failed), so delete it in the same
     * transaction — the question lifecycle is owned by the dispatch, not the long-gone
     * asking process, and leaving these rows behind grew SQLite unbounded. A group's member
     * questions go with it; its member rows stay until the settled-row prune, so a settled
     * group still reports the question ids it was registered with. See ADR-0021, ADR-0105.
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
                deleteGroupQuestions(listOf(id))
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

    private companion object {
        /** Rows per prune transaction. */
        const val PRUNE_BATCH_SIZE = 500
    }
}
