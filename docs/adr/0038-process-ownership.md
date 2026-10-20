---
status: accepted
date: 2026-08-11
deciders: Lukáš Vlk
---
# ADR-0038: Per-process ownership and the union visibility rule

## Context and Problem Statement

[ADR-0037](0037-grants-authorization.md) replaced path allowlists with
grants on agents, actions, and `role: admin`, but deliberately deferred one
piece: process-scoped routes (status, kill, retry, artifacts, questions,
dispatches, continuations, streams) and MCP tool calls stayed reachable by
any authenticated principal regardless of grants, because a grant alone
cannot answer "did *this caller* cause *this* run?" — that requires
recording who started each process. Without that record, `run`'s promise
of "unconditional visibility/control of runs the caller itself started"
(ADR-0037 decision 3) had no data to act on, and `read`'s "see all runs of
the agent" had no per-process attachment point either. This ADR adds that
record — a per-row `owner` — and the enforcement built on top of it.

## Decision Drivers

- Deliver the two visibility promises ADR-0037 already made in its action
  table (`run` → own-run visibility, `read` → all runs of the agent)
  instead of leaving them as unenforced documentation.
- Close the Plan-1 CHANGELOG caveat: process-scoped routes and MCP were
  explicitly called out as "not yet grant-gated ... until a follow-up
  release."
- Preserve the existing 404-hiding pattern already used elsewhere in the
  API (e.g. the `langgraph` inspect endpoint denies with 404, not 403) so
  a denied caller learns nothing about whether a resource exists.
- Don't block on nested-dispatch authority (ADR-0037 decision 9): that is
  a materially separate problem (gating a hop before it happens vs.
  recording who's accountable for a row that already exists) and can land
  in its own plan without holding this one hostage.
- Minimize migration risk: every existing Homer deployment has processes,
  dispatches, and continuations rows with no owner. The rule must degrade
  safely for `owner = null`, not require a backfill.

## Considered Options

- **Add an `owner` column, stamped at row creation, plus a union
  visibility rule enforced at every process-scoped route and list** —
  the option below.
- **Derive ownership at read time from request context (e.g. re-resolve
  "who started process N" from history/audit logs)** — rejected: Homer
  has no separate audit log; reconstructing ownership from the process's
  own mutable state is fragile and the row itself is the natural place to
  record it once, at creation.
- **Scope visibility to `read`-grant only, drop the owner concept
  entirely** — rejected: this breaks the load-bearing guarantee (ADR-0037
  decision 3, ADR-0037's own decision drivers) that a caller can always
  see and kill the run it started, even with zero `read` grants — the
  async API's only failure signal is polling the returned `processId`.

## Decision Outcome

Chosen option: **add a nullable `owner` column to `processes`,
`dispatches`, and `continuations` (schema v8), stamp it at row creation,
and enforce a union visibility rule at every process-scoped route,
list, and stream.**

### Owner data model

A guarded `ALTER TABLE ... ADD COLUMN owner TEXT` runs for any database
frozen at schema v1 through v7 (`processes` has existed since v1, so the
lower bound can't start at v3 like sibling single-column migrations do).
The column is nullable with no default — pre-migration rows and any run
started while `owner` isn't yet threaded through a given call path simply
read back `owner = null`.

### Owner principal formats

Owner is the exact `UserIdPrincipal.name` stamped by whichever auth scheme
authenticated the caller — the same principal-name formats ADR-0037's
`Authorizer` already keys its `principals` map by:

| Principal source | Owner value |
|---|---|
| Bearer token | The token's config-map key, verbatim (e.g. `"echoScoped"`) |
| HMAC key | `hmac:{keyId}` |
| Password / session user | `session:{username}` |
| Cron-triggered run | `system:cron` |
| Unsecured mode, or no principal resolved | `null` |

(`AuthConfig.Bearer` rejects any token key starting with the reserved
`session:`/`hmac:` prefixes, so the three principal namespaces never
collide.)

### The union visibility rule

`Authorizer` gains three process-scoped checks, each taking
`(principal, owner, agentName)`:

- **`canSee`** — `admin ∨ (owner ≠ null ∧ owner = principal) ∨ read-grant
  on agentName`. This is the union: admin, or the row's owner, or anyone
  holding a `read` grant on the agent — regardless of who actually caused
  the run. `owner ≠ null` is checked explicitly so two `null`s (e.g. an
  unsecured-mode caller against a pre-migration row) never satisfy the
  owner clause by accident.
- **`canControl`** — `admin ∨ owner-match ∨ (canSee ∧ control-grant)`. Same
  union, but a grant-based caller additionally needs `control`, not just
  `read` — visibility alone doesn't imply the right to kill or answer.
  Owner and admin still bypass the grant requirement entirely.
- **`canRetry`** — `canSee ∧ run-grant`. Deliberately **not** part of the
  owner-bypass pattern: owner alone does not grant retry. A principal who
  can see a process but holds no `run` grant on its agent gets 404 on
  retry, same as one who can't see it at all.

Unsecured mode (`enabled = false`) makes every check pass unconditionally
(`isAdmin` short-circuits true), so behavior there is unchanged from
before this ADR.

### 404-hiding and filtering

Single-resource routes hide denial behind the same 404 the absent-resource
case returns — no 403 anywhere in this set: `GET /status/{id}`,
`GET /artifacts`, `GET /artifacts/list`, `DELETE /kill/{id}`,
`POST /processes/{id}/retry`, `POST /questions/{id}/answer`. The retry
route in particular runs its `canSee` existence-hiding gate *before* the
terminal-status/agent-lookup checks that would otherwise leak existence
through a 409 or a different 404, then re-checks with `canRetry` after —
both denials look identical from the outside.

List routes filter to visible rows instead of denying the whole request:

- `GET /status/all` filters in SQL — `WHERE ... AND (owner = :principal OR
  agent_name IN (:readableAgents))` — with admin (or unsecured mode)
  skipping the clause entirely (`readableAgents` returns `null` for admin,
  meaning "no filter").
- `GET /questions`, `GET /dispatches`, `GET /continuations` have no owner
  column of their own (or, for questions, none at all); they filter in
  Kotlin after fetch, calling `canSee` per row using the owner/agentName
  already available (a dispatch's/continuation's own copied owner — see
  below — or, for questions, the asking process's owner/agentName).
  *(Amended 2026-10-09: `GET /dispatches` and `GET /continuations` now filter
  in SQL before the limit, see [ADR-0021's 2026-09-30 amendment](0021-ask-and-dispatch.md#amendment-2026-09-30-settled-records-are-pruned-after-a-retention-window); `GET /questions` still filters in Kotlin.)*

`GET /stream/processes` filters each SSE event through `canSee`, memoized
per connection (`processId → (owner, agentName)`) so repeat events for the
same process don't re-hit `ProcessManager` on every tick; the memo is
scoped to one connection, never shared across principals. The two
single-process streams, `GET /stream/processes/{id}` and
`GET /stream/processes/{id}/logs`, enforce the same `canSee` check as a
pre-stream 404 — resolved once against `ProcessManager` before
`respondBytesWriter` ever opens the connection, so a denied caller never
receives a byte of the tail/subscription. `GET /stream/agents` carries no
process-specific data and is unfiltered.

### Registration-time owner copy

A `Dispatch` or `Continuation` copies its origin process's `owner` once,
**at registration** (`create()`/`createJoin()`), not re-read at fire time.
The origin process may be purged (workspace cleanup, capacity eviction)
before the dispatch/continuation fires, so fire-time can't rely on it
still existing — the row carries its own copy forward.

### Chain inheritance

A `RunAgent` sub-run inherits its parent's owner unchanged, threaded
end-to-end: `Process.owner` → `AgentExecutor` → `ExecutionContext.owner` →
`RunAgentExecutor` reads it and passes it to the child's
`AgentRunStarter.start(owner = ...)` → the new child `Process.owner`. The
owner is never re-derived from "whoever is currently executing" at each
hop — it is the original top-of-chain principal, all the way down.

### MCP

The `/mcp` agent-tool list is filtered to agents the caller holds a `run`
grant on, and any run started through MCP is owner-stamped with the
caller's principal, same as the REST path.

**Known gap, deferred:** the MCP management tools (`get_run_info`,
`list_artifacts`, `get_artifact`, `cancel_run`) do not yet call
`canSee`/`canControl` — any authenticated MCP caller who knows or guesses
a `runId` can currently read its status/artifacts or cancel it, regardless
of ownership or grants. This is a real, currently-shipped gap, tracked as
follow-up work, not fixed by this ADR. *(Amended 2026-10-09: closed — all
four tools now call `canSee`/`canControl`, per
[ADR-0039](0039-nested-dispatch-authority.md).)*

### Deferred to Plan 3

Nested-dispatch **authority** — denying a `RunAgent`/dispatch hop when the
caller's (or the dispatching agent's `server.agentGrants`) permissions
don't cover the target agent — is explicitly out of scope here. This ADR
stamps owner down the chain for *visibility* (so the resulting run is
attributable and see-able by the right principal) but does not gate the
hop itself. [ADR-0030](0030-runagent-in-process-dispatch.md) stays
`accepted`, unaffected: `RunAgent` continues to dispatch any agent
unconditionally pending that follow-up. *(Amended 2026-10-09: hop gating
is implemented by ADR-0039.)*

### Consequences

- Good: closes the Plan-1 CHANGELOG caveat for process-scoped routes and
  MCP agent-tool listing — grants now mean something on the read/kill/
  retry path, not just run/edit/debug/admin.
- Good: the owner-bypass on `canSee`/`canControl` preserves the
  load-bearing "I can always see and kill what I started" guarantee even
  for a principal with zero grants.
- Good: 404-hiding is uniform across every single-resource process route —
  no new information-disclosure surface from adding this check.
- Good: null-owner rows (pre-migration, or any as-yet-unstamped call path)
  degrade safely — visible only to admin and `read`-grant holders, never
  accidentally "owned" by a null-principal caller.
- Bad: **MCP management tools remain visibility-ungated** — a real,
  documented gap until a follow-up closes it. *(Closed by ADR-0039.)*
- Bad: **nested-dispatch authority remains unenforced** — `RunAgent`
  still dispatches any agent unconditionally; only its owner-stamping
  changed, not its gating. *(Closed by ADR-0039.)*
- Neutral: `dispatches`/`continuations` visibility is a Kotlin post-filter,
  not a SQL `WHERE` clause like `processes` — acceptable given list sizes,
  but not the same performance profile if either table grows large.
  *(Amended 2026-10-09: both now filter in SQL, see ADR-0021's 2026-09-30
  amendment.)*

## More Information

- Authorizer: [`Authorizer.kt`](../../app/app/src/main/kotlin/homer/app/services/Authorizer.kt)
  — `canSee`/`canControl`/`canRetry`.
- Schema v8 migration:
  [`DatabaseService.kt`](../../app/app/src/main/kotlin/homer/app/services/persistence/DatabaseService.kt).
  `owner` on `Process`/`Dispatch`/`Continuation`:
  [`ProcessManager.kt`](../../app/app/src/main/kotlin/homer/app/services/ProcessManager.kt),
  [`DispatchService.kt`](../../app/app/src/main/kotlin/homer/app/services/DispatchService.kt),
  [`ContinuationService.kt`](../../app/app/src/main/kotlin/homer/app/services/ContinuationService.kt).
- Route enforcement:
  [`SecuredRoutes.kt`](../../app/app/src/main/kotlin/homer/app/routes/SecuredRoutes.kt),
  [`StreamingRoutes.kt`](../../app/app/src/main/kotlin/homer/app/routes/StreamingRoutes.kt).
- MCP filtering:
  [`McpToolFactory.kt`](../../app/app/src/main/kotlin/homer/app/services/McpToolFactory.kt).
- Config reference: [`app/SERVER.md`](../../app/SERVER.md) §
  *Authentication Configuration*.
- Builds on [ADR-0037](0037-grants-authorization.md) (grants/actions
  model, `run`/`read` action semantics this ADR enforces); nested-dispatch
  authority and MCP management-tool gating remain follow-up work (Plan 3);
  [ADR-0030](0030-runagent-in-process-dispatch.md) is unaffected.

## Amendment (2026-09-28): the broadcast stream is removed

[ADR-0070](0070-llm-harness-adapter-boundary.md) removes
`GET /stream/processes/{id}`, the `claude.event` broadcast named above as one
of the two single-process streams. The pre-stream `canSee` 404 now applies to
`GET /stream/processes/{id}/logs` and to its replacement,
`GET /stream/processes/{id}/llm-events`, which resolves the same check before
the connection opens and also returns `404` for a `cmd` that is not an LLM
harness command.
