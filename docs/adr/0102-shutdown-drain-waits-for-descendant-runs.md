---
status: proposed
date: 2026-11-10
deciders: Lukáš Vlk
consulted: ADR-0020, ADR-0047, ADR-0058, ADR-0066, ADR-0083, ADR-0092
---

# ADR-0102: The shutdown drain re-reads the run jobs it joins, admits the runs they start, and stops the consumers last

## Context and Problem Statement

`ApplicationStopping` joins the application scope's run jobs within `shutdownDrainSec` and then closes
whatever runner sessions remain. It read that scope **once**. A run that finishes inside
the window starts another one — a `!<agent>` hop returns as soon as its child is started
(`RunAgentExecutor`), a dispatch fires from a running run, a continuation is fired by the run that just
ended — and every one of those is a job the snapshot never saw. They were killed at exit and found
`UNKNOWN` at the next boot.

The runner-consumer loops were cancelled **before** the drain (ADR-0066). A run already waiting for a
static runner's claim at `SIGTERM` therefore could never get one: it burned the drain window and then
ended `UNKNOWN` too.

Together this means a flow of an orchestrator plus children, or a LangGraph run that parks and resumes,
could not be drained by a rolling update at any `shutdownDrainSec`. Operators wrapped rollouts in
maintenance mode plus their own wait-for-idle scripts instead.

## Decision Drivers

- The window is the contract: `server.shutdownDrainSec` bounds the drain, and ADR-0047 rule 5 says a
  bound is never reset per round — a chain of short-lived runs must not hold the shutdown open.
- A start that arrives during the drain and is queued behind a process that is going away can only end
  `UNKNOWN`; the drain must shed rather than accumulate (ADR-0047 rule 5).
- Work the drain is already waiting for must be able to finish, or the drain waits for a parent that is
  blocked on a child it refused.
- The claim queue stays the single execution path (ADR-0066): the fix must not add a second one, nor
  make the drain wait on work no consumer can pick up.
- The maintenance window's admission rule already exists (ADR-0092) and answers exactly the question the
  drain asks — "is this start part of work already admitted?" — so the drain should reuse it rather than
  invent a second one.
- Drain logic stays in `homer.app` (ADR-0083); the Services layer reads the flag through a lambda rather
  than importing the HTTP layer's `AtomicBoolean`.

## Considered Options

How the drain learns about runs started during the drain:

- **A. Re-read the scope in a loop**, ending on two consecutive empty reads, inside one deadline fixed
  at drain start.
- **B. Snapshot the scope once** (the status quo) and accept that descendant runs are lost.
- **C. Re-read the scope with a fresh `withTimeoutOrNull` per round.**
- **D. Stop accepting starts entirely and wait for the scope to be idle**, with no deadline.

What the consumers do:

- **A. Keep them running through the drain**, exclude them from what the drain joins by `Job` identity,
  and stop them right after it.
- **B. Cancel them first** (the status quo).

What a start arriving during the drain gets:

- **A. Admitted when a server-derived parent still reads `WORKING`; refused with `503` otherwise.**
- **B. Parked in the maintenance queue**, exactly as while maintenance mode is on.
- **C. Accepted**, as today.

## Decision Outcome

Chosen: **re-read inside one fixed deadline**, **consumers stopped last**, **the ADR-0092 live-parent
admission plus a `503` refusal**.

- **The loop.** `drainInFlightJobsThenCloseSessions` joins the application scope's children, and when
  the read is empty it polls once more before concluding. Two consecutive empty reads end the drain, so
  a child started by a run that just finished is caught within one poll. The whole loop sits inside a
  single `withTimeoutOrNull(drainTimeout)` opened at drain start and **never reset**, so the deadline is
  the same whether the drain needs one round or twenty. On expiry the drain logs the number of jobs it
  left behind and proceeds to close sessions: the engine stops either way, and the runs it could not
  finish are reconciled to `UNKNOWN` at the next boot as before.
- **The scope-scan invariant.** The drain identifies work structurally: it treats every child of the
  application scope that is not in `excluded` as a run job or a background cost settlement (ADR-0089)
  and joins it. That scan stands in for an explicit run-job registry, and it is only correct while
  every application-scope child **is** a run job, a cost settlement, or one of the excluded
  consumer loops. A future background loop launched on the application scope must either be stopped
  before the drain begins or be added to `excluded` — otherwise it makes every shutdown burn the
  full `shutdownDrainSec` and log a timeout warning for work no run ever owed. An explicit registry
  was weighed and rejected: it is a second place run starts must register, and a start that forgets
  to fails silently in the opposite direction — a run lost at exit instead of a slow shutdown.
- **The consumers.** `RunnerRegistry.consumerJobs()` exposes the consumer and heartbeat-sweeper loops
  `start` launched, and the drain excludes them from what it joins — their `while (isActive)` loops
  never complete on their own, so counting them would make every drain burn its whole budget.
  `RunnerRegistry.stop()` moves to **after** the drain. A run queued for a static runner at `SIGTERM` is
  therefore still claimed, and finishes inside the window. A claim no consumer can serve does not push
  the drain past its deadline: the run is simply one of the jobs the drain times out on.
- **The refusal.** `MaintenanceModeService.gated` refuses an unadmitted start with the new
  `ServerDrainingException` (`503`, beside `MaintenanceWaitTimeoutException` in the sealed `when`,
  ADR-0002) as soon as the drain flag is set, **instead of** parking it. Shed, never queued, and never
  cached: the process is going away, so a parked start could only end `UNKNOWN`, and a `503` tells the
  caller to retry elsewhere. The flag reaches the Services layer as an injected `() -> Boolean`
  (`homer.app.isDraining::get`), keeping `MaintenanceModeService` off the HTTP layer (ADR-0083).
  Because the flag is JVM-global and is never cleared on the way out, `configureServer` clears it
  as it configures an application: a second application in the same JVM would otherwise start up
  already draining, refusing every start and answering `/readyz` with `503`.
- **The admission.** `AgentRunStarter.admittedByLiveParent` now reads the parent row when maintenance
  mode is on **or** the drain is running. The rule is otherwise unchanged from ADR-0092: the flag must
  be set by a path that derived the parent itself (a `!<agent>` hop, a dispatch, a continuation fire),
  the parent row must still read `WORKING`, and a false flag still short-circuits before the read, so a
  caller-supplied parent neither admits a start nor probes a row (ADR-0047 rule 3). A continuation
  whose origin is a fan-out parent still `WORKING` is admitted on exactly this rule; one whose origin
  has already ended is refused, and `ContinuationFireService.isTransient` treats that refusal as
  transient, so the row goes back to `PENDING` for the next boot's sweep rather than settling `FAILED`.
  The answered dispatch fire gets the same treatment: its start refused by the drain releases the
  claim back to `PENDING` with the answer intact, so the next boot's `refirePendingAnswered` fires
  it — settling `FAILED` would consume the operator's answer for a run that never started.
- **The order on `ApplicationStopping`.** The flag flips, the consumer jobs are read, the background
  services stop (cron, maintenance, purge, dispatch sweep, continuation sweep), the drain runs, the
  consumers stop, and the database closes last (ADR-0020). `MaintenanceModeService.stop()` stays before
  the drain, so a start already parked at the gate is failed with its wait-timeout error instead of
  holding a request for the whole window.

**Why not the others:**

- **B** is the bug. The descendants of a run are the normal case in Homer, not an edge case.
- **C** gives a per-round deadline, which is no deadline at all: a chain of short-lived runs — exactly
  what a fan-out produces — resets it forever and the pod is killed by the kubelet mid-write.
- **D** would make a single wedged run block shutdown indefinitely, and it still leaves the run waiting
  on a claim it cannot get.
- **Consumers first** (the status quo) is what made a queued run unreachable. There is no reason to
  cancel a loop whose only cost is a coroutine the drain does not join.
- **Parking** a start during the drain turns a fast `503` into a held connection that ends `UNKNOWN`
  anyway, and it would make the drain wait on work the drain itself refuses to start.

### Confirmation

Each constraint has a test that asserts the denial path (ADR-0047 rule 8):

- a child started by a run that finishes during the drain is joined, as is a job fired from a hook that
  runs after the first join;
- the deadline is not reset per round: two sequential slow runs still end at it, and a run started
  after it is not waited for;
- a never-finishing run ends the drain at the timeout and its session is closed as an orphan;
- the consumer loops are excluded, so an idle drain returns well under the budget;
- a run parked on the claim queue is claimed by a still-running consumer during the drain, and a claim
  no consumer can serve does not push the drain past its deadline;
- `ApplicationStopping` fails the parked maintenance starts first, joins the run jobs, and stops the
  consumers last;
- an unadmitted start during the drain throws `ServerDrainingException` with `queueDepth() == 0` — never
  parked — while an admitted one runs, and the refusal wins when maintenance mode is on too;
- a start whose server-derived parent is still `WORKING` is admitted during the drain; a parent that
  has ended, a caller-supplied parent and a parentless start are all refused, the last two without a
  liveness read;
- `POST /api/v1/agent/{agentName}` answers `503` while the flag is set, and a caller without a `run`
  grant still gets the same `404` it would get with the drain off;
- a continuation fire refused by the drain is released for retry rather than settled `FAILED`, and so
  is an answered dispatch fire refused by the drain.

### Consequences

- Good: a flow of an orchestrator plus children, and a LangGraph run that parks and resumes, now drain
  inside `shutdownDrainSec` — a rolling update no longer needs maintenance mode plus an external
  wait-for-idle script.
- Good: a run queued for a static runner at `SIGTERM` is claimed and finishes instead of burning the
  window and ending `UNKNOWN`.
- Good: one admission rule (ADR-0092's) covers both the maintenance window and the drain, so the two
  cannot drift.
- Bad: **shutdown takes longer** when child or continuation runs are in flight, up to
  `shutdownDrainSec`. A deployment whose `terminationGracePeriodSeconds` sits close to
  `shutdownDrainSec` should re-check the margin.
- Bad: **new top-level starts are refused with `503`** during the drain where they used to be accepted
  and then killed at exit. A caller that treats `503` as fatal now sees the refusal instead of a run
  that ends `UNKNOWN`.
- Neutral: the drain adds up to one `DRAIN_POLL` (50 ms) after the last run finishes, so an idle
  shutdown is marginally slower than before.
- Neutral: a pull runner keeps registering, claiming and reporting during `ApplicationStopping` under
  the existing instance, session and heartbeat validation. The drain adds no new trust and no new
  bypass; it only stops waiting on the consumers later.

## More Information

- [ADR-0092](0092-maintenance-mode-queues-starts-and-skips-cron.md) — the admission rule this reuses,
  and the gate the refusal is added to. This ADR does not amend it.
- [ADR-0066](0066-external-pull-runners-and-unified-claim-queue.md) — the claim queue whose consumers
  now outlive the drain.
- [ADR-0047](0047-security-by-design-and-threat-model.md) — rules 3, 5 and 8: the refusal that names no
  input value, the bound that is not reset and sheds rather than accumulates, and the denial test.
- [ADR-0020](0020-durable-state-in-sqlite.md) — why the database still closes last.
- [ADR-0058](0058-parent-child-run-linkage.md) — the server-derived parent link the admission
  reads.
- [ADR-0083](0083-backend-layers-through-ports-and-packages.md) — the Services layer the flag is
  injected into, and why the drain logic stays in `homer.app`.
