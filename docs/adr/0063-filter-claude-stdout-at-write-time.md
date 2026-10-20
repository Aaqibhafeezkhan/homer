---
status: accepted
date: 2026-09-16
deciders: Lukáš Vlk
---
# ADR-0063: Drop configured Claude `system` subtypes from stdout at write time

> **Note:** `ClaudeExecutor`, `ClaudeTelemetryService` and `ClaudeEventTail`, named
> below, were replaced by the harness-generic `LlmHarnessExecutor`,
> `LlmTelemetryService` and `/llm-events` stream in [ADR-0070](0070-llm-harness-adapter-boundary.md).
> Likewise `inspectClaudeOutput`, `parseClaudeNdjson` and `scanClaudeAccounting` are now
> `LlmHarness.inspect`, `parseNdjson` and `scanLlmAccounting` (2026-10-09 note).

## Context and Problem Statement

A `!<claude>` step running a third-party model through an LLM proxy (`model:
glm-5.3-flash`, `ANTHROPIC_BASE_URL` pointing at the proxy) was killed by
`server.maxOutputBytes` roughly ten minutes into a two-hour budget. Its
`cmd_N.out` held 10,500,257 bytes over 49,935 lines; 9.73 MB (93 %) of that was
49,813 `{"type":"system","subtype":"thinking_tokens",…}` progress events of about
195 bytes each — one per one or two thinking tokens. The conversation itself was
0.77 MB.

Claude Code emits these events only on the third-party path, where it cannot get
real usage from the provider (`estimated_tokens`, `[claude-code:unrecognized_model]`
on stderr). Native Anthropic models emit none. The events cannot be suppressed from
the caller side: `includePartialMessages` is off, and `MAX_THINKING_TOKENS=0` still
produced them. Nothing in Homer reads them — `ClaudeOutput.kt` does not model the
subtype, and no console, telemetry, accounting or env-mapping consumer looks at it.

The cap is a byte budget on the file, so a read-side filter changes nothing. The
filter has to run before the bytes reach `cmd_N.out`.

## Decision Drivers

- The cap must keep measuring what a reader will actually load: `inspectClaudeOutput`
  parses the whole file after every step, `maxArtifactBytes` bounds what the
  artifact API serves, and the console renders the result. Raising the cap trades one
  failure for three.
- Stdout must stay pure Claude NDJSON: `parseClaudeNdjson`, `scanClaudeAccounting`,
  `ClaudeEventTail` and `ClaudeTelemetryService` all parse it. Homer must not
  inject its own lines there.
- A future useful `system` subtype must not be swallowed by accident, and a future
  noisy one must be droppable without a Homer release.
- The default should encode what is already known to be noise; operators on the
  proxy path should not have to rediscover the kill.
- Cmd and Script steps must be untouched byte for byte.
- Memory per run stays constant (repo §12, rule 4).

## Considered Options

- Option A — Raise `server.maxOutputBytes` (default or per deployment).
- Option B — Wrap the CLI in a shell pipeline (`claude … | grep -v …`).
- Option C — A line-aware filter stage in every `ShellExecutor`, driven by an
  optional parameter on `cmd()`, with the subtype list configured on the server
  and defaulting to `["thinking_tokens"]`.

## Decision Outcome

Chosen option: **"Option C — executor-level write-time filter, configured
subtypes, non-empty default"**.

- `ShellExecutor.cmd` gains `stdOutFilter: OutputLineFilter? = null`. Null keeps
  today's path exactly (the local runner keeps its OS file redirect).
- `LineFilteringOutputStream` splits the byte stream into `\n`-terminated lines,
  decodes a copy only to decide, and writes the original bytes of kept lines. An
  unterminated line longer than 1 MiB streams through unfiltered until the next
  newline, so the buffer is bounded.
- `ClaudeSystemEventFilter` drops a line only when it contains a configured subtype
  as a quoted string **and** parses to a JSON object with `type == "system"` and a
  `subtype` in the configured set. It counts events and bytes per subtype.
- `server.claudeDropSystemSubtypes: List<String>` (default `["thinking_tokens"]`,
  `[]` disables). Entries must match `^[a-zA-Z0-9_]+$`; `init` is refused because
  that event carries `claude_code_version`, which `requireClaudeCodeVersion` reads.
- After every attempt, `ClaudeExecutor` appends one
  `[HOMER] Dropped <n> system/<subtype> events (<bytes> bytes) from stdout` line per
  dropped subtype to the attempt's stderr file, and sets
  `claude.dropped_system_events` on the span. Stderr is where Homer's own markers
  already live (`[HOMER] Output cap exceeded`), and nothing machine-reads them.

### Consequences

- Good: the run in the context section writes ~0.8 MB instead of 10.5 MB and is no
  longer killed; `result`, `resultEnvParam`, cost accounting, `maxTurnsExitCode`,
  the live tail and Langfuse telemetry see the same events as before minus the
  dropped subtype.
- Good: native Anthropic models are unaffected in content; the drop is visible per
  run in `cmd_N.err` whenever it happened.
- Good: the next noisy subtype is a config change.
- Bad: the cap now measures kept bytes. A run emitting only dropped events cannot
  trip it; the run is still bounded by its timeout and the filter's memory is
  constant. Accepted. Recorded as an accepted risk in `SECURITY.md`; a scanner
  will keep reporting it (CWE-770), and the answer is this
  bullet — counting raw bytes would kill the runs the filter exists to keep
  alive.
- Bad: the local runner, for Claude steps only, moves from an OS redirect to a pipe
  and a pump thread. A background child that inherits stdout keeps the pipe open
  past the main process's exit, so the pump join is bounded (5 s); past the bound a
  warning is logged and the stdout file is closed, so a later write by that child
  fails instead of growing the artifact after the cap check. The pump thread itself
  survives until the child's next write or exit — a pipe read blocked in another
  thread is not woken by `close()` — so one parked daemon thread per such step, for
  the child's lifetime, is the accepted residue. Kubernetes already pumps its exec
  stream, which ends with the exec channel. (2026-10-14: a pod child can hold the
  exec open past the command's exit too, so the Kubernetes pump and stdin joins
  are bounded the same way.)
- Bad: test doubles that mock `ShellExecutor.cmd` for Claude steps must match the
  new parameter.
- Bad: the default is non-empty, so every Claude step on every model — not only the
  proxy path — takes the pipe-and-pump route on the local runner. Native models are
  unaffected in content; they are not on the unchanged I/O path. `[]` restores it.
- Bad: while a filter is active the cap is checked against the file, which lags the
  child by at most one buffered line (≤ 1 MiB); on the SSH runner the trailing partial
  line is flushed after the post-exit re-check. Both stay inside the documented soft
  ceiling.
- Neutral: with `retry.attempts > 1` and the default artifact template each attempt
  truncates `cmd_N.err`, so only the last attempt's `[HOMER] Dropped` line survives —
  the same lifetime the cap and TTL markers already have — and
  `claude.dropped_system_events` keeps the last non-zero attempt's total.

## Pros and Cons of the Options

### Option A — Raise the cap

- Good: one-line change.
- Bad: `inspectClaudeOutput` materialises the whole file into a `List<JsonElement>`
  (~330 k objects at 64 MiB on a 1536 MB heap); `maxArtifactBytes` and the console
  become the next failures.
- Bad: still stores 127 bytes of envelope per thinking token.

### Option B — Shell pipeline around the CLI

- Good: no Homer code in the byte path.
- Bad: loses the CLI's exit code without `pipefail`, needs a shell on every runner
  image and host, and breaks the argv-list semantics every runner preserves.

### Option C — Executor-level filter (chosen)

- Good: one mechanism for all three runners; the cap semantics stay local to the
  executor; stdout stays pure NDJSON.
- Neutral: a new optional parameter on the `ShellExecutor` contract.
- Bad: the local runner needs a pump thread for filtered steps.

## More Information

- [ADR-0013](0013-stream-claude-events-ndjson-artifact.md) — NDJSON is the
  canonical stdout artifact this filter preserves.
- [ADR-0061](0061-price-claude-runs-from-session-spend-endpoint.md) — the
  accounting scan that must keep seeing `result` and `assistant` events.
