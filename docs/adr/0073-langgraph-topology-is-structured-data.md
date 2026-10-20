---
status: accepted
date: 2026-10-02
deciders: Lukáš Vlk
---
# ADR-0073: LangGraph topology is structured data, drawn by the console

## Context and Problem Statement

ADR-0027 and ADR-0044 ship a LangGraph command's workflow graph to the console
as Mermaid source (`draw_mermaid()`), rendered client-side by the `mermaid`
library. That source carries LangGraph's own hard-coded styling (lavender
`classDef default`, `curve: linear`), so the console pins a fixed white canvas
in every theme and cannot offer dark mode. Run status is layered on by
appending `classDef` lines, and per-sub command dots are injected by patching
mermaid's rendered SVG through its private node-id scheme — a coupling that
needs a runtime canary to detect breakage. Node names come from agent code and
pass through a Mermaid parser before reaching the DOM via `innerHTML`.

The runtime already holds the topology as data: `compiled.get_graph()` exposes
nodes and edges with `source`, `target`, `data` (route label) and
`conditional`.

## Decision Drivers

- Graphs must follow every console theme, including operator-supplied themes.
- Status and progress overlays must not depend on a renderer's private DOM.
- Agent-derived text must reach the DOM as inert text (ADR-0047 rule 2).
- Every resource a caller or a run can multiply is bounded locally (ADR-0047 rule 4).

## Considered Options

1. Keep Mermaid; strip LangGraph's styling client-side and theme via `themeVariables`.
2. Emit structured topology; lay it out with `@dagrejs/dagre`; render React SVG.
3. Emit structured topology; render with `@xyflow/react` (interactive canvas).
4. Emit structured topology; lay out with `elkjs`.

## Decision Outcome

Chosen option 2.

- `homer_langgraph` emits `topology: {nodes: [{id}], edges: [{source, target,
  label, conditional}]}` from `state` and from `inspect --json` — our own
  minimal shape, not LangGraph's `to_json()` (which carries runnable class
  paths the console has no use for).
- `mermaid` stays in the runtime output and both HTTP responses, deprecated;
  the console no longer reads it. Plain `inspect` (no flag) is unchanged.
- The backend validates the topology where it uses it: at most 200 nodes and
  1000 edges, ids 1–200 characters, labels at most 200, unique node ids,
  edges between known nodes. A violation turns the agent-page entry into an `error` and
  drops `topology` from run status (the console then lists node statuses).
- Both introspection subprocess runners read at most 2 MiB of stdout and
  16 KiB of error text. `state` output carries checkpoint-derived text that a
  run can influence, and neither runner bounded its read before.
- The console lays the graph out with `@dagrejs/dagre` and draws it as React
  SVG with theme tokens; it applies its own render cap (200 nodes / 300
  edges) because layout cost is its own resource, and synchronous layout cost
  grows steeply with edges. `mermaid` is removed.

Unchanged: the introspection subprocess and its concurrency bounds
(ADR-0027, ADR-0044) — the topology exists only in Python code, so the agent
module is still imported on the Homer host at a run's trust level.

### Consequences

- Good: graphs follow every theme; status/progress overlays are React props;
  no renderer-private DOM coupling; no Mermaid parser or `innerHTML` sink on
  agent-derived text; a large dependency (and its CVE stream) leaves the
  console bundle.
- Good: interactivity (tooltips, links to sub runs) is ordinary React work.
- Bad: layout quality is ours to maintain — dagre does ranking and routing,
  but label sizing is an estimate (no DOM measurement).
- Bad: `inspect --json` fails on a command whose custom `python` carries an
  older `homer_langgraph`; that agent page shows the error until the runtime is
  upgraded. The bundled runtime always matches.
- Neutral: the inspect/state stdout file on disk is not size-capped while the
  subprocess runs (bounded by its timeout); that is same-UID host access at a
  run's trust level, as ADR-0027 already accepts. Homer reads the stdout and
  stderr temp files through handles opened before the subprocess starts and
  never re-opens their paths, so a path swapped mid-run (FIFO, symlink) can
  neither block nor redirect the read.

### Pros and Cons of the Options

- Option 1: smallest change, but keeps the private-DOM dot patching, the
  `innerHTML` sink and the dependency; theme colors must be read from CSS at
  render time and fail on themes mermaid's color parser rejects.
- Option 3: pan/zoom for free, but a heavy dependency for a read-only diagram.
- Option 4: better layout for dense graphs, but large and EPL-2.0-licensed
  (fails the console license gate).

## Amendment (2026-10-08): inspect/state output is capped at write time

The "Neutral" consequence above — *the inspect/state stdout file on disk is not
size-capped while the subprocess runs (bounded by its timeout)* — is
**superseded and its acceptance withdrawn**. Both runners now pipe stdout and
stderr and drain them under a byte cap while the subprocess runs; nothing is
written to disk. This reverses the accepted risk recorded for a CWE-770 finding.

Three points the original reasoning missed:

- **The trigger is not a run.** "A run's trust level" describes what executes,
  not who triggers it: `GET /api/v1/agents/{agentName}/langgraph` is gated by
  `anyGrant` and `GET /api/v1/processes/{id}/langgraph` by `canSee`, so a
  principal holding only a read grant — who could never start a run — can make
  Homer execute the module and store its whole output, repeatedly. Making Homer
  act is itself an escalation (ADR-0047), and availability is in scope for
  authenticated readers.
- **`state` output is shaped by agent-written data.** `homer_langgraph state`
  prints the thread's checkpoint state, and the checkpoint store is something the
  agent writes, so the bytes stored are not purely a function of
  operator-authored module code (ADR-0047 rule 2).
- **A timeout is not a byte bound (ADR-0047 rule 4).** At disk speed the 15 s
  timeout times the four inspect and four state permits is tens of gigabytes, and
  nothing local to the runners limited bytes. The old `descendants()` kill also
  missed a double-forked, reparented grandchild holding the temp file open, whose
  inode kept growing after the path was unlinked.

The fix is local: `ProcessGraphInspectRunner` and `ProcessGraphStateRunner` spawn
through `ProcessGroupSupport` with `PIPE` redirects, each stream drained by a
bounded reader holding at most `MAX_GRAPH_STDOUT_BYTES` /
`MAX_GRAPH_STDERR_BYTES` (+1 byte each). Stdout past the cap kills the process
group at once and returns the existing "output exceeds … bytes" error; stderr
past its cap is drained and discarded. With no temp files there is no path to
swap, so the "handles opened before start" workaround is gone too. The
concurrency bounds, timeouts, state cache and error strings are unchanged
(ADR-0027, ADR-0044).

## Amendment (2026-10-09): a cancelled caller's subprocess is reaped when the wait returns

The runners wait for the interpreter with one blocking `waitFor(timeout)`, so a
caller cancelled while it runs (client disconnect, shutdown) keeps its permit,
the child and both readers until the child exits or the timeout fires; the
group reap then runs as on any other unwind. This is **accepted** rather than
polled away: cancellation gives no amplification, because an uncancelled caller
holds the same single permit and interpreter for the same at most 15 s, and the
total is already bounded by the per-service permits and the `maxQueueWait` shed
(ADR-0027, ADR-0044), which cancellation cannot bypass. The exposure is late
release of work the caller was entitled to start, not an unbounded resource.

## More Information

- [ADR-0027](0027-langgraph-graph-introspection.md), [ADR-0044](0044-langgraph-resume-and-run-introspection.md),
  [ADR-0047](0047-security-by-design-and-threat-model.md).
