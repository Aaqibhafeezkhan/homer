package homer.app.services.persistence

import homer.app.services.QuestionsService.Question
import homer.app.services.QuestionsService.QuestionStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.union
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Durable access to operator questions. SQLite is authoritative; the
 * answer-wait `CompletableDeferred`s and the per-process callback-token
 * registrations stay in [homer.app.services.QuestionsService]'s memory (they
 * are this instance's live coroutine state, not durable data). See ADR-0020 /
 * ADR-0019.
 */
class QuestionsRepository(
    private val db: DatabaseService,
) {
    /** Result of an atomic answer attempt (mirrors the old in-`Mutex` guard). */
    sealed interface AnswerResult {
        data object NotFound : AnswerResult

        data class NotOpen(
            val status: QuestionStatus,
        ) : AnswerResult

        data class Answered(
            val question: Question,
        ) : AnswerResult
    }

    /**
     * Counts the process's OPEN questions and inserts only when still under
     * [maxOpenPerProcess] — both in one transaction so concurrent asks can't
     * race past the cap. Returns false when the cap is reached.
     */
    suspend fun tryInsert(
        question: Question,
        maxOpenPerProcess: Int,
    ): Boolean =
        db.query {
            val openCount =
                Questions
                    .selectAll()
                    .where {
                        (Questions.processId eq question.processId) and (Questions.status eq QuestionStatus.OPEN.name) and
                            (Questions.dispatchBound eq 0)
                    }.count()
            if (openCount >= maxOpenPerProcess.toLong()) {
                return@query false
            }
            Questions.insert {
                it[id] = question.id
                it[processId] = question.processId
                it[agentName] = question.agentName
                it[text] = question.text
                it[optionsJson] = encodeOptions(question.options)
                it[status] = question.status.name
                it[createdAt] = question.createdAt
                it[dispatchBound] = if (question.dispatchBound) 1 else 0
            }
            true
        }

    suspend fun get(id: String): Question? =
        db.read {
            Questions
                .selectAll()
                .where { Questions.id eq id }
                .singleOrNull()
                ?.toQuestion()
        }

    suspend fun markAnswered(
        id: String,
        answer: String,
        answeredAtSec: Long,
    ): AnswerResult =
        db.query {
            val row =
                Questions
                    .selectAll()
                    .where { Questions.id eq id }
                    .singleOrNull()
                    ?: return@query AnswerResult.NotFound
            val current = QuestionStatus.valueOf(row[Questions.status])
            if (current != QuestionStatus.OPEN) {
                return@query AnswerResult.NotOpen(current)
            }
            Questions.update({ Questions.id eq id }) {
                it[status] = QuestionStatus.ANSWERED.name
                it[Questions.answer] = answer
                it[answeredAt] = answeredAtSec
            }
            AnswerResult.Answered(
                row.toQuestion().copy(status = QuestionStatus.ANSWERED, answer = answer, answeredAt = answeredAtSec),
            )
        }

    /**
     * One page of questions, oldest first (rowid, the insertion order), and the count of all rows
     * matching the filters. [visibleAgents] null means an admin (no visibility filter); otherwise a
     * question matches when its asking process is owned by [visibleOwner] or it was asked by one of
     * [visibleAgents] (a dispatch-bound question outlives its process, so the agent arm reads the
     * question's own column). Visibility runs in SQL before the limit, so neither the page nor the
     * total carries a question the caller cannot see (ADR-0039).
     *
     * Every filtered shape is `rowid IN (<one SELECT rowid per visibility arm, UNIONed>)`, as in
     * [FiredRecords.page]: each arm seeks its own index (`processes_owner` then
     * `questions_process_dispatch_bound`, `questions_agent_name`, `questions_status_dispatch_bound`)
     * and the outer read walks the rowid set in order, so the page needs no sort. The plain
     * `x OR y` form plans as a walk of the whole table once statistics exist. Only the unfiltered
     * admin page walks the table, reading offset + limit rows. DatabaseMigrationTest pins every shape.
     */
    suspend fun page(
        status: QuestionStatus?,
        processId: Int?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
    ): RecordPage<Question> =
        db.read {
            val filter = pageFilter(status, processId, visibleOwner, visibleAgents)
            val query = if (filter == null) Questions.selectAll() else Questions.selectAll().where { filter }
            val total = query.count().toInt()
            if (offset >= total) return@read RecordPage(emptyList(), total)
            val rows =
                query
                    .orderBy(QuestionRowId to SortOrder.ASC)
                    .limit(limit)
                    .offset(offset.toLong())
                    .map { it.toQuestion() }
            RecordPage(rows, total)
        }

    private object QuestionRowId : Expression<Long>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("questions.rowid")
        }
    }

    /** [page]'s predicate; null = no filter at all. An empty visibility is the owner arm alone, never `x OR 0`. */
    private fun pageFilter(
        status: QuestionStatus?,
        processId: Int?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
    ): Op<Boolean>? {
        val filters =
            listOfNotNull(
                status?.let { Questions.status eq it.name },
                processId?.let { Questions.processId eq it },
            )

        fun arm(visibility: Op<Boolean>?): Query =
            Questions.select(QuestionRowId).where { (filters + listOfNotNull(visibility)).reduce { a, b -> a and b } }
        if (visibleAgents == null) return if (filters.isEmpty()) null else QuestionRowId inSubQuery arm(null)
        val arms =
            listOfNotNull(
                visibleOwner?.let { owner ->
                    arm(Questions.processId inSubQuery Processes.select(Processes.id).where { Processes.owner eq owner })
                },
                visibleAgents.takeIf { it.isNotEmpty() }?.let { arm(Questions.agentName inList it) },
            )
        if (arms.isEmpty()) return Op.FALSE
        return QuestionRowId inSubQuery arms.drop(1).fold<Query, AbstractQuery<*>>(arms.first()) { acc, q -> acc.union(q) }
    }

    suspend fun openCountByProcess(): Map<Int, Int> =
        db.read {
            // Only process_id: questions_status_dispatch_bound then covers the whole read.
            Questions
                .select(Questions.processId)
                .where { (Questions.status eq QuestionStatus.OPEN.name) and (Questions.dispatchBound eq 0) }
                .map { it[Questions.processId] }
                .groupingBy { it }
                .eachCount()
        }

    /** Marks the process's OPEN questions EXPIRED and returns them (for waiter wakeups + events). Skips dispatch-bound questions. */
    suspend fun expireForProcess(processId: Int): List<Question> =
        db.query {
            val open =
                Questions
                    .selectAll()
                    .where {
                        (Questions.processId eq processId) and
                            (Questions.status eq QuestionStatus.OPEN.name) and
                            (Questions.dispatchBound eq 0)
                    }.map { it.toQuestion() }
            if (open.isNotEmpty()) {
                Questions.update({
                    (Questions.processId eq processId) and
                        (Questions.status eq QuestionStatus.OPEN.name) and
                        (Questions.dispatchBound eq 0)
                }) {
                    it[status] = QuestionStatus.EXPIRED.name
                }
            }
            open.map { it.copy(status = QuestionStatus.EXPIRED) }
        }

    /** Marks one question EXPIRED if it is still OPEN; true when it changed. Keyed by the primary key. */
    suspend fun expireIfOpen(id: String): Boolean =
        db.query {
            Questions.update({ (Questions.id eq id) and (Questions.status eq QuestionStatus.OPEN.name) }) {
                it[status] = QuestionStatus.EXPIRED.name
            } > 0
        }

    /** Deletes one question; true when it existed. Keyed by the primary key. */
    suspend fun delete(id: String): Boolean = db.query { Questions.deleteWhere { Questions.id eq id } > 0 }

    /** Deletes the process's non-dispatch-bound questions and returns their ids (for waiter wakeups + cleanup). */
    suspend fun removeForProcess(processId: Int): List<String> =
        db.query {
            val ids =
                Questions
                    .select(Questions.id)
                    .where { (Questions.processId eq processId) and (Questions.dispatchBound eq 0) }
                    .map { it[Questions.id] }
            if (ids.isNotEmpty()) {
                Questions.deleteWhere { (Questions.processId eq processId) and (Questions.dispatchBound eq 0) }
            }
            ids
        }

    private fun ResultRow.toQuestion(): Question =
        Question(
            id = this[Questions.id],
            processId = this[Questions.processId],
            agentName = this[Questions.agentName],
            text = this[Questions.text],
            options = decodeOptions(this[Questions.optionsJson]),
            status = QuestionStatus.valueOf(this[Questions.status]),
            answer = this[Questions.answer],
            createdAt = this[Questions.createdAt],
            answeredAt = this[Questions.answeredAt],
            dispatchBound = this[Questions.dispatchBound] == 1,
        )

    private companion object {
        fun encodeOptions(value: List<String>) = columnJson.encodeToString(stringListSerializer, value)

        fun decodeOptions(text: String) = columnJson.decodeFromString(stringListSerializer, text)
    }
}
