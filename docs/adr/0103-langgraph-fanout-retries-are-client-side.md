---
status: proposed
date: 2026-11-12
deciders: Lukáš Vlk
consulted: ADR-0023, ADR-0025, ADR-0026, ADR-0044, ADR-0046, ADR-0047
---

# ADR-0103: Fan-out retries are a client-side graph capability with replay-derived attempt counts

## Context and Problem Statement

A failed sub-run reaches a `!<langgraph>` graph as a value — `homer_fanout` returns
`{status: "FAILED", processId, result: null}` — and Homer stores that value as the resume
payload of the segment that received it. Resuming replays the same payload forever: the sub is
never dispatched again. Retrying the sub through the existing retry endpoint creates a new
process no continuation watches, so the parent graph is never woken. Every graph author who
needs retries wraps `homer_fanout` in a hand-rolled loop, written differently each time. A
decision was needed on where the retry lives, what counts as an attempt, where the attempt count
is kept, and what an undecodable resume payload should do.

## Decision Drivers

- **The resume channel stays as ADR-0044 built it**: `HMR_LG_RESUME` deliberately wins over
  `HMR_LG_RECOVER`, and the server's resume path ships no retry semantics.
- **Grouped fan-outs resolve as one join** (ADR-0026); a retry must respect the per-interrupt and
  per-group resolution units and never re-dispatch a member that already finished.
- **Bounds are local and explicit** (ADR-0047 rule 4): a retry re-dispatches a sub-agent, so the
  caps live in code at the point of use, and failure and infrastructure outcomes get separate
  limits.
- **The checkpoint store is agent-writable** (ADR-0046, ADR-0047 rule 2): anything read from
  graph state as authority is attacker-controlled.
- **A retried sub's over-cap result still arrives as a `resultRef`** (ADR-0025); the retry path
  reuses the idempotent fetch, it does not invent result recovery.

## Considered Options

- **A. A client-side retry parameter on `homer_fanout`** — the gate re-parks the same node with
  the original payload while attempts remain.
- **B. A server resume flag** — a `retryFailed` flag on the resume endpoint drops the recorded
  resume value for failed subs and re-runs the node, so it re-parks and re-dispatches them.
- **C. The status quo** — every graph writes its own retry loop around `homer_fanout`.

## Decision Outcome

Chosen option: **A**, because it closes the loop where the payload already is — inside the gate —
with no server change, no new resume mode and no contract touch, and it composes with both
resolution units (sequential and join) for free, since the gate only ever re-parks its own
interrupt.

- `homer_fanout(..., retries=N, infra_retries=K)` loops on `interrupt()`: while a budget lasts,
  it re-parks the node with the identical original payload, so the runtime re-dispatches that sub
  from the original call's args — never from the failed result. `FAILED` draws on `retries`;
  `UNKNOWN`, a missing or unrecognized status and an undecodable payload draw on `infra_retries`.
  After the last attempt the final payload reaches the graph unchanged, exactly as with no
  retries.
- **The attempt count is the loop index, rebuilt by replay.** LangGraph re-runs the node from its
  start and replays the thread's recorded resume values in order, so the counters rebuild exactly
  on every segment from the checkpoint itself. No state key, no side file — and no value written
  to graph state can raise the count, because none is read: the ticket's original "keep the
  attempt count in graph state" shape was dropped for this one. The checkpoint store is
  agent-writable, so a state-held counter is attacker-controlled — a reset value would loop the
  dispatch for as long as the failures keep coming. The replay-derived count has no input to
  tamper with.
- **The cap is a client constant (`MAX_FANOUT_RETRIES = 5`), not config.** It bounds the
  dispatch multiplication (ADR-0047 rule 4) while leaving headroom over any realistic transient
  failure streak; budgets above it are refused with `ValueError` before anything is parked.
  Making it config would let a caller raise the very bound that protects Homer from that caller.
- **The same-node loop is a recorded scope exception.** The per-interrupt resume map targets
  *distinct parallel* interrupts; multiple `interrupt()` calls inside one node are replayed by
  scratchpad index and are not individually addressed. The retry loop parks one fan-out at a time
  and relies on exactly that in-order replay, so the runtime README names it as the one supported
  same-node loop — the interrupt scope the feature had to widen.
- **An undecodable resume payload re-dispatches the sub within the infrastructure budget.** The
  payload indicts the resume channel, not the sub, so the alternative — raise immediately,
  without spending a sub execution on a value that was never decoded — was considered and
  rejected: the gate cannot tell a corrupt channel from a corrupt value, an undecodable payload
  is never read as success, and ending the run on it would defeat the budgets the feature exists
  to provide. The re-dispatch is capped, and once `infra_retries` is spent the `ValueError`
  raises exactly as it does with no retries at all. The accepted cost: one sub execution per
  infrastructure retry, visible only as the one retry log line, even when the sub itself finished
  fine.

### Consequences

- Good: a three-sub fan-out that loses one sub retries only that sub, with no custom graph code,
  and continues with all three results.
- Good: failure and infrastructure budgets are spent separately, so an unclassifiable outcome
  never eats the failure budget.
- Good: no server change — parity, ownership and the env contract are untouched.
- Bad: retries multiply sub-dispatches per graph run; the constant cap and the two separate
  budgets are the bound, and they are enforced client-side only.
- Bad: the attempt count is only as durable as the thread's own checkpoint; a run on a runner
  whose state dir does not survive (ADR-0044's "Bad" consequence) loses the park entirely, retries
  or not.

## Pros and Cons of the Options

### A — Client-side retry parameter (chosen)

- Good: no contract change; both resolution units compose; bounded locally.
- Good: the attempt count cannot be raised by any graph-state write.
- Bad: the graph author opts in per call, and the node body must be idempotent.

### B — Server `retryFailed` resume flag (rejected for now)

- Good: would also cover graphs that do not use the helper.
- Bad: needs node idempotency the server cannot verify, a new resume mode with its own
  authorization and shedding on the resume endpoint, and it inverts ADR-0044's "resume replays
  the recorded value" semantics for exactly one payload class. Deferred: consider after the
  client-side helper has shown what the demand actually is.

### C — Status quo (rejected)

- Bad: every graph re-implements the loop; the attempt counts and caps drift per graph, and none
  of them is reviewed.

## More Information

- Feature and limits are documented in the runtime README ("Retrying failed fan-outs") and the
  package docstring of `homer_fanout`; the changelog entry points back here.
- Resume precedence and checkpoint ownership: ADR-0044, ADR-0046. Join resolution: ADR-0026.
  Result-by-reference on over-cap results: ADR-0025. Bounds and the attacker-controlled-input
  rule: ADR-0047.
