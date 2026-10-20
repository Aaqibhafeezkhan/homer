---
status: accepted
date: 2026-10-02
deciders: Lukáš Vlk
---
# ADR-0072: The bodyless relaxation is POST-only — GET execution stays requiring a body

## Context and Problem Statement

An agent whose only body input is optional forced a JSON body on every run, so
the console's Run Agent dialog and its Export Curl command — which send no body
when every body input is blank — were refused with `400 Expected Content-Type`.
`AgentInputResolver.resolve` now treats an empty (or whitespace-only) request
body as `{}` for an agent without an `inputSchema`, so optional body inputs
fall to their default and a missing required one is still reported by name.

That relaxation raised an adjacent question: a `GET` on the deprecated execution
path from an agent whose only input is an optional `src: body` param also needs
no body at all, yet it is still rejected with `requires request body; use POST`
— a bodyless `GET` would resolve exactly like the now-accepted bodyless `POST`.
The asymmetry is deliberate, but it was recorded nowhere.

## Decision Drivers

- GET execution is deprecated and is the CSRF-relevant method for
  cookie-authenticated callers (see [ADR-0016](0016-cookie-auth-csrf-protection.md)):
  a `SameSite=Lax` session cookie attaches to top-level GET navigations, so
  making GET easier to fire side-effectful runs through widens the exact surface
  ADR-0016 closed.
- The console — the client this fix targets — sends POST already; no caller that
  benefits from the bodyless relaxation needs GET.

## Considered Options

- Relax GET too (a bodyless GET resolves like a bodyless POST) — consistent, but
  it lowers the bar on the deprecated, CSRF-relevant path at the same time.
- Keep GET tight, require POST for any run with body inputs — chosen.

## Decision Outcome

Chosen option: **"Keep GET tight"**. The bodyless relaxation applies to `POST`
only; `GET` on an agent with body inputs keeps failing with `requires request
body; use POST`, even when every body input is optional and a bodyless request
would otherwise resolve fine. The rejected alternative — allowing a bodyless
`GET` the same way — was rejected because GET execution is deprecated and is
the method [ADR-0016](0016-cookie-auth-csrf-protection.md) treats as the
drive-by risk; a 400 that names the migration path (`use POST`) is the nudge
the deprecation already wants.

### Consequences

- Good: the deprecated GET path keeps its friction; no new drive-by surface.
- Good: callers get a precise error message pointing at POST.
- Bad: a webhook caller on the documented deprecated GET path with an
  optional-only body input must switch to POST (or send a body) — accepted,
  since that is the direction the deprecation is heading anyway.

## More Information

- [ADR-0016](0016-cookie-auth-csrf-protection.md) — CSRF protection for
  cookie/console auth; the source of the GET-side-effect concern.
