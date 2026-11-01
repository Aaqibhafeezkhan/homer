# Server Configuration Reference

This document provides comprehensive documentation for configuring the Homer server via `AppConfig`.

## Configuration Overview

Homer accepts configuration in either YAML or JSON format. The configuration file path is passed as a command-line argument; the file must end in `.yaml` or `.json` (`.yml` is rejected):

```bash
./gradlew :app:run --args="config.yaml"
# or
./gradlew :app:run --args="config.json"
```

The configuration controls:
- Server HTTP settings and authentication
- Agent discovery and workspace paths
- Langfuse integration for observability
- Global environment variables
- OpenTelemetry export settings

## Configuration Schema

### Top-Level Structure

```yaml
server:
  port: 8080
  apiAuth: !<bearer>
    tokens:
      admin:
        token: "secret-token-123"
        role: admin

agentsSearchPath: "./agents"
workspaceRootPath: "./workspaces"
databasePath: "./homer.db"

langfuse:
  serverUrl: "https://cloud.langfuse.com"
  publicKey: "pk-lf-xxx"
  secretKey: "sk-lf-xxx"

globalEnv:
  APP_ENV: "production"
  LOG_LEVEL: "info"

openTelemetryExport: LANGFUSE

llmPrice:
  sessionSpend:
    url: "https://spend.example.internal/api/session/{sessionId}"
    headers: { Authorization: "Bearer @SYSENV:LLM_SPEND_TOKEN" }
    costPath: "$.spend"
```

<details>
<summary>JSON format</summary>

```json
{
  "server": {
    "port": 8080,
    "apiAuth": {
      "type": "bearer",
      "tokens": {
        "admin": { "token": "secret-token-123", "role": "admin" }
      }
    }
  },
  "agentsSearchPath": "./agents",
  "workspaceRootPath": "./workspaces",
  "databasePath": "./homer.db",
  "langfuse": {
    "serverUrl": "https://cloud.langfuse.com",
    "publicKey": "pk-lf-xxx",
    "secretKey": "sk-lf-xxx"
  },
  "globalEnv": {
    "APP_ENV": "production",
    "LOG_LEVEL": "info"
  },
  "openTelemetryExport": "LANGFUSE",
  "llmPrice": {
    "sessionSpend": {
      "url": "https://spend.example.internal/api/session/{sessionId}",
      "headers": { "Authorization": "Bearer @SYSENV:LLM_SPEND_TOKEN" },
      "costPath": "$.spend"
    }
  }
}
```
</details>

### Field Reference

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `server` | `Server` | Yes | - | HTTP server configuration |
| `agentsSearchPath` | `String` | Yes | - | Directory path containing agent definitions |
| `workspaceRootPath` | `String` | Yes | - | Root directory for process workspaces |
| `databasePath` | `String` | Yes | - | Path to the SQLite database for durable process + question state (created if absent). Supports `@SYSENV:VAR_NAME`. |
| `langfuse` | `LangfuseConfig` | No | empty | Langfuse integration configuration |
| `globalEnv` | `Map<String, String>` | No | `{}` | Global environment variables for all processes |
| `parentEnvAllowlist` | `List<String>` | No | `["PATH","HOME","LANG","TZ"]` | Host JVM env keys layered under `globalEnv` for the `local` runner only — remote runners never receive them — see [`parentEnvAllowlist`](#parentenvallowlist) |
| `openTelemetryExport` | `OpenTelemetryExport` | No | `LANGFUSE` | OpenTelemetry export destination |
| `runners` | `Map<String, RunnerConfig>` | No | `{}` | Named pluggable execution backends — see [Runners](#runners-pluggable-execution-backends) |
| `costLimits` | `CostLimits` | No | `{}` | Soft caps on LLM harness spend (`claude`, `codex`, `opencode`) — see [Cost Limits](#cost-limits) |
| `llmPrice` | `LlmPriceConfig` | No | `null` | Price every LLM harness run (`!<claude>`, `!<codex>`, `!<opencode>`) from an external spend API — see [LLM price](#llm-price) |
| `costTrackedTagKeys` | `List<String>` | No | `[]` | Subject-tag keys queryable via `GET /api/v1/costs/by-tag/{key}` — see [Cost by tag](#cost-by-tag) |

## Cost Limits

`costLimits` adds rolling-window caps on spend from any LLM harness command
(`!<claude>`, `!<codex>`, `!<opencode>`). Caps are checked before a
run starts on `POST/GET /api/v1/agent/{name}`, cron, MCP tools, `!<agent>`,
dispatch, continuation fires (all via the shared preflight in
`AgentRunStarter`), and on the retry and resume endpoints, which check
explicitly.

**`POST /api/v1/agents/{name}/debug` is not capped.** It runs a single declared
command by index, which may be a `!<claude>`, `!<codex>` or `!<opencode>` one, and it does not
check spend caps. It honours the agent's `cooldown.minInterval`, which is
enforced when the process row is inserted — but not `cooldown.afterFailure`,
which a manual investigation is meant to bypass. Same for the retry and resume
endpoints. A `debug` grant on an agent is therefore a way to spend past
that agent's cap; scope the grant accordingly. If the
relevant window already meets-or-exceeds the cap, the start is refused with
`CostCapExceededException` (`429 Too Many Requests` over HTTP) before any
process is launched, so no LLM tokens are spent and `ProcessManager` is not
consumed. A run that has already passed the check runs to completion even if
its in-flight cost would push the window over the cap — the next request will
see the updated window and be refused.

**An agent whose definition contains no `claude`/`codex`/`opencode` command is never
refused by a cost cap.** That is a deliberate, stable contract, not an
implementation accident: such an agent cannot accrue LLM harness spend, so
binding it to a spend cap would only make it unavailable. Build notifier /
escalation agents on it — they keep working precisely when spend is capped and
you most need to hear about it. A continuation whose target *does* run an LLM
harness command and is refused by a cap is not lost either: it is retried with
backoff rather than failed (see
[ADR-0023](../docs/adr/0023-completion-continuations.md)).

Concurrent requests that all pass the boundary check at once may collectively
push the window over the cap (each sees the pre-update window state). This is
the documented soft-throttle behavior; tighten the cap if you need a hard
ceiling.

Spend is tracked per-process from each LLM harness (Claude/Codex/opencode) attempt's **resolved** cost —
the CLI's own `total_cost_usd`, or the external `llmPrice.sessionSpend` lookup
when configured (see [LLM price](#llm-price)) — charged whenever it is
positive, for every attempt regardless of exit code, including attempts a
shell aborted by timeout or output cap. Charges are appended to the
`cost_entries` table in the SQLite database, so cap windows survive a server
restart. Rows are never pruned. There is no cross-node sync — caps are a
single-node soft throttle. See [ADR-0035](../docs/adr/0035-sqlite-backed-cost-ledger.md)
and [ADR-0061](../docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md).

```yaml
costLimits:
  globalUsdPerMin: 5.0       # Global cap: \$5 across all agents in any 60 s window
  perAgentUsdPerDay:
    pr-reviewer: 50.0        # Per-agent cap: \$50/day rolling for pr-reviewer
    nightly-batch: 200.0
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `globalUsdPerMin` | `Double?` | No | `null` | Cap on summed spend (USD) over the trailing 60 s window across all agents. `null` disables the global cap. Must be `> 0` when set. |
| `perAgentUsdPerDay` | `Map<String, Double>` | No | `{}` | Per-agent cap on summed spend (USD) over the trailing 24 h window. Agents not listed are uncapped. Each value must be `> 0`. |

The current spend (and configured caps) is exposed at `GET /api/v1/costs` for
dashboards.

## LLM price

By default Homer prices every Claude attempt from the `total_cost_usd` the Claude CLI
reports, which the CLI computes from its built-in Anthropic rate card for the model
name it believes it called. Behind an LLM proxy that serves other models or other
contracts, that figure is not what you are billed.

`llmPrice.sessionSpend` prices each attempt from an external spend endpoint keyed by
the CLI session id instead. It applies to every LLM harness command (`!<claude>`,
`!<codex>` and `!<opencode>`) alike. When the lookup fails the CLI figure is charged and the run is
marked (`costSource: cli`, `llmAccounting.fallbackReason`) — for a harness whose CLI
reports no cost of its own (Codex), a failed or unconfigured lookup instead records
`costSource: none` and charges nothing, since there is no CLI figure to fall back to.
Each lookup is a single `GET`, bounded by `timeoutMs` and a 1 MiB response cap, and
never blocks or fails the run. A finished attempt does not wait for it either: the step
is recorded at once with the CLI figure (`costSource: none` and 0.0 for Codex, which has
none) and `fallbackReason: session figure pending`, and
the lookup settles in the background, replacing that record and adding to the run's
`costUsd` when it answers — so a run read right after an LLM step, or right after it
finished, can still show the pending figure. See ADR-0089.

By default the figure charged is the session-cumulative `costPath` value — the whole
session's spend so far. An agent that resumes its session with `--continue` (for example a
developer agent running plan → implement → fix rounds) reuses one `session_id` across runs,
so every run after the first would be charged the running total of all earlier runs plus its
own. Point the window keys at the endpoint's per-request rows — `requestsPath` (`$.requests`),
`startedPath` (`$.started`) and `requestSpendPath` (`$.spend`) — to price each attempt from
only the requests whose start time falls inside that attempt's own window, so the per-attempt
charges sum to the session's spend. `windowGraceMs` (default 5000) widens the window on each
side to absorb clock skew and ingestion delay. The count of rows inside the window — not the
session-wide `callsPath` count — then drives the completeness poll below, so a resumed
session no longer accepts the figure before this attempt's requests are ingested. All three
window keys must be set together or left unset; a window row whose `started` cannot be read
(a date with no zone, or a non-date) refuses the whole window rather than being misattributed
or silently dropped from the charge, and a window with no rows or a non-numeric spend degrades
to the CLI figure with a reason. Leaving the window keys unset keeps the session-cumulative
behaviour exactly.

Spend endpoints ingest their rows asynchronously, so the figure available the moment the
CLI exits often covers only the first calls of the session. Point `callsPath` at a
JSONPath naming how many calls the endpoint has ingested for the session and Homer polls
at 0, 5, 15 and 30 s after the exit, stopping at the first response that accounts for
every call the attempt made (counted as distinct assistant message ids in the CLI's own
output). Those offsets are nominal: the waits between polls are 5, 10 and 15 s and each
starts only once the previous response has come back, so a slow endpoint pushes the later
polls out. A response that arrives without the count, or with a non-number, is a failed
attempt (`callsPath yielded no number`), retried by the same schedule. If the count never
catches up, the highest partial figure observed across the schedule is still charged
(`costSource: session`) and marked `llmAccounting.fallbackReason: incomplete: <n> of <m>
calls ingested`, so an under-count is visible rather than silent. That visibility holds
only as far as `callsPath` counts units comparable to the CLI's assistant messages (one
spend row per model response): an endpoint that also logs calls the CLI never surfaces —
summaries, internal retries — reaches the expected count early, so an under-count can
still be accepted unmarked; it is strictly better than the single lookup either way.
Codex and opencode report no model-call ids, so a `!<codex>` or `!<opencode>` attempt has no call count to wait for.
Its figure is accepted once two successive responses agree on both the call count and the
cost and show at least one call, which takes at least the 5 s gap to the second poll. A
figure still changing when the schedule ends is charged and marked `incomplete: figure not
stable across polls`. An endpoint that stalls for a whole poll gap mid-ingestion is
accepted as an under-count without a mark. See [ADR-0070](../docs/adr/0070-llm-harness-adapter-boundary.md).
Leaving `callsPath` unset keeps the single lookup.

When an attempt is aborted by the command `timeout` or the output cap, the attempt is
priced before the abort propagates, so the abort itself can be observed up to `timeoutMs`
later — or, with `callsPath` or the window keys set and an endpoint that never catches up, up
to 30 s plus four request timeouts later. A genuine process kill (server shutdown, the run itself being
cancelled) is not delayed this way — the remote lookup is skipped (`run cancelled before
lookup`) and only the bounded local accounting runs. Such an attempt charges only the CLI
figure: nothing for Codex, and nothing for Claude cancelled before its result event, so a
cancelled run can end outside `costLimits` (accepted residual, ADR-0070). The aborted
attempt's tool and message spans still reach Langfuse: a failed or aborted attempt's stream
is read leniently, so a truncated last line costs only that line; an exit-0 stream keeps
strict validation. With `callsPath` or the window keys set, raise
[`server.shutdownDrainSec`](#servershutdowndrainsec) above 30 s + 4 × `timeoutMs` (42 s at
defaults) and `terminationGracePeriodSeconds` with it, otherwise a finished attempt's
background settlement, or an attempt aborted by `timeout`/the output cap, can be killed
mid-accounting on SIGTERM and the step keeps `session figure pending` for good. See [ADR-0061](../docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md).

```yaml
llmPrice:
  sessionSpend:
    url: "https://spend.example.internal/api/session/{sessionId}"
    headers:
      Authorization: "Bearer @SYSENV:LLM_SPEND_TOKEN"
    costPath: "$.spend"
    callsPath: "$.calls"
    requestsPath: "$.requests"
    startedPath: "$.started"
    requestSpendPath: "$.spend"
    windowGraceMs: 5000
    timeoutMs: 3000
```

<details><summary>JSON format</summary>

```json
{ "llmPrice": { "sessionSpend": { "url": "https://spend.example.internal/api/session/{sessionId}", "headers": { "Authorization": "Bearer @SYSENV:LLM_SPEND_TOKEN" }, "costPath": "$.spend", "callsPath": "$.calls", "requestsPath": "$.requests", "startedPath": "$.started", "requestSpendPath": "$.spend", "windowGraceMs": 5000, "timeoutMs": 3000 } } }
```
</details>

### `sessionSpend` fields

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `url` | `String` | Yes | - | `GET` URL template; must contain `{sessionId}` exactly once, in the path or query only — not the host, port, userinfo or fragment. `https://`, or `http://` to `localhost` / `127.0.0.1` / `[::1]` only — the request carries `headers`, typically a bearer token. Rejected at startup otherwise. |
| `headers` | `Map<String,String>` | No | `{}` | Sent verbatim. Values support `@SYSENV:VAR`. Never written to logs, spans or error text. |
| `costPath` | `String` | Yes | - | JSONPath into the response body yielding one number, or a list of numbers that Homer sums. Examples: `$.spend` (a session aggregate), `$.data[*].spend` (LiteLLM `GET /spend/logs/v2?session_id=…` rows). Invalid paths fail startup. A list-shaped path only sums the rows returned by that one request — if the endpoint paginates, pin a page size large enough for a whole session or point Homer at an aggregate endpoint, since under-counting is the unsafe direction for `costLimits`. Ignored when the window keys are set. |
| `callsPath` | `String?` | No | `null` | Optional JSONPath yielding the number of model calls the endpoint has ingested for the session, e.g. `$.calls` (a session aggregate). An endpoint that returns per-request rows and no count field can count the rows themselves with a length function — `$.requests.length()`, or `$.data.length()` for LiteLLM `GET /spend/logs/v2?session_id=…`. When set, Homer polls at 0, 5, 15 and 30 s after the CLI exits until the count reaches the calls the CLI made (capped at 1 000 to bound adversarial inflation), charging a partial figure (marked incomplete) otherwise. Sessions exceeding 1 000 calls are marked incomplete whenever the session figure is charged, since the excess calls remain unconfirmed by the spend endpoint. Both rules apply to Claude, which counts its calls; a Codex attempt has no call count and settles on two agreeing readings instead (see above). Invalid paths fail startup. Leave unset to keep the single lookup. Ignored when the window keys are set (the window's row count drives the poll instead). |
| `requestsPath` | `String?` | No | `null` | Optional JSONPath yielding the response's per-request rows, e.g. `$.requests`. Set with `startedPath` and `requestSpendPath`, it prices each attempt from only the rows whose start time falls inside that attempt's window instead of the session-cumulative `costPath`, so a `--continue`-resumed session is charged per attempt rather than the running total. All three window keys must be set together or all left null; a partial trio fails startup. |
| `startedPath` | `String?` | No | `null` | Optional JSONPath into one `requestsPath` row yielding its start time, e.g. `$.started`. ISO-8601 with a zone (e.g. `2026-10-06T10:00:00Z`), or a number read as epoch milliseconds — or epoch seconds when its magnitude is below 100 000 000 000. A row whose value cannot be read refuses the whole window, not just that row. Set with `requestsPath` and `requestSpendPath`. |
| `requestSpendPath` | `String?` | No | `null` | Optional JSONPath into one `requestsPath` row yielding its cost, e.g. `$.spend`. Set with `requestsPath` and `startedPath`. |
| `windowGraceMs` | `Long` | No | `5000` | Milliseconds added on each side of the attempt window when matching `requestsPath` rows, absorbing clock skew and ingestion delay. `>= 0`. Two attempts whose windows overlap through the grace can each claim a row started inside the overlap, so per-attempt charges can exceed the session's spend by up to that row; keep the grace at or above the worst clock skew but no wider. Only used when the window keys are set. |
| `timeoutMs` | `Long` | No | `3000` | Per-request timeout, `1..30000`. Without a completeness signal (`callsPath`, or a window) one attempt, no retry; with one, each of the four scheduled polls gets this timeout. |

### What is priced and where the number goes

- **Every attempt**, including non-zero exits and `timeout` kills: the session id is
  read from the first stream event the CLI wrote, so a killed run is still looked up.
  Without `llmPrice` such attempts are charged the CLI figure when a `result` event
  exists, else nothing.
- The resolved cost drives the process record (`costUsd`), the cost ledger behind
  [Cost Limits](#cost-limits), the `llm.cost_usd` span attribute and the Langfuse
  `cost_details` override. An attempt that ends more than one turn (a background
  subagent finishing after the first `result`) splits that override across its
  generations — in proportion to the CLI's own per-turn cost, or, when those
  increments cannot be read (a missing, negative or decreasing cost, or a zero total), with the whole
  charge on the last generation and an explicit zero on the earlier ones — so they
  sum to the attempt's cost. A failed or stopped turn end emits no priced generation,
  so its share moves to the next successful one, or the last one before it. An attempt
  with no successful turn end (failed, or stopped at a turn or budget limit) is priced on
  its last turn end, and one the CLI left with no turn end at all (killed) gets one
  generation of its own with the `no result event` reason; both carry an ERROR status, so
  a charged attempt never shows in Langfuse as free. See ADR-0074.
- `costSource` on the status API is `session`, `cli`, or `mixed` when a run's charges
  disagree. `none` appears only on an attempt's `llmAccounting.source`, for a harness
  that reports no cost when no spend endpoint answered: such an attempt charges
  nothing, so it never stamps the run. Each LLM harness command's `llmAccounting`
  carries `sessionId`, `costUsd`, `source` and `fallbackReason`.
- `fallbackReason` says why a figure is degraded — a CLI fallback, a session figure
  charged before the endpoint had ingested every call
  (`incomplete: <n> of <m> calls ingested` or the capped-poll reason for Claude,
  `incomplete: figure not stable across polls` for a Codex or opencode attempt; the reasons that
  come with `source: session`), or a pending one (`session figure pending` — the step
  returned on the CLI figure and the session lookup is still settling in the background,
  replacing the record when it answers).
- Fallback reasons are a fixed vocabulary: `no session id in output`, `invalid session id`
  (defense in depth; not reachable from a normal run — the same charset/length gate already
  runs where the id enters storage, before the resolver ever sees it),
  `http <status>`, `timeout`, `connection failed`, `response over 1 MiB`,
  `response is not JSON`, `costPath yielded no number`, `costPath yielded a negative number`,
  `callsPath yielded no number` (the configured `callsPath` matched nothing or a non-number in
  an otherwise usable response, so the endpoint's progress could not be read),
  `requestsPath yielded no rows` (the configured `requestsPath` matched nothing, was not a
  list, or matched rows none of which fall inside the attempt window),
  `requestsPath rows have no readable timestamp` (a window row whose `started` cannot be read
  refuses the whole window, even when other rows parse),
  `requestsPath yielded no number`, `requestsPath yielded a negative number`,
  `attempt window unavailable` (the window keys are set but the attempt's start/end were not
  supplied, so the window cannot be bounded),
  `incomplete: <n> of <m> calls ingested`, `incomplete: <n> session calls, poll count capped
  at 1000` (Claude only) and, for a harness with no call count (Codex, opencode),
  `incomplete: figure not stable across polls` (the reasons that accompany `source: session`),
  `no result event`, `session figure pending` (the attempt finished and its session lookup
  is still running in the background; the record is replaced when it settles — only set
  when `llmPrice.sessionSpend` is configured), `run cancelled before lookup` (the remote
  lookup was skipped or abandoned and only the bounded local accounting ran: the attempt's
  coroutine was cancelled before the lookup — process kill, server shutdown — or the
  background settlement was cancelled or threw mid-lookup; a harness that reports no cost
  of its own (Codex) charges nothing, and a Claude or opencode attempt is charged its CLI
  figure only if its result event arrived; only set when `llmPrice.sessionSpend` is configured),
  `lookup failed` (an unexpected internal error, e.g. from the HTTP client itself).
- `resultEnvParam` mappings of `total_cost_usd` still return the CLI's raw figure.

## Cost by tag

`costTrackedTagKeys` lists the run subject tag (see `README.md`'s
`/status/all?tag=` filter) keys an operator can break LLM harness (Claude/Codex/opencode) spend down by.
A run's `costUsd` is joined against
its current tags at query time — nothing is snapshotted, so a tag declared
mid-run via the tag callback still counts. Only keys listed here are
queryable; an unlisted key is rejected with `400`. Each key must match the
tag-key charset (`^[A-Za-z0-9._-]{1,64}$`) and the list is capped at 10
entries. See [ADR-0062](../docs/adr/0062-cost-by-tag.md).

```yaml
costTrackedTagKeys:
  - team
```

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/v1/costs/by-tag/team
# {"key":"team","valuesUsd":{"payments":1.42},"totalUsd":1.42,"truncated":false}
```

Same admin-only gate as `/costs`, `/costs/monthly` and `/costs/totals` — a
non-admin caller gets `403`.

## Server Configuration

The `server` object configures the HTTP server and authentication:

```yaml
server:
  port: 8080
  apiAuth: !<bearer>
    tokens:
      admin:
        token: "admin-token"
        role: admin
      user:
        token: "user-token"
        grants:
          - agents: ["*"]
            actions: [run]
```

<details>
<summary>JSON format</summary>

```json
{
  "server": {
    "port": 8080,
    "apiAuth": {
      "type": "bearer",
      "tokens": {
        "admin": { "token": "admin-token", "role": "admin" },
        "user":  { "token": "user-token",  "grants": [{ "agents": ["*"], "actions": ["run"] }] }
      }
    }
  }
}
```
</details>

### Server Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `port` | `Int` | No | `8080` | HTTP server port |
| `apiAuth` | `AuthConfig` | **Yes** | — | API authentication configuration. Omitting it is a **startup error** — running without auth requires the explicit `!<unsecured>` opt-in. See [ADR-0036](../docs/adr/0036-explicit-api-auth-opt-in.md). |
| `rateLimit` | `RateLimitConfig` | No | `{limit=1000, refillPeriod=60}` | Global API rate limiting: a server-wide ceiling, one bucket shared by every request; the `/livez` and `/readyz` probes are exempt and never answer `429` (see [Liveness & readiness probes](#liveness--readiness-probes)), and `POST /api/v1/auth/login` and runner requests carrying a valid domain token use their own buckets; the same size also caps all logins together (see [Rate Limiting](#rate-limiting)) |
| `perCallerRateLimit` | `RateLimitConfig` | No | `{limit=300, refillPeriod=60}` | Per-caller rate limit on the secured `/api/v1` routes, keyed on the authenticated identity so each token/key/user gets its own bucket. Layered *under* `rateLimit` (both apply). Raise `limit` to effectively disable. See [Rate Limiting](#rate-limiting) and [ADR-0014](../docs/adr/0014-per-caller-rate-limiting.md). |
| `loginRateLimit` | `RateLimitConfig` | No | `{limit=5, refillPeriod=60}` | Stricter per-IP cap on `POST /api/v1/auth/login` to slow brute-force guessing. Keyed on the direct TCP peer — behind a reverse proxy that peer is the proxy, so it becomes one shared bucket for all clients; raise `limit` for a busy multi-user or proxied deployment. See [Rate Limiting](#rate-limiting). |
| `corsAllowedOrigins` | `List<String>` | No | `[]` | Allowed CORS origins, each `http(s)://host[:port]` with no path and a port of 1–65535 if any (e.g. `["http://console.example.com"]`). An entry may contain `@SYSENV:VAR` placeholders, resolved at startup like the auth secrets, so the console's port can come from the environment: `"http://localhost:@SYSENV:FRONTEND_PORT"`. An unset variable, or an entry that does not resolve to an origin, is a **startup error**. Unlike `globalEnv`, a placeholder here does not mark the value secret. Empty list = any host **without** credential reflection (development default; a `*` response can never carry cookies). Pinned origins enable credentialed CORS. Under a `Password` (cookie) auth scheme an empty list is a **startup error** — cookie auth needs credentialed CORS, so explicit origins are required. See [ADR-0016](../docs/adr/0016-cookie-auth-csrf-protection.md). |
| `maxProcesses` | `Int` | No | `10000` | Maximum number of process rows retained. At the cap a new run evicts the oldest **terminal** row (and deletes its workspace); if none can be evicted the run is refused with `503`. This is the hard ceiling on retention — raising an agent's `workspace.retain.recordFor` only keeps records until this cap forces them out, so raise both together if you want a long record history. |
| `maxAgentInvocationDepth` | `Int` | No | `5` | Maximum allowed depth of nested `runAgent` invocations. A root run counts as depth 1; an agent that runs another makes that child depth 2, and so on. When a `runAgent` command would exceed the limit, the child run is refused and the command fails (exit 255). Cycles are also refused: if the target agent name already appears in the upstream chain, it is rejected even when depth is below the cap (so `A → B → A` does not waste retries on a guaranteed-loop). The chain is threaded from parent to child by `runAgent`'s in-process dispatch and exposed inside the called process as `HMR_INVOCATION_CHAIN`. Direct external API calls always start a fresh chain at depth 1. See [ADR-0008](../docs/adr/0008-recursion-guard-via-invocation-chain-header.md) and [ADR-0030](../docs/adr/0030-runagent-in-process-dispatch.md). |
| `shutdownDrainSec` | `Int` | No | `30` | Seconds to wait for in-flight `ProcessExecutor` jobs (agent executions already running when SIGTERM arrives) and background LLM cost settlements to finish before the Ktor engine stops — see [`server.shutdownDrainSec`](#servershutdowndrainsec), and [LLM price](#llm-price) for the higher floor a configured `llmPrice.sessionSpend.callsPath` (or request-window keys) needs. |
| `requireClaudeCodeVersion` | `String?` | No | `null` | If set, every successful Claude run must report this exact value in its `system` event's `claude_code_version` field. A mismatch (or a missing field when the pin is set) fails the run with `ClaudeCliVersionMismatchException` and is *not* retried. Leave null to skip the check — see [`server.requireClaudeCodeVersion`](#serverrequireclaudecodeversion). |
| `claudeDropSystemSubtypes` | `List<String>` | No | `["thinking_tokens"]` | `system`-event subtypes dropped from a Claude step's stdout (`cmd_N.out`) before it is written, so `maxOutputBytes` counts conversation rather than telemetry — see [`server.claudeDropSystemSubtypes`](#serverclaudedropsystemsubtypes). |
| `maxRequestBodyBytes` | `Long` | No | `10485760` (10 MiB) | Maximum request body size in bytes; requests over the cap get `413 Payload Too Large`. Exempted: `/api/v1/runner/sessions/.../tar`, bounded instead by the `maxTransferBytes` of the domain whose token the request presents (this cap still applies when no domain token matches), `/api/v1/runner/sessions/.../output`, bounded by the 1 MiB output chunk cap, and `/api/v1/runner/sessions/.../next`, bounded at 64 KiB — see [External runners (pull mode)](#external-runners-pull-mode). |
| `maxArtifactBytes` | `Long` | No | `104857600` (100 MiB) | Maximum size in bytes of a single artifact file served by `GET /api/v1/artifacts`. Files larger than this are rejected pre-flight with `413 Payload Too Large` so a misbehaving agent cannot turn an artifact fetch into a sustained bandwidth/time DoS. Range / partial-download support is not implemented — raise the cap (or split the artifact at the agent) if a use case needs >100 MiB downloads. |
| `maxRetainedArtifactBytes` | `Long` | No | `16777216` (16 MiB) | Per-run byte cap on artifacts retained past the workspace purge (`workspace.retain.artifacts`). The retained set is agent-written and lives until the record dies, so the operator's disk ceiling is `maxProcesses` x this value. Overflow keeps what fits in declared-glob order and flags the record `retainedTruncated`. See [ADR-0053](../docs/adr/0053-declared-artifacts-ride-the-record-lifetime.md). |
| `maxConfigFileBytes` | `Long` | No | `1048576` (1 MiB) | Maximum size in bytes of a single agent definition file (`agent.json` / `agent.yaml`) or a file read or written via the agent files API. The top-level config file is not governed by this setting: it is capped at a fixed 1 MiB, checked before the config (and so this value) is read. Files larger than the cap are rejected pre-flight, before kaml / kotlinx.serialization parse them. Real agent definitions are kilobytes; this cap bounds DoS exposure from a pathological YAML (oversized strings, huge anchor lists) regardless of what the underlying parser allows. The kaml parser ships on top of snakeyaml-engine 2.7, which already enforces `nestingDepthLimit=50` and `maxAliasesForCollections=50`, so deep-nesting / billion-laughs vectors are already bounded — this cap is the missing piece for total input size. Raise it if your agent definitions legitimately exceed 1 MiB (rare). |
| `maxOutputBytes` | `Long` | No | `10485760` (10 MiB) | Maximum size in bytes of a single command's stdout file (`cmd_N.out`) or stderr file (`cmd_N.err`). Each stream is capped independently at this value; either exceeding the cap kills the command via the same `destroyForcibly` + descendants path used for TTL timeouts, appends an `[HOMER] Output cap exceeded ($cap bytes)` line to the command's stderr, and surfaces through the per-command `ExecutionInfo.outputTruncated` flag returned by `GET /api/v1/status/{processId}`. The cap is checked every 250 ms alongside the TTL check, so the effective on-disk size at kill time may exceed the cap by one polling window's worth of writes — treat it as a soft ceiling. For Claude steps, `system` events listed in `claudeDropSystemSubtypes` are dropped before they reach the file and so never count toward this cap. Default 10 MiB. |
| `claudeStreamPollMs` | `Int` | No | `150` | Polling interval (ms) of the stdout file poll behind `GET /api/v1/stream/processes/{id}/llm-events`. Lower = lower first-event latency, higher = less CPU. Coerced to `50..2000` at startup. |
| `mcp` | `McpServerConfig` | No | `{enabled=false, maxWaitSec=300, maxInlineArtifactBytes=65536}` | Streamable HTTP MCP endpoint at `/mcp` exposing opted-in agents as MCP tools — see [MCP endpoint](#mcp-endpoint). |
| `questions` | `QuestionsConfig` | No | `{maxWaitSec=86400, maxOpenPerProcess=10, maxTextLength=10000, maxOptions=20, maxConcurrentWaitsPerProcess=32}` | Human-in-the-loop questions asked by running commands — see [Operator questions](#operator-questions). |
| `messages` | `RunMessagesConfig` | No | `{maxTextLength=10000, maxPerRun=100}` | Per-run inbox limits for operator messages sent to a running run — see [Run messages](#run-messages). |
| `dispatch` | `DispatchConfig` | No | `{maxPending=100, defaultTtlSec=null, sweepIntervalSec=60, settledRetentionSec=604800, resultTokenTtlSec=3600, maxJoinSubs=32}` | Ask-and-dispatch bounds and TTLs — see [Ask-and-dispatch](#ask-and-dispatch). |
| `langGraph` | `LangGraphConfig` | No | `{stateDir=null}` | LangGraph runtime settings (durable checkpoint root) — see [LangGraph](#langgraph). |
| `agentGrants` | `Map<String, List<Grant>>` | No | `{}` | Per-agent delegation: which target agents an agent's own nested dispatches may reach, keyed by the dispatching agent's name — see [Grants and roles](#grants-and-roles). |
| `localRunner` | `LocalRunnerConfig` | No | `{enabled=true, tags=[], concurrency=null}` | The built-in `local` runner: `enabled` (`false` registers none, so no run executes in the server's container), the `tags` it serves for `runnerTags` agents and `concurrency` (runs at once; `null` = unbounded). `tags` and `concurrency` are refused while `enabled` is `false`. See [Runner claim queue](#runner-claim-queue). |
| `runnerClaimTimeoutSec` | `Int` | No | `300` | Seconds a run waits in the claim queue for a runner with a free permit before it fails; agents override with `runnerClaimTimeoutSec`. See [Runner claim queue](#runner-claim-queue). |
| `maxPendingRunnerClaims` | `Int` | No | `1000` | Bound on runs waiting in the claim queue; a run arriving at the bound fails immediately. See [Runner claim queue](#runner-claim-queue). |
| `maintenance` | `MaintenanceConfig` | No | `{maxQueuedStarts=500, maxQueuedStartsPerPrincipal=50, queueWaitSec=600, releaseConcurrency=4}` | Bounds of the admin maintenance-mode queue: how many starts may wait, how much of it one caller may hold, how long a start may wait, and how many are released at a time. See [Maintenance mode](#maintenance-mode). |
| `maintenance.maxQueuedStarts` | `Int` | No | `500` | Bound on starts waiting in the maintenance queue; a start arriving at the bound fails immediately with `429`. See [Maintenance mode](#maintenance-mode). |
| `maintenance.maxQueuedStartsPerPrincipal` | `Int` | No | `50` | Bound on one caller's share of the maintenance queue, so a single caller cannot fill it and lock everyone else out. See [Maintenance mode](#maintenance-mode). |
| `maintenance.queueWaitSec` | `Int` | No | `600` | How long a start may wait in the maintenance queue before it is dropped and answers `503`. The wait is not replayed once the mode goes off. See [Maintenance mode](#maintenance-mode). |
| `maintenance.releaseConcurrency` | `Int` | No | `4` | How many queued starts are released at a time when the mode goes off, so the end of maintenance does not resume every waiter in one burst. See [Maintenance mode](#maintenance-mode). |

### `server.shutdownDrainSec`

Seconds to wait for in-flight `ProcessExecutor` jobs (agent executions
already in flight when SIGTERM arrives) and background LLM cost
settlements (ADR-0089) to finish before the Ktor engine stops. Default `30`. On a Kubernetes rolling update this should
be ≤ `terminationGracePeriodSeconds`. Set lower for ephemeral debug
containers, higher if your typical agent takes longer than 30s.

A drain timeout is logged at WARN; the engine then stops regardless,
mirroring k8s `terminationGracePeriodSeconds` semantics.

### Maintenance mode

An operator switch for the window in which Homer itself is being replaced. It is
**not** a config value — it is turned on and off at runtime by an admin through
`POST /api/v1/maintenance` (see [`README.md`](README.md)), persisted in the
`server_meta` table so it survives a restart, and reported on `GET /api/v1/health`
as `maintenance` and by `GET /api/v1/maintenance` with the queue depth.

While it is on:

- **Cron fires are skipped.** A schedule that comes due during the window is
  dropped, never replayed — `POST /api/v1/agents/{name}/cron-run` (the console's
  Run button) still fires, but the run queues like any other start.
- **Every other agent start waits in a queue** — HTTP, MCP, dispatch,
  continuation and `!<agent>` hops alike, since they all converge on the same
  admission gate above the claim queue. A start that waits longer than
  `maintenance.queueWaitSec` answers `503`; one that arrives at a full queue
  answers `429`. Neither is replayed.
- **A run already `WORKING` keeps running.** This is an admission gate, not a
  kill switch, and it does not touch `/api/v1/readyz`.

When the mode goes off the queue drains in arrival order within one release
drain, at most `maintenance.releaseConcurrency` starts at a time; rapid
re-toggles run drains concurrently, so the order across drains is not
guaranteed. A queued start is only
*parked*: it re-runs its own cost-cap, cooldown, invocation-chain and input
checks after it is released, against the state in force then.

See [ADR-0092](../docs/adr/0092-maintenance-mode-queues-starts-and-skips-cron.md).

```yaml
server:
  apiAuth: !<unsecured>
  maintenance:
    maxQueuedStarts: 500
    maxQueuedStartsPerPrincipal: 50
    queueWaitSec: 600
    releaseConcurrency: 4
```

### `server.requireClaudeCodeVersion`

Optional Claude CLI version pin. When non-null, the executor compares
the value reported in the `system` event's `claude_code_version` field
against this string after a successful Claude run. A mismatch (or the
field being absent when a pin is set) fails the run with
`ClaudeCliVersionMismatchException`; this is **not** retried — a CLI
upgrade cannot happen between attempts of the same invocation, so
retrying wastes budget. Default `null` (no check).

Set this to the Claude CLI version your agents were tested against so
a silent upgrade on the host cannot reshape stdout in ways the executor
still parses but the downstream agents do not expect.

```yaml
server:
  requireClaudeCodeVersion: "1.0.34"
```

### `server.claudeDropSystemSubtypes`

`system`-event subtypes that a Claude step's stdout filter drops before the
line reaches `cmd_N.out`. Only events with `"type":"system"` are ever
eligible; `result`, `assistant` and `user` events are never touched, so
`resultEnvParam` mapping, cost accounting, the live tail and Langfuse
telemetry see the same conversation as before.

While the filter is active the cap is measured on the written file, which lags the
CLI by at most one buffered line (up to 1 MiB) — within the soft ceiling
`maxOutputBytes` already documents.

Why this exists: on a third-party model behind an LLM proxy, Claude Code
cannot read real usage from the provider and emits one
`{"type":"system","subtype":"thinking_tokens",…}` progress event per one
or two thinking tokens — about 195 bytes each. A single implementation
step can write tens of thousands of them, and they were 93 % of a
`cmd_N.out` that hit the 10 MiB `maxOutputBytes` kill. Nothing in Homer
reads them. Native Anthropic models never emit the event, so the default
changes nothing for them.

Every attempt whose stdout had lines dropped gets one line per subtype
appended to its stderr file (`cmd_N.err`), so the volume stays
diagnosable:

```text
[HOMER] Dropped 49813 system/thinking_tokens events (9731234 bytes) from stdout
```

Entries must match `^[a-zA-Z0-9_]+$`. `init` is refused because that
event carries `claude_code_version`, which
[`server.requireClaudeCodeVersion`](#serverrequireclaudecodeversion)
reads. Set `[]` to write the CLI's stdout unfiltered.

```yaml
server:
  claudeDropSystemSubtypes: ["thinking_tokens"]   # default
```

See [ADR-0063](../docs/adr/0063-filter-claude-stdout-at-write-time.md).

### MCP endpoint

`server.mcp` configures the Streamable HTTP MCP endpoint at `POST /mcp`
(same port as the REST API). When enabled, agents with
`mcp: { enabled: true }` in their definition (see
[AGENT.md](AGENT.md#mcp-exposure)) are exposed as MCP tools, alongside
four always-registered run-management tools: `get_run_info`,
`list_artifacts`, `get_artifact`, and `cancel_run`. Disabled by default.

```yaml
server:
  mcp:
    enabled: true
    maxWaitSec: 300              # clamp for the per-call _wait argument (default 300)
    maxInlineArtifactBytes: 65536  # get_artifact inline cap in bytes (default 64 KiB)
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `enabled` | `Boolean` | No | `false` | Serve the `/mcp` endpoint. |
| `maxWaitSec` | `Int` | No | `300` | Upper bound (seconds) for the per-call `_wait` argument on agent tools; requested waits are clamped to `0..maxWaitSec`. |
| `maxInlineArtifactBytes` | `Long` | No | `65536` | Maximum artifact size (bytes) the `get_artifact` tool inlines into a tool result; larger files are truncated to this many bytes. |

The endpoint sits inside the same authentication block as the REST API:
the MCP client must send the same credentials (typically
`Authorization: Bearer <token>`). Any authenticated principal reaches
`/mcp`; the tool list it sees contains only the agent tools whose agent
the principal holds `run` on. The run-management tools are listed for
everyone but apply the same visibility rules as the REST API (see
[Process ownership and visibility](#process-ownership-and-visibility)).

**Reverse-proxy note:** a tool call with `_wait` holds the HTTP request
open for up to `maxWaitSec` seconds. If Homer sits behind a reverse
proxy, make sure the proxy's idle/read timeout exceeds `maxWaitSec`
(or lower `maxWaitSec` to fit the proxy).

### Operator questions

`server.questions` bounds the human-in-the-loop question channel
(see [AGENT.md](AGENT.md) for how agents ask questions and
[ADR-0019](../docs/adr/0019-human-in-the-loop-questions.md) for the
design). Questions are persisted in the SQLite database, like process
state. On restart, the open questions of runs the restart interrupted are
expired (dispatch-bound questions survive — see
[Ask-and-dispatch](#ask-and-dispatch)). Per-process callback tokens are held
in memory only, so a helper blocked on a question when the server restarts
gets `401` and exits with an error.

```yaml
server:
  questions:
    maxWaitSec: 86400      # clamp for one answer-wait hold (default 1 day)
    maxOpenPerProcess: 10  # open-question cap per process (default 10)
    maxTextLength: 10000   # question/option length cap in characters (default 10000)
    maxOptions: 20         # options per question (default 20)
    maxConcurrentWaitsPerProcess: 32  # held answer-waits per process (default 32)
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `maxWaitSec` | `Int` | No | `86400` (1 day) | Upper bound (seconds) for a single answer-wait. Caller-supplied waits (REST `waitSec`, helper `--wait`/`--ttl`) are clamped to this value. Waits are resumable — a timed-out wait returns the still-open question id and the caller waits again — so this caps one HTTP hold, not the total time a question may stay open. |
| `maxOpenPerProcess` | `Int` | No | `10` | Maximum simultaneously open questions per process; further asks are rejected with `429`. |
| `maxTextLength` | `Int` | No | `10000` | Maximum length (characters) of the question text and of each option; longer payloads are rejected with `400`. Questions are stored until purge, so this bounds workspace-controlled input the way the `max*Bytes` caps bound other inputs. |
| `maxOptions` | `Int` | No | `20` | Maximum number of options per question; more are rejected with `400`. |
| `maxConcurrentWaitsPerProcess` | `Int` | No | `32` | Maximum simultaneously held answer-waits per process; further waits are rejected with `429`. Each held wait pins a connection and a coroutine for up to `maxWaitSec`, so this bounds what a workspace command can pin open. |

**Reverse-proxy note:** an answer-wait holds its HTTP request open for
up to `maxWaitSec` seconds, same as the MCP `_wait` note above —
either raise the proxy's idle/read timeout or lower `maxWaitSec`.

### Run messages

`server.messages` bounds the per-run inbox: operator messages sent to a
running run, which the run takes through a hook or the MCP tool (see
[ADR-0097](../docs/adr/0097-per-run-inbox.md)). Messages are persisted in the
SQLite database and purged with the run record. A message still queued when
the run reaches a terminal state, or when a restart leaves it `UNKNOWN`,
becomes `UNDELIVERED`. Message text never appears in logs, spans or error
text.

```yaml
server:
  messages:
    maxTextLength: 10000  # one message, in characters (default 10000)
    maxPerRun: 100        # every stored message of a run, in any state (default 100)
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `maxTextLength` | `Int` | No | `10000` | Maximum length (characters) of one message; longer text is rejected with `400`. |
| `maxPerRun` | `Int` | No | `100` | Maximum messages stored for one run, whatever their state; further sends are rejected with `429`. |

### Ask-and-dispatch

`server.dispatch` configures the ask-and-dispatch feature: running commands
register a question plus a target agent and parameters
(`POST /api/v1/callback/dispatches`, `homer-ask ask-and-dispatch`, or the
`ask_and_dispatch` MCP tool). When the operator answers, Homer fires the target
run automatically. See [ADR-0021](../docs/adr/0021-ask-and-dispatch.md) for the
design.

```yaml
server:
  dispatch:
    maxPending: 100           # global cap on PENDING dispatches, and separately on PENDING continuations (default 100)
    defaultTtlSec: 86400      # default per-dispatch TTL in seconds (default null = no TTL)
    sweepIntervalSec: 60      # how often the TTL sweep runs, in seconds (default 60)
    settledRetentionSec: 604800  # delete settled dispatches/continuations this long after creation (default 7 days, floor 3600)
    resultTokenTtlSec: 3600   # TTL for read-by-reference result tokens (default 3600, floor 60)
    maxJoinSubs: 32           # max subs a single /callback/joins request may fan out to (default 32)
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `maxPending` | `Int` | No | `100` | Global cap on the number of simultaneously PENDING dispatches and continuations (the pending cap is counted separately for each). A new dispatch or continuation that would exceed its cap is rejected with `429`. |
| `defaultTtlSec` | `Int?` | No | `null` | Default TTL (seconds) applied to dispatches and continuations (including joins) that do not supply their own `ttlSec`. `null` = no default TTL (they stay PENDING until answered, fired, or cancelled). |
| `sweepIntervalSec` | `Int` | No | `60` | How often (seconds) the background sweeps run to cancel PENDING dispatches (and expire PENDING continuations) whose `expires_at` has passed. |
| `settledRetentionSec` | `Long` | No | `604800` | How long (seconds) a settled dispatch or continuation — any status but `PENDING` and `DISPATCHING` — is kept, measured from its `createdAt`; the dispatch and continuation sweeps then delete it, with its dispatch-bound question or join watch rows. A row that settled late is therefore kept for less than this after settling. Minimum `3600`. |
| `resultTokenTtlSec` | `Int` | No | `3600` | TTL (seconds) for read-by-reference result tokens minted when a watched sub-process's result exceeds the inline cap. Floored at `60` so a token outlives the resumed parent's first segment. See [ADR-0025](../docs/adr/0025-capability-token-result-read.md). |
| `maxJoinSubs` | `Int` | No | `32` | Maximum sub-agents a single `POST /api/v1/callback/joins` request may fan out to. Each sub starts a process immediately, so this caps synchronous per-request fan-out; an over-limit request is rejected with `400`. |

**Dispatch-bound questions** created via this feature are decoupled from the
asking process's lifecycle: they are not expired when the asker goes terminal,
is purged, or is evicted. They are cleaned up only when the dispatch is
answered, cancelled, or TTL-swept. Starting from schema v26 the settled-row
prune is a further cleanup path: a settled dispatch is deleted with its
question once its `createdAt` passes `server.dispatch.settledRetentionSec`.

### LangGraph

`server.langGraph` configures the LangGraph runtime.

```yaml
server:
  langGraph:
    stateDir: /var/lib/homer/langgraph  # durable checkpoint dir (default null = beside databasePath)
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `stateDir` | `String?` | No | `null` | Durable **root** for LangGraph SQLite checkpoints. It must live **outside** the ephemeral per-process workspace so continuations survive across runs. When `null`, the root is derived as a `langgraph/` subdirectory beside `databasePath` (i.e. `dirname(databasePath)/langgraph`). Checkpoints are stored one level down, per owner — see below. |

#### Checkpoint store layout (per owner)

Checkpoints live in `<stateDir>/<owner>/checkpoints.sqlite`, where `<owner>` is
the principal that caused the run ([ADR-0038](../docs/adr/0038-process-ownership.md)).
Runs with no owner — unsecured mode, `agentdebug` — use `_unowned`.

A principal name is used as the directory name verbatim only when it is
lowercase, at most 64 characters, drawn from `[a-z0-9._-]`, and does not start
with `_` or `.`. Anything else gets a `_h-<hash>` directory. That keeps every
distinct principal in its own store: names cannot reach the reserved `_unowned`
bucket or spell a `_h-` hash (a verbatim name never starts with `_`), names
differing only in case cannot share a directory on a case-insensitive
filesystem, and `..` or an embedded separator cannot escape the root.

The split is a **security boundary**, not a layout preference. A run's thread id
is read from the `langgraph.json` artifact in its own workspace, which agent code
can write; scoping the store per owner means a forged thread id can only ever
address the forging principal's own checkpoints. See
[ADR-0046](../docs/adr/0046-owner-scoped-langgraph-checkpoints.md).

Owner is copied down run chains, so resumes, continuations, and join
continuations targeting another agent all resolve to the same directory as the
run that created the thread.

**Upgrading:** checkpoints written before this layout sit directly in
`<stateDir>/checkpoints.sqlite` and are no longer read. Threads parked at the
time of the upgrade become unresumable unless you move that file into the right
owner directory (`mv <stateDir>/checkpoints.sqlite <stateDir>/<owner>/`) — pick
the owner whose runs created those threads. Nothing else needs migrating; new
runs create their own store on first use.

### Liveness & readiness probes

Homer exposes two probe endpoints intended for Kubernetes / reverse-proxy
health checking:

| Endpoint | Status | Meaning |
| --- | --- | --- |
| `GET /api/v1/livez` | Always `200` | Process is alive. Wire your `livenessProbe` here. Failing this would restart the pod. |
| `GET /api/v1/readyz` | `200` in steady state; `503` once `ApplicationStopping` fires (drain in progress) | Pod should receive new traffic. Wire your `readinessProbe` (and any upstream reverse-proxy health check) here. |

Both probes are **exempt from every rate limiter** — the global
[`rateLimit`](#rate-limiting) bucket and the per-caller one — and never answer
`429`. The global bucket is unauthenticated and shared by every non-probe request, so
without the exemption any client able to reach the port could drain it and turn
`/livez` into a `429`, which the kubelet reads as a liveness failure and answers
by restarting the pod and killing every in-flight run. With the exemption the
"Always `200`" row above holds under load.

**Do NOT wire `livenessProbe` to `/readyz` or to `/health`.** `/readyz`
returns `503` during drain on purpose, so the load balancer pulls the
pod out of rotation while in-flight jobs finish. If the kubelet sees
that 503 as a liveness failure it will **restart the pod mid-drain** —
exactly the failure that defeats [`server.shutdownDrainSec`](#servershutdowndrainsec).

`/health` is a rich JSON status endpoint for the console (runner
summary + sweep status) and is **not** a probe. It does not switch to
503 during drain. Use `/livez` and `/readyz` for probes; use `/health`
for human / dashboard consumption.

Minimal Kubernetes manifest snippet:

```yaml
livenessProbe:
  httpGet: { path: /api/v1/livez, port: 8080 }
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /api/v1/readyz, port: 8080 }
  periodSeconds: 5
terminationGracePeriodSeconds: 60  # ≥ server.shutdownDrainSec
```

For reverse-proxy setups (nginx / Caddy / HAProxy), point the upstream
health check at `/api/v1/readyz` so the proxy stops sending requests
the moment a drain starts.

## Authentication Configuration

Authentication is configured via the `apiAuth` field, which is a sealed class with four implementations (`bearer`, `hmac`, `password`, `unsecured`) plus `combined` for serving multiple schemes from a single deployment.

`apiAuth` is **required**: a config that omits it fails startup instead of silently running unsecured. To run without authentication, opt in explicitly with `!<unsecured>`. See [ADR-0036](../docs/adr/0036-explicit-api-auth-opt-in.md).

Every scheme authenticates a **principal** (who is this caller); what that
principal may do is a separate, uniform layer described next.

### Grants and roles

Once a request is authenticated, a single `Authorizer` service decides what
it may do. Homer never parses request paths for authorization — every check
is `hasAction(principal, action, agentName)` or the `admin` role. See
[ADR-0037](../docs/adr/0037-grants-authorization.md).

Every principal — a bearer token, an HMAC key, or a password user — declares
either `role: admin` or a non-empty `grants` list (or both; `role` wins):

```yaml
grants:
  - agents: ["deploy-*"]      # name patterns; "*" = all agents
    actions: [run, read, control, edit]
```

**Actions** (`Action`):

| Action | Grants on the matched agent(s) |
|---|---|
| `run` | Invoke the agent (`GET/POST /api/v1/agent/{name}`) plus unconditional visibility/control of runs the caller itself started. |
| `read` | See all runs of the agent. |
| `control` | Kill/answer runs of the agent. For runs the caller did not start, `control` also needs visibility, so pair it with `read`. |
| `edit` | File CRUD under the agent's directory, and creating a new agent whose name matches the pattern. Writing `agent.yaml`/`agent.json` that declares a name other than the target agent also requires edit on the declared name. **`edit` is authority over what the agent runs**: `agent.yaml`/`agent.json` are executable definitions, so an `edit` holder can change the agent's commands or cron and those changes then run on schedule or when someone with `run` triggers the agent — grant `edit` only to principals trusted to define behavior. (`edit` cannot reach *other* agents' authority: nested-dispatch hops are re-checked against operator-controlled grants, not the editable definition.) |
| `debug` | `POST /api/v1/agents/{name}/debug` (arbitrary command execution). Never implied by anything except `role: admin` — a `["*"]`-style `agents` grant without `debug` in `actions` still cannot reach it. |

**`agents` patterns** use the same glob rules as agent names: `*` matches
all agents, a trailing `*` (e.g. `"deploy-*"`) matches by prefix, anything
else is an exact match.

**`role: admin`** grants every action on every agent plus server-wide
operations: `POST /api/v1/agents/reload`, `GET /api/v1/costs*`, the
server `GET /api/v1/status`, `GET /api/v1/runners`,
`GET /api/v1/runners/queue`, and `POST /api/v1/agents/{agentName}/cron-run`
(a non-admin gets `404` there, the same as an unknown agent; the others
answer `403`), plus visibility of rows with no matching owner. It is mutually exclusive with a meaningful `grants` list — if
`role` is set, `grants` is ignored.

**Validation at startup:** a principal with neither `role: admin` nor a
non-empty `grants` list fails startup (it could authenticate but do
nothing); an unknown action name fails startup; `agents` patterns follow
the glob rules above.

**`server.agentGrants`** — per-agent delegation: which target agents (and
actions) an agent's own dispatches (`RunAgent`, ask-and-dispatch, etc.) may
reach, keyed by the dispatching agent's name:

```yaml
server:
  agentGrants:
    router:
      - agents: ["worker-*"]
        actions: [run]
```

Agent *definitions* carry zero authority on their own — only
`server.agentGrants` (an operator-controlled, server-config setting) can
delegate one agent's dispatches to reach another.

**Denials hide agent existence.** A caller holding no grant at all on an
agent gets the same `404 Agent not found` as for an agent that does not
exist — on run, debug, and LangGraph inspect alike — so a probe cannot
enumerate agents. A caller who holds some grant on the agent (and so already
sees it in `GET /api/v1/agents`) gets `403` for an action it lacks. See
[ADR-0047](../docs/adr/0047-security-by-design-and-threat-model.md) rule 3.

**`agentGrants` gates nested dispatch.** A hop `A → B` — via `RunAgent`
(`!<agent>`), ask-and-dispatch, `dispatchNow`, or a completion/join
continuation — is authorized iff the run's root principal holds `run` on
B (re-resolved live, so revoking a token immediately de-authorizes its
pending hops) **or** `B` is reachable from `A` via `agentGrants[A]`. A
cron-triggered chain has no root principal (`owner = system:cron`), so its
hops ride solely on `agentGrants`. A denied hop fails the same way other
dispatch-time failures do: `RunAgent` exits `255`; a dispatch/continuation
is marked `FAILED`; `dispatchNow` and dispatch/continuation registration
answer `404`, exactly like an unknown agent (and `RunAgent`'s stderr reads
`agent 'B' not found`), so a denied hop cannot confirm that B exists. See [ADR-0039](../docs/adr/0039-nested-dispatch-authority.md)
(supersedes the "any agent, no gate" clause of
[ADR-0030](../docs/adr/0030-runagent-in-process-dispatch.md)).

### Process ownership and visibility

Every run carries an `owner` — the principal that caused it, stamped at
creation and inherited by sub-runs, dispatches, and completion
continuations: the bearer token's config key verbatim, `hmac:{keyId}`,
`session:{username}`, `system:cron` for cron-triggered runs, or `null` in
unsecured mode / before this field existed. Process-scoped reads (status,
artifacts, questions, dispatches, continuations, the `/stream/processes` SSE)
are filtered by the union rule `admin ∪ owner ∪ read grant on the agent`.
Controls are stricter: kill and answer need `admin`, the owner, or a visible
run plus `control` on the agent; retry needs a visible run plus `run` on the
agent (owning the run alone is not enough).
Single-resource denials return `404`, identical to the not-found response
— existence is never revealed to a caller who can't see the row. See
[ADR-0038](../docs/adr/0038-process-ownership.md).

### Bearer Authentication

Enables token-based authentication. Each named token carries a `role` and/or
`grants`.

```yaml
apiAuth: !<bearer>
  tokens:
    admin:
      token: "@SYSENV:ADMIN_TOKEN"
      role: admin
    ci-runner:
      token: "@SYSENV:CI_TOKEN"
      grants:
        - agents: ["deploy-*"]
          actions: [run, read]
    readonly:
      token: "@SYSENV:RO_TOKEN"
      grants:
        - agents: ["*"]
          actions: [read]
```

<details>
<summary>JSON format</summary>

```json
{
  "apiAuth": {
    "type": "bearer",
    "tokens": {
      "admin": { "token": "@SYSENV:ADMIN_TOKEN", "role": "admin" },
      "ci":    { "token": "@SYSENV:CI_TOKEN", "grants": [{ "agents": ["deploy-*"], "actions": ["run", "read"] }] },
      "readonly": { "token": "@SYSENV:RO_TOKEN", "grants": [{ "agents": ["*"], "actions": ["read"] }] }
    }
  }
}
```
</details>

**Fields:**
- `tokens`: Map of `username → TokenConfig` with `token` (non-blank string; blank values — including `@SYSENV` references resolving to blank — are rejected at startup), `role` (`"admin"` or unset), and `grants` (see [Grants and roles](#grants-and-roles)). The map key becomes the principal name verbatim, so it must **not** start with the reserved `session:`, `hmac:`, or `system:` prefixes (claimed by cookie/session principals, HMAC key principals, and internal synthetic owners such as `system:cron` respectively); such a key fails startup.

**Response Codes:**
- `401 Unauthorized` — token not found in config
- `403 Forbidden` — token valid, but the caller lacks the action/agent grant for the requested route (message names the missing permission, e.g. `Not permitted to run 'X'`). A caller with no grant at all on the agent gets `404` instead — see [Grants and roles](#grants-and-roles).

**Usage:**
```bash
curl -H "Authorization: Bearer ci-token-value" \
  "http://localhost:8080/api/v1/agent/my-agent"
```

> **Legacy:** a plain-string token (`"username": "token-value"`) still
> parses, but carries no `role`/`grants` and is therefore rejected at
> startup by the same check that rejects `endpoints` (see
> [Migrating from `endpoints`](#migrating-from-endpoints)) — give it an
> explicit `role`/`grants` in object form instead.

### HMAC Authentication

Verifies inbound requests by recomputing an HMAC over the request body
(and optionally other request elements) using a shared secret. Designed
for webhook-style callers (e.g. **Vikunja** out of the box, GitHub by
adjusting fields). The signature header must carry the MAC alone (after an
optional fixed `prefix`), so senders that pack several values into one header
(Stripe's `t=…,v1=…`) or send a plain shared token instead of a MAC are not
supported.

```yaml
apiAuth: !<hmac>
  keys:
    vikunja:
      secret: "@SYSENV:VIKUNJA_WEBHOOK_SECRET"
      grants:
        - agents: [handle-vikunja-event]
          actions: [run]
      signatureHeader: "X-Vikunja-Signature"
```

<details>
<summary>JSON format</summary>

```json
{
  "apiAuth": {
    "type": "hmac",
    "keys": {
      "vikunja": {
        "secret": "@SYSENV:VIKUNJA_WEBHOOK_SECRET",
        "grants": [{ "agents": ["handle-vikunja-event"], "actions": ["run"] }],
        "signatureHeader": "X-Vikunja-Signature"
      }
    }
  }
}
```
</details>

**Fields:**

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `secret` | `String` | Yes | — | Shared secret; must not be blank (a `@SYSENV:VAR` resolving to blank is rejected at startup). |
| `role` | `String?` | No | `null` | `"admin"` or unset. See [Grants and roles](#grants-and-roles). |
| `grants` | `List<Grant>` | No | `[]` | See [Grants and roles](#grants-and-roles). |
| `signatureHeader` | `String` | No | `X-Homer-Signature` | Header carrying the signature. |
| `algorithm` | `sha1 \| sha256 \| sha512` | No | `sha256` | HMAC algorithm. |
| `encoding` | `hex \| base64` | No | `hex` | Encoding of the signature header value. |
| `prefix` | `String` | No | `""` | Required prefix stripped before decoding (e.g. `sha256=` for GitHub). |
| `payloadTemplate` | `String` | No | `{{body}}` | What gets HMACed. Tokens: `{{body}}`, `{{timestamp}}`, `{{method}}`, `{{path}}`, `{{query}}` (raw query string, no leading `?`). Query params are only authenticated when the template includes `{{query}}`. |
| `timestampHeader` | `String?` | No | `null` | If set, the header must hold an integer Unix timestamp — seconds, or milliseconds when the value is above 10^12. It is checked for staleness, and `payloadTemplate` **must** include `{{timestamp}}` (rejected at startup otherwise — an unsigned timestamp provides no replay protection). |
| `replayToleranceSec` | `Long` | No | `300` | Max skew vs server clock (seconds). Only enforced when `timestampHeader` is set. |

**Response codes:**
- `401 Unauthorized` — no matching key, MAC mismatch, or stale timestamp.
- `403 Forbidden` — MAC matched, but the key lacks the action/agent grant for the requested route (`404` when it holds no grant at all on the agent — see [Grants and roles](#grants-and-roles)).

**Provider examples:**

```yaml
# GitHub webhooks: sha256= prefix, hex
github:
  secret: "@SYSENV:GITHUB_WEBHOOK_SECRET"
  grants:
    - agents: [handle-github-push]
      actions: [run]
  signatureHeader: "X-Hub-Signature-256"
  prefix: "sha256="

# Generic timestamped sender: timestamp + body, base64
billing:
  secret: "@SYSENV:BILLING_WEBHOOK_SECRET"
  grants:
    - agents: [handle-billing-event]
      actions: [run]
  signatureHeader: "X-Billing-Signature"
  timestampHeader: "X-Billing-Timestamp"
  payloadTemplate: "{{timestamp}}.{{body}}"
  encoding: base64
```

**Replay protection:** present only when `timestampHeader` is configured.
The Vikunja preset has none (matches upstream), so transport-layer
security is your only replay defence. Homer logs a startup **WARN** for each
HMAC key without a `timestampHeader` so the absence of a replay window is
visible rather than silent.

When a `timestampHeader` **is** set, the template must MAC-bind it: an HMAC
key with `timestampHeader` but no `{{timestamp}}` in `payloadTemplate` is
rejected at startup, because an unsigned timestamp header is
attacker-controlled and a captured signature could be replayed indefinitely
with fresh values. Homer additionally keeps an in-memory
cache of recently-accepted signatures and rejects a signature that is presented
twice within its freshness window (± `replayToleranceSec`), so a captured
request cannot be replayed even inside the tolerance window. The cache is
bounded and single-node. **Implication:** a sender that legitimately retries the
*exact* same signed request within the window is treated as a replay — retries
should carry a fresh timestamp (and thus a fresh signature), which every
timestamped preset here already does.

**Signature scope (startup validation):** the default `payloadTemplate`
(`{{body}}`) binds only the body, so a signature is portable across every
agent the key can reach. Homer **rejects at startup** any HMAC key that
combines a template *without* `{{path}}` with grants reaching more than one
agent — `role: admin`, any `grants` entry whose `agents` contains a `*`
pattern, or `grants` naming two or more distinct exact agents. Resolve it
either way:

- narrow the key's `grants` to a **single** exact agent name (no `*`), e.g.
  `agents: [handle-webhook]`; or
- bind the path in the template, e.g. `payloadTemplate: "{{method}}.{{path}}.{{body}}"`
  (and include method/path in what the sender signs).

Query binding is **not** enforced (it stays opt-in), but Homer logs a startup
**WARN** for any HMAC key that can run an agent reading inputs from the query
string while its `payloadTemplate` omits `{{query}}` — a captured signature is
otherwise replayable with altered query parameters. Add `{{query}}` to the
template (e.g. `"{{method}}.{{path}}.{{query}}.{{body}}"`) when a reachable
agent has query-sourced inputs.

### Password Authentication

Username + bcrypt-hashed password authentication for browser
clients (the Homer console). A successful `POST /api/v1/auth/login`
sets an `HttpOnly` session cookie (`SameSite=Lax` by default, see
`cookieSameSite`) signed with an HMAC secret; subsequent requests are
authenticated by the cookie.
CLI / MCP / webhook clients keep using `bearer` (or `hmac`); the
two schemes coexist via `combined`. See
[ADR-0011](../docs/adr/0011-cookie-based-console-auth.md) for the
design rationale.

```yaml
apiAuth: !<password>
  users:
    admin:
      passwordHash: "@SYSENV:ADMIN_PASSWORD_HASH"
      role: admin
    readonly:
      passwordHash: "@SYSENV:READONLY_PASSWORD_HASH"
      grants:
        - agents: ["*"]
          actions: [read]
  sessionSecret: "@SYSENV:HOMER_SESSION_SECRET"
  sessionSecretPrevious: "@SYSENV:HOMER_SESSION_SECRET_PREVIOUS"
  sessionTtlMin: 720
  cookieName: "homer_session"
  cookieSecure: true
  cookieSameSite: "lax"
  bcryptCost: 12
```

<details>
<summary>JSON format</summary>

```json
{
  "apiAuth": {
    "type": "password",
    "users": {
      "admin": {
        "passwordHash": "@SYSENV:ADMIN_PASSWORD_HASH",
        "role": "admin"
      }
    },
    "sessionSecret": "@SYSENV:HOMER_SESSION_SECRET",
    "sessionTtlMin": 720,
    "cookieName": "homer_session",
    "cookieSecure": true,
    "cookieSameSite": "lax",
    "bcryptCost": 12
  }
}
```
</details>

**Fields (`AuthConfig.Password`):**

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `users` | `Map<String, PasswordUserConfig>` | Yes | — | At least one user; key is the username supplied at login. |
| `sessionSecret` | `String` | Yes | — | HMAC key used to sign the session cookie. Must be at least 32 characters (checked after `@SYSENV` resolution; startup fails otherwise). `@SYSENV:VAR` supported — generate with e.g. `openssl rand -hex 32`. |
| `sessionSecretPrevious` | `String?` | No | `null` | Previous HMAC key, accepted on read but never used for new signatures. Same 32-character minimum. Set during rotation, drop after `sessionTtlMin` elapses. |
| `sessionTtlMin` | `Int` | No | `720` | Cookie `Max-Age` and server-side `exp` claim, in minutes. |
| `cookieName` | `String` | No | `homer_session` | Cookie name. |
| `cookieSecure` | `Boolean` | No | `true` | Sets the cookie `Secure` flag. Set `false` only for non-localhost plain-HTTP dev. |
| `cookieSameSite` | `lax` \| `none` \| `strict` | No | `lax` | `SameSite` attribute of the session cookie. Use `none` when the console is served from a different origin than the API (cross-origin cookie auth); `none` requires `cookieSecure: true` (a startup error otherwise) and HTTPS. See [Cross-origin / multi-instance consoles](#cross-origin--multi-instance-consoles). |
| `bcryptCost` | `Int` | No | `12` | bcrypt work factor (4–15). Higher = slower verify; 12 keeps unknown-user login latency around ~150 ms on commodity hardware. |

**Fields (`PasswordUserConfig`):**

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `passwordHash` | `String` | Yes | — | bcrypt hash (`$2a$...`). Generate with `./agentdebug.sh hash-password`. |
| `role` | `String?` | No | `null` | `"admin"` or unset. See [Grants and roles](#grants-and-roles). |
| `grants` | `List<Grant>` | No | `[]` | See [Grants and roles](#grants-and-roles). |

**Endpoints added by this scheme:**

| Method | Path | Auth required | Notes |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | None | Body: `{ "username": "...", "password": "..." }`. Rate-limited per remote IP via `server.loginRateLimit` (default 5/min). |
| `POST` | `/api/v1/auth/logout` | None (idempotent) | Clears the session cookie; 204. |
| `GET`  | `/api/v1/auth/me` | Valid cookie | Returns `{ username, role, grants }`. |

These three `/auth/*` routes are mounted **outside** the per-user
grant checks by design: `/login` is unauthenticated, `/logout`
must be idempotent against a stale cookie, and `/me` reads the cookie
directly so it isn't mis-authenticated as a bearer principal under a
combined deployment. A user with only a narrow `read` grant can still
call `/auth/me` to discover their own identity and capabilities —
that's intentional, and it's the console's capability source. See
ADR-0011 invariant 7.

**Generating a hash:**

```bash
./agentdebug.sh hash-password           # prompts twice, no echo
./agentdebug.sh hash-password --cost 12 # explicit cost
echo 'plaintext' | ./agentdebug.sh hash-password   # for scripting
```

The bcrypt hash is the only value stored in `homer-config.yaml` —
never paste a plaintext password into the file.

**Rotation:** to rotate `sessionSecret` without invalidating all
in-flight sessions:
1. Set `sessionSecretPrevious` to the current secret and
   `sessionSecret` to the new value. Deploy.
2. Wait at least `sessionTtlMin` minutes (default 12 hours).
3. Remove `sessionSecretPrevious`. Deploy.

`sessionSecretPrevious` is only consulted when **verifying** an
incoming cookie — new logins are always signed with `sessionSecret`.
Dropping `sessionSecretPrevious` before all cookies minted under the
old secret have expired will invalidate every still-valid session
that was issued before the rotation. Wait the full `sessionTtlMin`
before step 3.

**Response codes:**
- `401 Unauthorized` — cookie missing, expired, signature invalid, or user
  no longer in `users`.
- `403 Forbidden` — cookie valid but the caller lacks the action/agent grant
  for the requested route (`404` when it holds no grant at all on the agent —
  see [Grants and roles](#grants-and-roles)).
- `403 Forbidden` — a session-authenticated request (any method, `GET`
  included) without a non-empty `X-Homer-CSRF` header. See
  [ADR-0016](../docs/adr/0016-cookie-auth-csrf-protection.md).

#### Cross-origin / multi-instance consoles

A single console deployment can point at several Homer instances and switch
between them. Because the console is a browser SPA, every request it makes is
subject to the **backend's** CORS policy, and cookie auth additionally needs the
session cookie to be sent cross-origin. To serve a console from a different
origin than an instance's API:

- Set `server.corsAllowedOrigins` on **every** instance to the **single
  console origin** (e.g. `["https://console.example.com"]`) — identical across
  all instances. Wildcard/empty origins cannot carry credentials (and are a
  startup error under `password` auth; see ADR-0016).
- For cookie-mode instances, run **HTTPS** and set `cookieSameSite: none` with
  `cookieSecure: true`. A `SameSite=Lax` cookie is not sent on a cross-origin
  `fetch`, so cookie auth silently fails without this. Moving off `Lax` does not
  reopen CSRF — the `X-Homer-CSRF` custom-header guard plus pinned credentialed
  CORS remain the defense (ADR-0016, ADR-0028).
- Bearer-mode instances need no credentialed CORS (the token rides in a header,
  not a cookie), so they can keep wildcard CORS — unless the same instance also
  offers cookie auth, in which case pinned origins are required anyway.
- **Mixed content:** a console served over HTTPS cannot call an `http://`
  backend; the browser blocks it. All instances reachable from an HTTPS console
  must themselves be HTTPS.

### Combined Authentication

Allows multiple schemes simultaneously — useful when the console
authenticates with Password (cookie) but CLI / MCP callers use Bearer
and webhook callers use HMAC.

```yaml
apiAuth: !<combined>
  schemes:
    - !<password>
      users:
        alice: { passwordHash: "@SYSENV:ALICE_PASSWORD_HASH", role: admin }
      sessionSecret: "@SYSENV:HOMER_SESSION_SECRET"
    - !<bearer>
      tokens:
        ci:
          token: "@SYSENV:CI_TOKEN"
          grants:
            - agents: ["deploy-*"]
              actions: [run, read]
    - !<hmac>
      keys:
        vikunja:
          secret: "@SYSENV:VIKUNJA_WEBHOOK_SECRET"
          grants:
            - agents: [handle-vikunja-event]
              actions: [run]
          signatureHeader: "X-Vikunja-Signature"
```

<details>
<summary>JSON format</summary>

```json
{
  "apiAuth": {
    "type": "combined",
    "schemes": [
      {
        "type": "password",
        "users": { "alice": { "passwordHash": "@SYSENV:ALICE_PASSWORD_HASH", "role": "admin" } },
        "sessionSecret": "@SYSENV:HOMER_SESSION_SECRET"
      },
      {
        "type": "bearer",
        "tokens": {
          "ci": {
            "token": "@SYSENV:CI_TOKEN",
            "grants": [{ "agents": ["deploy-*"], "actions": ["run", "read"] }]
          }
        }
      },
      {
        "type": "hmac",
        "keys": {
          "vikunja": {
            "secret": "@SYSENV:VIKUNJA_WEBHOOK_SECRET",
            "grants": [{ "agents": ["handle-vikunja-event"], "actions": ["run"] }],
            "signatureHeader": "X-Vikunja-Signature"
          }
        }
      }
    ]
  }
}
```
</details>

**Constraints:** at most one each of `bearer`, `hmac`, and `password`
per `combined`; no nested `combined`; no `unsecured`. Each scheme
authenticates its own principals; grants are per-principal (per
token/key/user) regardless of which scheme authenticated the request —
mixing schemes in one `combined` block does not widen or narrow any
individual principal's grants.

### Unsecured Authentication

Disables authentication entirely (logs a warning at startup). This is an
**explicit opt-in** — omitting `apiAuth` altogether is a startup error, so an
unsecured API can never be the result of a forgotten or mistyped config key:

```yaml
apiAuth: !<unsecured>
```

<details>
<summary>JSON format</summary>

```json
{
  "apiAuth": {
    "type": "unsecured"
  }
}
```
</details>

**Warning:** Only use unsecured mode in trusted environments (development, internal networks). Never expose an unsecured Homer instance to the public internet.

### Migrating from `endpoints`

`endpoints` (a per-principal path-pattern allowlist) is **removed**. A
config that still sets it on any bearer token, HMAC key, or password user
fails startup with an error naming that principal; the general
translation:

| Old `endpoints` | New `role`/`grants` |
|---|---|
| `["*"]` | `role: admin` |
| `["/api/v1/agent/X"]` | `grants: [{agents: [X], actions: [run]}]` |
| `["/api/v1/agents", "/api/v1/status/*"]` | No faithful mapping — these mixed an agent-listing route with process-status routes that grants express differently (`read` covers an agent's runs, not a status-listing path). Decide what the principal actually needs and grant that directly, e.g. `grants: [{agents: ["*"], actions: [read]}]` for read-only visibility. |

There is no automatic migration: ambiguous legacy patterns (anything that
isn't a single-agent path or a bare `["*"]`) have no single correct grants
equivalent, so the startup error is the migration checkpoint rather than a
silent best-effort translation. See
[ADR-0037](../docs/adr/0037-grants-authorization.md).

### Sealed Class Mapping

The `AuthConfig` sealed class is represented differently in YAML and JSON:

**YAML:** Uses type tags (`!<bearer>`, `!<hmac>`, `!<password>`, `!<combined>`, `!<unsecured>`)
```yaml
apiAuth: !<bearer>
  tokens: {...}
```

**JSON:** Uses a `type` discriminator field
```json
{
  "apiAuth": {
    "type": "bearer",
    "tokens": {...}
  }
}
```

## Rate Limiting

Global rate limiting is configured via the `rateLimit` field on `server`. It uses a token bucket algorithm applied to all API endpoints.

```yaml
server:
  rateLimit:
    limit: 1000
    refillPeriod: 60
```

<details>
<summary>JSON format</summary>

```json
{
  "server": {
    "rateLimit": {
      "limit": 1000,
      "refillPeriod": 60
    }
  }
}
```
</details>

### Rate Limit Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `limit` | `Int` | No | `1000` | Maximum number of requests allowed per refill period (bucket capacity) |
| `refillPeriod` | `Long` | No | `60` | Time window in seconds after which the bucket refills |

### Behavior

- Rate limiting is applied globally to all API endpoints, through **one bucket shared by every request** (it is not keyed on caller or path), including `401`s and `404`s, with three exceptions:
  - the `/livez` and `/readyz` probes are exempt and never answer `429` (see [Liveness & readiness probes](#liveness--readiness-probes));
  - `POST /api/v1/auth/login`, when a `password` scheme mounts it, uses an aggregate login bucket of its own instead, the same size as `rateLimit` (see [Login rate limiting](#login-rate-limiting));
  - a request under `/api/v1/runner/` whose domain token verifies uses its domain's own bucket instead (see [Runner rate limiting](#runner-rate-limiting)). A missing or invalid token stays in the global bucket.

  So a client draining the global bucket cannot stall run pickup or lock operators out of login. See [ADR-0014](../docs/adr/0014-per-caller-rate-limiting.md).
- When the limit is exceeded, the server responds with **`429 Too Many Requests`** and no response body
- The bucket refills fully after each `refillPeriod` interval

### Per-caller rate limiting

`rateLimit` alone is a single bucket shared by every non-probe request, so one noisy caller
can consume the whole budget and starve everyone else. `perCallerRateLimit`
adds a *second*, per-identity bucket on the secured `/api/v1` routes. The two
limiters are layered — secured routes are subject to **both** the global
ceiling and the per-caller limit.

```yaml
server:
  perCallerRateLimit:
    limit: 300
    refillPeriod: 60
```

<details>
<summary>JSON format</summary>

```json
{
  "server": {
    "perCallerRateLimit": {
      "limit": 300,
      "refillPeriod": 60
    }
  }
}
```
</details>

- **Identity key.** The bucket is keyed on the authenticated identity Homer
  already computes:
  - Bearer → the token's config-map name.
  - HMAC → `hmac:<keyId>`.
  - Password/session → `session:<username>`.
- **Unsecured mode (`apiAuth: Unsecured`).** There is no principal, so the key
  falls back to the direct TCP peer (`local.remoteHost`). `X-Forwarded-For` is
  **not** trusted — same stance as the login limiter (see
  [ADR-0011](../docs/adr/0011-cookie-based-console-auth.md)) — so behind a proxy
  every request keys on the proxy's IP until a trusted-proxy story exists.
  > **Heads-up for proxied / single-IP unsecured deployments:** with one source
  > IP, all traffic collapses onto a single per-caller bucket. Because that
  > bucket is keyed on identity only (not path) and applies across every secured
  > endpoint, the default `limit` (300) becomes the binding aggregate limit and
  > is **stricter than the global `rateLimit`** (1000) — so the effective ceiling
  > drops from 1000 to 300. Raise `perCallerRateLimit.limit` (or run with auth so
  > each caller keys on its own identity) if that is too tight.
- **On by default**, with a generous `limit` (300). There is no separate enable
  flag — raise `limit` to effectively disable it, consistent with `rateLimit`.
- The unauthenticated probe and login routes (`/livez`, `/readyz`,
  `/auth/login`) are **not** subject to this limiter, so kubelet probes are
  unaffected (login keeps its own stricter per-IP limiter). Neither draws from
  the global bucket either — see
  [Liveness & readiness probes](#liveness--readiness-probes) and
  [Login rate limiting](#login-rate-limiting).

See [ADR-0014](../docs/adr/0014-per-caller-rate-limiting.md).

### Login rate limiting

`POST /api/v1/auth/login` carries its own stricter limiter, sized to slow
brute-force credential guessing without throttling normal API traffic. Login
does not draw from the global bucket, so a client draining it through other
routes cannot lock operators out; instead every login shares one aggregate
login bucket, as deep as `rateLimit` (default 1000 per 60 s), which bounds the
total across all peers. A flood aimed at login itself can drain that bucket and
block logins until it refills. The per-peer limiter is configured via `server.loginRateLimit`
(default `{limit=5, refillPeriod=60}`).

```yaml
server:
  loginRateLimit:
    limit: 30
    refillPeriod: 60
```

- **Key.** The bucket is keyed on the direct TCP peer (`local.remoteHost`),
  never `X-Forwarded-For` (spoofable). **Behind a reverse proxy** (nginx
  ingress, Envoy, …) that peer is the proxy itself, so the limit becomes a
  **single shared bucket for every client** rather than per-IP. In that
  topology the default 5/min is easily exhausted by ordinary traffic — a
  session expiring and forcing several tabs to re-authenticate, plus a user
  retrying — which surfaces in the console as an intermittent "can't connect"
  on login for up to a refill period. **Raise `limit` for any proxied or
  multi-user deployment** (e.g. 30–60/min).
- **Behavior.** Over-limit requests get `429 Too Many Requests`. The bucket
  refills fully once `refillPeriod` has elapsed since the last refill, and a
  rejected request does not reset that clock, so retrying does not prolong the
  lockout.

### Runner rate limiting

Requests under `/api/v1/runner/` whose domain token verifies (the same
constant-time match the routes use) skip the global bucket and draw from a
bucket of their domain's own, so an unauthenticated flood cannot stall run
pickup or let a healthy instance miss its heartbeat. A missing or invalid token
stays in the global bucket and gets `429` from it like any other request. A
domain token exempts runner routes only; on an operator route it is
unauthenticated traffic like any other.

- **Size.** `maxInstances × maxConcurrencyPerInstance × 1000` requests per 60 s
  (512 000 at the defaults), not configurable. An idle claim slot polls 2 times
  a minute (its single long poll — `claim`, or `next` while it holds a
  session — is held the full 30 s and re-issued at once), so a healthy fleet
  at its configured capacity never reaches the bound; output
  chunks, one request each, make up most of the rest.
- **Key.** The domain alias: each domain has its own bucket, and one domain's
  flood never throttles another.

## Agents Search Path

The `agentsSearchPath` specifies the directory where Homer looks for agent definitions:

```yaml
agentsSearchPath: "./agents"
```

**Behavior:**
- Homer walks this directory **recursively** and loads every directory holding `agent.json` or `agent.yaml`; when both exist, `agent.json` wins
- A definition may sit at any depth, **including inside another agent's directory** — a multi-agent pipeline lives in one tree
- The walk descends at most 8 levels below the search path and visits at most 1000 directories **within any one top-level directory's subtree** (10 000 across the whole catalog); files, hidden entries and symlinks do not count against that budget, and the directories kept under a cap are the first by name, so the same tree loads the same agents on every filesystem — except when a raw-entry budget below binds, in which case the entries read before the cut follow filesystem order. Raw entries read, files and skipped entries included, have the same two-level budget: at most 200 000 within any one top-level directory's subtree and 2 000 000 across the whole catalog. It skips hidden directories (`.git`, `.venv`, …) and never follows a symlinked directory. The per-subtree budget is what keeps one agent's directory from starving the rest of the catalog: a walk that hits any cap logs a warning and loads only the agents it saw, so a principal who can write into their own agent's directory cannot make other principals' agents vanish by filling it with directories or files
- A path handed to the agent files API may be at most 10 segments deep; a deeper one is refused with `400` before any directory is created, so a single write cannot plant a chain that every later walk then pays for. A definition file is bounded further: one landing deeper than the walk's 8-level cap, or under a hidden directory, is also refused with `400` — discovery would never load it, yet it would still wall off a subtree the file API cannot reach again (ADR-0068). A file that is not a definition is unaffected and still writes and reads at those paths
- Each agent directory defines exactly one agent; other files in it (e.g. `cleanup.agent.yaml`) are not loaded as agents
- Agents are identified by the `name` field in the agent definition; names are one flat global namespace and the directory name does not affect naming
- If two directories declare the same `name` — at any depth — **all** claimants are skipped (and the collision is reported as a load error), so one directory cannot shadow another
- A nested agent loads and runs independently of its parent: a parent that fails to parse does not take its children down
- Nesting is layout only: no agent gains a way to reference or read another agent's files (ADR-0049). A nested agent's directory is a different agent's `HMR_AGENT_DIR`, and the file API of the parent cannot reach it (ADR-0047)

**Example:**
```
./agents/
  ├── hello/
  │   └── agent.yaml                     # name: hello
  ├── batch/
  │   ├── agent.yaml                     # name: batch
  │   └── cleanup.agent.yaml             # ignored: not agent.json / agent.yaml
  └── web-pipeline/
      ├── agent.yaml                     # name: web-pipeline (orchestrator)
      └── nodes/
          ├── analysis/
          │   └── agent.json             # name: analyze (nested agent)
          └── sources/
              └── agent.yaml             # name: sources (nested agent)
```

**Relative Paths:** Relative paths are resolved from the current working directory when Homer starts.

## Workspace Root Path

The `workspaceRootPath` defines where Homer creates process workspaces:

```yaml
workspaceRootPath: "./workspaces"
```

**Template Variables:**
The workspace root path supports `{{VAR}}` templates, resolved once at startup
from `globalEnv` only. A `{{…}}` that names no `globalEnv` key stays in the path
literally — there is no built-in `{{DATE}}` or similar.

```yaml
# Separate workspaces per deployment environment
globalEnv:
  DEPLOY_ENV: "staging"
workspaceRootPath: "./workspaces/{{DEPLOY_ENV}}"
```

Do **not** put `{{HMR_SERVER_ID}}` in `workspaceRootPath`: it is a new random
UUID on every boot, so the root would move on each restart and the workspaces
of earlier boots would never be purged.

**Behavior:**
- Each process gets an isolated workspace directory
- The per-run directory under the root is defined by each agent's `workspace.path` template (see AGENT.md); the default is the process id
- Workspaces can be automatically cleaned up after process completion

**Example Directory Structure** (default `workspace.path`):
```
./workspaces/
  ├── 12/                                    # Process workspace (process id)
  │   ├── workdir/                           # HMR_WORK_DIR
  │   └── artifacts/                         # HMR_ARTIFACTS_DIR
  └── 13/
      ├── workdir/
      └── artifacts/
```

**Relative Paths:** Relative paths are resolved from the current working directory when Homer starts.

**Workspace Path Configuration:** The workspace directory structure within `workspaceRootPath` is defined per-agent in each agent's `workspace.path` configuration. See AGENT.md for details on configuring workspace paths with templates.

## Database Path

The `databasePath` points Homer at the SQLite database holding durable
orchestrator state — process records (status, history, executions, runner
state, cost) and operator questions. SQLite is the authoritative store; there
is no in-memory cache of this state. See [ADR-0020](../docs/adr/0020-durable-state-in-sqlite.md).

```yaml
databasePath: "./homer.db"
```

**Behavior:**
- The file (and its WAL sidecar files) is created automatically if absent.
- The value supports `@SYSENV:VAR_NAME` resolution, like other config secrets.
- Relative paths are resolved from Homer's current working directory at startup.
- **Restart semantics:** process records and questions survive restarts. On
  boot, any process still `CREATED`/`WORKING` from a previous run — whose
  driving coroutine is gone — is marked **`UNKNOWN`** (a terminal status) and
  its open questions are expired. Its workspace and record are then purged on
  the agent's current `workspace.purgeAfter` / `workspace.retain` schedule
  (the defaults if the agent is gone), counted from the restart. New runs
  always receive a strictly higher process id (the id column is monotonic and
  never reused).

> **Containers:** the database file MUST live on a persisted volume, not the
> ephemeral container filesystem — otherwise process history and open questions
> are lost on every restart. See [DOCKER.md](../DOCKER.md).

## Langfuse Configuration

The `langfuse` object configures the Langfuse integration for observability.
The whole object is optional; omitted fields default to an empty string. It is
used for three things:

- **Trace export**, when `openTelemetryExport` is `LANGFUSE` (the default).
- **Fetching `!<langfuse>` prompts** for Claude/Codex/opencode commands, regardless of the
  export setting.
- **Trace deep links**, when `projectId` is also set (see the field table).

Leave it out if you need none of these.

```yaml
langfuse:
  serverUrl: "https://cloud.langfuse.com"
  publicKey: "pk-lf-1234567890abcdef"
  secretKey: "sk-lf-1234567890abcdef1234567890abcdef"
```

<details>
<summary>JSON format</summary>

```json
{
  "langfuse": {
    "serverUrl": "https://cloud.langfuse.com",
    "publicKey": "pk-lf-1234567890abcdef",
    "secretKey": "sk-lf-1234567890abcdef1234567890abcdef"
  }
}
```
</details>

### Langfuse Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `serverUrl` | `String` | No | `""` | Langfuse instance URL. When export is `LANGFUSE`, a plaintext `http://` URL to a non-loopback host is rejected at startup (it would leak the API keys + prompt content); use `https://` or a `localhost`/`127.0.0.1` collector. Userinfo (`https://user:pass@host`), query and fragment are stripped before the endpoint is written to the log; the exporter still uses the URL as given. |
| `publicKey` | `String` | No | `""` | Langfuse public API key |
| `secretKey` | `String` | No | `""` | Langfuse secret API key |
| `projectId` | `String` | No | `""` | Langfuse project id. Used only to deep-link a run to its trace (`{serverUrl}/project/{projectId}/traces/{traceId}`), surfaced as `langfuseTraceUrl` on the process status API and as a link on the console's process detail. Langfuse derives the project from the API key pair and never returns it over OTLP, so it has to be configured; copy it out of any Langfuse URL. Blank = no trace links. **Setting it makes the link visible to every principal that can see the run** — the URL grants nothing on its own (reading the trace needs Langfuse credentials Homer does not issue), but it does reveal your `serverUrl` and `projectId` to those callers. Leave blank if that is unwanted. |

### Finding Your Langfuse Credentials

1. Log into your Langfuse instance
2. Navigate to Settings → API Keys
3. Copy the public and secret keys
4. For self-hosted instances, use your instance URL (e.g., `https://langfuse.yourdomain.com`)
5. For cloud instances, use `https://cloud.langfuse.com`

### Langfuse Integration Features

- **Trace Creation**: Each agent execution creates a Langfuse trace
- **Span Generation**: Each command creates a span with timing data
- **Prompt Management**: Claude agents can reference Langfuse-managed prompts
- **OpenTelemetry Export**: Homer exports OTLP data to Langfuse

> **Content egress:** exported spans include the compiled prompt and command
> input/output. Read this before deciding what may reach your collector:
>
> - **Masked (`***`) on every command path** — `!<claude>`, `!<cmd>`,
>   `!<script>`, `!<codex>`, `!<opencode>`, `!<langgraph>` and `!<runnerCmd>` all route span
>   input/output through the redactor, as does the captured `output.env`:
>   - values of inputs declared `secret: true`;
>   - any `globalEnv` / `agent.env` value the operator sourced from the host
>     via `@SYSENV:` — reaching into the host environment is treated as
>     declaring the value a credential. A composite such as
>     `"Bearer @SYSENV:TOKEN"` masks both the whole resolved string and the
>     referenced host value on its own, so output that carries just the
>     credential is masked too;
>   - the per-process `HMR_CALLBACK_TOKEN`.
> - **Not masked** — a `globalEnv` / `agent.env` entry whose value is a
>   **literal**. Homer cannot tell a token from a URL, and masking every global
>   would replace ordinary strings with `***` throughout telemetry. Forward
>   credentials via `@SYSENV:` or a `secret: true` input instead of pasting
>   them into the config file.
>
> The corollary of the `@SYSENV:` rule: do not use it for ordinary values like
> a region or a log level, or telemetry will show `***` wherever that string
> appears.
>
> Everything not masked is sent to `langfuse.serverUrl` in cleartext. Point
> `serverUrl` only at a trusted (HTTPS) collector. Note also that a blank
> `serverUrl` falls back to the logging exporter, which prints the same span
> content to stderr via `java.util.logging` (not `app.log`).

### Span attributes

Homer sets these on the spans it exports, so a dashboard or alert can be built
on them without parsing span content. Span input/output values are covered by the
egress note above.

| Span | Attribute | Value |
|---|---|---|
| Process | `process.id` | The run's process id |
| Process | `process.status` | `CREATED`, then the terminal status (`FINISHED`, `FAILED`, `KILLED`) |
| Command (any type) | `cmd.type` | Command class: `Cmd`, `Script`, `Claude`, `Codex`, `Opencode`, `RunAgent`, `LangGraph`, `RunnerCmd` |
| Command (any type) | `command.label` | The command's `label`, which also names a Claude generation |
| Command (any type) | `output.exitCode` | Exit code as a string |
| Command (any type) | `cmd.skipped` | `true` when a `when` clause skipped the command |
| Command (any type) | `output.env` | Captured `resultEnvParam` values, redacted |
| Command (remote) | `runner.alias` | The runner that executed it, plus that runner session's own metadata keys |
| `!<claude>` / `!<codex>` / `!<opencode>` | `llm.cost_usd` | Cost charged for the attempt; absent when the charge is zero |
| `!<claude>` / `!<codex>` / `!<opencode>` | `llm.cost.source` | `session` (the spend endpoint answered), `cli` (the CLI's own figure) or `none` (no cost reported, nothing charged) |
| `!<claude>` / `!<codex>` / `!<opencode>` | `llm.cost.fallback_reason` | Why the figure is degraded; absent when it is not. A step that returned while its session lookup was still running keeps `session figure pending` here — the command span is not rewritten once it has ended; the settled figure arrives on the step's `llm_cost_settlement` child span, written when the background settlement answers **or** when its 45 s hand-off wait bound expires first — the latter reports the pending figure and marks the shed with `telemetry.llm.handoff_shed` on the child span. A `session figure pending` can therefore be final, not still settling: a hand-off shed at the concurrency bound reports inline on the still-open command span and likewise leaves `session figure pending` for good. Alert on a non-empty value other than `session figure pending` with `llm.cost.source != session` to catch `incomplete: …` and endpoint failures |
| `!<claude>` / `!<codex>` / `!<opencode>` (background settlement) | `llm.cost.source`, `llm.cost_usd`, `llm.cost.fallback_reason`, `llm.session_id`, `llm.usage.*`, `telemetry.error` | The `llm_cost_settlement` child span of a step that returned on a pending figure, written when the background settlement answers **or** when its 45 s hand-off wait bound expires first: the settled cost and its source — or, on an expired wait bound, the pending figure with `telemetry.llm.handoff_shed` set — a `fallback_reason` only when the settled figure is itself degraded, and `telemetry.error` when the telemetry hand-off failed. Absent when `llmPrice.sessionSpend` is not configured, and for a settlement cancelled by shutdown (the command span keeps the pending figure then) |
| `!<claude>` / `!<codex>` / `!<opencode>` | `llm.session_id` | CLI session / thread id, when the output carried one |
| `!<claude>` / `!<codex>` / `!<opencode>` | `llm.usage.*` | Token usage the CLI reported, summed over every turn end of the attempt: `input`, `output`, `cache_read`, `cache_write`, `reasoning` |
| `!<claude>` | `llm.cost.parse_failed` | `true` when result events carried no parseable, non-negative cost (a CLI schema-drift signal) |
| `!<claude>` | `claude.max_turns_stop` | `true` when the attempt stopped at `maxTurns` and `maxTurnsExitCode` applied |
| `!<claude>` | `claude.dropped_system_events` | Events removed by `server.claudeDropSystemSubtypes` |
| `!<claude>` / `!<codex>` / `!<opencode>` | `retry.attempts`, `retry.attempts.used` | Configured attempts, and how many were spent |
| `!<claude>` / `!<codex>` / `!<opencode>` | `llm.escalation.tier` | Highest `escalate` tier the step ran; absent when only tier 0 ran. See [ADR-0079](../docs/adr/0079-llm-steps-escalate-to-another-model-on-a-bad-outcome.md) |
| `!<agent>` | `agent.processId` | The child run this command started |

## Global Environment Variables

The `globalEnv` map defines environment variables available to all agents:

```yaml
globalEnv:
  APP_ENV: "production"
  LOG_LEVEL: "info"
  API_ENDPOINT: "https://api.example.com"
  TIMEOUT: "30"
```

<details>
<summary>JSON format</summary>

```json
{
  "globalEnv": {
    "APP_ENV": "production",
    "LOG_LEVEL": "info",
    "API_ENDPOINT": "https://api.example.com",
    "TIMEOUT": "30"
  }
}
```
</details>

**Behavior:**
- These variables are available to all commands in all agents
- Agent-specific environment variables override global variables
- Query parameters override both global and agent-specific variables
- Commands read these values as ordinary environment variables (`$VAR_NAME`). `{{VAR_NAME}}` templates are substituted only in Claude/Codex/opencode prompts and options, `!<agent>` `agentName`, the workspace path, the lock key and `artifactNameTemplate` — **not** in `!<cmd>` `cmd` or `!<script>` `content`

### Referencing System Environment Variables

Several config values can reference host system environment variables using the
`@SYSENV:VAR_NAME` syntax, either as the whole value or inside a longer string
(e.g. `"Bearer @SYSENV:TOKEN"`). The value is resolved at startup — if the variable
does not exist, the server fails to start with a clear error message. Agent `env`
values are the exception: they are resolved whenever the agent is loaded (at startup
and on every reload), and a missing variable fails only that agent — it is skipped
with an `Error loading agent from …` load error while the server keeps running.

`@SYSENV:` is resolved in:
- `globalEnv` values, agent `env` values, and runner `env` values (`ssh`, `kubernetes`, `external`)
- the `external` runner's domain `token`
- `databasePath`
- `langfuse.publicKey` and `langfuse.secretKey` (not `langfuse.serverUrl`)
- bearer token values, HMAC key `secret`s, password users' `passwordHash`, and `sessionSecret` / `sessionSecretPrevious`
- `llmPrice.sessionSpend.headers` values
- `server.corsAllowedOrigins` entries — each must still resolve to an `http(s)://host[:port]` origin, and is not treated as a secret

Anywhere else the string is taken literally.

**Example:**
```yaml
globalEnv:
  API_TOKEN: "@SYSENV:MY_API_TOKEN"
  BASE_URL: "https://api.example.com/@SYSENV:TENANT_ID/v1"

server:
  apiAuth: !<bearer>
    tokens:
      admin:
        token: "@SYSENV:HOMER_SECRET_TOKEN"
        role: admin
  # The console's port comes from the environment (FRONTEND_PORT in the compose stack).
  corsAllowedOrigins:
    - "http://localhost:@SYSENV:FRONTEND_PORT"
    - "http://127.0.0.1:@SYSENV:FRONTEND_PORT"
```

**System Environment Variables:**
Homer automatically adds the following variables to every command execution:
- `HMR_SERVER_ID` - Unique server instance ID (generated at startup)
- `HMR_PROCESS_ID` - Unique process execution ID
- `HMR_WORK_DIR` - Working directory path for the process
- `HMR_ARTIFACTS_DIR` - Artifacts directory path for the process
- `HMR_AGENT_NAME` - Name of the agent being executed
- `HMR_AGENT_DIR` - Absolute path to the agent's own config directory (holding its `agent.yaml` / `agent.json`)

All of them are set in every command's environment. Workspace path and lock key
templates are resolved before the workspace exists, so only a subset is available
there: `HMR_SERVER_ID`, `HMR_PROCESS_ID`, `HMR_AGENT_DIR`, `HMR_INVOCATION_CHAIN`,
`HMR_PARENT_PROCESS_ID` and `HMR_ROOT_PROCESS_ID` (the last two where set), plus
`globalEnv`, the runner's `env` (alias-addressed runs only), the agent's `env`,
the run's inputs, and — on the local runner only — the `parentEnvAllowlist` host
values. `HMR_WORK_DIR`, `HMR_ARTIFACTS_DIR` and
`HMR_AGENT_NAME` are available to commands only.

`HMR_ROOT_PROCESS_ID` — the id of the first run in this run's flow — is layered
with the rest of the reserved `HMR_*` names, alongside `HMR_PARENT_PROCESS_ID`
(the run one hop up). It is **removed**, not set empty, at the root run itself,
so an agent `env:` entry or an allowlisted host variable cannot pose as it. The
same value is served as `rootProcessId` on the status API. See
[ADR-0058](../docs/adr/0058-parent-child-run-linkage.md).

**Use Cases:**
- Configuration that applies to all agents (API endpoints, credentials)
- Environment-specific settings (dev, staging, production)
- Default values that can be overridden per-agent

### `parentEnvAllowlist`

List of host-JVM environment variable names that are layered into every
agent's child-process env *underneath* `globalEnv`. The default —
`["PATH", "HOME", "LANG", "TZ"]` — keeps typical agents (the Claude
CLI, shell scripts, locale-aware tools) functional without forcing
every operator to copy these into `globalEnv`.

Any host env var NOT on this list is invisible to agents, including
third-party agents. To forward an additional credential, either:

- Add the key to `parentEnvAllowlist` (and Homer reads it from
  `System.getenv()`), or
- Declare it in `globalEnv` via `@SYSENV:VAR_NAME` (recommended for
  credentials, because it makes the contract explicit at the agent
  boundary).

`globalEnv` wins on key collisions, so `parentEnvAllowlist: ["PATH"]`
plus `globalEnv: { PATH: "/custom/bin" }` produces `/custom/bin` in
the child env.

Example to forward Anthropic's API key (which the Claude CLI
auto-consumes) without putting it on the allowlist:

```yaml
globalEnv:
  ANTHROPIC_AUTH_TOKEN: "@SYSENV:ANTHROPIC_AUTH_TOKEN"
```

#### Remote runners

The allowlist applies to the built-in `local` runner **only**. Remote
runners (`ssh`, `kubernetes`, `external`) never receive `parentEnvAllowlist` keys:
the values describe Homer's own host — its `HOME`, its `PATH` — and on
another machine they are wrong by construction (on a macOS ssh target,
an inherited `HOME=/home/homer` makes every tool that resolves
`~/.config` fail: `/home` is an autofs mount nothing can be created
under). With `HOME` absent on an ssh target, sshd sets it from the
remote user's passwd entry and the login shell supplies its own
`PATH`/`LANG`/`TZ`, so there is nothing to configure for the common
case.

This is a **breaking change** for remote runners that silently relied on
a forwarded key. After upgrading:

- **Values the remote host must still see** that Homer used to supply
  via the allowlist → set them explicitly in the runner's
  [`env`](#sshrunner-fields) (per-host values: `PATH`, `LANG`, `TZ`,
  and `HOME` where the image/uid has no usable home) or in `globalEnv`
  (values identical for every runner).
- **Host-env credentials** previously forwarded by adding their key to
  `parentEnvAllowlist` → move them to `globalEnv` as
  `@SYSENV:VAR`. This also puts them into the telemetry redaction set,
  which the allowlist path never did.
- **`kubernetes` note:** with `HOME` no longer forwarded, a pod whose
  `runAsUser` has no passwd entry gets `HOME=/`; set `HOME` in the
  runner's `env` if tools in the image need a writable home.
- **`ssh` note:** nothing to do for `HOME` — sshd sets it from the
  remote user's passwd entry; the login shell supplies `PATH`/`LANG`/
  `TZ` unless the runner's `env` overrides them.

The exclusion also holds on the cross-runner path: a
[`runnerCmd`](AGENT.md#commandrunnercmd---run-a-command-on-another-runner) step
targeting a remote runner inherits the requesting run's env, so the executor
strips the allowlisted host values from it first — exactly the keys still
carrying the host value they were layered with. A key re-set via `globalEnv`,
the runner's `env`, or the agent's `env` is the operator's own and reaches the
target; a local target keeps everything.

See [ADR-0065](../docs/adr/0065-remote-runner-env-composition.md) for the rationale and the full layering order.

## OpenTelemetry Export

The `openTelemetryExport` field controls where OpenTelemetry data is exported:

```yaml
openTelemetryExport: LANGFUSE
```

<details>
<summary>JSON format</summary>

```json
{
  "openTelemetryExport": "LANGFUSE"
}
```
</details>

### Export Options

| Value | Description |
|-------|-------------|
| `LANGFUSE` | Export to Langfuse OTLP endpoint (default) |
| `LOG` | Export to stderr via `java.util.logging`, not `app.log` (useful for debugging) |

**LANGFUSE Export:**
- Sends OTLP data to `{langfuse.serverUrl}/api/public/otel/v1/traces`
- Requires valid `langfuse` configuration
- Enables full observability in Langfuse UI
- If `langfuse.serverUrl` is blank, Homer logs a warning and falls back to the `LOG` exporter

**LOG Export:**
- Logs span data to stderr via `java.util.logging` (not logback, so not `app.log`)
- Useful for development and debugging
- Does not require Langfuse configuration

### Knowing where traces actually go

On startup Homer logs the resolved destination at INFO — the OTLP endpoint under
`LANGFUSE`, or stderr via `java.util.logging` (not `app.log`) otherwise; the
log line itself says "application log". Check that line first when traces
are missing; it also makes the blank-`serverUrl` fallback visible.

A batch the collector rejects or cannot be reached for is logged at WARN
(`Failed to export N OpenTelemetry span(s) to <endpoint>`) and dropped; spans are
never retried. The OTel SDK also reports the failure itself, but only through
`java.util.logging` — an unformatted `SEVERE` on stderr that bypasses logback,
names no endpoint, and is rate-limited to once a minute past five messages.

### Trace propagation to the LLM CLI

When an LLM harness command (`!<claude>`, `!<codex>`, `!<opencode>`) runs, Homer passes the
active span to the CLI subprocess as a W3C `OTEL_TRACE_PARENT` environment variable. If the CLI
emits its own OpenTelemetry data, it is stitched into the same trace.
Propagation is automatic and requires no configuration; it is skipped
when no span is active.

## Complete Configuration Examples

### Minimal Configuration

Bare minimum configuration for local development:

```yaml
server:
  port: 8080
  apiAuth: !<unsecured> {}

agentsSearchPath: "./agents"
workspaceRootPath: "./workspaces"
databasePath: "./homer.db"

langfuse:
  serverUrl: "https://cloud.langfuse.com"
  publicKey: "pk-lf-xxx"
  secretKey: "sk-lf-xxx"
```

<details>
<summary>JSON format</summary>

```json
{
  "server": {
    "port": 8080,
    "apiAuth": {
      "type": "unsecured"
    }
  },
  "agentsSearchPath": "./agents",
  "workspaceRootPath": "./workspaces",
  "databasePath": "./homer.db",
  "langfuse": {
    "serverUrl": "https://cloud.langfuse.com",
    "publicKey": "pk-lf-xxx",
    "secretKey": "sk-lf-xxx"
  }
}
```
</details>

### Production Configuration

Production-ready configuration with authentication and custom settings:

```yaml
server:
  port: 9000
  apiAuth: !<bearer>
    tokens:
      admin:
        token: "@SYSENV:ADMIN_TOKEN"
        role: admin
      analyst:
        token: "@SYSENV:ANALYST_TOKEN"
        grants:
          - agents: ["*"]
            actions: [run, read]
      readonly:
        token: "@SYSENV:READONLY_TOKEN"
        grants:
          - agents: ["*"]
            actions: [read]

agentsSearchPath: "/opt/homer/agents"
workspaceRootPath: "/var/homer/workspaces"
databasePath: "/var/homer/homer.db"

langfuse:
  serverUrl: "https://langfuse.company.com"
  publicKey: "pk-lf-production-key"
  secretKey: "sk-lf-production-secret"

globalEnv:
  APP_ENV: "production"
  LOG_LEVEL: "warn"
  API_ENDPOINT: "https://api.company.com"
  TIMEOUT: "60"
  DATA_DIR: "/data"

openTelemetryExport: LANGFUSE
```

<details>
<summary>JSON format</summary>

```json
{
  "server": {
    "port": 9000,
    "apiAuth": {
      "type": "bearer",
      "tokens": {
        "admin":    { "token": "@SYSENV:ADMIN_TOKEN",    "role": "admin" },
        "analyst":  { "token": "@SYSENV:ANALYST_TOKEN",  "grants": [{ "agents": ["*"], "actions": ["run", "read"] }] },
        "readonly": { "token": "@SYSENV:READONLY_TOKEN", "grants": [{ "agents": ["*"], "actions": ["read"] }] }
      }
    }
  },
  "agentsSearchPath": "/opt/homer/agents",
  "workspaceRootPath": "/var/homer/workspaces",
  "databasePath": "/var/homer/homer.db",
  "langfuse": {
    "serverUrl": "https://langfuse.company.com",
    "publicKey": "pk-lf-production-key",
    "secretKey": "sk-lf-production-secret"
  },
  "globalEnv": {
    "APP_ENV": "production",
    "LOG_LEVEL": "warn",
    "API_ENDPOINT": "https://api.company.com",
    "TIMEOUT": "60",
    "DATA_DIR": "/data"
  },
  "openTelemetryExport": "LANGFUSE"
}
```
</details>

### Development Configuration with Logging

Development configuration with telemetry exported to logs:

```yaml
server:
  port: 8080
  apiAuth: !<unsecured> {}

agentsSearchPath: "./agents"
workspaceRootPath: "./tmp/workspaces"
databasePath: "./tmp/homer.db"

langfuse:
  serverUrl: "https://cloud.langfuse.com"
  publicKey: "pk-lf-dev"
  secretKey: "sk-lf-dev"

globalEnv:
  APP_ENV: "development"
  LOG_LEVEL: "debug"

openTelemetryExport: LOG
```

<details>
<summary>JSON format</summary>

```json
{
  "server": {
    "port": 8080,
    "apiAuth": {
      "type": "unsecured"
    }
  },
  "agentsSearchPath": "./agents",
  "workspaceRootPath": "./tmp/workspaces",
  "databasePath": "./tmp/homer.db",
  "langfuse": {
    "serverUrl": "https://cloud.langfuse.com",
    "publicKey": "pk-lf-dev",
    "secretKey": "sk-lf-dev"
  },
  "globalEnv": {
    "APP_ENV": "development",
    "LOG_LEVEL": "debug"
  },
  "openTelemetryExport": "LOG"
}
```
</details>

## API Endpoints Reference

### GET /api/v1/status/all

Get a list of all processes with optional filtering, ordering, and pagination.

**Authentication**: Required (when configured)

**Query Parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `types` | string | No | - | Comma-separated list of status types to filter. Valid values: CREATED, WORKING, FINISHED, KILLED, FAILED, UNKNOWN (case-insensitive) |
| `order` | string | No | `desc` | Sort order by process ID. Valid values: `asc`, `desc` |
| `limit` | integer | No | `100` | Maximum number of results to return. Must be between 1 and 1000 |
| `offset` | integer | No | `0` | Number of results to skip. Must be >= 0 |
| `agentName` | string | No | - | Exact-match filter on the agent name (case-sensitive) |
| `parentProcessId` | integer | No | - | Only runs whose parent (the run that caused them) has this id. Must be a positive integer |
| `rootProcessId` | integer | No | - | Only the runs belonging to the flow rooted at this run — its descendants at every depth, in one paginated read. Must be a positive integer. Rows with no stored root (pre-v22 rows, or a descendant whose root had already been evicted when the descendant started) are not matched. The id is also gated on visibility: a flow root the caller cannot see reads as an empty list, never as an unfiltered list |
| `roots` | boolean | No | - | `true` returns only runs with no parent (`parent_process_id IS NULL`) — one row per flow. Composes with `types`, `agentName` and `tag` |
| `tag` | string | No | - | Subject-tag filter `key:value`. Repeatable; repeated values AND together (a run must carry every one). At most 10, each at most 193 characters |

**Request Examples**:

```bash
# Get all processes (default: desc order, limit 100)
curl "http://localhost:8080/api/v1/status/all" \
  -H "Authorization: Bearer your-token"

# Filter by status types
curl "http://localhost:8080/api/v1/status/all?types=WORKING,FINISHED" \
  -H "Authorization: Bearer your-token"

# Order ascending
curl "http://localhost:8080/api/v1/status/all?order=asc" \
  -H "Authorization: Bearer your-token"

# Pagination
curl "http://localhost:8080/api/v1/status/all?limit=50&offset=0" \
  -H "Authorization: Bearer your-token"

# Combine all parameters
curl "http://localhost:8080/api/v1/status/all?types=WORKING&order=asc&limit=20&offset=10" \
  -H "Authorization: Bearer your-token"

# Case-insensitive status types
curl "http://localhost:8080/api/v1/status/all?types=working,finished" \
  -H "Authorization: Bearer your-token"

# Filter by agent name (exact match)
curl "http://localhost:8080/api/v1/status/all?agentName=deploy-service" \
  -H "Authorization: Bearer your-token"
```

**Success Response (200 OK)**:

```json
{
  "processes": [
    {
      "id": 12345,
      "status": "WORKING",
      "agentName": "example-agent",
      "history": [
        {
          "timestamp": 1700000000,
          "status": "CREATED"
        },
        {
          "timestamp": 1700000100,
          "status": "WORKING"
        }
      ],
      "executions": [
        {
          "label": "cmd",
          "start": 1700000050,
          "end": 1700000090,
          "resultCode": 0,
          "stdOut": "/tmp/homer/workspace-12345/artifacts/cmd_0.out",
          "stdErr": "/tmp/homer/workspace-12345/artifacts/cmd_0.err",
          "errMsg": null
        }
      ]
    }
  ],
  "total": 150,
  "offset": 0,
  "limit": 100
}
```

**Response Fields**:

| Field | Type | Description |
|-------|------|-------------|
| `processes` | array | Array of process information objects (same format as `/status/{processId}`) |
| `total` | integer | Total number of matching processes before pagination |
| `offset` | integer | The offset that was applied |
| `limit` | integer | The limit that was applied |

**Error Responses (400 Bad Request)**:

Invalid status type:
```json
{
  "err": 1,
  "msg": "Invalid status types: INVALID"
}
```

Invalid order parameter:
```json
{
  "err": 1,
  "msg": "Invalid order parameter. Must be 'asc' or 'desc'"
}
```

Invalid limit:
```json
{
  "err": 1,
  "msg": "Invalid limit parameter. Must be between 1 and 1000"
}
```

Invalid offset:
```json
{
  "err": 1,
  "msg": "Invalid offset parameter. Must be >= 0"
}
```

**Usage Notes**:

- Results are filtered by visibility (`admin` ∪ owner ∪ `read` grant on the agent — see [Process ownership and visibility](#process-ownership-and-visibility)); a run the caller cannot see is simply absent, and `total` counts only visible runs
- Status types are case-insensitive (e.g., `working`, `WORKING`, or `Working` all work)
- When no processes match the filter, returns empty `processes` array with `total: 0`
- The `total` field represents the count BEFORE pagination, useful for building pagination UI
- Default ordering is descending (newest processes first)
- Multiple status types can be specified as comma-separated values
- Clients can calculate if there are more results via: `offset + limit < total`

### GET /api/v1/artifacts

Download artifact files from process executions.

**Authentication**: Required (when configured)

**Query Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `processId` | integer | Yes | Process identifier |
| `path` | string | Yes | Relative path from artifacts directory (e.g., `cmd_0.out`, `cmd_1.err`) |

**Request Examples**:

```bash
# Get command stdout file
curl "http://localhost:8080/api/v1/artifacts?processId=12345&path=cmd_0.out" \
  -H "Authorization: Bearer your-token"

# Get command stderr file
curl "http://localhost:8080/api/v1/artifacts?processId=12345&path=cmd_1.err" \
  -H "Authorization: Bearer your-token"

# Get custom artifact file (written by agent commands)
curl "http://localhost:8080/api/v1/artifacts?processId=12345&path=output.json" \
  -H "Authorization: Bearer your-token"
```

**Response Headers**:

| Header | Type | Description |
|--------|------|-------------|
| `X-Process-Status` | string | Current process status (CREATED, WORKING, FINISHED, KILLED, FAILED, UNKNOWN) |
| `X-File-Complete` | boolean | `true` if file is complete, `false` if still being written by active process |
| `Content-Type` | string | `text/plain` (no `charset` parameter) |

**Success Response (200 OK)**:

Returns the complete file contents at the time of the request.

**Security**:

- Path traversal attacks prevented (paths containing `..` are rejected)
- Only files within process artifacts directory accessible
- Absolute paths rejected
- Paths escaping the artifacts directory rejected

**Error Responses (400 Bad Request)**:

Missing processId parameter:
```json
{
  "err": 1,
  "msg": "Invalid or missing processId parameter"
}
```

Missing path parameter:
```json
{
  "err": 1,
  "msg": "Missing path parameter"
}
```

Invalid path (directory traversal attempt):
```json
{
  "err": 1,
  "msg": "Invalid path: Path traversal (..) is not allowed"
}
```

Invalid path (absolute path):
```json
{
  "err": 1,
  "msg": "Invalid path: Absolute paths are not allowed"
}
```

Invalid path (escapes directory):
```json
{
  "err": 1,
  "msg": "Invalid path: Path escapes the artifacts directory"
}
```

**Error Responses (404 Not Found)**:

Process not found (also returned when the caller cannot see the process):
```json
{
  "err": 1,
  "msg": "Process not found"
}
```

Artifact file not found:
```json
{
  "err": 1,
  "msg": "Artifact file not found"
}
```

The path resolves outside the artifacts directory (e.g. through a symlink),
names something other than a regular file, names a hard link (a file with more
than one link), or the process has no artifacts directory:
```json
{ "err": 1, "msg": "Artifact not found" }
```
```json
{ "err": 1, "msg": "Process has no artifacts directory" }
```

**Error Responses (413 Payload Too Large)**:

Artifact larger than [`server.maxArtifactBytes`](#server-fields):
```json
{
  "err": 1,
  "msg": "Artifact exceeds server.maxArtifactBytes (209715200 > 104857600)"
}
```

**Usage Notes**:

- All command stdout/stderr is automatically written to `<stem>.out` and `<stem>.err` files; the stem comes from the agent's `artifactNameTemplate` and defaults to `cmd_N`, where N is the command index
- Agents can write custom artifacts to `HMR_ARTIFACTS_DIR` for retrieval via this endpoint
- The `X-File-Complete` header indicates whether the file is still being written by an active process
- If file is incomplete (still being written), clients may poll this endpoint to get updated content

**Example Use Cases**:

1. **Retrieve completed command output**:
   ```bash
   # After process finishes, get final output
   curl "http://localhost:8080/api/v1/artifacts?processId=12345&path=cmd_0.out"
   ```

2. **Access custom artifacts**:
   ```bash
   # If agent writes files to $HMR_ARTIFACTS_DIR/report.html
   curl "http://localhost:8080/api/v1/artifacts?processId=12345&path=report.html"
   ```

### POST /api/v1/processes/{id}/retry

Retry a finished process (`FINISHED`, `FAILED` or `KILLED`) by launching a new execution of the same agent with the source process's captured `inputParams`. The stored inputs are re-validated against the agent's **current** definition first, exactly as a fresh request would be. An `UNKNOWN` run (interrupted by a restart) cannot be retried — use `POST /api/v1/processes/{id}/resume` for a LangGraph run; an `UNKNOWN` run of any other agent cannot be replayed through the API.

**Authentication**: Required (when configured)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `id` | integer | Yes | Source process identifier to replay |

**Request Body**: None. The body is ignored; parameters come from the source process.

**Request Example**:

```bash
curl -X POST "http://localhost:8080/api/v1/processes/12345/retry" \
  -H "Authorization: Bearer your-token"
```

**Success Response (200 OK)**:

```json
{
  "processId": 12346
}
```

The returned `processId` identifies the newly started execution. The original source process is left untouched.

**Error Responses**:

400 Bad Request — non-integer `id`:
```json
{ "err": 1, "msg": "Invalid process id" }
```

400 Bad Request — a stored input no longer satisfies the agent's current definition (for example a newly required input, or a value the current validation rejects):
```json
{ "err": 1, "msg": "Cannot retry: agent 'deploy-service' now requires input 'env' which is absent from the original run" }
```

404 Not Found — unknown source process, or one the caller cannot retry (see **Authorization** below):
```json
{ "err": 1, "msg": "Process 12345 not found" }
```

409 Conflict — source process is not `FINISHED`, `FAILED` or `KILLED` (still running, or `UNKNOWN`):
```json
{ "err": 1, "msg": "Cannot retry a non-terminal process (status: WORKING)" }
```

429 Too Many Requests — the agent's cost cap or `cooldown.minInterval` refuses the new run (`afterFailure` does not gate this path).

503 Service Unavailable — [`server.maxProcesses`](#server-fields) is reached and no terminal row can be evicted to make room.

404 Not Found — the agent the source process used is no longer registered:
```json
{ "err": 1, "msg": "Agent 'deploy-service' no longer exists" }
```

**Authorization**: the caller must be able to see the source run and hold a `run` grant on its agent. Otherwise the response is `404`, the same as not-found.

**Record Retention Note**:

Retry replays the source process's `inputParams` from its record in the SQLite database. Once that record is gone — purged after the agent's `workspace.purgeAfter` (or `workspace.retain.recordFor`, when set), or evicted at [`server.maxProcesses`](#server-fields) — retrying that process id returns `404`.

**Telemetry**:

The retry handler opens a span named `agent_retry_request` with attribute `retry.of_process_id` set to the source process id, so the new execution trace can be linked back to the original.

## Streaming Endpoints (Server-Sent Events)

Four read-only SSE endpoints under `/api/v1/stream/*` stream realtime updates to clients. All require the same bearer authentication as other `/api/v1/*` endpoints. All emit a `: ping` comment line immediately on connect — flushing the response headers, which Netty otherwise defers until the first body byte — and every 15 seconds thereafter to keep idle connections open through proxies.

**Concurrent streams are capped per caller.** Each caller (keyed like the per-caller rate limit: principal name, else the TCP peer) may hold at most 100 open streams across all four endpoints; a further stream request gets `429 Too Many Requests` without opening the stream.

**Single-instance only.** Events are broadcast in-process via a `MutableSharedFlow`; there is no cross-node pub/sub. Horizontal scaling (running multiple Homer replicas behind a load balancer) would require adding Redis/Kafka as a backplane — not currently supported.

### GET /api/v1/stream/processes

Emits process lifecycle and question events, filtered to the runs the caller can see (see [Process ownership and visibility](#process-ownership-and-visibility)).

| Event | Data |
|-------|------|
| `process.status` | `{"processId": int, "status": "CREATED"\|"WORKING"\|"FINISHED"\|"FAILED"\|"KILLED"\|"UNKNOWN", "agentName": string}` |
| `process.cmd` | `{"processId": int, "label": string\|null, "startedAt": int\|null}` (Unix seconds) |
| `question.asked` | `{"processId": int, "questionId": string, "agentName": string}` |
| `question.answered` | `{"processId": int, "questionId": string}` |
| `question.expired` | `{"processId": int, "questionId": string}` |

```bash
curl -N "http://localhost:8080/api/v1/stream/processes" \
  -H "Authorization: Bearer your-token"
```

### GET /api/v1/stream/agents

Emits an event every time the agent set is reloaded: after `POST /api/v1/agents/reload`, and after an agent file is written or deleted, or an agent created, through the agent files API. Each list is filtered to the agents the caller holds a grant on.

| Event | Data |
|-------|------|
| `agent.reload` | `{"added": [string], "updated": [string], "removed": [string]}` |

### GET /api/v1/stream/processes/{processId}/llm-events

Streams the canonical LLM events of one run the caller can see (`404` otherwise, the same as not-found), live or finished. Query `cmd` follows one `!<claude>`/`!<codex>`/`!<opencode>` command (`404` for any other command type); without it the stream follows every LLM command of the run in order. Poll latency is set by [`server.claudeStreamPollMs`](#server-fields). Event kinds and resume: [README](README.md#get-apiv1streamprocessesprocessidllm-events).

| Event | Id | Data |
|-------|-----|------|
| `llm.event` | `cmdIndex:attemptIndex:lineOffset:eventIndex` | `{"processId": int, "cmdIndex": int, "attemptIndex": int, "event": object}` — `event` is one canonical event |
| `llm.end` | — | `{}` — emitted when the process reaches a terminal status (with `cmd`, also once a later command has started) and the stdout has been drained |

### GET /api/v1/stream/processes/{processId}/logs

Tails a command's stdout or stderr file. New bytes are emitted as they are written.

**Query Parameters**:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `cmd` | integer | No | `0` | Command index (0-based) |
| `stream` | string | No | `out` | `out` or `err` |

**Headers**:

| Header | Description |
|--------|-------------|
| `Last-Event-ID` | Byte offset to resume from on reconnect. Absent = start from byte 0 (the whole file so far). |

| Event | Id | Data |
|-------|-----|------|
| `log.chunk` | `<new-byte-offset>` | `{"content": string}` — the offset travels only in the SSE `id`; content is at most 1 MiB, cut on a whole UTF-8 character, so chunks concatenate safely |
| `log.end` | — | `{}` — emitted when the process reaches a terminal status and the final bytes have been flushed |

```bash
curl -N "http://localhost:8080/api/v1/stream/processes/12345/logs?cmd=0&stream=out" \
  -H "Authorization: Bearer your-token"
```

The tailer polls file length every 500 ms — sufficient latency for UI updates without relying on unreliable cross-platform filesystem watch APIs.

## Runners (pluggable execution backends)

By default every agent command runs on Homer's own host via the
built-in `local` runner. Configuring entries under `runners:` unlocks
alternate backends — currently `kubernetes`, `ssh` and `external`
(pull-mode runner instances, see [External runners](#external-runners-pull-mode))
— that agents opt into by setting `runner:` in their definition (see
[AGENT.md](AGENT.md)).

```yaml
workspaceRootPath: /var/homer/workspaces

runners:
  k8s-dev: !<kubernetes>
    namespace: homer
    image: ubuntu:24.04
    resources:
      requests: { cpu: "100m", memory: "256Mi" }
      limits:   { cpu: "2",    memory: "2Gi"  }
    syncExcludes: [".git", ".gradle", "build"]
    extraUploads:
      - local: /app/scripts
        remote: .homer/scripts
        env: SCRIPTS_DIR
    volumes:
      - name: scripts
        mountPath: /scripts
        readOnly: true
        source: !<configMap> { name: homer-shared-scripts }
      - name: gradle-home
        mountPath: /home/homer/.gradle
        source: !<pvc> { claimName: homer-gradle-cache }
    homerApiUrl: http://homer.homer.svc.cluster.local:8080
```

YAML uses kaml's tag-style polymorphism for the runner backend and any
nested sealed type — `!<kubernetes>` on the runner and `!<configMap>` /
`!<secret>` / `!<hostPath>` / `!<pvc>` / `!<emptyDir>` on a volume
`source` — the same style as `apiAuth: !<bearer>` above. The equivalent
JSON form uses a `"type"` discriminator instead (`"type": "kubernetes"`,
`"source": { "type": "configMap", … }`).

The key `local` is reserved for the built-in host runner and is rejected
at startup if it appears under `runners:`.

### Remote runner requirements

The `local` runner inherits Homer's own host environment. Any **remote**
runner (`kubernetes`, `ssh`, `external`) instead runs commands in an
environment you provision — the container image, the remote host, or the
machine a pull-mode runner instance runs on — and Homer installs nothing
there, so that environment must supply every binary a command relies on:

- **`/bin/sh`** and **`curl`** — required by the `homer-ask` /
  `homer-dispatch` / `homer-result` / `homer-join` / `homer-tag` /
  `homer-inbox` helpers, which are POSIX-shell scripts with a hard-coded `#!/bin/sh` that call the
  Homer API over `curl`; a different configured `shell` does not replace
  `/bin/sh`. Without them, operator questions, ask-and-dispatch, result
  fetching, runtime run tags and operator messages fail even though plain
  commands still run.
- **`claude`** — for agents that use `Claude` commands.
- **`codex`** — for agents that use `Codex` commands.
- **`opencode`** — for agents that use `Opencode` commands.
- **`homer_langgraph`** — for agents that use `LangGraph` commands.
- Any other executable your `Cmd` / `Script` commands invoke — the agent
  author's responsibility, exactly as on the `local` runner.

For an `external` domain this is the image or host the `homer-runner`
instance itself runs on. The reference `homer-runner` image is
deliberately minimal — `bash`, `curl`, `git`, `tar`, `gzip`,
`util-linux` and a JRE — and ships none of `claude`, `node` or `python`,
so agents needing those need an image derived from it; see
[DOCKER.md](../DOCKER.md) § *homer-runner image*.

Also set **`homerApiUrl`** (per-runner, below) to a URL the remote
environment can reach Homer on. It is exposed remotely as `HMR_API_URL`
and backs every feature that calls back over HTTP — the `homer-*`
helpers (`homer-ask`, dispatch/join/continuation, result fetching, `homer-tag`, `homer-inbox`) and the
MCP question tools. If it is unset or unreachable, those fail silently
while ordinary commands succeed, which looks like partial breakage rather
than a misconfiguration. `RunAgent` does not use it: the child run is
started in-process by Homer ([ADR-0030](../docs/adr/0030-runagent-in-process-dispatch.md)).

### KubernetesRunner fields

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `type` | const | Yes | - | Must be `kubernetes`. |
| `kubeconfig` | `String?` | No | `null` | Path to kubeconfig. `null` = in-cluster config or `$KUBECONFIG`. |
| `namespace` | `String` | No | `default` | Namespace pods are created in. |
| `image` | `String` | Yes | - | Container image for the main container. |
| `shell` | `String` | No | `/bin/sh` | Shell used as PID 1 (`sleep infinity`) and for `Script` commands. |
| `resources` | `ResourceSpec` | No | `{}` | `requests` / `limits` maps (same keys as Kubernetes). |
| `volumes` | `List<VolumeSpec>` | No | `[]` | Extra volumes (configMap, secret, pvc, hostPath, emptyDir) — also how to mount a persistent build cache (e.g. a `pvc` source at a Gradle home), independent of the workspace `emptyDir`. |
| `annotations` | `Map<String, String>` | No | `{}` | Annotations set on every pod this runner creates, passed through verbatim (Homer sets none of its own). The hook for annotation-driven cluster tooling — secret injectors (e.g. Vault Agent Injector), service meshes, metric scrape config. See [Injecting secrets from an external store](#injecting-secrets-from-an-external-store). |
| `initContainers` | `List<ContainerSpec>` | No | `[]` | Init containers; pod waits for each to terminate with exit 0. |
| `podStartTimeoutSeconds` | `Long` | No | `120` | How long to wait for readiness before failing the run. |
| `keepPodOnFailure` | `Boolean` | No | `false` | On a failed run — or a failed pod startup, if the pod already reached a node — patch the pod's TTL instead of deleting it: a failed run keeps its synced workspace under `/homer/workspace` available for inspection, a startup-failed pod keeps its image/init state inspectable. A pod that never got scheduled is always deleted (its TTL could never fire). A command timeout and an output-cap kill both count as a failure; an operator-initiated kill does not (the pod is still deleted). |
| `keepPodTtlSeconds` | `Long` | No | `3600` | TTL applied to kept-for-debug pods. |
| `homerApiUrl` | `String?` | No | `null` | Cluster-internal Homer URL; exposed in-pod as `HMR_API_URL` so the `homer-*` helpers and MCP question tools can reach Homer from inside the pod. |
| `env` | `Map<String, String>` | No | `{}` | Per-runner environment variables, layered between `globalEnv` and agent `env` — the place for values describing *this* runner's host/image (`PATH` with `/opt/homebrew/bin` on a macOS ssh box, `JAVA_HOME`, `HOME` for a uid with no passwd entry). Values support `@SYSENV:VAR` (fail-fast at startup, and the resolved names join the telemetry redaction set exactly like `@SYSENV` in `globalEnv`); keys must not be blank or use the reserved `HMR_` prefix. Remote runners receive no `parentEnvAllowlist` host env, so this is also where a value the image must still see goes. Like agent `env`, values ride the per-exec `env K=V…` delivery — **visible in the remote process list (`ps`)** on multi-tenant hosts; forward credentials via `@SYSENV:` and prefer file delivery where possible. For a run that selected this runner via `runnerTags` rather than by alias, these keys are instead merged **above** agent `env` when the run is claimed — see [Runner claim queue](#runner-claim-queue). |
| `tags` | `List<String>` | No | `[]` | Tags this runner serves for agents that select a runner with `runnerTags` (every requested tag must be present). Empty = reachable by alias only. Shape `^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$`, at most 32. See [Runner claim queue](#runner-claim-queue). |
| `concurrency` | `Int?` | No | `null` | Runs this runner serves at once. A run addressed to a saturated runner waits in the claim queue and fails at the claim TTL. `null` = unbounded. |
| `imagePullSecrets` | `List<String>` | No | `[]` | Pull secrets referenced by name. |
| `serviceAccountName` | `String?` | No | `null` | ServiceAccount for the pod. |
| `securityContext` | `SecurityContextSpec?` | No | `null` | Pod- and container-level security context. Required when the namespace enforces Pod Security Admission `restricted` or otherwise sets `runAsNonRoot: true` — see below. |
| `syncExcludes` | `List<String>` | No | `[]` | Directory-entry names excluded from upload (applied during sync-up only). A bare name (e.g. `build`) matches that entry at every directory level; a leading `/` (e.g. `/build`) anchors the match to the upload root only, so a path-shaped entry like `build/tmp` matches nothing unless anchored (`/build/tmp`). |
| `extraUploads` | `List<ExtraUploadSpec>` | No | `[]` | Host files/folders uploaded into every pod workspace on this runner (`{local, remote, env?}`); `remote` is projectRoot-relative in normalized form (no `.`, `//`, or trailing `/`) and may not target `workdir/`, `artifacts/`, `.homer/agent`, `.homer/bin`, or `.homer/mcp.json` — nor an ancestor of them like `.homer`; `env` (optional, non-`HMR_`) is repointed at the pod-local copy. `local` must exist at startup. See ADR-0042/0043. |

**`VolumeSpec`** (each `volumes` entry; also mountable by name from an init container):

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `name` | `String` | Yes | - | Volume name, referenced by init-container `volumeMounts`. |
| `mountPath` | `String` | Yes | - | Mount path in the main container. |
| `readOnly` | `Boolean` | No | `false` | Mount read-only in the main container. |
| `source` | `VolumeSource` | Yes | - | One of the sources below (tag-style in YAML, `"type"` discriminator in JSON). |

| `source` | Fields | Description |
|---|---|---|
| `hostPath` | `path` (required) | A path on the node. |
| `configMap` | `name` (required) | A ConfigMap in the runner's namespace. |
| `secret` | `secretName` (required) | A Secret in the runner's namespace. |
| `pvc` | `claimName` (required), `readOnly` (default `false`) | A PersistentVolumeClaim; `readOnly` is set on the claim reference. |
| `emptyDir` | none | A pod-scoped scratch directory. |

**`ContainerSpec`** (each `initContainers` entry). It also gets the workspace
mounts and the runner's container `securityContext`:

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `name` | `String` | Yes | - | Container name. |
| `image` | `String` | Yes | - | Container image. |
| `command` | `List<String>` | No | `[]` | Entrypoint override; empty = the image's own. |
| `args` | `List<String>` | No | `[]` | Arguments; empty = the image's own. |
| `env` | `Map<String, String>` | No | `{}` | Written into the pod spec verbatim (readable by anyone who can read the pod) — keep secrets out. |
| `volumeMounts` | `List<{name, mountPath, readOnly?, subPath?}>` | No | `[]` | Mounts of `volumes` entries by `name`; `readOnly` defaults to `false`, `subPath` to none. |

**`CapabilitiesSpec`** (`securityContext.capabilities`): `add` and `drop`,
each a `List<String>` of Linux capability names, default `[]`.

**`SeccompProfileSpec`** (`securityContext.seccompProfile`): `type`
(required; `RuntimeDefault`, `Localhost` or `Unconfined`) and
`localhostProfile` (`String?`, the profile path, needed when `type` is `Localhost`); both are passed to Kubernetes as-is.

### Running under Pod Security Admission `restricted`

If the target namespace is enforced with PSA `restricted` (or any
policy that sets `runAsNonRoot: true`), the kubelet rejects images
that default to root — including `busybox`, `ubuntu`, etc. — with
`CreateContainerConfigError: container has runAsNonRoot and image
will run as root`. Set `securityContext` to the canonical PSA-compliant
fields:

```yaml
runners:
  k8s-prod: !<kubernetes>
    namespace: homer-agents
    image: ubuntu:24.04
    securityContext:
      runAsUser: 1000
      runAsGroup: 1000
      runAsNonRoot: true
      fsGroup: 1000
      allowPrivilegeEscalation: false
      readOnlyRootFilesystem: true
      capabilities:
        drop: ["ALL"]
      seccompProfile:
        type: RuntimeDefault
```

The pod-level fields (`runAsUser`, `runAsGroup`, `runAsNonRoot`,
`fsGroup`, `seccompProfile`) are applied to `PodSpec.securityContext`.
The container-level fields (`runAsUser`, `runAsGroup`, `runAsNonRoot`,
`allowPrivilegeEscalation`, `readOnlyRootFilesystem`, `capabilities`,
`seccompProfile`) are applied to the **main container and every init
container**, so a `chown` init container is held to the same policy
as the main image.

Omitting `securityContext` (or leaving any field `null`) preserves the
prior behavior — Homer emits no security context, and pods inherit
their namespace's defaults.

### Workspace sync

Each pod gets its own workspace: a pod-local `emptyDir` mounted at the
fixed path `/homer/workspace` (not configurable — a pod per run means
no path collisions). The volume is mounted twice — once at the root
and once, via a `workdir` subPath, at the container's working
directory — so the working directory exists before any command runs.
Content moves between Homer and the pod over the existing exec
channel (the same mechanism `kubectl cp` uses): each direction is one
gzip'd tar stream, so many small files cost a single round-trip
(the driving case is large source trees, e.g. Gradle/Android builds).
Tar extraction (Homer's side for k8s sync-down and external-runner
downloads; `homer-runner` on the instance for uploads to it — both use the
same tar code) preserves a file's executable bit — normalized to
`0755` or `0644` as git normalizes a checkout — and drops setuid, setgid,
sticky and every other mode bit, so a run's scripts stay executable
while a remote tar cannot plant a privileged or world-writable file.
An entry whose mode cannot be normalized is refused rather than written
unnormalized: the target and, when the write landed through a dangling
symlink, the link's destination are removed, and the extraction fails.
See [ADR-0043](../docs/adr/0043-k8s-sync-workspaces.md).

- **Sync-up, before the run:** the whole local workspace (`workdir/`,
  `.homer/`, empty `artifacts/`), then the agent's own definition
  directory (`HMR_AGENT_DIR`) to `.homer/agent` — excluding any
  directory nested inside it that is itself an agent, and any symlinked
  directory (ADR-0068) — then
  each `extraUploads` entry — one tar stream per upload. `workdir`'s
  contents upload like the rest of the workspace; only the directory's
  own tar entry is omitted from the project-root upload, so tar never
  issues a `chmod`/`utime` against the pod's pre-created, kubelet-owned
  `workdir` subPath mount (below) — a workspace-authored directory
  named `workdir` deeper in the tree still gets its own entry. See
  [ADR-0054](../docs/adr/0054-kubernetes-workdir-uploads-without-its-tar-entry.md).
  `syncExcludes` entry names are excluded at every directory level
  unless anchored with a leading `/` (upload root only), uploads only (a
  download is unaffected). `HMR_WORK_DIR`, `HMR_ARTIFACTS_DIR`,
  `HMR_MCP_CONFIG`, and `PATH`'s `.homer/bin` are **not** identical
  paths in the pod as they are on the host — the pod-local values are
  rewritten by substituting the host `projectRoot` prefix with
  `/homer/workspace`; `HMR_AGENT_DIR` is overridden outright to the
  pod's `.homer/agent` copy. `extraUploads` entries are uploaded on
  every run (full agent runs, and `runnerCmd` sessions right after
  `createWorkspace()` and before their checks), filtered by
  `syncExcludes` like every upload. A workspace symlink that resolves
  outside the workspace root is skipped with a warning rather than
  uploaded (its content is not transferred); one resolving inside is
  still dereferenced, except that a symlink to a directory already
  uploaded is skipped with a warning (a real directory never is), so a
  symlink cycle ends instead of looping forever, and the walk descends at
  most 64 directory levels (the 65th is not entered). A hard-linked file
  (more than one link) met during the walk is skipped with a warning like an escaping symlink,
  since its other name cannot be containment-checked; an upload source
  — file or directory — that is a symlink, or a single-file source that
  is hard-linked, is refused and fails the upload. See
  [ADR-0042](../docs/adr/0042-runner-extra-uploads.md).
- **Sync-down, after the run:** the pod's `artifacts/` directory is
  always downloaded back (additive overlay — it never overwrites the
  local `cmd_<i>.out`/`.err` files streamed live during the run); the
  pod's `workdir/` is downloaded too only when the workspace is kept
  (`cleanAfterFinish: false`), and as a mirror — modified files
  overwrite the host copy and files deleted in the pod are removed
  from it, once the download is confirmed fully successful — so a
  kept workspace's host copy converges to the pod's cwd at run end and
  that content seeds the next pod's cwd on its sync-up, same as
  ssh/local. The download uses `tar`'s dereferencing mode, so a
  symlink's target content — not a link entry — is what transfers.
  Sync-down is best-effort: a failure, or a missing pod path, leaves
  the host copy possibly partially updated, never pruned, and is
  logged loudly but does not fail an otherwise-successful run. A
  download is also refused once it has extracted 1 GiB (not
  configurable) and is then treated like any other sync-down failure.
  See [ADR-0055](../docs/adr/0055-workdir-sync-down-is-a-mirror.md).
- **`cleanWorkspace` is a no-op** on kubernetes — the `emptyDir` dies
  with the pod, so pod deletion *is* the cleanup. A pod kept via
  `keepPodOnFailure` deliberately keeps its workspace too; that
  retention is the point of keeping it. `keepPodTtlSeconds` patches
  `spec.activeDeadlineSeconds`, which Kubernetes counts from **pod
  start**, not from the failure — a long-running agent has already
  spent part of that budget by the time it fails, so a run whose
  duration approaches `keepPodTtlSeconds` may leave little or no
  inspection window before the kubelet terminates the pod.
- **No live tailing mid-run.** Artifacts only appear host-side at the
  sync-down above — unlike the old shared-PVC model, there is no
  shared filesystem for `ArtifactService` to tail during the run.
  Command stdout/stderr are unaffected: they still stream live, since
  those are captured over the same exec channel as the command itself,
  not read from the pod's filesystem.
- Workspace lifecycle (`create`, `cleanup`, purge) still runs
  host-side against Homer's own copy; Homer remains the source of
  truth and pods stay ephemeral consumers.
- **Node disk is the constraint**, since the workspace is an
  `emptyDir` on the node. `resources.requests`/`limits.ephemeral-storage`
  on the container **does** bound it — `emptyDir` usage counts against
  the pod's ephemeral-storage limit, and the kubelet evicts pods that
  exceed it — so that's the lever to set a hard cap; `syncExcludes` (to
  shrink what gets synced, e.g. `.git`, build output) is the other one.
  There is no size cap on the volume itself absent that limit.

### `homerApiUrl` and `HMR_API_URL` (v1 note)

When set, the kubernetes runner overrides `HMR_API_URL` in the
workspace env — delivered to commands per-exec like the rest of the
env, not baked into the pod spec (see
["Env delivery over exec, not the pod spec"](#env-delivery-over-exec-not-the-pod-spec)
below). The local runner defaults `HMR_API_URL` to
`http://127.0.0.1:<server.port>` when nothing else (operator
`globalEnv`, agent `env`) already provides one, so local commands —
e.g. the `homer-ask` question helper — reach Homer's own API without
configuration; in-cluster runs resolve Homer's Service DNS via
`homerApiUrl`.

v1 assumption: `homerApiUrl` is plain HTTP and must be reachable
cluster-internally (e.g. a ClusterIP Service DNS name). TLS / mTLS /
ingress auth between pod and Homer is out of scope for v1.

### Image requirements

The main container image must ship whatever binaries the agent's
commands expect:

- **`tar` and `gzip`** — required for **every** run, regardless of
  which command types the agent uses: workspace sync (upload before
  the run, download after) is tar-over-exec and fails without them.
  Busybox's versions suffice; this is the same requirement `kubectl
  cp` already imposes, so it holds in effectively every build image.
- `Cmd` — the binary named by `cmd[0]` must be on `PATH` or given by
  absolute path (`env(1)` is assumed present).
- `Script` — the configured `shell` must exist at its path.
- `Claude` — the `claude` CLI must be installed; Homer compiles the
  prompt host-side and pipes it via stdin.
- `Codex` — the `codex` CLI must be installed; the prompt is delivered
  the same way.
- `Opencode` — the `opencode` CLI must be installed; the prompt is
  delivered the same way.
- `RunAgent` — no binary and no pod egress required; the child run is
  started in-process on Homer's side.

### RBAC

Homer's ServiceAccount needs, in every namespace referenced by a
kubernetes runner:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: homer-runner
  namespace: homer
rules:
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch", "create", "delete", "patch"]
  # Startup readiness polling and failure diagnostics read the status
  # SUBRESOURCE (readNamespacedPodStatus); `get` on `pods` does not cover it.
  - apiGroups: [""]
    resources: ["pods/status"]
    verbs: ["get"]
  # Homer's Java client execs over WebSocket — an HTTP GET on /exec,
  # authorized as the `get` verb. `create` covers only SPDY (kubectl-style
  # POST) exec. Without `get`, every in-pod command fails the upgrade
  # with 403.
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create", "get"]
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: homer-runner
  namespace: homer
subjects:
  - kind: ServiceAccount
    name: homer
    namespace: homer
roleRef:
  kind: Role
  name: homer-runner
  apiGroup: rbac.authorization.k8s.io
```

#### Env delivery over exec, not the pod spec

Pod specs carry **no** agent environment at all — the main container
is started with `sleep infinity` and nothing else. Every command's
merged env (declared `secret` inputs, `@SYSENV` values, agent `env`,
`resultEnvParam` values from earlier commands, everything) travels
per-exec as `env KEY=VAL … argv` over the API-server exec websocket
(TLS), the same channel the command's stdio uses — it is never
written into a stored Kubernetes object. `kubectl get pod -o yaml` in
the runner's namespace no longer discloses agent secrets, and a
manual `kubectl exec` shell into a kept-for-debug pod sees no agent
env either — only the exec sessions Homer itself starts carry it.

The residual exposure surface is narrower but not zero:

- **API audit logs.** The exec client passes the full merged env as
  repeated `env KEY=VAL …` arguments in the exec request's `command=`
  query parameters — part of the request URI, not the body. Kubernetes
  audit events record `requestURI` at **every** audit level, including
  the `Metadata` level most clusters default to for `pods/exec` — so
  any cluster auditing exec calls at all captures the full per-exec env
  in its audit log, not just at `RequestResponse`. Operators should
  scope the audit policy for `pods/exec` accordingly (e.g. exclude it,
  or treat the audit backend as holding the same secrets the pod spec
  used to).
- **`pods/exec` RBAC remains the boundary that matters** — anyone who
  can `create` (SPDY) **or `get`** (WebSocket) on `pods/exec` in the
  runner's namespace can start their own exec session against a
  running pod for the life of the run.

The dedicated-namespace guidance still applies: run the kubernetes
runner in a **dedicated namespace** and restrict `pods`/`pods/exec`
there to operators only. A dedicated namespace also bounds the
[startup sweep](#startup-sweep) blast radius. See
[ADR-0043](../docs/adr/0043-k8s-sync-workspaces.md) for the security
considerations behind this change.

#### Injecting secrets from an external store

`annotations` is the hook for annotation-driven secret injectors (Vault
Agent Injector, the Secrets Store CSI driver, External Secrets). Homer
emits the annotations verbatim and knows nothing about the store: the
injector's sidecar renders the secret into the pod at runtime, and the
agent's command reads it there.

```json
{
  "runners": {
    "k8s": {
      "type": "kubernetes",
      "image": "registry.example.com/homer-runtime:1.15.0",
      "serviceAccountName": "homer-runner",
      "annotations": {
        "vault.hashicorp.com/agent-inject": "true",
        "vault.hashicorp.com/role": "homer-runner",
        "vault.hashicorp.com/agent-inject-secret-db": "secret/data/homer/db"
      }
    }
  }
}
```

Prefer **file** delivery (the sidecar writes `/vault/secrets/db`) over
having the agent shell out to a CLI. Reading a secret through a command
puts its value on that command's stdout, and hoisting it into a later
command with `resultEnvParam` then routes it through the per-exec env
described [above](#env-delivery-over-exec-not-the-pod-spec) — which is
exactly the audit-log surface file delivery avoids. Read the rendered
file in the command that consumes it.

Homer never resolves values from the store itself: it holds no store
credentials, and no secret fetched this way passes through the Homer
process. Pod authentication is the ServiceAccount's (`serviceAccountName`),
so the store's policy — not Homer's config — decides what a runner can
read. See [ADR-0045](../docs/adr/0045-external-secret-delivery.md).

Keep the injector's pre-populate init container enabled: pod readiness
requires every init container to exit 0, so a pre-populate render is
guaranteed to finish before the first command runs. A long-running
sidecar is not required to be ready, so it cannot deadlock a run —
which also means secrets arriving *only* from the sidecar have no such
ordering guarantee.

### Startup sweep

On boot, for each configured kubernetes runner, Homer lists pods
labelled `app.kubernetes.io/managed-by=homer` **and** carrying this
instance's `homer.app/instance=<id>` label, and deletes any whose
`homer.app/process-id` label does not match an active process. The
sweep result (scanned / deleted / errors) is reported under
`/api/v1/health`. Pods kept via `keepPodOnFailure` carry a
`homer.app/kept` label and are skipped by the sweep regardless of
process activity; their `keepPodTtlSeconds` still bounds their
lifetime.

`<id>` is a stable per-instance identifier generated on first boot and
persisted in Homer's SQLite database (distinct from the per-boot
`HMR_SERVER_ID`), so the sweep still reaps *this* instance's own orphan
pods across a restart while never touching pods created by a **different**
Homer instance that shares the same namespace — each instance has its own
database and therefore its own id. No configuration is required. Because
the scope now depends on this label, orphan pods created by a Homer
version predating the label are not auto-swept and must be removed
manually (or left to a Kubernetes TTL controller).

### `ssh` runner

The `ssh` runner runs an entire agent on a remote host reached over
SSH, syncing the workspace up before the run and pulling results back
after — the same "one session per agent run, synced rather than
shared" model as the kubernetes runner, with SFTP standing in for
tar-over-exec. See [ADR-0032](../docs/adr/0032-ssh-runner-backend.md)
for the design rationale.

```yaml
runners:
  build-box: !<ssh>
    host: build01.internal
    port: 22
    user: homer
    auth: !<key>
      privateKeyPath: /etc/homer/ssh/id_ed25519
    knownHostsPath: /etc/homer/ssh/known_hosts
    remoteWorkspaceRoot: /home/homer/homer-workspaces
    extraUploads:
      - local: /app/scripts
        remote: .homer/scripts
        env: SCRIPTS_DIR
```

YAML uses kaml's tag-style polymorphism (`!<ssh>`, `!<key>`) — the same
style as `apiAuth: !<bearer>` above. The JSON form uses a `"type"`
discriminator instead:

```json
{
  "runners": {
    "build-box": {
      "type": "ssh",
      "host": "build01.internal",
      "port": 22,
      "user": "homer",
      "auth": { "type": "key", "privateKeyPath": "/etc/homer/ssh/id_ed25519" },
      "knownHostsPath": "/etc/homer/ssh/known_hosts",
      "remoteWorkspaceRoot": "/home/homer/homer-workspaces",
      "extraUploads": [{"local": "/app/scripts", "remote": ".homer/scripts", "env": "SCRIPTS_DIR"}]
    }
  }
}
```

#### SshRunner fields

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `type` | const | Yes | - | Must be `ssh`. |
| `host` | `String` | Yes | - | Remote host to connect to. |
| `port` | `Int` | No | `22` | SSH port. |
| `user` | `String` | Yes | - | Remote user to authenticate as. |
| `auth` | `SshAuth` | Yes | - | `key` (private-key file) or `agent` (ssh-agent) — see below. |
| `knownHostsPath` | `String?` | No | `null` | Path to a `known_hosts` file. Required when `strictHostKeyChecking` is `true` (the default). |
| `strictHostKeyChecking` | `Boolean` | No | `true` | `true` verifies the remote host key against `knownHostsPath` and rejects unknown/mismatched keys; `false` accepts any host key (opt-in, insecure — only for trusted networks/testing). |
| `remoteWorkspaceRoot` | `String` | Yes | - | Remote directory the synced workspace is written under; combined with the run's relative workspace path to compute the remote project root. Must be an absolute, normalized path (no empty, `.` or `..` segments) other than `/` — config load rejects anything else, because `cleanAfterFinish` hands the project root under it to `rm -rf` ([ADR-0067](../docs/adr/0067-ssh-cleanup-via-rm-rf.md)). |
| `homerApiUrl` | `String?` | No | `null` | Homer URL reachable from the remote host; overrides `HMR_API_URL` remotely (like the kubernetes runner's `homerApiUrl`) so the `homer-*` helpers and MCP question tools reach Homer instead of `127.0.0.1`. |
| `env` | `Map<String, String>` | No | `{}` | Per-runner environment variables for this remote host, layered between `globalEnv` and agent `env` — e.g. `PATH` with `/opt/homebrew/bin`, `JAVA_HOME`, `CI`. Values support `@SYSENV:VAR` (fail-fast at startup, and the resolved names join the telemetry redaction set exactly like `@SYSENV` in `globalEnv`); keys must not be blank or use the reserved `HMR_` prefix. The ssh target's own login shell supplies `HOME`/`PATH`/`LANG`/`TZ` unless overridden here, because remote runners receive no `parentEnvAllowlist` host env — see [Remote runners](#remote-runners). Like agent `env`, values ride the per-exec `env K=V…` delivery and are **visible in the remote process list (`ps`)** on multi-tenant hosts (see Security posture below). For a run that selected this runner via `runnerTags` rather than by alias, these keys are instead merged **above** agent `env` when the run is claimed — see [Runner claim queue](#runner-claim-queue). |
| `tags` | `List<String>` | No | `[]` | Tags this runner serves for agents that select a runner with `runnerTags` (every requested tag must be present). Empty = reachable by alias only. Shape `^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$`, at most 32. See [Runner claim queue](#runner-claim-queue). |
| `concurrency` | `Int?` | No | `null` | Runs this runner serves at once. A run addressed to a saturated runner waits in the claim queue and fails at the claim TTL. `null` = unbounded. |
| `shell` | `String` | No | `/bin/sh` | Shell used for `Script` commands on the remote host. |
| `connectTimeoutSeconds` | `Long` | No | `30` | Timeout for connect and authentication. |
| `idleTimeoutSeconds` | `Long?` | No | `null` | SSH-idle timeout in seconds. `null` disables it: the SSH session is never closed for lack of output — a remote command that stays quiet (compile, `git clone`, a long Claude tool call) is a normal state, and the command's own `timeout` is the intended cap. Set a value to re-enable MINA's inactivity disconnect deliberately. |
| `heartbeatIntervalSeconds` | `Long?` | No | `30` | SSH keepalive interval in seconds; `null` disables keepalives. Heartbeats keep NAT/proxy idle timers on the path alive and let the client detect a dead link while the idle timeout is off. |
| `sftpTransferTimeoutSeconds` | `Long?` | No | `300` | Per-operation SFTP transfer deadline in seconds; `null` disables the bound. Each blocking SFTP round trip (file copy, directory listing, stat, mkdir, the SFTP subsystem handshake, and the client close) must complete within this time or the SSH session is closed and the transfer fails — a peer answering keepalives while stalling SFTP data cannot hold a run slot and the workspace lock indefinitely; a timed-out or failed close is the exception: it is logged and swallowed and does not fail the operation. Bounds SFTP operations only, not remote commands. |
| `syncExcludes` | `List<String>` | No | `[]` | Directory-entry names excluded from upload (applied during sync-up only). A bare name (e.g. `build`) matches that entry at every directory level; a leading `/` (e.g. `/build`) anchors the match to the upload root only, so a path-shaped entry like `build/tmp` matches nothing unless anchored (`/build/tmp`). |
| `extraUploads` | `List<ExtraUploadSpec>` | No | `[]` | Host files/folders uploaded into every remote workspace on this runner (`{local, remote, env?}`); `remote` is projectRoot-relative in normalized form (no `.`, `//`, or trailing `/`) and may not target `workdir/`, `artifacts/`, `.homer/agent`, `.homer/bin`, or `.homer/mcp.json` — nor an ancestor of them like `.homer`; `env` (optional, non-`HMR_`) is repointed at the remote copy. `local` must exist at startup. See ADR-0042. |

**No idle disconnect.** The SSH client's idle timeout is disabled by
default: a session is never closed for lack of output. A remote command
that stays quiet — a compile, `git clone`, a long tool call — is a
normal state, and the command's own `timeout` is the intended cap.
Keepalives every 30 s (`heartbeatIntervalSeconds`) keep NAT/proxy idle
timers on the path alive and still detect a dead link; set the field to
`null` to disable them.

**SFTP transfer deadline.** Keepalives only disconnect a peer that
*stops answering*; they say nothing about whether SFTP data is
actually flowing. `sftpTransferTimeoutSeconds` (default `300`) bounds
each blocking SFTP round trip (file copy, directory listing, stat,
mkdir, the SFTP subsystem handshake, and the SFTP client's own close)
independently of keepalive traffic, so a peer that answers keepalives
while trickling or withholding SFTP data cannot hold a
`Dispatchers.IO` worker, the workspace lock, and a run slot forever. On
expiry the SSH session is closed immediately to unblock the interrupted
operation and the transfer fails. A release that fails or exceeds the
deadline is logged and swallowed — the session is discarded at the end
of the run either way, so cleanup never masks the operation's own
outcome. Set the field to `null` to disable the bound (e.g. a trusted,
very slow link with multi-gigabyte transfers).

**Directory listing cap.** During sync-down each remote directory
listing is read into memory before its entries are transferred, so it
is capped at 100 000 entries per directory; a listing over the cap
fails the download. The cap is fixed, not configurable. Remote cleanup
removes the root with one `rm -rf` over the exec channel (10-minute
cap) and only falls back to the SFTP walk — which streams its listings
and is bounded by the transfer deadline — when that command fails,
exits non-zero, times out or exceeds the output cap ([ADR-0067](../docs/adr/0067-ssh-cleanup-via-rm-rf.md)).

#### Keepalive misses

The client sends its keepalive as a global request and counts
consecutive unanswered ones (bounded internally at 3, roughly
`3 × heartbeatIntervalSeconds` of dead time). The counter resets on
*any* heartbeat reply, so an intermittently slow link tolerates misses;
it is not a wall-clock bound on a command's runtime, just liveness of
the peer.

#### `auth`: key file vs. ssh-agent

```yaml
auth: !<key>
  privateKeyPath: /etc/homer/ssh/id_ed25519
  passphrase: null   # optional, for an encrypted key
```

- **`key` (supported)** — loads a private key file (`privateKeyPath`,
  optionally encrypted with `passphrase`) and authenticates with it.
  This is the tested, supported auth path.
- **`agent` (experimental — not yet verified)** — intended to
  authenticate via a running `ssh-agent` without Homer touching key
  material directly. The current implementation registers no explicit
  identity and relies on MINA SSHD picking up the agent socket
  automatically; this path has **not** been verified to actually
  authenticate against a real `ssh-agent` and may be a no-op. Prefer
  `key` until this is confirmed.

#### Security posture

- **Strict host-key checking is the default** (`strictHostKeyChecking:
  true`) and requires `knownHostsPath` — Homer refuses to connect if
  the flag is left `true` without a known-hosts file, rather than
  silently accepting an unverified host key. Setting
  `strictHostKeyChecking: false` accepts any host key and should only
  be used on trusted networks or for local testing.
- **The operator is responsible for the remote host's toolchain** —
  Homer does not provision the remote machine. It must already have
  whatever binaries the agent's commands need (e.g. the `claude` CLI
  for a `Claude` command, `git`/build tools for `Cmd`/`Script`), the
  same responsibility documented for the kubernetes runner's image.
- **Orphaned-process reaping needs `setsid` on the target.** On
  kill/timeout Homer reaps the command's whole remote process group so a
  backgrounded/detached child cannot orphan on the host. This is enabled
  automatically when a one-shot probe finds `setsid -w` (util-linux) on
  the target; where it is absent, teardown degrades to closing the exec
  channel only (a detached child may then survive) — no configuration
  either way. See [ADR-0041](../docs/adr/0041-process-group-reaping-on-kill.md).
- **Fast workspace cleanup needs `rm` on the target.** When
  `cleanAfterFinish` clears the workspace, Homer removes the remote
  project root with one `rm -rf` over the exec channel; where `rm` is
  absent the run degrades to the per-file SFTP walk, which can hold the
  workspace lock for minutes on a build-sized tree. No configuration
  either way. The path handed to `rm` is re-checked first: it must be
  absolute, normalized, at least two segments deep and below
  `remoteWorkspaceRoot`, and no component below `remoteWorkspaceRoot`
  may be a symlink — otherwise the sweep is refused (logged as a cleanup
  failure) rather than widened. See [ADR-0067](../docs/adr/0067-ssh-cleanup-via-rm-rf.md).
- **Env values are visible in the remote process list.** Commands are
  sent as `cd <workdir> && exec env K=V… <argv…>` over the SSH exec
  channel (many sshd configs reject `SendEnv`/`AcceptEnv`), so any
  secret passed via `env` is visible to other users on the remote host
  via `ps` for the life of the command — the same property k8s exec
  has. Avoid putting secrets in `env` on multi-tenant remote hosts.

#### Workspace sync

- **Sync-up, before the run:** the whole local workspace (`workdir/`,
  `.homer/`, empty `artifacts/`) is uploaded via SFTP to
  `remoteWorkspaceRoot/<relative-workspace-path>`, followed by the
  agent's own definition directory (`HMR_AGENT_DIR`) to
  `<remote-project-root>/.homer/agent` — upload-only, so agent
  changes made remotely are not pulled back — excluding any directory
  nested inside it that is itself an agent (ADR-0068), since a nested
  agent is a different agent with its own definition and grants, and any
  symlinked directory. POSIX permissions are
  preserved so tooling like `homer-ask` stays executable.
  `syncExcludes` is applied at every directory level unless anchored
  with a leading `/` (upload root only).
  `extraUploads` entries are uploaded on every run (full agent runs, and
  `runnerCmd` sessions before their checks), filtered by `syncExcludes`
  like every upload, and swept with the workspace. **Security
  contract:** entries are replicated to every remote workspace with
  local file modes copied and only best-effort deletion, and uploaded
  scripts are `source`d by agents — do not put secrets in declared
  folders, keep `remoteWorkspaceRoot` permissions `0700` on shared
  hosts. A symlink that resolves outside the uploaded folder is
  skipped with a warning rather than uploaded (its content is not
  transferred); one resolving inside is still dereferenced (declare
  only curated folders), descending into its checked realpath. A
  symlink to a directory already uploaded is skipped with a warning (a
  real directory never is), so a symlink cycle ends instead of looping
  forever, and the walk descends at most 64 directory levels (the 65th
  is not entered). A hard-linked file (more than one link) met during
  the walk is skipped with a warning like an escaping symlink, since its
  other name cannot be containment-checked; an upload source
  — file or directory — that is a symlink, or a single-file source that
  is hard-linked, is refused and fails the upload. See
  [ADR-0042](../docs/adr/0042-runner-extra-uploads.md).
- **Sync-down, after the run:** the remote `artifacts/` directory is
  always downloaded back (additive overlay — it never overwrites the
  local `cmd_<i>.out`/`.err` files streamed live during the run); the
  remote `workdir/` is downloaded too only when the workspace is kept
  (`cleanAfterFinish: false`), and as a mirror — modified files
  overwrite the host copy and files deleted remotely are removed from
  it, once the SFTP download completes fully. Sync-down is best-effort
  — a failure, or a missing remote path, leaves the host copy possibly
  partially updated, never pruned, and is logged loudly but does not
  fail an otherwise-successful run. See
  [ADR-0055](../docs/adr/0055-workdir-sync-down-is-a-mirror.md).
- If `cleanAfterFinish` clears the workspace, the remote project root
  is also removed — with a single `rm -rf` over the exec channel, falling
  back to a per-file SFTP walk only if that command fails, exits
  non-zero, times out or exceeds the output cap ([ADR-0067](../docs/adr/0067-ssh-cleanup-via-rm-rf.md)).
  Otherwise it is left on the remote host for inspection. The process is
  marked finished and its lock released only after this removal, so on a
  host without `rm` a large workspace still costs the walk.

### Runner claim queue

Every run, whatever runner it targets, is placed in one in-memory claim queue
and is taken by the first runner that matches it and has a free permit
(ADR-0066). From the run's point of view:

1. The run enters the queue with a filter, either the alias from `runner:` or
   the tag set from `runnerTags:`, and its claim TTL starts.
2. A runner takes the run only if it matches the filter **and** has a free
   permit. A runner with `concurrency: 2` and two runs in flight leaves a third
   waiting. A runner without `concurrency` is unbounded and takes the run at
   once.
3. One claim TTL (`server.runnerClaimTimeoutSec`, or the agent's
   `runnerClaimTimeoutSec`) covers the whole wait. At expiry the run fails and
   the failure names what was observed, for example
   `No runner claimed the run within 300 s (runner 'build-host'): runner 'build-host' at concurrency 2/2`
   or `... (tags [gpu, linux]): no registered runner matches tags [gpu, linux]`.
   The TTL bounds the wait for a *claim*. A run claimed just before the TTL
   expired is given the same time again for its runner to open the session,
   so a run whose runner claims it and then never delivers a session fails
   after at most twice the TTL.
4. `server.maxPendingRunnerClaims` bounds the queue; a run arriving at the bound
   fails immediately.

```yaml
server:
  runnerClaimTimeoutSec: 300      # default claim TTL; agents override per agent
  maxPendingRunnerClaims: 1000    # runs waiting in the claim queue, all agents together
  localRunner:
    tags: [linux]                 # the built-in local runner serves these runnerTags
    concurrency: 2                # runs at once on the local runner; omit for unbounded
runners:
  build-host: !<ssh>
    host: build.internal
    user: homer
    auth: !<key>
      privateKeyPath: /etc/homer/ssh/id_ed25519
    knownHostsPath: /etc/homer/ssh/known_hosts
    remoteWorkspaceRoot: /home/homer/homer-workspaces
    tags: [linux, docker]
    concurrency: 4
```

**Matching.** An alias filter matches the runner with that alias. A tag filter
matches a runner whose `tags` contain every requested tag; a runner without
`tags` never matches a tag filter. Among matches the oldest request wins.
Among idle matching consumers, a new request goes to the one that claimed
least recently, so idle runners take turns
([ADR-0096](../docs/adr/0096-unhealthy-runners-stop-claiming-and-idle-consumers-take-turns.md)).

**Env and host visibility for tag-filtered runs.** An alias-filtered run knows
its runner before the workspace env is composed, so per-runner `env` keeps its
layer between `globalEnv` and agent `env` and `parentEnvAllowlist` applies as
for that runner. A tag-filtered run learns its runner only when claimed: it is
treated as remote if **any** runner that could match is remote (so a run served
by `local` may not receive the allowlisted host env), and the serving runner's
`env` is merged after the session opens, above agent `env`. Session-contributed
values such as `HMR_API_URL` take precedence over the runner's `env`.

A configuration without `tags` or `concurrency` on any runner behaves exactly as
it did before the queue existed.

**Turning the local runner off.** `server.localRunner.enabled: false` registers
no local runner, so no run executes in the server's own container. A run routed
to `local` (an agent with neither `runner:` nor `runnerTags:`, one with
`runner: local`, or a `runnerCmd` step targeting `local`) fails at once, before
it is queued, with
`Configuration error: runner 'local' is disabled (server.localRunner.enabled: false); route the agent to another runner`.
Use it on a server whose agents all run on external, Kubernetes or SSH runners.
`GET /api/v1/runners` then no longer lists `local`. The setting covers runs only:
the server still runs `homer_langgraph inspect` and `state` itself for the
console's LangGraph views (ADR-0027, ADR-0044), and those import the agent's
graph module on the server host.

```yaml
server:
  localRunner:
    enabled: false                # every agent must name another runner or runnerTags
```

### External runners (pull mode)

An `!<external>` entry under `runners:` is a **trust domain**, not a machine:
it holds the token that runner instances authenticate with and the limits
that bound them (fields: [ExternalRunner fields](#externalrunner-fields)).
Instances connect to Homer, so a machine behind NAT works; Homer never
connects to an instance.

```yaml
runners:
  workers: !<external>
    token: "@SYSENV:HOMER_WORKERS_TOKEN"
    homerApiUrl: https://homer.internal      # what the instances see as HMR_API_URL
    allowedTags: [linux, gpu]                # optional typo guard; omit to allow any tag
    heartbeatTimeoutSec: 60
    maxInstances: 32
    maxConcurrencyPerInstance: 16
    transferTimeoutSec: 300
    maxTransferBytes: 1073741824
    env:
      MODEL_CACHE: /var/cache/models
    extraUploads:                            # ssh shape rules and boot check (ADR-0042); no syncExcludes filtering
      - { local: /app/scripts, remote: .homer/scripts, env: SCRIPTS_DIR }
```

An instance registers with a name, the tags it serves and a `concurrency`,
then long-polls for claims. It matches an agent with `runner: workers` or an
agent whose `runnerTags` are all among its announced tags; among matches the
oldest run wins, exactly as for static runners, and among idle matching
instances the one that claimed least recently gets it. Adding a domain needs a
restart; adding an instance or a tag never does.

Every wait is bounded: an instance silent for `heartbeatTimeoutSec` is
dropped and its runs fail; a command result is awaited for its `timeout` plus
`heartbeatTimeoutSec`; each transfer request (a tar either way, an output
chunk) for `transferTimeoutSec`; a tar the instance posts for at most
`maxTransferBytes`, compressed and decompressed; a domain holds at most
`maxInstances` instances, an instance at most its `concurrency` claim polls;
a transfer is served one request at a time (per op for a tar, per op and
stream for output), and a concurrent duplicate is `429`. A domain token is
its own credential class: it works only on `/api/v1/runner/*` and is `401`
anywhere else; an `apiAuth` credential is `401` on `/api/v1/runner/*`.
Startup fails if a domain token is shorter than 32 characters or equals any
`apiAuth` secret — a bearer token, an HMAC key secret, or a current or
rotated-out session signing secret.

Tags are routing, not a security boundary: an instance may announce a tag it
cannot honour and the run fails inside a domain the operator already trusted
(availability, not confidentiality). Everything an instance sends is
attacker-controlled and is contained: tars are extracted with the same path
checks as the kubernetes runner, output is size-bounded and goes through the
same output cap. Tar transfers preserve a file's executable bit — normalized
to `0755` or `0644` as git normalizes a checkout — and drop setuid, setgid,
sticky and every other mode bit, so the `.homer/bin` helpers arrive
executable on the instance and a tar cannot plant a privileged or
world-writable file. An entry whose mode cannot be normalized is refused
rather than written unnormalized: the target and, when the write landed
through a dangling symlink, the link's destination are removed, and the
transfer fails.

A tag-filtered run is treated as remote when any domain could serve it: a
domain without `allowedTags` could serve any tag set. The load-time warning
"requests runner tags that no configured runner currently serves" knows a
domain's `allowedTags` and the tags of instances registered at that moment,
so with a domain that has no `allowedTags` and no instance yet the warning
fires for agents that domain will serve later.

The reference instance is the `homer-runner` binary (`app/homer-runner`,
image `homer-runner`; see `DOCKER.md` § homer-runner image):

```bash
# the domain token is read only from HOMER_RUNNER_TOKEN, never a flag
homer-runner --url https://homer.internal \
             --tags linux,gpu --concurrency 2 --root /var/homer-runner \
             --name host-1 --drain-timeout 600
```

`--url` must be `https://`, or `http://` to a loopback host, with no embedded
credentials: every request carries the domain token, so `homer-runner` refuses
any other URL at startup with exit code `2`.

Before it reads the token or claims anything, the runner JVM calls
`prctl(PR_SET_DUMPABLE, 0)` and verifies it. Every run executes as the runner's
own user, so without this a run could read `HOMER_RUNNER_TOKEN` from
`/proc/<jvm pid>/environ` or ptrace the runner; a non-dumpable process denies
both to same-user processes. Spawned runs are unaffected — `execve` resets the
flag for the child. On Linux a failed call is fatal (exit `1`); on a host
without `prctl` the runner logs a warning and continues.

Before registering and before every claim the runner also checks that it can
start a process (a `/bin/sh -c "exit 0"` probe with an empty environment, so the
token never reaches it) and create a file under `--root`. A JVM that can no
longer spawn processes would otherwise keep claiming runs and fail each at once
while healthy instances sit idle; a restart fixes it, so the runner exits `3`
instead ([ADR-0096](../docs/adr/0096-unhealthy-runners-stop-claiming-and-idle-consumers-take-turns.md)).

| Option | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `--url` | `HOMER_RUNNER_URL` | required | Homer base URL (`https://`, or loopback `http://`), without embedded credentials. |
| — | `HOMER_RUNNER_TOKEN` | required | The domain token. Env var only, never a flag, so it stays out of argv. |
| `--tags` | `HOMER_RUNNER_TAGS` | none | Comma-separated tags this instance serves. |
| `--concurrency` | `HOMER_RUNNER_CONCURRENCY` | `1` | Runs at once; above the domain's `maxConcurrencyPerInstance` the registration is refused. |
| `--root` | `HOMER_RUNNER_ROOT` | `/var/homer-runner` | Where each session gets a fresh owner-only `<processId>-<random id>` directory, deleted when the session ends. |
| `--name` | `HOMER_RUNNER_NAME` | the host name, shaped to the instance-name pattern | Instance name shown in Homer. |
| `--drain-timeout` | `HOMER_RUNNER_DRAIN_TIMEOUT` | `600` | Seconds in-flight runs may finish after `SIGTERM` or a failed health check before they are killed. |

Exit codes: `1` for a usage error (a missing option or token, an unknown flag),
an unexpected failure, or a refused startup when the JVM cannot be made
non-dumpable; `2` when Homer rejects the registration (bad token, disallowed
tag) or `--url` is refused; `3` when this host cannot start a process or write
`--root` (checked before registering and before every claim): the runner stops
claiming, deregisters, lets in-flight runs finish within `--drain-timeout` and
exits, so run it under a supervisor that restarts it (launchd `KeepAlive`, or
systemd `Restart=on-failure` with `RestartSec=10s`: with the default `100ms` and
`StartLimitBurst=5` per 10 s a host that fails the check at startup hits the
start limit within a second, and the unit stays failed after the cause is
fixed); `143` after a `SIGTERM` drain.

#### ExternalRunner fields

An `!<external>` entry is a **trust domain**, not a machine: it holds the token
that `homer-runner` instances present on `/api/v1/runner/*` and the limits that
bound them. Instances announce their own `tags` and `concurrency` when they
register, so this entry has neither.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `type` | const | Yes | - | Must be `external`. |
| `token` | `String` | Yes | - | Shared secret instances send as `Authorization: Bearer`. Supports `@SYSENV:VAR`. Must be at least 32 characters (checked after `@SYSENV` resolution) and differ from every `apiAuth` credential — bearer token, HMAC key secret or session signing secret (current or rotated-out) — and from every other domain's token; startup fails otherwise. Generate with e.g. `openssl rand -hex 32`. Never logged. |
| `homerApiUrl` | `String?` | No | `null` | Homer URL reachable from the instances; injected into runs as `HMR_API_URL`, like the ssh/kubernetes `homerApiUrl`. Must be `https://`, or `http://` to a loopback host, without embedded credentials, since runs send their callback token there; startup fails otherwise. |
| `allowedTags` | `List<String>?` | No | `null` | Tags an instance may announce; `null` = any. Registration with a tag outside the list is `422`. A typo guard, not a security gate: an instance may still announce a tag it cannot honour. |
| `heartbeatTimeoutSec` | `Long` | No | `60` | An instance whose claim/next polls stop for longer is dropped; each of its runs fails. Must be at least 35 (the 30 s long-poll window, `pollSec`, plus 5 s slack); startup fails otherwise. |
| `maxInstances` | `Int` | No | `32` | Registration beyond the bound is `429`. |
| `maxConcurrencyPerInstance` | `Int` | No | `16` | Cap on the `concurrency` an instance may announce (`422` above it). |
| `transferTimeoutSec` | `Long` | No | `300` | Deadline for one tar transfer (workspace upload to the instance, artifacts/workdir download from it), enforced both on the waiting run and on the transfer request itself, and for each output-chunk request. A posted tar or chunk past it is `408`; a fetched tar is cut off mid-stream, its `200` already sent. |
| `maxTransferBytes` | `Long` | No | `1073741824` | Largest tar an instance may post for one download, applied to both the compressed body and the bytes it decompresses to, so a gzip bomb is refused at the same ceiling; over it the post is `413` and the run fails. |
| `env` | `Map<String, String>` | No | `{}` | Per-domain environment variables for runs on any instance of this domain; same rules and layering as the ssh/kubernetes `env`. |
| `extraUploads` | `List<ExtraUploadSpec>` | No | `[]` | Host files/folders uploaded into every workspace on whichever instance claims the run, after the agent dir; same shape rules, reserved paths and startup check as the ssh runner's `extraUploads`, but always uploaded in full (a domain has no `syncExcludes`), and `env` (optional, non-`HMR_`) is repointed at the instance's copy under its session root. Every instance of the domain receives them, so the ssh runner's security contract applies: no secrets in declared folders. See ADR-0042. |

#### Operator endpoints

`GET /api/v1/runners` and `GET /api/v1/runners/queue` give an operator view
of every configured runner (including live `external` instances) and of
pending runner claims. Both are `admin`-only (`403` otherwise); payloads
never carry a domain token, session id, or env value. See
[`app/README.md`](README.md) for the response shapes. A runner domain
token is a separate principal class
([ADR-0066](../docs/adr/0066-external-pull-runners-and-unified-claim-queue.md))
and gets `401` on these routes, same as on every other `apiAuth` route.

#### Runner protocol reference

All routes take `Authorization: Bearer <domain token>`; ids are scoped to the
domain, a foreign or unknown id is `404`. A request with a valid token is
rate-limited by its domain's bucket, not the global one (see
[Runner rate limiting](#runner-rate-limiting)).

| Method and path | Purpose |
| --- | --- |
| `POST /api/v1/runner/register` | Body `{name?, tags?, concurrency?=1, version?}`. `201 {instanceId, pollSec, heartbeatTimeoutSec}`. `422` when tags violate the shape or `allowedTags`, `concurrency` is outside `1..maxConcurrencyPerInstance`, or `name` doesn't match `^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$`. `429` at `maxInstances`. |
| `DELETE /api/v1/runner/instances/{id}` | Stop offering claims to this instance; in-flight sessions continue. `204`. |
| `POST /api/v1/runner/instances/{id}/claim` | Long-poll up to `pollSec` (30 s). `200 {sessionId, processId, agentName, tags}` or `204`. At most `concurrency` concurrent polls per instance, the next is `429`. Also a heartbeat. |
| `POST /api/v1/runner/sessions/{sid}/next` | Body optionally `{completed: <result>}`, at most 64 KiB (`413` over it). Long-poll up to `pollSec`; `200 <op>` or `204`. Also a heartbeat. A result for an op that already has one is ignored. At most 3 concurrent polls per session, a further one is `429` — the refused call still records the result it carried, so only the wait is refused. |
| `GET /api/v1/runner/sessions/{sid}/ops/{opId}/tar` | Streams the gzip'd tar for an `upload` op, for at most `transferTimeoutSec`; past it the stream is cut. `429` while another fetch of the same op is in flight. |
| `POST /api/v1/runner/sessions/{sid}/ops/{opId}/tar?kind=dir\|file` | The instance posts the gzip'd tar for a `download` op, at most `maxTransferBytes` compressed **and** decompressed; `202`, `413` over either bound, `408` past `transferTimeoutSec`, `422` if an entry is refused (it escapes the target, or its mode cannot be normalized) — extraction streams entries, so a refused tar may leave already-contained entries on disk, never outside the target. `429` while another post for the same op is in flight. |
| `POST /api/v1/runner/sessions/{sid}/ops/{opId}/output?stream=stdout\|stderr&seq=N` | Raw chunk body, at most 1 MiB and at most `transferTimeoutSec`. Appended in `seq` order per stream; a gap or repeat is `409`. `429` while another chunk of the same op and stream is in flight; the other stream is unaffected. |

A claim poll is refused with `429` when `openPolls + activeSessions >=
concurrency`.

Ops delivered by `next` (JSON `type` discriminator), mirroring the session
primitives:

| `type` | Payload | Result (`completed`) |
| --- | --- | --- |
| `createWorkspace` | `{opId}` | `{opId, root}`: absolute path the instance created, with `workdir`, `artifacts` and `.homer` inside. |
| `upload` | `{opId, remoteRelative, isDir}` | `{opId}` after the instance fetched and extracted the tar. |
| `cmd` | `{opId, argv, env, stdin?, timeoutMs, cwd}` | `{opId, exitCode, timedOut, stdoutChunks?, stderrChunks?}`; output arrives through the `output` route while running. |
| `download` | `{opId, remoteRelative, mode}` | `{opId, missing}`; the instance posts the tar first, then the result. |
| `cleanWorkspace` | `{opId}` | `{opId}`. |
| `close` | `{opId, reason}` | none; the session is over. |
| `cancel` | `{opId}` of the running `cmd` | none; the instance kills the process group and then reports the `cmd` result as usual. |

Every result carries `ok` (default `true`) and, when `ok` is `false`, `error`. A `cmd`
result may also carry `stdoutChunks`/`stderrChunks`, one past the last `seq` the instance
posted or gave up posting for that stream — a chunk whose post was abandoned after its
retries is counted too, so a lost chunk shows up as a short count and never as a complete
one. The one exception: a chunk still unsent when the instance's bounded post-exit drain
expires is dropped with one warning naming the session, op, stream and count, and reaches
no count — that result then matches what Homer holds while output is missing, and the
instance's log is the only trace. Both fields are optional, so a result from an
older runner binary omits them. When one is present and ahead of the chunks Homer has
received, Homer waits once, briefly, for the rest and then fails the step with
`output incomplete on <stream>` instead of running on truncated output.

Both ends of this protocol are upgraded independently, so every body it carries is
decoded with unknown keys ignored: an instance newer than the server it reports to is
read for the fields this server knows, never refused as a malformed body.

**Nested runs hold a permit.** A run holds its runner's permit for its whole
duration, not just while it is opening the session. If `concurrency` is set on
a runner, a `RunAgent` child targeting the same runner, or a `runnerCmd` step
targeting `local` while `server.localRunner.concurrency` is set, can end up
waiting for a permit its own parent already holds — and fail at the claim TTL
once that permit never frees. `runnerCmd` steps take a permit on their target
runner the same way a whole agent run does, so they carry the same hazard.
Leave `concurrency` unset on a runner that serves both a parent agent and its
`RunAgent` children or `runnerCmd` targets, or size it for the deepest chain
of nested runs that can land on it at once.

**The queue bound is global.** `server.maxPendingRunnerClaims` bounds the
whole claim queue with no per-agent share. One agent with a large
`maxParallelRuns` on a saturated runner can fill the queue by itself; once
full, unrelated agents' runs fail immediately with `Runner claim queue is
full`, not just that agent's own. In practice the pending count is bounded by
the sum of every agent's `maxParallelRuns`, and there is no way to reserve a
share of the queue for one agent. Size `maxPendingRunnerClaims` at or above
that sum (default `maxParallelRuns` is `10` per agent) and the bound becomes a
backstop that only trips on misconfiguration, never in steady state.

## Logging

Homer logs via **logback-classic** (`app/src/main/resources/logback.xml`).
The bundled defaults set `homer.app` to `DEBUG`, frameworks (`io.ktor`,
`io.netty`, `io.opentelemetry`) to `INFO`, and the root level to
`${HOMER_LOG_LEVEL:-INFO}`. (`agentdebug` is the exception — it keeps
slf4j-simple to avoid a double SLF4J binding.)

Two log streams:

| Stream | Contents | Where it lands |
|--------|----------|----------------|
| `app.log` | The full application log. | Written to **stdout** by the console appender and to a rolling file appender in `HOMER_LOG_DIR` (10 MB × 3). In Docker/K8s stdout is the container log (`docker logs` / `kubectl logs`). |
| `api.log` | The per-request access log, written by the `CallLogging` plugin to the dedicated `homer.access` logger. | Rolling file appender in `HOMER_LOG_DIR` (10 MB × 3). `additivity=false`, so access lines never appear in `app.log`. |

Two settings control this. Logback resolves each from a JVM system property
first, then the OS environment, so either form works:

- **`HOMER_LOG_LEVEL`** — root log level (default `INFO`).
  `docker-compose.dev.yml` sets it as a system property via
  `JAVA_OPTS=-DHOMER_LOG_LEVEL=DEBUG`. Per-logger levels are set in
  `logback.xml`; adjust them there rather than at runtime.
- **`HOMER_LOG_DIR`** — directory for `app.log` and `api.log`. Defaults to the working
  directory; the Dockerfile sets it as an env var to `/app/workspace`.

> **Access-log coverage:** each line is `status: METHOD path` — the query
> string is not recorded, so query-param values never reach `api.log`, and the
> result-read token in `/api/v1/callback/results/<token>` is masked as `***`.
> Health, heartbeat, `livez`, **all `/api/v1/stream/*` requests**, and the
> runner-protocol `/api/v1/runner/…/claim`, `/next` and `/output` calls are
> excluded entirely as noise (`isNoisyAccessPath`), so SSE, log-tail and
> external-runner instance activity leaves no per-request trace — worth knowing
> if you rely on `api.log` for audit.

## Configuration Best Practices

1. **Use Absolute Paths in Production**: Avoid relative paths for `agentsSearchPath` and `workspaceRootPath` in production to prevent path resolution issues.

2. **Secure Your Tokens**: Generate cryptographically secure random tokens for bearer authentication. Never commit tokens to version control.

3. **Organize Workspaces**: Configure workspace paths per-agent using the `workspace.path` field (see AGENT.md) to organize workspaces by agent or date for easier management and cleanup.

4. **Set Appropriate Global Environment**: Use `globalEnv` for environment-specific configuration that all agents need.

5. **Enable Authentication in Production**: Always use bearer authentication in production environments. Only use unsecured mode in trusted, isolated environments.

6. **Monitor Disk Usage**: Workspaces can consume significant disk space. Enable `cleanAfterFinish` in agent configurations or implement periodic cleanup.

7. **Langfuse Self-Hosted**: If using self-hosted Langfuse, ensure the `serverUrl` is accessible from Homer and includes the protocol (https://).

## See Also

- [README.md](README.md) - Project overview and quick start
- [AGENT.md](AGENT.md) - Agent format and feature reference
