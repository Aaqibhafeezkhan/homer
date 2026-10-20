---
status: accepted
date: 2026-06-16
deciders: Lukáš Vlk
---
# ADR-0023: Completion continuations — resume a parked workflow when a watched process ends

## Context and Problem Statement

ask-and-dispatch (ADR-0021) and script-driven immediate dispatch (ADR-0022)
let a running command hand work to another agent, but their *trigger* is a
human answering a question or a script calling an endpoint. A parked workflow
— e.g. a LangGraph graph that spawned a Homer sub-run and now needs to resume
when that sub-run *finishes* — has no Homer primitive to wake it on a
sub-process's terminal state. We need a registration whose trigger is "a
watched sub-process reached a terminal status," firing a target agent run that
carries the watched process's result so the graph can resume.

## Decision Drivers

- The parent must **always** wake — on success, failure, kill, or a restart
  that orphaned the run (`UNKNOWN`). A missed completion silently strands the
  graph forever.
- Exactly-once fire: a crash between run-start and the durable mark must not
  double-fire, and concurrent triggers (live hook + boot replay) must collapse
  to one run.
- The trigger must observe a *durable, authoritative* terminal status, never a
  best-effort signal.
- Reuse the dispatch machinery (opt-in gate, CAS state model, SQLite store,
  TTL sweep, boot reconciliation) rather than inventing a parallel one.

## Considered Options

- **Trigger off `ProcessEventBroadcaster`** vs. **trigger off the authoritative
  terminal-status commit in `ProcessExecutor`**.
- **Reuse dispatch's `CANCELLED` for TTL expiry** vs. **a separate `EXPIRED`
  state**.
- **Always inline the watched result** vs. **cap inline size, fall back to a
  reference (`result = null`)**.
- **Constructor-inject `ContinuationService` into `ProcessExecutor`** vs.
  **lazy/provider injection** to break the DI cycle.

## Decision Outcome

Chosen:

1. **Trigger off the authoritative terminal commit, not the broadcaster.**
   `ProcessEventBroadcaster` is `DROP_OLDEST`/lossy (a slow or absent consumer
   drops events), so a completion could be missed — fatal for a wake trigger.
   The hook runs from `ProcessExecutor`'s `finally`, after the terminal status
   is durably committed (CAS for FINISHED, the exception path for FAILED, and
   KILLED preserved by the KILLED-wins invariant), re-reading the actual final
   status via `getProcessStatus` exactly as telemetry does. Hook failures are
   caught and logged so they can never corrupt the process's terminal status.

2. **A separate `EXPIRED` state.** Dispatch reuses `CANCELLED` for both operator
   cancel and TTL lapse; for continuations the two are worth distinguishing
   (an operator cancel vs. "the watched process never finished in time"), so
   TTL sweep moves PENDING → `EXPIRED` and explicit cancel moves PENDING →
   `CANCELLED`.

3. **Inline-result size cap with reference fallback.** The watched process's
   final stdout is run through the optional `resultTransformer` and embedded in
   the resume payload (`{status, processId, result}`). Over 64 KiB, `result` is
   `null` — the parent resumes by reference (it can fetch the artifact) rather
   than inflating the env/payload. *(Superseded in part by
   [ADR-0025](0025-capability-token-result-read.md): the over-cap branch now
   adds a fetchable `resultRef` capability token instead of a bare `processId`.)*

4. **Opt-in via `dispatch.enabled` on the target agent**, the same gate
   ask-and-dispatch uses — no new opt-in surface.

5. **Lazy/provider injection to break the DI cycle.** `ContinuationService` →
   `AgentRunStarter` → `ProcessExecutor` → (terminal hook) →
   `ContinuationService` is a cycle Koin cannot satisfy by constructor
   injection. `ProcessExecutor` takes an `onTerminal: suspend (Int,
   ProcessStatus) -> Unit` callback wired in DI to resolve
   `get<ContinuationService>()` at call time, so construction stays acyclic.

6. **Two distinct param channels on `POST /api/v1/callback/runs`: `params` →
   sub, `continueParams` → continuation.** A `homer-dispatch` call carries two
   audiences of inputs — the dispatched sub-agent and the resumed parent (the
   continuation). The LangGraph runtime's per-interrupt resume needs its
   interrupt id (`HMR_LG_INTERRUPT`) to reach the *parent*, not the sub. Rather
   than special-case that runtime-specific env name inside the generic dispatch
   route (a layering smell — the route would have to know LangGraph's contract),
   the request carries an explicit `continueParams` map (wrapper flag
   `--continue-param`) merged into the continuation's params alongside the
   thread id. The reserved-control-param passthrough then delivers the three
   LangGraph control markers — `HMR_LG_THREAD`, `HMR_LG_RESUME`,
   `HMR_LG_INTERRUPT` — to the resumed run's env. It is a strict allowlist, not
   the whole `HMR_` prefix (`ReservedEnvNames.LANGGRAPH_CONTROL_PARAMS`):
   re-admitting every reserved name would let a callback-API caller set `PATH` /
   `LD_*` in the target's env or spoof `HMR_*` identity. Every other param
   reaches the target only through its declared `inputs`.

### Consequences

- Good: completions are never dropped; the trigger is as reliable as the
  process status itself.
- Good: exactly-once fire via the PENDING→DISPATCHING claim CAS, identical to
  dispatch; boot reconciliation (fail orphaned DISPATCHING, re-fire
  already-terminal PENDING) makes it crash-safe.
- Good: reuses dispatch's config, store, sweep, and reconciliation patterns,
  so the surface a maintainer must learn is small.
- Bad: the hook runs inline in the executor's `finally`; a slow continuation
  lookup/fire adds latency to job teardown (mitigated — failures are swallowed,
  the heavy run-start is the same path dispatch already uses).
- Bad: a watched result over the 64 KiB cap is not delivered inline; the parent
  must fetch it out of band.

### Later refinement — transient fire failures retry

"The parent must always wake" held for the *trigger*, but not for `fire()`
itself. A fire could end `FAILED` without ever starting the target for reasons
that had nothing to do with the continuation — the target agent over its cost
cap, a full process table, a full run queue — and the only trace was a `WARN`
line. Agents that use continuations as their crash-reporting channel would
lose the report of the crash.
*(Amended 2026-10-09: a full run queue never reaches `fire()` — `maxQueuedRuns`
is enforced inside the already-launched target run, which ends `FAILED` like any
other run, so only the cost cap, the cooldown and a full process table are
retried. The `MaxQueuedRunsExceededException` arm of `isTransient` is
unreachable; removing it is an open follow-up.)*

Those three rejections are now treated as **transient**: the row goes back to
`PENDING` with the attempt counted (`fire_attempts`) and the next attempt
scheduled (`next_attempt_at`, doubling from 60 s up to 1 h), and
`ContinuationSweepService` re-fires it on the existing sweep tick. Retries are
bounded twice — by an 8-attempt budget and by the continuation's own TTL, which
is checked first so a lapsed row expires rather than spending another attempt.

Everything else stays permanent and still fails on the first attempt: target
agent gone, no longer dispatch-enabled, hop unauthorized, input validation
rejected. Retrying those would only burn sweep cycles. `failOrphanedDispatching`
is likewise unchanged — a restart mid-`DISPATCHING` may already have started the
target, so re-firing risks a duplicate run.

## Pros and Cons of the Options

### Trigger source

- **Broadcaster** — Good: already fans out process events. Bad: `DROP_OLDEST`
  means a completion can be silently dropped; unacceptable for a wake trigger.
- **Authoritative terminal commit** — Good: as reliable as the durable status.
  Neutral: couples the executor to a post-construction callback.

### Inline result

- **Always inline** — Good: simplest. Bad: an unbounded stdout bloats the resume
  env and payload.
- **Cap + reference fallback** — Good: bounded payload. Neutral: large results
  need an out-of-band fetch.

## More Information

Builds on ADR-0020 (durable state in SQLite — `continuations` table at schema
v3), ADR-0021 (ask-and-dispatch — the CAS state model, claim/fire/sweep/boot
patterns, and `dispatch.enabled` gate this mirrors), and ADR-0022 (decoupled
dispatch). HTTP endpoints and the `homer-dispatch.sh` surface that register and
inspect continuations ship in a later commit.

## Amendment (2026-09-30): settled continuations are pruned

Settled continuations (every status but `PENDING` and `DISPATCHING`) are deleted,
with their `continuation_watches` rows, once their `created_at` is older than
`server.dispatch.settledRetentionSec`, and the list endpoint is paginated. The rule,
its `created_at` anchor and its consequences are recorded once, in
[ADR-0021's 2026-09-30 amendment](0021-ask-and-dispatch.md#amendment-2026-09-30-settled-records-are-pruned-after-a-retention-window).
