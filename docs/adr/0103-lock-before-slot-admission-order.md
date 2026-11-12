---
status: proposed
date: 2026-11-12
deciders: Lukáš Vlk
consulted: ADR-0002, ADR-0029, ADR-0047, ADR-0051, ADR-0098
---

# ADR-0103: A run takes its lock before its parallel-run slot, and the lock-wait queue is bounded

## Context and Problem Statement

`Process.execute` took the agent's `maxParallelRuns` slot first and waited for the workspace
lock second, holding the slot for the whole wait. With a per-subject lock key and
`onCollision: WAIT` — the shape a "one run per merge request" agent uses — every run queued
behind one subject's lock held an agent-wide slot. With `maxParallelRuns: 2`, lock key
`mr-{{MR}}`, two runs on `mr=1` and one on `mr=2`: run 1 executed, run 2 held the second slot
while it waited for the `mr-1` lock, and run 3 (`mr=2`, whose lock nobody else wanted) waited
for a slot until both `mr=1` runs had finished. One busy subject starved every other subject,
which defeats the point of a per-subject lock.

Swapping the two is not enough on its own, because the order is what each `onCollision` value
means:

- `KILL_OLD_RUN_CURRENT` killed the previous holder **when the new run took the lock**. With
  the lock first, a new run would kill the old run and only then wait for a slot — the work is
  lost with nothing running in its place.
- `SKIP_CURRENT` failed the new run after it had waited for a slot; with the lock first it
  fails at once, which moves when its `FAILED` row appears.
- `WAIT` parked the new run on the slot, so moving it off the slot queue removes the only
  bound its wait had. `maxParallelRuns` bounded it implicitly (each waiter held a slot);
  without a replacement bound, runs waiting for a lock accumulate without limit — the
  unbounded queue ADR-0047 rules 4 and 5 forbid.

## Decision Drivers

- `maxParallelRuns` means "runs that are actually executing". A run parked on another run's
  subject lock is not executing, and charging it a slot is what starves the other subjects.
- A `KILL_OLD_RUN_CURRENT` kill must stay tied to the moment the new run can actually run:
  a kill that lands before the replacement holds a slot trades a running run for a parked
  one, and the work the victim had already done is lost.
- Every bound creates a queue whose wait must itself be bounded and shed, never accumulated
  (ADR-0047 rule 5). Moving waiters off the slot queue is exactly the change that can turn a
  bounded wait into an unbounded one, so the replacement bound has to be local and explicit
  (rule 4) — and proven by a denial test (rule 8).
- No new agent-schema key: the bound is derived from what the agent already declares
  (`maxQueuedRuns`, `maxParallelRuns`), so no `Agent.kt` / schema / `AGENT.md` field changes.
- A run refused before it started must read as such: a stored pre-start failure reason
  (ADR-0098) that names the agent and the bound, never the lock key or an input value
  (ADR-0047 rule 7), and that keeps the row out of `cooldown.afterFailure` (ADR-0051).
- The refusal reaches the caller through the existing async contract: the start still answers
  `200` and the run ends `FAILED`, mapped by the sealed `when` in `homerExceptionStatus`
  (ADR-0002).

## Considered Options

What a run takes first:

- **A. The lock, then the slot** — a run waiting for another run's subject lock holds no
  agent-wide slot.
- **B. The slot, then the lock** (the status quo) — simple, and every waiter is implicitly
  bounded by the slot queue, at the cost of one busy subject starving the rest.
- **C. The lock, then the slot, with the lock released while the slot is waited for** — the
  subject lock would no longer be held by a run that is about to execute, so two runs on one
  subject could both pass the lock and then run concurrently.

How the `KILL_OLD_RUN_CURRENT` kill is timed:

- **A. Decide on the lock, kill after the slot is held** — the victim dies only once its
  replacement can run.
- **B. Kill as soon as the lock is taken** — the new run may then be refused its slot, having
  killed a run it cannot replace.
- **C. Kill before taking the slot, and give the slot back if the kill is refused** — the same
  lost work as B, with extra states to unwind.

How runs waiting for a lock are bounded:

- **A. Share the slot queue's counter when `maxQueuedRuns` is set, else cap them at
  `maxParallelRuns`** — one combined cap where the operator declared one, and otherwise the
  worst case the old order allowed.
- **B. A new `maxLockWaiters` agent field** — explicit, but a new config key, a schema change
  and a default that must be chosen for every existing agent.
- **C. A time bound on the lock wait** — needs a new key too, and how long a holder runs is
  already bounded by the run's own TTLs and the lock's own strategies; a timeout would fail
  runs that were about to be admitted.

## Decision Outcome

Chosen: **A** (lock first), **A** (kill after the slot), **A** (the derived bound).

- **The admission order.** `Process.execute` resolves the workspace config, then — unless the
  run inherits an ancestor's lock (`lockStrategy.acceptParentLock`, unchanged) — takes the
  lock, then the slot, then runs the deferred kill, then moves the run to `WORKING` and
  executes. `tearDown` releases exactly what was taken: the lock key when the lock was
  acquired here, and the slot when the slot was acquired. A run that fails its slot attempt
  therefore still releases the lock it took.
- **`KILL_OLD_RUN_CURRENT` is split into decide and kill.** `LockManager.acquireLock` decides
  the collision under its mutex — for this strategy it registers the new holder and returns the
  displaced run's id in the `LockGrant` it hands back — and `killDisplaced` performs the kill.
  `Process` calls `killDisplaced` only after `acquireSlot` has returned, so a refused slot
  attempt leaves the victim running and the lock is given back by `tearDown`. A lock that was
  free grants no victim, and `killDisplaced` is then a no-op. `waitForLockOrThrow` stays as
  acquire-plus-immediate-kill for its existing callers.
- **`WAIT` holds no slot while it waits.** The run parks in `LockManager`'s per-key queue,
  which blocks only the runs that want that same subject. Its own bound is taken through the
  new `ParallelRunsManager.enterLockWait`: a non-suspending ticket taken **inside** the lock
  manager's mutex, before the run is enqueued, and closed when the wait ends however it ends
  (granted, cancelled or killed). A refused ticket means the run is never enqueued, so no
  queue entry is left behind.
- **The lock-wait bound is derived, never a new key.** Under `onMaxParallelRuns: WAIT` with
  `maxQueuedRuns` set, lock waiters share the slot queue's own counter, so one operator-declared
  cap covers both queues and the combined number of parked runs cannot exceed it. Otherwise —
  `maxQueuedRuns` null, or `onMaxParallelRuns: REJECT`, where `maxQueuedRuns` has no meaning —
  a per-agent lock-waiter counter caps them at `maxParallelRuns`, which is exactly the number
  of lock waiters the old order could park (each held a slot). The surplus is refused with the
  new `MaxLockWaitersExceededException` (or the existing `MaxQueuedRunsExceededException` when
  the cap is the shared one), mapped to `429` beside its siblings (ADR-0002), classified
  transient alongside the run queue (ADR-0023, whose amendment records that neither arm can
  reach a fire), and stored as a pre-start failure reason naming only the agent and the bound
  (ADR-0098, ADR-0047 rule 7). Both counters are dropped on `AgentsService.reload()`, like the
  slot semaphores.
- **`SKIP_CURRENT` decides at the lock.** It fails with `AnotherProcessStillActive` before any
  slot is taken, which is the same refusal as before at an earlier moment: the run's `FAILED`
  row now appears without the slot wait in front of it. Its holder id still travels as its own
  field, so the API can withhold it from a caller who may not see that run (ADR-0058).
- **No time bound on the lock wait.** A wait is bounded by how many runs may wait (above) and
  by the holder finishing, which the holder's own TTLs, drain and kill paths bound. A timeout
  would need a new key and would fail runs that were about to be admitted, turning a queue
  into a source of lost work.
- **Fairness between subjects changes, deliberately.** Runs are admitted in the order they
  arrive at their own subject's lock, so a subject whose lock is free runs without waiting for
  another subject's backlog. Within one subject the order is the arrival order at that key.
  The cost is that two runs of one agent on different subjects can now execute concurrently
  where the old order serialized them behind the slot queue — that is the intent of
  `maxParallelRuns`, which bounds *executing* runs, not *queued* ones.

**Why not the others:**

- **B** is the bug: a lock wait charged to an agent-wide resource.
- **C** trades a starvation bug for a correctness bug — the lock is released before the run it
  protects has started, so the mutual exclusion it exists for is gone.
- **Kill as soon as the lock is taken** (B) and **kill then unwind** (C) both let a run
  destroy its predecessor and then fail to replace it: the victim dies for a replacement
  that never runs.
- **A new `maxLockWaiters` key** (B) is surface for a bound that `maxParallelRuns` already
  expresses, and it would need a default chosen for every existing agent — including agents
  that never collide.
- **A time bound** (C) is the wrong shape: the wait it would cut short is the one an operator
  asked for with `WAIT`.

### Confirmation

Each constraint has a test that asserts the denial path (ADR-0047 rule 8):

- the ticket scenario: with `maxParallelRuns: 2`, key `mr-{{MR}}` and `WAIT`, the run on `mr=2`
  reaches `WORKING` while the second `mr=1` run is still waiting for the `mr-1` lock;
- `maxParallelRuns` still bounds the runs in `WORKING`: contending runs never exceed it;
- a `WAIT` collision takes no slot while it waits, and takes one once the lock is granted;
- a `KILL_OLD_RUN_CURRENT` victim is alive while the new run waits for a slot, is killed exactly
  once when the slot is held, and is not killed when the slot attempt is refused (which also
  releases the lock again);
- `SKIP_CURRENT` fails on the lock before any slot is taken;
- a surplus lock waiter is refused, the run ends `FAILED` with a stored pre-start reason naming
  the agent and the bound, no cooldown is armed, and the start itself still answers `200`;
- the reason carries no lock key or input value, and the property test over mapped reasons
  covers the new exception;
- a ticket refused by the gate leaves no queue entry behind, and a ticket is freed on grant, on
  cancellation and on a kill;
- `close()` on a ticket is idempotent, and a reload does not misdirect the decrement;
- the combined cap holds: lock waiters plus slot waiters never exceed `maxQueuedRuns`, and the
  fallback cap is `maxParallelRuns` when `maxQueuedRuns` is null or the strategy is `REJECT`.

### Consequences

- Good: one busy subject no longer starves the agent's other subjects — the bug this ADR
  settles.
- Good: `maxParallelRuns` now means what it says: the number of runs executing. The agent's
  total parked work is still bounded, once by the slot queue's cap and once by the lock-wait
  cap.
- Good: `KILL_OLD_RUN_CURRENT` can no longer lose work: a victim dies only when its
  replacement holds a slot.
- Bad: **a run refused its slot now holds the lock while it is refused**, so a
  `REJECT`-configured agent's refusal momentarily blocks its own subject. `tearDown` releases
  it, and the alternative — checking the slot first — is the bug being fixed.
- Bad: **two runs of one agent on different subjects can execute concurrently** where the old
  order queued the second behind the first's slot. This is the intended behaviour, but an
  agent whose `maxParallelRuns` was effectively also serializing its subjects will now see more
  concurrency, and more concurrent load on whatever it calls.
- Bad: **more lock waiters can be parked at once** in the fallback case (up to
  `maxParallelRuns` per agent, as before) — but they are parked on a per-key queue rather than
  in slots, so the number of *executing* runs is unchanged.
- Neutral: no new config key, no schema change, and no change to lock-key rendering.
- Neutral: the lock-wait bound is per agent and in memory, like the slot semaphores; a
  multi-node deployment keeps one bound per node (as it already does for `maxParallelRuns`).

## More Information

- [ADR-0029](0029-flow-scoped-lock-passthrough.md) — the flow-scoped lock pass-through (its
  `inheritLock` skip is unchanged here) and its amendment recording the slot limiter's
  `maxQueuedRuns`; that ADR's decision point 5 also recorded the slot-before-lock order this
  ADR reverses, so it carries an amendment note pointing here.
- [ADR-0047](0047-security-by-design-and-threat-model.md) — rules 4, 5, 7 and 8: the local
  explicit bound, the bounded and shed wait, the refusal that names no input value, and the
  denial tests above.
- [ADR-0098](0098-pre-start-failure-reason-is-a-fixed-vocabulary.md) — the stored reason
  vocabulary the new refusal joins.
- [ADR-0002](0002-unified-error-model-at-http-boundary.md) — the sealed `when` that maps the new
  exception to `429`.
- [ADR-0051](0051-cooldown-is-enforced-in-the-insert-transaction.md) — why a stored pre-start
  reason keeps the refusal out of the `afterFailure` window.
- [ADR-0058](0058-parent-child-run-linkage.md) — the lock holder travelling as its own field
  rather than inside the reason.
