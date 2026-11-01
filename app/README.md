# HOMER – Hub for Orchestrating Managed Execution Runs

> Asynchronous HTTP hub for running and managing agents — from Shell commands to Claude AI scripts — with native Langfuse prompt integration and full observability via OpenTelemetry.

## What is Homer?

Homer is an asynchronous execution orchestrator that runs user-defined "agents" — sequences of commands that can include shell scripts, CLI tools, and Claude AI interactions. It provides:

- **Asynchronous Execution**: HTTP API for triggering agents that run in the background
- **Agent Orchestration**: Sequential command execution with result transformation and environment passing
- **Claude AI Integration**: Native support for Claude Code CLI with Langfuse-managed prompts
- **Full Observability**: OpenTelemetry instrumentation with Langfuse integration
- **Per-run Workspaces**: Each process gets its own working directory with configurable cleanup (a directory, not a sandbox)
- **Concurrency Control**: Lock strategies to manage concurrent agent executions

**Security model**: Homer itself is not a sandbox. On the `local` runner an agent's commands run as the OS user Homer runs as, with that user's full reach — its files, network, and any credentials in its environment. To isolate runs, send the agent to a remote runner — a pod per run on `kubernetes`, or a `homer-runner` container away from Homer's host — and that machine bounds what the run can reach ([README](../README.md#security-model), [SERVER.md](SERVER.md#runners-pluggable-execution-backends)). Agent definitions are trusted operator configuration, but whatever they execute (scripts, Claude/Codex/opencode sessions, LangGraph graphs, and every file they write) is treated as untrusted. What Homer does enforce is the boundary between callers: every request is authenticated (bearer, HMAC, or password session), each principal holds `role: admin` and/or per-agent grants (`run`, `read`, `control`, `edit`, `debug`), and a run is visible only to admins, its owner, or holders of a `read` grant on its agent. Running without authentication (`apiAuth: !<unsecured>`) is an explicit opt-in that turns every check off. See [ADR-0037](../docs/adr/0037-grants-authorization.md), [ADR-0038](../docs/adr/0038-process-ownership.md), [ADR-0047](../docs/adr/0047-security-by-design-and-threat-model.md) and [SECURITY.md](../SECURITY.md).

## Quick Start

### Prerequisites

- JDK 25 — `JAVA_HOME` must point at a JDK 25; the system Java will not
  build the project. On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`
- Gradle (included via wrapper)
- Claude Code CLI (for `!<claude>` commands) — see [Claude Code setup](https://code.claude.com/docs/en/setup)
- Codex CLI (for `!<codex>` commands) and opencode CLI (for `!<opencode>` commands), and Python 3 with `homer_langgraph` (for `!<langgraph>` commands) — only if you use them

### 1. Create Configuration File

Create a configuration file (JSON or YAML):

```yaml
# config.yaml
server:
  port: 8080
  apiAuth: !<unsecured> {}

agentsSearchPath: "./agents"
workspaceRootPath: "./workspaces"
databasePath: "/tmp/homer.db"

langfuse:
  serverUrl: "https://cloud.langfuse.com"
  publicKey: "pk-lf-xxx"
  secretKey: "sk-lf-xxx"

openTelemetryExport: LANGFUSE
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
  "databasePath": "/tmp/homer.db",
  "langfuse": {
    "serverUrl": "https://cloud.langfuse.com",
    "publicKey": "pk-lf-xxx",
    "secretKey": "sk-lf-xxx"
  },
  "openTelemetryExport": "LANGFUSE"
}
```
</details>

### 2. Create a Simple Agent

Create an agent definition in `./agents/hello/agent.yaml`:

```yaml
name: hello
description: Simple hello world agent

commands:
  - !<script>
    content: echo "Hello, $NAME!"

inputs:
  - paramName: name
    envName: NAME
    type: string
    required: true
```

### 3. Run the Server

Build the fat JAR from the `app/` directory (see [Building](#building)), then
start it from the directory holding `config.yaml` and `agents/` — relative
paths in the config resolve against the working directory:

```bash
java -jar <repo>/app/app/build/libs/homer-<version>-all.jar config.yaml
```

### 4. Execute the Agent

```bash
curl -X POST "http://localhost:8080/api/v1/agent/hello?name=World"
```

Response:
```json
{
  "processId": 12345
}
```

Check the process status:
```bash
curl "http://localhost:8080/api/v1/status/12345"
```

## Running Tests

From the `app/` directory, with `JAVA_HOME` pointing at a JDK 25:

```bash
./gradlew :app:test
```

Tests cover:
- Configuration loading (YAML and JSON)
- Agent parsing and validation
- Command execution (Cmd, Script, Claude, Codex, Opencode, RunAgent, LangGraph, RunnerCmd)
- Result transformation (JSONPath, Regex)
- Parameter handling
- Workspace management
- Lock strategies

## Building

Homer provides multiple build options depending on your deployment needs. All commands below run from the `app/` directory.

### Fat JAR (Single Executable File)

Build a single executable JAR with all dependencies bundled:

```bash
./gradlew :app:shadowJar
```

Output: `app/build/libs/homer-<version>-all.jar` (e.g. `homer-0.0.2-all.jar`)

Run with:
```bash
java -jar app/build/libs/homer-0.0.2-all.jar config.yaml
```

**Use case**: Simple deployments, containers, single-file distribution

### Thin JAR (Application Only)

Build a lightweight JAR with only application classes:

```bash
./gradlew :app:jar
```

Output: `app/build/libs/app.jar`

**Note**: Requires dependencies in classpath. Not executable on its own.

### Distribution

Build a complete distribution with startup scripts and all dependencies:

```bash
./gradlew :app:installDist
```

Output: `app/build/install/app/`

Directory structure:
```
app/build/install/app/
├── bin/
│   ├── app       # Unix/Linux/macOS startup script
│   └── app.bat   # Windows startup script
└── lib/          # All JAR dependencies
```

Run with:
```bash
./app/build/install/app/bin/app config.yaml
```

**Use case**: Traditional server installations (the Docker image ships the fat JAR — see [DOCKER.md](../DOCKER.md))

### Distribution Archives

Package the distribution as ZIP or TAR for easy transfer:

```bash
# Create ZIP archive
./gradlew :app:distZip

# Create TAR archive
./gradlew :app:distTar
```

Output:
- `app/build/distributions/app.zip`
- `app/build/distributions/app.tar`

**Use case**: Deployment packages, versioned releases

## API Endpoints

| Method | Endpoint | Description | Authentication |
|--------|----------|-------------|----------------|
| GET | `/api/v1/heartbeat` | Liveness ping with version. Use `/livez` for Kubernetes probes. | Any authenticated caller (open under `unsecured`) |
| GET | `/api/v1/livez` | Kubernetes-style liveness probe — always `200`. Wire `livenessProbe` here. | Not required |
| GET | `/api/v1/readyz` | Kubernetes-style readiness probe — `200` in steady state, `503` once drain begins. Wire `readinessProbe` and any reverse-proxy upstream check here. | Not required |
| GET | `/api/v1/health` | Rich JSON status (runners + sweep snapshot + `maintenance`) for the console. Configured runners' **aliases** (in the runner list and the sweep snapshot's `runnerAlias`) are admin-only (the built-in `local` row keeps its fixed, non-sensitive alias): a non-admin caller gets `alias: null` with the generic backend `type` — the same classification as the status API. In the sweep snapshot, `ranAt` is the time the boot sweep last ran as Unix epoch **seconds**. `maintenance` is the admin maintenance flag (see [below](#getpost-apiv1maintenance)). **Not** a probe — stays `200` during drain. | Any authenticated caller (open under `unsecured`) |
| POST | `/api/v1/runner/register` | Register a pull-mode runner instance in the domain the token belongs to (`201`; `422` bad tags/concurrency; `429` at `maxInstances`). See [`SERVER.md`](SERVER.md) § *Runner protocol reference*. | Runner domain token (`runners.*.token`); `401` for every `apiAuth` credential |
| DELETE | `/api/v1/runner/instances/{id}` | Deregister an instance (`204`; `404` unknown or foreign id). | Runner domain token |
| POST | `/api/v1/runner/instances/{id}/claim` | Long-poll for a run to execute (`200` session, `204` nothing or the instance is draining, `404` unknown or foreign instance id, `429` too many polls). | Runner domain token |
| POST | `/api/v1/runner/sessions/{sid}/next` | Report the previous op's result and long-poll for the next op (`200` op, `204` nothing, `400` malformed body, `404` unknown session, `429` more than 3 polls open on the session — the reported result is still recorded). | Runner domain token |
| GET | `/api/v1/runner/sessions/{sid}/ops/{opId}/tar` | Fetch the gzip'd tar of an `upload` op (`200` stream; `404` unknown or finished op; past `transferTimeoutSec` the stream is cut off, its `200` already sent; `429` another fetch of the op in flight). | Runner domain token |
| POST | `/api/v1/runner/sessions/{sid}/ops/{opId}/tar` | Post the gzip'd tar of a `download` op (`?kind=dir\|file`; `202`; `400` bad `kind`; `404` unknown or finished op; `408` past `transferTimeoutSec`; `413` over `maxTransferBytes` compressed or decompressed; `422` a refused entry (escaping, or a mode that cannot be normalized); `429` another post of the op in flight). | Runner domain token |
| POST | `/api/v1/runner/sessions/{sid}/ops/{opId}/output` | Append one output chunk (`?stream=stdout\|stderr&seq=N`; `202`; `400` bad `stream` or `seq`; `404` unknown or finished op; `408` past `transferTimeoutSec`; `409` out of order; `413` over 1 MiB; `429` another chunk of the op and stream in flight). | Runner domain token |
| GET | `/api/v1/status` | Get server status | `admin` role required |
| GET | `/api/v1/runners` | Configured runners with permits in use, and each external domain's live instances | `admin` role required |
| GET | `/api/v1/runners/queue` | Pending runner claims: count, oldest age, breakdown by filter | `admin` role required |
| GET | `/api/v1/maintenance` | Maintenance mode state: `enabled`, `since` (Unix epoch seconds; null while off, and also for a mode that was already on when the server booted — the toggle time is not persisted) and `queued` (starts waiting). Responses carry `Cache-Control: no-store`. | `admin` role required; a non-admin gets the same `404` an unmounted path answers |
| POST | `/api/v1/maintenance` | Turn maintenance mode on or off (body `{"enabled": true\|false}`, `400` on a malformed body). On: cron fires are skipped and every other agent start queues. Off: the queue drains in arrival order within one release drain, `releaseConcurrency` at a time — rapid re-toggles overlap drains, so the order across drains is not guaranteed. See [below](#getpost-apiv1maintenance). | `admin` role required; a non-admin gets the same `404` an unmounted path answers |
| GET | `/api/v1/costs` | Current LLM harness (Claude/Codex/opencode) spend windows + configured caps; also returns `costTrackedTagKeys`, the keys queryable via `GET /api/v1/costs/by-tag/{key}` | `admin` role required |
| GET | `/api/v1/costs/monthly` | Per-agent LLM harness (Claude/Codex/opencode) spend for one UTC month, from the persistent cost ledger (query: `month`, defaults to current UTC month; `400` if malformed) | `admin` role required |
| GET | `/api/v1/costs/totals` | All-time per-agent LLM harness (Claude/Codex/opencode) spend from the persistent cost ledger | `admin` role required |
| GET | `/api/v1/costs/by-tag/{key}` | LLM harness (Claude/Codex/opencode) spend broken down by the values of one run subject-tag key, summed from `processes.costUsd` against each run's current tags. `key` must be listed in `costTrackedTagKeys`, else `400`. See [below](#get-apiv1costsby-tagkey). | `admin` role required |
| GET | `/api/v1/agents` | List all available agents. Each agent carries its optional `group` (null when unset) — the label the console groups the Agents page by. A `secret: true` input's `default` and `values` are never included in the returned summary. | Filtered to agents the caller holds any grant on (`admin`: all) |
| GET/POST | `/api/v1/agent/{agentName}` | Execute an agent asynchronously. **Deprecated:** use POST. GET execution remains for backward compatibility but will be removed in a future release — executing side-effectful runs via GET invites accidental re-runs from prefetchers and URL previews. Optional `X-Homer-Parent-Process-Id` header links the new run to a parent run the caller can see (`400` malformed, `422` unknown or not visible). Error statuses: `400` invalid input (missing or invalid parameter, GET on an agent that takes a body, wrong `Content-Type` or malformed JSON body — both only when a body is sent; a bodyless POST is accepted for an agent without an `inputSchema`, its body inputs falling to their defaults), `422` body failing the agent's `inputSchema`, `429` cost cap exceeded / agent `cooldown` active, `503` process manager at capacity. A run refused by `maxParallelRuns: REJECT`, a full `maxQueuedRuns` queue or a `SKIP_CURRENT` lock collision is still accepted (`200` with a `processId`) and ends `FAILED` without executing. | `run` grant on `agentName` (`404` for an unknown agent or one the caller holds no grant on; `403` when it holds another grant on the agent but not `run`) |
| GET | `/api/v1/status/all` | Get all processes with optional filtering (`types`, `agentName`, `parentProcessId`, `rootProcessId`, `roots`, `tag`, `order`, `limit`, `offset`); rows include `openQuestions`, `owner`, `parentProcessId`, `rootProcessId`, `tags` and `group` — the agent's group, read from the *current* agent definition, null when the agent is ungrouped or has since been removed. Repeatable `tag=key:value` ANDs the run's subject tags (a run must carry every one); at most 10 params, and each value at most 193 characters (`64` key + `:` + `128` value — a longer value can match no stored tag), more is `400`. Filtered to processes the caller can see (`admin` ∪ owner ∪ `read` grant on the agent) — a run the caller cannot see is simply absent, never `403`. A tag that would be screened from a run's status display (a value from a `secret` input) is likewise not matchable via `?tag=` for that run, so the filter cannot confirm a guessed secret. Tag-filtered results are bounded at 10 000 matching rows (in the order specified by `?order=`, default descending); `total` reflects only that prefix. A row's `rootProcessId` is nulled when the root run itself is not visible to the caller, so the id of another principal's flow root is never emitted. Each row's `runner.alias` is returned only to an `admin` caller; every other caller gets the generic `runner.type` with `alias: null`. A row's `failureReason` carries the stored reason without the id of the run that blocked it; that id is appended on `GET /status/{id}` alone, and only for a caller who may open that run. | Visible to `admin`, the process's owner, or a `read` grant on its agent |
| GET | `/api/v1/status/{processId}` | Get process status (processId is integer); includes `openQuestions`, `owner` and `group` (the agent's group, from the current agent definition, null when unset or the agent is gone). The `runner.alias` is returned only to an `admin` caller; every other caller gets the generic `runner.type` with `alias: null`. | Visible to `admin`, the process's owner, or a `read` grant on its agent (`404`, existence hidden, otherwise) |
| POST | `/api/v1/status/{processId}/messages` | Queue an operator message for a running run. Body `{ "text": "..." }`; `202` with the message (`id`, `processId`, `text`, `status` `QUEUED`, `sender`, `createdAt`, `deliveredAt`). `400` for a blank or over-long text (`server.messages.maxTextLength`), `409` when the run has ended, `429` at `server.messages.maxPerRun`. | `admin`, the process's owner, or visible + a `control` grant on its agent (`404`, existence hidden, otherwise) |
| GET | `/api/v1/status/{processId}/messages` | The run's messages, oldest first, in every state (`QUEUED`, `DELIVERED`, `UNDELIVERED`): `{ "messages": [...] }`. | Visible to `admin`, the process's owner, or a `read` grant on its agent (`404`, existence hidden, otherwise) |
| DELETE | `/api/v1/kill/{processId}` | Kill a running process (processId is integer) | `admin`, the process's owner, or visible + a `control` grant on its agent (`404`, existence hidden, otherwise) |
| GET | `/api/v1/artifacts` | Stream artifact file from a process execution. Query: `processId` (int, required), `path` (non-blank, required). See [below](#get-apiv1artifacts). | Visible to `admin`, the process's owner, or a `read` grant on its agent (`404`, existence hidden, otherwise) |
| GET | `/api/v1/artifacts/list` | List artifacts produced by a process (query: `processId`). The response is `{processId, artifactsDir, files, truncated}`; the listing is capped at 10,000 entries and `truncated` is `true` when files were omitted. | Visible to `admin`, the process's owner, or a `read` grant on its agent (`404`, existence hidden, otherwise) |
| GET | `/api/v1/schemas/agent` | Return the homer-agent JSON Schema | Any authenticated caller (open under `unsecured`) |
| POST | `/api/v1/agents/reload` | Rescan the agents directory and emit `agent.reload` SSE events; returns `{added, updated, removed, errors}`. Every still-`CREATED` (queued) run of an updated or removed agent is killed ("Agent '…' was reloaded"), and those agents' parallel-run state is reset. | `admin` role required |
| POST | `/api/v1/agents` | Create a new agent directory (body: `{ name }`) | `edit` grant on the requested `name` |
| GET | `/api/v1/agents/{name}/langgraph` | Return each `!<langgraph>` command's workflow graph as nodes and edges (`topology`); `404` if unknown, `200 []` if no LangGraph commands | Any grant on `name`; `404` (existence hidden) if the caller holds none |
| GET | `/api/v1/agents/{name}/files` | List files in an agent's directory (omits any nested agent's subtree — a nested agent is a different agent, ADR-0068 — and any symlink whose target resolves outside the agent directory, so no entry leaks an outside file's size or mtime) | `edit` grant on `name` |
| GET | `/api/v1/agents/{name}/files/{path...}` | Read a text file inside an agent's directory (paths inside a nested agent's directory are 404, ADR-0068) | `edit` grant on `name` |
| PUT | `/api/v1/agents/{name}/files/{path...}` | Write a file (422 if `agent.yaml`/`agent.json` fails schema; paths inside — or reached through a symlink into — a nested agent's directory are 404, ADR-0068); a definition file declaring a new agent name also needs edit on that name, an unparsable definition-named file outside the agent's own root is denied (404), and a definition discovery could not load (deeper than its depth cap or under a hidden directory) is refused as 400 | `edit` grant on `name` |
| DELETE | `/api/v1/agents/{name}/files/{path...}` | Delete a file (refuses `agent.yaml`/`agent.json`; paths inside a nested agent's directory are 404, ADR-0068) | `edit` grant on `name` |
| POST | `/api/v1/agents/{name}/debug` | Run a single command (body: `{ commandIndex, env?, queryParams?, body?, headers? }`). `400` malformed body, `commandIndex` out of range, or `env` overriding a reserved name; `503` process manager at capacity. | `debug` grant on `name` (never implied by a wildcard grant — only `role: admin` or an explicit `debug` action). `404` for an unknown agent or one the caller holds no grant on; `403` when it holds another grant but not `debug`; `429` when the agent's `cooldown.minInterval` is active (the `afterFailure` window does not gate this path) |
| POST | `/api/v1/agents/{name}/cron-run` | Fire `name` immediately with cron semantics — source `cron`, owner `system:cron`, no caller-supplied inputs, the same code path as a tick. A preflight refusal (cost cap, cooldown, a required input with no default, or the process manager at capacity) starts nothing and is only logged — the response is still `204` with no body, exactly as on a tick. Lock and parallel-run limits are applied inside the run, as on a tick: a `SKIP_CURRENT` collision, a `REJECT` cap or a full `maxQueuedRuns` queue creates a process that ends `FAILED`. Works on any agent, with or without a `cron` block; the new process id is read from `GET /api/v1/agents` (`cron.lastRunProcessId`) once it is the agent's most recent run, which is surfaced only for agents that declare `cron` (others report `cron: null`). See [ADR-0080](../docs/adr/0080-manual-cron-fire-endpoint.md). | `admin` role required; `404` for an unknown agent, identical to not-found |
| POST | `/api/v1/processes/{id}/retry` | Retry a terminal process (FINISHED/FAILED/KILLED) with the same agent and inputs. Stored inputs are re-validated against the **current** agent definition (a since-tightened or newly-required input rejects the retry with `400`; values for inputs removed since are dropped), so a retry can never replay values the current input policy would refuse. `409` for a non-terminal source (use resume for `UNKNOWN`); `404` when the agent no longer exists; `429` cost cap exceeded or the agent's `cooldown.minInterval` active (a manual retry is not gated by `afterFailure`); `503` process manager at capacity. Checks run in order: visibility, then status (`409`), then agent existence (`404`), then the `run` grant — so a caller that can see the run but lacks `run` can still get the `409` or the agent-missing `404`. | Visible to caller + a `run` grant on its agent (`404`, existence hidden, otherwise — owner alone does not grant retry) |
| GET | `/api/v1/processes/{id}/langgraph` | LangGraph run introspection: thread id and, with `?graph=true`, the graph `topology` plus per-node status and `traversed` (the edges the run took, when the runtime reports them). `404` if the process is not a LangGraph run (no run-identity artifact). See [below](#get-apiv1processesidlanggraph). | Visible to `admin`, the process's owner, or a `read` grant on its agent (`404`, existence hidden, otherwise) |
| POST | `/api/v1/processes/{id}/resume` | Resume a failed LangGraph run (FAILED/KILLED/UNKNOWN) from its latest checkpoint, carrying the original inputs plus the recover markers. `409` other statuses, `404` when the agent no longer exists, `400` no LangGraph thread recorded, the agent no longer defines a LangGraph command, or a stored input the current definition rejects (as on retry); `429` cost cap exceeded or the agent's `cooldown.minInterval` active (a manual resume is not gated by `afterFailure`); `503` process manager at capacity. The status (`409`) and agent-existence (`404`) checks run before the `run` grant check, as on retry. See [below](#post-apiv1processesidresume). | Visible to caller + a `run` grant on its agent (`404`, existence hidden, otherwise — owner alone does not grant resume) |
| GET | `/api/v1/questions` | List agent questions, oldest first (query: `status` = `OPEN`/`ANSWERED`/`EXPIRED`, `processId`, `limit` default 100 and at most 1000, `offset` default 0). Returns `{questions, total, offset, limit}`; `total` counts the visible questions matching the filters before paging. Each question carries an optional `dispatch` annotation (`{ id, agentName, status, dispatchedProcessId?, error? }`) when it is bound to an ask-and-dispatch. | Filtered in SQL to questions whose asking process the caller owns, or of an agent the caller holds a `read` grant on while that agent is loaded — a question of an unloaded agent is visible to its process's owner only, as on `/dispatches` |
| POST | `/api/v1/questions/{id}/answer` | Answer an open question (body: `{ answer }`); `400` blank answer, `404` unknown id, `409` if already answered/expired. Fires the bound dispatch (if any) and returns the question with its `dispatch` annotation. | `admin`, the asking process's owner, or visible + a `control` grant on its agent (`404`, existence hidden, otherwise) |
| GET | `/api/v1/dispatches` | List ask-and-dispatch records, newest first (query: `status` = `PENDING`/`DISPATCHING`/`DISPATCHED`/`FAILED`/`CANCELLED`, `agentName`, `limit` default 100 and at most 1000, `offset` default 0). Returns `{dispatches, total, offset, limit}`; `total` counts the visible rows matching the filters before paging. `params` values for the target agent's `secret: true` inputs (flagged now or when the row was registered) come back as `***`; every value is masked when the target agent is no longer loaded, and so is any undeclared key on a row registered without a secret snapshot. Settled rows are deleted after `server.dispatch.settledRetentionSec`. | Filtered to dispatches the caller can see (`admin`, the owner, or a `read` grant on the target agent when that agent is currently loaded — a row for an unloaded agent is visible to its owner only, as on `/status/all`) before paging, so `total` never counts a hidden row |
| GET | `/api/v1/dispatches/{id}` | Get one dispatch; `404` unknown id. Secret `params` values are masked as above. | Visible to `admin`, the dispatch's owner, or a `read` grant on its agent (`404`, existence hidden, otherwise) |
| DELETE | `/api/v1/dispatches/{id}` | Cancel a `PENDING` dispatch and expire its question (`204`); `404` unknown id, `409` if not `PENDING` | `admin`, the dispatch's owner, or visible + a `control` grant on its agent (`404`, existence hidden, otherwise) |
| GET | `/api/v1/continuations` | List completion continuations, newest first (query: `status` = `PENDING`/`DISPATCHING`/`DISPATCHED`/`FAILED`/`CANCELLED`/`EXPIRED`, `agentName`, `limit` default 100 and at most 1000, `offset` default 0). Returns `{continuations, total, offset, limit}`; `total` counts the visible rows matching the filters before paging. `params` values for the target agent's `secret: true` inputs (flagged now or when the row was registered) come back as `***`; every value is masked when the target agent is no longer loaded, and so is any undeclared key on a row registered without a secret snapshot. Settled rows are deleted after `server.dispatch.settledRetentionSec`. | Filtered to continuations the caller can see (`admin`, the owner, or a `read` grant on the target agent when that agent is currently loaded — a row for an unloaded agent is visible to its owner only, as on `/status/all`) before paging, so `total` never counts a hidden row |
| GET | `/api/v1/continuations/{id}` | Get one continuation; `404` unknown id. Secret `params` values are masked as above. | Visible to `admin`, the continuation's owner, or a `read` grant on its target agent (`404`, existence hidden, otherwise) |
| DELETE | `/api/v1/continuations/{id}` | Cancel a `PENDING` continuation (`204`); `404` unknown id, `409` if not `PENDING` | `admin`, the continuation's owner, or visible + a `control` grant on its target agent (`404`, existence hidden, otherwise) |
| GET | `/api/v1/stream/processes` | SSE: process status / cmd transitions plus `question.asked` / `question.answered` / `question.expired` and `message.queued` / `message.delivered` / `message.undelivered` (payload `{processId, messageId}`) across all processes. `429` when the caller already holds 100 open streams across the four `/stream/*` endpoints. | Each event filtered to processes the caller can see |
| GET | `/api/v1/stream/agents` | SSE: agent reload events (added/updated/removed), each list filtered to agents the caller holds any grant on; every reload emits a frame (lists may be empty) so consumers can use it as a reload-completed signal. `429` when the caller already holds 100 open streams across the four `/stream/*` endpoints. | Filtered to agents the caller holds any grant on (`admin`: all) |
| GET | `/api/v1/stream/processes/{processId}/logs` | SSE: live tail of a process's stdout/stderr (`log.chunk`, `log.end`). Query: `cmd` (command index, default `0`), `stream` (`out`/`err`, default `out`; other values → `400`). Honors `Last-Event-ID` to resume from an offset. Each `log.chunk` carries at most 1 MiB, cut on a whole UTF-8 character, so chunks concatenate safely. A stdout/stderr file that is a symlink, hard link or other non-regular file is never served: a symlink already there answers `404`, anything else ends with `log.end` and nothing from it. `429` when the caller already holds 100 open streams across the four `/stream/*` endpoints. | Visible to `admin`, the process's owner, or a `read` grant on its agent (`404`, existence hidden, otherwise) |
| GET | `/api/v1/stream/processes/{processId}/llm-events` | SSE: canonical LLM events of a run (`llm.event` with `{processId, cmdIndex, attemptIndex, event}`, then `llm.end`). Query `cmd` follows one `!<claude>`/`!<codex>`/`!<opencode>` command from byte 0 (`404` for any other command type, `400` for a non-integer or negative value); without `cmd` it follows every LLM command of the process in order. `Last-Event-ID` is `cmdIndex:attemptIndex:lineOffset:eventIndex`. Serves finished runs too; after a server restart only `secret: true` input values are masked (the raw `/logs` tail and artifacts are never masked). See [below](#get-apiv1streamprocessesprocessidllm-events). `429` when the caller already holds 100 open streams across the four `/stream/*` endpoints. | Visible to `admin`, the process's owner, or a `read` grant on its agent (`404`, existence hidden, otherwise) |
| POST | `/api/v1/callback/questions` | Ask the operator a question (body: `{ text, options? }` → `{ id }`). Markdown `text`; `400` blank text, blank/duplicate options, text or an option over `server.questions.maxTextLength`, or more than `server.questions.maxOptions` options, `409` on a terminal process, `429` at the per-process open cap. | Callback token (`HMR_CALLBACK_TOKEN`) |
| GET | `/api/v1/callback/questions/{id}` | Check / long-poll an answer (query: `waitSec`, clamped to `server.questions.maxWaitSec`). Returns the question with `status` `OPEN`/`ANSWERED`/`EXPIRED`; an `OPEN` response just means poll again with the same id. Foreign question ids answer `404`; `429` at the per-process concurrent-wait cap. | Callback token (`HMR_CALLBACK_TOKEN`) |
| POST | `/api/v1/callback/dispatches` | Register an ask-and-dispatch (body: `{ text, agent, options?, answerParam?, params?, ttlSec? }` → `{ dispatchId, questionId }`): asks the operator a question and, once answered, starts `agent` with `params` plus the answer under `answerParam` (which cannot target a reserved env name). `400` validation, `403` non-dispatchable `agent`, `404` unknown `agent` or one this run's hop may not reach, `409` on a terminal process, `429` at the global pending-dispatch cap. | Callback token (`HMR_CALLBACK_TOKEN`) |
| POST | `/api/v1/callback/tags` | Declare a subject tag on the calling run (body: `{ tag: "key:value" }` → `{ tag, count }`). `key` must be listed in the agent's `tags.runtime`; the run is identified by the token, so no run id is passed. `400` malformed tag / key not allowed / value charset / value containing one of the run's secret values (secret inputs, `@SYSENV:` env values, the callback token; 8-character floor) / per-run cap (20) exceeded, `409` on a terminal or unknown process. Tags containing a value that is secret at read time are omitted from status responses. Re-declaring the same `key:value` is idempotent. | Callback token (`HMR_CALLBACK_TOKEN`) |
| POST | `/api/v1/callback/runs` | Dispatch a run immediately, fire-and-forget (body: `{ agent, params?, continueAgent?, continueThread?, continueParams?, resumeParam?, resultJsonPath?, ttlSec? }` → `{ processId, continuationId? }`): starts the opt-in `agent` with `params`, no operator question, fresh chain. When `continueAgent` is present, also registers a completion continuation that starts `continueAgent` once the sub reaches a terminal state, carrying its result under `resumeParam` (default `HMR_LG_RESUME`; a reserved env name other than the LangGraph control markers is rejected), the thread id under `HMR_LG_THREAD`, and any `continueParams`. `params` go to the sub; `continueParams` go to the continuation run. `400` malformed body, `403` non-dispatchable `agent`/`continueAgent`, `404` unknown `agent`/`continueAgent` or one this run's hop may not reach (indistinguishable by design), `409` on a terminal process; starting the sub can also answer `400`/`422` for invalid `params`, `429` for a cost cap or `cooldown`, and `503` at capacity, and registering the continuation `429` at the pending-continuation cap; on any of these the started sub is killed, so a `4xx`/`5xx` leaves no sub running. | Callback token (`HMR_CALLBACK_TOKEN`) |
| POST | `/api/v1/callback/joins` | Register a parallel fan-out join (body: `{ subs:[{ agent, params?, resultJsonPath?, interruptId }], continueAgent, continueThread?, continueParams?, resumeParam?, ttlSec? }` → `{ processIds, continuationId }`): starts every sub at once and registers ONE continuation that starts `continueAgent` a single time once the last sub reaches a terminal state, carrying all results keyed by interrupt id under `resumeParam` (default `HMR_LG_RESUME`; a reserved env name other than the LangGraph control markers is rejected). `subs[*].params` go to each sub; `continueParams` (with the thread id under `HMR_LG_THREAD`) go to the continuation run. A `4xx`/`5xx` leaves no sub running: every check that needs no started sub (including each sub's `params`) runs before the first start, and a failure that surfaces only while starting subs or registering the join kills every sub the request already started. `400` malformed body / empty `subs` / more than `server.dispatch.maxJoinSubs` subs / invalid sub `params` (`422` for an `inputSchema` violation), `403` non-dispatchable `continueAgent` or sub, `404` unknown agent or one this run's hop may not reach, `409` on a terminal process, `429` at the pending-continuation cap or for a sub's cost cap or `cooldown`, `503` at capacity. See [ADR-0026](../docs/adr/0026-join-continuations.md). | Callback token (`HMR_CALLBACK_TOKEN`) |
| GET | `/api/v1/callback/results/{token}` | Fetch a large continuation result by reference: returns the (transformed) artifact behind a `resultRef` capability token minted when a sub's result exceeded the inline cap. The token is audience-bound to the continuation's target agent — reusable within the token TTL by any process running that agent. `401` missing callback token or invalid/expired result token, `403` caller's agent is not the token's audience, `404` artifact gone, `413` oversized (raw over `server.maxArtifactBytes`, or a transform over 4 MiB). See [ADR-0025](../docs/adr/0025-capability-token-result-read.md). | Callback token (`HMR_CALLBACK_TOKEN`) + result token |
| POST | `/api/v1/callback/messages/take` | Take the operator messages queued for the calling run (see `POST /api/v1/status/{processId}/messages`): every `QUEUED` message, oldest first, marked `DELIVERED` in the same transaction; each message is returned once. Never long-polls. Without a query, `200 { "messages": [{ id, text, createdAt }] }` (empty list when nothing was queued). `?hook=post-tool-use` or `?hook=stop` answers with the Claude Code hook JSON for that event (`hookSpecificOutput.additionalContext`, or `decision: "block"` with the messages as `reason`), or `204` when nothing was queued. The run is the token's own (a sub-run has its own token). `400` for another `hook` value, `409` once the run has ended. | Callback token (`HMR_CALLBACK_TOKEN`) |
| POST | `/api/v1/auth/login` | Exchange username + password for an `HttpOnly` session cookie. Rate-limited (5/min per IP). Only mounted when `apiAuth: password` (or `combined` with `password`). | Not required |
| POST | `/api/v1/auth/logout` | Clear the session cookie. Idempotent — returns 204 whether or not a cookie was present. Only mounted when `apiAuth: password` (or `combined` with `password`). | Not required |
| GET | `/api/v1/auth/me` | Returns `{ username, role, grants }` for the holder of a valid session cookie — the console's capability source. Only mounted when `apiAuth: password` (or `combined` with `password`). | Cookie required |
| POST | `/mcp` | Stateless Streamable HTTP MCP endpoint exposing opted-in agents as MCP tools. Only mounted when `server.mcp.enabled`. See [MCP Server](#mcp-server). | Any authenticated caller (open under `unsecured`) |
| POST | `/mcp/questions` | MCP question tools (`ask_user`, `check_answer`, `ask_user_and_wait`, `ask_and_dispatch`) and `check_messages` for the running command's Claude process. Only mounted when `server.mcp.enabled`. See [MCP question tools](#mcp-question-tools). | Callback token (`HMR_CALLBACK_TOKEN`) |

### `GET /api/v1/stream/processes/{processId}/llm-events`

Each `llm.event` frame carries one harness-neutral event, translated from the
command's stdout by its harness adapter; declared-secret values are masked. The
frame `id` is `cmdIndex:attemptIndex:lineOffset:eventIndex`: `lineOffset` is the
stdout byte position where the line the event came from starts, and `eventIndex`
the event's 0-based index among that line's events (one line can yield several,
e.g. a tool call and its result). Send it back as `Last-Event-ID` to resume
right after that event; any other form, or a cursor for another attempt, replays
from byte 0. A stdout line over 4 MiB is skipped. A stdout file that is a symlink, hard link or other non-regular file is never served: the stream carries nothing from it (with `cmd`, a symlink already there answers `404`). A retry's new attempt is
followed from byte 0 once the previous attempt's file is drained. With the
default `artifactNameTemplate` (every attempt truncates the same file), bytes
written after the stream's last poll of a failed attempt may not appear before
the file is truncated by the next attempt; a per-attempt template (with
`{{HMR_CMD_ATTEMPT}}`) keeps each attempt's own file. `llm.end`
(`{}`) closes the stream once the process is terminal (with `cmd`, also once a
later command has started) and the files are drained.

`event.type` is one of six kinds (nullable fields are sent as `null`):

| `type` | Fields |
|--------|--------|
| `session_start` | `sessionId`, `model`, `tools` (string list) |
| `assistant_message` | `messageId`, `model`, `text`, `subagent` (boolean) |
| `tool_call` | `id`, `name`, `input` (JSON), `messageId` |
| `tool_result` | `id`, `output` (JSON), `isError` |
| `turn_end` | `status` (`{"type": "success"}`, or `failed` / `stopped` with `reason`), `usage` (`input`, `output`, `cacheRead`, `cacheWrite`, `reasoning` token counts), `costUsd` (`null` when the harness reports tokens only), `model`, `durationMs`, `durationApiMs`, `numTurns`, `resultText` |
| `transient_error` | `message` |

```text
event: llm.event
id: 2:0:1702:0
data: {"processId":12345,"cmdIndex":2,"attemptIndex":0,"event":{"type":"assistant_message","messageId":"msg_1","model":"claude-sonnet-4-5","text":"Done.","subagent":false}}

event: llm.end
data: {}
```

### `GET /api/v1/costs/monthly?month=YYYY-MM`

Per-agent LLM harness (Claude/Codex/opencode) spend for one **UTC** month, from the persistent cost
ledger. `month` is optional and defaults to the current UTC month; a
malformed value returns `400`.

```json
{
  "month": "2026-07",
  "perAgentUsd": { "reviewer": 2.0, "planner": 0.5 },
  "totalUsd": 2.5,
  "availableMonths": ["2026-07", "2026-06"]
}
```

### `GET /api/v1/costs/totals`

All-time per-agent LLM harness (Claude/Codex/opencode) spend from the persistent cost ledger.

```json
{
  "perAgentUsd": { "reviewer": 3.0, "planner": 0.5 },
  "totalUsd": 3.5
}
```

### `GET /api/v1/costs/by-tag/{key}`

LLM harness (Claude/Codex/opencode) spend broken down by the values of one run subject-tag key (see
[ADR-0062](../docs/adr/0062-cost-by-tag.md)). `key` must be a member of
`costTrackedTagKeys`; an unlisted key returns `400`. A tag whose value
is secret at read time — or whose input key is no longer in the current agent
definition — is excluded from the breakdown (fail-closed), same as `?tag=` on
`/status/all`. Totals cover at most the newest 10,000 runs carrying the key
(newest by process id); `truncated` is `true` when more exist, so the totals
are a partial sum rather than a silent undercount.

```json
{
  "key": "ticket_id",
  "valuesUsd": { "PROJ-123": 1.42, "PROJ-456": 0.31 },
  "totalUsd": 1.73,
  "truncated": false
}
```

### `GET /api/v1/runners`

Configured runners with permits in use, and for each `external` domain, its
live registered instances. See [ADR-0066](../docs/adr/0066-external-pull-runners-and-unified-claim-queue.md).

```json
{
  "runners": [
    { "alias": "local", "kind": "local", "tags": [], "concurrency": null, "inUse": 0, "instances": null },
    { "alias": "gpu-box", "kind": "ssh", "tags": ["gpu"], "concurrency": 2, "inUse": 1, "instances": null },
    {
      "alias": "workers",
      "kind": "external",
      "tags": [],
      "concurrency": null,
      "inUse": 1,
      "instances": [
        {
          "instanceId": "a1b2c3d4",
          "name": "host-1",
          "tags": ["gpu"],
          "concurrency": 1,
          "activeSessions": 1,
          "lastSeenAt": 1758000000,
          "registeredAt": 1757999000,
          "draining": false
        }
      ]
    }
  ]
}
```

`concurrency: null` means unbounded; `instances` is `null` on non-external
rows. `lastSeenAt` / `registeredAt` are Unix epoch seconds. The `local` row is
absent when `server.localRunner.enabled` is `false` (see
[ADR-0095](../docs/adr/0095-local-runner-can-be-turned-off.md)).

### `GET /api/v1/runners/queue`

Pending runner claims (requests waiting for a free permit), grouped by filter.

```json
{
  "pending": 2,
  "oldestAgeSec": 14,
  "byFilter": [
    { "filter": { "alias": null, "tags": ["gpu"] }, "pending": 1, "oldestAgeSec": 14 },
    { "filter": { "alias": "gpu-box", "tags": null }, "pending": 1, "oldestAgeSec": 3 }
  ]
}
```

A filter sets exactly one of `alias`/`tags`; the other is `null`. Top-level
`oldestAgeSec` is `null` and `byFilter` is `[]` when nothing is waiting.

### `GET`/`POST /api/v1/maintenance`

Admin maintenance mode: a durable switch an operator flips before replacing the
server. While it is on, cron fires are **skipped** (a missed schedule is never
replayed) and every other agent start — HTTP, MCP, dispatch, continuation,
`!<agent>` hop — waits in a bounded queue instead of running. A run already
`WORKING` is untouched: this is an admission gate, not a kill switch.

```json
{
  "enabled": true,
  "since": 1700000000,
  "queued": 2
}
```

`since` is Unix epoch **seconds** and `null` while the mode is off, and also
for a mode that was already on when the server booted (the toggle time is not
persisted, so a restart cannot report when it was turned on); `queued` is
the number of starts currently waiting. Both responses carry
`Cache-Control: no-store`, so a proxy cannot serve a stale "off" after the mode
was turned on.

`POST` takes `{ "enabled": true | false }` (`400` on anything else, including an
unknown key). Turning it **off** releases the queue in arrival order within one
release drain, at most `server.maintenance.releaseConcurrency` starts at a
time; rapid re-toggles run drains concurrently, so the order across drains is
not guaranteed. A start that waited
longer than `queueWaitSec` answers `503`, and one that arrived at a full queue
(`maxQueuedStarts`, or its own `maxQueuedStartsPerPrincipal` share) answers
`429` — neither is replayed later.

The gate is admin-only and handler-level: a non-admin or unauthenticated caller
gets the same `404` an unmounted path answers, and the body is parsed only after
that check, so the endpoint's existence and request shape stay hidden
(ADR-0037, ADR-0047 rule 3).

### `GET /api/v1/agents/{agentName}/langgraph`

Returns the workflow graph of each `!<langgraph>` command on the agent as
nodes and edges, for visualization in the console. `404` if the agent does not
exist; `200 []` if it has no LangGraph commands.

Response: array of
`{ "label": string, "module": string, "topology": { "nodes": [{ "id": string }], "edges": [{ "source": string, "target": string, "label": string | null, "conditional": boolean }] } | null, "mermaid": string | null, "error": string | null }`.

Each command's graph is rendered by a short-lived Python subprocess on the server
host. The number running at once is capped: under concurrent load a request waits
briefly for a slot rather than adding another interpreter, and if none frees up in
time each entry comes back with a `busy` `error` instead. Retrying is safe.
Exactly one of `topology` / `error` is non-null per element (`error` when graph
introspection failed, e.g. an import error); both keys are always present.
`mermaid` is deprecated and will be removed; a topology over 200 nodes, 1000
edges or 200-character ids/labels is reported as `error`. A command whose
`python` carries a `homer_langgraph` without `inspect --json` reports that as
`error`.

Introspection imports the module in a subprocess with the agent's declared
`env` applied, so the `module` resolves exactly as it does for a real run — if
the module needs a `PYTHONPATH`, set it in the agent's `env`. A module that is
not importable yields an `error` element rather than failing the request.

### Agent execution inputs

When an agent declares any `body` input (`Param.src: body`), POST is
required and the request body must be `application/json` (or
`application/*+json`). An agent without an `inputSchema` also accepts a
bodyless POST: an empty body counts as `{}`, so optional body inputs fall
to their defaults and a missing required one is still a `400` naming the
input. The Content-Type requirement applies only when a non-empty body is
sent. Inputs with `src: header` are read from the
request headers (case-insensitive). Inputs with `src: query` (the
default) continue to come from the URL query string. See
[`AGENT.md`](AGENT.md) § *Agent Inputs* for the full reference.

### Agent file-editing endpoints

The file-editing endpoints reject path traversal (`..`, absolute
paths, symlinks escaping the agent directory) with 400 and refuse
writes when the agent's filesystem entry is not writable — reflected
by `writable: false` on the entries returned by `/api/v1/agents`. The
probe uses `Files.isWritable`, which is POSIX-semantics on Linux/macOS
and ACL-semantics on Windows; behavior on exotic filesystems (NFS,
stacked bind mounts) may be surprising. See
[ADR-0004](../docs/adr/0004-agent-file-editing-api.md) for the
decisions behind the write API.

### `GET /api/v1/artifacts`

Streams an artifact file produced by a process. Requires `processId`
(integer) and a non-blank `path` (relative to the process's artifacts
directory; path traversal is rejected with `400`); `404` if the process or
file does not exist, or the resolved path is not a regular file (a
FIFO/device/directory/symlink is treated as not found) or is a hard link (more than one
link, also treated as not found). Returns `413` if the artifact exceeds
`server.maxArtifactBytes`. The response carries `X-Process-Status` (the
process's current status) and `X-File-Complete` (`false` while the process
is still `WORKING`/`CREATED` and may still append to the file, `true`
otherwise) headers.

### `GET /api/v1/processes/{id}/langgraph`

LangGraph run introspection for a process. Returns the run's thread id and
(with `?graph=true`) the workflow's `topology` (nodes and edges) plus per-node
status (`done` / `failed` / `parked` / `pending` / `unreached`), and `traversed`
(list of `{ source, target }`): the workflow edges this run has actually taken,
limited to edges of `topology`; `null` when `topology` is unavailable or the runtime
did not report it (an older runtime, or one that could not determine the path). For fan-out nodes,
`subs` (map of node -> `[{ interruptId, processId?, agentName?,
status?, commands? }]`) reports the sub-run behind each interrupt id the node
has parked on — pending now **or already resolved**, so a completed node keeps
its fan-out history — resolved against the run owner's continuations of any status.
`commands` is the sub run's per-command progress
(`[{ label, state: ok|failed|skipped|running }]`). Sparse (unmatched interrupt
ids and nodes with no matches are omitted) and `null` when the runtime does not
report interrupts or enrichment fails; `agentName`/`status`/`commands` are null
for a sub whose process has since been evicted from the process store. A node's
interrupt history is capped at its 100 most recent ids (a node's live park is
never dropped), so a node that has served more fan-outs than that reports only
its newest subs and is listed in `subsTruncated`. `404` when the process is not a
LangGraph run (no run-identity artifact). `topology` is `null` for an older
runtime, a topology over 200 nodes/1000 edges/200-character ids or labels, or a
draw failure, while `nodes`/`errors`/`subs` still arrive; `traversed` is then
also `null`, since it is only ever reported against that same topology;
`mermaid` is deprecated and may likewise be `null`. A `state` runner failure
is reported in `error` with the rest null.
`nodes`/`errors`/`topology`/`traversed`/`mermaid` come from a
reading of the checkpoint store that is cached for 4s, so concurrent viewers of
one run share a single subprocess and
node status can lag by up to that long; `subs` is resolved per request and always
current. The number of those subprocesses running at once is capped: under
concurrent load a request waits briefly for a slot rather than adding another
interpreter, and if none frees up in time the response carries a `busy` `error`
with the rest null. That refusal is not cached, so an immediate retry is served
normally. Visibility-gated like other process-scoped routes.

### `POST /api/v1/processes/{id}/resume`

Resume a failed LangGraph run from its latest checkpoint. Allowed for
processes in `FAILED` / `KILLED` / `UNKNOWN`; starts a new process of the same
agent carrying the original inputs plus the recover markers. `409` for other
statuses, `400` when the process has no LangGraph thread recorded or the
agent definition no longer contains a `!<langgraph>` command; `429` when the
agent's `cooldown.minInterval` is active (a manual resume is not gated by
`afterFailure`). Gated like
retry (`canRetry`).

### Authentication

When `apiAuth` is configured as `bearer`, include the token in the `Authorization` header:

```bash
curl -X POST -H "Authorization: Bearer your-token-here" \
  "http://localhost:8080/api/v1/agent/hello?name=World"
```

Each token declares `role: admin` and/or `grants` — what agents and
actions (`run`/`read`/`control`/`edit`/`debug`) it may reach:

```json
{
  "server": {
    "apiAuth": {
      "type": "bearer",
      "tokens": {
        "admin": { "token": "secret-admin-token", "role": "admin" },
        "ci":    { "token": "ci-token", "grants": [{ "agents": ["deploy-*"], "actions": ["run"] }] }
      }
    }
  }
}
```

Unknown credentials receive `401`. A caller without the needed grant receives `403` on admin-only routes (except cron-run, which answers `404`), on the agent file-editing routes, and on run and debug for an agent it holds some grant on; on those two, and on LangGraph inspect (which any grant passes), a caller with no grant at all on the agent gets the same `404` as an unknown agent. Routes scoped to a process, question, dispatch or continuation answer `404` too — identical to not-found, so they never confirm the resource exists. See [`SERVER.md`](SERVER.md) § *Grants and roles* and [ADR-0037](../docs/adr/0037-grants-authorization.md) for the full model (actions, `role: admin`, `server.agentGrants`) and the migration path off the removed `endpoints` allowlist.

For browser clients, configure `apiAuth: password` (or `combined`
with `password`) and the console will log in with username + password
against `POST /api/v1/auth/login`, then ride an `HttpOnly`
`SameSite=Lax` session cookie. CLI / MCP / webhook clients keep using
`Authorization: Bearer ...` (or HMAC). See
[`SERVER.md`](SERVER.md) § *Password Authentication* and
[ADR-0011](../docs/adr/0011-cookie-based-console-auth.md).

Under cookie/console auth, every request authenticated by the session
cookie — including state-changing actions and SSE streams — must carry
the custom header `X-Homer-CSRF` (any non-empty value); the console
sends it automatically. Requests missing it are rejected with `403`.
Token/HMAC callers are unaffected and need no such header, so the GET
webhook/curl triggers below keep working. Cookie auth also requires
`corsAllowedOrigins` to be set to the console origin(s) — see
[ADR-0016](../docs/adr/0016-cookie-auth-csrf-protection.md).

The `/api/v1/callback/*` endpoints (`questions*`, `dispatches`, `tags`, `runs`, `joins`, `results/{token}`) use a
separate credential domain: a per-process random token Homer injects into every
command as `HMR_CALLBACK_TOKEN` (sent as `Authorization: Bearer ...`). Operator
bearer/HMAC/cookie credentials do **not** work there, and the callback
token works nowhere else — it authorizes exactly its own process's
callbacks. Tokens die with the process state (server restart → `401`).
See [ADR-0019](../docs/adr/0019-human-in-the-loop-questions.md) and
[`AGENT.md`](AGENT.md) for how agents ask questions.

### Example API Usage

**List all agents:**
```bash
curl "http://localhost:8080/api/v1/agents"
```

**Run an agent with parameters:**
```bash
curl -X POST "http://localhost:8080/api/v1/agent/analyze?input=data.json&format=detailed"
```

**Run an agent as the child of an existing run** (the parent must be visible to the caller):
```bash
curl -X POST -H "X-Homer-Parent-Process-Id: 12345" "http://localhost:8080/api/v1/agent/analyze?input=data.json"
```

**Check process status (using integer process ID):**
```bash
curl "http://localhost:8080/api/v1/status/12345"
```

**Response format:**
```json
{
  "id": 12345,
  "status": "WORKING",
  "agentName": "example-agent",
  "group": "platform",
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
      "errMsg": null,
      "skipped": false,
      "llmAccounting": null,
      "kind": "cmd"
    }
  ],
  "currentCommand": "cmd",
  "currentCommandStart": 1700000050,
  "currentCommandKind": "cmd",
  "currentCommandIndex": 0,
  "purgeAt": null,
  "inputParams": { "name": "World" },
  "runner": { "alias": "local", "type": "local", "podName": null, "podPhase": "NONE", "createdAt": null, "readyAt": null, "endedAt": null, "startupFailure": null },
  "costUsd": 0.0,
  "costSource": "cli",
  "openQuestions": 0,
  "owner": "ci",
  "langfuseTraceUrl": null,
  "parentProcessId": null,
  "rootProcessId": null,
  "retainedTruncated": null,
  "tags": [],
  "failureReason": null
}
```

The response includes:
- **id**: Process ID
- **status**: Current process status (CREATED, WORKING, FINISHED, FAILED, KILLED, UNKNOWN — set by startup reconciliation for runs interrupted by a server restart; terminal)
- **agentName**: Name of the agent that was executed
- **group**: The agent's current group label (optional; null when the agent is ungrouped or no longer loaded), resolved from the agent's current definition at read time
- **history**: Complete audit trail of all status transitions with timestamps
- **executions**: Detailed execution information for each command including timing, exit codes, output file paths, and error messages. `resultCode` is normally the command's exit code. Commands skipped by a `when` clause record `-1` with `skipped: true` and the skip reason in `errMsg`; a failed `when`-predicate evaluation records the predicate's exit code instead; a timed-out predicate records `-1` with the timeout message in `errMsg`. Error text on these entries is secret-redacted. Otherwise `-1` means no exit code was available (internal failure, or the run was aborted for exceeding the output cap). `kind` is the command's tag (`cmd`, `script`, `claude`, `codex`, `opencode`, `agent`, `langgraph`, `runnerCmd`); empty for runs recorded before 1.27. LLM harness commands (`claude`, `codex`, `opencode`) also carry `llmAccounting` — `{sessionId, costUsd, source, fallbackReason, usage}` — the CLI session id, the cost charged for that command, its source (`cli` | `session` | `none` — no cost figure was available from either), and, when the cost figure is degraded, why — a CLI fallback, an incomplete session figure (the endpoint had not ingested every call of the session), or a pending one (`session figure pending` — the step returned on the CLI figure and the session lookup is still settling in the background, replacing the record when it answers). `usage` is `{input, output, cacheRead, cacheWrite, reasoning}` token counts summed over every turn end of the winning attempt, or `null` when no turn end was seen. `llmAccounting` is `null` for non-LLM commands.
- **currentCommand**: Label of the command currently executing, `null` if none
- **currentCommandStart**: Unix epoch seconds when the current command started, `null` if none
- **currentCommandKind**: Tag of the command currently executing, `null` if none
- **currentCommandIndex**: Index of the command currently executing, `null` if none
- **purgeAt**: Unix epoch seconds when the process is scheduled for purge, `null` if not yet scheduled or still active
- **inputParams**: The input parameter values the run was started with. Values of `secret: true` inputs (flagged now or at run start) and of any key the current definition does not declare as an input (including control markers such as `HMR_LG_THREAD`) come back as `***`; every value does when the agent is no longer loaded
- **runner**: The backend that served the run — `{alias, type, podName, podPhase, createdAt, readyAt, endedAt, startupFailure}`. `type` is the generic backend kind (`local`, `ssh`, `kubernetes`, `external`, or `unknown` for an alias no longer configured) and is always emitted, matching the `kind` on `GET /api/v1/runners`. `alias` is the operator-chosen backend name and is returned **only to an `admin` caller**; every other caller who can see the run gets `alias: null` with the generic `type`, because the alias can name a host, tenant or environment. For a run that got a session and created its workspace it is the `local`, `ssh`, `external` or Kubernetes runner that served it, not the queue filter of a `runnerTags` run. The pod fields (`podName`, `podPhase`, the timestamps, `startupFailure`) are populated only for a Kubernetes run. The whole `runner` block is `null` for a run that never got a session (claim timeout, refused claim, or a run from before this field was populated), and for a run whose workspace creation failed after the session opened
- **costUsd**: LLM harness (Claude/Codex/opencode) spend attributed to this process so far
- **costSource**: Which pricing rung produced `costUsd`: `cli` (the CLI's own estimate), `session` (the configured `llmPrice.sessionSpend` endpoint), or `mixed` (charges on this run came from both). See [SERVER.md → LLM price](SERVER.md#llm-price).
- **openQuestions**: Count of OPEN operator questions waiting on this process
- **owner**: Principal that caused this run (bearer token key, `hmac:{keyId}`, `session:{username}`, `system:cron`, or `null`); determines who can see/control it. See [ADR-0038](../docs/adr/0038-process-ownership.md).
- **langfuseTraceUrl**: Deep link to this run's Langfuse trace, or `null`. Requires both `langfuse.serverUrl` and `langfuse.projectId` to be configured, and a run that produced a trace.
- **parentProcessId**: Id of the run that caused this one — the `RunAgent` parent, the origin of a dispatch or continuation, or the `X-Homer-Parent-Process-Id` header an external caller sent; a retry or resume keeps the source run's parent. `null` at the root of a flow. The parent row may already be gone; the id is kept as a plain reference, not a foreign key. See [ADR-0058](../docs/adr/0058-parent-child-run-linkage.md).
- **rootProcessId**: Id of the first run in this run's flow — the root of the parent chain. `null` at the root run itself, and `null` on a row whose flow root is unknown (a row predating the column, or one whose root was evicted before it) or whose root the caller cannot see — the id is withheld when the root row is not openable by the caller, so the existence of another principal's flow root is not revealed. Like `parentProcessId` it is a plain reference, not a foreign key. See [ADR-0058](../docs/adr/0058-parent-child-run-linkage.md).
- **retainedTruncated**: Whether `server.maxRetainedArtifactBytes` cut this run's `workspace.retain.artifacts` set short at the workspace purge. `null` until a retention prune has run.
- **tags**: Subject tags on the run (`key:value` strings) from `tags.fromInputs` and runtime `POST /api/v1/callback/tags`, listed in tag order (sorted by tag, not insertion order); filter with `?tag=`. Tags carrying a value that is secret at read time are omitted.
- **failureReason**: Why the run ended `FAILED` **before any of its commands started** — a claim timeout, a `SKIP_CURRENT` lock skip, a `REJECT` parallel-run cap, a full parallel-run wait queue, or a full claim queue. `null` for every other failure, including a failure that happened while the run was running, and for a run recorded before schema v30 (the upgrade adds the columns and does not backfill). At most 500 characters, one line, secret-redacted. When the cause names the run that blocked this one (a lock holder), that run's id is appended as `(run #123)` **only to a caller who may open it**; every other caller gets the same sentence without the id, so the existence of another principal's run is not revealed. See [ADR-0098](../docs/adr/0098-pre-start-failure-reason-is-a-fixed-vocabulary.md).

**Get all processes with filtering:**
```bash
# Get all processes (default: descending order by ID, limit 100)
curl "http://localhost:8080/api/v1/status/all"

# Filter by status types
curl "http://localhost:8080/api/v1/status/all?types=WORKING,FINISHED"

# Order ascending by process ID
curl "http://localhost:8080/api/v1/status/all?order=asc"

# Pagination
curl "http://localhost:8080/api/v1/status/all?limit=50&offset=0"
curl "http://localhost:8080/api/v1/status/all?limit=50&offset=50"

# Children of one run (parent/child linkage)
curl "http://localhost:8080/api/v1/status/all?parentProcessId=12345"

# Every run below the flow root
curl "http://localhost:8080/api/v1/status/all?rootProcessId=12345"

# One row per flow
curl "http://localhost:8080/api/v1/status/all?roots=true"

# Runs by subject tag (repeatable = AND)
curl "http://localhost:8080/api/v1/status/all?tag=mr:15"
curl "http://localhost:8080/api/v1/status/all?tag=mr:15&tag=projectId:4711"

# Combine all filters
curl "http://localhost:8080/api/v1/status/all?types=WORKING&order=asc&limit=20&offset=0"
```

**Response format:**
```json
{
  "processes": [
    {
      "id": 12345,
      "status": "WORKING",
      "agentName": "example-agent",
      "history": [...],
      "executions": [...]
    }
  ],
  "total": 150,
  "offset": 0,
  "limit": 100
}
```

The response includes:
- **processes**: Array of process information objects (same format as `/status/{processId}`, except `currentCommandKind` and `currentCommandIndex` are always `null`)
- **total**: Total number of matching processes (before pagination)
- **offset**: The offset that was applied
- **limit**: The limit that was applied

Query parameters:
- **types**: Comma-separated list of status types to filter (case-insensitive). Valid values: CREATED, WORKING, FINISHED, KILLED, FAILED, UNKNOWN
- **agentName**: Filter to processes for a single agent
- **parentProcessId**: Filter to the direct children of one run (positive integer; `400` otherwise). Composes with the caller's visibility filter, so children the caller cannot see are not listed
- **rootProcessId**: Filter to every run below the flow root, in one paginated read (positive integer; `400` otherwise). Composes with the caller's visibility filter, so descendants the caller cannot see are not listed. Rows with no stored root are not matched, and a flow root the caller cannot see reads as an empty list, never as an unfiltered list
- **roots**: `true` to filter to runs with no parent (`parent_process_id IS NULL`) — one row per flow. Composes with `types`, `agentName` and `tag`
- **tag**: Repeatable `key:value`; a run must carry every one (at most 10 params)
- **order**: Sort order by process ID - `asc` or `desc` (default: `desc`)
- **limit**: Maximum number of results to return (default: 100, max: 1000)
- **offset**: Number of results to skip (default: 0)

**Kill a running process (using integer process ID):**
```bash
curl -X DELETE "http://localhost:8080/api/v1/kill/12345"
```

## MCP Server

With `server.mcp.enabled: true` (see [`SERVER.md`](SERVER.md#mcp-endpoint)),
Homer serves a [Model Context Protocol](https://modelcontextprotocol.io)
endpoint at `POST /mcp` on the same port as the REST API — stateless
Streamable HTTP in JSON-response mode (no sessions, no SSE). LLM clients can
start agent runs, poll them, fetch artifacts, and cancel them as tool calls.

**Connect URL:** `http://<host>:<port>/mcp`. The endpoint sits behind the
same authentication as the REST API; bearer clients send
`Authorization: Bearer <token>`. The agent-tool list is filtered by
`run` grant (a caller sees only tools for agents it may run), MCP-started
runs are owner-stamped, and the run-management tools
(`get_run_info`/`list_artifacts`/`get_artifact`/`cancel_run`) are
visibility-gated to the run's owner / a `read` (or `control`, for
`cancel_run`) grant on its agent — see
[ADR-0038](../docs/adr/0038-process-ownership.md) and
[ADR-0039](../docs/adr/0039-nested-dispatch-authority.md). Per the MCP
Streamable HTTP spec, every POST must send
`Accept: application/json, text/event-stream` — requests missing either
media type are rejected with `406`. Both `/mcp` and `/mcp/questions` accept
POST only — GET and DELETE requests are rejected with `405`.

**Tool catalogue:**

| Tool | Description |
|------|-------------|
| *(one per opted-in agent)* | Starts a run of the agent. Name = agent name, arguments from the agent's `inputs`/`inputSchema` (header inputs excluded). Optional `_wait` (seconds) blocks until the run finishes and returns final status + cost + artifact list; without it the call returns `{runId, status}` immediately. See [`AGENT.md`](AGENT.md#mcp-exposure). |
| `get_run_info` | Status, cost, and per-command results of a run (`runId`). Works for runs started over MCP, HTTP, or cron. |
| `list_artifacts` | List the artifact files a run produced (`runId`). Capped at 10,000 entries; the result flags `truncated` when files were omitted. |
| `get_artifact` | Inline a text artifact (`runId`, `path`); truncated at `server.mcp.maxInlineArtifactBytes`, binary files are rejected with a pointer to `GET /api/v1/artifacts`. |
| `cancel_run` | Kill a running run (`runId`). |

`get_run_info`/`list_artifacts`/`get_artifact` require the same visibility
as the REST routes they mirror (`admin` ∪ the run's owner ∪ a `read` grant
on its agent); `cancel_run` requires `control` in place of `read`. A denied
call returns the same "Run not found" tool error an unknown `runId` would —
existence is hidden, same as the REST 404s. See
[ADR-0039](../docs/adr/0039-nested-dispatch-authority.md).

Agents are exposed only when their definition opts in with
`mcp: { enabled: true }`. The tool list is rebuilt per request, so agent
reloads are reflected immediately.

**Sample client config** (Claude Code / any MCP client supporting
Streamable HTTP):

```json
{
  "mcpServers": {
    "homer": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "Bearer your-token-here"
      }
    }
  }
}
```

### MCP question tools

With the same `server.mcp.enabled` switch, Homer also serves
`POST /mcp/questions` — a separate stateless MCP endpoint with the
human-in-the-loop question tools for *running commands* (typically a
`claude` command wired via `HMR_MCP_CONFIG`, see
[`AGENT.md`](AGENT.md)). It authenticates with the per-process
callback token (`Authorization: Bearer $HMR_CALLBACK_TOKEN`), the same
credential domain as `/api/v1/callback/questions*` — operator
credentials do not work here and vice versa.

| Tool | Description |
|------|-------------|
| `ask_user` | Ask the operator a question (`text` Markdown, optional `options` button list) without blocking; returns `{questionId, status}` immediately. |
| `check_answer` | Check / briefly wait for the answer (`questionId`, optional `waitSec` clamped to 50). An `OPEN` result just means call it again — questions survive between calls. |
| `ask_user_and_wait` | Ask and block until answered, up to `ttlSec` (default and cap: `server.questions.maxWaitSec`). On timeout the question stays OPEN and `check_answer` picks it up. |
| `ask_and_dispatch` | Ask the operator a question and, when they answer, start another agent with the given params plus the answer — without waiting. Returns `{dispatchId, questionId}` immediately. |
| `check_messages` | Take the operator messages queued for this run (no arguments), like `POST /api/v1/callback/messages/take`: returns them as one text block and marks them `DELIVERED`, or `No messages from the operator.` A run that has ended gets a tool error. |

Unlike `/mcp`, responses are delivered in SSE mode: headers flush
before the tool runs, idle gaps carry comment heartbeats, and while
`ask_user_and_wait` holds, `notifications/progress` events stream
every 15 s (when the call carries a `progressToken`) so spec-compliant
clients keep resetting their request timeout. If a client kills a held
call anyway, nothing is lost — the question is server-side state and
`check_answer` resumes the wait.

## Architecture Overview

```
┌─────────────┐
│  HTTP API   │ ← User requests
└──────┬──────┘
       │
       ▼
┌─────────────────┐
│ Agent Loader    │ ← Reads agent definitions from agentsSearchPath
└──────┬──────────┘
       │
       ▼
┌─────────────────┐
│ Process Manager │ ← Creates per-run workspaces
└──────┬──────────┘
       │
       ▼
┌──────────────────────────────────┐
│ Command Executor (Sequential)    │
│  • Cmd (shell commands)          │
│  • Script (shell scripts)        │
│  • Claude (Claude Code CLI)      │
│  • Codex (Codex CLI)             │
│  • Opencode (opencode CLI)       │
│  • RunAgent (nested agent)       │
│  • LangGraph (Python graph)      │
│  • RunnerCmd (remote runner cmd) │
└──────┬───────────────────────────┘
       │
       ▼
┌─────────────────┐
│ Result Transformer │ ← JSONPath, Regex
└──────┬──────────┘
       │
       ▼
┌─────────────────┐
│ Environment     │ ← Pass results between commands
└──────┬──────────┘
       │
       ▼
┌──────────────────────────────────┐
│ OpenTelemetry → Langfuse         │ ← Full observability
└──────────────────────────────────┘
```

### Process Lifecycle

```
CREATED → WORKING → FINISHED (success)
                 → FAILED (error)
                 → KILLED (user terminated)
                 → UNKNOWN (interrupted by a server restart; terminal)
```

### Observability Pipeline

Homer integrates with OpenTelemetry and Langfuse:

1. **Trace Creation**: Each agent execution creates a trace
2. **Span Generation**: Each command creates a span with timing and metadata
3. **Prompt Tracking**: Claude commands can reference Langfuse-managed prompts
4. **Export**: Telemetry data exports to Langfuse OTLP endpoint or logs

## Key Features

- **Sequential commands with env passing** — see [AGENT.md § Result-to-Environment Mapping](AGENT.md#result-to-environment-mapping)
- **Per-run workspaces and `HMR_*` variables** — see [AGENT.md § Workspace Configuration](AGENT.md#workspace-configuration)
- **Lock strategies** (`WAIT`, `SKIP_CURRENT`, `KILL_OLD_RUN_CURRENT`) — see [AGENT.md § Lock Strategy](AGENT.md#lock-strategy)
- **Langfuse-managed prompts** — see [AGENT.md § Langfuse](AGENT.md#langfuse---langfuse-managed-prompt)

## Configuration Reference

For complete configuration documentation, see [SERVER.md](SERVER.md).

## Agent Development

For comprehensive agent format and feature documentation, see [AGENT.md](AGENT.md).

## License

Apache License 2.0 — see the [LICENSE](../LICENSE) file at the repository root.

## Contributing

Contributions welcome! Please open an issue or pull request. See
[../CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines.
