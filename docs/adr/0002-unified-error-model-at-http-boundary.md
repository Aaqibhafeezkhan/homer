---
status: accepted
date: 2026-04-21
deciders: Lukáš Vlk
---
# ADR-0002: Unified error model at the HTTP boundary

## Context and Problem Statement

Homer's backend already had the pieces of a unified error model — a sealed
`HomerException` hierarchy and an `ErrorResponse` wire type — but no
central place that mapped exceptions to HTTP status codes. Ktor's
`StatusPages` plugin was never installed, so unhandled `HomerException`s
bubbled up as the framework's default plain-text 500. The only custom
exception with HTTP awareness, `QueryParamValidationException`, was caught
per-route inside `handleAgentExecution` and mapped by hand. The result
was inconsistent: the same exception class could produce a structured JSON
body on one route and an unstructured 500 on another.

An important constraint shaped the scope: most `HomerException`s are
thrown inside `ProcessExecutor`'s `coroutineScope.launch { try/catch }`
block, so they never propagate back to the HTTP handler. The HTTP
response for async execution is always `200 {processId}` and clients
discover failures via status polling. Any HTTP-boundary mapping therefore
only covers the synchronous prelude (query-param parsing, process
lookup) and any future code that throws a `HomerException` directly in a
request handler — still valuable, but the scope is narrower than it first
appears.

## Decision Drivers

- Every exception class should have exactly one HTTP status mapping,
  defined in one place.
- Route handlers should stop duplicating error-to-response boilerplate.
- A new `HomerException` subclass should either be mapped explicitly or
  produce a visible failure — silent defaulting to 500 is worse than a
  compile error.
- Keep service signatures stable. Do not turn this into a full
  error-handling refactor.

## Considered Options

- **Option A — Install StatusPages, map each subclass in one handler.**
  Add a `configureStatusPages()` helper next to the existing
  `configureCors()`. Register a single `exception<HomerException>` branch
  with an exhaustive `when` over the sealed hierarchy. Delete the
  per-route try/catch. Nothing else moves.
- **Option B — Broader refactor.** Convert `ArtifactService` to
  `Result<T>`, remove nullable returns from `ProcessManager`, surface the
  silent `.getOrNull()` paths in `ResultToEnvMapper` / `AgentQueryParams`.
  Unified model end-to-end, not just at the edge.
- **Option C — Per-route error mapping, consistently applied.** Keep
  local try/catches but extract a shared mapping helper each route calls.

## Decision Outcome

Chosen option: **"Option A — install StatusPages, map each subclass in
one handler"**, because it addresses the actual problem (no central
mapping) with the smallest footprint and leaves every service signature
untouched. The mapping lives in `Application.configureStatusPages()` as a
`when (e)` over the sealed hierarchy, so exhaustiveness is checked at
compile time — a future subclass must be explicitly classified, rather
than silently defaulting to 500.

Mapping:

| Exception | HTTP status |
| --- | --- |
| `QueryParamValidationException` | `e.httpStatusCode` (default 400) |
| `ValidationException` | 400 |
| `AnotherProcessStillActive` | 409 Conflict |
| `MaxParallelRunsExceededException` | 429 Too Many Requests |
| `ConfigurationException` / `CommandExecutionException` / `AgentException` / `WorkspaceException` | 500 |

### Consequences

- Good: one authoritative mapping, zero per-route catches for
  `HomerException`.
- Good: the `when` is exhaustive on the sealed class, so adding a new
  subclass without mapping it is a build failure, not a silent 500. The
  top-level `homerExceptionStatus` keeps that guarantee; its helpers
  `requestErrorStatus` and `transferCapacityOrServerStatus` map only the
  request-error, respectively the transfer, capacity and server-side
  subclasses handed to them, and end in an intended `else -> 400`,
  respectively `else -> 500`.
- Good: response bodies are now uniformly `ErrorResponse` JSON for every
  `HomerException`, matching the wire format that
  `QueryParamValidationException` already used. The one exception is
  `BodylessBadRequestException`, which StatusPages answers with a bare `400`
  so the SSE stream endpoints `/stream/processes/{id}/logs` and
  `/stream/processes/{id}/llm-events` keep their pre-existing bodiless 400.
- Bad: the fix only covers the synchronous request prelude, because async
  executor exceptions never reach the handler. The status-polling path
  for async failures is unchanged by this ADR.
- Bad: silent-null transformers (`ResultToEnvMapper`,
  `AgentQueryParams`) and nullable `ProcessManager` returns remain as
  they were; fixing those is explicitly out of scope.

## More Information

- Implementation: commit `9651c73` — `Application.kt` +
  `ApplicationTest.kt`.
- Roadmap entry: M3 in the Homer remediation roadmap.
- Reuses: `HomerException` sealed hierarchy, `ErrorResponse` data class,
  `ktor-server-status-pages` (already on the classpath via the `ktor`
  bundle).
