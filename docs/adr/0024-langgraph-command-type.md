---
status: accepted
date: 2026-06-16
deciders: Lukáš Vlk
---
# ADR-0024: `!<langgraph>` command type — thin Kotlin launcher for a Python runtime

## Context and Problem Statement

Human-in-the-loop workflows that park mid-graph and resume later (ADR-0023's
completion continuations) are a natural fit for LangGraph, whose ecosystem is
Python. Homer's executors are Kotlin. We need a command type that lets an agent
run a LangGraph graph and resume it through Homer's existing dispatch/continuation
machinery, without reimplementing graph execution or durable checkpointing in
Kotlin.

## Decision Drivers

- Graph execution, checkpointing, and side effects belong in the Python LangGraph
  runtime, not in Kotlin.
- Resume must flow through the continuation machinery already built in ADR-0023,
  which re-fires a target agent run carrying the watched process's result.
- The durable checkpoint location is a server-level concern (one store per Homer
  instance), not something each command should redefine.
- Keep the contract surface (model ↔ schema ↔ AGENT.md) minimal.

## Considered Options

- A thin Kotlin launcher that shells out to `python -m homer_langgraph` and lets
  Python own the graph and checkpointer.
- A full Kotlin LangGraph reimplementation / JVM port.
- A long-lived Python sidecar process Homer talks to over RPC.

## Decision Outcome

Chosen option: **thin Kotlin launcher**, because it keeps the division of labor
clean — Kotlin launches and captures output; Python owns graph execution, side
effects, and checkpointing — and reuses the existing command-executor and
continuation paths without new infrastructure.

`Command.LangGraph` runs `python -m homer_langgraph run --module <module>`, passes
the workspace env through (so resume params arriving in env reach the runtime),
and injects the durable state directory as `HOMER_LG_STATE_DIR`, resolved from
server config (`server.langGraph.stateDir`, else derived beside the database
path), not from a per-command field. *(Amended 2026-10-09 by
[ADR-0046](0046-owner-scoped-langgraph-checkpoints.md): the configured
directory is now a root, and each run gets `<root>/<owner-segment>`.)*

Two conventions are enforced/established:

- **`dispatch.enabled: true` is required.** An agent with a `!<langgraph>` command
  resumes through the dispatch/continuation machinery, so it must be dispatchable.
  This is checked at load time in `AgentsService.parseAgentDir`; an agent that
  omits it is rejected and skipped.
- **Resume re-runs the whole agent from the top.** When a parked thread resumes,
  Homer re-fires the agent run (carrying the thread id in `HMR_LG_THREAD`), not
  just the langgraph step. Earlier commands must be self-contained or guarded with
  `when:` on `HMR_LG_THREAD` so they don't repeat side effects on resume.

### Consequences

- Good: no graph engine or checkpointer in Kotlin; Python owns the hard parts.
- Good: reuses the ADR-0023 continuation path for resume; no new resume protocol.
- Good: one durable state dir per server, consistent with the SQLite store layout.
  *(Amended by ADR-0046: one directory per owner under that root.)*
- Bad: introduces a Python runtime dependency on the execution host (shipped in a
  later commit) — Kotlin alone cannot run a langgraph agent.
- Bad: re-running the whole agent on resume puts the idempotency burden on the
  author (mitigated by the `HMR_LG_THREAD` guard convention).

## More Information

- [ADR-0023](0023-completion-continuations.md) — completion continuations, the
  resume mechanism this command type relies on.
- [ADR-0020](0020-durable-state-in-sqlite.md) — the durable-store layout the
  langgraph state dir derives beside.
- Reference docs: [`app/AGENT.md`](../../app/AGENT.md) (`Command.LangGraph`) and
  [`app/SERVER.md`](../../app/SERVER.md) (`server.langGraph.stateDir`).
