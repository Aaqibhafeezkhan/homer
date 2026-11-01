# executors/llm/CLAUDE.md

Dev guide for the LLM harness adapters (`homer.app.executors.llm`).
Project-wide rules live in the root [`CLAUDE.md`](../../../../../../../../../CLAUDE.md) and apply here
too; the cross-cutting backend guide is [`app/CLAUDE.md`](../../../../../../../../CLAUDE.md). This
file is canonical for the LLM harness recipe and its cost accounting
(root §9).

## LLM cost accounting

Every LLM harness attempt (`!<claude>`, `!<codex>`, `!<opencode>`) is cost-accounted regardless of exit
code: `LlmAttemptAccounting` charges what `LlmAttemptPricing` resolves — a lenient stdout scan
(`scanLlmAccounting`) then `LlmCostResolver` (session-spend endpoint → the harness's own cost
figure, or `CostSource.NONE` when the harness reports none and the endpoint doesn't answer).
Success gating (`LlmHarness.inspect`) is separate, strict, and exit-0 only. See ADR-0061,
ADR-0070.

With `llmPrice.sessionSpend` configured, a finished attempt does not wait for the endpoint:
`LlmAttemptAccounting.finished` returns the harness figure at once, marked `session figure
pending`, and `settle` runs the lookup on the `settlementScope` (the application scope, so the
shutdown drain joins it). The settlement charges the run once and swaps the pending accounting on
the process record through `StepRecords.settleAccounting`; the telemetry hand-off waits for it, so
Langfuse sees the settled figure. The command span is over by then (OpenTelemetry drops attribute
writes after `end()`), so the settled figure and a hand-off failure land on the step's
`llm_cost_settlement` child span, not on the command span — the command span keeps the preview's
`session figure pending`. That waiting hand-off (`LlmHarnessExecutor.handOff`) is bounded locally: it
holds one of `MAX_CONCURRENT_SETTLEMENT_HANDOFFS` permits while it waits, so concurrent steps
cannot multiply the parsed streams kept alive on the heap, and it waits at most
`SETTLEMENT_HANDOFF_TIMEOUT_MS` for the settlement. A hand-off that finds no permit sheds rather
than queues: it reports the harness figure inline, while the command span is still open, and sets
`telemetry.llm.handoff_shed` on it. One whose settlement outlives the wait bound gives up in the
background — the command span is over by then — reports the harness figure and sets the same
attribute on the `llm_cost_settlement` child span, where the settled figure would have landed. A
shed wait costs the trace's settled figure, never a charge, because the settlement keeps charging
the run on its own scope (CWE-770/CWE-400, ADR-0047 §12 rules 4–5). The permit is released on the
job's completion, never in a `finally` inside it: a scope already cancelled (the shutdown drain)
never runs the body, and eight leaked permits would shed every later hand-off for good. A settlement
that is cancelled or whose lookup blows up still charges the harness figure; any other failure leaves
the attempt on its pending figure, logged. Without the endpoint, and on every abort path (timeout,
output cap, run cancelled), accounting stays inline and uncancellable as before. See ADR-0089.

The executor captures the attempt's own wall-clock window (Homer server time, immediately
before and after the shell call) and hands it to `resolve`. With the endpoint's request-window
keys configured (`requestsPath` / `startedPath` / `requestSpendPath`), the resolver prices the
attempt from the spend rows whose start falls inside that window, and the window's row count —
not the session-wide `callsPath` — is the ingested figure for the completeness poll. This is
what makes a `--continue`-resumed session (one `session_id`, several runs) charge each run only
its own requests. Without the keys the resolver is unchanged. See ADR-0061.

An attempt can end more than one turn (Claude writes a second `result` when a background
subagent finishes after the first). A harness reports `TurnEnd.costUsd` **cumulatively** for
its process and `usage` per turn, so the scan charges the highest cost reported and sums the
usage, and `LlmTelemetryService` splits the attempt's cost across its generations. An adapter
whose CLI reports a per-turn cost must accumulate it before emitting — as bounded
per-instance accounting state, the one state an adapter carries (ADR-0070, as amended),
with a per-step dedupe so translating one stdout file twice never double-charges it. A charged attempt is
never left without a priced generation: with no successful turn end its last turn end carries
the charge, and with no turn end at all one fallback generation does. See ADR-0074.

Only an exit-0 stream is parsed strictly. The telemetry hand-off of a non-zero exit, and of
an attempt whose shell threw or whose accounting was cancelled (where `execute`'s own
hand-off never runs), reads the stream with `parseNdjsonLenient`, so a killed CLI's
truncated last line costs that line only, not the attempt's tool and message spans. Every
skipped line is counted into `telemetry.llm.malformed_events`. The read is bounded at
32 MiB and the parse at 50 000 events kept, and a tripped bound sets
`telemetry.llm.handoff_truncated`; a hand-off shed at its concurrency or wait bound sets
`telemetry.llm.handoff_shed` (ADR-0047 §12 rule 4); the abort-path hand-off also runs
under a 5 s timeout, so it cannot hold a cancelled run's teardown open.

## Escalation tiers

`escalate` (ADR-0079) is executor-owned: `LlmHarnessExecutor` runs tier 0, judges each next tier's
`when` through `TierEscalation` with `HMR_LLM_OUTCOME` / `HMR_LLM_EXIT_CODE`, and reruns the same
compiled prompt on `LlmHarness.escalated(command, tier)`. Tiers continue the attempt numbering, and each tier's
attempts are accounted like any other and reported, except a `malformed` tier the next tier takes
over: that tier is accounted only, since it hands the step to the next model. An empty `escalate`
must stay a single pass with no predicate evaluated — `LlmHarnessExecutorTest` pins it.

## Adding an LLM harness

A new LLM coding CLI is a small `Command` subclass (the `LlmCommand`
contract) plus an `LlmHarness` adapter, not a new executor —
`LlmHarnessExecutor` (retry, accounting, telemetry hand-off) is shared. See
ADR-0070. An adapter carries no shared state: instances are per-consumer, and the
one state an adapter may hold is bounded per-instance accounting state (see
"LLM cost accounting" above), never anything keyed beyond its own bounded
structure.

0. Add the `Command` subclass implementing `Command.LlmCommand`, with its
   `@SerialName`, its `homer-agent.schema.json` `Command.oneOf` entry and its
   AGENT.md section, as in [Adding a new command type](../../../../../../../../CLAUDE.md#adding-a-new-command-type)
   and root §10.
1. Implement `LlmHarness<C>` in `executors/llm/MyHarness.kt` (`id` — the command's
   kind, which keys the `LlmEventStreamService` map and names the `llm/<id>/` fixture
   directory; argv, translate, inspect, escalated; `reportsCost` false if the CLI has no notion of its own USD cost;
   `countsModelCalls` false unless each message id is one model request).
2. Add a captured-stream fixture under
   `src/test/resources/llm/<id>/` and a `MyHarnessTest`.
3. Register it in `CommandExecutorFactory` (`LlmHarnessExecutor(MyHarness(), …)`).
4. Register the adapter in the harness map passed to `LlmEventStreamService`
   in `AppModule`, so `/llm-events` serves the new kind.
5. Add the kind to the console's `LLM_KINDS`.
