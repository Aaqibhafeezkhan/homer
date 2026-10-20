---
status: accepted
date: 2026-05-18
deciders: Lukáš Vlk
---
# ADR-0011: Cookie-based console authentication (username + password)

## Context and Problem Statement

Until now the Homer console authenticated by asking the user to paste
a long-lived bearer token into a single-input login page. The token
was held in `localStorage` and replayed by every fetch / SSE request.
Audit P0 #6's short-term phase added forced clearing on a 401, but
deferred the real fix — replace the localStorage token with a real
login flow backed by an `HttpOnly` session cookie — under "needs ADR".
P1 #19 (open redirect race: login sets the token then routes to
`/processes` before the new auth state is reflected in React Query
caches) is a symptom of the same design and is closed by the same
change.

Operators also wanted **multiple console users** with the same
per-user endpoint allowlist that Bearer tokens already carry, so that
read-only operators and admins can share a single deployment.

CLI / MCP / webhook callers must keep working unchanged. The new
scheme must plug into the existing `AuthConfig.Combined` machinery
([ADR-0010](0010-auth-schemes.md)) so a single deployment can serve
cookie-auth to browsers and bearer/HMAC to scripts and webhooks at
the same time.

## Decision Drivers

- Browser JS must not be able to read the credential — rules out
  any localStorage-based design (XSS replays the token verbatim).
- Closing P0 #6 and P1 #19 in one change, without touching the
  existing `bearer` / `hmac` flows.
- Multi-user with the existing `EndpointMatcher` allowlist — no new
  per-user permission model.
- Single-node deployment invariant ([memory](../../README.md)) — no
  distributed session stores, no Redis.
- Schema / model / doc drift is a known pain point (root §10) — keep
  `AuthConfig.Password` shaped like the existing variants so the
  exhaustive `when` blocks catch drift at build time.
- Operator UX: hashes go into `homer-config.yaml`; we must not invite
  pasted plaintext passwords. A first-class CLI subcommand
  (`agentdebug hash-password`) is the affordance.

## Considered Options

- **A.** Signed (HMAC-only, not encrypted) `HttpOnly` cookie with a
  JSON payload of `{username, exp, ver}`. Bcrypt-hashed passwords in
  the config. Multi-key MAC for zero-downtime rotation. Rate-limited
  login route at 5/min per remote IP. Constant-time-ish unknown-user
  path via a baked-in dummy bcrypt hash. Mounted alongside
  `bearer`/`hmac` via the existing `Combined` wrapper.
- **B.** Encrypted (AES-GCM) cookie containing the full session state.
- **C.** Server-side session store (in-memory map of session-id → user)
  with a random opaque cookie.
- **D.** OIDC / external identity provider.

## Decision Outcome

Chosen option: **A**, because:

- HMAC-signed cookies are the minimum needed property: integrity +
  authenticity. The only data in the payload is `{username, exp}`,
  which is **not** a secret — the holder already knows their own
  username and TTL. Encryption (Option B) adds a key-management
  burden with no actual data to protect.
- A server-side store (Option C) would break the single-node
  invariant the moment we ship a second replica, and gains nothing
  for the cookie payload we actually have. Same problem as B in
  reverse: solving a problem we don't have.
- OIDC (Option D) is the right answer for a multi-tenant SaaS, but
  Homer is operator-deployed for one team at a time. Pinning a
  password-only flow now does not preclude an OIDC-backed flow
  later as a new `AuthConfig` variant.
- `Combined` already exists from [ADR-0010](0010-auth-schemes.md);
  `Password` joins as a third variant with the same `EndpointMatcher`
  allowlist, so cookie-auth and bearer/HMAC coexist on the same
  deployment without code-path special-casing. *(Amended 2026-10-09:
  per-user `endpoints` and `EndpointMatcher` were removed for every principal
  by [ADR-0037](0037-grants-authorization.md); grants replace them.)*

### Critical invariants the implementation must preserve

These came out of design review and are tested explicitly. Future
edits to `Application.kt` / `SessionAuth.kt` must keep them:

1. **`Sessions` plugin installed before `Authentication`.** Ktor
   enforces this at plugin-install time; the wiring depends on it.
2. **`SameSite=Lax`, not `Strict`.** With CORS pinned to explicit
   origins and JSON-only on mutating endpoints, Lax is materially as
   safe as Strict and avoids the deep-link bounce. The JSON-only
   requirement is the real boundary. *(Amended 2026-10-09: `SameSite` is
   configurable since [ADR-0028](0028-cross-origin-cookie-auth-samesite.md)
   (`cookieSameSite`, default `Lax`).)*
3. **HMAC, not encryption.** The cookie's JSON payload is plaintext.
   Anyone who can read the cookie can read `username` / `exp` / `ver`.
   `ver: Int = 1` future-proofs format migrations — bump it and
   reject old payloads on sight.
4. **Two-secret rotation, multi-key on read.** `sessionSecret`
   (required) + `sessionSecretPrevious` (optional). Ktor's built-in
   `transform(...)` list **chains** transformers (sign with A then
   with B), not multi-key fallback. The code uses a custom
   `MultiKeyMacTransformer` that tries each key in order on read and
   only signs with `current` on write. Rotation flow is:
   set previous = old / current = new, deploy, wait `sessionTtlMin`,
   drop previous.
5. **Constant-time-ish user-not-found.** Always run bcrypt verify,
   even when the username doesn't exist — against a dummy hash
   produced via `PasswordVerifier.dummyHashAtCost(scheme.bcryptCost)`
   — so a 401 takes the same wall-clock time as wrong-password.
   The dummy must be bound to the configured cost: a fixed cost-12
   constant would reintroduce the oracle whenever the operator
   deviates from 12 (cost-4 real verify ≈ 5 ms, cost-12 dummy ≈
   250 ms = 50× asymmetry). The dummy is memoized per cost in
   `PasswordVerifier` and pre-warmed at server startup so the very
   first unknown-user login is no slower than subsequent ones.
6. **Per-IP rate limit on `/auth/login`.** 5/min, keyed by
   `call.request.local.remoteHost`. The existing `global` limiter is
   too coarse to defend against a 1-RPS guesser.
7. **Auth routes outside `authenticate(...)`.** `/login` is by
   definition unauthenticated. `/logout` must be idempotent (a stale
   cookie + valid bearer should still clear the cookie, not 401).
   `/me` reads the session directly so it isn't mis-authenticated as
   the bearer principal by `FirstSuccessful`.
8. **`provider("auth-session")`, not `session<T>()`.** Ktor 3.4's
   built-in `session<T>()` provider does not give access to
   `call.request.path()` for the per-user endpoint check. The
   custom provider mirrors the Bearer block's shape. *(Amended
   2026-10-09: the per-user endpoint check is gone, see ADR-0037.)*

### Consequences

- Good: P0 #6 (long-term) and P1 #19 close in one change. Browser JS
  no longer holds the credential.
- Good: Multi-user with per-user endpoint allowlists reuses
  `EndpointMatcher` — no new permission model. *(Superseded by ADR-0037:
  grants replace endpoint allowlists.)*
- Good: Coexistence with `bearer` / `hmac` via `Combined` — CLI and
  webhook clients keep working unchanged on the same deployment.
- Good: Operators paste bcrypt hashes, not plaintext, courtesy of
  `agentdebug hash-password`.
- Bad: `AuthConfig` sealed-class grows again. Mitigated by
  compiler-enforced exhaustiveness — the build fails if a `when`
  branch is forgotten.
- Bad: `Combined` invariant set grows (`at most one Password`)
  alongside the existing `at most one Bearer / one HMAC`. Enforced
  at `init` time; `AppConfigSerializerTest` covers the path.
- Bad: HTTP-only deployments behind a TLS-terminating reverse proxy
  must opt out of `cookieSecure` explicitly. Default is `true`. The
  README and SERVER.md call this out.
- Bad: The `MultiKeyMacTransformer` is a one-off — it exists because
  Ktor's built-in transformers chain rather than fall back. If Ktor
  ever ships native multi-key MAC, this class becomes deletable.

## Pros and Cons of the Options

### Option A — Signed `HttpOnly` cookie (chosen)

- Good: Smallest change that gets browser JS out of the credential
  path. Minimum cryptography — HMAC-SHA256.
- Good: Reuses existing `EndpointMatcher`, `Combined`, `SysEnvResolver`
  (`@SYSENV:` plumbing for the secret), and `register(RateLimitName(...))`
  paths.
- Neutral: Requires operators to mint hashes — `agentdebug
  hash-password` makes this a single command.
- Bad: Cookie payload is plaintext (signed only). Acceptable because
  the payload contains no secret data.

### Option B — Encrypted cookie

- Good: Payload is opaque to the holder.
- Bad: Adds a key-management surface for no actual data to protect.
- Bad: AES-GCM in a Java/Kotlin stack pulls in nonce-management
  complexity for a problem we don't have.

### Option C — Server-side session store

- Good: Revocation is trivial (`store.remove(id)`).
- Bad: First step away from the single-node invariant. Solving for
  revocation we can already approximate by dropping the user from
  `homer-config.yaml` (next request 401s via the existing user-lookup
  check in `passwordSessionProvider`).
- Bad: New persistence boundary, new failure mode, new test surface.

### Option D — OIDC / external IdP

- Good: Eventually right for multi-tenant SaaS Homer.
- Bad: Multi-week protocol work for a deployment shape Homer doesn't
  serve today. Can be added later as a new `AuthConfig` variant
  without invalidating this decision.

## More Information

- Closes audit P0 #6 (long-term phase) and P1 #19.
- Companion ADR: [ADR-0010](0010-auth-schemes.md) — Bearer / HMAC /
  Combined / Unsecured. `Password` joins that family.
- Reference: `SERVER.md` § Password Authentication.
- Reference: `app/README.md` § Authentication (Endpoints table).
