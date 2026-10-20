---
status: accepted
date: 2026-10-03
deciders: Lukáš Vlk
---
# ADR-0074: An attempt that ends more than one turn is charged once, at its highest cumulative cost

## Context and Problem Statement

A `!<claude>` attempt can end more than one turn: when a background subagent finishes
after the main turn, `claude -p` writes a second `result` line. Measured on CLI 2.1.287,
`total_cost_usd` and `modelUsage` on those lines are **cumulative** for the process, while
`usage` is the turn's own. Homer assumed one turn end per attempt in two places. Telemetry
priced every turn end with the whole attempt's cost, so Langfuse showed such an attempt at
N times its charge. The accounting scan kept the first turn end, so when the session-spend
endpoint did not answer, the CLI fallback charged the attempt only up to its first turn —
the under-count `costLimits` cannot tolerate (ADR-0061).

## Decision Drivers

- An attempt is charged once, and the ledger never charges less than the CLI reported.
- Langfuse aggregation by step or by model must sum to the ledger, with no observation
  left for Langfuse to price from its own model table.
- stdout is agent-writable (ADR-0047): a later `result` line is not trusted to lower a
  charge.
- One rule for every harness, since `scanLlmAccounting` and `LlmTelemetryService` are
  harness-neutral (ADR-0070).

## Considered Options

- Keep the first turn end (the behaviour this replaces).
- Keep the latest turn end's cost.
- Keep the highest non-negative cost, and split it across the generations by each turn's
  increment.
- Price only one generation per attempt and leave the others without `cost_details`.

## Decision Outcome

Chosen option: **keep the highest non-negative cost, and split it across the generations
by each turn's increment**.

`TurnEnd.costUsd` is cumulative for the harness process and `TurnEnd.usage` is per turn —
a contract every adapter keeps; one whose CLI reports a per-turn cost accumulates it
before emitting. The accounting scan charges the highest non-negative cost any turn end
reported and sums the usage. Telemetry gives each turn end its increment of that
cumulative figure, scaled to the resolved cost, so an attempt's generations sum to its
charge. When the increments cannot be read — a turn end without a cost, a negative one,
or one that went down — or they total zero, the last turn end carries the whole charge
and the earlier ones an explicit zero. A failed or stopped turn end emits no priced
generation, so its share moves to the next successful turn end, or the last one before
it. An attempt with no successful turn end — every turn failed, or it stopped at a turn or
budget limit — is priced on its last turn end, whose generation carries the whole charge
with an ERROR status and its reason. An attempt the CLI left with no turn end at all (killed
by the step timeout, the output cap, an OOM kill or a rollout) gets one generation of its
own from the attempt's accounting, carrying the charge and the `no result event` reason, so
a charged attempt is never shown in Langfuse as a free one. A negative reported cost counts as no cost at all, and an attempt
nothing priced (cost source `none`) hands telemetry no resolved cost rather than a zero, so
it is never shown in Langfuse as a free one; a spend endpoint's own zero stays a zero.

### Consequences

- Good: Langfuse cost per step and per model sums to the ledger for every attempt.
- Good: a forged or drifted lower figure can only be ignored, never charged, and a
  negative token count is not summed into the attempt's usage.
- Bad: nothing bounds a forged higher figure. It over-charges its own run and its own
  agent's `costLimits` window — what a forged first `result` line could already do
  before this decision, and what a run can do by really spending. A ceiling would be new
  configuration and is left to a decision of its own.
- Bad: a degraded attempt (increments unreadable or zero) shows all of its cost on its last
  generation — the sum is right, the per-turn split is not.
- Bad: a harness that reported a per-turn cost without accumulating it would be
  under-charged to its largest turn; the contract is documented on `TurnEnd` and in
  `executors/llm/CLAUDE.md` because nothing can detect it at runtime.

## Pros and Cons of the Options

### Keep the highest non-negative cost, and split it by increment (chosen)

- Good: one charge per attempt, never lowered by a later line, and a Langfuse split that
  sums to it.
- Bad: two mechanisms (the scan's maximum and the telemetry split) that must agree on
  what counts as no cost.

### Keep the first turn end

- Bad: under-charges every multi-turn attempt on the CLI fallback, and a forged early
  `result` line lowers the charge.

### Keep the latest turn end's cost

- Good: right for every honest stream.
- Bad: a later lower or negative line — drift or agent-written — lowers or zeroes the
  charge unmarked.

### Price only one generation, leave the rest unpriced

- Bad: Langfuse prices a generation without `cost_details` from its own model table, so
  the attempt is still charged twice in Langfuse, at a price nobody configured.

## More Information

ADR-0061 (pricing from the session-spend endpoint), ADR-0070 (the harness adapter
boundary), ADR-0047 (stdout is attacker-controlled). The stream behind the measurement is
`app/src/test/resources/claude/cli-2.1-background-agent-stream.jsonl`.
