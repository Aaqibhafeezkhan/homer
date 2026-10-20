---
status: accepted
date: 2026-05-14
deciders: Lukáš Vlk
---
# ADR-0010: Allowed API auth schemes — Bearer / HMAC / Combined / Unsecured

## Context and Problem Statement

Until now Homer's HTTP API supported only `Bearer` (named long-lived
tokens) and `Unsecured`. External systems that trigger Homer agents —
starting with Vikunja webhooks — sign their payloads with HMAC; passing
a long-lived bearer over every webhook is the wrong tradeoff for that
traffic (rotation cost, log leakage risk, no per-request integrity
guarantee). We need an auth model that can accept HMAC-signed bodies
alongside (or instead of) bearer tokens, without exploding into
per-provider code paths.

## Decision Drivers

- Vikunja is the immediate concrete consumer and uses a simple
  HMAC-SHA256-of-body scheme with header `X-Vikunja-Signature`.
- Other providers (GitHub, Stripe) follow the same conceptual
  shape with small variations (prefix, encoding, timestamp).
- Single-node deployment invariant (memory `single-node-only`) rules
  out persistent nonce stores; replay protection is window-based only.
- Existing Bearer-based configs must continue to deserialize unchanged.
- Schema / model / doc drift is a known pain point (root §10) — keep
  the surface area small enough to maintain in lockstep.

## Considered Options

- **A.** Generic HMAC variant with a single verifier and per-key knobs
  (`signatureHeader`, `algorithm`, `encoding`, `prefix`,
  `payloadTemplate`, `timestampHeader`, `replayToleranceSec`). Vikunja
  is just defaults + one header name. A `Combined` wrapper lets
  operators run Bearer + HMAC side-by-side.
- **B.** Per-provider profiles (`profile: github | stripe | vikunja`)
  hard-coded in the verifier.
- **C.** AWS SigV4-style canonical-request signing (method + canonical
  path + canonical query + body hash + scope string).

## Decision Outcome

Chosen option: **A**, because the per-knob surface covers every
documented inbound webhook scheme we expect to support, keeps the code
path single, and turns Vikunja into a doc example rather than a switch
in the verifier. A `Combined` wrapper allows multiple schemes
side-by-side without breaking single-scheme configs (Bearer-only,
HMAC-only, Unsecured all keep working unchanged).

### Consequences

- Good: One code path to test, audit, and harden. New providers ship as
  config + docs, not code.
- Good: Replay protection scales naturally — `timestampHeader` is
  opt-in, costing zero for callers (Vikunja) who don't supply one.
- Good: Constant-time comparison and `DoubleReceive` (so the auth
  provider and `AgentInputResolver` can both see the raw body) are
  one-time concerns, not per-provider.
- Bad: Operators wanting a Vikunja preset must still know the header
  name (`X-Vikunja-Signature`). Mitigated by the SERVER.md example.
- Bad: `AuthConfig` sealed-class growth makes the `when` blocks in
  `Application.kt` more invasive — but exhaustiveness is enforced by
  the compiler, so drift surfaces at build time.
- Bad: `Combined` invariants (no nested `Combined`, no `Unsecured`
  inside, at most one Bearer and one HMAC) are enforced at `init`
  time, not by the schema. The `AuthConfig Combined …` tests in
  `AppConfigSerializerTest` cover these explicitly.

## Pros and Cons of the Options

### Option A — Generic verifier
- Good: smallest surface that covers every known case.
- Neutral: requires operators to set `prefix` / `encoding` per provider.
- Bad: easy to misconfigure a knob silently (mitigated by
  `HmacKeyConfig.init` invariants — e.g. `{{timestamp}}` in the
  template without `timestampHeader` fails at startup).

### Option B — Per-provider profiles
- Good: zero-config Vikunja / GitHub / Stripe.
- Bad: each profile is its own verifier, its own test matrix, its own
  behavior drift over time. Adding a new provider means a code change,
  not a config change.

### Option C — SigV4-style
- Good: strongest guarantees (method + canonical path + canonical
  query + body hash + scope).
- Bad: drastically more complex; no inbound caller we expect signs
  this way. Would block the immediate Vikunja use case on weeks of
  protocol work nobody actually consumes.

## More Information

- Vikunja webhook spec: https://vikunja.io/docs/webhooks/
- GitHub webhook signing: https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries
- Stripe webhook signing: https://docs.stripe.com/webhooks/signatures
