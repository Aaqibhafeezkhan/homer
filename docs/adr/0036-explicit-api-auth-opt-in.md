---
status: accepted
date: 2026-08-11
deciders: Lukáš Vlk
---
# ADR-0036: `server.apiAuth` is required — unsecured is an explicit opt-in

## Context and Problem Statement

`Server.apiAuth` defaulted to `AuthConfig.Unsecured`. A config that omitted the
field — a fresh minimal config, a typo'd key swallowed by the YAML parser's
sibling defaults, a copy-paste that dropped the block — silently exposed every
API endpoint (including agent execution and, with the console routes, agent
file editing) without any authentication. The only signal was a startup WARN
banner in `app.log` that nothing forces an operator to read.

Security scanning ("Unsecured Default API Authentication", risk 7.2)
flagged the insecure-by-default posture: the *absence* of configuration should
never select the weakest mode.

## Decision Drivers

- **Secure by default**: forgetting a key must fail loudly, not open the API.
- Unsecured mode is still legitimate (local development, trusted networks) and
  must remain available — but as a visible, deliberate choice in the config.
- The fix must not change behavior for any config that already sets `apiAuth`
  (all known real deployments do).
- Mirror the established fail-fast pattern: startup validators throwing
  `ConfigurationException` (ADR-0016 CORS-for-cookie-auth, HMAC signature
  scope, session-secret strength).

## Considered Options

- **Nullable `apiAuth` + startup validator** — `null` means "omitted", startup
  fails; explicit `!<unsecured>` still parses to `Unsecured` and runs.
- **Keep the default, WARN harder** — no contract change, but the failure mode
  (silently open API) survives; a louder log is still ignorable.
- **Sentinel `AuthConfig.Unspecified` default** — avoids the nullable type, but
  adds a fake variant to the public sealed API that config authors could name.
- **Remove `Unsecured` entirely** — breaks local development and the
  `agentdebug`-style workflows for no security gain over explicit opt-in.

## Decision Outcome

Nullable field + startup validator:

1. **`Server.apiAuth: AuthConfig? = null`.** Deserialization distinguishes an
   omitted field (`null`) from an explicit `apiAuth: !<unsecured>`
   (`AuthConfig.Unsecured`).
2. **`validateExplicitApiAuth` fails startup on `null`**, ~~first~~ second
   among the validators in `configureServer` *(amended 2026-10-09: it runs
   after [ADR-0037](0037-grants-authorization.md)'s `validateNoLegacyEndpoints`)*,
   with a message that shows both remedies
   (configure a scheme, or write the explicit `!<unsecured>` opt-in).
3. **Explicit `Unsecured` is unchanged**: it runs and keeps the startup WARN
   banner.
4. **Schema contract**: `server` and `server.apiAuth` are `required` in
   `homer-config.schema.json`, so editors flag the omission before deploy.
5. `flattenAuthSchemes(null)` returns no schemes — reachable only in tests,
   since the validator aborts boot before any scheme is consulted.

## Consequences

- Good: a forgotten or mistyped `apiAuth` block is a hard startup error with a
  copy-pasteable fix, not a silently open API.
- Good: existing configs that set `apiAuth` (any scheme, including explicit
  `unsecured`) are byte-for-byte unaffected.
- Bad: **breaking** for configs that relied on the implicit default — they must
  add `apiAuth: !<unsecured>` (or a real scheme) to boot. This is the point of
  the change; the migration is one line and named in the error message.
- Neutral: minimal configs now always contain a `server` block, which the
  schema now requires.
