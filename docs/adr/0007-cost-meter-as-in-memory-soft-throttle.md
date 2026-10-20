---
status: accepted
date: 2026-05-06
deciders: Lukáš Vlk
---
# ADR-0007: `CostMeter` as an in-memory soft throttle, enforced at the HTTP boundary

> **Note:** `ClaudeExecutor`, named below, was replaced by the harness-generic
> `LlmHarnessExecutor` in [ADR-0070](0070-llm-harness-adapter-boundary.md).
>
> **Amendment (2026-10-09) — storage superseded by [ADR-0035](0035-sqlite-backed-cost-ledger.md).**
> The windows now live in the SQLite `cost_entries` ledger and survive restart.
> The boundary check, `current >= cap` rule and soft-throttle semantics still hold.

## Context and Problem Statement

Homer launches `claude` commands on behalf of agents. Until this
decision, the only spend bound was the per-command `--max-budget-usd`
CLI flag, which caps a single Claude run. A cron agent firing every
minute, or a bursty external trigger, could rack up arbitrary
aggregate spend across many runs before anyone noticed. Operators
needed an aggregate ceiling — a way to say "no more than $X / minute
across every agent" and "no more than $Y / day for agent Z" — without
turning Homer into a ledger / billing service.

The decision is about the *enforcement model*, not the existence of
caps: where the check fires, where state lives, and what guarantees
the throttle does and does not provide.

## Decision Drivers

- **Single-node by design.** Homer is intentionally not distributed
  (memory: `project_single_node_only`). A persistent cost ledger or
  cross-node coordinator would push us off that property for marginal
  gain.
- **The HTTP boundary is the only place a refusal can return a useful
  status code.** Most `HomerException`s are thrown inside
  `ProcessExecutor.coroutineScope.launch { … }` and never reach
  `StatusPages` (see [ADR-0002](0002-unified-error-model-at-http-boundary.md));
  a check buried in `ClaudeExecutor` would see the exception swallowed
  into a `FAILED` ProcessInfo with no visible 429.
- **Soft-throttle semantics are good enough for cost.** A few cents
  of overshoot under concurrent burst is acceptable; the consequence
  is "operator's budget alarm fires a few seconds later", not data
  corruption.
- **No reservation, no global lock.** Claude calls take seconds-to-
  minutes; reserving budget at `checkCaps` time and refunding on
  failure would add a per-run state machine for negligible benefit.

## Considered Options

- **Option A — In-memory rolling window, checked at request boundary,
  not reserved.** Two `ArrayDeque`s (60 s global, 24 h per-agent),
  pruned on read; `checkCaps` refuses if the current sum already
  meets-or-exceeds the cap; `recordCost` charges on success.
- **Option B — Reserved-budget ledger.** Hold a slot at `checkCaps`,
  release on success/failure. Stronger guarantees (no overshoot under
  concurrency) at the cost of a per-run state machine and a leak risk
  if a release path is missed.
- **Option C — Persisted ledger (SQLite or external store).** Survives
  restart; can be inspected; can scale across nodes. Pulls in storage,
  schema migration, and recovery logic that Homer otherwise does not
  need.
- **Option D — Defer to upstream / external rate limiter.** Push the
  problem onto API gateway quotas or a Redis-backed sidecar.

## Decision Outcome

Chosen option: **"Option A — In-memory rolling window, checked at
the request boundary, not reserved."**

Two rationales beat the alternatives:

1. **Boundary check is non-negotiable.** Per ADR-0002, exceptions
   inside the async launch path do not reach the HTTP handler. The
   cost cap is enforced in `handleAgentExecution` /
   `handleRetry` *before* `ProcessExecutor.execute` is called, so a
   refusal returns `429 Too Many Requests` (new
   `CostCapExceededException`) and consumes no `ProcessManager` slot
   and no LLM tokens. `ClaudeExecutor` no longer calls `checkCaps` at
   all — it only `recordCost`s on success. There is exactly one place
   the cap fires.
2. **In-memory is consistent with the rest of the architecture.** No
   other Homer state survives restart by default
   (`ProcessManager`, `LockManager`, `ParallelRunsManager` are all
   in-memory). Adding a persistent store for cost specifically would
   create a new operational surface for a soft throttle whose worst
   failure mode is "alarm fires a few seconds later". *(Superseded by
   ADR-0035: cost state is persisted.)*

The check is `current >= cap` (not `current + estimate >= cap`)
because the per-run cost is unknown until Claude responds; checking
the *already-spent* total keeps the meter monotonically defensible
("we never let it go higher than the cap once we know we are at the
cap"). Concurrent callers can all pass `checkCaps` and then push the
window over together; this is documented in `CostMeter.checkCaps`'s
KDoc as soft-throttle semantics.

### Consequences

- Good: refusals return `429` synchronously, before any process
  starts, so clients get clean backpressure they can rate-limit
  against.
- Good: zero new persistence — restart resets the meter, matching the
  rest of Homer's runtime state. *(Superseded by ADR-0035: caps survive restart.)*
- Good: `GET /api/v1/costs` snapshot is a cheap mutex-guarded read of
  the same windows the check uses, so the console UI shows the same
  numbers the throttle sees.
- Good: `ClaudeExecutor.runWithRetry` only records cost; the executor
  has no opinion on caps. One source of truth.
- Bad: under concurrent burst, the rolling window can overshoot the
  cap by up to N×$cost where N is the number of in-flight requests
  that all passed the check together. Acceptable for soft throttle;
  not acceptable if Homer ever needs to enforce a hard contractual
  limit (would require Option B).
- Bad: state is lost on restart. A cron agent that runs at minute 0
  and exhausts the daily cap will get a fresh budget on a server
  restart. We accept this in exchange for not running a database.
  *(Superseded by ADR-0035: caps survive restart.)*
- Bad: cross-node deployments (which Homer does not support today)
  would each have their own meter. Documented in CHANGELOG and
  `SERVER.md` as "single-node soft throttle by design".

## Pros and Cons of the Options

### Option A — In-memory rolling window, checked at the boundary

- Good: matches the rest of Homer's stateless-runtime architecture.
- Good: refusal hits the HTTP 429 path; ADR-0002 boundary is honored.
- Good: cheap, observable via `GET /api/v1/costs`.
- Neutral: window pruning is O(n) on read; n is bounded by traffic in
  the window so this stays sub-millisecond in practice.
- Bad: soft-throttle only — concurrent overshoot possible.

### Option B — Reserved-budget ledger

- Good: hard cap, no overshoot.
- Bad: per-run state machine, leak risk on missed release paths,
  much larger blast radius if a bug holds budget.

### Option C — Persisted ledger

- Good: survives restart; auditable; scales across nodes.
- Bad: storage layer, schema migrations, recovery logic — all for a
  soft throttle. Pulls Homer's operational surface in a direction
  ADR-0001 is meant to push back against.

### Option D — External rate limiter

- Good: no Homer-side state at all.
- Bad: not all deployments have one; Homer should be useful out of
  the box; cost is per-Claude-run not per-HTTP-call so a generic
  gateway quota is the wrong granularity.

## More Information

- Service: [`CostMeter`](../../app/app/src/main/kotlin/homer/app/services/CostMeter.kt).
- HTTP refusal path: `handleAgentExecution` / `handleRetry` in
  [`Application.kt`](../../app/app/src/main/kotlin/homer/app/Application.kt).
- Snapshot API: `GET /api/v1/costs`.
- Console UI: `console/app/(dashboard)/costs/page.tsx`.
- Related ADRs: [ADR-0002](0002-unified-error-model-at-http-boundary.md)
  for why the boundary matters.
