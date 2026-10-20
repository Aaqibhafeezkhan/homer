---
status: accepted
date: 2026-10-07
deciders: Lukáš Vlk
consulted: ADR-0037, ADR-0038, ADR-0047, ADR-0051
---

# ADR-0080: The manual cron-fire endpoint is admin-only, body-less and silent about refusals

## Context and Problem Statement

`/schedules` grew a per-row Run button that fires one agent immediately through
the same path a cron tick uses (`CronSchedulerService.fireAgent`). That needed
a new HTTP endpoint, and three decisions about it were not covered by an
existing ADR: who may call it, what a denial looks like, and what the response
says when the fire starts no run.

## Decision Drivers

- The endpoint starts real runs under a scheduler-owned identity (`system:cron`)
  with no caller-supplied inputs — wider authority than a personal run.
- ADR-0047 rule 3: a single-resource denial hides existence — the same `404`
  as not-found, never `403`.
- ADR-0038/0039: the fired run is indistinguishable from a tick's run (source
  `cron`, owner `system:cron`, authorization riding solely on `agentGrants`).
- `fireAgent` swallows every preflight refusal by design (tick semantics): a
  fire over the cost cap, inside the cooldown, lock- or slot-refused, or one
  whose required inputs cannot be resolved, logs and starts nothing.
  *(Amended 2026-10-09: lock and parallel-run limits are applied inside the
  launched run, not before it — a `SKIP_CURRENT` collision, a `REJECT` cap or a
  full `maxQueuedRuns` queue creates a process that ends `FAILED`, exactly as on
  a tick. Only cost-cap, cooldown, input-resolution and process-capacity
  refusals start nothing.)*

## Considered Options

- Reuse `POST /agent/{name}` with a `cron` flag — entangles the caller-input
  path with tick semantics and would stamp the caller, not `system:cron`, as owner.
- Gate admin but deny non-admins with `403` — betrays the existence-hiding rule
  for the one caller class most able to probe it.
- Return the new process id in the body, and rethrow preflight refusals so the
  caller learns a fire did nothing — makes the endpoint a different fire path
  from a tick, diverging the two behaviors this feature exists to unify.

## Decision Outcome

Chosen option: **a dedicated `POST /api/v1/agents/{name}/cron-run`, admin-only
(handler-level `isAdmin` gate, ADR-0037), body-less `204`, delegating to
`fireNow` → `fireAgent`.** A denial — unknown agent or non-admin — returns the
same `404` as not-found (ADR-0047 rule 3). A fire that starts no run is **not
surfaced**: `fireAgent`'s swallow-and-log behavior is kept as is, so the
endpoint answers `204` whether or not a run started, exactly as a tick does;
the caller learns the outcome from `GET /api/v1/agents`
(`cron.lastRunProcessId`) once the fired run is the agent's most recent.

The interactive caller's silence is accepted deliberately: the console already
guards the click with a confirmation dialog, and the Last-run cell makes a
successful fire self-evident. A refused fire costs a log line and a `204` —
the same experience a tick gives the operator, which is the contract this
endpoint promises.

### Consequences

- Good: one fire path, one set of preflights, one ownership rule — a manual
  fire cannot drift from a tick.
- Good: existence stays hidden from non-admins; no grant-probing oracle.
- Bad: an interactive caller cannot distinguish "fired" from "refused" except
  by the Last-run cell or the server log.
- Bad: the response carries no process id, so the console attributes the run
  only after a refetch, and a cron tick landing between fire and refetch can
  take the Last-run slot.

## More Information

Route: `SecuredRoutes.kt` (`post("/agents/{agentName}/cron-run")`).
Preflight order and the cooldown windows: ADR-0051. The load-time refusal that
keeps a required-input agent off the schedules page at all: `AgentsService`.
