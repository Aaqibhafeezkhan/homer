---
status: accepted
date: 2026-08-24
deciders: Lukáš Vlk
---
# ADR-0049: Agent definitions stay self-contained — no includes or step templates

## Context and Problem Statement

An `agent.yaml` / `agent.json` is one file, deserialized directly into `Agent`
by `AgentsService.parseAgentDir`. There is no preprocessing step: no `include`,
no `extends`, no fragment library. kaml rejects YAML anchors, and they would
only work within a single file anyway.

Users migrating a fleet ask for this: twelve agents sharing roughly seven
blocks of steps (workspace preparation, metric collection, cleanup) means
copying those blocks twelve times, and fixing the shared logic twelve times
after that. The request proposes the cheapest possible shape — a
`stepsFrom:` key expanded at load, before schema validation, leaving execution
untouched.

The duplication is real. The question is whether Homer is the right place to
remove it.

## Decision Drivers

- `agent.yaml` is a contract with more consumers than the loader.
- Agent directories are operator-controlled but *writable through the API*
  (ADR-0004), so anything that resolves paths at load time is attack surface.
- Load is a restart-critical path with existing DoS bounds.
- Whatever we choose has to answer the fleet-of-twelve case, not just decline
  it.

## Considered Options

- **`stepsFrom:` fragments expanded at load** — as proposed.
- **A first-class "library agent" invoked mid-pipeline** — make `!<agent>`
  synchronous so a shared block can be factored into its own agent.
- **No mechanism; the flat file stays flat.**

## Decision Outcome

Chosen: **no mechanism. Agent definitions stay self-contained.**

The reasons are cumulative rather than any single blocker:

**One file is a contract, not an implementation detail.** The same bytes are
read by the JSON-schema validator, the console's agent editor, `agentdebug`,
`AgentMdExamplesTest`, and the distributed authoring plugin. An include turns
"parse this file" into "resolve a graph", and every one of those consumers has
to implement the same resolution or quietly diverge from the server. That is
five places to keep in agreement, forever, to save copying seven blocks.

**It opens a cross-agent read channel that does not exist today.** An agent
directory is writable through the file-editing API, and a `stepsFrom:` path is
by definition attacker-influenced input resolved on the load path. Containment
against `agentsSearchPath` is solvable (`PathSafety` already exists), but
containment is not the whole problem: within the search path, agent A could
pull steps out of agent B's directory, which it cannot read now. Enforcing
"fragments only, from a fragments directory" means a second namespace with its
own rules — more surface than the feature is worth. (§12: name the boundary a
change crosses. This one crosses one, for convenience.)

**Errors get worse in the place they matter most.** A schema violation today
names a file and a line an author can open. After expansion it names a position
in a synthesized document that exists nowhere on disk. Agent loading is exactly
where good error messages pay off, because a bad agent is skipped and the
operator's only signal is that log line.

**It re-solves problems the flat format does not have.** Include graphs need
cycle detection, a depth bound, and an expansion-size bound to keep
`maxConfigFileBytes` meaningful — the same class of guard `maxAgentInvocationDepth`
exists for, rebuilt on the load path.

**And it does not actually remove the duplication.** Each of the twelve agents
still carries its own include lines, in the right order, with the right
variables in scope. It relocates the copy rather than eliminating it.

### What to do instead

In rough order of preference:

1. **Generate the definitions.** Homer loads plain files and does not care who
   wrote them. Keeping templates in the agent repo and committing the rendered
   `agent.yaml` removes the duplication completely — not partially — and gives
   full templating power (loops, conditionals, per-environment values) that no
   `stepsFrom:` key would. The deployed artifact stays flat, diffable, and
   reviewable, which is the property this ADR is protecting. The cost is
   honest: the file you review is not the file you edit, so the generator
   belongs in CI.
2. **Share the shell, not the steps.** Most "shared blocks" are shell. Put the
   scripts in the runtime image (ADR-0015) or materialize them into remote
   workspaces with runner `extraUploads` (ADR-0042), and let each agent call
   them with a one-line `!<cmd>`. This does not cover a shared block containing
   a `!<claude>` step or `resultEnvParam` capture.
3. **Make a genuinely independent block its own agent.** A shared *tail* —
   cleanup, reporting — is a fine `!<agent>` target. `!<agent>` is
   fire-and-forget, so a shared block in the *middle* of a pipeline needs
   `homer-dispatch` plus a continuation (ADR-0023), which is worth it when the
   block is substantial and not worth it when it is three commands.

### Consequences

- Good: `agent.yaml` remains "what you read is what runs", for every consumer.
- Good: no new path resolution, no new cross-agent read channel, no new
  expansion bounds on the load path.
- Bad: a fleet sharing many blocks needs a generator in CI, which is real work
  we are pushing onto users rather than doing once.
- Bad: this ADR has to be revisited if a case appears that generation genuinely
  cannot serve — a *runtime*-resolved shared block, say. Nothing here argues
  against that; it argues against load-time textual includes.
