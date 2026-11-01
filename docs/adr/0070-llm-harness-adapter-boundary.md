---
status: accepted
date: 2026-09-27
deciders: Lukáš Vlk
---
# ADR-0070: LLM harness adapter boundary and canonical event model

## Context and Problem Statement

Homer runs two LLM coding-harness CLIs as subprocesses, Claude Code
(`!<claude>`) and OpenAI Codex (`!<codex>`), and wants to add more. Today the
two executors share only a lenient NDJSON reader and a copy-pasted retry
loop; every observability feature — Langfuse generation/tool spans,
per-attempt cost accounting, the session-spend resolver, usage on the
process record, the console's structured conversation view — is
Claude-specific code that Codex does not have. A third harness would copy
the ~200-line loop a third time and still start with none of it.

## Decision Drivers

- Adding a harness must cost one adapter, not a copy of the executor loop.
- Claude's spans, cost accounting, and fallback-reason vocabulary must not
  change for existing operators and dashboards.
- The console must render conversations without knowing any CLI's dialect.
- §12 bounds (session-id gating, call-count caps, redaction) must live in
  one place, enforced for every harness, not re-implemented per adapter.
- The session-spend resolver (ADR-0061) must serve every harness, not just
  Claude.

## Considered Options

- (A) One generic `LlmHarnessExecutor` plus a small stateless per-harness
  adapter (`LlmHarness`) that only knows argv-building and line translation.
- (B) An abstract base executor with template methods each harness overrides.
- (C) Shared services only (a common accounting/telemetry library), each
  harness keeping its own executor and retry loop.

## Decision Outcome

Chosen option: **"(A) generic executor + adapter"**, because it is the only
option that puts the retry loop, accounting, telemetry, and streaming in
exactly one place while keeping each harness's CLI-specific knowledge
(argv shape, its own JSONL dialect, its own success/failure rules) fully
isolated and independently testable. Option B still lets state leak into
"template" hooks and does not stop the loop itself from drifting between
subclasses; option C leaves a third executor free to skip a bound the
shared services never enforced directly.

The adapter contract lives in `homer.app.executors.llm.LlmHarness.kt`:

```kotlin
interface LlmHarness<C> where C : Command, C : Command.LlmCommand {
    val id: String
    val reportsCost: Boolean
    fun argv(command: C, env: Map<String, String>): List<String>
    fun processEnv(command: C): Map<String, String> = emptyMap()
    fun stdOutFilter(command: C): LlmStdOutFilter? = null
    fun translate(line: JsonObject): Translation
    fun inspect(events: List<LlmEvent>, raw: List<JsonObject>, command: C): Inspection
}
```

*(Amended 2026-10-09: the interface also declares `val countsModelCalls: Boolean`,
described below, and `fun escalated(command: C, tier: LlmEscalation): C`, added by
[ADR-0079](0079-llm-steps-escalate-to-another-model-on-a-bad-outcome.md).)*

*(Amended 2026-10-31: an adapter may carry **bounded per-instance accounting
state**. ADR-0074 requires a harness whose CLI prices a run per step to emit a
cumulative `TurnEnd.costUsd`, and the canonical model has no place to keep a
running total — so `OpencodeHarness` holds a `StepCostTotals` keyed by the CLI's
session id: a step counts once by the id the CLI puts on it, and the retained
state is bounded by session and step count, shedding one eldest bucket. "No
shared mutable state" below reads as no *shared* state: the totals are the
adapter's own, per instance — the executor factory, the `/llm-events` harness map
and agentdebug each build their own adapter — and the step-id dedupe makes
`translate` idempotent per instance rather than order-dependent.)*

`id` equals the command's `kind` (`claude`, `codex`, …), and `reportsCost`
states — once, per adapter, with no default — whether the CLI itself prices
a run. `translate` is total (never throws) and returns a `Translation`
(`Events`, `Ignored`, or `Malformed(reason)`); `inspect` judges a zero-exit
attempt and returns `Success`, `Malformed(reason)`, or
`Terminal(exitCode)` for a recoverable stop the agent opted into
(Claude's `error_max_turns`). Adapters are constructed with their own
config only — no shared mutable state — so each is tested as plain
functions against recorded stdout lines, independent of the executor.

`homer.app.models.LlmEvent` (added in Task 1) is the six-kind canonical
model every consumer reads: `SessionStart`, `AssistantMessage`, `ToolCall`,
`ToolResult`, `TurnEnd`, `TransientError`. Six, deliberately — thinking
blocks, Claude `system` subtypes other than `init`, Codex `turn.started` /
`item.started` / `item.updated`, and any other line Homer does not act on
all translate to `Ignored`. They carry no decision Homer makes and adding a
seventh event per harness quirk would re-couple every consumer to that
quirk; the adapter boundary, not the event model, is where a CLI's
peculiarities are supposed to stay. `Command.Claude` and `Command.Codex`
both implement the new marker interface `Command.LlmCommand` (`cmd`,
`prompt`, `retry`, `label`, `timeout`) so the generic executor reads either
uniformly; `ClaudeRetry` is renamed `LlmRetry` (fields and the schema
`$defs` entry unchanged — no serialized shape changes, so no schema or
AGENT.md field-table edit follows).

**Cost-source rule.** `CostSource.NONE` (wire `none`) applies only to a
harness whose CLI reports no cost (`reportsCost = false`) when no
session-spend endpoint answered; a Claude attempt without a cost figure
keeps today's `cli` source and fallback reasons. `reportsCost` is what lets
the executor tell "this harness never prices runs" apart from "Claude
priced this run at zero because a fallback path failed" — collapsing the
two into one `null` check would silently mis-tag a degraded Claude figure
as an unpriced one.

**Call counting is per adapter.** The session-spend re-poll (ADR-0061)
waits until the endpoint has ingested as many calls as the attempt made,
counted as distinct message ids. That holds only where a message id is one
model request, so each adapter states it in `countsModelCalls`, again with
no default. Claude's `message.id` is one API response (`true`). Codex item
ids are not (`false`): one response can carry a message and a tool call, so
an item count over-states the calls, and every Codex run with a tool call
waited out the whole 30 s schedule and was marked `incomplete` although the
figure was complete. A harness that cannot count passes no expected count,
and the resolver instead accepts a figure once two successive readings
agree on both calls and cost and show at least one call; a figure still
moving when the schedule ends is charged and marked `incomplete: figure not
stable across polls`. The trade: an endpoint that stalls for a whole poll
gap mid-ingestion is accepted as an unmarked under-count, where the item
count never accepted early. Refused: counting agent messages only, which
under-counts a response that is only a tool call and so ends polling early
by construction. Gained besides the latency: the Codex expected count no
longer comes from agent-written stdout at all. This narrows ADR-0061's
promise that an under-count is always visible: it holds only for a harness
that counts model calls. That includes its 1 000-call cap mark, which is
not applied to Codex. That mark flags calls beyond a capped count that the
endpoint never confirmed, and a Codex attempt has no count to cap. Marking
Codex sessions above 1 000 items would key on a number that over-states
requests, and would leave the same stall trade in place for every smaller
session.

**Version pin stays outside the canonical model.** `ClaudeHarness.inspect`
reads `claude_code_version` off the raw `system` line in the `raw:
List<JsonObject>` parameter (the strict-parsed line list the executor
already holds) to enforce `requireClaudeCodeVersion`, exactly as today. This
keeps a Claude-only field out of `LlmEvent.SessionStart` rather than adding
`cliVersion: String?` that no other harness would ever fill.

**The broadcast stream is replaced.** `GET /api/v1/stream/processes/{id}`
(`claude.event` frames, in-process fan-out via `ProcessEventBroadcaster`)
is removed in favor of `GET /api/v1/stream/processes/{id}/llm-events`, a
per-subscriber SSE stream modelled on `LogTailService`'s file-follow: it
serves canonical events from byte 0 with `Last-Event-ID` resume (the
frame id is `cmdIndex:attemptIndex:lineOffset:eventIndex`, because one
stdout line can translate into several events), in
per-command mode (`?cmd=N`) or process mode (follows the process's LLM
harness commands in order, each retry attempt after the previous one), and
closes with `llm.end` once the process is terminal (per-command mode also
once a later command starts). The broadcast's one known consumer, a Slack connector
(subscribes with `progress.claudeEvents: true`, reads `assistant` /
`tool_use` blocks to live-edit a Slack message), treats a `404` as
"endpoint unavailable — step-level progress only" and so degrades rather
than breaks; it still needs one coordinated release to move to
`/llm-events`.

`ExecutionInfo`/`ExecutionInfoResponse` gain `kind: String` — the command's
serial name — persisted in the existing `executions` JSON column (no new
SQLite column, no migration); it defaults to `""` for rows predating the
field.

### Consequences

- Good: one retry/accounting/telemetry loop instead of N; a redaction path
  that only has to be correct once; Codex reaches feature parity with
  Claude (spans, usage, session-spend pricing) for free; the console
  becomes harness-agnostic, gated on `kind` rather than sniffing content.
- Good: adding a harness after this lands is its `Command` subclass (with
  the schema entry and AGENT.md section any command type needs), one adapter
  file, one fixture directory, one adapter test, and three registrations:
  `CommandExecutorFactory`, the `/llm-events` harness map and the console's
  `LLM_KINDS`. Nothing in accounting, telemetry, streaming or the console's
  rendering changes.
- Bad: the `llm.*` span attribute renames (`claude.session_id` →
  `llm.session_id`, etc.) break existing Langfuse filters/dashboards built
  against the old `claude.*` names — a breaking change called out in the
  changelog.
- Bad: the Slack connector needs a coordinated release to consume
  `/llm-events` instead of the removed broadcast stream.
- Bad: until a recorded Codex stream replaces them, the Codex adapter
  fixtures are hand-written from the documented CLI schema rather than
  captured from a real run — the same authoring risk ADR-0057 already
  accepts for modelled-but-unread Claude fields, now extended to a second
  harness.

- Neutral: `LlmEventStreamService` repeats `LogTailService`'s follow skeleton
  (poll, containment guard, final drain at a terminal status) instead of sharing
  it. The two are kept separate on purpose: `/logs` ships raw byte chunks addressed
  by one offset, while `/llm-events` must split lines on bytes, bound a held line,
  translate through an adapter, address events by `lineOffset:eventIndex`, and
  re-target retry attempts and later commands. A shared core would have to carry
  all of that as options for one of its two callers. The terminal-status sets are
  the one piece that must agree; `LogTailService`'s set points at the other.
- Neutral: the span attributes `claude.dropped_system_events` and
  `claude.max_turns_stop` keep their `claude.*` names because both are
  Claude-only features: the drop filter comes from
  `server.claudeDropSystemSubtypes` via `ClaudeHarness.stdOutFilter`, the
  terminal stop from `ClaudeHarness.inspect` on `maxTurnsExitCode`. A neutral
  name comes when a second harness grows either.
- Neutral: the config key `server.claudeStreamPollMs` keeps its name although it
  now paces the harness-neutral `/llm-events` poll. Renaming it would add a second
  breaking config change to this one for no behavior change; a rename with a
  deprecated alias can come with the next config-breaking release.

## Pros and Cons of the Options

### Option A — generic executor + adapter

- Good: the loop, the bounds, and every consumer live in exactly one place.
- Good: adapters are pure functions over recorded fixtures — no executor
  scaffolding needed to test a new CLI's translation.
- Neutral: the adapter interface is a contract every future harness must
  satisfy exactly, including harnesses whose event shape doesn't map
  cleanly onto the six kinds.
- Bad: the six-event model is a real constraint — a harness with a
  genuinely new concept still has to fit it or ADR-0070 gets revisited.

### Option B — abstract base executor with template methods

- Good: less new interface surface than a from-scratch adapter contract.
- Neutral: still lets each subclass keep some of its own state.
- Bad: nothing stops a "template method" override from reimplementing a
  piece of the loop (retry timing, accounting) slightly differently per
  harness, which is the exact drift this ADR exists to end.

### Option C — shared services only

- Good: least structural change; each executor keeps calling into common
  helpers.
- Neutral: each harness still owns its own retry loop and wiring.
- Bad: a bound implemented as a shared *service* is opt-in per caller; a
  third executor can simply not call it. The generic-executor option makes
  the bound structurally impossible to skip.

## Security (§12)

| Bound | Where it lives now | Denial test |
| --- | --- | --- |
| Session id charset / length | `LlmEvent.SessionStart` construction (`gateSessionId`) | adapter test: bad id → `null`; executor test: `null` id → resolver skipped, reason `no session id in output` |
| Model-call count ≤ 10 000 | generic accounting scan | executor test with 10 001 distinct message ids |
| Polling expected-calls ≤ 1 000 | generic executor | existing ADR-0061 test, moved |
| No call count: never accept a single reading, a zero-call reading, or two readings that differ | `LlmCostResolver` stable-figure rule | resolver tests: one repeated reading → 2 requests; repeated `calls: 0` → whole schedule, marked; same calls at a different cost → not settled |
| Stdout read chunk ≤ 1 MiB; held line ≤ 4 MiB per subscriber, a longer line dropped whole | `/llm-events` stream; the `/logs` tail reads ≤ 1 MiB per `log.chunk` too, cut on a whole UTF-8 character | stream tests with a 3 MiB unterminated line and a > 4 MiB line followed by a valid one; tail tests with a 3 MiB file and a character across the 1 MiB boundary |
| Redaction before egress | executor (span attrs), telemetry (all attrs), `/llm-events` (each translated event, re-encoded; one the mask breaks is dropped, logged once per stream). The raw line is not the unit: a secret written as `\uXXXX` escapes is cleartext only after parsing (CWE-200) | one test per sink with a declared secret in prompt, tool input, tool output, result text; stream test with an escaped secret in message text and tool input and output |
| `/llm-events` visibility | `canSee` in the handler, `404` otherwise; `404` for a `cmd` that is not an LLM harness command | route test: other principal → 404; `cmd` pointing at a `!<script>` → 404 |
| `/llm-events` process mode follows only this process's own artifacts | tracked path must resolve directly inside the process's artifacts dir (the `/logs` containment guard, reused) | stream test with a tracked path outside the dir → ignored |
| Stdout artifact containment at open (CWE-22, CWE-59): a symlink (dangling or not), hard link, FIFO or other non-regular file at the artifact name is never served | resolve: `PathSafety.assertContained` + `assertNoSymlinkComponentsBelow` → `404`; every open: `PathSafety.isLinkedOrSpecial` refuses, then `FileChannel.open` with `NOFOLLOW_LINKS`; a refused file is warned about once and yields nothing. Same guards in `/logs` (`LogTailService`) and `/artifacts` (`ArtifactService.resolveArtifactPath` at resolve, `LocalFileOperations.streamFile` again at open, immediately before `openBounded`). A FIFO swapped in between the check and the open still blocks that one subscriber's open; for a hard link the same race applies at `/artifacts`' open, since `O_NOFOLLOW` does not catch nlink: same-UID residual, ADR-0047. Whole-file reads that only feed this run's own `output.value` span (`LocalFileOperations.readText` in the command executors) keep no link check: the agent already runs as Homer's UID and can copy any file it can read into its own stdout without a link, so the check grants no principal anything, while refusing there would fail the command after it ran; Langfuse viewers already see every run's spans | stream and tail tests: symlink outside the dir, symlink to a sibling artifact, dangling symlink, hard link, FIFO, symlink planted after resolve → nothing served; a regular file still streams |
| SSE slots per caller | `withSseSlot`, unchanged | existing test extended to the new route |
| Cost charged exactly once per attempt under cancellation | generic executor, moved verbatim | existing ADR-0061 tests, run against the generic executor |

`/llm-events` redaction uses the run's in-memory `SecretRedactor` and, after
a restart, one rebuilt from the process's stored secret-input keys
(`processes.secret_input_keys`, ADR-0052) — the same exposure level as the
raw `/artifacts` endpoint, which has never redacted. So after a restart
only `secret: true` input values are masked in `/llm-events`; `@SYSENV:`-backed
values and the callback token are masked only while the run's redactor is in
memory, and the raw `/logs` tail and artifacts are never masked. Removing the broadcast
stream also removes a fan-out path where one producer's events reached
every subscriber of a process through a shared, bounded buffer
(`ProcessEventBroadcaster`); `/llm-events` is per-subscriber instead. Nothing
new is written where another principal can write: `kind` and `usage` are
server-derived from the canonical event stream, never agent-derived.

**Accepted residual: a cancelled attempt can go unpriced.** A genuinely
cancelled attempt skips the remote spend lookup, ADR-0061's availability
trade that keeps a cancelled run from holding its `NonCancellable`
accounting block open. It then charges only the CLI figure. A Codex attempt
has none, so it charges nothing, and neither does a Claude attempt cancelled
before its `result` event, which already held on main. A caller allowed to
cancel a run can therefore end it unpriced and outside `costLimits`. Closing
this needs a lookup that outlives the cancelled attempt without holding its
block: a bounded background reconcile, with its own queue bound per §12
rule 5. That is structural work of its own and a follow-up to this ADR, not
part of it.

## More Information

This ADR amends [ADR-0013](0013-stream-claude-events-ndjson-artifact.md),
whose broadcast stream it removes. Related: ADR-0057 (Claude output modelled only where read), ADR-0061 (price
Claude runs from session-spend), ADR-0063 (drop configured Claude `system`
subtypes from stdout).
