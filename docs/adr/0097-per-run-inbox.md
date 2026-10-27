---
status: proposed
date: 2026-10-27
deciders: Lukáš Vlk
consulted: ADR-0019, ADR-0047
---

# ADR-0097: A per-run inbox carries operator messages to a running run

## Context and Problem Statement

A `!<claude>` step can run for tens of minutes. A person watching it in the
console can see it heading the wrong way but cannot tell it anything; the only
correction is to kill the run and pay for the work twice (issue #80). Today a
`PostToolUse` hook keeps a human-in-the-loop question open and hands its answer
to the model. A question is the wrong channel: it posts a chat message per run,
counts against `maxOpenPerProcess` and stays open only to carry messages.

## Decision Drivers

- The correction must reach the model between tool calls, without ending the step.
- Executors, runners and the external-runner protocol must not change.
- It must work the same on every runner and for harnesses without hooks.
- Sending steers an agent that holds secrets: the grant required must be explicit
  and every denial must look like "not found" (root CLAUDE.md §12).
- Every resource a caller can multiply is bounded.

## Considered Options

- Write messages to the CLI's stdin (`--input-format stream-json`).
- Keep using a HITL question as the channel.
- Interrupt and restart with `--resume <session> --fork-session`.
- A per-run inbox the run reads when it chooses to.

## Decision Outcome

Chosen option: **"A per-run inbox the run reads when it chooses to"**.

An operator sends text to a run; the message is stored as `QUEUED` in the
`run_messages` table. The run takes its messages through a callback-token route
(used by a hook helper) or an MCP tool, which marks them `DELIVERED`. The queue
belongs to the run, not to a step.

**Trust boundary.** A caller holding a `control` grant on the agent can steer a
running agent that holds its secrets, at the times the run reads its inbox. Two
paths read it. The `homer-inbox` helper in the agent's hooks is an opt-in by the
agent author. The `check_messages` tool sits on the `homer-questions` MCP server,
so every Claude step wired to that server can read the inbox: a per-tool allow
is needed only when the step does not allow the whole server or run with
`bypassPermissions`. A run that reads neither way behaves as before. Taking is
callback-token only and scoped to the token's own process, so a child run cannot
read its parent's inbox. Message text is operator free text and never goes into
logs, spans or error text.

**Bounds.** `server.messages.maxTextLength` (default 10000 characters) and
`server.messages.maxPerRun` (default 100, every stored message of the run in any
state). Rows are deleted in the same transaction as the process row, on record
purge and on capacity eviction.

**Run end.** A message still `QUEUED` when the run reaches a terminal state, or
is left `UNKNOWN` by a restart, becomes `UNDELIVERED` at the point where the
run's open questions expire. The insert checks, inside the same write
transaction, that the process row is `CREATED` or `WORKING`. A send racing the
run's end therefore either lands before the status change, and is swept to
`UNDELIVERED`, or is refused. That run-end close runs after the terminal status
is committed, so a close that fails, or a server that stops between the two,
leaves `QUEUED` rows on an ended run. Startup reconciliation is the backstop: on
every boot it marks `UNDELIVERED` each `QUEUED` message whose run is no longer
`CREATED` or `WORKING`. Until then such a row cannot be delivered, since taking
is refused once the run has ended.

### Consequences

- Good: no change to executors, runners or the external-runner protocol.
- Good: works on every runner and for any harness that can call a hook or an MCP tool.
- Good: no chat post and no open question per run.
- Bad: delivery depends on the agent reading the inbox; `DELIVERED` means read,
  not acted on.
- Bad: a new table (schema v29) and a new stored free-text surface to bound and purge.

## Pros and Cons of the Options

### Write messages to the CLI's stdin

- Good: delivery at the same point, after a tool result.
- Bad: changes the step contract on all four runners and the external-runner protocol.
- Bad: Homer would decide when a step ends, which conflicts with `retry` and `escalate`.
- Bad: works for `!<claude>` only.

### Keep using a HITL question as the channel

- Good: exists today.
- Bad: a chat post per run, the open-question cap, and a question open only to carry messages.

### Interrupt and restart with `--resume <session> --fork-session`

- Good: needs no cooperation from the running step.
- Bad: reloads the whole session; a coarser, separate feature.

### A per-run inbox

- Good: see Consequences.
- Bad: see Consequences.
