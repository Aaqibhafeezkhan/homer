---
status: accepted
date: 2026-06-01
deciders: Lukáš Vlk
---
# ADR-0013: Stream Claude Code events live and ship NDJSON as the canonical stdout artifact

## Context and Problem Statement

A `Command.Claude` invocation can run for many minutes. Today the Claude CLI is
launched with `--output-format json`, which buffers every event and writes the
whole run as a single JSON **array** to the process artifact (`cmd_N.out`) only
at subprocess exit. Until that moment Homer has zero live observability: cost,
tool calls, and assistant messages are invisible while the run is in flight, and
nothing can be surfaced to the console or telemetry until the process is already
finished. We want events to be observable as they happen, not only post-mortem.

## Decision Drivers

- Long Claude runs need live progress (cost, tool calls, messages) instead of a
  single end-of-run dump.
- The change should not ripple through every other command type or add new
  on-disk artifacts to reason about.
- Post-mortem correctness and cost accounting must stay authoritative — the live
  path is observability, not a correctness signal.

## Considered Options

- **A — `--output-format stream-json` + tail the artifact file.** Switch the CLI
  to NDJSON; the on-disk artifact becomes NDJSON; tail it live and broadcast each
  event; keep the post-mortem reader authoritative.
- **B — tee Claude stdout through `ShellExecutor`.** Intercept the stream at the
  shared shell layer common to every command.
- **C — Homer-mediated rewrite (NDJSON in flight → array at exit).** Tail NDJSON
  live but rewrite the artifact back into the legacy JSON array on completion.
- **D — dual write (NDJSON sidecar + JSON-array canonical).** Keep the array
  artifact and additionally emit a separate NDJSON file for the live tail.

## Decision Outcome

Chosen option: **"A — `--output-format stream-json` + tail the artifact file"**,
because it gives live observability with the smallest blast radius: only the
Claude command's argv and its post-mortem readers change. The CLI emits NDJSON
(one event per line); Homer tails the stdout file with `RandomAccessFile`
polling and broadcasts each parsed event via `ProcessEventBroadcaster`; the
artifact file itself becomes the canonical NDJSON stream. The post-mortem
`validateClaudeOutput` stays the authoritative check for run correctness and
cost — it now parses NDJSON line-by-line and still fails the run on a malformed
stream.

### Consequences

- Good: events are observable live (console, telemetry) instead of only at exit.
- Good: the artifact is a single canonical NDJSON file — no extra files, no
  rewrite step, no ambiguity about which file is authoritative.
- Good: the post-mortem reader stays the single source of truth for correctness
  and cost, independent of the live tail.
- Resolved (2026-06-04): initially a **breaking change** for `resultEnvParam`
  with `src: STD_OUT` and a `jsonPath` transformer against Claude output;
  `ResultToEnvMapper` now re-assembles the NDJSON into a JSON event array so
  those transformers keep working (see the update below). Tooling that reads the
  raw artifact still sees NDJSON.
- Bad: the post-mortem reader and the live tail parse the same bytes
  independently and can in principle disagree; this is acceptable because only
  the post-mortem path gates correctness.
- Bad: `ClaudeTelemetryService` remains a post-mortem reader in Phase 1; a future
  phase may re-host it on the live tail.

## Pros and Cons of the Options

### Option A — stream-json + tail the artifact file

- Good: minimal blast radius (Claude command only); single canonical artifact.
- Good: keeps post-mortem correctness/cost authoritative.
- Resolved: `jsonPath`-on-stdout consumers keep working — the env mapper
  re-assembles the NDJSON into an array (see update); only raw-artifact readers
  see NDJSON.

### Option B — tee through ShellExecutor

- Good: one interception point.
- Bad: changes the contract of the shared shell layer used by **every** command
  type, not just Claude — far larger blast radius for a Claude-only need.

### Option C — Homer-mediated rewrite (NDJSON → array at exit)

- Neutral: would preserve the legacy array artifact for existing jsonPath users.
- Bad: extra I/O complexity and a second on-disk representation to keep in sync
  with the live stream; the rewrite step is pure overhead.

### Option D — dual write (NDJSON sidecar + array canonical)

- Bad: doubles artifact disk usage.
- Bad: operator confusion about which file is canonical, for no functional gain.

## More Information

Phase 1 ships all of: the NDJSON-aware post-mortem readers, the argv flip to
`--output-format stream-json`, the best-effort live tail
(`ClaudeEventTail` / `PollingClaudeEventTail`), and the new
`GET /api/v1/stream/processes/{id}` SSE endpoint that broadcasts each event as
`claude.event`. Phase 2 builds the console consumer of that stream; Phase 3
addresses the cost cap/throttle gap. See
[ADR-0002](0002-unified-error-model-at-http-boundary.md) for the error-handling
boundary the post-mortem reader fails through.

## Update (2026-06-04): jsonPath transformers restored

The breaking-change consequence noted above — `jsonPath` `resultEnvParam` with
`src: STD_OUT` breaking against Claude output — is reversed for the env-mapper
read path.
`ResultToEnvMapper` now detects that the stdout is not a single JSON document
and re-assembles the NDJSON lines into a JSON **array** of events before
evaluating the path. Agent authors get the pre-streaming JSON-array shape back
(e.g. `$[?(@.type=='result')].total_cost_usd`), so existing `jsonPath`
transformers work without switching to `regex`.

This is a narrower form of the rejected Option C: the re-assembly happens only
in-memory at extraction time, not as a rewrite of the on-disk artifact. The
canonical artifact stays NDJSON (so the live tail and raw-artifact consumers are
unaffected), and there is no second on-disk representation to keep in sync. The
alternative considered here was exposing only the terminal `result` event
object (cleaner scalar paths like `$.total_cost_usd`); the event-array shape was
chosen for backward compatibility with existing array-filter paths.

## Amendment (2026-09-27): the broadcast stream is removed

[ADR-0070](0070-llm-harness-adapter-boundary.md) removes the live broadcast this
ADR introduced: `GET /api/v1/stream/processes/{id}` and its `claude.event`
frames are gone, together with the polling tail that fed them. Live events are
served instead by `GET /api/v1/stream/processes/{id}/llm-events`, a
per-subscriber stream that reads the stdout artifact from byte 0, translates each
line into a harness-neutral event, and resumes via `Last-Event-ID`. A subscriber
that connects late therefore still sees every event, which the broadcast could not
offer. The rest of this decision stands: the Claude CLI still runs with
`--output-format stream-json`, the NDJSON stdout file remains the canonical
artifact, and the env-mapper re-assembly from the 2026-06-04 update is unchanged.
