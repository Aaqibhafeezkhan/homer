---
status: accepted
date: 2026-06-15
deciders: Lukáš Vlk
---
# ADR-0022: script-driven immediate dispatch — `homer-dispatch`

## Context and Problem Statement

Homer already lets one agent trigger another two ways: the static
`RunAgent` command (declared in `agent.yaml`, threads the invocation chain
via the loopback token for cycle/depth protection) and `ask-and-dispatch`
(ADR-0021: a running command registers a target agent that fires *after an
operator answers a question*, fire-and-forget, fresh chain).

Neither covers the case where a **Script/Cmd block needs to start another
agent immediately, without a human question**, with the target name and
params computed at run time. A raw `curl` from a script cannot do it
cleanly: the secured `POST /api/v1/agent/{name}` needs an operator token
(scripts only hold the per-process `HMR_CALLBACK_TOKEN`, valid on
`/api/v1/callback/*`), and a script can never present `RunAgent`'s loopback
token (it is never exposed to scripts). We want "like `RunAgent`, but
script-driven and fire-and-forget like `ask-and-dispatch`, minus the
question."

## Decision Drivers

- A command must be able to compute the target agent + inputs at run time
  and start the run from the shell, authenticated only by the callback
  token it already holds.
- The caller must not block on the dispatched run (fire-and-forget).
- Reuse the existing security gate — no new config keys, no new agent
  fields — so the dispatchable surface stays in one place.
- Stay cooperative and decoupled: the dispatched run is a first-class,
  independent run; the caller's credentials must not leak into it.

## Considered Options

- **New `POST /api/v1/callback/runs` verb + `homer-dispatch` helper**,
  reusing the `dispatch.enabled` gate and starting a fully decoupled run.
- **Reuse the loopback / `RunAgent` chain** — would give cycle/depth
  protection, but the loopback token is deliberately never exposed to
  scripts, and threading the chain from an arbitrary script is exactly the
  coupling we want to avoid.
- **A `homer-ask` subcommand** rather than a standalone helper — folds
  immediate dispatch into the question helper, conflating "ask the
  operator" with "start an agent now."

## Decision Outcome

Chosen: **a new `POST /api/v1/callback/runs` callback verb plus a bundled
standalone `homer-dispatch` helper**, starting a fully decoupled run gated
by the existing `agent.dispatch.enabled` opt-in.

**Fully decoupled, no Dispatch row.** `DispatchService.dispatchNow` mirrors
`fire` but starts the run with a *fresh empty invocation chain* (no
cycle/depth check) and writes **no `dispatches` table row**. Unlike
ask-and-dispatch there is no PENDING/question state to track, no operator
answer to wait for, and nothing to re-fire on boot — the run either starts
synchronously within the callback request or the call returns an error.
Adding a Dispatch row would imply a lifecycle (claim, fire, cancel, TTL
sweep, crash re-fire) that immediate dispatch simply does not have. *Why a
fresh chain rather than threading the caller's:* the loopback token that
carries the chain is never available to a script, and immediate dispatch is
intentionally cooperative and decoupled — the same stance ADR-0021 takes.
*(Amended 2026-10-09: the loopback token was removed by
[ADR-0030](0030-runagent-in-process-dispatch.md); the fresh chain stands on
the decoupling argument alone.)*

**Referer, not a chain.** The only linkage back to the caller is the
*referer* — the origin process id and asking agent name — recorded on the
dispatched run's `agent_request` telemetry span as `referer.process.id` /
`referer.agent.name`. `AgentRunStarter.start` gains an optional
`extraAttributes` parameter (default empty, so every other caller is
unchanged) that `dispatchNow` populates. This mirrors how ask-and-dispatch
records `origin_process_id` / `origin_agent_name`, without threading the
invocation chain.

**Liveness + opt-in gate.** `dispatchNow` requires the origin process to be
live (`questionsService.registrationFor` non-null and not closed, else
`409`) so a dead process cannot keep spawning runs, and the target must opt
in via `agent.dispatch.enabled: true` — the *same* gate `create` enforces.
An unknown target is `404`, a non-dispatchable one `403`. No new config key
or agent field is introduced; `dispatch.enabled` now gates **any** dispatch
target, ask-and-dispatch or `homer-dispatch`.

**Independent run.** Like ask-and-dispatch, the dispatched run uses the
server-configured `server.dispatch.authToken` for its own callbacks and
never inherits the caller's `HMR_CALLBACK_TOKEN`. *(Amended 2026-10-09:
`server.dispatch.authToken` was removed (ADR-0030, which also refines this
ADR); the dispatched run gets its own per-process callback token.)*

**Helper.** `homer-dispatch` is a standalone POSIX `sh` helper (not a
`homer-ask` subcommand), materialized next to `homer-ask` in
`{projectRoot}/.homer/bin`. Usage: `homer-dispatch --agent NAME
[--param k=v]...`; `--param` parsing matches ask-and-dispatch's exactly. It
POSTs `{ agent, params }` to `/api/v1/callback/runs` with the Bearer
callback token and prints the new process id. It keeps its own minimal
`json_escape` + numeric-field extractor rather than sourcing a shared lib,
avoiding materialization-path coupling for a tiny amount of duplicated
plumbing.

### Consequences

- Good: a command can start another agent immediately from the shell, with
  only the callback token it already holds.
- Good: no new config/agent surface — `dispatch.enabled` is the single
  opt-in for both dispatch paths.
- Good: the dispatched run is fully independent; the caller's credentials
  never enter it; cost/parallel-run caps apply normally.
- Bad: no cycle/depth protection — a misconfigured set of agents can
  dispatch each other without the `RunAgent` chain catching a loop. The
  `dispatch.enabled` opt-in and the live-origin check are the only guards.
- Bad: a second small chunk of JSON plumbing is duplicated between
  `homer-ask` and `homer-dispatch`.

## More Information

- [ADR-0021](0021-ask-and-dispatch.md) — deferred dispatch on answer; this
  is its no-question sibling and reuses the `dispatch.enabled` gate.
- [ADR-0019](0019-human-in-the-loop-questions.md) — the callback-token
  trust boundary `homer-dispatch` authenticates against.
- [`app/AGENT.md`](../../app/AGENT.md) "Dispatching another agent" — usage.
- [`app/README.md`](../../app/README.md) — `POST /api/v1/callback/runs`.
