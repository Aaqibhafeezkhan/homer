---
status: accepted
date: 2026-09-02
deciders: Lukáš Vlk
---
# ADR-0057: Claude CLI output is modelled only where Homer reads it

> **Note:** `ClaudeOutputJson` and `ClaudeTelemetryService`, named below, are now
> the harness-generic `LlmOutputJson` and `LlmTelemetryService` ([ADR-0070](0070-llm-harness-adapter-boundary.md)).
> The span attributes `telemetry.claude.model.source` and `claude.cost.parse_failed`
> are likewise now `telemetry.llm.model.source` and `llm.cost.parse_failed` (2026-10-09 note).

## Context and Problem Statement

`homer.app.models.ClaudeEvent` mirrors the Claude CLI's `--output-format stream-json`
events field by field, including many fields no Homer code ever reads.
`ClaudeOutputJson` sets `ignoreUnknownKeys = true`, which tolerates a key the CLI
*adds* — but not a key it *retypes*. A known key whose type changed fails the whole
event: `ClaudeTelemetryService.dispatchEvent` catches the `SerializationException`,
counts a malformed event, and the event's handler never runs. The failure is a WARN
in the log and a counter on the parent span, so it is invisible in Langfuse, which is
where the data was going.

Two instances of exactly that are live against CLI 2.1.x. `system.plugins` is
declared `List<String>` and the CLI emits objects (`{name, path, source, version}`),
so every run whose CLI has a plugin installed loses its `system` event — and with it
the run's model, which is why those generations report `model: unknown` while their
cost is correct (cost is read from the `result` event, on a separate path).
`result.permission_denials` is declared `List<String>` and also carries objects, so a
run with any denied tool call loses its `result` event — and with it the *only*
priced observation Homer emits, i.e. its cost and tokens vanish from Langfuse
entirely. Neither field is read by Homer at all. Measured over ~14 hours of one
production deployment: 27 lost `system` events and 12 lost `result` events.

## Decision Drivers

- A field Homer does not read cannot be worth an event. The blast radius of a
  declaration is the whole event, and it is paid by data that *is* read.
- The CLI is an external, frequently-released dependency whose stream shape is not a
  stable contract. It will drift again, and the pin (`requireClaudeCodeVersion`)
  guards the *version*, not the payload's shape.
- The failure mode is silent by construction — a WARN nobody reads, and an
  observation that looks merely uninformative rather than broken.
- §12: the stream is agent-influenced input. Whatever replaces the typed decode has
  to be total, never throwing on a shape it did not expect.

## Considered Options

- **Declare only the fields Homer consumes; read the rest, where consumed, off the raw
  `JsonObject`** (chosen).
- **Fix each field's type as it drifts.**
- **Type unread fields as `JsonElement?`.**
- **Turn on `coerceInputValues = true`.**
- **Wrap every field read in `runCatching`.**

## Decision Outcome

Chosen option: **model only what is read.** `ClaudeEvent` and its nested types
declare exactly the fields Homer consumes; a field with no reader is deleted rather
than typed. Where a payload is read outside the typed model — today the `system`
event, which Homer reads for five values and nothing else — it is read from the raw
`JsonObject` through total accessors (`this[key] as? JsonPrimitive`), never through
kotlinx's throwing `.jsonPrimitive` / `.jsonArray` helpers, whose
`IllegalArgumentException` is not what the `SerializationException` catch is for.
Every field that *is* declared and read carries a default, so a missing field costs
one attribute rather than the event.

For the `system` event this is less code, not more: `handleSystem` already read
`tools` and `mcp_servers` raw, and the typed `ClaudeEvent.System` contributed nothing
but the ability to fail. `ClaudeEvent.System` is therefore deleted outright.

The model the run reports is additionally resolved through a ladder rather than from
one event, so that losing any single event degrades the answer instead of erasing it:
the `system` line, then the first main-agent `assistant` message's model, then the
`result` line's own `modelUsage` accounting, then the model the step configured, then
`unknown`. The rung that answered rides along on the generation as
`telemetry.claude.model.source` — the point being that the next drift is visible in
the data rather than only in a log line. That mirrors `claude.cost.parse_failed`,
which the executor already sets for the same reason.

The rejected options:

- **Fix each type as it drifts** is the status quo, and the status quo is two live
  defects found by reading a log nobody reads. Every unread field stays a way for a
  future CLI release to delete a run's telemetry.
- **`JsonElement?` on unread fields** is total, and keeps a field that has no reason
  to exist; it also still lets a *required* declared field's absence fail the event.
- **`coerceInputValues`** only coerces `null` into a declared default for
  non-nullable fields. It does not touch a type mismatch, so it would not have
  prevented either instance — and as a blanket setting it hides real problems in the
  fields Homer does read.
- **`runCatching` per read** puts noise at every read site and leaves the typed
  declarations, i.e. the cause, in place.

### Consequences

- Good: a CLI release that retypes a field Homer does not read is a no-op, because
  the field is no longer declared.
- Good: the run's model survives the loss of any one event, and a run with a denied
  tool call keeps its priced observation — so Langfuse's cost data stops having holes
  nobody can see.
- Good: `telemetry.claude.model.source` makes the next degradation legible from the
  data. A generation whose source is `configured` or `unknown` says the ladder is
  compensating for something.
- Bad (accepted): the `system` event is no longer type-checked at all, so a reader
  added later has to do its own total access. The KDoc on `ClaudeEvent` says so.
- Bad (accepted): deleting unread fields makes the model a poorer description of the
  CLI's stream. A contributor who wants to know the full payload reads a captured
  fixture (`app/app/src/test/resources/claude/`), not the Kotlin.
- Bad (accepted): a Langfuse filter saved on `name = "Result"` stops matching new
  runs, because the generation is now named after the step that produced it.
  Historical data is unchanged.
- Neutral: this closes nothing about *polymorphic* drift. An unknown `ContentBlock`
  or `UserContentBlock` discriminator — a new content-block type from a future CLI —
  still fails its whole `assistant` / `user` event and drops that event's tool spans.
  Fixing that means a `polymorphicDefaultDeserializer` on the shared
  `ClaudeOutputJson`, which three readers share; it is a separate decision.

## More Information

- The version pin that does not cover this: `server.requireClaudeCodeVersion`
  compares the version the CLI reports after a successful run, which says nothing
  about the shape of what it printed.
- Telemetry the export depends on: [ADR-0050](0050-per-agent-telemetry-block.md)
  (per-agent telemetry block), [ADR-0013](0013-stream-claude-events-ndjson-artifact.md)
  (the NDJSON stream as an artifact).
- Redaction of the same stream: [ADR-0052](0052-redaction-metadata-is-captured-with-the-run.md).
