---
status: accepted
date: 2026-05-06
last-updated: 2026-05-14
deciders: Lukáš Vlk
---
# ADR-0008: Bound `RunAgent` recursion via the `X-Homer-Invocation-Chain` header

> **Amendment (2026-10-09) — transport superseded in part by [ADR-0030](0030-runagent-in-process-dispatch.md).**
> `RunAgent` dispatches in-process and passes the chain directly; no header is
> sent or read, the HTTP route always starts an empty chain, and the
> `X-Homer-Loopback-Token` is removed. The cycle/depth rule and
> `HMR_INVOCATION_CHAIN` are unchanged.

## Context and Problem Statement

Agents can call other agents through `Command.RunAgent`, which posts
to `POST /api/v1/agent/{name}` over HTTP — even for in-process calls,
because the executor goes through Ktor like any external client. With
no bound on chain length, a self-referential or cyclic agent graph
(`A → A`, `A → B → A`, or just deeply nested orchestration) could
launch processes until `ProcessManager` ran out of capacity, at which
point [ADR-0002 + ADR-0007 era] new requests start failing with
`503 Service Unavailable` and the cluster is wedged until the chain
unwinds.

The problem is bounding *recursion* across HTTP-launched agent
invocations, where each hop is a fresh request from Homer to itself
with no shared in-process state between caller and callee.

## Decision Drivers

- **No shared in-process state across hops.** A `RunAgent` call is
  HTTP, not a Kotlin function call. Even with the parent process
  still alive locally, the called agent is a fresh request handler;
  there is no parent-pointer the child can walk to detect a cycle.
- **External callers must be bounded the same way internal callers
  are.** A cron job, a shell script, or a malicious external client
  hitting `POST /api/v1/agent/X` directly should hit the same cap as
  an internal `RunAgent` chain. Anything else is a bypass.
- **Cycles are guaranteed-loops; depth is just a backstop.**
  `A → B → A` is going to fail no matter how high `maxAgentInvocationDepth`
  is. Refusing it as soon as the cycle is provable saves wasted
  retries and keeps the error message actionable.
- **The bound must be visible inside the called process.** Agents
  composing chains often want to know how deep they are (for logging,
  for conditional behavior). Exposing the chain as an env var lets
  agent authors observe and react to it without a second API call.

## Considered Options

- **Option A — `X-Homer-Invocation-Chain` HTTP header**, threaded
  through `RunAgentExecutor` on outbound calls and read on every
  inbound request; cycle and depth checked at the request boundary;
  exposed inside the called process as `HMR_INVOCATION_CHAIN` env
  var.
- **Option B — Server-side in-memory tracking** keyed by
  `parentProcessId`, with the call graph rebuilt at refusal time.
- **Option C — Agent-author-supplied depth in the agent definition**
  (e.g., `maxDepth` on `Command.RunAgent`).
- **Option D — Do nothing; rely on `ProcessManager` capacity to
  eventually reject the chain.**

## Decision Outcome

Chosen option: **"Option A — `X-Homer-Invocation-Chain` HTTP
header"**.

The header carries a comma-separated list of upstream agent names.
On every `POST /api/v1/agent/{name}`, the handler:

1. Reads the inbound `X-Homer-Invocation-Chain` header (empty list if
   absent).
2. Refuses with `422 Unprocessable Entity`
   (`InvocationCycleDetectedException`) if the target agent name is
   already in the chain.
3. Refuses with `422 Unprocessable Entity`
   (`InvocationDepthExceededException`) if `chain.size + 1 >
   server.maxAgentInvocationDepth` (default `5`).
4. Otherwise, appends the target's name and threads the new chain
   into the workspace as `HMR_INVOCATION_CHAIN`. `RunAgentExecutor`
   reads the env var and writes it back to the outbound header on
   the next hop. *(Superseded by ADR-0030: the chain is passed
   in-process; see the note above.)*

Cycle check is **before** depth check so the error message names the
actual problem when both apply (`A → B → A` at depth 5+ reports a
cycle, not a depth violation).

The refusal fires in the synchronous request prelude (before
`ProcessExecutor.coroutineScope.launch`), so it reaches `StatusPages`
and returns `422` cleanly per [ADR-0002](0002-unified-error-model-at-http-boundary.md).
A check inside the launch path would be invisible to the HTTP layer
and indistinguishable from any other failure mode.

The inbound chain header is trusted only when accompanied by a
matching `X-Homer-Loopback-Token`. The server generates a random
loopback token once per JVM process and `RunAgentExecutor` stamps
every outbound self-call with it. Inbound requests without the token
(or with a wrong value) get a fresh empty chain regardless of the
chain header they supplied. This was a refinement made on 2026-05-14
(audit item P1 #11): the original design left an authenticated
external caller able to pre-populate the chain — strictly self-DoS,
but still surface area worth closing now that the fix is mechanical.
The loopback token is never serialized in any API or response; the
only way to obtain it is to read the server process's memory.
*(Superseded by ADR-0030: the loopback token no longer exists.)*

### Consequences

- Good: bound applies uniformly to internal `RunAgent` calls and
  external direct calls; no codepath is special-cased.
- Good: stateless on the server side. No call graph to maintain, no
  parent-pointer tracking, no cleanup on process termination.
- Good: refusal reaches HTTP 422 cleanly via the synchronous prelude.
- Good: the chain is visible inside the called process as
  `HMR_INVOCATION_CHAIN`, so agent authors can log it, branch on
  it, or refuse based on their own rules.
- Good: cycle check fires regardless of depth (`A → B → A` at depth
  3 of a 5-cap is still refused), saving the runtime that would
  otherwise be spent on a guaranteed-loop.
- Good: the inbound chain header is authenticated by a per-process
  loopback secret (`X-Homer-Loopback-Token`), so external callers
  cannot lie about the chain in either direction. Cron and webhooks
  always start at depth 0 regardless of any header they send.
  *(Superseded by ADR-0030: no header or token; HTTP callers start at an empty chain.)*
- Bad: agent names appear in logs and in env on the called process.
  If agent names are ever considered sensitive, the header would
  need redaction; today they are not.
- Bad: no enforcement on `Command.RunAgent` chains that go *out* of
  Homer (e.g., webhooks). Out of scope: those are not Homer-managed
  invocations.

## Pros and Cons of the Options

### Option A — Header-threaded chain

- Good: stateless, simple, uniform across internal and external
  callers.
- Good: composes with future runners (Kubernetes, etc.) — the chain
  flows through whatever transport the runner uses, since
  `RunAgentExecutor` is HTTP regardless of how the agent itself
  runs.
- Neutral: header size grows linearly with depth. At a cap of 5 with
  reasonable agent names, the header is well under any HTTP-server
  limit.
- Bad: depends on inbound caller honesty for the chain reported, but
  the only impact of a lying caller is to refuse themselves.

### Option B — Server-side in-memory tracking

- Good: cannot be lied about by external clients.
- Bad: requires keeping a parent → child graph keyed by
  `parentProcessId`, which means new lifecycle hooks
  (creation, termination, abandonment) and cleanup logic.
- Bad: cross-runner deployments (where some hops execute in a
  Kubernetes pod) would need the graph mirrored, since the parent
  process and child request handler may not be on the same node.

### Option C — Agent-author-supplied depth

- Good: declarative.
- Bad: the agent author is exactly the wrong person to enforce a
  cluster-wide cap. Operators who run an Homer instance need to be
  able to bound recursion regardless of what an individual agent
  declares.

### Option D — Rely on `ProcessManager` capacity

- Good: zero new code.
- Bad: by the time the cap fires, the cluster is wedged. The whole
  point is to refuse the runaway chain *before* it consumes
  capacity.

## More Information

- HTTP layer: `handleAgentExecution` and `handleRetry` in
  [`Application.kt`](../../app/app/src/main/kotlin/homer/app/Application.kt).
- Outbound propagation: [`RunAgentExecutor.kt`](../../app/app/src/main/kotlin/homer/app/executors/RunAgentExecutor.kt).
- Constants: `EnvConstants.INVOCATION_CHAIN`,
  `INVOCATION_CHAIN_HEADER`, `LOOPBACK_TOKEN_HEADER` in
  [`EnvConstants.kt`](../../app/app/src/main/kotlin/homer/app/EnvConstants.kt).
- Loopback token: `homer.app.loopbackToken` top-level val in
  [`Application.kt`](../../app/app/src/main/kotlin/homer/app/Application.kt).
- Exceptions: `InvocationCycleDetectedException`,
  `InvocationDepthExceededException` in
  [`Exceptions.kt`](../../app/app/src/main/kotlin/homer/app/exceptions/Exceptions.kt).
- Configuration: `server.maxAgentInvocationDepth` (default `5`),
  documented in [`SERVER.md`](../../app/SERVER.md).
- Related ADRs: [ADR-0002](0002-unified-error-model-at-http-boundary.md)
  for why the refusal must fire in the synchronous prelude.
