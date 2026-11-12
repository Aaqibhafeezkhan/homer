---
status: proposed
date: 2026-11-10
deciders: Lukáš Vlk
consulted: ADR-0023, ADR-0039, ADR-0052, ADR-0060
---

# ADR-0101: A continuation can inherit the registering run's inputs

## Context and Problem Statement

A continuation, a dispatch and a LangGraph resume all start a **new run**. The
fire supplies the continuation's own params, the resume payload and the thread
id — and nothing else. Every input the registering run resolved from its caller
is gone, because it was never stored on the `continuations` or `dispatches` row.

For a LangGraph agent that resumes *itself*, that silently breaks two things
derived from a subject input. Its lock key (`ticket-{{TICKET_ID}}`) renders with
the variable unset, leaving the literal `ticket-{{TICKET_ID}}` as the key: every
resumed segment of every subject shares one key, and none of them matches the
fresh run for the subject they belong to. And its `tags.fromInputs` tags
disappear from the resumed segment, so `?tag=ticket:123` stops finding the run
that is still working on ticket 123. The same loss hits a `required: true`
subject input: registering the continuation is rejected with `400` because the
registration call cannot cover it, even though the registering run already has
the value in hand.

## Decision Drivers

- A resumed segment must be able to keep a real lock key and its subject tags
  without every agent author re-plumbing inputs through `--continue-param`.
- The value is already in the server's own database — re-supplying it from the
  client is a workaround, not a fix.
- Crossing to another agent must not become a way to read that agent's inputs
  (root CLAUDE.md §12: principals are a boundary).
- A secret input's value must not become reachable where it was not before
  (ADR-0052), and no row that holds no secrets today may gain one.
- Turning the behaviour on must be the agent author's choice, and turning it off
  must stop pending fires.

## Considered Options

- **Server-side inheritance behind an opt-in agent flag** — `continuation: { inheritInputs: true }`;
  the fire merges the registering run's stored inputs under its own params.
- **Always inherit** for a fire back to the same agent, with no flag.
- **Client-side forwarding** — the LangGraph runtime passes its declared inputs
  back as `--continue-param` on every dispatch.
- **A new `POST /api/v1/processes/{id}/resume`-style endpoint** the runtime calls
  itself.
- **A `dispatch.inheritInputs` sibling flag** — one flag per fire path, so a
  dispatch could opt in independently of continuations.
- **A top-level `inheritOriginInputs` flag** — detached from any fire-path group.

## Decision Outcome

Chosen option: **"Server-side inheritance behind an opt-in agent flag"**.

The flag lives under `continuation` — `continuation: { inheritInputs: true }` — even
though it changes ask-and-dispatch fires too. The decision is deliberate: the
mechanism is one merge of a registering run's inputs into a fire back to the same
agent, and the LangGraph segment is the case that motivates it, so it sits beside
the only continuation configuration the agent format already groups. A
`dispatch.inheritInputs` sibling was rejected because it lets the two halves of one
behaviour disagree — an agent inheriting on continuations but not dispatches, or the
reverse, is a state with no use case and one more combination to test; a top-level
`inheritOriginInputs` was rejected because it hides the same behaviour behind a name
that says nothing about which fires it reaches. An ask-and-dispatch author who reads
the field table and misses that the flag covers dispatches loses nothing: the
`continuation` row states it in one sentence.

One internal helper (`inheritedOriginInputs`) computes what a fire inherits, and
both fire paths (`ContinuationFireService`, `DispatchService`) and both
registration paths (`ContinuationService.validate`, `DispatchService.create`)
use it. It reads the origin run's row through the suspend supplier the caller
passes — a read the fire paths defer until the flag and same-agent gates pass,
so a flag-off fire reads no row, while the registration paths pass an
already-fetched row — and returns the origin's inputs **keyed by paramName** —
the keying the fire's own params use — plus the origin's `secretInputKeys`
snapshot.

The fire merges that map **under** its own params:
`inherited + continuation.params + (resumeParam to payload)`. An explicit
`--continue-param`, a dispatch's `--param` and the operator's answer therefore
always win over a stored value. Registration treats the inherited names as
provided, so a `required: true` subject input no longer blocks a continuation
whose registering run already satisfied it.

Every gate fails closed to "inherit nothing", and each is one condition in the
helper:

- The **target's live definition** must opt in, read at fire time exactly as
  `dispatch.enabled` already is, so clearing the flag stops pending fires.
- The fire must target **the agent that registered it** (`originAgentName ==
  target.name`). A continuation to a different agent inherits nothing, so a hop
  can never read another agent's inputs through this path.
- The origin row must exist and its **owner** must equal the owner the
  continuation or dispatch row carries. A purged row or an owner mismatch
  inherits nothing and logs; it does not fail the fire.
- The inherited map is built by mapping stored **envNames** back through
  `agent.effectiveInputs`, dropping every name the agent no longer declares and
  every `ReservedEnvNames.isReserved` key. A stale `HMR_LG_THREAD` or
  `HMR_LG_RESUME` from a previous segment can never ride in, and the set is
  bounded by the agent's declared inputs, so it cannot grow from segment to
  segment.

Values are re-validated at fire time by the same `AgentInputResolver` /
`ParamValidator` / `inputSchema` path a caller's value takes — never trusted as
"already validated". Because a stored input was resolved once already, the
transformer is **not** applied a second time: re-running e.g. a `jsonPath`
transformer over the already-extracted value would reject every inherited value of
such an input. They are merged **in memory only**: no `continuations` or
`dispatches` column is added or written, so no row that holds no secrets today
gains one. Redaction rides the existing `extraSecretInputKeys` union of the
target's current flags and the origin's snapshot, which is also what keeps a
secret value out of `langfuseTraceTags` and `?tag=`.

This is the second mechanism that replays a run's stored inputs against the
current definition — the other is `AgentInputResolver.resolveRetryParams`, which
retry uses. The divergence is deliberate. Retry validates **eagerly** and throws
on a required input the original run does not show, because a retry reruns the
same agent with nothing else to supply; a fire's own params, resume payload or
operator answer can supply a required input the registering run lacked, so
inheritance soft-drops what is absent and defers validation to the fire's merged
resolution, where the whole param map is checked together. Retry also keys by
`envName` end to end, while inheritance bridges to the continuation's
paramName-keyed params.

### Consequences

- Good: a self-resuming agent keeps a subject-specific lock key and its
  `tags.fromInputs` tags across segments, with no client change.
- Good: a `required: true` subject input no longer blocks registering a
  continuation that the registering run already satisfies.
- Good: the blast radius is one helper plus four call sites, and every gate fails
  closed, so an ineligible continuation fires exactly as it did before.
- Bad: an agent that enables the flag sees a behaviour change in the opposite
  direction too — a command gated on an input being *unset* now finds the
  registering run's value set. The `HMR_LG_THREAD` marker stays reserved and
  uninherited, so it remains the correct "is this a fresh run?" gate.
- Bad: flipping the default to `true` later is a breaking change for any agent
  whose commands assume a resumed run carries only the fire's params. This ADR
  is the record that the default is deliberately `false` now.
- Neutral: the client-side variant is not implemented. It would have to be
  re-implemented by every runtime and would cover only the agents that use it.

## Pros and Cons of the Options

### Always inherit, no flag

- Good: nothing to configure; the loss described above never happens.
- Bad: changes the behaviour of every existing continuation to the same agent,
  including ones whose commands gate on an input being unset — a silent break in
  a released feature.
- Bad: no way for an author to keep the old semantics.

### Client-side forwarding (`--continue-param` per input)

- Good: no server change at all.
- Bad: covers only the runtime that implements it (LangGraph), leaving plain
  script agents and every other client out.
- Bad: the values are already in the server's database; forwarding them back over
  the API makes the client the authority on what the server stored, and puts a
  secret input's value back on the wire.
- Bad: every agent author has to remember to do it, and a missed input fails
  silently.

### A resume-style endpoint the runtime calls itself

- Good: explicit and auditable per call.
- Bad: a second start path to keep gated, bounded and authorized alongside the
  existing ones; the continuation machinery already exists to wake a run.
- Bad: the runtime would have to be trusted to pass the right origin id, which is
  exactly the input the server can derive itself.

## More Information

- ADR-0023 — completion continuations, the fire path this extends.
- ADR-0052 — the secret redaction union reused here, unchanged.
- ADR-0060 — `tags.fromInputs`, the tag derivation this restores for a segment.
- ADR-0039 — the dispatch hop authority the same-agent gate complements.
- ADR-0098 — its 2026-11-12 amendment makes an unresolved lock key placeholder
  fail the run before it starts, so the literal `ticket-{{TICKET_ID}}` key
  described above is no longer what a resumed segment without this flag gets:
  that segment is now refused, and this flag is what keeps it runnable.
