---
status: accepted
date: 2026-06-19
deciders: Lukáš Vlk
---
# ADR-0027: Read-only LangGraph graph introspection

## Context and Problem Statement

The console agent detail page should visualize the workflow of a
`!<langgraph>` command. The graph's topology lives only in Python code (a
`StateGraph`); the Kotlin `Command.LangGraph` model stores just the module
path. The only way to obtain nodes/edges is to import and compile the graph and
call LangGraph's `get_graph().draw_mermaid()`.

## Decision Outcome

Expose `GET /api/v1/agents/{name}/langgraph`, backed by `LangGraphInspectService`,
which shells out to a new read-only `homer_langgraph inspect --module <m>` mode
once per LangGraph command and returns the Mermaid source. *(Superseded in
part by [ADR-0073](0073-langgraph-topology-is-structured-data.md), 2026-10-09
note: the endpoint returns structured topology drawn by the console with dagre;
`mermaid` is deprecated and no longer used by the console.)*

Key choices:

- **On-demand, not at agent-scan time.** Introspection runs only when the
  endpoint is called, so importing agent Python never blocks server startup and
  a broken graph fails one request, not boot.
- **Read-only and checkpointer-free.** `inspect` compiles the graph without a
  SQLite checkpointer and performs no side effects.
- **Host-local subprocess inheriting the server environment, with the agent's
  env overlaid.** Unlike `LocalShellExecutor` (which clears env for workspace
  isolation), the inspect subprocess inherits the server process environment so
  `python` and the `homer_langgraph` package resolve, and then overlays the
  agent's declared `env` so the agent's `PYTHONPATH` makes its module importable
  — exactly as a real run does (a run gets that env via the workspace config;
  introspection has no workspace). `PYTHONWARNINGS=ignore` is set (unless the
  agent overrides it) so non-fatal import-time warnings do not pollute the
  surfaced error. This is the first backend path that imports/executes
  agent-authored Python **outside an actual run**; it runs at the **same trust
  level as a run** — operators already control which modules their agents load —
  bounded by a timeout, with failures returned as data (`error` field) rather
  than thrown.
- **Response DTO, not a contract surface.** `LangGraphGraphInfo` is a
  response-only type, so the agent JSON schema and `AGENT.md` are unaffected.

### Consequences

- Good: static topology visualization, enabling console UI to render the graph
  structure upfront.
- Bad: static topology only — no live per-run execution highlighting (would need
  per-node execution state Homer does not track). *(Amended 2026-10-09: per-run
  status and traversed-path overlays exist since
  [ADR-0044](0044-langgraph-resume-and-run-introspection.md) and
  [ADR-0075](0075-langgraph-traversed-path-from-checkpoint-writes.md).)*
- Bad: in Kubernetes deployments the server image must have `python` +
  `homer_langgraph` installed for introspection to succeed; otherwise the
  endpoint returns `error` entries and the console shows an inline message.
- Neutral: rendering is client-side (`mermaid`); the backend never produces an image
  (LangGraph's PNG path needs network or headless Chrome). *(Superseded in part by
  ADR-0073: still client-side, now dagre SVG instead of `mermaid`.)*

## Amendment (2026-09-30): why introspection is bounded, and why one permit covers a whole request

The decision above shells out to a Python interpreter on demand and says nothing
about how many of those may run at once. This amendment records the bound added
since, which until now lived only in `app/CLAUDE.md` and in
`LangGraphInspectService`'s own comments.

**Any host-side Python introspection needs a bound.** Concurrent inspects are
capped by `maxConcurrentInspects` (4), with the same bounded wait and
shed-on-saturation behaviour as `LangGraphRunStatusService` (ADR-0044): callers
past the limit wait for a slot, but only for `maxQueueWait`, and a saturated
endpoint answers with a `busy` error instead of accumulating suspended callers.
The two budgets are **independent**, so the host's worst case is their sum —
neither is a bound on the other (ADR-0047 rule 4).

**One permit covers a whole request, not one per command.** An agent with N
LangGraph commands would otherwise queue N times and could wait
N × `maxQueueWait`, while costing no extra interpreters: a request's commands
run in sequence, so at most one interpreter is alive at a time regardless. The
permit is therefore taken once around the whole `inspect` loop.

**The permit-leak rule is shared with ADR-0044.** As in
`LangGraphRunStatusService`, the "did I get a permit" flag is set *inside* the
`withTimeoutOrNull` block immediately after `acquire()` returns, with no
suspension point between the two, and read afterwards: `withTimeoutOrNull` can
report `null` even when the permit was taken (it cancels at the boundary), and
trusting its return value alone leaks that permit permanently. See ADR-0047
rules 4–5.

## More Information

- [ADR-0024](0024-langgraph-command-type.md) — `!<langgraph>` command type,
  the command execution model this introspection surface complements.
- [ADR-0023](0023-completion-continuations.md) — completion continuations,
  the resume mechanism LangGraph agents rely on.
- Reference docs: [`app/AGENT.md`](../../app/AGENT.md) and
  [`app/SERVER.md`](../../app/SERVER.md).
