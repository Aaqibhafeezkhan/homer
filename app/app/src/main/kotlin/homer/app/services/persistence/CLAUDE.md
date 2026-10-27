# persistence/CLAUDE.md

Dev guide for the persistence layer (`homer.app.services.persistence`).
Project-wide rules live in the root [`CLAUDE.md`](../../../../../../../../../CLAUDE.md) and apply here
too; the cross-cutting backend guide is [`app/CLAUDE.md`](../../../../../../../../CLAUDE.md). This
file is canonical for the persistence model — read vs write handles, the
reader connection cache, concurrency, planner statistics and the
schema-version history (root §9).

The **SQLite rules for every change that touches the database** are
cross-cutting — a service that merely *calls* the database must see them
without opening this package — so they stay in [`app/CLAUDE.md`](../../../../../../../../CLAUDE.md)
§ SQLite rules. Read them before making any change described here.

## Persistence layer

Durable orchestrator state lives in SQLite (`services/persistence/`).
**SQLite is authoritative — there is no in-memory cache of durable state.**
See ADR-0020.

- **`DatabaseService`** — owns the Exposed `Database`. Opens/creates the file
  at `databasePath`, sets pragmas on every writer connection
  (`journal_mode=WAL`, `busy_timeout=5000`, `transaction_mode=IMMEDIATE`),
  creates tables via `SchemaUtils.create`, and
  tracks `PRAGMA user_version` for future migrations. **No table declares a
  foreign key and no `foreign_keys` pragma is set**: referential integrity is
  maintained in code — co-transactional deletes, existence-guarded inserts, and
  plain-reference ids that may outlive their parent row (ADR-0048, ADR-0058,
  ADR-0060). `query { }` runs a blocking JDBC transaction on `Dispatchers.IO`
  (overridable in tests) and is built **single-attempt**
  (`DatabaseConfig { defaultMaxAttempts = 1 }`): a commit that throws can never
  replay a write block, so do not add a retry of one without making it
  idempotent first (SQLite rule 5 in
  [`app/CLAUDE.md`](../../../../../../../../CLAUDE.md) § SQLite rules). `read { }`
  runs one on a second handle whose transactions begin DEFERRED under `PRAGMA
  query_only = ON`, on connections `IdleConnectionCache` keeps open between
  transactions (up to four; more open on demand, nobody waits), and which keeps
  Exposed's default retries, safe because a read block is re-runnable. Writer
  connections are never kept: sqlite-jdbc re-begins the IMMEDIATE transaction
  after every commit, so an open writer holds the write lock. `close()` then
  checkpoints the WAL (`PRAGMA wal_checkpoint(TRUNCATE)`) on a short-lived
  autocommit connection — never inside `transaction(database)`, which
  `transaction_mode=IMMEDIATE` puts inside an open transaction, where SQLite
  refuses a checkpoint with `SQLITE_LOCKED`. See ADR-0020 (2026-09-29
  amendments).
- **`ProcessRepository` / `QuestionsRepository` / `DispatchRepository` /
  `ContinuationRepository` / `CostRepository`** — all access via
  `DatabaseService.query` (writes) or `DatabaseService.read` (transactions
  that only read). Map rows ↔ `ProcessInfo` / `Question` / `Dispatch` /
  `Continuation` at this boundary; the `ProcessInfo.job` comes from
  `ProcessJobRegistry`. `ProcessRepository`'s members are the run lifecycle
  (INSERT with cooldown and capacity eviction, status transitions, boot
  reconciliation, single-row reads); the rest are extension functions on it by
  concern — `ProcessQueries.kt` (the list page, cost by tag, batched id reads),
  `ProcessRetention.kt` (purge times, both sweeps, the shared-dir guard, record
  purge, eviction), `ProcessStepRecords.kt` (step boundaries, runner state,
  cost), `ProcessRunTags.kt` (run-time tags) — over the row mapping and column
  codecs in `ProcessRows.kt`. The part of `ProcessManager`'s API that emits no
  event is these records as stored, exposed by interface delegation from here:
  `ProcessRecordReads`, `ProcessRetentionRecords`, `ProcessRunRecords`.
  `DispatchRepository` owns the `dispatches` table
  (schema v2) and enforces the `dispatch_bound` caveat: question teardown
  paths (`expireForProcess`, `removeForProcess`, startup reconciliation) skip
  dispatch-bound questions (`dispatch_bound = 1`); they live until the
  dispatch lifecycle settles them (answer → fire, cancel, or TTL sweep), or
  until the settled-row prune deletes their settled dispatch once its
  `createdAt` passes `server.dispatch.settledRetentionSec` (schema v26).
  `ContinuationRepository` owns the `continuations` table (schema v3).
  The two tables share the claim-CAS / fire / sweep columns (`FiredRecordTable`),
  and `FiredRecords` holds the statements both repositories run on them: the
  PENDING cap, the claim, the expiry predicate, the list page and the settled
  prune.
  `ResultTokenRepository` owns the `result_tokens` table (schema v4;
  `consumer_agent` audience column v7). See ADR-0025.
  `CostRepository` owns the append-only `cost_entries` LLM-spend ledger
  (schema v6) backing `CostMeter`'s cap windows and the cost statistics;
  rows are never pruned. See ADR-0035. `ServerMetaRepository` owns the
  `server_meta` key/value table (schema v9), which holds two keys: the stable
  per-instance id (`getOrCreateInstanceId`, created on first boot) that scopes
  the Kubernetes startup sweep to this instance's own pods — read once,
  blocking, while the runner registry is built — and `maintenance_mode`
  (ADR-0092), read blocking at boot and **written** on the live
  `POST /api/v1/maintenance` path through `DatabaseService.query { }`; the live
  *read* is the service's in-memory flag, so the console's health poll costs no
  database work. A key/value row needs no DDL, so neither key has a
  schema-version row of its own; `writeMaintenanceMode` upserts (UPDATE, then
  INSERT when it matched no row) rather than assuming the boot read created it.
- **`ProcessJobRegistry`** — `ConcurrentHashMap<Int, Job>` of live cancellation
  handles; the one piece of process state that can't be persisted. Empty on
  boot (the coroutines it referred to are gone).
- **`StartupReconciliation`** — runs once before traffic: marks interrupted
  `CREATED`/`WORKING` processes `UNKNOWN`, expires their OPEN non-dispatch-bound
  questions, stamps purge times on every `UNKNOWN` row still missing them (from
  the agent's current `workspace` config, counted from now; ADR-0048), fails
  any dispatch left `DISPATCHING` by the restart (so a crash mid-fire can't
  cause a duplicate re-fire), then re-fires any PENDING dispatch
  whose question is already ANSWERED (crash-safety for ask-and-dispatch). Same
  two-step crash-safety for continuations: fail any left `DISPATCHING`, then
  fire any PENDING continuation whose watched process is already terminal.

### Planner statistics

`DatabaseService.optimize()` runs `PRAGMA optimize=0x10002` after `migrate()` at
startup, before traffic, and hourly from `startMaintenance`. It analyzes every
table that has no statistics or has changed size a lot since its last analysis,
and is a no-op otherwise. The `0x10000` bit is load-bearing: a plain `PRAGMA
optimize` only considers tables the same connection has queried, and each
transaction opens a new connection. The first run on a full `maxProcesses` table
takes about 0.3 s under the write lock. `DatabaseMigrationTest` checks that no
process query falls back to a table scan once statistics exist.

## Schema versions

`SCHEMA_VERSION` in
[`DatabaseService.kt`](DatabaseService.kt) is the single source for the
current version; `DatabaseService.migrate()` upgrades in place, running the
per-version steps in [`SchemaMigrations.kt`](SchemaMigrations.kt), and stamps
`PRAGMA user_version`. One row per version — a new migration adds its step
there and a row here (SQLite rule 1 in
[`app/CLAUDE.md`](../../../../../../../../CLAUDE.md) § SQLite rules).

| v | What changed | ADR |
| --- | --- | --- |
| 1 | `questions` table | — |
| 2 | `dispatches` table; `questions.dispatch_bound` | [0021](../../../../../../../../../docs/adr/0021-ask-and-dispatch.md) |
| 3 | `continuations` table | [0023](../../../../../../../../../docs/adr/0023-completion-continuations.md) |
| 4 | `result_tokens` table | [0025](../../../../../../../../../docs/adr/0025-capability-token-result-read.md) |
| 5 | `continuation_watches` table; `continuations.kind` | [0026](../../../../../../../../../docs/adr/0026-join-continuations.md) |
| 6 | `cost_entries` table | [0035](../../../../../../../../../docs/adr/0035-sqlite-backed-cost-ledger.md) |
| 7 | `result_tokens.consumer_agent` | [0025](../../../../../../../../../docs/adr/0025-capability-token-result-read.md) |
| 8 | `owner` on `processes`/`dispatches`/`continuations` | [0038](../../../../../../../../../docs/adr/0038-process-ownership.md) |
| 9 | `server_meta` table (stable instance id) | — |
| 10 | `continuations.fire_attempts` / `next_attempt_at` | [0023](../../../../../../../../../docs/adr/0023-completion-continuations.md) |
| 11 | `processes.trace_id` | — |
| 12 | `processes.record_purge_at` | [0048](../../../../../../../../../docs/adr/0048-split-record-retention-from-workspace-purge.md) |
| 13 | `processes.created_at`, backfilled from history | [0051](../../../../../../../../../docs/adr/0051-cooldown-is-enforced-in-the-insert-transaction.md) |
| 14 | `processes.ended_at`, backfilled for terminal rows | [0051](../../../../../../../../../docs/adr/0051-cooldown-is-enforced-in-the-insert-transaction.md) |
| 15 | `processes.secret_input_keys` | [0052](../../../../../../../../../docs/adr/0052-redaction-metadata-is-captured-with-the-run.md) |
| 16 | `secret_input_keys` on `dispatches`/`continuations` | [0052](../../../../../../../../../docs/adr/0052-redaction-metadata-is-captured-with-the-run.md) |
| 17 | `secret_param_names` on `dispatches`/`continuations` | [0052](../../../../../../../../../docs/adr/0052-redaction-metadata-is-captured-with-the-run.md) |
| 18 | `processes.retain_artifacts`, `workspace_purged_at`, `retained_truncated` | [0053](../../../../../../../../../docs/adr/0053-declared-artifacts-ride-the-record-lifetime.md) |
| 19 | `processes.parent_process_id` | [0058](../../../../../../../../../docs/adr/0058-parent-child-run-linkage.md) |
| 20 | `process_tags` table | [0060](../../../../../../../../../docs/adr/0060-run-tags.md) |
| 21 | `processes.cost_source` | [0061](../../../../../../../../../docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md) |
| 22 | `processes.root_process_id` (left NULL, no backfill) | [0058](../../../../../../../../../docs/adr/0058-parent-child-run-linkage.md) |
| 23 | indexes for the process-list filters | [0020](../../../../../../../../../docs/adr/0020-durable-state-in-sqlite.md) |
| 24 | indexes for the sweep, cooldown and lookup filters | — |
| 25 | write-once `input_params` moved to the `process_inputs` side table | [0020](../../../../../../../../../docs/adr/0020-durable-state-in-sqlite.md) |
| 26 | prune indexes + paginated list filters for `dispatches`/`continuations` | [0021](../../../../../../../../../docs/adr/0021-ask-and-dispatch.md) |
| 27 | `continuations.thread_id` + `continuations_thread_id` index (LangGraph `subs` thread lookup) | [0044](../../../../../../../../../docs/adr/0044-langgraph-resume-and-run-introspection.md) |
| 28 | `questions_agent_name` index (the read-grant arm of the paged `GET /questions`) | — |
| 29 | `run_messages` table (the per-run inbox) + `run_messages_process_status` index | [0097](../../../../../../../../../docs/adr/0097-per-run-inbox.md) |

### Schema constraints

Editor-facing constraints the row's ADR does not state:

- `processes.created_at` / `ended_at` (v13/v14) exist so the `cooldown` bound
  has a start and an end timestamp valid the instant the row is. The cooldown
  is evaluated **inside** `obtainProcessId`'s transaction, atomic with the
  INSERT; `CooldownGate` in the service layer is a pre-flight refusal only and
  cannot hold the bound on its own. Both share `CooldownDecision` so they cannot
  drift. Row `id` is INSERT order and same-agent runs overlap by default, so an
  older run can fail after a newer one — ordering by id returned the wrong
  failure.
- `processes.secret_input_keys` (v15) records the `envName`s the agent declared
  `secret: true` **at run time**; API redaction reads it in preference to the
  agent's current definition, so an input later flipped to `secret: false`
  cannot expose the historical value from a retained record. Three states: a set
  (captured), an empty set (nothing was secret), null (no snapshot — fall back
  to the current definition, for rows predating v15).
- `processes.retain_artifacts` (v18) is captured at terminal status next to
  `purge_at` — same run-time-capture principle as v15 (a restart's `UNKNOWN`
  rows, which never ended, take it from the agent at reconciliation; ADR-0048).
  `workspace_purged_at` is the sweep marker for a row the workspace sweep
  pruned rather than fully deleted: it keeps `artifacts_dir` set so the
  artifact routes keep resolving the retained files, so `artifacts_dir IS NULL` alone no longer means "swept" —
  the record sweep's "already handled" check accepts either marker.
  `retained_truncated` flags a prune that couldn't fit everything the globs
  matched under `server.maxRetainedArtifactBytes`: set true or false whenever
  files are kept, and set true — never left null — when the cap truncates the
  kept set down to nothing; null only when nothing matched at all.
- `processes.parent_process_id` (v19) is the id of the run that caused this one
  (`RunAgent`, dispatch, continuation, or the `X-Homer-Parent-Process-Id`
  header; a retry/resume copies the source run's value). Written once at INSERT,
  null at the root. **Not a foreign key**: the parent row may be evicted or
  record-purged first and the child keeps the id as a plain reference.
- `processes.root_process_id` (v22) denormalizes the flow root onto every row,
  written once at INSERT as `parent.rootProcessId ?: parent.id` by every path
  that sets `parent_process_id`, copied from the source run by retry/resume,
  null at a root run. It buys one paginated read of a whole flow
  (`?rootProcessId=`, `?roots=true`), which walking `parent_process_id` hop by
  hop cannot. Like the parent it is **not a foreign key**. Existing rows are
  deliberately **left NULL** rather than backfilled: a pre-v22 parent with no
  root of its own is indistinguishable from a root, and an evicted/purged parent
  cannot be resolved at all. NULL reads as "flow root unknown", never as "this
  row is a root".
- v23 indexes the process-list filters: `agent_name`, `status`,
  `parent_process_id`, `root_process_id`, and `owner` for the non-admin
  visibility `owner = ? OR agent_name IN (…)`, under the names the `Processes`
  table declares. The migration creates them by hand because
  `SchemaUtils.create` only indexes a table it creates. Without them every
  `GET /status/all` scanned the table twice (the page and the `COUNT(*)` behind
  `total`), costing ~100 ms at a full `maxProcesses` table. An OR stays
  indexable only while both of its sides are, so one unindexed column in
  `getAll`'s predicate turns the whole count back into a scan — a new list
  filter gets its index and its pinned plan in `DatabaseMigrationTest` in the
  same change. See ADR-0020.
- v24 indexes the sweep, cooldown and lookup filters that still scanned their
  tables at a full `maxProcesses` table: `processes_artifacts_dir` for the
  shared-workspace check before a purge, eviction or end-of-run cleanup deletes
  a directory, and three for the purge sweeps. `processes_workspace_purge` is partial
  (`artifacts_dir IS NOT NULL AND workspace_purged_at IS NULL`): a swept row
  keeps its `purge_at`, so a plain index on it would walk every row the sweep
  already handled. `processes_record_purge` and the partial
  `processes_legacy_record_purge` serve the two arms of the record sweep's OR
  (pre-v12 rows have no `record_purge_at`). `processes_agent_status_ended`
  serves `cooldown.afterFailure`'s latest-failure lookup inside
  `obtainProcessId`'s write transaction; `latestRunStartedAtTx` stays on
  `processes_agent_name`, whose entries are already in id order. Outside
  `processes`, v24 indexes the question checks
  (`questions_status_dispatch_bound` for the open-question cap, expiry and
  counts, `questions_process_dispatch_bound` for teardown),
  `dispatches_status_expires`, and `continuations_watched_status` and
  `continuations_status_expires` for the watched-process lookup, the PENDING
  caps and the expiry and retry sweeps. The upgrade test also checks that the
  migration's raw SQL and the `Tables.kt` declarations define each index
  identically. `DatabaseMigrationTest` pins each of these query shapes to its
  index (`v24LookupShapes`), planned without `sqlite_stat1`. The admin `?tag=`
  filter is an `id IN (SELECT …)` subquery driven by
  `process_tags_tag_process_id`; a scoped caller keeps the correlated `EXISTS`,
  whose cost never depends on runs it cannot see (ADR-0060, 2026-09-29
  amendment).
- v25 moves write-once `input_params` out of the `processes` row into the
  `process_inputs` side table (`process_id` PK, no foreign key), in the same
  change that narrows the transition reads. A run's inputs are bounded only by
  `server.maxRequestBodyBytes` (10 MiB), and SQLite rewrites a row's whole
  overflow chain on any UPDATE that changes its size — so while they sat inline,
  every per-step `setCurrentCommand`/`addExecution` of such a run rewrote
  megabytes of WAL (13.5 ms and 4.9 MB of WAL for a 5 MB row, against 0.05 ms
  for a normal 500-byte one). `process_inputs` rows are written and deleted
  co-transactionally with the process row — written with the process INSERT,
  deleted on both record purge and capacity eviction (ADR-0048). The side row is
  read only where `ProcessInfo.inputParams` is served (`getInfo` reads its one
  row; `getAll` and `costByTag` batch it via `inputsByProcessTx`, like
  `tagsByProcessTx`). Paths that never surface inputs — the purge sweeps — pass
  `emptyMap()` and skip the read; the reload-kill path (`createdRunsOf`) selects
  only `id` and `agent_name`. The v24→v25 migration copies each row's value with
  `COALESCE(…, '{}')` and drops the column, guarded by a `PRAGMA table_info`
  check so a re-run is a no-op. `DatabaseMigrationTest` pins the upgrade, the
  value round-trip and the WAL volume. Those same two inputs-free sweeps project
  away the other blobs too: they select `Processes.recordReadColumns` rather
  than the whole row, so a purge sweep at a full `maxProcesses` table never
  pulls `history`, `executions` or `runner_state` off disk to discard them.
  `costByTag` reads its up to 10 000 candidates through the same projection (its
  secret screen needs only tags, inputs and record-level columns).
  `ProcessRepositoryTest` pins each projection.
- v26 bounds `dispatches` and `continuations`. The two sweeps delete a
  settled row (any status but `PENDING` and `DISPATCHING`) once its `created_at`
  is older than `server.dispatch.settledRetentionSec`, with its dependent rows
  in the same transaction — a dispatch's `dispatch_bound` question, a join's
  `continuation_watches` — behind a `read { }` due-row pre-check and in batches
  of 500. The settled set is an `IN` list of the remaining statuses, not
  `NOT IN`, so `dispatches_status_created` / `continuations_status_created` can
  seek. The list routes page in SQL (`DispatchRepository.page` /
  `ContinuationRepository.page`, newest first), with the visibility rule before
  the limit as `id IN (SELECT id … owner = ? UNION SELECT id … agent_name IN
  (…))`, each arm on its own index (`*_owner`, `*_agent_name`,
  `*_status_created`). Do not fold it back into `owner = ? OR agent_name IN
  (…)`: with statistics that plans as a backward walk of the whole table. Only
  the unfiltered admin page walks the table, and it reads offset + limit rows.
  `DatabaseMigrationTest` pins the prune shapes and the SQL each page actually
  sends, at 150 000 rows, with and without statistics. See ADR-0021 (2026-09-30
  amendment).
- v27 adds `continuations.thread_id`: `params[HMR_LG_THREAD]`, written
  at INSERT and backfilled with `json_extract`, under `continuations_thread_id`.
  LangGraph `subs` reads one thread's rows on that index, the run's owner a
  residual filter, then all its sub runs in batched reads below the
  512-id chunk they split at. `DatabaseMigrationTest` pins both plans. See
  ADR-0044.

`transitionStatus` and `killTransition` project `status`, `history` and
`agent_name` instead of `selectAll()`: they run under the write lock, and pulling
the blobs of the row into memory only to write them back is pure cost.

Schema: `id`/`status`/`agent_name` are real columns (filtered/sorted on);
`history`/`executions`/`runner_state` and question `options`
are JSON-text columns (kotlinx.serialization via `columnJson`; `Path` fields
use `PathAsStringSerializer`). The process `id` is
`INTEGER PRIMARY KEY AUTOINCREMENT` — monotonic, never reused (so ids start at
1, not 0) — replacing the old `lastProcessId` counter, which is now read from
`sqlite_sequence`.

**A step boundary is one write transaction.** `ProcessRepository.completeStep`
appends the step's `ExecutionInfo` and sets or clears the current command in the
same `BEGIN IMMEDIATE` transaction, and `ProcessManager.completeStep` emits at
most one `CurrentCommandChanged` for it. The step-start set (the run's first step
only; every later step's start rides the previous boundary) is `setCurrentCommand`.
The boundary is also guarded on the row's status, not just its existence: a step
that lands after a concurrent kill still appends its execution, but leaves the
current command cleared (as `killTransition` wrote it) and emits no event — a
terminal run must never be handed back a running command. Both writers of
`current_command` carry that guard: `completeStep` and the step-start
`setCurrentCommand` (which writes the label only when the row is still live), so
no writer can hand a terminal row a running command. Do not split a
boundary's bookkeeping back into an append plus a separate
set/clear: that is the several-transactions-per-step shape this replaced, and it
also doubles the `process.cmd` SSE events per boundary.

**Concurrency, not a `Mutex`.** The read-modify-write operations the `Mutex`
used to make atomic (history/executions append, runner-state merge, the status
CAS, capacity-check + insert) are now transactions. `transaction_mode=IMMEDIATE`
is load-bearing: under WAL a DEFERRED read-then-write that races another writer
fails with the non-retryable `SQLITE_BUSY_SNAPSHOT`; `IMMEDIATE` takes the write
lock at `BEGIN` so writers serialize through it instead. The driver re-issues
`BEGIN IMMEDIATE` inside `commit()` in manual-commit mode, so `query { }` bounces
the connection back to autocommit at the after-commit seam: **the write lock ends
with the transaction, not with Exposed's connection teardown.** Keep transactions
short and never suspend on another transaction inside one.

**A transaction that only reads goes through `read { }`, never `query { }`.**
Under `IMMEDIATE` a read holds the write lock for its whole duration, and a list
read at a full `maxProcesses` table costs well over 100 ms, so API polling alone
can keep the lock busy until a run's step bookkeeping times out on
`SQLITE_BUSY` and the run fails. `read { }` begins DEFERRED, which under WAL
neither takes nor waits for that lock. Anything that writes — including a
read-then-write — stays on `query { }`; `query_only` makes a write misrouted to
`read { }` fail with `SQLITE_READONLY`. See ADR-0020 (2026-09-29 amendment).
