package homer.app.services.persistence

import homer.app.services.Dispatch
import homer.app.services.DispatchKind
import homer.app.services.DispatchStatus
import homer.app.services.QuestionsService.Question
import homer.app.services.QuestionsService.QuestionStatus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select

// Row-level helpers of [DispatchRepository] (ADR-0021, ADR-0105): the SINGLE/GROUP question
// teardown, the ordered member reads, and the row <-> [Dispatch] mapping. All of them run inside
// the caller's transaction.

internal fun deleteBoundQuestions(questionIds: List<String>) {
    if (questionIds.isEmpty()) return
    for (chunk in questionIds.chunked(SQLITE_PARAM_CHUNK)) {
        Questions.deleteWhere { (Questions.id inList chunk) and (Questions.dispatchBound eq 1) }
    }
}

/**
 * Deletes the member questions of the GROUP dispatches in [dispatchIds] and returns their
 * ordered member ids — [deleteBoundQuestions]'s group arm, read once and reused so a caller
 * settling a group can report the ids it deleted. The member rows in
 * `dispatch_group_questions` stay: they are what a settled group still reports its question
 * ids from, and the settled-row prune deletes them with the dispatch.
 */
internal fun deleteGroupQuestions(dispatchIds: Collection<Int>): Map<Int, List<String>> {
    if (dispatchIds.isEmpty()) return emptyMap()
    val byDispatch = groupQuestionIdsTx(dispatchIds)
    deleteBoundQuestions(byDispatch.values.flatten())
    return byDispatch
}

/** The asking process's OPEN dispatch-bound questions: `questions_process_dispatch_bound`. */
internal fun openDispatchBoundQuestionsTx(processId: Int): Long =
    Questions
        .select(Questions.id)
        .where {
            (Questions.processId eq processId) and
                (Questions.dispatchBound eq 1) and
                (Questions.status eq QuestionStatus.OPEN.name)
        }.count()

/** The ordered member question ids of each dispatch in [dispatchIds], inside the caller's transaction. */
internal fun groupQuestionIdsTx(dispatchIds: Collection<Int>): Map<Int, List<String>> =
    dispatchIds
        .chunked(SQLITE_PARAM_CHUNK)
        .flatMap { chunk ->
            DispatchGroupQuestions
                .select(DispatchGroupQuestions.dispatchId, DispatchGroupQuestions.questionId)
                .where { DispatchGroupQuestions.dispatchId inList chunk }
                .orderBy(
                    DispatchGroupQuestions.dispatchId to SortOrder.ASC,
                    DispatchGroupQuestions.position to SortOrder.ASC,
                ).map { it[DispatchGroupQuestions.dispatchId] to it[DispatchGroupQuestions.questionId] }
        }.groupBy({ it.first }, { it.second })

/** [groupMembers] for several dispatches at once, inside the caller's transaction. */
internal fun groupMembersTx(dispatchIds: Collection<Int>): Map<Int, List<DispatchRepository.GroupMember>> =
    dispatchIds
        .chunked(SQLITE_PARAM_CHUNK)
        .flatMap { chunk ->
            DispatchGroupQuestions
                .join(Questions, JoinType.INNER, DispatchGroupQuestions.questionId, Questions.id)
                .select(
                    DispatchGroupQuestions.dispatchId,
                    DispatchGroupQuestions.questionId,
                    Questions.status,
                    Questions.answer,
                ).where { DispatchGroupQuestions.dispatchId inList chunk }
                .orderBy(
                    DispatchGroupQuestions.dispatchId to SortOrder.ASC,
                    DispatchGroupQuestions.position to SortOrder.ASC,
                ).map {
                    it[DispatchGroupQuestions.dispatchId] to
                        DispatchRepository.GroupMember(
                            it[DispatchGroupQuestions.questionId],
                            if (it[Questions.status] == QuestionStatus.ANSWERED.name) it[Questions.answer] ?: "" else null,
                        )
                }
        }.groupBy({ it.first }, { it.second })

/**
 * [dispatches] with the ordered member question ids filled in on every GROUP row among them,
 * in one batched read. SINGLE rows keep an empty list: their one question is `questionId`.
 * Must run inside a read or write transaction.
 */
internal fun attachGroupQuestionIds(dispatches: List<Dispatch>): List<Dispatch> {
    val groupIds = dispatches.filter { it.kind == DispatchKind.GROUP }.map { it.id }
    if (groupIds.isEmpty()) return dispatches
    val byDispatch = groupQuestionIdsTx(groupIds)
    return dispatches.map { if (it.kind == DispatchKind.GROUP) it.copy(questionIds = byDispatch[it.id].orEmpty()) else it }
}

internal fun InsertStatement<*>.setQuestionColumns(question: Question) {
    this[Questions.id] = question.id
    this[Questions.processId] = question.processId
    this[Questions.agentName] = question.agentName
    this[Questions.text] = question.text
    this[Questions.optionsJson] = columnJson.encodeToString(stringListSerializer, question.options)
    this[Questions.status] = question.status.name
    this[Questions.createdAt] = question.createdAt
    this[Questions.dispatchBound] = 1
}

internal fun InsertStatement<*>.setDispatchColumns(dispatch: Dispatch) {
    this[Dispatches.questionId] = dispatch.questionId
    this[Dispatches.status] = dispatch.status.name
    this[Dispatches.agentName] = dispatch.agentName
    this[Dispatches.answerParam] = dispatch.answerParam
    this[Dispatches.params] = columnJson.encodeToString(stringMapSerializer, dispatch.params)
    this[Dispatches.originProcessId] = dispatch.originProcessId
    this[Dispatches.originAgentName] = dispatch.originAgentName
    this[Dispatches.expiresAt] = dispatch.expiresAt
    this[Dispatches.createdAt] = dispatch.createdAt
    this[Dispatches.owner] = dispatch.owner
    this[Dispatches.secretInputKeys] = dispatch.secretInputKeys?.let { encodeKeys(it) }
    this[Dispatches.secretParamNames] = dispatch.secretParamNames?.let { encodeKeys(it) }
    this[Dispatches.kind] = dispatch.kind.name
}

internal fun ResultRow.toDispatch(): Dispatch =
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
        kind = DispatchKind.valueOf(this[Dispatches.kind]),
    )
