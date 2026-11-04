---
status: proposed
date: 2026-11-04
deciders: Lukáš Vlk
consulted: ADR-0047, ADR-0058, ADR-0066, ADR-0077, ADR-0096
---

# ADR-0099: `runnerAffinity` prefers the instance that served the flow's earlier runs

## Context and Problem Statement

A flow of `!<agent>` hops often warms something on the machine that ran its
first step: a container image layer, a checkout, a compiler cache, a model
file. The claim queue (ADR-0066) hands each run to whichever matching consumer
is free, and ADR-0096 deliberately rotates the choice among idle consumers, so
a flow's second step lands on a different instance about as often as not and
pays to warm the same cache twice. Agents cannot express "keep this flow on one
machine" — `runnerTags` selects a *set* of instances, never one of them — and
the queue has no notion of a flow at all.

## Decision Drivers

- A flow root already exists and is Homer-stamped, never agent-writable
  (ADR-0058), so "this flow" is a key the server can trust.
- The claim queue is the one place every run passes through; a preference
  anywhere else would be a second scheduler.
- The choice must stay soft: an instance that has gone away, is draining or no
  longer matches the run's filter must not hold the run back, and an agent that
  declares no affinity must not be delayed by another agent's preference
  (ADR-0047 rule 5: bound the wait).
- Runner aliases and instance names are admin-only, and a run-visible error
  text may never name one (ADR-0077).
- The memory of "which instance served this flow" is per-flow state of bounded
  value; a table, an index and a schema version are not worth it.

## Considered Options

- **A per-agent pinned runner** (`runnerAlias` on the agent): the operator
  names the instance. Simple, but static — it cannot follow a flow, ignores
  instance identity that only exists at registration time (external instances),
  and turns a soft preference into a hard pin that fails the run when the
  instance is gone.
- **Sticky routing keyed on the run's inputs or a hash of the agent name**:
  needs no memory, but it is not what the user asked for — two runs of one flow
  would land together only by coincidence, and an agent-run flow's steps have
  nothing in common to hash.
- **An in-memory, flow-keyed preference in the claim queue, with a bounded
  wait and immediate fallback**: the option chosen.
- **A persisted table of (flow root, group) → instance**: survives a restart,
  at the cost of a growing table, an index, a schema version and a sweep for
  entries whose instances are long gone — for a preference whose whole value is
  a cache that a restart has already emptied.

## Decision Outcome

Chosen option: **"An in-memory, flow-keyed preference in the claim queue, with a
bounded wait and immediate fallback."**

An agent opts in per group:

```yaml
runnerAffinity:
  group: gpu-boxes
  waitSec: 30
```

The group is validated like a runner tag and never names an instance; `waitSec`
is bounded to a day. The run's key is `(rootProcessId, group)` — the flow root
from its own Homer-stamped session params, so an agent cannot address another
flow's memory. On claim, the queue records the claiming consumer's
`affinityInstance` under that key; on submit, it resolves the key to the
instance the flow should prefer.

- **The preference is soft, and it expires.** While `waitSec` has not passed,
  a matching consumer that is not the preferred one passes the request over;
  the preferred one takes it ahead of older requests it also matches. When the
  preferred instance is unregistered, draining or no longer matching the run's
  filter, the request is claimable by anyone at once — the queue never waits
  for an instance that cannot serve it. `waitSec` is clamped to the claim TTL,
  so a preference can never outlive the claim.
- **An unpreferred run is not starved.** A consumer that keeps taking preferred
  requests ahead of older ones it also matches is capped at
  `RunnerClaimQueue.MAX_JUMPS` consecutive jumps, then must take the oldest
  match. An agent that declares no affinity is otherwise untouched: its request
  carries no preference and no consumer ever passes it over.
- **The identity is the instance, and only where one exists.** A static runner
  is its own single instance (its alias); an external instance is the name it
  registered with, qualified by its domain, and a nameless instance records
  nothing, so affinity never resolves to an identity the operator cannot see.
  Kubernetes is unaffected: its one consumer is the only candidate anyway, so
  the preference is a no-op there with no per-kind branch.
- **The memory is bounded and local.** `RunnerAffinityMemory` is an
  access-ordered LRU of at most 1024 entries with a six-hour TTL, held by the
  process. It is not a cache of anything durable: a restart loses it and the
  next run of each flow simply claims without a preference.
- **A name is held by one live instance.** Two live instances of one domain can
  no longer share a registered name — a second registration is refused with the
  same `422` as any other domain refusal, naming no token (ADR-0077). Without
  that, an external runner could reconnect under a name another live instance
  holds and silently take over a flow's preference.
- **Nothing new is served to a caller.** The preference lives in the queue's
  scheduling, not in the run record or the status API: no field is added, so no
  alias or instance name can leak through it (ADR-0077).

### Consequences

- Good: an agent that opts in keeps a flow's steps on one instance while that
  instance is healthy, and pays nothing when it is not.
- Good: agents that do not opt in are unaffected — no new wait, no new state on
  their path.
- Bad: the preference is per-process. A server restart, or a restart of the
  instance itself, silently loses it, and the next run of the flow claims
  freely. Accepted: the caches it protects do not survive those either.
- Bad: an instance that is up but wedged is preferred until `waitSec` expires;
  the run waits that long before falling back. The bound is the agent's own
  `waitSec`, and the run's claim TTL still ends the whole wait.
- Bad: a flow root is the run that started the chain, so two unrelated chains
  started from one root share a key — correct by construction (they are one
  flow), but it means a long-lived root's preference outlives its children
  until the TTL.

## More Information

Implemented in `RunnerAffinityMemory`, `ClaimAffinity` (on `ClaimRequest`),
`RunnerClaimQueue.claimMatch`/`preferenceBlocks`/`dispatch`, the
`affinityInstance` facet of `ClaimConsumerInfo`, and the `declaredName` of
`ExternalRunnerInstance`. The queue itself is ADR-0066; the handoff order the
preference precedes is ADR-0096; the flow root is ADR-0058; the instance-name
uniqueness refusal follows the domain-refusal model of ADR-0066 and the
alias-hiding rule of ADR-0077.
