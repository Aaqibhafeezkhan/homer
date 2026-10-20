---
status: accepted
date: 2026-08-24
deciders: Lukáš Vlk
consulted: SAST (MR !6)
---
# ADR-0051: The cooldown is enforced in the process-INSERT transaction, on recorded timestamps

## Context and Problem Statement

`cooldown` (ADR-less, added alongside the AIF triage) bounds how *often* an agent
may start, complementing the bounds Homer already had on concurrency
(`lockStrategy`, `maxParallelRuns`) and spend (`costLimits`). It was implemented as
a pre-flight check in `AgentRunStarter`: read the previous run's timestamps, refuse
if the window has not elapsed, then create and launch the process.

Review found that bound did not hold, for two independent reasons — one a race, one
not a race at all.

**The check was blind, not merely stale.** `latestRunStartedAt` read the *first
`history` entry of the newest row* for the agent. But `obtainProcessId` inserts the
row with `history = EMPTY_HISTORY_JSON`, and the first entry is written by
`setProcessStatus(processId, CREATED)` *inside* `ProcessExecutor`'s
`coroutineScope.launch`. Between the INSERT and that asynchronous write, the newest
row shadowed the real previous run and read back empty — so `latestRunStartedAt`
returned `null`, and `minInterval` was skipped entirely rather than evaluated
against a stale value. The window's length is bounded by nothing in the code: it
lasts until the launched coroutine is scheduled and completes a write on the
`Dispatchers.IO` pool that this repo already documents as starvable.

**The check was not atomic with creation.** Even with a correct timestamp, two
concurrent starts both read the same previous run, both passed, and both inserted —
a textbook TOCTOU, with `resolveInputs()` (JSON-schema validation) widening the
window.

`afterFailure` was affected only by the second problem: `latestFailureEndedAt`
filters on `status = FAILED`, so a fresh `CREATED` row does not shadow it.

A bound that two parallel requests walk through is not a bound (§12 rule 4), and
this one exists precisely to stop repeated side effects — a comment on a merge
request, a message in a channel — which is exactly what a bypass reproduces.

## Decision Drivers

- The bound must hold under concurrent starts, not merely under sequential ones.
- Homer is single-node by design; distributed coordination is explicitly off the
  roadmap, so a DB-local mechanism is sufficient — but it must be *local and
  explicit* rather than inherited from another component.
- `services/persistence/CLAUDE.md` states the concurrency model: the
  read-modify-write operations a `Mutex` used to guard are now `BEGIN IMMEDIATE`
  transactions. A new bound should use that model, not reintroduce a lock beside it.
- Transactions must stay short and must not suspend on another transaction.
- A refusal must still reach the caller synchronously, as a `429`.

## Considered Options

- **A. Per-agent in-process mutex** around check + create.
- **B. Evaluate the cooldown inside `obtainProcessId`'s existing transaction**, and
  give the row a `created_at` column written by the same INSERT.
- **C. A dedicated `agent_last_started_at` table** updated by a conditional UPDATE
  that fails when the window has not elapsed.

## Decision Outcome

Chosen option: **"B"**.

Two changes, and both are required — either alone leaves the bound broken:

1. **`processes.created_at` (schema v13).** Written in the INSERT's own
   transaction; `latestRunStartedAt` reads it instead of the first `history` entry.
   This removes the blind window entirely, because the value exists the moment the
   row does. Migration backfills it from the first `history` entry — the field it
   used to be read from — so the bound keeps holding across an upgrade rather than
   going blind for one run per agent, which would hit exactly the deployments that
   configured a cooldown. Rows with no history keep `NULL`, which reads as "never
   ran"; there is no start time to recover.
2. **The cooldown decision moves inside `obtainProcessId`'s transaction.** Writers
   serialize through `BEGIN IMMEDIATE`, so evaluating the windows against rows read
   inside it makes check-and-insert atomic.

3. **`processes.ended_at` (schema v14).** `afterFailure` measured its window from
   the last `history` entry of the newest FAILED **row id**. Row id is INSERT
   order, and same-agent runs overlap by default — the default
   `lockStrategy.key` is `{{HMR_PROCESS_ID}}`, unique per run, and
   `maxParallelRuns` is 10 — so an older run can fail *after* a newer one. The
   query then returned the newer row's *earlier* failure and admitted a start
   inside the window. `ended_at` is written by the same UPDATE that sets a
   terminal status, and the read orders by it instead of by id. Migration backfills
   it from the last `history` entry of rows that are already terminal; an active row
   has not ended and stays `NULL`. `NULL` sorts last under `DESC` in SQLite, so a row
   without a recorded terminal time can never mask a real one — an assumption the
   read depends on, and one a test pins directly.

There is direct precedent for (2): that same transaction already enforces the
`maxProcesses` capacity bound, evicts a terminal row, and inserts — policy and
insert have always been atomic there. Passing the cooldown windows in is the same
shape, not a new kind of coupling.

`CooldownGate`'s pre-flight check **stays**, deliberately, and is no longer the
bound. It preserves the caller-facing exception ordering (cost cap → cooldown →
input validation) and avoids resolving inputs for a start that will be refused. The
arithmetic lives in one place — `CooldownDecision`, a pure function of the windows
and the two timestamps — because a transaction cannot call the suspending gate, and
a second copy of the arithmetic is exactly how the two would drift apart.

Option A was rejected not because it fails — Homer is single-node, so the scanner's
"weaker if multiple instances occur" caveat does not apply — but because it adds a
second concurrency primitive beside the transactional one this repo already
standardised on, for a guarantee the transaction already provides. Option C adds a
table and a write to every start to hold state the `processes` row already carries.

### Consequences

- Good: the bound holds under real parallel writers; a denial test asserts that 20
  concurrent starts admit exactly one and leave exactly one row.
- Good: `latestRunStartedAt` is now meaningful the instant a row exists, which
  removes a class of "reads empty for a while" bug from anything else that might
  use it.
- Good: both timestamps are now real columns, so neither read parses JSON on the
  start path, and neither can disagree with the status it accompanies.
- Good: the general lesson — a historical fact should be recorded when it happens,
  not re-derived later from current state. Both defects here were the same
  mistake in different clothes (row order standing in for failure time; an empty
  history standing in for "never ran").
- Neutral: schema v12 → v13, one nullable column, additive.
- Bad: `ProcessRepository` now knows about `Cooldown`. Accepted — it already knows
  about `maxProcesses`, and the alternative is a bound that does not bind.
- Bad: the cooldown is evaluated twice on the happy path (pre-flight, then in the
  transaction). Two cheap indexed reads, in exchange for keeping the refusal
  ordering callers already observe.

## Amendment (2026-09-26): retry, resume and debug skip `afterFailure`, not `minInterval`

Moving the authoritative check into the process-INSERT transaction gated **every**
start path, retry, resume and debug included — a consequence this ADR caused and
never stated. This amendment records the behaviour deliberately, after the
question was raised as its own ticket.

**The decision.** `POST /api/v1/processes/{id}/retry`,
`POST /api/v1/processes/{id}/resume` and `POST /api/v1/agents/{name}/debug`
ignore the agent's `cooldown.afterFailure`. All three still honour
`cooldown.minInterval`, and a start inside that window is refused with `429` and
launches nothing. Every other start path is unchanged and fully gated.

The two windows answer different questions, which is why they part company here.
`afterFailure` backs off an *automated repeat* of a failure: a fast-failing agent
on a short schedule would otherwise re-run against identical state forever. A
person clicking Retry, resuming a crashed run, or starting a debug command is the
opposite — the intended way *out* of that backoff, and the only one an operator
has. Gating it means a failure backoff can only be waited out. `minInterval` is
different in kind: it is a pacing and quota guarantee for *every* start of the
agent, so it holds everywhere, manual paths included.

The alternative — leaving all three fully gated, which is what this ADR
incidentally produced — was rejected because it makes the failure bound
unrecoverable without waiting, and because debug in particular is an
investigation tool that the backoff of the very failure being investigated would
block.

**Implementation.** The handlers start through `Agent.withoutFailureBackoff()`,
which returns the agent with `afterFailure` cleared and, when `minInterval` is
also unset, the whole cooldown dropped — `Cooldown`'s `init` rejects a value with
neither window set, so collapsing to null is required rather than cosmetic. The
mechanism this ADR established is untouched: the check stays inside
`obtainProcessId`'s INSERT transaction, `CooldownDecision` stays the single copy
of the window arithmetic, and the only change is the value the three handlers
pass. No second decision implementation was added.

**Accepted risk.** Automation calling the retry or resume endpoint through the
API bypasses `afterFailure`. It stays bounded by `minInterval`, by the `run` grant
each endpoint requires, and by the per-caller rate limit — but a caller with a
`run` grant can, deliberately or in a loop, outpace a backoff it would have been
held to had it called the agent directly. Accepted: the alternative is a manual
recovery path that cannot recover.

**Runs from these endpoints still count toward the windows.** They are ordinary
`processes` rows with `created_at` and `ended_at` set, so they open the agent's
`minInterval` window and, if they fail, the `afterFailure` window as well. A
failed debug run therefore also backs off the production cron. That is left as is
— it is the correct reading of "this agent just failed" — and can be revisited
separately if a debugging run is ever expected to leave the agent's schedule
alone.

## More Information

- §12 rule 4 (bound every resource a caller can multiply; a bound living in another
  component is not a bound) and rule 8 (prove each constraint with a denial test).
- Reported by the security scanner on MR !6 as a TOCTOU; the blind-window half was found while
  verifying the report and is the more severe of the two.
