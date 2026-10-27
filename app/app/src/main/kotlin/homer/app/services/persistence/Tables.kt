package homer.app.services.persistence

import homer.app.services.ContinuationKind
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull

/**
 * Durable schema for orchestrator state. See ADR-0020.
 *
 * The fields we filter/sort on (id, status, agent_name) are real columns; the
 * always-read-and-written-whole blobs (history, executions, runner state,
 * question options) are JSON-text columns serialized with [columnJson].
 * Cross-process queries stay plain SQL; per-process blobs avoid joins.
 *
 * Write-once `input_params` is the exception: it is bounded only by
 * `server.maxRequestBodyBytes` (10 MiB), so it lives in the [ProcessInputs] side
 * table instead of this row. SQLite rewrites a row's whole overflow chain on any
 * UPDATE that changes its size, so inline inputs would make every per-step
 * update of such a run rewrite megabytes. See ADR-0020.
 */
internal object Processes : Table("processes") {
    // INTEGER PRIMARY KEY AUTOINCREMENT: monotonic across restarts and never
    // reused, so a post-restart run always gets a strictly higher id than any
    // historical one. Replaces the old in-memory lastProcessId counter.
    val id = integer("id").autoIncrement()
    val status = varchar("status", 16)
    val agentName = varchar("agent_name", 255)
    val currentCommand = text("current_command").nullable()
    val currentCommandStart = long("current_command_start").nullable()
    val purgeAt = long("purge_at").nullable()

    // When the process ROW may be deleted, as opposed to purge_at, which is when
    // its workspace directory may be. Equal to purge_at unless the agent sets
    // workspace.retain.recordFor. See ADR-0048.
    val recordPurgeAt = long("record_purge_at").nullable()
    val costUsd = double("cost_usd").default(0.0)

    // Which pricing rung produced cost_usd: 'cli' | 'session' | 'mixed'. Set by addCost;
    // flips to 'mixed' once two charges disagree, and then stays. See ADR-0061.
    val costSource = varchar("cost_source", 16).default("cli")
    val artifactsDir = text("artifacts_dir").nullable()

    // input_params lived here until v25; it now lives in ProcessInputs. See ADR-0020.
    val history = text("history")
    val executions = text("executions")
    val runnerState = text("runner_state").nullable()
    val owner = text("owner").nullable()

    // W3C trace id of this run's root `agent_request` span, stamped at start.
    // Null when tracing produced no valid span. Outlives the artifacts, so a
    // run still links to its trace after the workspace is purged.
    val traceId = varchar("trace_id", 32).nullable()

    // Id of the run that caused this one; null at the root of a flow. Not a
    // foreign key: the parent row may be evicted or purged first, and the link
    // must keep reading as "caused by #N" after that. See ADR-0058.
    val parentProcessId = integer("parent_process_id").nullable()

    // Id of the first run of this run's flow, written once at INSERT as
    // `parent.rootProcessId ?: parent.id`; null at the root itself. Denormalized
    // so a whole flow is one paginated read instead of one request per parent
    // hop. Not a foreign key, for the same eviction/purge reason as
    // parentProcessId above. See ADR-0058.
    val rootProcessId = integer("root_process_id").nullable()

    // Wall-clock second the row was inserted. Written in the same transaction as
    // the INSERT, which is what makes it usable as the cooldown's "last run
    // started" — the first `history` entry is written later, asynchronously, so a
    // just-created row reads back with an empty history. See ADR-0051.
    // Nullable only for rows migrated in from before v13.
    val createdAt = long("created_at").nullable()

    // Wall-clock second the row reached a terminal status. Written by the same
    // UPDATE that sets that status, so `MAX(ended_at)` is an exact "when did this
    // agent last fail" — row id is INSERT order, and overlapping runs can finish
    // out of that order. Null while the run is active, and for rows migrated in
    // from before v14. See ADR-0051.
    val endedAt = long("ended_at").nullable()

    // envNames of the inputs this agent declared `secret: true` AT THE TIME OF THE
    // RUN, as a JSON array. API redaction reads this rather than the agent's
    // current definition: records now outlive their workspace (ADR-0048), and an
    // input later flipped to `secret: false` would otherwise expose the historical
    // value. Null for rows from before v15, which fall back to the current
    // definition. See ADR-0052.
    val secretInputKeys = text("secret_input_keys").nullable()

    // Artifact globs (JSON list) declared to survive the workspace purge,
    // captured at terminal status like record_purge_at. See ADR-0053.
    val retainArtifacts = text("retain_artifacts").nullable()

    // "Workspace sweep already ran" marker for rows that keep artifacts_dir set
    // because files were retained. Null artifacts_dir keeps doubling as the
    // marker for rows with nothing retained. See ADR-0053.
    val workspacePurgedAt = long("workspace_purged_at").nullable()

    // Whether the retained set was cut short by server.maxRetainedArtifactBytes.
    val retainedTruncated = bool("retained_truncated").nullable()

    // Why the run ended FAILED before any of its commands started (a claim timeout, a
    // SKIP_CURRENT lock skip, a parallel-run cap or a full queue), as a fixed-vocabulary
    // sentence built from typed fields — never a raw exception message, and redacted before
    // it is written. Written once, at the terminal transition, and only while the row still
    // reads FAILED with no step recorded, so a run that failed mid-flight keeps NULL.
    // See ADR-0020.
    val failureReason = text("failure_reason").nullable()

    // The run that blocked this one (the lock holder), when the cause names one. Not a
    // foreign key, like the other run links: the holder may be evicted first and the id must
    // keep reading after that. Served only to a caller who may see that run. See ADR-0058.
    val failureReasonRunId = integer("failure_reason_run_id").nullable()

    /**
     * The columns the record-level reads (the two purge sweeps and `costByTag`) use, and nothing else:
     * `history`, `executions` and `runner_state` are multi-MiB on a
     * large run and none of those paths serves them (the sweeps write nothing back either —
     * `record_purge_at` is set when the run ends, not at purge time). `toProcessInfo` reads
     * the omitted columns as null, which its non-null fields map to their defaults.
     */
    val recordReadColumns: List<Expression<*>> =
        listOf(
            id,
            status,
            agentName,
            currentCommand,
            currentCommandStart,
            purgeAt,
            recordPurgeAt,
            costUsd,
            costSource,
            artifactsDir,
            owner,
            traceId,
            parentProcessId,
            rootProcessId,
            secretInputKeys,
            retainArtifacts,
            workspacePurgedAt,
            retainedTruncated,
            failureReason,
            failureReasonRunId,
        )

    // One per list filter of GET /status/all, plus `owner` for the non-admin visibility OR, so
    // COUNT(*) behind `total` never scans the table. Named explicitly: migration v23 creates the same five.
    init {
        index("processes_agent_name", false, agentName)
        index("processes_status", false, status)
        index("processes_parent_process_id", false, parentProcessId)
        index("processes_root_process_id", false, rootProcessId)
        index("processes_owner", false, owner)
        // v24: the shared-workspace check before a purge or eviction deletes a directory.
        index("processes_artifacts_dir", false, artifactsDir)
        // v24: the two purge sweeps. A plain purge_at index would also walk every row the
        // workspace sweep already handled, since those keep their purge_at; the partial
        // index holds only rows still waiting. The legacy index covers rows from before v12,
        // which have no record_purge_at and fall back to purge_at.
        index("processes_workspace_purge", false, status, purgeAt, filterCondition = {
            artifactsDir.isNotNull() and workspacePurgedAt.isNull()
        })
        index("processes_record_purge", false, status, recordPurgeAt)
        index("processes_legacy_record_purge", false, status, purgeAt, filterCondition = { recordPurgeAt.isNull() })
        // v24: cooldown.afterFailure's latest-failure lookup, run under the write lock at run start.
        index("processes_agent_status_ended", false, agentName, status, endedAt)
    }

    override val primaryKey = PrimaryKey(id)
}

/**
 * A run's subject tags: `0..n` `key:value` strings describing what the run ran
 * over (`mr:15`, `repo:homer`), queried by `GET /status/all?tag=`. One row per
 * (process, tag). Two sources feed it: `tags.fromInputs` tags resolved at start
 * and written co-transactionally with the process INSERT (ADR-0051), and
 * `tags.runtime` tags declared later through the callback route. Rows are deleted
 * co-transactionally with the process row (record purge AND capacity eviction,
 * ADR-0048). Not a foreign key, matching `processes`' own no-FK links. Child runs
 * do not inherit parent tags. See ADR-0060.
 */
internal object ProcessTags : Table("process_tags") {
    val processId = integer("process_id")

    // The whole `key:value` tag as stored and matched. Filtering is exact-equality
    // on this column, so `(tag, process_id)` is the driving index: `?tag=mr:15`
    // seeks the tag then reads the matching process ids.
    val tag = text("tag")

    init {
        index(false, tag, processId)
    }

    override val primaryKey = PrimaryKey(processId, tag)
}

/**
 * A run's write-once `input_params` as JSON text, in its own row since schema v25
 * so per-step UPDATEs never rewrite it. Written once with the process INSERT and
 * never updated; not a foreign key, deleted co-transactionally with the process
 * row on both record purge and capacity eviction. See ADR-0020 for why.
 */
internal object ProcessInputs : Table("process_inputs") {
    val processId = integer("process_id")
    val inputParams = text("input_params")

    override val primaryKey = PrimaryKey(processId)
}

internal object Questions : Table("questions") {
    val id = varchar("id", 64)
    val processId = integer("process_id")
    val agentName = varchar("agent_name", 255)
    val text = text("text")

    // Named optionsJson because Exposed 1.3 added Table.options, which a
    // property named `options` would hide. The column name stays "options".
    val optionsJson = text("options")
    val status = varchar("status", 16)
    val answer = text("answer").nullable()
    val createdAt = long("created_at")
    val answeredAt = long("answered_at").nullable()

    // 1 only for questions created by ask-and-dispatch. The question-teardown
    // queries (expire/remove on the asker's lifecycle) skip these rows so a
    // dispatch outlives its asking process. See the ask-and-dispatch ADR.
    val dispatchBound = integer("dispatch_bound").default(0)

    // v24: the per-process open-question checks (cap, expiry and teardown at every run end,
    // under the write lock) and the open-question counts every process-list read carries.
    // v28: the read-grant visibility arm of the paged `GET /questions`.
    init {
        index("questions_status_dispatch_bound", false, status, dispatchBound, processId)
        index("questions_process_dispatch_bound", false, processId, dispatchBound)
        index("questions_agent_name", false, agentName)
    }

    override val primaryKey = PrimaryKey(id)
}

/**
 * The per-run inbox: operator messages a running run takes through a hook or the MCP tool. Not a
 * foreign key; deleted co-transactionally with the process row on both record purge and capacity
 * eviction. See ADR-0097.
 */
internal object RunMessages : Table("run_messages") {
    val id = varchar("id", 64)
    val processId = integer("process_id")
    val text = text("text")
    val status = varchar("status", 16)
    val sender = varchar("sender", 255).nullable()
    val createdAt = long("created_at")
    val deliveredAt = long("delivered_at").nullable()

    init {
        index("run_messages_process_status", false, processId, status)
    }

    override val primaryKey = PrimaryKey(id)
}

internal object Dispatches : Table("dispatches"), FiredRecordTable {
    override val id = integer("id").autoIncrement()
    val questionId = varchar("question_id", 64).uniqueIndex()
    override val status = varchar("status", 16)
    override val agentName = varchar("agent_name", 255)
    val answerParam = varchar("answer_param", 255)
    val params = text("params")
    val originProcessId = integer("origin_process_id")
    val originAgentName = varchar("origin_agent_name", 255)
    override val expiresAt = long("expires_at").nullable()
    override val createdAt = long("created_at")
    val dispatchedAt = long("dispatched_at").nullable()
    val dispatchedProcessId = integer("dispatched_process_id").nullable()
    val error = text("error").nullable()
    override val owner = text("owner").nullable()

    // envNames the TARGET agent declared `secret: true` when this row was registered,
    // as a JSON array. Read at fire time so a definition edited in between cannot turn
    // a stored value into a cleartext trace tag or an unredacted API field. Null for
    // rows from before v16. See ADR-0052.
    val secretInputKeys = text("secret_input_keys").nullable()

    // paramNames the TARGET agent declared `secret: true` when this row was registered,
    // as a JSON array. `params` above is stored keyed by paramName, so this is what
    // `redactStoredParams` matches against directly — the envName snapshot alone cannot
    // survive the input being deleted or re-pointed to a different envName since
    // registration. Null for rows from before v17. See ADR-0052.
    val secretParamNames = text("secret_param_names").nullable()

    // v24: the PENDING cap, the expiry sweep and the DISPATCHING recovery at startup.
    // v26: the settled-row prune, and the list's status, agentName and visibility filters.
    init {
        index("dispatches_status_expires", false, status, expiresAt)
        index("dispatches_status_created", false, status, createdAt)
        index("dispatches_agent_name", false, agentName)
        index("dispatches_owner", false, owner)
    }

    override val primaryKey = PrimaryKey(id)
}

internal object ResultTokens : Table("result_tokens") {
    // Capability token scoping a read-by-reference fetch to one (watchedProcessId,
    // artifact path). Reusable within its TTL (a resumed graph re-runs the node and
    // re-fetches), independent of the continuation row's lifetime. See ADR-0025.
    val token = varchar("token", 64)
    val watchedProcessId = integer("watched_process_id")
    val path = text("path")

    // JSON-encoded ResultEnvParam applied to the artifact at fetch time; null = stream raw.
    val resultTransformer = text("result_transformer").nullable()
    val sizeBytes = long("size_bytes")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").nullable()

    // Agent the token is minted for (the continuation's target). The fetch endpoint
    // redeems it only for a caller running this agent (audience binding). See ADR-0025.
    val consumerAgent = varchar("consumer_agent", 255)

    override val primaryKey = PrimaryKey(token)
}

internal object Continuations : Table("continuations"), FiredRecordTable {
    override val id = integer("id").autoIncrement()
    val watchedProcessId = integer("watched_process_id")
    override val status = varchar("status", 16)
    override val agentName = varchar("agent_name", 255)
    val resumeParam = varchar("resume_param", 255)
    val params = text("params")

    // JSON-encoded ResultEnvParam used to extract a value from the watched
    // process's stdout into the resume payload; null = pass the raw result.
    val resultTransformer = text("result_transformer").nullable()

    // envNames the TARGET agent declared `secret: true` when this row was registered,
    // as a JSON array. Read at fire time so a definition edited in between cannot turn
    // a stored value into a cleartext trace tag or an unredacted API field. Null for
    // rows from before v16. See ADR-0052.
    val secretInputKeys = text("secret_input_keys").nullable()

    // paramNames the TARGET agent declared `secret: true` when this row was registered,
    // as a JSON array. `params` above is stored keyed by paramName, so this is what
    // `redactStoredParams` matches against directly — the envName snapshot alone cannot
    // survive the input being deleted or re-pointed to a different envName since
    // registration. Null for rows from before v17. See ADR-0052.
    val secretParamNames = text("secret_param_names").nullable()
    val originProcessId = integer("origin_process_id")
    val originAgentName = varchar("origin_agent_name", 255)
    override val expiresAt = long("expires_at").nullable()
    override val createdAt = long("created_at")
    val firedAt = long("fired_at").nullable()
    val firedProcessId = integer("fired_process_id").nullable()
    val error = text("error").nullable()

    // SINGLE (one watched process, today's path) or JOIN (a fan-out group resumed
    // once after the last of N watched processes ends). JOIN rows set
    // watched_process_id = 0 (sentinel) and carry their watch set in
    // continuation_watches. See ADR-0026.
    val kind = varchar("kind", 16).default(ContinuationKind.SINGLE.name)
    override val owner = text("owner").nullable()

    // Transient-failure retry (ADR-0023): a fire that failed for a reason that may
    // clear on its own (capacity, cost cap) returns the row to PENDING with the
    // attempt counted and the next attempt scheduled, instead of ending as FAILED.
    // Null next_attempt_at = never retried; the sweep only picks up non-null, due rows.
    val fireAttempts = integer("fire_attempts").default(0)
    val nextAttemptAt = long("next_attempt_at").nullable()

    // v27: `params[HMR_LG_THREAD]` copied out at INSERT so a LangGraph run's `subs` reads its
    // thread's rows by index. Agent-supplied: only ever compared for equality with a bound
    // parameter. Null when the row belongs to no LangGraph thread.
    val threadId = text("thread_id").nullable()

    // v24: the watched-process lookup on every terminal transition, the PENDING cap, and the
    // expiry and retry sweeps.
    // v26: the settled-row prune, and the list's status, agentName and visibility filters.
    // v27: the LangGraph `subs` thread lookup.
    init {
        index("continuations_watched_status", false, watchedProcessId, status)
        index("continuations_status_expires", false, status, expiresAt)
        index("continuations_status_created", false, status, createdAt)
        index("continuations_agent_name", false, agentName)
        index("continuations_owner", false, owner)
        index("continuations_thread_id", false, threadId)
    }

    override val primaryKey = PrimaryKey(id)
}

/**
 * The watch set of a [ContinuationKind.JOIN] continuation: one row per watched
 * sub-process, carrying the LangGraph interrupt id its result is keyed under in the
 * resume map and the per-sub result transformer. SINGLE continuations have no rows
 * here (they use `continuations.watched_process_id`). See ADR-0026.
 */
internal object ContinuationWatches : Table("continuation_watches") {
    val continuationId = integer("continuation_id").index()
    val watchedProcessId = integer("watched_process_id").index()
    val interruptId = varchar("interrupt_id", 255)

    // JSON-encoded ResultEnvParam applied to this sub's stdout; null = raw result.
    val resultTransformer = text("result_transformer").nullable()

    override val primaryKey = PrimaryKey(continuationId, watchedProcessId)
}

/**
 * Append-only Claude spend ledger: one row per accounted Claude attempt (success,
 * failure, or shell-aborted), not only successful commands. Backs the cost-cap
 * windows and the monthly / all-time cost statistics. Rows are never pruned —
 * all-time totals depend on them. Months are bucketed in UTC. See ADR-0035 and
 * ADR-0061.
 */
internal object CostEntries : Table("cost_entries") {
    val id = long("id").autoIncrement()
    val agentName = varchar("agent_name", 255)
    val timestampSec = long("timestamp_sec")
    val costUsd = double("cost_usd")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, agentName, timestampSec)
        index(false, timestampSec)
    }
}

/**
 * Small key/value store for durable server-instance metadata. Currently holds
 * exactly one row — the stable per-instance id ([ServerMetaRepository]) that
 * scopes the Kubernetes startup sweep so a fresh instance boot cannot delete
 * another instance's pods in a shared namespace. Persisted (not the per-boot
 * `HMR_SERVER_ID`) so the sweep still reaps this instance's own orphans across
 * a restart.
 */
internal object ServerMeta : Table("server_meta") {
    val key = varchar("key", 64)
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}
