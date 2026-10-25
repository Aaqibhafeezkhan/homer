---
status: proposed
date: 2026-10-25
deciders: Lukáš Vlk
consulted: ADR-0047, ADR-0066, ADR-0077
---

# ADR-0095: The built-in local runner can be turned off

## Context and Problem Statement

The built-in `local` runner always existed: `server.localRunner` only set its
tags and concurrency, and an agent with neither `runner:` nor `runnerTags:`
runs on it. A server meant only to orchestrate, with its agents on external
pull runners (ADR-0066), Kubernetes or SSH, therefore still ran any agent that
missed its routing inside its own container. There the run either fails for
lack of tools (the slim image has no agent CLIs) or executes agent code next
to the server's config, secrets and SQLite database. The operator had no way
to make the server refuse such a run.

## Decision Drivers

- An operator can keep agent runs out of the server container entirely
  (root CLAUDE.md §12: name the trust boundary, fail closed).
- A misrouted run fails with a reason that names the fix, without waiting.
- One agent directory can serve servers with and without a local runner.
- Existing configurations behave exactly as before.

## Considered Options

- `server.localRunner.enabled` (default `true`): with `false` no local
  consumer is registered and a claim for `local` fails before it is queued
- The same switch, but let a claim for `local` wait in the queue until its TTL
- Reject at agent load every agent that routes to `local` while it is off
- `server.localRunner.concurrency: 0` as "off"

## Decision Outcome

Chosen option: **`server.localRunner.enabled`, failing a claim for `local`
before it is queued**.

- With `enabled: false`, `RunnerWiring` registers no `LocalRunner` consumer and
  tells `RunnerRegistry` that `local` is off. `RunnerRegistry.forAlias("local")`
  then throws a `ConfigurationException` naming the setting. This covers an
  agent with no routing, one with `runner: local`, and a `runnerCmd` step
  targeting `local`. The message names no other runner alias (ADR-0077).
- **This amends ADR-0066's invariant** that every run reaches a runner through
  the claim queue. A run for a disabled `local` never reaches the queue.
  Nothing could ever claim it, so queueing it would only turn a configuration
  error into a stall of `runnerClaimTimeoutSec`.
- `tags` and `concurrency` are refused while `enabled` is `false`, in the
  model and in the JSON schema, because they would never apply.
  `GET /api/v1/runners` does not list a disabled `local`.

### Consequences

- Good: a server can guarantee that no agent run executes in its container.
- Good: a misrouted run fails at once, with the setting to change in its
  failure reason.
- Neutral: a run routed by `runnerTags` that only the local runner used to
  serve behaves like any unservable tag set under ADR-0066. Agent load warns
  that no configured runner serves the tags, and the run waits out its claim
  TTL. Failing every unservable tag set at once is a broader change to the
  claim queue, not part of this switch.
- Bad: the boundary covers runs only. For the console's LangGraph views the
  server still runs `homer_langgraph inspect` (ADR-0027) and `state`
  (ADR-0044) itself, and both import the agent's graph module on the server
  host. Moving them to the runner is separate work. Until then, an operator
  who must keep all agent code off the server host avoids `!<langgraph>`
  agents on it.

## Pros and Cons of the Options

### Let the claim wait until its TTL

- Good: no exception to the ADR-0066 queue invariant.
- Bad: every misrouted run stalls for `runnerClaimTimeoutSec` (300 s by
  default) before failing with a generic claim-timeout reason.

### Reject routed-to-local agents at load

- Good: the mistake surfaces before any run.
- Bad: one agent directory can no longer serve both a server with a local
  runner and one without. It also misses `runnerCmd` steps, whose target is
  resolved per step.

### `concurrency: 0`

- Bad: overloads a capacity setting with an on/off meaning, and a claim would
  still wait out its TTL.

## More Information

- Issue #33. The slim server image
  ([ADR-0094](0094-slim-server-image-for-pull-runner-deployments.md)) is the
  deployment this switch is for.
