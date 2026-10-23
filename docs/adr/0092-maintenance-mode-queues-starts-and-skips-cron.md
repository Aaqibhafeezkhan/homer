---
status: proposed
date: 2026-10-23
deciders: Lukáš Vlk
consulted: ADR-0020, ADR-0047, ADR-0066, ADR-0080, ADR-0083
---

# ADR-0092: Maintenance mode queues new starts and skips cron, above the claim queue

## Context and Problem Statement

An operator replacing Homer has no way to stop it from taking on new work while
the runs already in flight finish. Restarting is not the same thing: the drain
on `SIGTERM` bounds how long a shutdown waits, it does not stop the server from
accepting starts in the meantime, and a rolling replacement gives no window at
all. Disconnecting the runners does not solve it either. The built-in `local`
runner cannot be disconnected, an `ssh` or `kubernetes` runner is configuration
rather than a connection, and a start that finds no runner does not fail — it
waits in the claim queue and only then fails, after `runnerClaimTimeoutSec`
(ADR-0066).

So the problem is how to hold back every *new* start — HTTP/API, MCP, a
`!<agent>` hop, a dispatch, a completion continuation — without touching the
runs already executing, and without losing the starts that arrive while the
window is open.

## Decision Drivers

- Nearly every entry point already converges on one function,
  `AgentRunStarter.start`, so a gate there covers them without each caller
  growing its own check; the three replay/debug handlers that build their
  process directly take the same gate.
- Cron is the exception that is not a caller: `CronSchedulerService.tick` decides
  on its own that a run should exist, so it can be told to decide otherwise.
- Root §12 rules 4 and 5: a queue a caller can multiply is bounded locally and
  explicitly, the wait is bounded too, a surplus caller is shed rather than
  accumulated, and a shed response is never cached.
- The state has to survive a restart — a maintenance window spans the very
  restart it exists for.
- The claim queue stays the single execution path (ADR-0066). Maintenance mode
  is an admission decision, not a second queue of runs.
- Operator surface follows the existing admin-only control endpoint (ADR-0080):
  one small handler, `authorizer.isAdmin` first, the same `404` a denied
  resource gets, and nothing cached.

## Considered Options

- **A. An admission gate inside `AgentRunStarter.start`, above the claim queue,
  plus a cron skip.**
- **B. Stop or disconnect the runners for the duration of the window.**
- **C. Reuse the claim queue's own bound (`maxPendingRunnerClaims`) as the
  queue.**
- **D. Refuse every start immediately with `503` — no queue at all.**
- **E. Queue cron fires like any other start, so nothing is lost.**

## Decision Outcome

Chosen option: **"A"**, because it is the only option that both stops new work
and keeps the starts that arrive during the window, and because the single
convergence point already exists.

- **The gate.** `MaintenanceModeService.awaitRelease(owner)` is called inside
  `AgentRunStarter.start`'s span, **before** the pre-flight checks. A start that
  is parked therefore re-runs the cycle, depth, cost-cap and cooldown checks and
  resolves its inputs **at dequeue, not at enqueue**: a cost cap that was reached
  or a cooldown that started while the run waited is seen, and the run is refused
  then. Nothing is created while parked — a shed start leaves no process row.
  Retry, resume and debug build their process directly instead of going through
  the starter, so each takes the same gate at the same point — after its
  authorization and body validation, before its pre-flight and input resolution,
  inside its own root span.
- **The queue.** Bounded in total (`maxQueuedStarts`, 500) and per principal
  (`maxQueuedStartsPerPrincipal`, 50), so one caller cannot fill the queue for
  everyone; the wait is bounded (`queueWaitSec`, 600). A surplus start is shed
  with `429` (`MaintenanceQueueFullException`), an expired wait with `503`
  (`MaintenanceWaitTimeoutException`), both mapped in the sealed `when` of
  `homerExceptionStatus` (ADR-0002) and neither names an input value. The bounds are `server.maintenance` config keys rather than
  constants, because the wait has to outlive an operator's deployment window.
- **The release.** Waiters are released in arrival order within one release
  drain, at most
  `releaseConcurrency` (4) at a time, and a release slot is held until the
  released start's run exists — not merely until its caller wakes. That is what
  keeps the end of the window from handing the runners one burst the size of the
  whole queue. If the mode goes on again mid-drain, the rest stay queued. Two
  rapid toggles-off launch overlapping drains: the order across drains is then
  scheduling, not arrival — the guarantee is the per-drain order, and every
  waiter is still handed out exactly once.
- **Cron.** `CronSchedulerService.tick` filters out every agent and logs one skip
  line while the mode is on. A schedule missed during the window is **not
  replayed** when it ends: a fire is "run this now", and running a day's worth of
  missed ticks at the moment the operator brings the server back is the burst the
  window exists to avoid. `fireNow` is untouched, so the console's Run button
  queues like any other manual start — an operator who wants that run asks for it.
- **Persistence.** The flag is one more key in `server_meta`
  (`maintenance_mode`, already key/value, v9), read blocking at boot — a strict
  `"true"`/`"false"` parse, a corrupt value logs a warning and reads as off — and
  written on the live toggle path through `DatabaseService.query { }`. The live
  *read* is the service's in-memory flag, so a health poll costs no database
  work. No DDL, so no `SCHEMA_VERSION` bump (ADR-0020).
- **The API.** `GET`/`POST /api/v1/maintenance`, admin-only, `authorizer.isAdmin`
  first in the handler, body parsed only after the gate, `Cache-Control: no-store`
  on both responses. A denial is a body-less `404`, byte-identical to an unmounted
  path, so a caller cannot tell a denied handler from a missing route (root §12
  rule 3). `GET /api/v1/health` carries the flag from the service's in-memory
  state, so the console's banner costs no database read per poll.
- **Running work.** Nothing in this path reads or writes a process row: a run
  already `WORKING` is untouched, and a run in the claim queue is not recalled.

**Why not the others:**

- **B** cannot be done for the `local` runner, and for `ssh`/`kubernetes` it means
  editing config during an incident. It also leaves the failure mode the ticket
  describes: a start with no reachable runner waits, then fails late.
- **C** couples two different resources. `maxPendingRunnerClaims` bounds claims
  against a runner; a maintenance queue holds starts that have no runner yet. One
  bound would have to be wrong for one of them, and ADR-0066's queue stays the
  single execution path.
- **D** loses the work an operator was in the middle of asking for, and turns a
  planned window into a batch of failed requests to retry by hand.
- **E** replays the whole window at its end. The missed ticks are exactly the
  burst the window exists to prevent.

### Confirmation

Each constraint has a test that asserts the denial path (root §12 rule 8):

- a start while the mode is on parks, and runs only after it goes off — for
  retry, resume and debug too, the three start paths that do not go through
  `AgentRunStarter`;
- a cost-capped or cooldown-blocked start is refused **at dequeue**: the preflight
  is verified to have run zero times while parked;
- a shed start creates no process row, and neither shed nor expiry puts an input
  value in the error text;
- the total cap sheds and the per-principal cap sheds one principal without
  blocking another;
- the wait expires, removes the entry and throws;
- release order is arrival order within one drain (overlapping drains after
  rapid re-toggles are only asserted to hand every waiter out exactly once),
  and release keeps at most `releaseConcurrency`
  starts in flight;
- `tick` fires nothing while the mode is on, nothing is replayed when it goes
  off, and `fireNow` still reaches the start path;
- a non-admin `GET` and `POST` both get the same `404` as an unknown resource,
  with the state unchanged, and a malformed body from a non-admin gets that `404`
  rather than a `400` — the gate runs before the parse;
- a run already `WORKING` is still `WORKING` after the mode goes on;
- the flag survives a reopened database, and a corrupt stored value reads as off
  and is logged.

### Consequences

- Good: an operator can hold Homer still for a restart without killing anything
  and without losing the starts that arrive meanwhile.
- Good: one gate covers every entry point, because they all already converge on
  `AgentRunStarter.start`.
- Good: the bounds are explicit and local, and both shed paths are the same shape
  as the claim queue's.
- Bad: a queued start's caller holds a connection for up to `queueWaitSec` and
  then may be told `503`. The queue absorbs a restart window, not an outage.
- Bad: a cron schedule missed during the window is lost. An operator who needs
  that run fires it by hand.
- Bad: the release drain is in-process state, so it does not survive a restart
  either. On boot the flag is restored and new starts park again; the starts that
  were parked when the process died are gone with it, and their callers saw the
  connection close.

## Pros and Cons of the Options

### Option A — Admission gate plus cron skip

- Good: keeps the arriving work, in arrival order within one release drain, with
  the queue bounded and the
  surplus shed.
- Good: re-running the preflight at dequeue means the run is checked against the
  state it will actually execute in.
- Neutral: two shed errors instead of one, because "the queue is full" and "the
  wait ran out" are different answers for the caller.
- Bad: a held connection per parked caller.

### Option B — Stop or disconnect the runners

- Good: nothing to build in the server.
- Bad: impossible for the built-in `local` runner, and config surgery for the
  others.
- Bad: does not stop a start from being created; it just moves the failure to
  `runnerClaimTimeoutSec` later.

### Option C — Reuse the claim queue's bound

- Good: one queue to reason about.
- Bad: the two bounds answer different questions and cannot share a number.
- Bad: puts an admission decision inside the execution path ADR-0066 keeps
  single-purpose.

### Option D — Refuse immediately, no queue

- Good: trivial, and every caller gets its answer at once.
- Bad: loses work that was already asked for, and the caller has to guess when to
  retry.
- Bad: no way to say "the window is nearly over" — every retry in the window
  fails the same way.

### Option E — Queue cron fires too

- Good: no schedule is ever missed.
- Bad: the end of the window fires the entire backlog at once, into runners that
  have just come back.
- Bad: a fire is a point in time, not a unit of work to be deferred; deferring it
  changes what the schedule means.

## More Information

- [ADR-0047](0047-security-by-design-and-threat-model.md) — rules 3–5 and 8: the
  denial shape, the local and explicit bound, the bounded wait that sheds, and
  the denial test for each.
- [ADR-0066](0066-external-pull-runners-and-unified-claim-queue.md) — the claim queue this gate sits
  above. This ADR does not amend it.
- [ADR-0080](0080-manual-cron-fire-endpoint.md) — the admin-only, body-less
  control endpoint this one is shaped after.
- [ADR-0020](0020-durable-state-in-sqlite.md) — why the flag is a key in `server_meta`
  and not a table.
- [ADR-0083](0083-backend-layers-through-ports-and-packages.md) — the Services
  layer `MaintenanceModeService` belongs to.
