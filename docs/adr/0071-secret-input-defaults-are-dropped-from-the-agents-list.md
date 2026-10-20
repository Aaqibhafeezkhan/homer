---
status: accepted
date: 2026-09-30
deciders: Lukáš Vlk
---
# ADR-0071: Secret input defaults and allowed values are dropped from the API, not masked

## Context and Problem Statement

`GET /api/v1/agents` serves every agent's declared inputs to any console
user holding any grant on that agent — there is no per-input
authorization. The MCP tool schema for an exposed agent is served the
same way to any caller holding a run grant. The agent schema lets an
author declare an input `secret: true` and also give it a `default` (a
fallback credential the run itself needs) and `values`. Before this
decision both surfaces served those fields verbatim; the console's Run
dialog only hid them at render time with `!param.secret`, a client-side
check any HTTP caller skips. A run-time value outside a secret input's
`values` was also rejected with a 400 that listed the whole allowed set.

## Decision Drivers

- Any user holding any grant reads the agent summary, so the API — not
  the console — is the disclosure point (ADR-0047 §12 rule 7: secrets
  travel by reference, never by manifest).
- The run still needs the real default and the allow-list, so the agent
  definition and the run path (ADR-0052) keep them; only what is served
  narrows.
- Only `default` and `values` carry actual values of the credential.
  `pattern`, `min`/`max` and `minLength`/`maxLength` describe its shape,
  and a caller needs them to submit a valid value.

## Considered Options

- (A) Drop `default` and `values` from what is served for a secret input,
  and keep the 400 for a value outside `values` generic.
- (B) Mask them (serve a fixed placeholder such as `"•••"`) and let the
  console decide not to render it.
- (C) Keep serving both and rely on the console's render-time
  `!param.secret` gate.

## Decision Outcome

Chosen option: **"(A) drop, don't mask"**, because absence is the only
representation the console already treats as "don't render"
(`default != null && !secret`), and because the console is not the trust
boundary — any HTTP caller with any grant reads the same endpoints.

Only `default` and `values` are dropped. `pattern`, `min`/`max` and
`minLength`/`maxLength` describe a secret's shape, not the credential, so
they stay served, and a secret value that fails `pattern` still gets a
400 naming the pattern. No error message repeats the secret value itself:
a range denial states the bound without the "got …" part, and a load
error for an invalid secret `default` or `values` entry omits the entry.

Redaction is keyed on `secret: true` and applied when the summary, the
tool schema or the 400 is built, not stored anywhere. So an author who
forgets the flag is not protected, but a flag added later protects the
fields from the next agent reload on, with nothing to migrate.

### Consequences

- Good: no client is trusted to keep the secret; the API never serves a
  flagged input's default or allowed values.
- Good: a late `secret: true` flag covers the fields retroactively.
- Good: absence needs no new sentinel value and no console change —
  the dialog's existing "absent means don't render" contract just works.
- Bad: the protection still depends on the author setting the flag; an
  unflagged credential default is served like any other default.
- Bad: an author who declared `values` on a secret input to document the
  allowed set finds it gone from the summary and the tool schema; the
  agent definition remains the place that set is written down. The run
  path still enforces the allow-list, but a value outside it gets only
  "is not one of the allowed values".
- Rejected (B): a mask is served data — the credential's presence still
  leaves the API, and the console must remember to not render it, which
  reintroduces the client gate as the boundary.
- Rejected (C): it trusts the client-side check any HTTP caller skips;
  the disclosure point is the API, so the guard must sit in the API.

## More Information

Enforced at three points, each with a denial-path test:

- `AgentInfo.from` — the `GET /api/v1/agents` summary, for both `inputs`
  and the legacy `queryParams` alias.
- `McpToolFactory` — the MCP tool schema's `default` and `enum`.
- `ParamValidator` — the generic 400 for a secret value outside `values`;
  no other denial, at run time or load time, repeats a secret's value.

ADR-0052 is the run-path counterpart (redaction metadata captured with
the run); ADR-0047 is the threat model this boundary answers to.
