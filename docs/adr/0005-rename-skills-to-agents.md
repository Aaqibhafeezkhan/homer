---
status: accepted
date: 2026-04-10
deciders: Lukáš Vlk
---
# ADR-0005: Rename "skills" to "agents" throughout the codebase

## Context and Problem Statement

Homer was originally built around a "skill" abstraction — a sequence of
commands invokable by name through an HTTP endpoint. By early 2026 the
project was being positioned as an orchestrator for autonomous and
semi-autonomous workloads (Claude Code runs, scheduled jobs, sub-pipeline
calls), and the wider industry had standardised on **agent** for that
shape. "Skill" had also picked up a competing meaning inside Anthropic's
Claude Code CLI itself, where `skills` is a separate JSON output field.
Carrying two definitions of the same word in the same product was
already a small but recurring source of confusion for new contributors.

We needed to decide whether to keep the term, rename, or quietly add
synonyms. The choice would touch every layer: Kotlin models, the HTTP
API surface, the on-disk config schema, environment-variable names, the
console UI, the standalone `agentdebug` CLI module, and every
documentation page.

## Decision Drivers

- **Industry alignment.** "Agent" is now the dominant term for what
  Homer runs; sticking with "skill" would force every new user to
  translate.
- **Self-collision risk.** Claude Code's own JSON output uses `skills`
  with a different meaning. The longer Homer kept the old term, the
  more places this collision would crop up.
- **One-shot cost vs. drift cost.** A rename is a single big diff today
  versus a slow accretion of "skill (a.k.a. agent)" caveats forever.
- **Public surface impact.** Anything that crossed the network boundary
  (routes, env vars, on-disk file names) was a breaking change either
  way — better to make it once.

## Considered Options

- **Option A — Rename everything in one commit.** Breaking change
  delivered atomically.
- **Option B — Add `agent` as a synonym, keep `skill` as primary.**
  Backward-compatible, deferred decision.
- **Option C — Keep "skill", document the collision.** Status quo,
  rely on docs to disambiguate.

## Decision Outcome

Chosen option: **"Option A — Rename everything in one commit"**, because
the public surface had to break either way, the alternatives all locked
in long-term doc / code drift, and a single big diff is easier to
review and revert than dual-naming.

The rename landed in commit `1441542` on 2026-04-10 and covered:

- **Kotlin layer** — package classes, services, executors, tests.
- **HTTP routes** — `/api/v1/skills` → `/api/v1/agents`,
  `/api/v1/skill/{name}` → `/api/v1/agent/{name}`.
- **Config keys** — `skillsSearchPath` → `agentsSearchPath`,
  `skillsCacheTime` → `agentsCacheTime`.
- **On-disk files** — `skill.json` / `skill.yaml` →
  `agent.json` / `agent.yaml`.
- **Env var** — `HMR_SKILL_NAME` → `HMR_AGENT_NAME`.
- **Module** — Gradle subproject `skilldebug` → `agentdebug`, with
  package, JAR name, and wrapper script renamed.
- **Console** — TS types, React components, hooks, API client; route
  `/skills` → `/agents`.
- **Command type** — `@SerialName("skill")` →
  `@SerialName("agent")`; `RunSkill` → `RunAgent` with the inner field
  `skillName` → `agentName`.

### Intentional exception

[`ClaudeOutput.kt`](../../app/app/src/main/kotlin/homer/app/models/ClaudeOutput.kt)
keeps a field named `skills`. That field deserialises Claude Code's own
`"skills"` JSON output key and has nothing to do with Homer's former
`skill` concept. A comment in the source pins this down so future grep
sweeps don't "fix" it.

*(Superseded in part by [ADR-0057](0057-claude-output-modelled-only-where-read.md):
`ClaudeEvent.System`, which held the `skills` field, was deleted, so this
exception no longer exists.)*

### Consequences

- Good: terminology now matches the rest of the ecosystem; new
  contributors stop having to translate.
- Good: collision with Claude Code's `skills` JSON field is gone
  everywhere except the one intentional, commented line.
- Good: future ADRs and docs no longer need disambiguating prose.
- Bad: every existing on-disk config and every external integration
  broke — hand-migration required (`skill.yaml` → `agent.yaml`,
  `skillsSearchPath` → `agentsSearchPath`, etc.). No compatibility
  shim was provided.
- Bad: pre-rename git history, commit messages, and old branches
  retain the legacy term — searches across history need both.

## More Information

- Implementing commit: `1441542` ("rename: skill → agent throughout
  entire codebase").
- Memory note (cross-session): the rename is recorded in user memory
  so future sessions don't suggest reverting it piecemeal.
