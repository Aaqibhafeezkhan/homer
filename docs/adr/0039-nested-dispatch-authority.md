---
status: accepted
date: 2026-08-12
deciders: Lukáš Vlk
---
# ADR-0039: Nested-dispatch authority — gating `RunAgent`/dispatch/continuation hops

## Context and Problem Statement

[ADR-0037](0037-grants-authorization.md) replaced path allowlists with grants
on agents, actions, and `role: admin`, but explicitly deferred one piece
(decision 9): a run that dispatches into another agent — via `RunAgent`
(`!<agent>`), ask-and-dispatch, `dispatchNow`, or a completion/join
continuation — reached the target with **no authority check at all**.
[ADR-0038](0038-process-ownership.md) then shipped owner *stamping* for
these chains (so the resulting run is attributable and see-able by the
right principal) but likewise left the hop itself ungated: "`RunAgent` still
dispatches any agent unconditionally, unchanged from
[ADR-0030](0030-runagent-in-process-dispatch.md)." A caller holding `run` on
agent A only — with zero grant on agent B — could still reach B by having A
dispatch to it, silently bypassing the entire grants model at every nested
hop. This is Caller-Steered Nested Agent Authorization Bypass (security
review, risk 8.4) / Nested Agent Authorization Bypass (risk 7.4). This ADR
closes it.

## Decision Drivers

- Close the last authority gap ADR-0037/ADR-0038 knowingly left open: a
  grant on agent A must not become a transitive grant on every agent A can
  dispatch to.
- Preserve legitimate delegation: a router-style agent that fans out to a
  fleet of worker agents on the operator's behalf is a real, intended
  pattern — the fix must not force every caller to hold a direct grant on
  every possible sub-agent.
- Agent *definitions* must carry zero authority of their own. `edit` lets a
  caller rewrite an agent's commands and inputs; if an agent definition
  could declare "I may dispatch to X", editing the file would mint access
  to X — escalation via an editable artifact, not an operator decision.
- Cron-triggered chains have no caller principal (owner is `system:cron`,
  not an entry in the grants map) — the rule must still produce a sane,
  intentional answer for them, not silently deny or silently allow
  everything.
- Token revocation must actually revoke: a hop authorized by a caller's own
  grants must stop working the instant that grant is removed, not remain
  valid for whatever is already queued.
- Match the existing 404-hiding read-side pattern (ADR-0038) with an
  equivalent write-side denial that doesn't leak agent existence, and
  extend the read-side union rule to the two surfaces ADR-0038 left open
  (MCP management tools; `dispatches`/`continuations` single-resource
  routes).

## Considered Options

- **Thread the root principal's authority down the chain, unioned with a
  new server-config delegation map** — the option below.
- **Require every intermediate agent to hold its own principal-style grants
  (agent-as-principal only, no root fallback)** — rejected: breaks the
  common case of a caller with a direct grant on the sub-agent dispatching
  through an intermediary for organizational reasons (e.g. a `router`
  agent that just forwards); would force operators to duplicate every
  caller's grants into `agentGrants` for agents that need no delegation at
  all.
- **Let agents self-declare `dispatch.allowedTargets` in their own
  definition** — rejected per the decision drivers: `edit` grant becomes a
  privilege-escalation path, and agent definitions are meant to describe
  behavior, not carry authority.

## Decision Outcome

Chosen option: **a dispatch hop `A → B` is authorized iff the root
principal holds `RUN` on B (re-resolved at fire time) OR B is reachable
from A via `server.agentGrants`** — composite
`Authorizer.canDispatchHop(root, dispatchingAgent, target)`:

```
caller (grants G) ──run──► agent A ──RunAgent/dispatch/join──► agent B
allowed iff:  admin(root) ∨ run ∈ G(B) ∨ run ∈ agentGrants[A](B)

cron ──► agent A ──► agent B
allowed iff:  run ∈ agentGrants[A](B)          (no caller principal to fall back to)
```

### Two authority sources

1. **The root principal's own grants.** `root` is the `owner` riding down
   the chain (bearer token key, `hmac:{keyId}`, `session:{username}`, or
   `system:cron` — the same values ADR-0038 stamps and inherits). Authority
   is `hasAction(root, RUN, target)`, which re-resolves against the
   **live, in-memory `Authorizer`** built from the current config — not a
   snapshot taken at dispatch time. Revoking a token (removing it from
   config and reloading/restarting) immediately de-authorizes every
   dispatch/continuation still pending on it, beyond whatever
   `agentGrants` separately allow.
2. **`server.agentGrants[A]`** — an operator-configured delegation: which
   target agents (and actions) agent A's own dispatches may reach,
   regardless of who is running A. This is **server-config only, never
   self-declared in an agent's own definition** — an agent's `edit` grant
   lets a caller rewrite its commands and inputs, but never its delegated
   dispatch authority. Putting that authority in `server.agentGrants`
   keeps it under the operator's control, structurally outside what any
   `edit` grant can touch.

### Cron chains

A cron-triggered run's owner is `system:cron`, not an entry in the
`principals` map — `hasAction(system:cron, RUN, target)` is always `false`
(no grants are ever declared for it). A cron chain's hops therefore ride
**solely** on `agentGrants`: if the cron-launched agent isn't delegated to
its target, the hop is denied, full stop. There is no way to grant a cron
chain authority via the principals table — only via `agentGrants`.

### Fire-time is authoritative; registration is a fail-fast pre-check

Every fire path (`RunAgent`, dispatch `fire`, continuation `fire`/`fireJoin`)
re-checks `canDispatchHop` at the moment it actually starts the target run,
using the row's own stored `owner`/`originAgentName`/`targetAgentName` —
this is what makes fire-time revocation-aware (see above). Registration
(`DispatchService.create`, `ContinuationService.create`/`createJoin`,
`dispatchNow`) additionally pre-checks the same rule **before** inserting
the row, purely so an obviously-unauthorized hop surfaces immediately as a
~~`403`~~ `404` (superseded by the 2026-09-23 amendment) instead of silently sitting `PENDING` until an operator answers a
question or a watched process finishes, only to fail then. The
registration check is a convenience, not a substitute: it can't detect a
revocation that happens *between* registration and fire, which is exactly
why fire-time re-checks regardless of what registration already validated.

### Denial behavior per entry point

| Entry point | On denial |
|---|---|
| `RunAgent` (`!<agent>`) command | `RunAgentExecutor` returns exit `255` (same shape as "target agent not found" or any other dispatch-time `HomerException`) |
| Dispatch fire (ask-and-dispatch, on answer) | `DispatchService.fire` marks the dispatch `FAILED` with an authorization error message; no run starts |
| Continuation fire / join fire | `ContinuationService.fire`/`fireJoin` marks the continuation `FAILED`; no run starts |
| `dispatchNow`, and dispatch/continuation **registration** (`create`/`createJoin`) | ~~throws `NestedDispatchUnauthorizedException` → `403 Forbidden`~~ — superseded by the 2026-09-23 amendment: throws `DispatchAgentNotFoundException` → `404`, identical to an unknown agent. Reachable from `POST /api/v1/callback/runs`, `/joins`, and ask-and-dispatch registration. |

`RunAgent`'s `255` and the dispatch/continuation `FAILED` mirror how those
paths already report *other* dispatch-time failures (unknown target agent,
cycle/depth limits) — an unauthorized hop is just one more member of that
same failure family, not a new response shape. ~~On the HTTP registration
paths the throw maps to `403 Forbidden` — an authorization denial of the
specific hop, matching the sibling `AgentNotDispatchableException` (target
not opted into dispatch), which registration already returns as `403`. It
is distinct from a `401` on the caller's own callback credentials, which
remain valid; what is refused is the hop, not the caller.~~ — superseded by
the 2026-09-23 amendment: registration answers `404`, identical to an unknown agent.

### `dispatchNow` owner-stamp fix (Plan-2 gap)

While wiring this, a gap from Plan 2 (ADR-0038) surfaced: `dispatchNow`
started its sub-run with `owner = null` instead of inheriting the origin
process's owner — the one dispatch path ADR-0038's chain-inheritance
sweep missed. Fixed alongside the authority gate: `dispatchNow` now reads
the origin process's owner and passes it through, same as every other
entry point.

### Read-gap closures

ADR-0038 shipped the union visibility rule (`canSee`/`canControl`) but
explicitly deferred two surfaces as a "known gap": the MCP management
tools, and the `dispatches`/`continuations` single-resource routes. Both
close here, using the same existence-hiding pattern as everything else on
the read side:

- **MCP management tools** — `get_run_info`, `list_artifacts`, and
  `get_artifact` now call `canSee(principal, run.owner, run.agentName)`
  before returning any data; `cancel_run` calls `canControl`. A denied call
  returns the same "Run not found" tool error a genuinely unknown `runId`
  would — no separate "forbidden" response that would confirm the run
  exists.
- **`GET/DELETE /api/v1/dispatches/{id}`, `GET/DELETE
  /api/v1/continuations/{id}`** — GET now requires `canSee` (owner/agent
  from the row); DELETE requires `canControl`. Denial is the same
  `DispatchNotFoundException`/`ContinuationNotFoundException` → `404` an
  absent id already produces.

### Unsecured mode

`server.apiAuth: !<unsecured>` disables the `Authorizer` entirely
(`enabled = false`); `hasAction` short-circuits `true` for every principal,
so `canDispatchHop` is unconditionally `true` — status quo, unchanged from
before this ADR.

### Caveat: `edit` on an agent that holds `agentGrants`

`server.agentGrants` keys by agent *name*, not by a specific definition
file. Granting `edit` on an agent name that appears as a key in
`agentGrants` hands that agent's delegated dispatch authority to whoever
holds `edit` — not because the definition can declare authority (it
can't, per the decision above), but because an editor can rewrite *which
agents the delegated agent's commands dispatch to* within whatever
`agentGrants[thatAgent]` already permits. Operators who delegate dispatch
authority to an agent via `agentGrants` should treat `edit` on that agent
name as equivalent to holding the delegation itself, and scope `edit`
grants accordingly.

### Consequences

- Good: closes the last open item from ADR-0037 (decision 9) and
  ADR-0038's "Deferred to Plan 3" section — a grant on agent A is no
  longer a transitive grant on everything A can dispatch to.
- Good: revocation is real — deleting a token immediately de-authorizes
  its pending dispatches/continuations beyond `agentGrants`, because
  fire-time re-resolves against the live `Authorizer`, not a snapshot.
- Good: legitimate delegation (a router/orchestrator agent fanning out to
  a fleet) keeps working via `server.agentGrants` without requiring every
  caller to hold a direct grant on every possible sub-agent.
- Good: `agentGrants` is server-config only — no agent-definition field
  was added, so `edit` never becomes a self-service escalation path
  (subject to the caveat above, which is about `edit` on the *delegated*
  agent, not about self-declared authority).
- Good: closes ADR-0038's two deferred read-gaps (MCP management tools,
  `dispatches`/`continuations` `{id}`) with the same existence-hiding
  pattern already used everywhere else.
- Neutral: denial shape differs by entry point (255 / FAILED / ~~403~~ 404,
  per the 2026-09-23 amendment)
  rather than one uniform status — deliberately so, matching each path's
  pre-existing failure-reporting shape rather than inventing a new one.
- Bad: registration-time pre-checks add a small amount of duplicate
  authority evaluation (checked once at registration, again at fire) —
  accepted because fire-time must be authoritative regardless (revocation
  can happen in between), and the registration check's only job is faster
  feedback on an obviously-bad request.

## Amendment (2026-09-23): a denied hop reads as an unknown agent

The registration `403` above told the calling run which agents exist: an
unknown target answered `404`, a known but unauthorized one `403` with a
distinct message, and a known but non-dispatchable one `403` with a third.
Agent code chooses the target (the name can come from env), so any run could
enumerate the server's agents — contradicting the existence-hiding rule this
ADR applies everywhere else, and
[ADR-0047](0047-security-by-design-and-threat-model.md) rule 3.

Registration now checks the hop **before** the target's existence or
dispatchability, and an unauthorized hop throws `DispatchAgentNotFoundException`
(`404`, same message as an unknown agent). `NestedDispatchUnauthorizedException`
is removed. A hop that *is* authorized still gets `403` for a non-dispatchable
target: its principal may already reach that agent, so nothing new leaks.
`RunAgent`'s denial stderr likewise now reads `agent 'B' not found` instead of
`not authorized to run 'B'` — the exit code was already the same `255`.

The HTTP routes follow the same rule: `POST /api/v1/agent/{name}` and
`/agents/{name}/debug` answer `404` to a caller holding no grant on the agent,
and keep `403` for a caller who holds some grant (and so sees the agent in
`GET /api/v1/agents`) but not the action.

## More Information

- Authorizer: [`Authorizer.kt`](../../app/app/src/main/kotlin/homer/app/services/Authorizer.kt)
  — `canDispatchHop`, `agentCanDispatch`.
- Exception: [`Exceptions.kt`](../../app/app/src/main/kotlin/homer/app/exceptions/Exceptions.kt)
  — `DispatchAgentNotFoundException` (since the 2026-09-23 amendment).
- Enforcement sites:
  [`RunAgentExecutor.kt`](../../app/app/src/main/kotlin/homer/app/executors/RunAgentExecutor.kt),
  [`DispatchService.kt`](../../app/app/src/main/kotlin/homer/app/services/DispatchService.kt),
  [`ContinuationService.kt`](../../app/app/src/main/kotlin/homer/app/services/ContinuationService.kt).
- Read-gap closures:
  [`McpToolFactory.kt`](../../app/app/src/main/kotlin/homer/app/services/McpToolFactory.kt),
  [`SecuredRoutes.kt`](../../app/app/src/main/kotlin/homer/app/routes/SecuredRoutes.kt).
- Config reference: [`app/SERVER.md`](../../app/SERVER.md) §
  *Authentication Configuration* (`server.agentGrants`).
- Supersedes the "any agent, no gate" clause of
  [ADR-0030](0030-runagent-in-process-dispatch.md); closes the deferral in
  [ADR-0037](0037-grants-authorization.md) decision 9 and the "Deferred to
  Plan 3" / "Known gap" sections of [ADR-0038](0038-process-ownership.md).
  Closes the security-review findings Caller-Steered Nested Agent
  Authorization Bypass (risk 8.4) and Nested Agent Authorization Bypass (risk 7.4).
