---
status: proposed
date: 2026-11-12
deciders: Lukáš Vlk
consulted: ADR-0020, ADR-0041, ADR-0047, ADR-0066, ADR-0089, ADR-0100
---

# ADR-0108: A run can be stopped gracefully — opt-in, SIGINT before SIGKILL, and `FAILED` plus a `stop` record

## Context and Problem Statement

Every way a run ends today ends it abruptly. A step whose `timeout` expires has its process group
SIGKILLed (ADR-0041); a Kubernetes run whose `maxRunSeconds` passes has its pod deleted by the
cluster's own `activeDeadlineSeconds` (ADR-0100). In both cases the agent gets no chance to save
what it was doing: a test report, a partial artifact, a note about where it stopped. Callers who
want a summary of an interrupted run have to reconstruct it from the step records.

An agent author also has no way to say *what should happen when my step runs too long*: `timeout`
fails the run, and that is the only answer. This ADR records the opt-in graceful stop: an agent may
declare steps to run after its run is stopped, a step may ask for a graceful stop instead of a
failure, and a runner may reserve a slice of its run limit for that stop phase.

## Decision Drivers

- The default must not change. Most agents have no stop work to do, and a SIGINT-and-wait in front
  of every timeout would slow down every failure path for no benefit — and would give a hung
  command a second chance to hang again.
- SIGKILL cannot be caught, so it cannot be the only signal: a stop phase is only useful if the
  command being stopped gets a chance to react. SIGINT is the portable "please finish" signal for a
  shell's process group, and `SIGKILL` remains the backstop for a command that ignores it.
- The stop phase runs while the runner's slot, the pod and the workspace are still held, so it is
  the most expensive place in a run to spend time. Both the phase and each step in it need a bound
  (ADR-0047 rule 4).
- A run that was stopped is a run that did not finish. Inventing a seventh `ProcessStatus` for it
  would ripple through every status filter, eviction rule, cooldown check and console badge for a
  distinction the status API can already carry as data.
- The stop environment is derived from attacker-influenced values: a step label written by an agent
  author or a template, and a CLI session id parsed out of a harness's own stdout. It must be
  bounded and validated at the point of use (ADR-0047 rule 2).

## Considered Options

- **No stop phase; document that a killed run saves nothing.**
- **Always graceful**: every timeout and every run-limit kill sends SIGINT first and waits.
- **Opt-in graceful stop** with an agent-level `onStop:` step list, a per-step `onTimeout: stop`,
  a per-step `interruptGraceSeconds`, a Kubernetes `gracefulStopSeconds`, and a `stop` record on
  the run.
- **A new `ProcessStatus.STOPPED`** for a run that was stopped gracefully, instead of `FAILED`
  plus a `stop` record.

## Decision Outcome

Chosen option: **opt-in graceful stop**. Four keys, each defaulting to today's behaviour:

- `onStop: [<commands>]` (agent level) — the steps run once, in order, after the run is stopped.
  Empty (the default) = no stop phase.
- `onTimeout: fail | stop` (per command, default `fail`) — `stop` makes a step's own `timeout` stop
  the run gracefully instead of failing it.
- `interruptGraceSeconds` (per command, default `30`, `0..600`) — how long a stopped command may
  take to exit after SIGINT before SIGKILL.
- `gracefulStopSeconds` (Kubernetes runner, default unset) — the run is stopped this many seconds
  before `maxRunSeconds`.

A run stopped this way ends **`FAILED`**, with a `stop` record: `{reason, stepIndex, stepLabel, at,
stopSteps}` where `reason` is `step_timeout` or `run_deadline` and `stopSteps` is `finished`,
`failed` or `none`. The interrupted step's `ExecutionInfo` carries `stoppedBy` (the same value) and
each stop step's carries `onStop: true` — except on a stop detected between steps (a soft deadline
passing before the next step starts): the record then names the last step whose turn came, which
either completed or was skipped by `when:`; nothing was in flight to interrupt, and that step's row
carries no `stoppedBy` either way. The stop steps receive `HMR_STOP_REASON`,
`HMR_STOP_STEP_INDEX`, `HMR_STOP_STEP_LABEL`, `HMR_STOP_DEADLINE` (only for `run_deadline`) and
`HMR_STOP_SESSION_ID` (only when the interrupted step was an LLM step whose CLI session id parsed
and passed `^[A-Za-z0-9-]{1,128}$`); the label is control-stripped and capped at 256 characters, so
a crafted label cannot forge an env line.

**Why opt-in, and not always graceful.** `interruptGrace` is armed only where a graceful stop is
possible: a step with `onTimeout: stop`, any step of a run whose runner published a soft deadline,
and every stop step. Everywhere else the shell call is exactly what it was, and no SIGINT is ever
sent — a property the tests pin, because "no new keys = byte-identical behaviour" is the whole
reason an operator can adopt this without auditing their agents.

**Why SIGINT before SIGKILL.** A stop phase is worthless if the command it stops is killed outright:
the agent's own cleanup (flush the report, close the file) is what the phase exists for. The grace
is bounded at both ends — `0` means "SIGINT and kill at once", the cap is 600 s because the wait
holds a runner slot (ADR-0047 rule 4) — and the SIGKILL path is unchanged, so a command that
ignores SIGINT ends exactly as it does today, `gracefulStopSeconds` later.

**Why `FAILED` plus a record, not a new status.** Every consumer of `ProcessStatus` — eviction,
cooldown's `afterFailure`, the console's badges, the terminal-set checks in SQL — treats a stopped
run as an unfinished run, which is what it is. A new status would be a new arm in each of those for
no new decision: `stop` already says *why*, and says it in more detail than a status could.

### Consequences

- Good: an agent can declare its own end-of-run work, and an operator can adopt the feature per
  agent, per step and per runner without touching the default paths.
- Good: the interrupted step and every stop step are visible in the status API with the reason, so
  a caller can tell "timed out with no stop work" from "timed out and its stop steps ran".
- Good: the stop phase is bounded twice — the count of stop steps (10) and each step's timeout
  (the remaining deadline minus a 10 s margin, and capped at 300 s) — so it cannot extend a run
  indefinitely. A stop detected between steps names the last step whose turn came in the record —
  it completed or was skipped by `when:`, and nothing was in flight.
- Bad: a stopped run's spend and its stop steps' spend are on the same run record, and the run is
  `FAILED` either way, so a caller who wants to know whether the stop steps did anything must read
  `stop.stopSteps`.
- Bad: the Kubernetes soft deadline needs one extra read of the pod's `startTime` when the pod is
  ready. It is taken once per session, only when `gracefulStopSeconds` is set.
- Bad: a command that catches SIGINT and does *not* exit is worse off than before — it waits out
  the grace before being killed. That is the price of the grace existing at all, and `0` is
  available to anyone who does not want it.

## Pros and Cons of the Options

### Always graceful

- Good: no per-agent opt-in, and every interrupted run gets its cleanup chance.
- Bad: it changes every failure path — every timeout, on every runner — for agents that have no
  stop work to do, and it gives a wedged command the grace period to wedge harder. It also cannot
  work without an `onStop:` list to run, so it would buy a wait and no cleanup.

### A new `ProcessStatus.STOPPED`

- Good: a status filter or a badge could select stopped runs directly.
- Bad: it is a new terminal status, so it must be added to the terminal sets in SQL, to eviction,
  to cooldown's failure window, to startup reconciliation and to the console's status vocabulary —
  all to encode a distinction that `stop.reason` already carries, and that no decision in the
  system branches on differently from `FAILED`.

## More Information

- [`app/AGENT.md` → Graceful stop](../../app/AGENT.md#graceful-stop) — the author-facing contract,
  the `HMR_STOP_*` variables and a complete example.
- [`app/SERVER.md` → Kubernetes](../../app/SERVER.md) — `gracefulStopSeconds` next to
  `maxRunSeconds`.
- [ADR-0041](0041-process-group-reaping-on-kill.md) — the process-group kill the grace precedes;
  `interruptGroup` keeps its `pgid == pid` and never-own-group guards.
- [ADR-0100](0100-kubernetes-job-workload-and-pod-run-limit.md) — `activeDeadlineSeconds` stays the
  hard bound; `gracefulStopSeconds` deliberately never reaches the pod spec.
- [ADR-0047](0047-security-by-design-and-threat-model.md) — the bounds on the grace, the stop-step
  count and the exported label and session id, and the denial tests behind them.
- [ADR-0089](0089-settle-llm-cost-in-the-background.md) — the accounting an interrupted LLM
  attempt keeps, now also recorded on the step it was interrupted in.
