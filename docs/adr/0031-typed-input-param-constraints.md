---
status: accepted
date: 2026-07-12
deciders: Lukáš Vlk
---
# ADR-0031: Typed input-param constraints, validated by a dedicated `ParamValidator`

## Context and Problem Statement

Agent input params (`Agent.inputs: List<Param>`) declared a `type` of
`STRING | NUMBER | ANY`, checked by an ad-hoc heuristic in
`AgentsService.validateParameter`: `STRING` rejected any value containing a
space (surprising; authors fell back to `ANY` and lost validation entirely),
`NUMBER` meant "parses as `Int`" with no decimals or bounds, and there was no
way to express enums, patterns, ranges, length limits, booleans, or defaults.
The check also lived in the wrong place — `AgentsService`, not a resolver or
validator — and author mistakes surfaced only on first request, not at agent
registration. `inputSchema` (JSON Schema via networknt) already validates the
request *body*, but query/header params never reach it.

## Decision Drivers

- Predictable, documented param types with real constraints (enums, regex,
  numeric ranges, length bounds, defaults).
- Author errors must surface at agent load, not at first request.
- Legacy `STRING`/`NUMBER`/`ANY` and the `queryParams:` alias must keep
  working this release with unchanged semantics — no breaking change yet.
- Match the repo's existing flat `Param` style rather than introduce nesting.

## Considered Options

- **Option A — Flat constraint fields on `Param`, dedicated `ParamValidator`,
  lowercase types as the generation discriminator.**
- **Option B — Compile each `Param` to a JSON Schema fragment and reuse
  `InputSchemaValidator`.**
- **Option C — Adopt a typed-object validation library (e.g. Konform).**
- **Option D — A nested `constraint:` block on `Param` instead of flat
  fields.**

## Decision Outcome

Chosen option: **"Option A"**. `ParamType` gains four lowercase entries
(`string`, `integer`, `number`, `boolean`); the uppercase legacy entries stay,
their deprecation expressed via KDoc on the enum entries, the JSON schema
`type` description, `AGENT.md`, and a load-time WARN — not `@Deprecated`,
which would make `ParamValidator`'s legitimate legacy branch emit compiler
warnings on code that must keep working. Casing in the agent file is the discriminator — a param
written as `type: string` gets the new semantics and constraint fields
(`values`, `pattern`, `min`/`max`, `minLength`/`maxLength`, `default`, all
optional and type-appropriate); a param written as `type: STRING` keeps
today's exact behavior. This lets both generations coexist in one enum with
zero new top-level fields, and makes migration a spelling change.

A new `homer.app.infra.ParamValidator` owns all param-value logic —
`validateDeclaration(param)` for load-time shape errors and
`validate(value, param)` for runtime checks with precise messages (e.g.
"Parameter 'count' must be <= 100, got 250"). `AgentsService.validateParameter`
is deleted; the legacy `STRING`/`NUMBER`/`ANY` logic moves into
`ParamValidator` behind a legacy branch, byte-for-byte equivalent to before.
`AgentsService` registration now calls `validateDeclaration` per param
alongside the existing reserved/duplicate `envName` checks, rejecting the
agent at load on out-of-range constraints, incompatible constraint/type
pairings, uncompilable patterns, or a `default`/`values` entry that fails the
param's own constraints.

Defaults are resolved in the single `AgentInputResolver.resolveInternal`
loop: when a param's raw value is absent and `default` is set, the default is
substituted and then validated like any value. Every entry point (HTTP
execute, debug, MCP, dispatch, `RunAgent` forwarding, continuations) funnels
through `resolveInternal`, so defaults behave identically everywhere,
including `enforceRequired = false` paths that previously dropped a missing
optional param silently.

### Consequences

- Good: real types, constraints, and load-time rejection close the original
  gaps without touching the resolved-value representation (inputs still land
  as `Map<String, String>` env vars).
- Good: one validator (`ParamValidator`) instead of logic split across
  `AgentsService` and ad-hoc call sites.
- Good: `McpToolFactory` maps the same lowercase types/constraints to real
  JSON Schema (`enum`, `pattern`, `minimum`/`maximum`, `minLength`/
  `maxLength`, `default`) in generated MCP tool schemas, so MCP clients see
  an accurate contract derived from the same declaration; legacy uppercase
  types keep their current, coarser mapping.
- Good: `inputSchema` keeps its existing, unchanged role as the body-payload
  escape hatch for structured JSON the flat param model doesn't cover.
- Bad: legacy `STRING`/`NUMBER`/`ANY` and the `queryParams:` alias are frozen
  in place (with a load-time deprecation WARN) rather than removed — that
  removal is deferred to a future breaking release.

## Pros and Cons of the Options

### Option B — JSON-Schema fragment compilation via `InputSchemaValidator`

- Neutral: would reuse an existing dependency (networknt) instead of adding
  a new validator class.
- Bad: query/header values arrive as strings, so JSON Schema cannot validate
  `"42"` as an integer without a hand-written pre-coercion layer — the exact
  string-parsing logic we'd write anyway, just relocated.
- Bad: networknt error messages are poor for this use case (e.g. `$.count:
  must have a maximum value of 100`) compared to a purpose-built message.
- Bad: per-request schema evaluation is heavier than a direct field check.

### Option C — Typed-object validation library (Konform-style)

- Bad: these libraries fit statically-typed DSLs declared in code, not
  constraints loaded data-driven from YAML/JSON at runtime — wrong shape for
  `Param`.

### Option D — Nested `constraint:` block

- Neutral: would group constraint fields visually.
- Bad: verbosity that breaks from the repo's established flat `Param` style,
  for no compensating benefit — flat fields were kept for consistency.

## More Information

- Validator: [`ParamValidator.kt`](../../app/app/src/main/kotlin/homer/app/infra/ParamValidator.kt).
- Default resolution: [`AgentInputResolver.kt`](../../app/app/src/main/kotlin/homer/app/infra/AgentInputResolver.kt).
- MCP schema mapping: [`McpToolFactory.kt`](../../app/app/src/main/kotlin/homer/app/services/McpToolFactory.kt).
- Field reference: [`app/AGENT.md`](../../app/AGENT.md).
