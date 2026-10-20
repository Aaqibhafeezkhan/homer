---
status: accepted
date: 2026-06-14
deciders: Lukáš Vlk
---
# ADR-0020: Durable orchestrator state in SQLite (processes + questions)

## Context and Problem Statement

`ProcessManager` and `QuestionsService` held **all** process records and
human-in-the-loop questions in in-memory maps behind a coroutine `Mutex`. A
server restart silently lost every process record and every open question;
in-flight runs vanished from history with no trace. We want process state and
HITL questions to survive restarts, configured by a DB file path in
`AppConfig` exactly like `agentsSearchPath` / `workspaceRootPath`. This is also
the foundation for the planned `ask-and-dispatch` feature, which needs a shared
durable store rather than inventing its own JSON files.

## Decision Drivers

- Survive restarts: process history and open questions must not disappear.
- Single-node deployment (see the single-node ADRs) — no distributed store.
- Keep the executors and routes untouched: the service interfaces stay the same.
- A clean substrate for the upcoming dispatch feature (a `dispatches` table).

## Considered Options

- SQLite via Exposed (JetBrains Kotlin SQL framework) + sqlite-jdbc.
- Hand-rolled JSON files per process / question.
- An embedded KV store (e.g. RocksDB / MapDB).

## Decision Outcome

Chosen option: **SQLite via Exposed + sqlite-jdbc**, with **SQLite as the
single source of truth** (no in-memory cache of durable state). `ProcessManager`
and `QuestionsService` keep their public `suspend` interfaces and delegate to
`ProcessRepository` / `QuestionsRepository`; only their internals swap
map+`Mutex` for transactions. The runtime-only state that cannot be persisted —
the process coroutine `Job`, the question answer-wait `CompletableDeferred`s,
and the callback-token registry — stays in small in-memory registries
(`ProcessJobRegistry`, the `QuestionsService` maps) that start empty on boot.

Schema: the fields we filter/sort on (`id`, `status`, `agent_name`) are real
columns; the always-read-and-written-whole blobs (`input_params`, `history`,
`executions`, `runner_state`, question `options`) are JSON-text columns
(kotlinx.serialization). The process `id` is `INTEGER PRIMARY KEY AUTOINCREMENT`
— monotonic across restarts and never reused — replacing the old in-memory
`lastProcessId` counter.

Concurrency: every connection is opened with `journal_mode=WAL`,
`busy_timeout=5000`, `foreign_keys=ON`, and `transaction_mode=IMMEDIATE`. The
read-modify-write operations the `Mutex` used to make atomic (history append,
executions append, runner-state merge, the status CAS) become transactions.
`IMMEDIATE` is load-bearing, not just an optimization: under WAL a DEFERRED
read-then-write that races another writer fails with the non-retryable
`SQLITE_BUSY_SNAPSHOT`; beginning every transaction with the write lock
serializes writers through it instead. The conditional status UPDATE enforces
the KILLED-precedence invariant (audit #9) atomically at the DB level.
(Amended 2026-09-30: `foreign_keys=ON` is dropped — see the amendment below;
no table declares a foreign key and integrity is maintained in code.)

Restart reconciliation: on boot, before serving traffic, every process still
`CREATED`/`WORKING` is marked **`UNKNOWN`** (with a history entry) and its OPEN
questions are expired. `UNKNOWN` is treated as terminal so these rows are
purgeable and never block on a resume that can't happen. This reuses the
existing `UNKNOWN` status rather than introducing a new one, and runs alongside
the Kubernetes orphan-pod startup sweep.

### Consequences

- Good: process history and open questions survive restarts; interrupted runs
  are visible as `UNKNOWN` instead of vanishing.
- Good: executors, routes, and the MCP layer are untouched — the service
  interfaces are unchanged.
- Good: a shared durable store the `ask-and-dispatch` feature can extend with a
  `dispatches` table instead of bespoke files.
- Bad: the status hot-path now does a DB round-trip per read/write, and
  `IMMEDIATE` serializes even reads — accepted for single-node throughput.
- Bad: process ids now start at 1 (AUTOINCREMENT) rather than 0.
- Bad: the DB file must live on a persisted volume in containers, or the
  durability guarantee is void (documented in DOCKER.md).

## Pros and Cons of the Options

### SQLite via Exposed

- Good: transactional, single-file, zero-ops; type-safe Kotlin DSL; coroutine-friendly via `newSuspendedTransaction` / `Dispatchers.IO`.
- Neutral: JSON-text columns keep per-process blobs trivial to map while the queryable fields stay real columns.
- Bad: single-writer model; needs WAL + `busy_timeout` + `IMMEDIATE` to behave under concurrency.

### Hand-rolled JSON files

- Good: no dependency.
- Bad: every concurrency and atomicity concern (the exact thing the `Mutex` solved) becomes our problem again; cross-process queries (pagination, active-id scans) become directory walks.

### Embedded KV store

- Good: fast.
- Bad: another dependency and operational surface for no query benefit; still need our own indexing for status/agent filters.

## Amendment (2026-09-24): `migrate()` runs before the first injection that can reach SQLite

This ADR's ordering rule was read as "before reconciliation, cron, sweeps and
traffic". That is not enough: a Koin service initializer can reach the database
too. `configureServer` now injects `DatabaseService` and calls `migrate()` before
any other injection, because `AgentsService` scans the agents directory on
construction, and for an agent selecting its runner with `runnerTags` that scan
evaluates the servability check, which resolves the runner registry — which reads
`server_meta` whenever a kubernetes runner is configured. On a fresh database (or
one older than schema v9, where `server_meta` was added) that table does not
exist until `migrate()` creates it, so resolving the service first made the
per-agent `catch` skip the agent and boot with an empty agents list.

The invariant, stated so a reordering cannot lose it: **`migrate()` completes
before the first database access of any kind** — before the first service whose
initializer touches persistence, and therefore before reconciliation, cron,
sweeps and traffic alike. Moving the call below the injection block, or moving an
injection that reaches SQLite above it, reintroduces the failure. A migration
failure at that point aborts boot deliberately rather than starting with
unreadable durable state.

## Amendment (2026-09-29): transactions that only read run DEFERRED, off the write lock

The Consequences above accepted that "`IMMEDIATE` serializes even reads". At a
production table size that acceptance does not hold. With `processes` at its
150 000-row `maxProcesses` cap, every list read costs 140–180 ms — the
`COUNT(*)` behind `total` scans the table — and under `IMMEDIATE` it holds the
write lock for all of it. A console list page refetching one list plus twenty
flow summaries on every process event drove `GET /status/all` to about 400 per
minute, which is the lock's whole capacity: a run's step bookkeeping
(`addExecution`, `setCurrentCommand`) then waited past `busy_timeout` and threw
`SQLITE_BUSY` outside the step's own try, so the run went `FAILED` with no
error message — 32 runs in 15 minutes on one installation, reproduced locally
against a seeded database.

Decision: `DatabaseService` keeps a second handle for reads. Its transactions
begin DEFERRED and every connection runs `PRAGMA query_only = ON`. Under WAL a
DEFERRED transaction that never writes neither takes nor waits for the write
lock, and it cannot hit `SQLITE_BUSY_SNAPSHOT`, which only a read that later
upgrades to a write can. The rule for repositories:

- a transaction that only reads goes through `DatabaseService.read`;
- a transaction that writes, **including every read-modify-write**, stays on
  `DatabaseService.query` and its `BEGIN IMMEDIATE`, exactly as above.

`query_only` makes a misrouted write fail loudly with `SQLITE_READONLY` instead
of silently running outside the write lock. A read sees the last committed state
as of its first statement, which is what an `IMMEDIATE` read saw too: no caller
relied on a read being serialized against a write in another transaction, since
separate transactions were never atomic with each other.

The same change indexes the list filters (`agent_name`, `status`,
`parent_process_id`, `root_process_id`, plus `owner` for the non-admin visibility
OR, schema v23). Taking reads off the lock
removes the failure, but a read still costs what it costs: without the indexes
every list read scanned the table in the backend's CPU.

## Amendment (2026-09-29): the writer handle never retries a transaction

Exposed re-runs a transaction up to `maxAttempts` times when a `SQLException`
escapes it, and a `commit()` that throws counts as a failed attempt **even when
the data landed**. That case is not hypothetical here: sqlite-jdbc's
`commit()` executes `COMMIT` and then immediately re-runs `BEGIN IMMEDIATE`,
and another connection can take the write lock in that window, so the commit
succeeds and `commit()` still throws `SQLITE_BUSY` (measured: 16 times in 300
iterations of a two-connection probe). Exposed then re-runs the whole
`query { }` block — and every write block in the repositories is a
read-modify-write append that a replay would duplicate: an execution entry, a
cost delta charged twice (the append-only ledger of ADR-0035 has no key a
replay could collide with), a second process row, a second cost entry.

Decision: the writer handle is **single-attempt**. `DatabaseService.open()`
connects it with `DatabaseConfig { defaultMaxAttempts = 1 }`
(`DatabaseService.WRITER_DATABASE_CONFIG`). The rule for write blocks:

- a write block may assume it runs exactly once and is never retried; it does
  **not** have to be idempotent, and none of the existing ones are;
- a transient `SQLITE_BUSY` past `busy_timeout` now surfaces to the caller
  instead of being retried twice — that is the intended trade, since the
  alternative is silently duplicating durable state;
- the reader handle keeps Exposed's default retries. Reads are DEFERRED and
  `query_only`, so a re-run is safe by construction.

This changes nothing about the handle split or `transaction_mode=IMMEDIATE`
above; it only removes a retry that was never safe on this handle.

## Amendment (2026-09-29): the write lock ends at commit, not at connection teardown

`transaction_mode=IMMEDIATE` makes sqlite-jdbc issue `BEGIN IMMEDIATE` when the
connection leaves autocommit — and, because `SQLiteConnection.commit()` runs
`COMMIT` and then `transactionPrefix()` again while the connection is still in
manual-commit mode, a *second* `BEGIN IMMEDIATE` immediately after every
commit. The committed transaction therefore kept the write lock until the
connection closed, which in Homer is Exposed's transaction teardown. The window
was short (Exposed closes the connection as part of tearing the transaction
down) but real: it is what made a `commit()` race throw `SQLITE_BUSY` from the
driver's own re-`BEGIN`, and it is why pooling or reusing a writer
connection was ruled out — an idle pooled writer would hold the lock for its
whole lifetime.

Decision: `DatabaseService.query` registers an Exposed `StatementInterceptor`
whose `afterCommit` runs `Connection.setAutoCommit(true)`. That ends the driver's
re-grown transaction and releases the lock with it, so **the write-lock window
equals the write transaction**. The pragma set is otherwise unchanged:
`IMMEDIATE` stays, because every write transaction must still take the write
lock up front (the invariant that replaced the coroutine `Mutex`), and the
driver re-issues `BEGIN IMMEDIATE` the moment Exposed starts the next
transaction. Writer connections are still not pooled or reused: nothing is
gained by holding one open now that its idle state releases the lock. Together
with the single-attempt amendment above — the same re-`BEGIN` is what makes a
`commit()` throw after it landed — a write block on this handle neither replays
nor holds the lock past its commit.

Only `query { }` registers the bounce. The other two raw
`transaction(db.database)` call sites — `migrate()` and
`ServerMetaRepository.getOrCreateInstanceId` — deliberately keep the wider
connection-lifetime window: both are blocking, startup-only, and run before any
traffic, so no run or request ever waits behind them. A new raw-`transaction`
call site on a live path would silently widen the window again and belongs on
`query { }` instead.

Two properties this relies on:

- **The fix is post-commit only.** `afterCommit` runs after the `COMMIT` has
  already landed, so a failure there cannot turn a committed write into an
  error; it degrades to the driver's own re-`BEGIN`, i.e. back to the old
  window. A rollback still goes through Exposed's `rollback()`, which
  `setAutoCommit(true)` never runs for.
- **Shutdown checkpointing leaves the lock-taking path.** `close()` used to run
  `PRAGMA wal_checkpoint(TRUNCATE)` inside its own write transaction
  (`transaction(database) { … }`), where the transaction's own write lock makes
  SQLite refuse the checkpoint with
  `SQLITE_LOCKED` — which `runCatching` swallowed, so the WAL was never
  truncated on shutdown. It now runs on its own autocommit connection, which
  holds the write lock only for the checkpoint's own duration.

## Amendment (2026-09-29): read connections are kept open between transactions

The read handle opened a new SQLite connection for every transaction and closed it
afterwards: 0.42 ms of open, pragma and schema parse per point read at a full
`maxProcesses` table, against 0.004 ms on a reused connection, with a cold page
cache every time. The console alone sends about a thousand reads a minute.

Decision: the read handle takes its connections from `IdleConnectionCache`, which
keeps up to four open between transactions. It is a cache, not a bound: with none
idle a new connection opens, so no caller ever waits there and no new failure mode
appears under load. A connection is returned with its transaction committed
(sqlite-jdbc re-begins one after every commit), so an idle connection pins no WAL
snapshot, and `PRAGMA query_only` is set once when it opens. `close()` closes the
idle connections before the shutdown checkpoint. A lease is a JDK proxy rather than a
`Connection by delegate` class: after its close() returns the connection, which may
already serve another transaction, the lease refuses every further call, and only a
proxy can guard all of `Connection`'s methods.

The writer is deliberately left out. Under `transaction_mode=IMMEDIATE`,
sqlite-jdbc re-runs `BEGIN IMMEDIATE` inside `commit()`, so a writer connection
kept open holds the write lock between transactions. The writer's connection model
is left to a separate change.

## Amendment (2026-09-29): the shutdown checkpoint runs outside a transaction

`close()` flushed the WAL into the main file with `PRAGMA
wal_checkpoint(TRUNCATE)` executed on the raw connection of an Exposed
`transaction(database)` block. Under `transaction_mode=IMMEDIATE` that
connection is inside an open `BEGIN IMMEDIATE`, and SQLite refuses a
checkpoint from inside any transaction: it fails with `SQLITE_LOCKED`
("database table is locked"). The failure was swallowed into a warning, so
the shutdown checkpoint never ran; the file stayed consistent only because
closing the last connection checkpoints implicitly.

Decision: `close()` runs the pragma on a short-lived autocommit
`SQLiteDataSource` connection opened and closed inline for `databasePath`,
with the same `busy_timeout`. The constraint, so a later refactor cannot lose
it: **a WAL checkpoint must never run inside an Exposed
`transaction(database)`** — or any explicit transaction — because
`transaction_mode=IMMEDIATE` makes every such block a `BEGIN IMMEDIATE`, and
SQLite returns `SQLITE_LOCKED` for a checkpoint issued inside a transaction.
The writer's `transaction_mode=IMMEDIATE` is unchanged and stays load-bearing
for `SQLITE_BUSY_SNAPSHOT` avoidance; the checkpoint is not a write
transaction, so the "writes go through `query`" rule is untouched.

## Amendment (2026-09-29): write-once `input_params` moves to a side table

The Decision Outcome above records the schema as "the fields we filter/sort on are
real columns; the always-read-and-written-whole blobs (`input_params`, `history`,
`executions`, `runner_state`, question `options`) are JSON-text columns". For
`input_params` that grouping was wrong, and the difference is expensive.

`input_params` is not read-and-written-whole; it is written once at INSERT and
read only where `ProcessInfo.inputParams` is served. It is also the only one of
those blobs whose size is caller-controlled: bounded by
`server.maxRequestBodyBytes`, 10 MiB by default, with no per-input cap. SQLite
stores a row larger than its page as an overflow chain and rewrites the whole
record — chain included — on any UPDATE that changes the row's size. So every
per-step write of a run with a large input (`setCurrentCommand`, `addExecution`,
the status transition) rewrote the entire blob: a query-audit measurement on a
5 MB row put one size-changing UPDATE at 13.5 ms and 4.9 MB of WAL, against
0.05 ms and 4 KiB for a normal 500-byte row. Step bookkeeping is the hottest
write path in the system, so that is the wrong row to keep it in.

Decision: `input_params` moves to a `process_inputs` side table (`process_id`
primary key, `input_params` JSON text, no foreign key) — the same shape and the
same co-transactional-delete rule as `process_tags` (ADR-0060, ADR-0048). The
side row is inserted in the process INSERT's own transaction and never updated,
so no per-step write touches it. Every read that surfaces the value goes there:
`getInfo` reads its one row, `getAll` and `costByTag` batch by process id in the
same way they already batch tags, and paths that never surface inputs pass none
and skip the read.

The rest of the model is untouched and remains binding: SQLite is still
authoritative, WAL is still on, writes are still `IMMEDIATE` read-modify-write
transactions on `query { }`, reads that only read are still on `read { }`, and
`migrate()` still completes before any database access. The migration is schema
v24→v25: it copies each row's value with `COALESCE(input_params, '{}')` into the
side table and drops the column, in one transaction, guarded by a
`PRAGMA table_info` check so a re-run on an already-migrated database is a no-op.

`history`, `executions` and `runner_state` deliberately stay inline. They are
genuinely read-modify-written every step and need row-local atomicity with
`status`; only the write-once, caller-sized blob moved.

The transition reads above get the same treatment for the same reason: a
read that runs under the write lock should not pull the row's blobs into memory
to write them back. `transitionStatus` and `killTransition` now select `status`,
`history` and `agent_name` — the columns they actually use — instead of
`selectAll()`.

## Amendment (2026-09-30): `foreign_keys=ON` is dropped — integrity is maintained in code

The Concurrency paragraph above lists `foreign_keys=ON` among the pragmas every
connection is opened with. That pragma has never had anything to enforce: no
table in `Tables.kt` declares a `REFERENCES` constraint. It was set
forward-looking, but as written it is dead configuration that invites a reader
of this ADR — or of `services/persistence/CLAUDE.md`'s pragma list — to assume
referential integrity the database does not provide. The 2026-09-29 persistence review
flagged exactly that gap.

Decision: `DatabaseService.open()` no longer sets the pragma, and no foreign key
is declared to give it something to do. Referential integrity is maintained in
code, which is how every link already works:

- **Co-transactional deletes.** A child row that must not outlive its parent is
  deleted in the same transaction as the parent row — `process_tags` and
  `process_inputs` on both record purge and capacity eviction (ADR-0048,
  ADR-0060). The database needs no cascade for this; the delete is explicit.
- **Existence-guarded inserts.** Where the parent's delete and the child's
  insert are *not* one transaction — a late runtime-tag callback after the run's
  record purge — the insert re-checks the parent row inside its own transaction
  and fails closed (ADR-0060). A foreign key could not hold that boundary either,
  since the two transactions were never atomic with each other.
- **Plain references that may dangle by design.** `parent_process_id` and
  `root_process_id` must keep reading as "caused by #N" after the parent is
  evicted at `maxProcesses` or record-purged, and `continuation_watches` rows
  follow the continuation's lifecycle rather than a cascade (ADR-0026, ADR-0048,
  ADR-0058). A foreign key on these would have to tolerate the dangling value it
  exists to prevent — which is to say it must not be declared.

Declaring the FKs that could hold today was the considered alternative and is
rejected. Only the two side-table links — `process_tags.process_id` and
`process_inputs.process_id` → `processes.id` — are co-transactional enough
to satisfy one, and adding a constraint to an existing SQLite table
requires a full table-rebuild migration (SQLite has no `ALTER TABLE … ADD
CONSTRAINT`) — real risk and a schema-version bump for a link the code already
maintains. It would also make the schema's no-FK decision for every sibling link
look accidental rather than deliberate.

`Tables.kt` is unchanged: no `.references()` is added, no schema-version bump,
no migration step. `DatabaseServiceTest` pins the outcome by asserting that the
migrated schema declares no `REFERENCES`/`FOREIGN KEY` anywhere, so declaring one
later fails the build and forces this decision to be revisited rather than
silently making the pragma meaningful again.

The rest of the model is untouched and remains binding: SQLite is still
authoritative, WAL is still on, writes are still `IMMEDIATE` read-modify-write
transactions on `query { }`, reads that only read are still on `read { }`, and
`migrate()` still completes before any database access.

## More Information

- Supersedes the in-memory lifecycle described in [ADR-0019](0019-human-in-the-loop-questions.md) for the *durable* parts of questions; the callback-token registry and answer-wait deferreds remain in memory as that ADR describes.
- Single-node scope is consistent with the SSE-broadcaster and cost-meter ADRs.
