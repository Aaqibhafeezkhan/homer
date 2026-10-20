# Accurate cost through an LLM gateway

Homer's maintainers and a team that builds a mobile app both send every
Claude step through an LLM gateway that serves third-party models next to
Anthropic's. Both found that the cost Homer recorded was not what they were
billed, for two separate reasons.

First, by default Homer charges each Claude attempt the `total_cost_usd` that
the Claude CLI reports. The CLI computes that from its built-in Anthropic rate
card, for the model name it believes it called. Behind a gateway, that is a
guess. Second, once Homer asked the gateway instead, the gateway's spend
endpoint turned out to ingest its rows asynchronously. A lookup the moment the
CLI exited saw only what had landed so far: **a run that made five calls was
charged for its first call only.**

Everything that reads cost sat on that number: the status API, the cost
ledger behind `costLimits` and the monthly totals, the span attributes and the
Langfuse cost override.

## The setup

```
Claude CLI ──▶ LLM gateway ──▶ models
    │ session_id                 │ spend rows, ingested asynchronously
    ▼                            ▼
Homer, after the CLI exits:   GET /api/session/{sessionId} → {"spend": …, "calls": …}
  records the step on the CLI figure ("session figure pending") and moves on, while
  a background settlement polls at 0, 5, 15, 30 s until "calls" covers every call
  the attempt made → costUsd, costSource, llmAccounting → ledger, costLimits, telemetry
```

```yaml
# Homer config (excerpt)
server:
  # Above the worst-case post-exit accounting: 30 s of polling + 4 x timeoutMs (3 s) = 42 s.
  shutdownDrainSec: 60

llmPrice:
  sessionSpend:
    url: "https://llm-gateway.example.com/api/session/{sessionId}"
    costPath: "$.spend"     # the session's spend so far
    callsPath: "$.calls"    # how many calls the gateway has ingested for the session
```

- **One `GET` per lookup, keyed by the CLI session id**
  ([LLM price](../../app/SERVER.md#llm-price)). Homer reads the session id
  from the first stream event the CLI writes, so an attempt that failed or hit
  its timeout is still priced. The `url` must contain `{sessionId}` once and
  be `https://` (plain `http://` only to loopback). Both deployments' endpoint
  is open inside the cluster network, so they send no `headers`; an endpoint
  that needs a token gets it with `headers` and `@SYSENV:`.
- **`callsPath` turns one lookup into a short poll.** Homer counts the calls
  the attempt made (distinct assistant message ids in the CLI's own output)
  and polls at 0, 5, 15 and 30 s after the exit, stopping at the first
  response whose call count covers them. If the count never catches up, Homer
  still charges the highest partial figure it saw, and marks it:
  `fallbackReason: incomplete: 3 of 5 calls ingested`. Each poll is bounded by
  `timeoutMs` (default 3000) and a 1 MiB response cap. A lookup never fails
  or blocks the run: the step is recorded on the CLI figure as soon as the CLI
  exits, marked `session figure pending`, and the poll replaces that record
  when it settles
  ([ADR-0089](../adr/0089-settle-llm-cost-in-the-background.md)). The next
  step starts without waiting, which on a pipeline of short LLM steps was
  worth up to 35 s per step.
- **A failed lookup falls back, visibly.** If the endpoint is down, times out
  or answers without a number, Homer charges the CLI figure and records
  `costSource: cli` with the reason.

What a run shows afterwards
([`app/README.md`](../../app/README.md#api-endpoints)):

```bash
curl -fsS -H "Authorization: Bearer $TOKEN" https://homer.example.com/api/v1/status/4711 \
  | jq '{costUsd, costSource,
         steps: [.executions[] | select(.llmAccounting)
                 | .llmAccounting | {costUsd, source, fallbackReason}]}'
```

`costSource` is `session`, `cli`, or `mixed` when a run's attempts were priced
differently. A rising share of `cli` or of `incomplete` reasons is the signal
that the gateway endpoint needs a look.

## Details that matter

- **Point `callsPath` at the session total.** The gateway's response has two
  counts: the calls ingested for the session, and the rows this response chose
  to return. Only the first one tells Homer when to stop. The other reaches the
  expected number early and accepts an under-count with no mark.
- **Size the shutdown drain above the poll.** A finished attempt's settlement,
  and an attempt cut short by its `timeout` or by the output cap, are priced
  by a poll that can take up to 30 s plus four request timeouts: 42 s at the
  defaults. With a shorter `server.shutdownDrainSec`, a SIGTERM during that
  window cuts the charge short, and real spend never reaches the ledger
  ([`server.shutdownDrainSec`](../../app/SERVER.md#servershutdowndrainsec)).
  The maintainers set 60 s, with the pod's `terminationGracePeriodSeconds`
  above it. The other deployment still runs the default 30 s, which is below
  that floor.
- **Telemetry lines can kill the run you are trying to price.** On a
  third-party model, Claude Code cannot read real usage, so it writes one
  `system`/`thinking_tokens` progress line (about 195 bytes) per one or two
  thinking tokens. One two-hour implementation step died eleven minutes in,
  on the 10 MiB `maxOutputBytes` cap. That was 9.73 MB in 49,813 lines of this
  telemetry, against 0.77 MB of actual conversation. The stopgap was
  `maxOutputBytes: 67108864` (64 MiB). That still fits under the 100 MiB
  `maxArtifactBytes`, so the file stays downloadable. Homer 1.24.0 then added
  `server.claudeDropSystemSubtypes`, which defaults to `["thinking_tokens"]`
  and drops those events before they are written
  ([`server.claudeDropSystemSubtypes`](../../app/SERVER.md#serverclaudedropsystemsubtypes),
  [ADR-0063](../adr/0063-filter-claude-stdout-at-write-time.md)). Result
  events are never dropped, so pricing is unaffected. The step's stderr gets
  one count line instead:
  `[HOMER] Dropped 49813 system/thinking_tokens events (9731234 bytes) from stdout`.
- **Read the charged figure, not the CLI's.** A `resultEnvParam` that maps
  `total_cost_usd`, or a script that reads the `result` event from the
  transcript, still gets the CLI's estimate. The figure Homer charged is
  `costUsd` and `llmAccounting.costUsd` on the status API.

## Cost by tag

Once the per-run number is right, it can be summed by subject. The mobile
team's stage agents already tag every run with the ticket it works on:

```yaml
# a stage agent (excerpt)
tags:
  fromInputs: [ticket_id]
```

Adding that key to `costTrackedTagKeys` (a top-level config key) makes spend per
ticket one admin call away
([Cost by tag](../../app/SERVER.md#cost-by-tag),
[ADR-0062](../adr/0062-cost-by-tag.md)):

```yaml
costTrackedTagKeys: [ticket_id]
```

```bash
curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://homer.example.com/api/v1/costs/by-tag/ticket_id
# {"key":"ticket_id","valuesUsd":{"1042":4.12,"1057":0.87},"totalUsd":4.99,"truncated":false}
```

The breakdown sums each run's `costUsd` against its tags at query time.
Child runs do not inherit their parent's tags, so every agent that runs an
LLM step has to declare the tag itself. A LangGraph parent's resume runs carry
no caller inputs, so the tag belongs on the stages, not the graph. Totals cover
the newest 10,000 runs that carry the key, and `truncated` says when there are
more.

## Cost limits

`costLimits` are rolling-window caps on the same resolved figure
([Cost Limits](../../app/SERVER.md#cost-limits)). Before the gateway pricing,
a cap would have measured the CLI's guess. Both deployments have a global cap
prepared and commented out:

```yaml
# costLimits:
#   globalUsdPerMin: 30.0
```

Know what a cap does before you switch it on:

- It is checked when a run starts. A start over the cap gets `429`, and a run
  already going finishes even if it pushes the window over. Concurrent starts
  can overshoot together. It is a single-node soft throttle.
- An agent with no `!<claude>` or `!<codex>` command is never refused. Keep
  the agents that report outcomes and failures free of LLM steps, so they
  still work when the cap is hit.
- `POST /api/v1/agents/{name}/debug` is not capped. A `debug` grant is a way
  around the cap.
- An under-count is the unsafe direction for a cap. Watch the `incomplete`
  reasons.

## What it does not solve

- **A killed run is priced from the CLI only.** A server shutdown or a kill
  cancels the attempt before the lookup, so Homer charges the CLI figure if
  the `result` event arrived, and otherwise nothing. Such a run can end up
  outside `costLimits`.
- **Resumed sessions are charged cumulatively.** `costPath` is the
  session's total so far. An agent that resumes one session across runs would
  be charged the running total each time. The window keys (`requestsPath`,
  `startedPath`, `requestSpendPath`) price each attempt from its own requests
  instead ([`sessionSpend` fields](../../app/SERVER.md#sessionspend-fields)).
  Neither deployment resumes sessions today.
- **The gateway is trusted.** Homer charges what the endpoint reports. A
  gateway that logs internal retries or summaries as calls reaches the
  expected count early, and the figure is accepted without a mark.
