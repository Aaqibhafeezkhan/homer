---
status: accepted
date: 2026-08-11
deciders: Lukáš Vlk
---
# ADR-0037: Grants-based authorization replaces path allowlists

## Context and Problem Statement

Homer authorized by URL-path allowlists: each bearer token, HMAC key, and
password user carried `endpoints: [pattern, ...]`, matched against the raw
request path. Homer's real resources are **agents** (invoke, read runs, edit
files, debug), **processes** (read, control), and **server operations** —
none of which a path list can express. Every sev≥7 authorization finding
from the 2026-08 security review was a symptom of that mismatch: `["*"]`
implicitly granted the arbitrary-command debug endpoint alongside everything
else; a path pattern can't see the HTTP method; there is no per-agent or
per-process dimension; the path matcher parsed raw request paths
(canonicalization desync between the matcher and the router); and nested
`RunAgent` dispatch was ungated by design (ADR-0030). Patching each finding
separately would bolt N mechanisms onto the wrong primitive.

## Decision Drivers

- Authorize the resources that actually exist (agents, processes, server
  ops), not URL shapes that happen to expose them.
- Close the debug-execution overbroad-grant finding: `debug` must never be
  implied by a wildcard grant, only by `role: admin`.
- Preserve the load-bearing guarantee that a caller can always see and kill
  a run it started — the async API's only failure signal is polling the
  returned `processId`.
- Retire the request-path parser from the authorization path entirely, so
  canonicalization drift between the matcher and the router can't reopen a
  bypass.
- Pre-open-source is the last cheap moment to break the config contract;
  ambiguous legacy patterns (e.g. `/api/v1/status/*`) have no faithful
  auto-mapping to the new model, so a clean break beats a lossy shim.

## Considered Options

- **Grants replace `endpoints`, hard break** — every principal declares
  `role: admin` and/or `grants: [{agents, actions}]`; a config still using
  `endpoints` fails startup with a migration hint.
- **Layer grants on top of `endpoints`** — keep both allowlist and grants,
  intersect them. Rejected: doubles the config surface permanently for a
  problem `endpoints` can't express correctly anyway, and doesn't close the
  debug-execution finding (`["*"]` still reaches it).
- **Auto-migrate `endpoints` to grants at load time** — rejected: legacy
  patterns like `/api/v1/status/*` or `/api/v1/agents/*` have no single
  faithful action/agent mapping (they span process- and agent-scoped
  routes), so an automatic translation would either over- or under-grant
  silently.

## Decision Outcome

Chosen option: **grants replace `endpoints`, hard break.** Ten decisions,
settled with the operator on 2026-08-11:

1. **Grants replace path allowlists.** Every principal (bearer token, HMAC
   key, password user) carries `grants: [{agents: [name patterns],
   actions: [...]}]` and/or `role: admin`. `endpoints` is **removed**: a
   config still using it fails startup with an error naming the equivalent
   grants (`["*"]` → `role: admin`; `/api/v1/agent/X` → `agents: [X],
   actions: [run]`).
2. **Five actions**: `run`, `read`, `control`, `edit`, `debug`. `debug` is
   never implied by anything except `role: admin` — closes the
   overbroad-allowlist-grants-debug-execution finding.
3. **`run` includes own-run visibility.** The owner branch is
   unconditional: whoever caused a run can always see and kill it.
4. **Visibility is a union**: `see(process) = admin ∪ owner(process) ∪ read
   grant on the process's agent`. `read` covers all runs of the agent
   regardless of who caused them (cron, other principals, delegation).
   **Note:** this plan lands the config model, the actions, and
   enforcement of `run`/`edit`/`debug`/`admin` at the agent- and
   server-operation routes. Owner stamping/inheritance and the full
   process-visibility union **shipped in Plan 2** — see
   [ADR-0038](0038-process-ownership.md).
5. **Admin is a role, not a path**: `role: admin` grants all actions on all
   agents plus server-wide operations (reload, costs, schedules, unowned
   rows).
6. **Enforcement at the route level** via one `Authorizer` service. Request
   paths are never parsed for authorization — `EndpointMatcher` leaves the
   auth path entirely, which retires the path-canonicalization finding
   structurally rather than patching the matcher again.
7. **Callback-token domain is unchanged** ([ADR-0019](0019-human-in-the-loop-questions.md)):
   `HMR_CALLBACK_TOKEN` routes are a process acting on itself, structurally
   outside operator auth. The LangGraph wrappers (`homer-ask`/
   `homer-dispatch`/`homer-join`/`homer-result`) are untouched.
8. **Unsecured mode is unchanged**: no principals exist, the `Authorizer`
   is disabled, behavior is status quo (explicit `!<unsecured>` opt-in per
   [ADR-0036](0036-explicit-api-auth-opt-in.md)).
9. **Nested dispatch authority** (threaded root-principal grants ∪ the
   dispatching agent's `server.agentGrants`) is designed but **not part
   of this plan** — it is still pending a follow-up plan (Plan 3).
   **Owner stamping / schema v8** shipped in Plan 2, see
   [ADR-0038](0038-process-ownership.md). `RunAgent` continues to
   dispatch any agent unconditionally in the meantime (see
   Consequences) — that gap is about *authority*, not visibility; the
   resulting run is still correctly owner-attributed. *(Amended 2026-10-09:
   implemented by [ADR-0039](0039-nested-dispatch-authority.md); every
   `RunAgent`/dispatch/continuation hop is now gated.)*
10. This ADR **supersedes the "no-gate" clause of
    [ADR-0030](0030-runagent-in-process-dispatch.md)**: ADR-0030 stated
    "`RunAgent` keeps invoking any agent — there is no `dispatch.enabled`
    gate." That clause is superseded in intent (nested dispatch is meant
    to be gated by threaded authority) but not yet in implementation — the
    actual gating (decision 9) is Plan 2 work. ADR-0030's `RunAgent`-is-
    in-process architecture and the `dispatch.enabled` gate's scope
    (specific to `DispatchService`) are otherwise unaffected. *(Amended
    2026-10-09: the gating is implemented, see ADR-0039.)*

### Consequences

- Good: authorization now speaks in the resources Homer actually has
  (agents, actions, admin), so a grant's meaning doesn't depend on
  reverse-engineering which routes a path pattern happens to match.
- Good: `debug` requires an explicit action or `role: admin` — a `["*"]`-
  equivalent `agents` grant without `debug` in its `actions` cannot reach
  `POST /agents/{name}/debug`.
- Good: the path-canonicalization finding is closed structurally — there
  is no path matcher left in the authorization path to desync.
- Bad: **breaking config change.** A config still using `endpoints` fails
  startup; every deployment must migrate before upgrading. The startup
  error names the mechanical translation.
- Bad: nested-dispatch gating (denying a hop when the caller/
  `agentGrants` don't permit the target) is designed but not yet
  enforced — `RunAgent` still dispatches any agent unconditionally,
  pending Plan 3. This is a deliberate scope cut, not a regression: that
  surface was equally ungated before this ADR. *(Amended 2026-10-09:
  closed by ADR-0039.)*
  Per-process owner visibility, by contrast, **shipped in Plan 2** — see
  [ADR-0038](0038-process-ownership.md): process-scoped routes (status,
  kill, retry, artifacts, questions, dispatches, continuations, the
  `/stream/processes` SSE) are now filtered by owner ∪ `read` grant ∪
  admin.
- Neutral: `role`/`grants` on bearer tokens, HMAC keys, and password users
  follow one shared validation path (`Principaled`), so all three principal
  kinds gained the same authorization surface in one change.

## More Information

- Authorizer service: [`Authorizer.kt`](../../app/app/src/main/kotlin/homer/app/services/Authorizer.kt).
- Grant/Action/Principaled models and the startup migration check:
  [`AppConfig.kt`](../../app/app/src/main/kotlin/homer/app/models/AppConfig.kt),
  `validateNoLegacyEndpoints` in
  [`Application.kt`](../../app/app/src/main/kotlin/homer/app/Application.kt).
- Config reference: [`app/SERVER.md`](../../app/SERVER.md) § *Authentication
  Configuration*.
- Owner stamping, the process-visibility union rule, and 404-hiding
  shipped in Plan 2: [ADR-0038](0038-process-ownership.md).
- Supersedes the no-gate clause of [ADR-0030](0030-runagent-in-process-dispatch.md);
  callback-token domain unchanged from [ADR-0019](0019-human-in-the-loop-questions.md);
  unsecured opt-in unchanged from [ADR-0036](0036-explicit-api-auth-opt-in.md).
