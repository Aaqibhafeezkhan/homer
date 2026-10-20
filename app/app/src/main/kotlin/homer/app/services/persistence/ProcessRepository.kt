package homer.app.services.persistence

import homer.app.infra.ProcessJobRegistry
import homer.app.infra.PurgeSchedule
import homer.app.models.Cooldown
import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.CooldownDecision
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Hard row cap the tag-screened filter path fetches before screening: a `?tag=`
 * read is bounded by this, not only by the caller's `limit`, so a visible-matching
 * set cannot be materialized wholesale (CWE-400). See ADR-0060.
 */
internal const val MAX_TAG_SCREENED_ROWS = 10_000

// Ids per chunked `IN` read. SQLITE_LIMIT_VARIABLE_NUMBER default is 999; stay well
// under even if the build is linked against an older SQLite.
internal const val SQLITE_PARAM_CHUNK = 512

/**
 * All durable access to process state. SQLite is authoritative — every read and
 * write is a transaction (no in-memory cache). The live coroutine [job] is the
 * one piece of [ProcessInfo] that cannot be persisted; it is sourced from
 * [jobRegistry] when mapping rows. See ADR-0020.
 *
 * Read-modify-write operations (history append, executions append, runner-state
 * merge) run inside a single `BEGIN IMMEDIATE` transaction (see
 * [DatabaseService]), so concurrent writers serialize through the write lock
 * rather than losing updates — this is what replaced the old `Mutex`.
 */
class ProcessRepository(
    internal val db: DatabaseService,
    internal val jobRegistry: ProcessJobRegistry,
) {
    /** Outcome of [obtainProcessId]: the new id plus any terminal row evicted to make room. */
    data class ObtainResult(
        val processId: Int,
        val evicted: EvictedProcess?,
    )

    /** The terminal row evicted to make room, with enough detail for the caller to log it and purge its workspace. */
    data class EvictedProcess(
        val id: Int,
        val agentName: String,
        val status: ProcessStatus,
        val artifactsDir: java.nio.file.Path?,
    )

    /** Outcome of a guarded status write: whether it applied, and the row's agent name. */
    data class TransitionResult(
        val updated: Boolean,
        val agentName: String?,
    )

    /**
     * Outcome of a run-time tag write: whether the tag is now present ([written]),
     * and the run's runtime-tag count after the attempt. [written] is false only when
     * the per-run ceiling was already reached; an exact-duplicate write is [written]
     * with the count unchanged. [count] excludes `fromInputs` tags — the ceiling bounds
     * runtime-declared tags only. See ADR-0060.
     */
    data class RuntimeTagResult(
        val written: Boolean,
        val count: Int,
    )

    /**
     * Inserts a new CREATED row and returns its id, enforcing capacity and — when
     * [cooldown] is set — the agent's frequency bound, both inside the same
     * `BEGIN IMMEDIATE` transaction as the INSERT.
     *
     * The cooldown check belongs *here*, not only in the caller: a check that
     * happens before the transaction lets two concurrent starts both observe the
     * same previous run and both insert. Writers serialize through this
     * transaction, so evaluating the windows against rows read inside it is what
     * actually makes the bound hold. See ADR-0051.
     *
     * Replay-unsafe (a re-run takes a fresh AUTOINCREMENT id: a second process
     * row and tag set); safe only because the writer handle is single-attempt.
     * See ADR-0020.
     */
    suspend fun obtainProcessId(
        agentName: String,
        inputParams: Map<String, String>,
        maxProcesses: Int,
        owner: String? = null,
        cooldown: Cooldown? = null,
        secretInputKeys: Set<String>? = null,
        parentProcessId: Int? = null,
        rootProcessId: Int? = null,
        tags: List<String> = emptyList(),
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): ObtainResult =
        db.query {
            cooldown?.let { enforceCooldownTx(agentName, it, nowSec) }
            val evicted = evictForCapacityTx(maxProcesses)
            val newId =
                Processes.insert {
                    it[status] = ProcessStatus.CREATED.name
                    it[Processes.agentName] = agentName
                    it[history] = EMPTY_HISTORY_JSON
                    it[executions] = EMPTY_EXECUTIONS_JSON
                    it[costUsd] = 0.0
                    it[costSource] = CostSource.CLI.wire
                    it[Processes.owner] = owner
                    // Same transaction as the INSERT: this is the timestamp the cooldown
                    // reads, and `history` does not get its first entry until the launched
                    // coroutine writes one. See ADR-0051.
                    it[createdAt] = nowSec
                    // Captured now, not looked up later: the agent's `secret` flags can
                    // change while this record is retained. Null when the caller did not
                    // capture a snapshot, which reads back as "ask the current definition"
                    // — an empty set means "nothing was secret". See ADR-0052.
                    it[Processes.secretInputKeys] = secretInputKeys?.let { keys -> encodeKeys(keys) }
                    it[Processes.parentProcessId] = parentProcessId
                    it[Processes.rootProcessId] = rootProcessId
                }[Processes.id]
            // Write-once inputs live in their own table so the per-step UPDATEs below
            // never rewrite them. Same transaction as the INSERT, so a run is never
            // readable with its inputs missing. See ADR-0020.
            ProcessInputs.insert {
                it[ProcessInputs.processId] = newId
                it[ProcessInputs.inputParams] = encodeParams(inputParams)
            }
            // fromInputs tags written in the INSERT's own transaction, so the run is
            // findable by `?tag=` the instant its row exists. De-duplicated: the tag set
            // is a set, and (process_id, tag) is the PK. See ADR-0051 and ADR-0060.
            ProcessTags.batchInsert(tags.distinct(), shouldReturnGeneratedValues = false) { tag ->
                this[ProcessTags.processId] = newId
                this[ProcessTags.tag] = tag
            }
            ObtainResult(newId, evicted)
        }

    /**
     * Throws the [CooldownDecision] refusal when [cooldown] bars a start of [agentName] at
     * [nowSec], evaluated against rows read in the caller's transaction. Each window's lookup
     * runs only when that window is configured. See ADR-0051.
     */
    private fun enforceCooldownTx(
        agentName: String,
        cooldown: Cooldown,
        nowSec: Long,
    ) {
        CooldownDecision
            .evaluate(
                agentName = agentName,
                cooldown = cooldown,
                nowSec = nowSec,
                latestRunStartedAt = cooldown.minIntervalSeconds?.let { latestRunStartedAtTx(agentName) },
                latestFailureEndedAt = cooldown.afterFailureSeconds?.let { latestFailureEndedAtTx(agentName) },
            )?.let { throw it }
    }

    suspend fun getStatus(processId: Int): ProcessStatus =
        db.read {
            Processes
                .select(Processes.status)
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.let { ProcessStatus.valueOf(it[Processes.status]) }
                ?: ProcessStatus.UNKNOWN
        }

    /**
     * Appends [newStatus] to history and writes it when the row exists and
     * [guard] accepts its current status (null guard = unconditional). The
     * status read, guard check, and write are one atomic transaction, which is
     * what enforces the KILLED-precedence invariant (audit #9) at the DB level.
     */
    suspend fun transitionStatus(
        processId: Int,
        newStatus: ProcessStatus,
        nowSec: Long,
        guard: Set<ProcessStatus>?,
    ): TransitionResult =
        db.query {
            // Projected, not selectAll(): this runs under the write lock, and reading the
            // history/executions blobs of the row just to write them back is pure cost.
            // See ADR-0020 (2026-09-29 amendment).
            val row =
                Processes
                    .select(Processes.status, Processes.history, Processes.agentName)
                    .where { Processes.id eq processId }
                    .singleOrNull()
                    ?: return@query TransitionResult(updated = false, agentName = null)
            val current = ProcessStatus.valueOf(row[Processes.status])
            if (guard != null && current !in guard) {
                return@query TransitionResult(updated = false, agentName = row[Processes.agentName])
            }
            val newHistory = decodeHistory(row[Processes.history]) + (nowSec to newStatus)
            Processes.update({ Processes.id eq processId }) {
                it[status] = newStatus.name
                it[history] = encodeHistory(newHistory)
                // Same UPDATE that sets the status, so the terminal time can never
                // disagree with it. See ADR-0051.
                if (newStatus.name in TERMINAL_STATUS_NAMES) it[endedAt] = nowSec
            }
            TransitionResult(updated = true, agentName = row[Processes.agentName])
        }

    /**
     * Transitions a CREATED/WORKING process to KILLED, clearing the current
     * command. Returns the agent name when it actually transitioned (so the
     * caller can cancel the job and broadcast), or null otherwise.
     */
    suspend fun killTransition(
        processId: Int,
        nowSec: Long,
    ): String? =
        db.query {
            // Projected like transitionStatus: status for the guard, history to append to,
            // agent_name for the caller's broadcast. See ADR-0020 (2026-09-29 amendment).
            val row =
                Processes
                    .select(Processes.status, Processes.history, Processes.agentName)
                    .where { Processes.id eq processId }
                    .singleOrNull()
                    ?: return@query null
            val current = ProcessStatus.valueOf(row[Processes.status])
            if (current != ProcessStatus.WORKING && current != ProcessStatus.CREATED) return@query null
            val newHistory = decodeHistory(row[Processes.history]) + (nowSec to ProcessStatus.KILLED)
            Processes.update({ Processes.id eq processId }) {
                it[status] = ProcessStatus.KILLED.name
                it[history] = encodeHistory(newHistory)
                it[currentCommand] = null
                it[currentCommandStart] = null
                it[endedAt] = nowSec
            }
            row[Processes.agentName]
        }

    /**
     * Insert time of the most recent run of [agentName], or null when it has never run —
     * or when every past run has been purged, which resets the cooldown.
     * Ordered by id: AUTOINCREMENT ids are monotonic and never reused.
     *
     * Reads `created_at`, **not** the first `history` entry. History is written by the
     * launched coroutine, so between the INSERT and that first write the newest row
     * reads back empty — and since this query takes the newest row only, it would
     * return null and skip the whole `minInterval` check. `created_at` is written in
     * the INSERT's own transaction and has no such window. See ADR-0051.
     * Null for rows migrated in from before v13, which predate the cooldown feature.
     */
    suspend fun latestRunStartedAt(agentName: String): Long? = db.read { latestRunStartedAtTx(agentName) }

    /** Terminal time (last history entry) of the most recent FAILED run of [agentName], or null. */
    suspend fun latestFailureEndedAt(agentName: String): Long? = db.read { latestFailureEndedAtTx(agentName) }

    /**
     * Nulls a row's `ended_at`, reproducing a row migrated in from before v14.
     * Test-only: nothing in production ever clears a recorded terminal time.
     */
    internal suspend fun clearEndedAtForTest(processId: Int) {
        db.query { Processes.update({ Processes.id eq processId }) { it[endedAt] = null } }
    }

    // Transaction-local forms of the two reads above, so the authoritative cooldown
    // check in obtainProcessId can run inside the INSERT's transaction. `db.query`
    // opens its own, and nesting one inside another would deadlock the writer.
    private fun latestRunStartedAtTx(agentName: String): Long? =
        Processes
            .select(Processes.createdAt)
            .where { Processes.agentName eq agentName }
            .orderBy(Processes.id, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(Processes.createdAt)

    // MAX over ended_at, not the newest row's history: `id` is INSERT order, and
    // same-agent runs overlap by default (the default lockStrategy.key is per
    // process and maxParallelRuns is 10), so an older run can fail AFTER a newer
    // one. Ordering by id then picked the newer row's EARLIER failure and let a
    // start through inside the afterFailure window. Rows from before v14 have a
    // null ended_at and are skipped by MAX. See ADR-0051.
    private fun latestFailureEndedAtTx(agentName: String): Long? =
        Processes
            .select(Processes.endedAt)
            .where { (Processes.agentName eq agentName) and (Processes.status eq ProcessStatus.FAILED.name) }
            // Ordered by ended_at, not id. SQLite sorts NULLs last under DESC, so a
            // pre-v14 row without a recorded terminal time never masks a real one.
            .orderBy(Processes.endedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(Processes.endedAt)

    suspend fun getHistory(processId: Int): List<Pair<Long, ProcessStatus>> =
        db.read {
            Processes
                .select(Processes.history)
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.let { decodeHistory(it[Processes.history]) }
                ?: emptyList()
        }

    suspend fun getExecutions(processId: Int): List<ExecutionInfo> =
        db.read {
            Processes
                .select(Processes.executions)
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.let { decodeExecutions(it[Processes.executions]) }
                ?: emptyList()
        }

    suspend fun getInfo(processId: Int): ProcessInfo? =
        db.read {
            Processes
                .selectAll()
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.toProcessInfo(jobRegistry, tagsForTx(processId), inputsForTx(processId))
        }

    /** Highest id ever allocated (AUTOINCREMENT high-water), or -1 if none. */
    suspend fun lastProcessId(): Int =
        db.read {
            exec("SELECT seq FROM sqlite_sequence WHERE name = 'processes'") { rs ->
                if (rs.next()) rs.getInt(1) else -1
            } ?: -1
        }

    /**
     * Marks every still-active (CREATED/WORKING) process as UNKNOWN with a
     * history entry and returns their ids. Called once on boot — the coroutines
     * that drove them are gone. See `StartupReconciliation`.
     */
    suspend fun markInterruptedAsUnknown(nowSec: Long): List<Int> =
        db.query {
            val ids =
                Processes
                    .select(Processes.id)
                    .where { Processes.status inList ACTIVE_STATUS_NAMES }
                    .map { it[Processes.id] }
            if (ids.isNotEmpty()) {
                // One bulk UPDATE regardless of row count: SQLite's json_insert
                // appends the (nowSec, UNKNOWN) entry to each row's history array
                // (`$[#]` = append). The element is encoded by the same serializer
                // decodeHistory reads, so it round-trips. Avoids an UPDATE per row.
                val element = encodeHistoryElement(nowSec to ProcessStatus.UNKNOWN)
                exec(
                    "UPDATE processes SET status = 'UNKNOWN', " +
                        "history = json_insert(history, '\$[#]', json(?)), " +
                        "current_command = NULL, current_command_start = NULL, " +
                        "ended_at = ? " +
                        "WHERE status IN ('CREATED', 'WORKING')",
                    listOf(TextColumnType() to element, LongColumnType() to nowSec),
                )
            }
            ids
        }

    /**
     * Stamps `purge_at` / `record_purge_at` / `retain_artifacts` on every UNKNOWN row that
     * has none, counted from [nowSec], and returns how many it stamped. A run's own
     * coroutine stamps these when it ends; a run a restart left UNKNOWN never got there,
     * and both purge sweeps skip a null `purge_at` — so without this its directory and
     * record lived until capacity eviction. Rows already stamped are left alone, so a
     * repeat boot is a no-op. Startup only: one read on `processes_status`, one UPDATE
     * per id chunk on the primary key. See ADR-0048 (2026-10-01 amendment).
     */
    suspend fun stampUnknownPurgeTimes(
        nowSec: Long,
        scheduleFor: (agentName: String) -> PurgeSchedule,
    ): Int =
        db.query {
            val unstamped =
                Processes
                    .select(Processes.id, Processes.agentName)
                    .where { (Processes.status eq ProcessStatus.UNKNOWN.name) and Processes.purgeAt.isNull() }
                    .map { it[Processes.id] to it[Processes.agentName] }
            val schedules = unstamped.map { it.second }.distinct().associateWith(scheduleFor)
            for ((schedule, rows) in unstamped.groupBy { schedules.getValue(it.second) }) {
                val retain = schedule.retainArtifacts.takeIf { it.isNotEmpty() }?.let { encodeRetainArtifacts(it) }
                for (chunk in rows.map { it.first }.chunked(SQLITE_PARAM_CHUNK)) {
                    Processes.update({ Processes.id inList chunk }) {
                        it[Processes.purgeAt] = nowSec + schedule.purgeAfterSeconds
                        it[Processes.recordPurgeAt] = nowSec + schedule.purgeRecordAfterSeconds
                        it[Processes.retainArtifacts] = retain
                    }
                }
            }
            unstamped.size
        }
}
