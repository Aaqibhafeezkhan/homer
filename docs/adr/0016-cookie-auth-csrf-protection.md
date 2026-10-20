---
status: accepted
date: 2026-06-09
deciders: Lukáš Vlk
---
# ADR-0016: CSRF protection for cookie/console auth — pinned CORS + scoped custom header

## Context and Problem Statement

The 2026-06-08 security review found a realistic drive-by chain against
cookie/session-auth deployments (the console mode introduced in
[ADR-0011](0011-cookie-based-console-auth.md)). Two findings compose:

- **CORS credential reflector (H3):** `configureCors` paired `anyHost()` with an
  unconditional `allowCredentials = true`. With the default empty
  `corsAllowedOrigins`, Ktor reflected any request `Origin` with
  `Access-Control-Allow-Credentials: true`, so any website could read the full
  API cross-origin with the victim's session cookie.
- **Credentialed GET side effects (H4):** state-changing actions were reachable
  over GET (`GET /kill/{id}`, `GET /agent/{name}` execution, SSE GETs). The
  session cookie is `SameSite=Lax`, which still attaches to top-level GET
  navigations, so a drive-by `<img src=".../kill/42">` or a navigation to
  `/agent/expensive` fires with the victim's cookie — process kill or
  Claude-spend DoS, no XSS required.

The credential that makes both exploitable is the **browser-ambient session
cookie**. Bearer tokens (held in `localStorage`, sent via an explicit
`Authorization` header) and HMAC signatures are not auto-attached by the browser
and are therefore not CSRF-able. The authenticated principal name already encodes
the scheme: `session:<user>`, `hmac:<keyId>`, or the bare bearer token name.

## Decision Drivers

- Close the cookie-auth CSRF surface (kill, agent-execute, SSE) without XSS.
- Keep the documented GET webhook/curl trigger working for token/HMAC callers —
  many `README.md` / `AGENT.md` examples rely on it.
- Single error-handling path ([ADR-0002](0002-unified-error-model-at-http-boundary.md)) — no inline `respond(403)`.
- Minimal, low-breakage mechanism.

## Considered Options

- **Pin CORS for credentials + scoped custom header `X-Homer-CSRF`** — refuse
  credentialed CORS without explicit origins; require a custom request header on
  `session:`-authenticated requests only.
- **Remove GET execution / move everything to POST** — rejected: breaks the
  documented GET webhook trigger that token/HMAC callers depend on.
- **Origin/Referer allowlist check** — rejected: brittle around top-level
  navigations and `<img>` loads (no/inconsistent `Origin`), and duplicates what
  pinned credentialed CORS already enforces.

## Decision Outcome

Chosen option: **pinned CORS for credentials + a session-scoped custom header**.

1. **Credentialed CORS requires pinned origins.** `configureCors` sets
   `allowCredentials = corsAllowedOrigins.isNotEmpty()`. An empty list keeps
   `anyHost()` for local bearer/unsecured dev but without credential reflection
   (Ktor answers `*`, which can never carry cookies). A `Password` auth scheme
   with an empty `corsAllowedOrigins` now **fails startup** with
   `ConfigurationException` instead of shipping a reflector.
2. **Session-scoped CSRF header.** A route-scoped `CsrfProtection` plugin,
   installed inside the `authenticate` block, runs on the `AuthenticationChecked`
   hook (after the principal is populated). If the principal name starts with
   `session:`, it requires a non-blank `X-Homer-CSRF` request header; otherwise it
   is a no-op. A missing header throws `CsrfValidationException` → 403 via
   StatusPages. A cross-site `<img>`/navigation cannot set a custom header, and a
   cross-site `fetch` cannot ride the cookie cross-origin once credentialed CORS
   is pinned. The console sends the header on every REST call and SSE stream.
3. **Kill moves to `DELETE`.** `GET /kill/{id}` becomes `DELETE /kill/{id}` —
   correct REST and defense in depth, even though the session guard already blocks
   GET-kill from a session principal.

Guarding *all* session requests (not just unsafe methods) is deliberate: the
console — the only legitimate session client — sends the header on every call, so
the simplest correct rule has zero legitimate breakage and also covers the
GET-execute and SSE-GET paths without per-path logic.

### Consequences

- Good: the cookie-auth drive-by chain (CORS reflector + credentialed GET side
  effects) is closed.
- Good: token/HMAC webhook callers are unaffected — they keep GET triggers and
  need no header.
- Bad: cookie-auth deployments **must** set `corsAllowedOrigins`, or startup
  aborts (intentional fail-fast).
- Bad: any future first-party cookie-auth client must send `X-Homer-CSRF`.

## More Information

- [ADR-0011](0011-cookie-based-console-auth.md) — cookie-based console auth.
- [ADR-0002](0002-unified-error-model-at-http-boundary.md) — single HTTP error handler.
- Internal security review, 2026-06-08 — findings H3 and H4.
