---
status: accepted
date: 2026-08-19
deciders: Lukáš Vlk
---
# ADR-0044: LangGraph resume and run introspection

## Context and Problem Statement

ADR-0024 gave Homer a LangGraph command type with durable checkpoint storage
(`SqliteSaver` at `$HOMER_LG_STATE_DIR/checkpoints.sqlite`), but Homer never
learns the thread id of a run. When a graph node raises, the process exits with
`FAILED` status and the run is permanently lost — the checkpoint survives in
SQLite, but no code path can resume it. Meanwhile, the console (ADR-0027) shows
only the static graph structure; there is no view of which nodes completed,
failed, are pending, or parked for a given run.

Both features need a common foundation: the thread identity must be recorded
durably by the runtime before execution starts.

## Decision Drivers

- **Recovery must be explicit and separate from fresh retry.** A crashed node
  and a rejected input are different failure modes; the user must choose resume
  vs. retry, and `POST /processes/{id}/retry` must keep its from-scratch
  semantics unchanged.
- **Resume must map to LangGraph's native recovery model** (`invoke(None,
  config)` from the latest checkpoint), not a custom one. No time travel, no
  checkpoint picker — simplest wins.
- **The hot execution path must not change.** The runtime's `run()` flow stays
  linear (fresh or resume or recover, always same path). Status reads are
  shelled out on demand; they do not restructure the runner.
- **Thread identity must survive a crash.** The artifact file exists before any
  graph code runs; if the process dies mid-execution, the identity is still
  readable and recovery is possible.
- **Scope must be local runner only.** On SSH or kubernetes runners, the state
  directory is remote; checkpoint durability there is a pre-existing gap outside
  this decision.

## Considered Options

- **Live status streaming from the running process.** The runner writes node
  status updates to a side channel during execution.
  - Bad: requires restructuring the hot path and adding a sink for status
    updates; increases complexity of the runtime's error handling.
  - Bad: status reflects the execution's view, not the checkpoint's current
    truth (out of sync during parks and subsequent segments).
  - Rejected.
- **Status from a read-only subcommand shelled out on demand** (chosen).
  - Good: hot path unchanged; mirrors the `inspect` subcommand pattern
    (subprocess + timeout, independent of the run).
  - Good: status reflects the thread's checkpoint state at query time, including
    the result of any completed segments.
  - Good: console can gate polling on whether the execution item is expanded.

## Decision Outcome

Three coordinated decisions:

### 1. Thread-id artifact

The runtime writes `$HMR_ARTIFACTS_DIR/langgraph.json` **before** invoking the
graph:

```json
{"threadId": "<hex>", "module": "pkg.mod:attr", "segment": "fresh" | "resume" | "recover"}
```

This is crash-safe by construction (file exists before any graph code runs) and
requires no DB schema change. Overwritten on every segment of the same thread.
Skipped silently when `HMR_ARTIFACTS_DIR` is unset (e.g., agentdebug builds
workspaces without the full env contract).

### 2. Explicit resume endpoint

New `POST /api/v1/processes/{id}/resume` endpoint (gated by `canRetry`, same
auth as `POST /retry`):

- **Validations:** source process status ∈ {`FAILED`, `UNKNOWN`, `KILLED`};
  agent still exists with a LangGraph command; `langgraph.json` present.
- **Dispatch:** new process started with the same agent, retried input params,
  plus `HMR_LG_THREAD=<threadId>` and `HMR_LG_RECOVER=1` in the env.
- **Runtime behavior:** recovery mode is triggered when `HMR_LG_THREAD` is set
  AND `HMR_LG_RECOVER == "1"` AND `HMR_LG_RESUME` is absent; the runtime
  invokes the graph with `None` input, causing LangGraph to resume from the
  latest checkpoint.
- **Non-change:** `POST /processes/{id}/retry` keeps its from-scratch semantics
  (fresh thread, new invoke). Recovered nodes re-run from their start (same
  idempotency caveat as interrupt re-runs today).

### 3. Read-only node-status introspection

New read-only `homer_langgraph state` subcommand and `GET
/api/v1/processes/{id}/langgraph` endpoint (gated by `canSee`, same auth as
process reads):

- **Subcommand:** `python -m homer_langgraph state --module <m> --thread <t>`
  compiles the graph with its `SqliteSaver` and derives per-node status from
  `get_state()` + `get_state_history()`:
  - `done` — a task for the node with `result` set and no `error` appears in
    any snapshot from `get_state_history()` (not `metadata["writes"]`, which
    `langgraph-checkpoint` 4.x no longer persists).
    *(Amended 2026-10-09: since commit `209604e5` a task counts as completed
    whenever it has a `result`, error or not, so a node recovered after a
    failure reports `done`.)*
  - `failed` — latest snapshot has a `PregelTask` for the node with `error` set.
  - `parked` — latest snapshot has a task for the node with non-empty `interrupts`.
  - `pending` — node is in the latest snapshot's `next`.
  - `unreached` — any other node of the compiled graph.
  - Precedence: `failed > parked > pending > done > unreached` (resolves
    ambiguity in loops).
- **Endpoint response** (200):
  ```json
  {
    "label": "...", "module": "...", "threadId": "...",
    "mermaid": "...",
    "nodes": {"node_a": "done", "node_b": "failed"},
    "errors": {"node_b": "..."}
  }
  ```
  The Mermaid source rides the `state` subprocess output (the graph is
  compiled there anyway), so one status request costs a single Python
  subprocess — `LangGraphInspectService` is not involved on this path.
- **Polling:** Console enables the query only while the execution item is
  expanded; stops on terminal status or collapse.
- **Failure mode:** a `state` subprocess failure surfaces as an `error` field
  with `nodes`/`errors`/`mermaid` null; the console shows the error text.
  A mermaid draw failure alone (or an older runtime whose `state` output
  predates the field) degrades to `mermaid: null` with the per-node state
  intact; the console then renders the statuses as a plain list.
  *(Amended 2026-10-09: the console draws from `topology` (+ `traversed`) since
  [ADR-0073](0073-langgraph-topology-is-structured-data.md) and
  [ADR-0075](0075-langgraph-traversed-path-from-checkpoint-writes.md); `mermaid`
  is deprecated and no longer read.)*
- **Per-sub progress for fan-out nodes:** `state` additionally reports
  `interrupts` (node -> pending interrupt ids) and `interruptsSeen` (node ->
  the interrupt ids in the thread's history, the same `get_state_history()` fold
  as the `done` derivation above). `interruptsSeen` is capped per node at its
  newest ids, never dropping a live park, and clipped nodes are named in
  `interruptsSeenTruncated`, which the response exposes as `subsTruncated`. The
  fold itself stays unbounded: a node's `done` may only be provable from the
  oldest snapshot, so an early exit would misreport status. It is recomputed on
  every call — a file cache beside the checkpoint store was tried and removed
  (see the amendment below); repeated polling is bounded instead by a
  short-TTL, size-bounded in-memory cache of this reading in
  `LangGraphRunStatusService`, which does not cache `subs` (a database read, so
  sub progress stays live on a cache hit).
  The endpoint matches each id against Homer's
  continuations for the run's thread **in any status** (JOIN watches and
  SINGLE fanout resumes both key a watched process id by interrupt id),
  resolves the watched process's status/agent via `ProcessManager`, and
  exposes the result as `subs` (node -> sub progress), sparse by construction
  and wrapped in a catch-all so a lookup failure only drops `subs`, never the
  rest of the response. `subs` is exposed to anyone who can see the parent
  process, with no separate per-sub authorization check — the same exposure
  model already used for dispatch summaries on questions. Restricting either
  side to what is *currently* pending is what made a node's fan-out history
  vanish the moment its join fired; a sub whose process has since been evicted
  from the store is reported with its interrupt id and null details rather than
  dropped.


### Notable property: interrupt-resume precedence

When a segment crashes mid-interrupt-resume (e.g., during `homer-ask` side
effects), the stored `HMR_LG_RESUME` value is replayed by `resolveRetryParams`
(reserved-name passthrough). The runtime's recovery detection gives it
precedence over recover mode:

```
recovering = HMR_LG_THREAD set  AND  HMR_LG_RECOVER == "1"  AND  HMR_LG_RESUME absent
```

This ensures the correct recovery path for a crashed side-effect operation (stay
on the interrupt until the side effect completes).

## Consequences

- Good: crashed LangGraph nodes can now be resumed explicitly, with the
  checkpoint resuming from the latest saved state.
- Good: console gains per-node status visibility, updated on demand, reflecting
  the thread's current truth across all segments.
- Good: hot execution path is unchanged; status introspection is independent
  (`inspect`-style subprocess), so the runner's logic stays linear and
  testable.
- Good: no DB schema changes; the contract stays in the artifact file + env,
  matching the existing checkpoint-id communication model.
- Bad: local runner only. On SSH/kubernetes runners, the state directory is
  remote and dies with the workspace/pod; checkpoint durability and status
  introspection are not available there. This is a pre-existing gap outside
  this ADR's scope.
- Bad: one LangGraph command per agent. If an agent defines multiple LangGraph
  commands, the artifact file reflects the last writer, creating ambiguity. The
  contract assumes (and is documented as) single-command. This is not fixed by
  this ADR, but documented and enforced by pre-invoke guards in the resume
  endpoint.

## Amendment (2026-08-20): no cache in the `state` subcommand

A per-thread file cache for `state` was tried and removed. It lived in the state
directory, which agent code can also write, and produced four consecutive findings
in a day: symlink-followed write, symlink-followed read via the parent, a TOCTOU
race in the temp-then-rename commit, and finally a hard-link overwrite once
`O_EXCL` was dropped to write in place. Each fix created the next surface. Writing
a file safely into a directory an attacker also writes needs `O_EXCL` on an
unpredictable name plus `renameat` on a directory descriptor, and `os.replace`
takes no `dir_fd` on macOS, so there is no portable version.

It was not worth defending: the fold is ~3% of a status call, because a `state`
subprocess is almost entirely interpreter start plus the `langgraph` import.
Repeated polling is bounded where it can be done without touching the filesystem
— a short-TTL, size-bounded in-memory cache in Homer's
`LangGraphRunStatusService`, which skips the whole subprocess rather than just
the fold. It caches *this reading*, not the endpoint's whole response: Homer
re-resolves per-sub progress from its own database on every request, so sub
status stays live even on a cache hit.

## Amendment (2026-09-30): the in-memory cache and its semaphore, in full

The 2026-08-20 amendment above decided *that* `LangGraphRunStatusService`
caches; this one records the shape of that cache and the bound beside it, which
until now lived only in `app/CLAUDE.md` and in the service's own comments.

**Why the subprocess is worth caching at all.** A `state` call costs ~350 ms,
almost entirely interpreter start plus the `langgraph` import — the fold the
previous amendment measured at ~3% is the cheap part. So the cache skips the
whole subprocess, and caches failure as well as success: a timing-out or
missing interpreter is the most expensive outcome there is and must not be
re-attempted once per poll per viewer.

**TTL (4 s) and the LRU size bound (64 entries).** The TTL sits just under the
console's 5 s poll: one viewer still reads fresh state on every poll, and extra
viewers of the same thread ride along. The TTL alone is not a bound, though —
part of the cache key is a thread id read from an agent-writable artifact
(`langgraph.json`), so key cardinality is attacker-influenced. The cache is
therefore a **bounded access-ordered LRU** (`maxCachedStates`, 64), and the
size bound — not the TTL — is what caps retention. Eviction is O(1), so no
request pays for a scan, and overflow only costs cache hits. This is ADR-0047
rule 4 applied locally: a bound that lives in another component (here, the
per-caller rate limiter) is not a bound.

**Cache versus semaphore — different properties.** The cache bounds *repeated*
work; a `Semaphore` (`maxConcurrentStateRuns`, 4) bounds *simultaneous* work,
and the latter is what protects the server. Each run is a ~75 MiB Python
interpreter whose exit is awaited by *blocking a `Dispatchers.IO` thread* — the
same pool every SQLite transaction uses — so an ungated burst of `?graph=true`
could starve the database dispatcher and degrade real agent runs (ADR-0047's
worked example).

**Bounded wait, then shed.** Callers past the limit wait for a slot, but only
for `maxQueueWait` (5 s). An unbounded wait just moves the pile-up from
interpreters to suspended callers holding connections, so a saturated endpoint
sheds load with a `busy` error instead (ADR-0047 rule 5).

**Ordering: permit before the second cache check.** The permit is taken
*before* the cache is re-checked, and that ordering is load-bearing — it is
what makes a burst of concurrent polls for one thread collapse onto the runs
already in flight, instead of each queued caller spawning its own interpreter.

**`subs` stays out of the cache.** It is a database read, so sub progress stays
live on a cache hit; re-resolving it per request is the whole point of the
split recorded in the 2026-08-20 amendment.

## Amendment (2026-09-30): `subs` reads the thread's continuations by index

`subs` used to read every continuation in the database and filter on
`params[HMR_LG_THREAD]` in memory, then read each watched sub's full process
record one at a time, so one status call grew with the whole `continuations`
table and with the number of subs. Schema v27 copies the thread id out of
`params` into the indexed `continuations.thread_id` column at INSERT (the
upgrade backfills it with `json_extract`), and `subs` now reads only that
thread's rows, then every watched sub's agent, status, executions and current
command in one batched read. The number of statements per call no longer grows
with the number of subs (it is fixed below the 512-id chunk the batched reads
split at), and the response is unchanged.

The thread id comes from agent-supplied params, so it is attacker-controlled
(ADR-0047): it is only ever compared for equality with a bound parameter.
`subs` is still not cached; the rationale above stands.

`subs` is also owner-scoped, like the checkpoint read (ADR-0046): it matches
only continuations whose `owner` equals the run's, and an unowned run only
unowned rows. No served case crosses owners — continuations copy their
origin run's owner down the chain (ADR-0038), and a retry or resume by another
principal is owned by that principal and reads its own checkpoint store.

## More Information

- Related: [ADR-0024](0024-langgraph-command-type.md) (LangGraph command type
  and checkpoint storage), [ADR-0027](0027-langgraph-graph-introspection.md)
  (static graph introspection), [ADR-0021](0021-ask-and-dispatch.md) (ask and
  dispatch, side-effect machinery resume uses).
