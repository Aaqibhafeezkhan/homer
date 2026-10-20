---
status: accepted
date: 2026-06-22
deciders: Lukáš Vlk
---
# ADR-0028: Cross-origin cookie auth via configurable `SameSite` (CSRF stays closed)

## Context and Problem Statement

A single Homer console deployment now needs to talk to **several** Homer
instances and switch the active one (see the multi-instance console work).
Because the console is a browser SPA, a console served from one origin
(e.g. `https://console.example.com`) talking to an instance on a different
origin (e.g. `https://homer-a.example.com`) is making **cross-origin**
requests.

Cookie/session auth (ADR-0011) issues an `HttpOnly` session cookie. Homer
hardcoded that cookie as `SameSite=Lax`. A `SameSite=Lax` cookie is **not**
attached to a cross-site `fetch` from the console origin to a different
backend origin, so cookie auth silently fails cross-origin — the request goes
out with no credential and the backend answers 401.

The fix is to issue the cookie as `SameSite=None`. But `SameSite=None`
attaches the cookie to **all** cross-site requests, which historically was the
CSRF concern. The question this ADR settles: does moving off `Lax` reopen the
CSRF surface that [ADR-0016](0016-cookie-auth-csrf-protection.md) closed?

## Decision Drivers

- Let one console origin authenticate against instances on other origins via
  cookie auth.
- Do not reopen CSRF (kill, agent-execute, SSE drive-by).
- Backward compatible: existing single-origin deployments keep working with no
  config change.
- Minimal, surgical mechanism (this is a config field, not a new auth scheme).

## Considered Options

- **Configurable `cookieSameSite` (default `Lax`), default-off `None`** —
  add a `cookieSameSite` field to `AuthConfig.Password`; cross-origin
  deployments opt into `None`.
- **Always `SameSite=None`** — rejected: changes behavior for every existing
  deployment and needlessly weakens same-origin installs.
- **Token (bearer) auth only for cross-origin** — rejected: the user wants
  cookie auth supported cross-origin; bearer is already available and is not a
  substitute for the HttpOnly-cookie threat model.

## Decision Outcome

Chosen option: **a configurable `cookieSameSite` on `AuthConfig.Password`,
defaulting to `Lax`.**

1. **New field `cookieSameSite: SameSiteMode` (`lax` | `none` | `strict`),
   default `lax`.** `SessionAuth.installPasswordSessions` writes the configured
   value to the cookie's `SameSite` attribute instead of the hardcoded `Lax`.
   A missing field deserializes to `Lax`, so existing configs are byte-for-byte
   unchanged in behavior.
2. **`None` requires `Secure`.** An `init {}` guard rejects
   `cookieSameSite=NONE` with `cookieSecure=false` — browsers drop a
   `SameSite=None` cookie that is not `Secure`, so the combination is always a
   misconfiguration. This is enforced at config-construction time (startup
   fail-fast), and can never trip an existing config (default is `Lax`).
3. **CORS is unchanged.** `configureCors` already pins origins and enables
   `allowCredentials` only for pinned origins, and `validateCorsForCookieAuth`
   already forces pinned origins under any `Password` scheme (ADR-0016). A
   cross-origin console deployment simply lists the **same** console origin in
   every instance's `corsAllowedOrigins`.

### Why CSRF stays closed

The CSRF defense established in ADR-0016 is **not** `SameSite`. It is two
mechanisms that are independent of the cookie's `SameSite` value:

- **Pinned credentialed CORS.** A cross-site `fetch` from a non-allowlisted
  site cannot read a credentialed response — the browser blocks it because the
  origin is not in `corsAllowedOrigins`. `SameSite=None` lets the cookie ride
  the request, but the attacker still cannot read the response or pass the
  preflight.
- **The `session:`-scoped `X-Homer-CSRF` custom header.** Session-authenticated
  requests must carry a non-safelisted custom header. A cross-site
  `<img>`/navigation cannot set a custom header at all, and a cross-site
  `fetch` that tries to set one triggers a CORS preflight that a non-allowlisted
  origin fails. This guard fires regardless of `SameSite`.

`SameSite=Lax` was therefore defense-in-depth, not the load-bearing control.
Moving to `None` removes that extra layer for cross-origin deployments but
leaves the actual CSRF guard intact. The drive-by chain ADR-0016 closed (CORS
reflector + credentialed GET side effects) stays closed.

### Consequences

- Good: a single console can authenticate cross-origin against multiple
  instances using cookie auth.
- Good: strictly opt-in; default `Lax` preserves all existing single-origin
  behavior, and the `None ⇒ Secure` guard cannot trip an existing config.
- Neutral: cross-origin cookie deployments must run HTTPS and pin the console
  origin in CORS — already required for credentialed CORS under ADR-0016.
- Bad: a cross-origin cookie deployment loses the `SameSite=Lax`
  defense-in-depth layer; correctness now rests entirely on the CORS pin and
  the custom-header guard. Both are already mandatory for any cookie-auth
  deployment, so the net new risk is the loss of one redundant layer.

## More Information

- [ADR-0011](0011-cookie-based-console-auth.md) — cookie-based console auth.
- [ADR-0016](0016-cookie-auth-csrf-protection.md) — pinned CORS + scoped CSRF
  header (the load-bearing CSRF defense this ADR relies on).
