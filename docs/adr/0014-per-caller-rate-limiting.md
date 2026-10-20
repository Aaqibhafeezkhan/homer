---
status: accepted
date: 2026-06-05
deciders: Lukáš Vlk
---
# ADR-0014: Per-caller rate limiting on the secured API

## Context and Problem Statement

Homer installs a single `global {}` rate limiter whose `requestKey`
defaults to a constant, so the configured `rateLimit` (1000/60s) is
**one shared bucket for every request to the server** — authenticated
or not, path notwithstanding (the historical reading of "one bucket per
endpoint" was wrong; Ktor's default key is constant, so all calls share
one limiter). A single noisy token (or IP) can drain that bucket and
starve every other caller — the classic noisy-neighbour problem. Audit
item #37 (2026-05-06 security/ops audit, P3) asks for token-bucket rate
limiting *per caller identity*. We need each token/key/user to get its
own fair share while keeping a server-wide ceiling.

## Decision Drivers

- Fairness: one caller must not be able to exhaust the budget of all
  others.
- Reuse the identity Homer already computes — no new auth plumbing.
- Keep the existing aggregate ceiling as a backstop against total load.
- Consistency with the existing always-on `rateLimit` posture.
- Simplicity (§2): no configuration surface beyond what the problem
  needs.

## Considered Options

- **A — Replace the global limiter with a per-caller one.** Single
  limiter keyed on identity.
- **B — Two-layer: keep global ceiling, add a separate per-caller
  limiter on the secured routes.**
- **C — Per-token override limits.** Each token carries its own limit
  in config.

## Decision Outcome

Chosen option: **"B — two-layer"**, with a uniform, on-by-default
per-caller limit.

A named `perCaller` limiter (default 300/60s) wraps the secured
`/api/v1` routes; the existing `global {}` limiter stays as the
server-wide ceiling. Ktor 3.4.1 **stacks** per-call limiters
(`RateLimitInterceptors` concatenates the limiter list rather than
replacing it), so secured routes are subject to *both* — option A would
have lost the aggregate ceiling.

The bucket key is the stable, non-secret identity every auth scheme
already sets via `UserIdPrincipal` (ADR-0010 / ADR-0011):

- Bearer → `UserIdPrincipal(tokenName)` (the config-map key).
- HMAC → `UserIdPrincipal("hmac:${keyId}")`.
- Password/session → `UserIdPrincipal("session:${username}")`.

So the key is simply
`call.principal<UserIdPrincipal>()?.name ?: call.request.local.remoteHost`.
The `local.remoteHost` fallback (direct TCP peer, **no**
`X-Forwarded-For` trust — same stance as the login limiter, ADR-0011)
applies only in `AuthConfig.Unsecured` mode where there is no principal.

**Correctness constraint.** The rate-limit interceptor runs on a
`BeforeCall` phase, so `requestKey` must observe the principal *after*
authentication populates it. This requires nesting `rateLimit` **inside**
`authenticate` (not wrapping it). `PerCallerRateLimitTest`'s per-token
isolation case is the regression guard: if the principal were null at
key time, both tokens would collapse onto the shared IP bucket and the
test would fail.

The limiter is **on by default** with a generous limit, matching the
always-on `rateLimit` field — there is no separate enable flag; raise
`limit` to effectively disable it. The limit is **uniform** for all
callers; per-token overrides (option C) were rejected as speculative
(§2) and can be added later without changing this model.

### Consequences

- Good: a single abusive caller can no longer starve others; each
  identity has its own bucket.
- Good: zero new auth plumbing — reuses `UserIdPrincipal` already set by
  every scheme.
- Good: the global ceiling still bounds total load.
- Bad: in unsecured mode behind a reverse proxy (or any single-source-IP
  deployment), all callers key on the proxy's IP (no `X-Forwarded-For` trust
  yet) and collapse onto one bucket. Since that bucket is keyed on identity
  only — not path — the per-caller `limit` (300) becomes the binding aggregate
  limit *across all endpoints* and is stricter than the global `rateLimit`
  (1000), effectively lowering the ceiling from 1000 to 300 for such
  deployments. Acceptable for the default secured posture; operators of
  proxied unsecured deployments should raise the limit or run with auth.
  Documented in SERVER.md.
- Bad: a uniform limit can't give a high-volume integration a larger
  share without raising it for everyone; deferred to a future override
  mechanism if a real need appears.

## Pros and Cons of the Options

### Option A — Replace global with per-caller

- Good: one limiter, simplest mental model.
- Bad: loses the aggregate ceiling — many distinct callers could
  collectively overwhelm the server.

### Option B — Two-layer (chosen)

- Good: fairness *and* an aggregate ceiling.
- Neutral: relies on Ktor stacking per-call limiters (verified in
  source).
- Bad: two limiters to reason about.

### Option C — Per-token override limits

- Good: lets specific integrations get a larger share.
- Bad: configuration surface and validation with no demonstrated need
  (§2 / YAGNI).

## More Information

- Identity lineage: [ADR-0010](0010-auth-schemes.md) (auth schemes and
  the `UserIdPrincipal` identities) and
  [ADR-0011](0011-cookie-based-console-auth.md) (session identity +
  the `local.remoteHost` / no-`X-Forwarded-For` proxy-trust stance,
  reused here for the unsecured-mode fallback).
- Closes audit item #37 (P3).

## Amendment 2026-09-24: probe exemption from the global bucket

The `global` limiter's `requestKey` now maps the Kubernetes probe
endpoints `GET /api/v1/livez` and `GET /api/v1/readyz` to a constant
`probe` key served by `RateLimiter.Unlimited`; every other path keeps
the shared `global` bucket. Two constants are the whole key space, so
the per-key limiter cache stays bounded (§12 rule 4).

Why the exemption outranks the uniform ceiling: the global bucket is
unauthenticated — reachable by any client that can reach the port, and
consumed by every request before routing and auth, including 401s and
404s. A drained bucket turned `/livez` into a `429`, which the kubelet
reads as a liveness failure and answers by restarting the pod, killing
every in-flight run. Availability is in scope even unauthenticated
(§12, [ADR-0047](0047-security-by-design-and-threat-model.md)); a
liveness probe must never be rate-limited.

Rejected alternative: scoping the global limiter to the secured routes
so the probes never see it. Rejected because the global limiter is an
application-level interceptor that runs before routing — a route-side
scope does not reliably bound it — and because the ceiling should keep
bounding unauthenticated non-probe traffic (`/auth/login`, callbacks,
401/404 sweeps), which is exactly the traffic an unauthenticated flood
uses to drain the bucket.

The residual risk this amendment recorded (the runner long polls and
`POST /api/v1/auth/login` still riding the global bucket) is closed by the
2026-09-30 amendment below.

The probes are exempt from the `perCaller` limiter only by mount
position: the probe routes live outside the `rateLimit(perCaller)`
wrapper, and nothing in the limiter config itself exempts them.
`GlobalRateLimitProbeExemptionTest` guards both buckets' drained state
against the probes. The full operator-facing contract (503 during
drain, never `429`) is in `app/SERVER.md`, "Liveness & readiness
probes".

## Amendment 2026-09-30: runner protocol and login leave the global bucket

The rule: the `global` limiter's key function (`globalRateLimitKey`) sends
three kinds of request somewhere other than the shared `global` bucket, and
everything else stays on it:

- the probes, as before (`probe`, `Unlimited`);
- `POST /api/v1/auth/login`, only when a `password` scheme mounts that route,
  which moves to one aggregate login bucket (`login`, a constant key, so one
  bucket for every peer), on top of its per-peer `login` bucket;
- a request under `/api/v1/runner/` whose domain token verifies, which moves
  to that domain's own bucket (`RunnerDomainLimitKey(alias)`).

The key space is those three constants plus one key per configured domain, so
the per-key limiter cache stays bounded (§12 rule 4). The runner key carries
the alias, never the token: Ktor logs the request key at trace level.

**Trust boundary.** A domain-token holder is a separate principal class
([ADR-0066](0066-external-pull-runners-and-unified-claim-queue.md), §12); the
exemption is a privilege of that principal and crosses no other. The token is
verified with `matchRunnerDomain`, the constant-time match the runner routes
and the runner body limit already use, not a cheaper check. A missing or
invalid token keeps the `global` key: it draws from the same bucket and gets
the same `429` as before, so it gains nothing and observes nothing new. A valid
token exempts runner-protocol paths only; on an operator route it is still an
unauthenticated request in the global bucket. The login move is conditional
on the route being mounted: with no route the path is a `404` like any other
and stays in the global bucket.

**Login keeps an aggregate ceiling.** The per-peer `login` bucket alone is not a
bound on the total: a caller with many source addresses (an IPv6 prefix) would
multiply both the attempts and the per-key limiter cache (§12 rule 4). The
aggregate login bucket is used by nothing but `POST /api/v1/auth/login` and is
the size of the global bucket (`rateLimit`), the ceiling login already lived
under, so a login flood can cost no more bcrypt verifies than before while other
routes can no longer spend login's share.

**Per-domain bucket size.** `maxInstances × maxConcurrencyPerInstance ×
1000` requests per 60 s, saturating at `Int.MAX_VALUE`; a code constant, since
the rate-limit config has no per-domain slot and the domain already declares
its capacity. One claim slot (an instance at one unit of `concurrency`) polls
2 times a minute while idle: its single long poll — `claim`, or `next` while it
holds a session — is held the full `POLL_SEC` (30 s) and re-issued at once. A
busy slot adds one `next` per op, at most one `tar` request per op, and output
chunks, which `homer-runner` posts one at a time per stream; output is the only
term the protocol does not bound. 1000 per
slot is 500 times the idle cadence, and equal to the whole default global
bucket every runner shared with every other caller before this change, so no
domain, however small, has less room than it had. At the defaults (32
instances, 16 each) the bucket is 512 000 per minute; a domain of one instance
at concurrency 1 gets 1000. Sizing it generously is deliberate: `homer-runner`
retries a `429`'d output chunk five times, one second apart, and then drops it,
so a healthy fleet must never reach the bound.

Rejected alternative: a route-scoped `rateLimit(RateLimitName("runner"))`
around `runnerProtocolRoutes`. The global interceptor runs before routing
regardless, so it would still have to verify the token to exempt the request,
and the route limiter would verify it a second time and need a key of its own
for unverified requests. One key function makes the one decision.

Residual risk, accepted: a flood aimed at login itself drains the aggregate
login bucket and locks every operator out of login until it refills. That
lockout is confined to login; the rest of the API, run pickup and existing
sessions are unaffected, and the attacker gets no more guesses than the
aggregate allows.

`GlobalRateLimitRunnerLoginExemptionTest` guards the denial paths: with the
global bucket drained, a valid-token runner still registers, claims, polls and
is not expired by the heartbeat sweep, while a missing or invalid token still
gets `429`; a valid-token flood gets `429` from its domain's bucket without
touching the global bucket or another domain; login works with the global
bucket drained and is still bounded by the per-peer `login` bucket; a login
flood that no per-peer limit catches gets `429` from the aggregate login bucket
without touching the global one; without a `password` scheme the login path
keeps the global bucket.
