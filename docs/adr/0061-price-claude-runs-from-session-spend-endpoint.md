---
status: accepted
date: 2026-09-10
deciders: Lukáš Vlk
---
# ADR-0061: Price Claude runs from an external session-spend endpoint, CLI figure as marked fallback

> **Note:** `ClaudeExecutor` and `ClaudeAccountingScan`, named below, are now the
> harness-generic `LlmHarnessExecutor` and `LlmAccountingScan` ([ADR-0070](0070-llm-harness-adapter-boundary.md)).
> Likewise `scanClaudeAccounting` is now `scanLlmAccounting`, and `inspectClaudeOutput`
> is the per-harness `LlmHarness.inspect` (2026-10-09 note).
>
> With `llmPrice.sessionSpend` configured, the timing below is narrowed: a finished attempt
> no longer waits out the re-poll schedule the amendments describe, but returns at once on
> the harness figure, marked `session figure pending`, and the session lookup settles in the
> background ([ADR-0089](0089-settle-llm-cost-in-the-background.md), 2026-10-16 note).

## Context and Problem Statement

Every Claude attempt's cost was `total_cost_usd`, a figure the Claude CLI computes
itself from its built-in Anthropic rate card for the model name it believes it
called. Homer propagated that figure unchanged into the cost ledger behind
`costLimits`, the status API, span attributes, and the Langfuse `cost_details`
override. Behind an LLM proxy (e.g. LiteLLM) serving non-Anthropic models or a
different contract, the figure is not what the deployment is actually billed —
every downstream consumer of cost was reading fiction. Separately, accounting only
ran on exit 0: a non-zero exit or a `timeout` kill charged nothing, even though the
CLI had already made billable requests before dying — unmetered spend that could
walk straight past `costLimits`.

## Decision Drivers

- **Provider-agnostic.** There is no standard session-spend API to target; LiteLLM
  itself only exposes paginated per-request rows (`GET /spend/logs/v2?session_id=…`),
  not a session aggregate. Any solution has to be configuration, not a hardcoded
  client for one product.
- **Availability (§12).** Pricing is metadata, not a reason to fail or slow a run
  beyond a small bound.
- **Attacker-controlled session id (§12 rule 2).** The session id used to look up
  spend is read from a file the agent's own process wrote to its workspace.
- **Secrets by reference (§12 rule 7).** A spend lookup typically carries a bearer
  token; it must never land in a manifest, log line, span, or error text.
- **Budgets must keep working on fallback.** `costLimits` cannot go blind just
  because an external lookup is slow, down, or misconfigured.

## Considered Options

- **Option A — let Langfuse price from its own model table.** Fixes the trace view
  only; the ledger, `costLimits`, and the status API still see the CLI's figure.
- **Option B — a Homer-maintained per-model rate table.** Needs ongoing maintenance
  as models and prices change, and does not reflect a proxy's actual negotiated
  rate. Deferred as a later rung — the resolver interface leaves room for it.
- **Option C — an external session-spend lookup, CLI figure as marked fallback.**
  Chosen.

## Decision Outcome

Chosen option: **"Option C — external session-spend lookup, CLI figure as marked
fallback"**, because it is the only option that fixes the ledger, `costLimits`, the
status API, and telemetry from one source, stays provider-agnostic (driver 1), and
never leaves a run unpriced or blocked (driver 2).

`llmPrice.sessionSpend {url, headers, costPath, timeoutMs}` (all under the new,
optional `AppConfig.llmPrice: LlmPriceConfig?`) configures one `GET` endpoint keyed
by the CLI's session id. Resolution order per attempt is **session spend → CLI
`total_cost_usd`**; whichever rung answers is recorded as the run's cost *source*
(`CostSource.SESSION` / `CostSource.CLI`), so a fallback is never silently
indistinguishable from a correct lookup. `processes.cost_source` (`cli` | `session`
| `mixed`) carries this on the process row; `llmAccounting` (session id, cost,
source, fallback reason) carries it per Claude execution. Omitting `llmPrice`
reproduces today's pricing exactly.

Accounting is also unified: every attempt is now priced and charged through one
path (`ClaudeExecutor.accountAttempt`), regardless of exit code — a lenient,
line-by-line scan of the attempt's stdout (`scanClaudeAccounting`) recovers the
session id from the first event that carries one (the `system/init` event, present
even on a killed run) and `total_cost_usd` from the `result` event when one exists,
then hands both to `LlmCostResolver`. The existing **strict** success-gating
(`inspectClaudeOutput` — full documented event shape, `result` present, subtype
`success`, version pin) is untouched and still runs only on exit 0. The two are
deliberately separate functions: accounting is lenient and always runs, success
gating stays fail-closed.

Two implementation decisions not spelled out in the design and worth recording:

- **`LlmCostResolver`'s `HttpClient` never follows redirects
  (`followRedirects = false`).** A 3xx response degrades the attempt to the CLI
  figure with reason `http <status>` (e.g. `http 302`) rather than being followed.
  Following it would
  issue a further `GET` to a URL supplied by the *response*, still carrying the
  configured `headers` (typically a bearer token) — an attacker-influenced
  redirect target would receive Homer's spend-API credential.
- **The whole request-and-read phase runs inside its own `withTimeout(timeoutMs)`,
  independent of the HTTP engine's own timeout plugin.** `HttpTimeout` cancels the
  *call* job, not necessarily whatever the response body read is suspended on —
  with a real socket that surfaces as a socket timeout, but not every engine has a
  socket to time out (`MockEngine` in tests, conceivably others), so a stalled read
  could otherwise hang past `timeoutMs`. Because this wraps the resolver's own
  coroutine, its `TimeoutCancellationException` must be told apart from a caller's
  own cancellation reaching the same catch block (e.g. a caller wrapping `resolve()`
  in a shorter timeout, or the process being killed mid-lookup): the resolver checks
  whether its enclosing coroutine context is still active — active means its own
  timeout fired and the lookup degrades to `CLI`/`timeout`; not active means a real
  cancellation is in flight and must propagate, not be priced.

### Consequences

- Good: cost reflects what the deployment is actually billed wherever a
  session-spend endpoint exists, instead of the CLI's Anthropic-rate-card guess.
- Good: attempts a shell kills by TTL timeout or output cap, and non-zero exits
  with a `result` event, are now metered — previously silent, unbudgeted spend.
- Good: a fallback is never invisible — `cost_source` / `llmAccounting.source` /
  `llmAccounting.fallbackReason` mark it on the run, span, and status API.
- Good: a genuinely cancelled attempt (process kill, server shutdown — as opposed to the
  shell's own `timeout`/output-cap abort) skips the remote lookup entirely
  (`run cancelled before lookup`) instead of holding the `NonCancellable` accounting block
  open for up to `timeoutMs`; the spend is still real and still charged from the CLI figure.
- Bad: one bounded HTTP call is added to every Claude attempt when configured, up
  to `timeoutMs` of latency (default 3000 ms, capped at 30000 ms) even on the happy
  path.
- Bad: `resultEnvParam` mappings of `total_cost_usd` (e.g. `jsonPath` /
  `regex` transformers an agent defines) still return the CLI's raw figure — they
  read the CLI's own stdout, not the resolved cost. `AGENT.md` calls this out next
  to the example.
- Bad: Codex is not wired to this resolver — the Codex CLI reports tokens, not
  USD, and correlating a proxy's spend rows to Codex's `thread_id` is unverified.
  Left as a follow-up; the resolver is executor-agnostic. *(Amended 2026-10-09: since
  [ADR-0070](0070-llm-harness-adapter-boundary.md) every harness, Codex included, is
  priced through this resolver.)*
- Bad (known limitation): when an agent pins `Command.Claude.options.sessionId`
  **and** sets `retry.attempts > 1` with `llmPrice.sessionSpend` configured, every
  attempt shares the same CLI session, so each attempt's lookup returns that
  session's *cumulative* total and each is charged as if it were a fresh delta —
  the effective charge over-counts by roughly the attempt count. The direction of
  the error is fail-closed (over-charging a budget, never under-charging it), and a
  correct fix needs per-attempt deltas from the spend endpoint, which no considered
  endpoint shape exposes today; deferred rather than solved by this ADR. (Superseded
  by the 2026-10-06 amendment below for any deployment that sets the request-window
  keys; without them this limitation still stands.)
- Bad (accepted): an exit-0 attempt now reads its own stdout file twice — once
  leniently for accounting (`scanClaudeAccounting`), once strictly for success
  gating (`inspectClaudeOutput`). Accepted because it is a local file read (not a
  network round trip), and keeping the two scans as separate functions is what lets
  accounting stay lenient and unconditional while success gating stays strict and
  exit-0-only; merging them would couple two concerns this design deliberately
  keeps apart.

### Amendment 2026-09-11 — re-poll a lagging endpoint

The single `GET` issued the moment the CLI exits assumed the endpoint already knows the
session's spend. LiteLLM, and proxies like it, ingest spend rows asynchronously: a real
five-call run was charged 0.1649 — its first call — against a session total of 0.2441,
with the lag observed at over 20 s. Under-charging is exactly the direction `costLimits`
cannot tolerate.

`llmPrice.sessionSpend.callsPath` (optional, default null) is a second JSONPath into the
same response, yielding how many of the session's calls the endpoint has ingested so far.
The expected count comes from the attempt's own stdout: distinct `message.id` values on
`assistant` events (`ClaudeAccountingScan.modelCalls`) — the CLI emits one event per
content block, all sharing the message id. While the endpoint is behind, the lookup is
retried at a fixed 0 / 5 / 15 / 30 s after the exit
(`LlmCostResolver.DEFAULT_POLL_OFFSETS_MS`), accepting the first response that covers
every call. Leaving `callsPath` unset keeps the original single lookup exactly.

**A fixed schedule rather than interval knobs.** Poll count, spacing and a deadline would
be three more config keys answering a question the operator cannot usefully answer — the
lag is a property of the proxy's ingestion pipeline, not of the deployment — and each is
another way to make one attempt hold the accounting block open for minutes. The fixed
schedule is front-loaded (most sessions are complete within the first slots) and its worst
case is one flat, auditable bound rather than a product of configured values.

**An incomplete figure is charged, and marked.** When the count never catches up the
highest partial figure observed across the schedule is charged as `CostSource.SESSION`
with `fallbackReason = "incomplete: <n> of <m> calls ingested"` — the best reading rather
than the latest, since a later response may report fewer ingested calls (a different
replica, a rolled-back row) and charging that would under-count spend already known.
Charging the CLI figure instead would swap a known under-count for a differently-wrong
guess, and dropping the charge would leave real spend unbudgeted. This widens
`ResolvedCost.fallbackReason` / `llmAccounting.fallbackReason` from "why the session lookup
failed" to "why the figure is degraded" — same field name and wire shape, but a reason can
now accompany `source: session`.

The marker is only as trustworthy as the comparison behind it: `callsPath` must count
units comparable to the CLI's assistant messages (one spend row per model response), since
an endpoint that also logs calls the CLI never surfaces (summaries, internal retries)
reaches the expected count early and an under-count can then be accepted unmarked — still
strictly better than a single lookup.

A `callsPath` that matches nothing, or a non-number, is a failed attempt
(`callsPath yielded no number`) rather than "zero ingested": a broken path says nothing
about ingestion progress, so it is retried on the schedule and, if it never resolves, the
CLI figure is charged with that reason — the same shape as `costPath yielded no number`.

The waits are plain `delay`, so a genuine cancellation during one propagates and the
attempt keeps its charge without the remote figure: `ClaudeExecutor` catches a
`CancellationException` escaping `accountAttempt` on the success path, re-accounts the
attempt under `NonCancellable` with the remote lookup skipped (the CLI figure, reason
`run cancelled before lookup`), and rethrows — mirroring the abort path. That re-account is
idempotent only because `accountAttempt` itself runs everything after the resolve — the span
attributes and both charge calls — under `NonCancellable`: `addCost` and `recordCost` are
suspension points, so without it a cancellation delivered between them would escape with the
first charge already committed and the catch would charge the attempt again. Cancellation can
therefore escape only before any charge, which is what makes a run cancelled mid-poll never
charged twice and never charged nothing.

Amending the latency consequence above: with `callsPath` set, an attempt whose session
never finishes ingesting delays the post-exit accounting by up to 30 s plus four
`timeoutMs` request bounds (a killed or aborted attempt therefore surfaces that much
later). Without `callsPath`, the bound is unchanged.

## Pros and Cons of the Options

### Option A — Langfuse prices from its own model table

- Good: no new config surface; Langfuse already has per-model pricing for many
  providers.
- Bad: only the trace view is corrected. The cost ledger behind `costLimits`, the
  status API, and `processes.cost_usd` still carry the CLI's fiction figure —
  budgets keep enforcing on the wrong number.

### Option B — Homer-maintained per-model rate table

- Good: no outbound HTTP call per attempt; works with no operator-side endpoint.
- Neutral: could still be added later as a middle rung between session lookup and
  the CLI figure — the resolver interface
  (`resolve(sessionId, cliCostUsd, expectedCalls) -> ResolvedCost`) leaves room for it.
- Bad: needs ongoing maintenance as models and negotiated proxy prices change, and
  a table entry is still a guess relative to a proxy's actual contract.

### Option C — external session-spend lookup, CLI figure as marked fallback

- Good: fixes every sink from one source; provider-agnostic (URL template, headers,
  JSONPath); a failure of the lookup never blocks or fails the run.
- Bad: requires the operator to stand up or already have a spend-query endpoint;
  adds per-attempt latency when configured.

## Security

**Boundary crossed:** the CLI session id is read from agent-writable output (the
CLI's own stdout, written into the workspace by a process the agent controls) and
used to build part of an outbound HTTP request to an operator-configured host —
untrusted input reaching a network call (§12 rule 2).

Mitigations:

- **Charset gate before use.** The session id must match `^[A-Za-z0-9_-]{1,128}$`
  (`SESSION_ID_PATTERN`, `homer.app.models.LlmAccounting.kt`) or the lookup is
  skipped entirely (`invalid session id`, zero requests issued) — checked before
  the id ever reaches the URL, so a path-traversal-shaped value (`../admin`)
  cannot redirect the request to a different path segment. The effective gate
  for a normal run is actually earlier: `scanClaudeAccounting` enforces the same
  shared pattern at the point the id enters storage (spans, the executions
  column, the status API), so an invalid id never reaches the resolver at all —
  `LlmCostResolver`'s own check exists as defense in depth (a caller other than
  the executor path could still pass an unvalidated id), which is why
  `invalid session id` is not reachable from `ClaudeExecutor` today.
- **URL-encoded on substitution.** The (already charset-gated) id is
  `encodeURLPathPart`-encoded before replacing `{sessionId}` in the configured URL
  template, so it can only ever address the one path segment the operator's
  template placed it in.
- **Host and path are config-only, not attacker-influenced.** The URL template
  itself comes from `llmPrice.sessionSpend.url`, validated at startup
  (`https://`, or `http://` to a loopback host only) — no part of the host or
  scheme is derived from agent output, closing the SSRF vector. `{sessionId}`'s
  *position* in that template is validated too: `SessionSpendConfig.init`
  substitutes two different probe values for the placeholder and compares what
  `java.net.URI` resolves for `authority` and `fragment` — a difference means
  the placeholder sits inside the host/port/userinfo/fragment rather than the
  path or query, and startup rejects the config.
- **Bounded request count.** Without `callsPath`, one `GET` and no retry. With it, at
  most four `GET`s per attempt at the fixed offsets 0 / 5 / 15 / 30 s, stopping early at
  the first complete reading. Each request's whole request-and-read phase is capped at
  `timeoutMs` (`1..30000`, default 3000) independent of the HTTP engine (see the
  `withTimeout` decision above), and each response body is capped at 1 MiB (read via a
  bounded channel, never an unbounded `bodyAsText()`), so an attempt's worst case is
  30 s of waiting plus 4 × `timeoutMs` — a flat bound, not a configurable one. Every failure
  mode — non-2xx (including a redirect, since none is followed), timeout,
  connection error, oversized body, non-JSON body, a JSONPath miss, a non-numeric,
  non-finite, or negative value — degrades to the CLI figure with a reason instead
  of throwing (§12 availability rule); cost is metadata, never a reason to fail the
  run. The lookup is also skipped outright (`run cancelled before lookup`) when the
  attempt's coroutine was genuinely cancelled rather than aborted by the shell itself —
  the bound above still applies to every lookup that does run, but a cancelled run must
  not wait out that bound at all: skipping instead of bounding avoids a `NonCancellable`
  accounting block that stays open up to `timeoutMs` on every cancelled attempt (§12
  availability rule).
- **`modelCalls` steers the request count, and is agent-writable.** The expected-call
  count is a second value read from the attempt's own stdout (distinct `message.id`s on
  `assistant` events), so an agent can influence it. Inflating it buys at most one full
  schedule of bounded waiting (the four requests above) and makes the `incomplete` marker
  appear on the charge; the id set is bounded at 10 000 so the count itself cannot grow
  without limit. Deflating it degrades the safeguard to the pre-`callsPath` single-lookup
  behaviour — weaker than what an agent already achieves by truncating its own stdout, where
  no session id means the attempt is charged 0.0. Accepted: neither direction buys more than
  the bound already permits.
- **Every remaining failure mode degrades too.** `LlmCostResolver.resolve`'s final
  `catch (Throwable)` deliberately degrades even a JVM `Error` to `lookup failed` with a
  WARN log instead of letting it propagate — cost is metadata, and no failure of this
  lookup may abort a run (§12 availability rule). Catching `Throwable` this broadly is
  an accepted observability trade-off: a genuine `Error` (e.g. `OutOfMemoryError`) is
  swallowed into a log line rather than surfacing as a crash, in exchange for the
  guarantee that a spend lookup can never fail a Claude run.
- **Never cached (§12 rule 5).** Each attempt gets its own lookup; nothing about a
  session's spend is retained beyond the attempt that resolved it.
- **Headers travel by reference, not by manifest (§12 rule 7).**
  `SessionSpendConfig.headers` values support `@SYSENV:VAR`; `toString()` redacts
  header values to `***`; fallback reasons are a fixed vocabulary plus, at most, an
  HTTP status code or a JSONPath description — never the URL's query part, the
  response body, or a header value.
- **Denial-path tests** (`LlmCostResolverTest`): invalid/path-traversal-shaped
  session id never issues a request; every failure class (404, 500, a followed-vs-
  not-followed redirect, timeout, connection failure, oversized body, non-JSON,
  path-miss, non-numeric, non-finite, negative — including a negative element
  inside a summed list) degrades to `CLI` with the matching reason and no thrown
  exception; a reason string never contains the configured header value or
  response body; the session id is URL-encoded on the wire. A same-UID accepted
  risk: a process that can rewrite the attempt's stdout can name any
  charset-valid session id and have Homer charge that session's total, possibly
  under-charging `costLimits` for the run that actually incurred the spend —
  per §12 same-UID access is not a boundary, and the previous code already
  trusted `total_cost_usd` read from the same file, so the trust level is
  unchanged.

### Amendment 2026-09-16 — cap `expectedCalls` at 1 000 and mark capped figures incomplete

The Security section above records that inflating `modelCalls` (the expected-call count
passed to `LlmCostResolver.resolve`) "buys at most one full schedule of bounded waiting …
Accepted: neither direction buys more than the bound already permits." That analysis assumed
`expectedCalls` was uncapped and was therefore always equal to the session's real call count.

A subsequent change introduced `MAX_EXPECTED_CALLS_FOR_POLLING = 1_000` in `ClaudeExecutor`
to prevent a crafted stdout from passing the scan ceiling (10 000 distinct ids) as the poll
driver. The cap is correct for the availability concern it addresses, but it re-opens the
accepted risk: a legitimate session with, say, 1 500 calls passes `expectedCalls = 1 000` to
the resolver. If the endpoint has ingested 1 200 calls by the time the executor polls, the
resolver sees `ingested (1 200) >= expectedCalls (1 000)` and returns `Complete` — charging
the 1 200-call total with no `fallbackReason`, even though 300 calls are still unaccounted.
That is the exact fail-open direction the amendment above exists to prevent.

**Resolution** — `ClaudeExecutor.accountAttempt` now forces
`fallbackReason = "incomplete: <n> session calls, poll count capped at 1 000"` when all of
the following hold: the remote lookup ran, `scan.modelCalls > MAX_EXPECTED_CALLS_FOR_POLLING`,
and the resolver returned a session figure with no prior reason. This mirrors the "incomplete:
N of M calls ingested" reason the schedule itself appends when the endpoint never catches up,
so the under-count is always visible in the status API and spans.

**Rejected alternative** — keep the original accepted bound (no cap on `expectedCalls`) and
rely on the scan ceiling (10 000) as the sole limit. The accepted risk analysis held at that
level: 10 000 expected calls is high enough that it is effectively never reached by a
legitimate session, so the resolver's `Complete`-on-cap path is never triggered in practice.
The cap was retained at 1 000 for defence-in-depth, paired with the incomplete marker above
so legitimate large sessions are never silently under-charged.

## Amendment 2026-10-06 — price each attempt from its own requests (window pricing)

The "Bad (known limitation)" consequence above records that a pinned
`Command.Claude.options.sessionId` with `retry.attempts > 1` charges each attempt the
session's *cumulative* total. In production the same over-charge affects every
`--continue` agent — a developer agent resuming one session across plan → implement →
fix runs reuses one `session_id`, so run *n* was charged the running total of runs 1..n
plus its own. Measured over 21 days the developer agent was charged $252.95 against
$120.00 of real spend (2.1×); a five-run session was charged $57.77 against $16.86.
The second defect the same runs exposed is on the completeness poll: it compared
`expectedCalls` (this attempt's distinct `message.id`s) with `$.calls`, the **whole
session's** call count, so on a resumed session the earlier runs' calls already
satisfied `ingested >= expectedCalls` and the figure was accepted before this attempt's
own requests were ingested.

**Resolution.** `llmPrice.sessionSpend` gains three optional keys — `requestsPath`
(`$.requests`), `startedPath` (`$.started`), `requestSpendPath` (`$.spend`) — naming the
endpoint's per-request rows and, within a row, its start time and cost. When all three are
set, an attempt is priced as the sum of the rows whose `started` falls inside the attempt's
own `[startedAtMs, endedAtMs]` window, widened by `windowGraceMs` (default 5000) on each
side for clock skew and ingestion delay; the count of rows inside the window replaces
`$.calls` as the ingested figure for the completeness poll. The window is captured in
`LlmHarnessExecutor` around the shell call — Homer server time, never a runner-local clock —
so the fix is runner-neutral. A row's `started` may be an ISO-8601 instant or offset
date-time, or a number read as epoch milliseconds — or seconds when its magnitude is below
1e11; any other value is unreadable. The three keys must be set together or all left null; a
partial trio fails startup, and a window row whose `started` cannot be read (a zone-less or
non-date value) refuses the whole window rather than being misattributed or skipped: including
it would risk charging this attempt an earlier one's spend, and skipping it would drop a
possibly in-window row from the charge invisibly. New fixed-vocabulary fallback reasons name each window
failure: `requestsPath yielded no rows`, `requestsPath rows have no readable timestamp`,
`requestsPath yielded no number`, `requestsPath yielded a negative number`, and
`attempt window unavailable` (the keys are set but no boundaries were passed). Leaving the
keys unset reproduces the session-cumulative behaviour exactly, so existing deployments are
unaffected.

**Bad (accepted): the grace can double-count a boundary row across overlapping attempts.**
The window is widened on both sides, so two `--continue` attempts whose wall-clock windows
overlap by up to ~2 × `windowGraceMs` (default 10 s — a retry with near-zero backoff, or a
fast plan round) can each claim a row started inside the overlap and charge it twice. The
direction is the fail-closed one (an over-charge, never an under-charge), and the bound is
one row per overlap, not a widening one; an operator auditing per-attempt charges that sum
to more than the session's spend should check for overlapping attempts first. Halving the
grace (applying it only after the window's end, where ingestion delay actually sits) was
rejected: a start-side grace is what absorbs a spend endpoint's rows whose clock sits
behind Homer's, and dropping it would push real boundary rows out of their attempt's
window and under-charge them instead.

**Bad (accepted): the window pair is two wall-clock readings, not a monotonic
measurement.** `startedAtMs` and `endedAtMs` are two independent `System.currentTimeMillis()`
captures around the shell call; nothing asserts `endedAtMs >= startedAtMs`. A wall-clock
step back mid-attempt yields a zero-width or inverted window, every row falls outside it,
and the attempt degrades to the CLI figure — never a misprice, but a degradation
indistinguishable from an ingestion gap. Coercing with `maxOf` (or deriving the window from
one instant plus the poll schedule) was rejected: it would paper over the step rather than
record it, and the poll schedule's own waits dominate the window in every real case.

**Rejected alternative — delta pricing (`session total − already charged`).** It needs
persisted per-session state, breaks when attempts of one session overlap or are partially
ingested, and does not fix the second defect (the poll would still accept a session-wide
count). The per-request window fixes both from the same response.

The known limitation above is superseded by this amendment for any deployment that sets the
window keys; the pinned-`sessionId` × `retry.attempts > 1` case is exactly a session reused
across attempts and is fixed the same way. Without the keys, that limitation stands.

## More Information

- [ADR-0047](0047-security-by-design-and-threat-model.md) — the §12 threat model
  this decision is held to.
- [ADR-0007](0007-cost-meter-as-in-memory-soft-throttle.md) — the soft-throttle
  cost cap this pricing feeds.
- [`app/SERVER.md#llm-price`](../../app/SERVER.md#llm-price) — configuration
  reference.
