---
status: proposed
date: 2026-10-16
deciders: Tomáš Hrstka
consulted: ADR-0047, ADR-0061, ADR-0074, ADR-0083
---

# ADR-0089: Settle an LLM step's session cost in the background

## Context and Problem Statement

With `llmPrice.sessionSpend` configured, `LlmHarnessExecutor` priced a finished attempt
before returning it: the resolver polled the spend endpoint at 0, 5, 15 and 30 s after the
CLI exited until the endpoint had ingested every call (ADR-0061). The gateway ingests rows
with a lag, so most lookups ran the whole schedule, and every LLM step cost its run about
35 s of waiting after the CLI was done. On a pipeline of short steps that wait was a large
share of the run's wall clock. The spend figure is only bookkeeping; nothing in the step
that follows depends on it.

## Decision Drivers

- The next step should start as soon as the CLI exits.
- Every attempt's spend must still be charged exactly once, settled or not.
- The process record, the cost ledger and Langfuse must end up with the settled figure.
- The abort paths (timeout, output cap, run cancelled) must keep their uncancellable inline
  accounting; they already run under `NonCancellable` and must not gain a second code path.

## Considered Options

- **A: settle in the background.** The executor returns the attempt on the harness figure,
  marked `session figure pending`, and runs the lookup as a child of the application scope.
  The settlement charges the run and replaces the step's accounting when it answers.
- **B: poll less.** Shorten the schedule, or stop at the first response. Cheaper only when
  the gateway ingests fast; otherwise it trades the wait for under-counts.
- **C: price lazily, on read.** Charge the harness figure and look the session up when the
  status API or the ledger is read. Moves the wait to every reader and leaves the ledger
  and the cost caps on the estimate.

## Decision Outcome

Chosen: **A**. `accountFinished` splits into a preview and a settlement when the resolver is
configured. The preview is the harness figure with `fallbackReason: session figure pending`;
it is recorded on the step and annotated on the span, but not charged. The settlement runs
on the `settlementScope` (the `applicationScope`, so `drainInFlightJobsThenCloseSessions`
joins it on SIGTERM like a run), prices the scan through the resolver, charges the run and
the cost meter once under `NonCancellable`, and swaps the pending accounting for the settled
one through `StepRecords.settleAccounting`. `ProcessManager` keeps a settlement that lands
before `completeStep` records the step and merges it then, through the in-memory map the two
sides coordinate on — each side is one write transaction plus one atomic map step, so no
mutex is held across a `query { }` (app/CLAUDE.md SQLite rule 3). The telemetry hand-off waits for the
settlement and reports the settled figure to Langfuse, under a local permit pool and wait bound (see
Consequences). A settlement that is cancelled or whose lookup throws still charges the harness figure
(`run cancelled before lookup`); any other failure leaves the step on the pending figure and logs.
Without a configured resolver, and on every abort path, nothing changes.

**Why not the others:** B keeps the wait or the under-count, and C leaves the ledger and
the caps on an estimate until someone reads the run.

### Consequences

- Good: an LLM step hands over as soon as the CLI exits; the run saves up to 35 s per step.
- Good: the ledger, the step record and Langfuse all carry the settled figure once it lands,
  and a cancelled settlement still charges the harness figure, so no spend is lost that was
  not lost before.
- Bad: a run read right after an LLM step, or right after it finished, can show
  `session figure pending` and a `costUsd` that is still growing. Cost caps see the charge
  up to 42 s later than before; a step that would have tripped a cap can start one more
  step first. Accepted: the caps were never exact to one step.
- Bad: a step that finishes under the drain timeout at shutdown keeps `session figure
  pending` forever and its spend never reaches the ledger. The same SIGTERM cut the inline
  accounting short before, so the drain floor of ADR-0061 (42 s at the defaults) still
  applies.
- Neutral: the command span is over by the time a settlement answers, and OpenTelemetry drops
  attribute writes after `end()`. The settled figure therefore lands on the step's
  `llm_cost_settlement` child span (a hand-off failure's `telemetry.error` with it), and the
  command span keeps the preview's `session figure pending` — the span-level alert on a degraded
  figure excludes that reason and reads the settlement span for the truth.
- Neutral: a settlement for a step that was never recorded (an attempt a retry superseded) stays
  in `ProcessManager`'s unapplied map until the process is removed; one small entry per such
  step. A settlement whose process row is already gone is dropped — `removeProcess`/eviction has
  run the only removal that would ever clear the entry, so buffering it would leak it.
  Two steps of one run with identical
  pending accountings settle the first match, which can swap the wrong one; the figures
  would be identical anyway.
- Neutral: no mutex spans the settlement's row swap and `completeStep`'s map read plus row write
  (a mutex across a `query { }` would serialize every run's step bookkeeping behind it,
  against app/CLAUDE.md SQLite rule 3). A settlement landing between a step's map read and its
  row write buffers after that read and is never applied: the step keeps `session figure
  pending` on the record while the ledger still carries the settled charge. Accepted: the
  window is one step bookkeeping's write, and the state it leaves is one operators already
  see transiently on every settled step.
- Neutral: a hand-off that waits for the settlement holds the attempt's parsed stream, so it is
  bounded locally: at most `MAX_CONCURRENT_SETTLEMENT_HANDOFFS` (8) wait at once, and each waits at
  most `SETTLEMENT_HANDOFF_TIMEOUT_MS` (45 s) for the settlement. Past either bound the hand-off sheds
  rather than queues, and marks the shed with `telemetry.llm.handoff_shed`: with no permit free the
  step is reported inline on the harness figure while the command span is still open, and past the
  wait bound the background job reports the harness figure on the `llm_cost_settlement` child span,
  the command span being over by then. A shed wait costs the trace's settled figure, never a charge:
  the settlement keeps charging the run on its own scope (ADR-0047 §12 rules 4-5). The permit pool is
  one semaphore per `LlmHarnessExecutor` instance, and `CommandExecutorFactory` builds one executor per
  harness kind, so the bound is 8 per harness kind rather than 8 server-wide; one shared pool in
  `CommandExecutorFactory` was considered and rejected, so moving the pool changes the bound's scope.
- Neutral: the debug runner settles on its own scope and drops a settlement its run exits
  before; a debug run never charges a ledger.
