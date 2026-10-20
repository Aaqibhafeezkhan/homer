---
status: accepted
date: 2026-06-24
deciders: Lukáš Vlk
---
# ADR-0030: `RunAgent` dispatches in-process, not via an HTTP self-call

## Context and Problem Statement

`Command.RunAgent` (`!<agent>`) let a running agent start another agent by
having `RunAgentExecutor` perform an HTTP **self-POST** to the secured route
`POST /api/v1/agent/{name}`, forwarding the caller's `Bearer context.authToken`
plus a loopback marker ([ADR-0008](0008-recursion-guard-via-invocation-chain-header.md))
so the receiving route would trust the propagated invocation chain and ancestor
lock keys. But a run started from the **console via session cookie** has no
bearer token (`context.authToken == null`); the self-POST then went out with no
`Authorization` header and was rejected by the `authenticate()` wrapper whenever
API auth was enabled. The `agent:` command type was therefore broken for
console-cookie-initiated runs, forcing operators to inject or bypass a token.

`RunAgentExecutor` runs **inside the Homer JVM**, and both dispatch paths (the
HTTP route and `DispatchService`) already converge on the same in-process core,
`AgentRunStarter.start(...)`, which accepts `invocationChain`, `authToken`,
`source`, and `ancestorLockKeys`. The HTTP hop was pure overhead that *created*
the auth-token coupling.

## Decision Drivers

- **Auth-scheme independence.** Starting a child agent must work regardless of
  how the parent run was authenticated (bearer, HMAC, or session cookie).
- **No behavior regression.** Invocation-chain cycle/depth enforcement and
  ancestor-lock-key propagation must be preserved exactly.
- **Less surface area.** The loopback-token machinery existed only to let the
  HTTP route trust a self-call's chain; removing the self-call removes the need
  for it.

## Considered Options

- **Option A — Dispatch in-process via `AgentRunStarter.start(...)`**, passing
  `invocationChain`, `authToken`, `source = "agent"`, and `ancestorLockKeys` as
  parameters; resolve inputs call-free via `AgentInputResolver.resolveDebug`.
- **Option B — Keep the HTTP self-call but mint/forward a synthetic bearer
  token** when the parent has none (e.g. reuse the server-wide `config.authToken`).
- **Option C — Keep the HTTP self-call but exempt loopback requests from the
  auth gate.**

## Decision Outcome

Chosen option: **"Option A — in-process dispatch"**. `RunAgentExecutor` calls
`AgentRunStarter.start(...)` directly. There is no network hop, so there is no
auth gate to satisfy: a `null` `authToken` is now fine. Cycle/depth checks and
ancestor-lock propagation are preserved by passing them to `start()`, exactly as
the HTTP route did. This is the full in-process unification of the two dispatch
paths.

`RunAgent` keeps invoking **any** agent — there is no `dispatch.enabled` gate.
That gate is specific to `DispatchService` (the `/api/v1/callback/runs` path),
not the `/api/v1/agent` route this command mirrors.

**Superseded (in part) by [ADR-0039](0039-nested-dispatch-authority.md):**
`RunAgent` hops are now authority-gated (root grants ∪
`server.agentGrants`) — a denied hop exits `255` instead of dispatching
unconditionally. The rest of this ADR (in-process dispatch, no HTTP
self-call, `dispatch.enabled` being `DispatchService`-specific) is
unaffected.

This **supersedes** the loopback-self-call mechanism of
[ADR-0008](0008-recursion-guard-via-invocation-chain-header.md): with no HTTP
self-call, nothing sends `X-Homer-Loopback-Token`, so the inbound route always
starts external callers at a fresh chain (unchanged observable behavior) and the
loopback machinery is removed. It also **refines**
[ADR-0022](0022-script-driven-immediate-dispatch.md): the distinction between a
forwarded caller bearer and the server-wide dispatch token no longer applies to
`RunAgent` — it forwards the caller's `authToken` as-is (which may be null).

### Consequences

- Good: `agent:` works under session-cookie auth (the original bug) and under
  any other auth scheme, with no token configured.
- Good: removes the per-process loopback token, the two trust headers' route
  logic, and the orphaned internal `HttpClient`.
- Good: one dispatch core (`AgentRunStarter`) for HTTP, `DispatchService`, and
  `RunAgent` — caps, locks, and parallel-run limits apply identically.
- Bad: `Command.RunAgent.timeout` becomes a no-op. It used to clamp the dispatch
  HTTP request; in-process, `start()` returns the `processId` near-instantly, so
  there is nothing to bound. The child's own command timeouts govern it. The
  field is retained for schema compatibility and documented as inert in AGENT.md.
- Follow-up (removal): the `authToken` that flowed `start()` → `Process` →
  `ExecutionContext` existed only to populate `RunAgentExecutor`'s HTTP
  `Authorization` header. With that call gone it had no consumer (it was never
  exposed to commands as an env var), so the whole parameter and the two config
  fields that fed it — `agent.cronAuthToken` and `server.dispatch.authToken` —
  were removed as dead. **BREAKING**; see CHANGELOG.

## More Information

- Executor: [`RunAgentExecutor.kt`](../../app/app/src/main/kotlin/homer/app/executors/RunAgentExecutor.kt).
- Dispatch core: [`AgentRunStarter.kt`](../../app/app/src/main/kotlin/homer/app/services/AgentRunStarter.kt).
- DI cycle break (provider lambda): `AppModule` `RunAgentExecutor` single,
  same precedent as `ProcessExecutor.onTerminal` ([ADR-0023](0023-completion-continuations.md)).
- Supersedes the loopback mechanism of [ADR-0008](0008-recursion-guard-via-invocation-chain-header.md);
  refines [ADR-0022](0022-script-driven-immediate-dispatch.md).
