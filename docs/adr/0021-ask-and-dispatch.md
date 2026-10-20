---
status: accepted
date: 2026-06-15
deciders: Lukáš Vlk
---
# ADR-0021: ask-and-dispatch — deferred dispatch from a question answer

## Context and Problem Statement

ADR-0019 lets a running command block on an operator question; the answer
is delivered inline to the waiting process. But a common pattern is
fire-and-forget: a command asks a question, exits immediately, and Homer
should start a target agent with the operator's answer *whenever it
arrives* — possibly hours later, long after the asking process has
finished. There is no mechanism for this: the ADR-0019 question lifecycle
is bound to the asking process, so questions die when their process goes
terminal. We need a way to register a target agent + parameters alongside a
question and have Homer fire the dispatch automatically on answer, with
the question and dispatch surviving the asker's lifecycle independently.

## Decision Drivers

- Asking commands should be able to return immediately and not tie up a
  process slot or workspace for the entire human-think time.
- The dispatch and its question must survive the asker going terminal,
  being purged, or being evicted — they must be decoupled from the
  ADR-0019 per-process lifecycle.
- The dispatched run must be a fully independent, first-class run: empty
  invocation chain, normal cost-cap / parallel-run preflight, and the
  asker's credentials must not leak into the target run.
- Crash safety: if Homer dies between recording the answer and firing the
  dispatch, the dispatch must be re-fired on next boot.
- Bounded growth: dispatch-bound questions escape the per-process open
  question cap, so a separate global cap and an optional per-dispatch TTL
  are needed.
- The feature builds on the ADR-0020 SQLite store; no new persistence
  technology.

## Considered Options

- **Deferred dispatch via a `dispatches` table** — record the target
  agent + params at question-creation time; fire on answer in the
  answer route handler; re-fire on boot for crash safety. Question
  lifecycle decoupled from the asker via a `dispatch_bound` flag.
- **Polling by the asker** — asker polls the question answer endpoint in
  a loop, fires the target agent itself. Simpler but requires the asker
  to stay running, which defeats the fire-and-forget goal.
- **Webhook / callback on answer** — Homer POSTs to a caller-supplied
  URL when an answer arrives. Avoids server-side dispatch state but
  shifts crash-safety and retry responsibility to the caller.

## Decision Outcome

Chosen: **deferred dispatch via a `dispatches` table**, with question
lifecycle decoupled from the asker via a `dispatch_bound` flag on the
ADR-0020 SQLite store (schema v2).

**Data model.** A new `dispatches` table records `id`, `question_id`
(reference to `questions.id`; no declared FK, see ADR-0020), `status` (`PENDING` / `DISPATCHING` / `DISPATCHED` /
`FAILED` / `CANCELLED`; `DISPATCHING` is the transient claimed-while-the-run-is-
starting state), `agent_name`, `answer_param`, `params` (JSON), `origin_process_id`,
`origin_agent_name` (asker referer), `expires_at` (nullable TTL), and
`dispatched_process_id` (set on `DISPATCHED`). The `questions` table gains
a `dispatch_bound INTEGER NOT NULL DEFAULT 0` column. A question row and
its dispatch row are created in one `IMMEDIATE` transaction
(`DispatchRepository.create`), so there are never orphaned questions
without a dispatch or vice versa.

**Decoupled question lifecycle.** The three teardown paths that normally
expire or remove questions gain an `AND dispatch_bound = 0` guard:
process terminal status (`QuestionsRepository.expireForProcess`), workspace
purge / capacity eviction (`removeForProcess`), and
`StartupReconciliation`'s open-question expiry for interrupted processes.
This is a deliberate divergence from ADR-0019, where questions die with
their process. Dispatch-bound questions are cleaned up only by the dispatch
lifecycle: answer → fire, operator cancel, or TTL sweep.

**Fire on answer.** `DispatchService.fire` is called from the answer route
handler immediately after the answer is recorded. The answer always
succeeds even if firing fails — a failed fire marks the dispatch `FAILED`
(with an error message) but does not roll back the answer. Firing is thus
a recorded, observable side-effect of the answer, not part of its
transaction.

**Crash safety.** `DispatchService.fire` claims the dispatch
(PENDING→`DISPATCHING`, a conditional UPDATE) *before* starting the run, so a
crash between run-start and `markDispatched` leaves the dispatch `DISPATCHING`,
never `PENDING`. On boot, `StartupReconciliation` first calls
`failOrphanedDispatching()` — every `DISPATCHING` row is moved to `FAILED`
(interrupted mid-dispatch) so it can never be re-fired — then
`refirePendingAnswered()` finds every still-`PENDING` dispatch whose
`questions.status` is already `ANSWERED` and re-fires them.
`DispatchRepository` status transitions are conditional UPDATEs gated on the
expected from-status, so a concurrent answer route and a re-fire race resolve to
a single winner: only one can win the PENDING→DISPATCHING claim. Operator cancel
and the TTL sweep cancel the dispatch and expire its question in one
transaction, so a crash can never orphan the question OPEN or lose an answer
that lands between the two steps.

**Fresh independent run.** `DispatchService.fire` calls
`AgentRunStarter.start` with an empty invocation chain,
`source = "dispatch"`, and uses `server.dispatch.authToken` (the dispatch
analog of `agent.cronAuthToken`) as the callback token for the dispatched
run. The asker's `HMR_CALLBACK_TOKEN` is never forwarded. The asker is
recorded as `origin_process_id` / `origin_agent_name` on the dispatch row
for traceability, but the dispatched run is fully isolated. *(Amended
2026-10-09: `server.dispatch.authToken` and `agent.cronAuthToken` were removed
([ADR-0030](0030-runagent-in-process-dispatch.md) follow-up); the dispatched
run gets its own per-process callback token like any other run.)*

**Bounding.** An optional per-dispatch `ttlSec` sets `expires_at`; a
periodic TTL sweep (`DispatchService.startTtlSweep`,
`server.dispatch.sweepIntervalSec`) cancels PENDING dispatches whose TTL
has lapsed and expires their questions. A global `server.dispatch.maxPending`
cap (default 100) is enforced inside the creation transaction — if the
count of PENDING dispatches meets the cap, the creation fails with `429`.

**Config block.** `server.dispatch` (optional; all fields have defaults):
`authToken` (nullable, `@SYSENV:`-resolvable), `maxPending` (default 100),
`defaultTtlSec` (nullable), `sweepIntervalSec` (default 60). *(Amended
2026-10-09: `authToken` was removed, see ADR-0030.)*

**Entry points.** A running command registers a dispatch via:
- `homer-ask ask-and-dispatch` (CLI helper, uses `HMR_CALLBACK_TOKEN`).
- `ask_and_dispatch` MCP tool (HITL MCP endpoint).
- `POST /api/v1/callback/dispatches` (raw callback route).

Operator management (list, cancel) is at `GET /api/v1/dispatches` and
`DELETE /api/v1/dispatches/{id}`.

**Target opt-in.** A dispatch may only target an agent that has opted in
via `agent.dispatch.enabled: true` (mirroring `agent.mcp.enabled`).
Registration against an existing-but-not-opted-in agent is rejected with
`403`. This is defense-in-depth on top of the operator-answer gate: even
though the operator must answer before any run fires, the set of
dispatchable agents is constrained at registration time so a command
cannot register a dispatch against an arbitrary agent. The check runs in
`DispatchService.create`; `fire` keeps only its existence check, since the
authorization decision is made when the dispatch is registered.

**Console.** The unified `/questions` inbox lists all OPEN questions;
dispatch-bound rows show the target agent and a link to the dispatched run
(or a failure label) after the answer is recorded.

### Consequences

- Good: asking commands return immediately; no process slot is held for
  the operator think-time.
- Good: dispatched runs are fully independent — operator credentials never
  enter the dispatched process; cost/parallel-run caps apply normally.
- Good: crash-safe by construction — boot reconciliation re-fires any
  PENDING+ANSWERED dispatch missed in a prior crash.
- Good: dispatch-bound questions are invisible to the normal question
  teardown paths, so a purged or evicted asker cannot silently cancel the
  operator's pending decision.
- Good: agents must opt in (`agent.dispatch.enabled`) to be dispatch
  targets, so a command cannot register a dispatch against an arbitrary
  agent — defense-in-depth on top of the operator-answer gate.
- Bad: dispatch-bound questions escape the per-process open-question cap;
  the global `maxPending` cap and TTL sweep are the substitutes.
- Bad: answer and fire are in the same request handler — a slow or
  crashing fire makes the answer endpoint slower; mitigated by recording
  the answer first and treating fire as a separate step.
- Bad: `server.dispatch.authToken` must be configured (or left null,
  making dispatched callbacks unauthenticated) — operators need to be
  aware of this token analogously to `agent.cronAuthToken`. *(Obsolete
  since ADR-0030: both tokens were removed.)*

## More Information

- [ADR-0019](0019-human-in-the-loop-questions.md) — HITL questions;
  `dispatch_bound` diverges from its per-process lifecycle.
- [ADR-0020](0020-durable-state-in-sqlite.md) — the SQLite store this
  extends with the `dispatches` table (schema v2) and the
  `questions.dispatch_bound` column.
- [`app/SERVER.md`](../../app/SERVER.md) `server.dispatch` — config reference.
- [`app/AGENT.md`](../../app/AGENT.md) "Ask-and-dispatch" — usage from
  agent commands.

## Amendment (2026-09-30): settled records are pruned after a retention window

Settled dispatches and continuations (ADR-0023 follows this amendment) were never
deleted, so both tables grew without bound and their list endpoints returned every
row. The dispatch and continuation sweeps now delete a row whose status is neither
`PENDING` nor `DISPATCHING` once its `created_at` is older than
`server.dispatch.settledRetentionSec` (default 7 days, minimum 1 hour), together
with its dependent rows in the same transaction: a dispatch's dispatch-bound
question, a join continuation's `continuation_watches`.

- **`created_at` is the anchor.** `FAILED`, `CANCELLED` and `EXPIRED` rows carry no
  settle time, and adding one would need a column and a backfill. A row never
  settles before it is created, so the window is never shorter than intended from
  creation; the cost is that a row that settled late is kept for less than the
  window after settling, which is acceptable for housekeeping. An unsettled row is
  never pruned, however old.
- **Bounded and lock-free when idle.** A `read { }` pre-check on
  `(status, created_at)` keeps an idle tick off the write lock; due rows go in
  write transactions of at most 500.
- **Lists are paginated.** `GET /dispatches` and `GET /continuations` take `limit`
  (default 100, at most 1000) and `offset`, newest first, and return `total`.
  Visibility (ADR-0039's union rule, resolved against the loaded agents as on
  `/status/all`) is applied in SQL before the limit, so neither a page nor `total`
  reveals a row the caller cannot see. This is a breaking change for a client that
  read the whole list.
- **Every filtered page is index-driven.** The predicate is
  `id IN (SELECT id … WHERE owner = ? UNION SELECT id … WHERE agent_name IN (…))`,
  each arm carrying the `status` / `agentName` filters, and the outer read walks that
  id set in primary-key order, so the page itself never sorts. The plain
  `owner = ? OR agent_name IN (…)` form planned as a backward walk of the whole table
  once statistics existed, which for a caller who sees few rows reads every retained
  row to fill a page. The union may sort the ids of its multi-valued arm in a temp
  B-tree; that set is bounded by the caller's visible matching rows. The one table
  walk left is the unfiltered admin page (`ORDER BY id DESC LIMIT/OFFSET` with no
  predicate): it reads exactly offset + limit rows, at most the retained table.
- **Bound.** The retained table is the rows created within the window plus the
  unsettled ones (PENDING capped by `maxPending`). At the default 7 days that is
  about 7 × the daily creation rate: ~7 100 rows at 1 000 a day, 150 000 at ~21 000
  a day. `DatabaseMigrationTest` pins every page and count shape at 150 000 rows,
  with and without planner statistics.
- **Consequence:** a pruned continuation no longer contributes its sub run to a
  LangGraph node's fan-out history (ADR-0044), and a pruned dispatch's id reads as
  not found.
