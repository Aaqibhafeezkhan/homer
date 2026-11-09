---
status: accepted
date: 2026-08-24
deciders: Lukáš Vlk, Tomas Hladik
---
# ADR-0050: Per-agent telemetry is one block, and every start path goes through it

## Context and Problem Statement

Two changes arrived at the same time, from opposite directions, both adding
per-agent telemetry configuration and neither aware of the other:

- **One request** wanted to mute a noisy agent. A `mr-watcher` on `* * * * *`
  produces ~1440 empty traces a day against ~20 real ones, and
  `openTelemetryExport` is server-wide, so quieting it meant blinding everything.
  It added a scalar `telemetry: ALL | OFF` on the agent.
- **The other** wanted a run to be findable by its subject. The Langfuse traces
  API filters on `name`, `userId`, `sessionId` and `tags` — never on span
  attributes — so "what did we spend on this ticket?" is unanswerable unless the
  ticket id is a *tag*. It added `traceTag: true` on an input.

Merged as they stood, an agent would configure telemetry in two unrelated
places, and the scalar `telemetry` key would sit exactly where the tag
configuration belongs. Neither had shipped, so the cost of converging was zero
and the cost of not converging was a later breaking change.

There is also a third thread. The second request's own review found that
`handleRetry`, `handleResume` and `handleDebugExecution` build their root spans
outside `AgentRunStarter` and had therefore missed the 1.16.0 trace naming.
The same bypass meant the first request's muting did not apply to them either: a muted
agent's retry still exported a full trace.

## Decision Drivers

- One place answers "what does this agent send to the collector?"
- A per-input flag must not become the pattern for the next Langfuse dimension.
- Whatever gates run tracing must gate it on *every* path that starts a run.
- Neither surface is released; converging now is free.

## Considered Options

- **One `telemetry` block on the agent**, naming inputs by `paramName`.
- **Keep both as they arrived** — scalar `telemetry` plus per-input `traceTag`.
- **Per-input flags for everything** — `traceTag`, later `traceSessionId`,
  `traceUserId`, as each Langfuse dimension is wanted.

## Decision Outcome

Chosen: **one `telemetry` block**.

```yaml
telemetry:
  export: ALL | OFF
  traceTags: [project, mr]   # paramNames of declared inputs
```

*(Amended 2026-10-09: `telemetry.traceTags` is deprecated in favour of the top-level
`tags.fromInputs`; the two are unioned into `effectiveFromInputTags`, see
[ADR-0060](0060-run-tags.md). The validation rules below apply to the union.)*

The per-input flag was the more obvious design, and it had one real advantage:
no indirection, so it cannot drift. It lost on two counts.

**Trajectory.** Tags are the first of four Langfuse filter dimensions. Done as
an input flag, `userId` and `sessionId` arrive as `traceUserId: true` and
`traceSessionId: true`, and `Param` accumulates three booleans that all mean
"promote this into a Langfuse dimension", with no single place that answers what
an agent exports.

**Category.** `secret: true` looks like the precedent, but it is not the same
kind of thing. `secret` describes the *value's nature* — it is a credential, and
that has consequences in API responses, logs, telemetry and the database.
`traceTag` describes *one subsystem's data model*. The syntax rhymes; the
concepts do not.

The drift objection is answered by validating at load: every name in
`traceTags` must match a declared input and must not name a `secret` one, and
a name whose `paramName` contains `:` is rejected because the tag is emitted as
`paramName:value` and would be ambiguous to read back. A rename now fails the
agent loudly instead of silently producing no tag. The block also gains a
property the flag could not offer — the author controls tag order.

**Every start path goes through the same helper.** `startAgentRootSpan` and
`langfuseTraceTags` live in `AgentTracing.kt` and are called by
`AgentRunStarter` *and* by the retry / resume / debug handlers. This is the
actual fix for the recurring bug: those three handlers have now silently missed
two separate features by re-implementing root-span setup, and the answer is one
helper rather than a third round of remembering.

Muting is implemented as a **valid but unsampled** root span context, not an
invalid one. Every span Homer creates for a run parents off that context, so the
SDK's default `ParentBased` sampler drops the whole subtree. An invalid parent
would make each child a new root instead — exporting more, not less.

**Correction (2026-08-24).** "Every start path goes through the same helper"
above was too strong as written: it covered every path that starts a *run*, and
missed spans emitted outside a run's own scope. `KubernetesRunnerSession.close()`
built `runner.session.close` with no parent — `openSession` set one, `close` did
not — so it became a new root and was sampled on its own merits, exporting pod
name, namespace and cleanup mode for an agent that was muted. Fixed by carrying
the run's context on the session and parenting the close span to it.

The general rule this implies, and the one to check when adding telemetry: **an
unparented span is not a neutral default — it is an opt-out of muting.** Any
`spanBuilder(...)` without a `setParent` escapes `export: OFF`. That is
especially easy to miss for lifecycle work that runs after the run's scope has
unwound, where there is no current context to inherit implicitly.

### Also decided here: export failures are reported by wrapping, not by a JUL bridge

`OtlpHttpSpanExporter` reports failures through `java.util.logging`, which
nothing in Homer bridges into logback: the message bypasses the
`io.opentelemetry` logger, names no endpoint, and is throttled to once a minute
under a sustained outage. Combined with the silent fallback to the logging
exporter on a blank `serverUrl`, there was nothing to grep for.

Options were installing `jul-to-slf4j` or wrapping the exporter.
`FailureLoggingSpanExporter` wraps it: no new dependency, a message we control,
and unit-testable. The cost is real and accepted — the SDK's *other* JUL
diagnostics stay invisible, so a future problem that the SDK reports but the
exporter does not surface will still be silent. Revisit by installing the bridge
if that happens.

### Accepted risk: `export: OFF` is reachable by anyone with `EDIT` on the agent

Agent definitions are writable through the file-editing API (ADR-0004) under an
`EDIT` grant, so a principal with `EDIT` on an agent can now take that agent's
runs out of the collector. Before this change, telemetry was server-wide and an
agent author had no such switch.

Accepted, with the reasoning recorded rather than left implicit. `EDIT` already
permits rewriting the agent's commands outright — changing what it does is
strictly more power than hiding that it ran. The uncomfortable part is that
these are not quite the same thing: the trace is the *record*, it lands in a
different service with a different set of readers, and someone who can change
behaviour *and* erase the record is worse than someone who can only do the
first. The mitigation is that `EDIT` is already a high grant that should not be
handed out to principals whose runs you need to audit — the same reasoning that
makes the pre-existing `DEBUG`-grant spend bypass tolerable.

If run-level audit ever has to survive an untrusted `EDIT` holder, the fix is
not to remove this field: it is that `EDIT` needs splitting, and the trace is
only one of several records such a principal could already influence.

### Consequences

- Good: one block answers what an agent sends; the next Langfuse dimension has
  an obvious home and does not touch `Param`.
- Good: muting, naming and tagging now apply identically on every start path,
  including retry, resume and debug.
- Good: a renamed input fails the agent at load instead of silently untagging.
- Bad: naming inputs indirectly is only safe because of that load-time check, so
  the check is load-bearing — deleting it re-introduces silent drift.
- Bad: tag *cardinality* is still unbounded (only length is capped at 128). The
  cost lands in the collector, which Homer cannot see, so it is documented as an
  authoring rule rather than enforced. *(Amended 2026-11-09: bounded after all —
  a v4 trace copies the list onto every span of the run, so `MAX_TRACE_TAGS`
  caps it where it is stamped. See the amendment below.)*

## Amendment (2026-11-09): the trace name and tags ride on every observation

Langfuse **v4 keeps no separate trace record**: it derives a trace from the
observations it receives, reading `langfuse.trace.name` and
`langfuse.trace.tags` off each one. The 1.16.0 naming that this ADR built put
both on the root span only, which a v4 server cannot turn into a named, tagged
trace — and a filter on either finds nothing.

Two changes follow from that, and they are the whole amendment:

- **Both attributes go on every span of a run, not only the root.**
  `Span.stampLangfuseTrace(agent, tags)` writes them on the root before
  `processExecutor.execute`, and `LangfuseTraceAttributesSpanProcessor` copies
  exactly those two keys from a span's parent at `onStart`, so the whole
  subtree carries them as it starts. Copying "the parent's attributes" instead
  would export the run's request and process metadata onto every generation;
  copying anything other than those two keys is a defect, not a convenience.
  The hook swallows any failure — it runs inside the SDK's span-start path,
  where a throw surfaces in whichever caller happened to start a span — and a
  parent that is not a `ReadableSpan` (a muted run's wrapped context) copies
  nothing. A start path that stamped its root *after* `execute` would leave the
  run's first spans untagged, so the stamp precedes the call on every path.
- **The exporter declares OTLP ingestion version 4**, with
  `x-langfuse-ingestion-version: 4` beside the `Authorization` header. Without
  it a v4 server ingests a batch within minutes rather than seconds; a v3
  server ignores the unknown header, so one value serves both.

Tag cardinality is no longer unbounded. The list is copied onto every span of
the run, so it is capped at `MAX_TRACE_TAGS` (20) at the point it is stamped —
the bound has to live where the multiplication happens, and a bound in the
collector is not a bound (root §12 rule 4). It truncates rather than refusing,
with a WARN naming the agent: the run matters more than its last tag. This is a
bound on the payload a caller can multiply by span count
(denial_of_service / CWE-400); the per-tag length cap (`VALUE_PATTERN`, 128)
still applies to each tag that survives.

**The stored run tags and the exported trace tags can disagree past the
ceiling.** One derivation feeds both (ADR-0060), but the truncation is
export-only: the `?tag=` rows keep every derived tag, while the list Langfuse
sees stops at 20. An operator filtering Langfuse by a 21st subject tag finds
nothing where `?tag=` on the same value still returns the run. That is accepted:
`tags.fromInputs` is author-declared and static per agent, checked at load, so
the stored set is bounded without a cap of its own and a run cannot add to it —
the truncation exists only to bound per-span payload, which the stored set does
not multiply. `MAX_TRACE_TAGS` is bound to ADR-0060's
`MAX_RUNTIME_TAGS_PER_RUN` rather than restating the number, so the two
ceilings cannot drift.

The `telemetry.export: OFF` mechanism is untouched by this amendment and still
holds for the new attributes: a muted run's root is a wrapped, non-recording
span, its children are unsampled, and no `onStart` copy runs for them — the
processor is registered server-wide at SDK init, not per run, so there is
nothing to unregister. The truncation WARN is silent there too: nothing is
exported, so a warning about a truncated export would send an operator hunting
a payload that was never sent. That is asserted, not assumed: the muted case is
a test in `AgentRunStarterTelemetryTest`, `RetryTraceAttributesTest` and
`LangfuseTraceAttributesSpanProcessorTest`.
