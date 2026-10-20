---
status: accepted
date: 2026-07-28
deciders: Lukáš Vlk
---
# ADR-0035: SQLite-backed cost ledger

> **Note:** `ClaudeExecutor.accountAttempt`, named below, now lives in the
> harness-generic `LlmHarnessExecutor` ([ADR-0070](0070-llm-harness-adapter-boundary.md)).

## Context and Problem Statement

`CostMeter` tracked Claude spend in in-memory rolling windows (60 s
global, 24 h per-agent), documented as intentionally process-local: the
meter reset on restart. That had two problems. A restart silently reset
a spent `perAgentUsdPerDay` budget, so the caps were advisory across
restarts. And there was no durable spend history at all —
`processes.cost_usd` is per-run but process rows are bounded (evicted
terminal-first) and purged, so aggregating over them cannot give
reliable long-term statistics.

## Decision Drivers

- Cost caps must survive a restart, not just reset silently.
- Operators need durable monthly / all-time spend statistics, not just
  a live 60 s / 24 h view.
- Consistency with ADR-0020: SQLite is authoritative, no in-memory
  cache of durable state.

## Considered Options

1. **Append-only `cost_entries` ledger; `CostMeter` holds no state,
   all reads are SQL aggregates** (chosen).
2. Hybrid: in-memory windows plus persisted entries rehydrated at
   boot — two sources of truth and drift risk for a cap check that
   runs once per run start.
3. Monthly aggregate tables only — cannot rebuild the trailing 24 h /
   60 s windows after a restart.

## Decision Outcome

Claude spend is recorded in an append-only `cost_entries` table
(schema v6) in the existing SQLite database: one row per successful
Claude command (`agent_name`, `timestamp_sec`, `cost_usd`). `CostMeter`
keeps its public API but holds no state — cap windows, the `/costs`
snapshot, and the new monthly / all-time statistics are all SQL
aggregates over the ledger, per ADR-0020's rule that SQLite is
authoritative with no in-memory cache.

- Months are bucketed in **UTC** (`strftime('%Y-%m', timestamp_sec,
  'unixepoch')`); no server-timezone dependence.
- Rows are **never pruned**: all-time totals depend on the full
  history, and one row per Claude run stays small at Homer's scale.
- `checkCaps` remains a reserve-nothing pre-flight (ADR-0007);
  overshoot accounting is unchanged and remains process-local
  observability, not durable state.

## Consequences

- Good: cost caps survive restarts — a bounce no longer resets a
  spent budget.
- Good: `GET /api/v1/costs/monthly` and `GET /api/v1/costs/totals`
  serve durable statistics; the console `/costs` page grows monthly
  and all-time views.
- Neutral: every cap check and cost record is now a short SQLite
  transaction (`BEGIN IMMEDIATE`), consistent with the rest of the
  persistence layer; the old `Mutex` is gone.
- Trade-off: the `cost_entries` table grows unboundedly by design; if
  it ever becomes a problem, a rollup + prune can be added without
  changing the API surface.

## Amendments

- 2026-09-10: [ADR-0061](0061-price-claude-runs-from-session-spend-endpoint.md)
  unifies Claude accounting onto one path (`ClaudeExecutor.accountAttempt`) that
  runs for every accounted attempt, not only a successful command. The ledger
  now receives one row per accounted Claude attempt — including non-zero exits
  and attempts a shell aborted (TTL timeout, output cap) — not only exit-0 runs.
