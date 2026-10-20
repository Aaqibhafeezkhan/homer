package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
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
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.union
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The columns `dispatches` and `continuations` share: a row parks PENDING until it is claimed
 * (PENDING→DISPATCHING) and fired once, is TTL-swept, and is pruned once settled. ADR-0021, ADR-0023.
 */
internal interface FiredRecordTable {
    val id: Column<Int>
    val status: Column<String>
    val agentName: Column<String>
    val owner: Column<String?>
    val expiresAt: Column<Long?>
    val createdAt: Column<Long>
}

/**
 * The statements [DispatchRepository] and [ContinuationRepository] run alike on their [table]:
 * the PENDING cap, the claim CAS, the expiry predicate, the list page and the settled-row prune.
 * Each is the statement either repository ran on its own table, served by the same index. A
 * `…Tx` function runs inside its caller's transaction; every other one opens its own, and each
 * state change stays one write transaction. [statuses] are the table's status names, which share
 * `PENDING` and `DISPATCHING`; every other one is settled.
 */
internal class FiredRecords<T>(
    private val db: DatabaseService,
    private val table: T,
    statuses: List<String>,
    private val nowSec: () -> Long,
) where T : Table, T : FiredRecordTable {
    // An IN list rather than NOT IN ('PENDING', 'DISPATCHING'): only an equality on the
    // leading column lets <table>_status_created seek instead of scanning.
    private val settledStatuses = statuses - setOf(PENDING, DISPATCHING)

    /** Whether [maxPending] PENDING rows already exist: the registration cap, in the INSERT's transaction. */
    fun pendingCapReachedTx(maxPending: Int): Boolean = table.selectAll().where { table.status eq PENDING }.count() >= maxPending.toLong()

    /** A PENDING row whose TTL has run out at [now]: an expiry sweep's pre-check and write share it. */
    fun lapsedAt(now: Long): Op<Boolean> =
        (table.status eq PENDING) and
            (table.expiresAt.isNotNull()) and
            (table.expiresAt lessEq now)

    /** A sweep's `db.read` pre-check: whether any row matches [due], so an idle tick skips the write lock. */
    suspend fun anyDue(due: Op<Boolean>): Boolean =
        db.read {
            table
                .select(table.id)
                .where { due }
                .limit(1)
                .any()
        }

    /** The PENDING→DISPATCHING claim CAS: false if row [id] was no longer PENDING or its TTL has lapsed. */
    suspend fun claim(id: Int): Boolean =
        db.query {
            table.update(
                {
                    (table.id eq id) and
                        (table.status eq PENDING) and
                        (table.expiresAt.isNull() or (table.expiresAt greater nowSec()))
                },
            ) { it[table.status] = DISPATCHING } > 0
        }

    /**
     * One page of rows, newest first, mapped by [toRecords] inside the read, and the count of all
     * rows matching the filters. [visibleAgents] null means an admin (no visibility filter);
     * otherwise only rows owned by [visibleOwner] or targeting one of [visibleAgents] match, in SQL
     * before the limit, so neither the page nor [RecordPage.total] carries a row the caller cannot
     * see (ADR-0039).
     *
     * Every filtered shape is `id IN (<one SELECT id per visibility arm, UNIONed>)`: each arm seeks
     * its own index (`<table>_owner`, `<table>_agent_name`, `<table>_status_created`) and the outer
     * read walks the id set in primary-key order, so the page needs no sort. The plain
     * `owner = ? OR agent_name IN (…)` form plans as a backward walk of the whole table once
     * statistics exist. Only the unfiltered admin page walks the table, reading offset + limit rows.
     * DatabaseMigrationTest pins every shape. See ADR-0021 (2026-09-30 amendment).
     */
    suspend fun <R> page(
        status: String?,
        agentName: String?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
        toRecords: (List<ResultRow>) -> List<R>,
    ): RecordPage<R> =
        db.read {
            val filter = pageFilter(status, agentName, visibleOwner, visibleAgents)
            val query = if (filter == null) table.selectAll() else table.selectAll().where { filter }
            val total = query.count().toInt()
            // Past the end the page is empty by definition; skip the walk-and-discard.
            if (offset >= total) return@read RecordPage(emptyList(), total)
            val rows =
                query
                    .orderBy(table.id to SortOrder.DESC)
                    .limit(limit)
                    .offset(offset.toLong())
                    .toList()
            RecordPage(toRecords(rows), total)
        }

    /** [page]'s predicate; null = no filter at all. */
    private fun pageFilter(
        status: String?,
        agentName: String?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
    ): Op<Boolean>? {
        val filters =
            listOfNotNull(
                status?.let { table.status eq it },
                agentName?.let { table.agentName eq it },
            )

        fun arm(visibility: Op<Boolean>?): Query =
            table.select(table.id).where { (filters + listOfNotNull(visibility)).reduce { a, b -> a and b } }
        if (visibleAgents == null) return if (filters.isEmpty()) null else table.id inSubQuery arm(null)
        val arms =
            listOfNotNull(
                visibleOwner?.let { arm(table.owner eq it) },
                visibleAgents.takeIf { it.isNotEmpty() }?.let { arm(table.agentName inList it) },
            )
        if (arms.isEmpty()) return Op.FALSE
        return table.id inSubQuery arms.drop(1).fold<Query, AbstractQuery<*>>(arms.first()) { acc, q -> acc.union(q) }
    }

    /**
     * Deletes settled rows created before [cutoff] in write transactions of at most [batchSize]
     * rows, so one sweep never holds the write lock long, behind an [anyDue] pre-check. Each batch
     * reads `id` plus [dependentColumns], and [deleteDependents] deletes the batch's dependent rows
     * before the rows themselves, in the same transaction. `created_at` is the anchor because a
     * failed or cancelled row carries no settle time, and no row settles before it is created; the
     * pre-check and each batch are served by `<table>_status_created`. Returns the number deleted.
     */
    suspend fun pruneSettled(
        cutoff: Long,
        batchSize: Int,
        dependentColumns: List<Column<*>>,
        deleteDependents: (List<ResultRow>) -> Unit,
    ): Int {
        if (!anyDue(settledBefore(cutoff))) return 0
        var pruned = 0
        while (true) {
            val deleted =
                db.query {
                    val batch =
                        table
                            .select(listOf(table.id) + dependentColumns)
                            .where { settledBefore(cutoff) }
                            .limit(batchSize)
                            .toList()
                    if (batch.isNotEmpty()) {
                        deleteDependents(batch)
                        val ids = batch.map { it[table.id] }
                        table.deleteWhere { table.id inList ids }
                    }
                    batch.size
                }
            pruned += deleted
            if (deleted < batchSize) return pruned
        }
    }

    private fun settledBefore(cutoff: Long): Op<Boolean> = (table.status inList settledStatuses) and (table.createdAt less cutoff)

    private companion object {
        // The two statuses every fired-record table names alike (DispatchStatus, ContinuationStatus).
        const val PENDING = "PENDING"
        const val DISPATCHING = "DISPATCHING"
    }
}
