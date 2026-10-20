---
status: accepted
date: 2026-06-17
deciders: Lukáš Vlk
---
# ADR-0026: Join continuations — resume a parked workflow once after N watched processes end

## Context and Problem Statement

Completion continuations (ADR-0023) wake a parked workflow when a *single*
watched sub-process reaches a terminal state. A `!<langgraph>` graph that fans
out to N sub-agents in parallel — N branches each calling `homer_fanout()` →
`interrupt()` — produces N pending interrupts on one LangGraph thread. With
only single-watch continuations, each sub completion fires its own
continuation, each resumes the *same* thread, and the resumes must be
serialized: concurrent resumes race on the thread's single SQLite checkpoint
(`$HOMER_LG_STATE_DIR/checkpoints.sqlite`). The runtime therefore resolves the
N interrupts one-per-resume-cycle — N sequential round-trips, wall-clock ≈ sum
of the sub durations.

We want a fan-out of N to cost **one** resume: dispatch all N subs, wait for
the last to terminate, then resume the parent thread a single time carrying all
N results. One resume → no concurrent checkpoint write → the serialization
constraint is structurally avoided, and wall-clock ≈ the slowest sub.

This does **not** change Homer's process model (FSM, lifecycle, status
precedence, one-job-per-process are all unchanged). It generalizes the
*continuation* layer from a 1:1 watch to a 1:N fan-in/barrier.

## Decision Drivers

- One resume per fan-out group, so concurrent resumes never race the
  checkpoint — the structural reason single-watch resumes are serialized today.
- Exactly-once fire, generalized from ADR-0023 1→N: two subs finishing
  concurrently can both observe "all watched terminal"; only one may fire.
- The parent must always wake, even on partial failure — FAILED/KILLED/UNKNOWN
  are terminal, so a join still fires; only a sub that *never* terminates is
  bounded by TTL → EXPIRED.
- Per-call opt-in that can mix grouped and ungrouped fan-outs in one graph; the
  no-group path must remain byte-for-byte today's sequential behavior.
- Reuse the existing continuation machinery (claim CAS, SQLite store, TTL
  sweep, boot reconciliation, `continueParams` + reserved-passthrough) rather
  than inventing a parallel one.

## Considered Options

- **Per-call `group=` marker on `homer_fanout`** vs. **a whole-agent env toggle**.
- **One JOIN continuation watching N processes** vs. **N single continuations
  plus a separate barrier that counts completions**.
- **JOIN rows reuse `continuations.watched_process_id`** vs. **a sentinel value
  (`0`) + a child `continuation_watches` table**.
- **Resume map values as nested JSON** vs. **as per-sub JSON strings**
  (byte-for-byte the single-fanout payload).

## Decision Outcome

**Per-call `group=` marker.** `homer_fanout(agent, ..., group="architects")`
adds `"group": "<name>"` to the interrupt payload; no `group` (default `None`)
takes today's sequential path unchanged. The marker — not a whole-agent toggle
— lets one graph mix grouped (parallel) and ungrouped (sequential, e.g. human
gates) fan-outs. On a park, the runner groups pending fanout interrupts by
`group`; a non-None group with **≥2** members is join-eligible. If any
join-eligible group exists it emits one join for the eligible group with the
smallest min interrupt-id; everything else stays pending for later segments.
This preserves the "exactly one resume-producing side effect per segment"
invariant: a group of N costs one round-trip, mixed parks fall back to
sequential for the remainder, and existing no-group graphs hit the unchanged
path.

**One JOIN continuation over N watches.** A new `POST /api/v1/callback/joins`
validates (origin live, `continueAgent` + **every** sub agent dispatchable,
resumeParam, ttl) *before* starting any sub — so a 4xx can never orphan a
started sub — then dispatches all N subs, registers ONE JOIN continuation over
the N `(processId, interruptId, transformer)` triples, and race-closes.

**Sentinel + child table.** Schema v5 adds `continuations.kind`
(`SINGLE`|`JOIN`) and a child table
`continuation_watches(continuation_id, watched_process_id, interrupt_id,
result_transformer)`. JOIN rows set `watched_process_id = 0` (real ids start at
1, per the AUTOINCREMENT invariant) so the single-watch trigger query never
matches them; the join trigger goes through the child table
(`findJoinsByWatchedProcessId`).

**Firing (generalizes ADR-0023 1→N).** `onProcessTerminal(pid)` fires SINGLE
continuations watching `pid` (existing) and, for each PENDING JOIN whose watch
set contains `pid`, fires when **all** its watched pids are terminal.
`fireJoin` claims the row (PENDING→DISPATCHING CAS) — exactly-once even when two
subs finish concurrently and both observe "all terminal" — aggregates each
sub's result into a map keyed by interrupt id, then starts the target.
Race-close at `createJoin` and boot replay (`refirePendingTerminal`) both
re-check "all terminal" and route JOIN rows to `fireJoin`; TTL sweep and
orphaned-DISPATCHING failure are status/expiry based and kind-agnostic.

**Resume map values are per-sub JSON strings.** The join delivers, under
`resumeParam` (`HMR_LG_RESUME`), a JSON map `{ interrupt_id: per_sub_payload }`
where each `per_sub_payload` is byte-for-byte what a single fanout delivers
today (`{status, processId, result}`, or `result:null` + `resultRef` over-cap)
— kept as a *string* value so each branch's `homer_fanout` decodes its own
per-branch payload unchanged. It also sets `HMR_LG_INTERRUPT` (via
`continueParams`) to the comma-joined sorted interrupt ids; the runner uses the
comma as the "join resume" signal: `Command(resume=json.loads(HMR_LG_RESUME))`
(the full map) when the id contains a comma, else today's
`Command(resume={iid: HMR_LG_RESUME})`.

### Consequences

- Good: a parallel fan-out of N costs one resume round-trip; wall-clock ≈
  slowest sub instead of the sum, with no concurrent-checkpoint race.
- Good: opt-in and additive — no-group graphs, single continuations, and the
  whole process model are unchanged; the feature is unreleased so the schema
  bump needs no production migration.
- Good: partial failure still wakes the parent (terminal includes
  FAILED/KILLED/UNKNOWN), carried as that sub's status in its map entry.
- Bad: LangGraph node bodies re-run on every resume; with parallel fan-out plus
  additive reducers this can double-apply (pre-existing, mode-independent).
  Grouped fan-out nodes must keep their bodies idempotent — flagged in the
  Python runtime guide.
- Bad: a sub that never terminates holds the join PENDING until TTL → EXPIRED;
  the parent then wakes only via the EXPIRED sweep semantics, same bound as a
  single continuation.

## More Information

Builds on ADR-0023 (completion continuations), ADR-0024 (`!<langgraph>` command
type), and ADR-0025 (capability-token read-by-reference for large results).
Empirically verified on LangGraph 1.2.5: `Command(resume={id1: v1, id2: v2})`
resolves multiple pending interrupts in one `invoke`, mapped by id, with none
left pending, producing the same final state as sequential resolution.

## Amendment (2026-10-01): kill started subs when the request fails

Validate-before-start alone did not keep the promise above. Each sub's cost cap,
`cooldown` and the process capacity are checked as that sub starts, and the
pending-continuation cap only when the join row is inserted after every sub
started — so a `429` or `503` could leave earlier subs running with no join
watching them and no process id in the error body. The same held for
`POST /api/v1/callback/runs` with `continueAgent` (ADR-0023), whose
continuation insert can hit the pending cap after the sub started.

Both endpoints now pre-check everything that needs no started process, including
every sub's `params` against its agent's inputs, and then compensate: if
anything fails (or the request is cancelled) once the first sub has started,
every sub this request started is killed (`KILLED`) before the error is
returned. A pre-check of the caps cannot replace this, because the caps are
shared with concurrent requests and only bind atomically at the insert. The
compensation is best-effort in one respect: a killed sub may already have begun
its first command.
