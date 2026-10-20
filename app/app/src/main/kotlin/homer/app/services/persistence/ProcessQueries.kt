package homer.app.services.persistence

import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.SubRunProgress
import homer.app.services.ProcessManager.CostByTagResult
import homer.app.services.ProcessManager.ProcessListResult
import homer.app.services.QuestionsService.QuestionStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

// The read-only queries over many process rows: the paged, filtered list behind `GET /status/all`,
// the cost-by-tag fold, and the batched id reads. Every one runs on the reader handle (`db.read`).

suspend fun ProcessRepository.getAll(
    filterStatuses: Set<ProcessStatus>?,
    orderAsc: Boolean,
    offset: Int,
    limit: Int,
    agentName: String?,
    visibleOwner: String? = null,
    /**
     * Null means an admin: no visibility filter, and the index-driven `?tag=` subquery,
     * whose cost follows every run with the tag. Never null for a principal-scoped caller.
     * See ADR-0060 (2026-09-29 amendment).
     */
    visibleAgents: Set<String>? = null,
    parentProcessId: Int? = null,
    rootProcessId: Int? = null,
    /**
     * Roots-only: `parent_process_id IS NULL`, i.e. the first run of each
     * flow. Composes with every other predicate here (AND). See ADR-0058.
     */
    rootsOnly: Boolean = false,
    tags: List<String> = emptyList(),
    /**
     * Read-time tag screen for the filtered path: given a matched row's
     * [ProcessInfo], returns the tags that survive display redaction. Required
     * whenever [tags] is non-empty, with no default-null escape — a defaulted
     * screen is how a future caller silently re-opens the CWE-203 oracle (ADR-0052
     * applies the same rule to `langfuseTraceTags`). [getAll] fails closed when
     * [tags] is non-empty and this is null.
     */
    screenTags: ((ProcessInfo) -> List<String>)? = null,
    /**
     * The caller's visibility check for a flow-root row, run in this read's transaction.
     * Required whenever [rootProcessId] is set ([getAll] throws otherwise): the filter is
     * honoured only when that root row exists and passes it (otherwise an empty page).
     * [ProcessListResult.visibleRootIds] names the page's roots that pass; when null, no
     * root is named visible. See ADR-0058.
     */
    canSeeRoot: ((owner: String?, agentName: String) -> Boolean)? = null,
): ProcessListResult {
    requireListGuards(tags, screenTags, rootProcessId, canSeeRoot)
    // AND semantics for repeated ?tag=: a row must carry ALL de-duplicated
    // tags, matched in SQL by carriesAllTags (GROUP BY ... HAVING COUNT = n):
    // a correlated EXISTS for a scoped caller, an index-driven id IN (SELECT ...)
    // for an admin. Nothing is materialized in the application. No match → empty
    // page, never an error, so "no such tag" and "no such tag you can see" are
    // one answer. See ADR-0058 and ADR-0060.
    val dedupedTags = tags.distinct()
    val order = Processes.id to if (orderAsc) SortOrder.ASC else SortOrder.DESC
    return db.read {
        // [information_disclosure / CWE-200] An invisible or missing root reads exactly
        // like an empty flow, never like "no filter". §12.
        if (rootProcessId != null) {
            val canSee = checkNotNull(canSeeRoot) { "requireListGuards admits no rootProcessId without canSeeRoot" }
            if (rootProcessId !in visibleRootIdsTx(listOf(rootProcessId), canSee)) {
                return@read ProcessListResult(processes = emptyList(), total = 0)
            }
        }
        val query =
            Processes.selectAll().where {
                withAccessFilter(
                    rowFilter(filterStatuses, agentName, parentProcessId, rootProcessId, rootsOnly),
                    dedupedTags,
                    visibleOwner,
                    visibleAgents,
                )
            }
        // [CWE-203] Apply the display-redaction screen per row before
        // accepting a tag match: the SQL predicate above matches unredacted values.
        // See ADR-0060 for why the screen cannot live in the SQL predicate.
        if (dedupedTags.isNotEmpty() && screenTags != null) {
            return@read screenedPageTx(query, order, dedupedTags, screenTags, offset, limit, canSeeRoot)
        }
        // Planned on the v23 indexes; a new predicate here needs one too (DatabaseMigrationTest pins the plans).
        val total = query.count().toInt()
        // offset is caller-chosen and unbounded above; past the end the page
        // is empty by definition, so skip the scan SQLite would otherwise
        // walk-and-discard through the whole table.
        if (offset >= total) return@read pageResultTx(emptyList(), total, canSeeRoot)
        val rows =
            query
                .orderBy(order)
                .limit(limit)
                .offset(offset.toLong())
                .toList()
        pageResultTx(rows.toProcessInfosTx(jobRegistry).toList(), total, canSeeRoot)
    }
}

/**
 * [getAll]'s fail-closed argument checks: a tag-filtered read without a screen cannot honour the
 * display redaction, so the call never leads to a `?tag=` oracle (ADR-0060); an
 * ungated `?rootProcessId=` lists the visible rows of a hidden flow, an existence oracle for its
 * root (CWE-200, ADR-0058).
 */
private fun requireListGuards(
    tags: List<String>,
    screenTags: ((ProcessInfo) -> List<String>)?,
    rootProcessId: Int?,
    canSeeRoot: ((owner: String?, agentName: String) -> Boolean)?,
) {
    if (tags.distinct().isNotEmpty() && screenTags == null) {
        throw IllegalArgumentException("screenTags is required whenever tags is non-empty")
    }
    if (rootProcessId != null && canSeeRoot == null) {
        throw IllegalArgumentException("canSeeRoot is required whenever rootProcessId is set")
    }
}

/** [getAll]'s column predicates, ANDed onto `TRUE` in this order. */
private fun rowFilter(
    filterStatuses: Set<ProcessStatus>?,
    agentName: String?,
    parentProcessId: Int?,
    rootProcessId: Int?,
    rootsOnly: Boolean,
): Op<Boolean> {
    var cond: Op<Boolean> = Op.TRUE
    if (filterStatuses != null) {
        cond = cond and (Processes.status inList filterStatuses.map { it.name })
    }
    if (agentName != null) {
        cond = cond and (Processes.agentName eq agentName)
    }
    if (parentProcessId != null) {
        cond = cond and (Processes.parentProcessId eq parentProcessId)
    }
    if (rootProcessId != null) {
        cond = cond and (Processes.rootProcessId eq rootProcessId)
    }
    if (rootsOnly) {
        cond = cond and Processes.parentProcessId.isNull()
    }
    return cond
}

/** [cond] with [getAll]'s tag match and visibility rule ANDed on, in that order. */
private fun withAccessFilter(
    cond: Op<Boolean>,
    dedupedTags: List<String>,
    visibleOwner: String?,
    visibleAgents: Set<String>?,
): Op<Boolean> {
    var filtered = cond
    if (dedupedTags.isNotEmpty()) {
        // [denial_of_service / CWE-400] A SQL subquery, never an app-side id
        // list: that would grow with every visible matching run and cap out
        // at SQLite's bound-parameter limit. Only an admin (no visibility
        // filter) gets the index-driven form: its cost follows every run with
        // the tag, so for a scoped caller its timing would count other
        // principals' runs. See ADR-0060 (2026-09-29 amendment).
        filtered = filtered and carriesAllTags(dedupedTags, indexDriven = visibleAgents == null)
    }
    // null visibleAgents = admin = no visibility filter; an empty set is "owner only".
    if (visibleAgents != null) {
        filtered = filtered and visibleTo(visibleOwner, visibleAgents)
    }
    return filtered
}

/**
 * The tag-screened page: [query]'s candidates, newest or oldest first by [order], screened per row
 * by [screenTags] before paging, so its total counts only rows whose tags survive redaction.
 */
private fun ProcessRepository.screenedPageTx(
    query: Query,
    order: Pair<Column<Int>, SortOrder>,
    dedupedTags: List<String>,
    screenTags: (ProcessInfo) -> List<String>,
    offset: Int,
    limit: Int,
    canSeeRoot: ((owner: String?, agentName: String) -> Boolean)?,
): ProcessListResult {
    // [CWE-400] SQL-level bound, not only the
    // caller's `limit`: without it the whole candidate set is fetched into
    // the application before the screen runs. Tags arrive in one batched
    // IN-list read (the paging path's helper), never per row.
    val rows =
        query
            .orderBy(order)
            .limit(MAX_TAG_SCREENED_ROWS)
            .toList()
    val ordered = rows.toProcessInfosTx(jobRegistry).filter { info -> dedupedTags.all { it in screenTags(info) } }.toList()
    val screenedTotal = ordered.size
    val page = ordered.drop(offset).take(limit)
    return pageResultTx(page, screenedTotal, canSeeRoot)
}

/**
 * Rows [visibleOwner] owns or of one of [visibleAgents]. With no agents it is the owner clause
 * alone: `owner = ? OR 0` keeps SQLite off `processes_owner`, and the page would scan every run.
 */
private fun visibleTo(
    visibleOwner: String?,
    visibleAgents: Collection<String>,
): Op<Boolean> {
    val ownerClause: Op<Boolean> = if (visibleOwner != null) (Processes.owner eq visibleOwner) else Op.FALSE
    return if (visibleAgents.isEmpty()) ownerClause else ownerClause or (Processes.agentName inList visibleAgents)
}

// The page's open-question counts and root visibility, read in the list's own
// transaction as IN reads over the page's ids: one snapshot, no per-row query.
private fun pageResultTx(
    page: List<ProcessInfo>,
    total: Int,
    canSeeRoot: ((owner: String?, agentName: String) -> Boolean)?,
): ProcessListResult =
    ProcessListResult(
        processes = page,
        total = total,
        openQuestions = openQuestionCountsTx(page.map { it.id }),
        visibleRootIds = canSeeRoot?.let { visibleRootIdsTx(page.mapNotNull { it.rootProcessId }.distinct(), it) } ?: emptySet(),
    )

// Served by questions_status_dispatch_bound (status, dispatch_bound, process_id), covering.
private fun openQuestionCountsTx(ids: List<Int>): Map<Int, Int> {
    if (ids.isEmpty()) return emptyMap()
    val counts = mutableMapOf<Int, Int>()
    for (chunk in ids.chunked(SQLITE_PARAM_CHUNK)) {
        Questions
            .select(Questions.processId)
            .where {
                (Questions.status eq QuestionStatus.OPEN.name) and (Questions.dispatchBound eq 0) and
                    (Questions.processId inList chunk)
            }.forEach { row -> counts.merge(row[Questions.processId], 1, Int::plus) }
    }
    return counts
}

// [sensitive_data_exposure / CWE-200] The ids among [rootIds] whose row exists and passes
// [canSeeRoot]; a missing row fails closed. By primary key, only the columns the check reads.
private fun visibleRootIdsTx(
    rootIds: List<Int>,
    canSeeRoot: (owner: String?, agentName: String) -> Boolean,
): Set<Int> {
    if (rootIds.isEmpty()) return emptySet()
    val visible = mutableSetOf<Int>()
    for (chunk in rootIds.chunked(SQLITE_PARAM_CHUNK)) {
        Processes
            .select(Processes.id, Processes.owner, Processes.agentName)
            .where { Processes.id inList chunk }
            .forEach { row -> if (canSeeRoot(row[Processes.owner], row[Processes.agentName])) visible += row[Processes.id] }
    }
    return visible
}

/**
 * Groups [Processes.costUsd] by tag value for `key`. [screenTags] MUST be
 * applied per row before grouping — never in SQL — to honour the CWE-203
 * secret-screen invariant. The candidate query is bounded at [maxRows]
 * (production default [MAX_TAG_SCREENED_ROWS]; overridable only for tests),
 * newest runs first, and [CostByTagResult.truncated] reports whether more
 * candidates existed than fit that bound. See ADR-0062.
 *
 * Rows are read through [Processes.recordReadColumns]: [screenTags] sees tags, inputs and
 * the record-level columns, never `history`/`executions`/`runner_state` (read as empty).
 */
suspend fun ProcessRepository.costByTag(
    key: String,
    screenTags: (ProcessInfo) -> List<String>,
    maxRows: Int = MAX_TAG_SCREENED_ROWS,
): CostByTagResult =
    db.read {
        val prefix = "$key:"
        // Range bound instead of `LIKE`: every character TagValidation allows in a
        // key or value sorts strictly between ':' (0x3A) and ';' (0x3B), so this
        // matches exactly `key:*` with none of the `_`/`%` wildcard ambiguity a
        // `LIKE` pattern built from a caller-influenced key would carry. See ADR-0062.
        val exclusiveUpperBound = "$key;"
        // Fetch one row past the bound to detect truncation, then drop it: ORDER BY
        // id DESC keeps the newest maxRows candidates when there are more than that.
        val fetched =
            Processes
                .select(Processes.recordReadColumns)
                .where { hasTagPrefix(prefix, exclusiveUpperBound) }
                .orderBy(Processes.id to SortOrder.DESC)
                .limit(maxRows + 1)
                .toList()
        val truncated = fetched.size > maxRows
        val rows = if (truncated) fetched.dropLast(1) else fetched
        if (rows.isEmpty()) return@read CostByTagResult(emptyMap(), truncated)
        val totals = mutableMapOf<String, Double>()
        for (info in rows.toProcessInfosTx(jobRegistry)) {
            for (tag in screenTags(info)) {
                if (tag.startsWith(prefix)) {
                    val value = tag.substring(prefix.length)
                    totals[value] = (totals[value] ?: 0.0) + info.costUsd
                }
            }
        }
        CostByTagResult(totals, truncated)
    }

// Both forms match a run whose tags include every requested tag: (process_id, tag) is
// unique, so a group of n rows means all n are present. [indexDriven]:
// `id IN (SELECT process_id FROM process_tags WHERE tag IN (...) GROUP BY process_id
// HAVING COUNT = n)`, driven by process_tags_tag_process_id. Otherwise a
// correlated EXISTS probed per row the visibility clause admits, so its cost never
// depends on runs the caller cannot see. See ADR-0060.
private fun carriesAllTags(
    dedupedTags: List<String>,
    indexDriven: Boolean,
): Op<Boolean> =
    if (indexDriven) {
        Processes.id inSubQuery
            ProcessTags
                .select(ProcessTags.processId)
                .where { ProcessTags.tag inList dedupedTags }
                .groupBy(ProcessTags.processId)
                .having { ProcessTags.processId.count() eq dedupedTags.size.toLong() }
    } else {
        exists(
            ProcessTags
                .select(ProcessTags.processId)
                .where { (ProcessTags.processId eq Processes.id) and (ProcessTags.tag inList dedupedTags) }
                .groupBy(ProcessTags.processId)
                .having { ProcessTags.processId.count() eq dedupedTags.size.toLong() },
        )
    }

// Index-driven on process_tags_tag_process_id; a correlated EXISTS plans as SCAN processes.
// See ADR-0062 (2026-09-29 amendment).
private fun hasTagPrefix(
    prefix: String,
    exclusiveUpperBound: String,
): Op<Boolean> =
    Processes.id inSubQuery
        ProcessTags
            .select(ProcessTags.processId)
            .where {
                (ProcessTags.tag greaterEq prefix) and (ProcessTags.tag less exclusiveUpperBound)
            }

/**
 * (id, agent name) of CREATED runs of the given agents, for `/agents/reload` to kill. Served
 * by `processes_agent_status_ended`; empty [agentNames] returns empty without a query.
 */
suspend fun ProcessRepository.createdRunsOf(agentNames: Collection<String>): List<Pair<Int, String>> {
    if (agentNames.isEmpty()) return emptyList()
    return db.read {
        Processes
            .select(Processes.id, Processes.agentName)
            .where { (Processes.status eq ProcessStatus.CREATED.name) and (Processes.agentName inList agentNames) }
            .map { it[Processes.id] to it[Processes.agentName] }
    }
}

/**
 * The progress of each of [ids] that still exists, keyed by id: one chunked `id IN (…)` read
 * on the primary key of only the columns a LangGraph `subs` entry shows. An evicted or purged
 * id is simply absent. See ADR-0044.
 */
suspend fun ProcessRepository.subRunProgress(ids: Collection<Int>): Map<Int, SubRunProgress> {
    if (ids.isEmpty()) return emptyMap()
    return db.read {
        val byId = mutableMapOf<Int, SubRunProgress>()
        for (chunk in ids.distinct().chunked(SQLITE_PARAM_CHUNK)) {
            Processes
                .select(Processes.id, Processes.agentName, Processes.status, Processes.executions, Processes.currentCommand)
                .where { Processes.id inList chunk }
                .forEach { row ->
                    byId[row[Processes.id]] =
                        SubRunProgress(
                            agentName = row[Processes.agentName],
                            status = ProcessStatus.valueOf(row[Processes.status]),
                            executions = decodeExecutions(row[Processes.executions]),
                            currentCommand = row[Processes.currentCommand],
                        )
                }
        }
        byId
    }
}

suspend fun ProcessRepository.activeProcessIds(): Set<Int> =
    db.read {
        Processes
            .select(Processes.id)
            .where { Processes.status inList ACTIVE_STATUS_NAMES }
            .map { it[Processes.id] }
            .toSet()
    }
