---
status: accepted
date: 2026-06-16
deciders: Lukáš Vlk
---
# ADR-0025: Capability-token read-by-reference for large continuation results

## Context and Problem Statement

Completion continuations (ADR-0023) embed a watched sub-process's result inline
in the resume payload, but cap the inline size at 64 KiB. Over that cap the
payload set `result = null` and the only "reference" was the `processId` — the
parked parent had **no way to retrieve the full result**. A LangGraph fan-out
to a sub whose output exceeds 64 KiB therefore silently lost that output.

We need a way for the resumed parent (a workspace command holding only its own
`HMR_CALLBACK_TOKEN`) to fetch the full, optionally-transformed result of a
*different* process's artifact — without handing it a broad operator credential
or letting it read arbitrary artifacts.

## Decision Drivers

- The parent command holds only `HMR_CALLBACK_TOKEN`, scoped to its own
  process. It must not gain blanket artifact-read access to do this.
- The reference must be narrowly scoped: one watched process, one artifact path,
  with the same result transform that an inline result would have had.
- A resumed graph re-runs the node from the top, so the fetch must be safely
  repeatable (idempotent, reusable within a TTL).
- Reuse existing artifact path-safety, size, and streaming machinery rather than
  inventing a parallel reader.

## Considered Options

- **Capability token (minted reference)** vs. **widening `HMR_CALLBACK_TOKEN` to
  read other processes' artifacts** vs. **inlining everything (no cap)**.
- **Token reusable within TTL** vs. **single-use token**.
- **Apply the result transform server-side at fetch** vs. **stream raw and
  transform in the Python client** (which would need a JsonPath dependency).
- **Token lifetime tied to the continuation row** vs. **its own table and TTL**.

## Decision Outcome

1. **Mint a capability token on the over-cap branch.** When `extractResult`
   sees a result above the 64 KiB cap, `ContinuationService` mints a token
   scoped to `(watchedProcessId, stdout artifact path, resultTransformer,
   sizeBytes)` and adds a `resultRef {processId, path, token, sizeBytes}` to the
   resume payload (with `result` still null).

2. **Own table, own TTL.** Tokens live in a dedicated `result_tokens` table
   (schema v4) with their own `expiresAt`, independent of the continuation row
   (which transitions to `DISPATCHED` immediately). TTL is
   `server.dispatch.resultTokenTtlSec` (default 3600 s, floored at 60 s) so a
   token always outlives the continuation's immediate dispatch and the resumed
   parent's first segment. Expired rows are reaped by the existing continuation
   sweep cadence.

3. **Reusable within TTL.** A resumed graph re-runs the node and re-fetches; the
   token authorizes repeated idempotent GETs until it expires.

4. **Dual-auth fetch endpoint.** `GET /api/v1/callback/results/{token}` is
   mounted outside `authenticate` next to the other callback routes. It requires
   **both** a valid `HMR_CALLBACK_TOKEN` (proving a live Homer process is
   calling) **and** the capability token (which is the scoping authority). The
   token is resolved with a constant-time compare and TTL enforcement.

5. **Transform server-side at fetch.** The stored `resultTransformer` is applied
   via `ResultToEnvMapper` when the artifact is read, so the Python client needs
   no JsonPath dependency. A JsonPath/regex transform parses the whole file in
   memory, so it is capped at 4 MiB; over that the fetch is raw-only (a transform
   request answers `413`). The raw stream is still bounded by
   `server.maxArtifactBytes`.

6. **Client auto-fetch.** A `homer-result` shell wrapper redeems the token; the
   `homer_langgraph` runtime's `homer_fanout` fetches automatically when it sees
   `result: null` with a `resultRef`, so graph authors see a normal payload.

## Consequences

- Good: large fan-out results are no longer dropped; the parent recovers them by
  reference with the same transform semantics as an inline result.
- Good: the parent gains no broad credential — the capability token is narrow,
  expiring, and minted by Homer, not the caller.
- Good: reuses `ArtifactService` (path-safety, size, stream) and
  `ResultToEnvMapper`; the new surface is one table, one service, one endpoint.
- Neutral: a result between 4 MiB and `server.maxArtifactBytes` can be fetched
  raw but not transformed server-side.
- Bad: an extra round-trip (and a DB row per over-cap result) versus inline
  delivery; mitigated by the TTL sweep.

This supersedes, in part, ADR-0023's decision #3: the over-cap branch no longer
strands the result — it resumes by a fetchable reference rather than a bare
`processId`.

## Amendment (2026-08-11): audience-bind the token to the target agent

The original dual-auth (decision #4) authorized the fetch on possession of *any*
valid `HMR_CALLBACK_TOKEN` plus the capability token — it resolved the caller's
process id but discarded it. So any live process that obtained the capability
token (e.g. via a log leak) could redeem it, regardless of which agent it ran.

The token is now **audience-bound**: `result_tokens` carries a `consumer_agent`
column (schema v7) set at mint time to the continuation's `targetAgentName`, and
`GET /api/v1/callback/results/{token}` additionally requires the calling
process's agent (looked up via `QuestionsService.registrationFor`) to equal that
audience — otherwise `403`. The check **fails closed** when the caller's
registration is absent (e.g. after a restart wiped the in-memory registry).

This does **not** weaken the reusability in decision #3. Homer resumes a parked
LangGraph thread by re-running the *same* target agent as a fresh process
(`homer_langgraph`), and multi-interrupt graphs re-execute an earlier over-cap
fan-out node on later segments — so the same token is legitimately re-fetched by
*different processes that all run the target agent*. Binding to the agent (not
the process) preserves exactly that cross-segment re-fetch while removing the
"any live process" gap. Binding to the single first-fetching process was
rejected for this reason: it would break multi-segment re-fetch.

Pre-existing tokens on an upgraded dev DB get the empty-string default, matching
no agent, so they fail closed until the TTL sweep removes them (the feature is
unreleased, so no production tokens exist). Combined with the earlier redaction
of the token from `api.log`, this closes the "Unbound Callback Result
Authorization" finding.
