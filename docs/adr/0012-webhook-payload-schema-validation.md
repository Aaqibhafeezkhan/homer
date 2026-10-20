---
status: accepted
date: 2026-05-27
deciders: Lukáš Vlk
---
# ADR-0012: Webhook payload schema validation

## Context and Problem Statement

MCP / webhook integrations `POST /api/v1/agent/{name}` with a JSON body.
Homer extracted only the individual fields named by `inputs`; the payload
as a whole was never validated, so a malformed or unexpected payload still
spawned a process that would fail (or behave surprisingly) downstream. We
want operators to be able to declare the expected payload shape and have
non-conforming requests rejected at the HTTP boundary, before any process
is created.

## Decision Outcome

- An agent may declare an optional inline `inputSchema` — a standard JSON
  Schema (Draft 2020-12), the dialect the bundled
  `com.networknt:json-schema-validator` is configured for. Inline (not an
  external file reference) keeps the contract in one place; an
  external-file form is a possible future extension.
- The schema validates the **entire JSON request body**, independent of
  `inputs` (which still extract fields into env vars).
- Validation runs in the **synchronous request prelude**
  (`AgentInputResolver`), so a failure maps to an HTTP status instead of
  becoming a failed async run.
- **Status split:** a well-formed body that violates the schema → **422
  Unprocessable Entity** (`PayloadSchemaValidationException`); a
  missing/empty body, wrong Content-Type, unparseable body, or
  GET-with-schema → **400** (the existing `QueryParamValidationException`
  cases). This mirrors Homer's existing use of 422 for invocation
  depth/cycle ([ADR-0008](0008-recursion-guard-via-invocation-chain-header.md)).
- A **malformed `inputSchema` is rejected at agent load** (the agent is
  skipped with an error log), the same posture as the `maxConfigFileBytes`
  size cap, so a broken schema never reaches request handling.
- Schemas are compiled and cached per distinct schema
  (`InputSchemaValidator`), keyed by canonical content.

## Consequences

- Operators get a declarative payload contract and clear 4xx feedback.
- The `inputSchema` blob counts toward the existing `maxConfigFileBytes`
  load cap — no new size logic.
- No runtime change to agents without `inputSchema`.
- Query / header inputs are not schema-validated (they are strings
  governed by the per-input `type` check); the schema is a JSON-body
  contract only.
