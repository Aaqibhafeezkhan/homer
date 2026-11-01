# Backend CLAUDE.md

Backend-dev guide for the Kotlin/Ktor orchestrator. Project-wide rules (commit hygiene, tests,
ADRs, changelog, docs, schema sync, security) live in the root [`../CLAUDE.md`](../CLAUDE.md) and
apply here too.

User-facing docs — don't duplicate their content here: [`README.md`](README.md) (API reference),
[`AGENT.md`](AGENT.md) (agent format), [`SERVER.md`](SERVER.md) (config), [`../CHANGELOG.md`](../CHANGELOG.md)
(deltas), [`../docs/adr/`](../docs/adr/) (decisions). Subsystem guides — the nearer `CLAUDE.md`
auto-loads when you read files in its package (root §9 lists each as canonical for its surface):
[`runners/`](app/src/main/kotlin/homer/app/runners/CLAUDE.md),
[`services/persistence/`](app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md),
[`executors/llm/`](app/src/main/kotlin/homer/app/executors/llm/CLAUDE.md).

## Stack

- **Kotlin 2.4.21** on **JDK 25** (pin `JAVA_HOME` — see root §5).
- **Ktor 3.5.0** (Netty) server + client — pinned below 3.5.1: the KTOR-9621 fix there runs rate
  limiting before authentication, which breaks the principal-keyed `perCaller` limiter (ADR-0014).
- kotlinx.serialization JSON, kaml YAML, Koin DI, kotlinx.coroutines, OpenTelemetry with the Langfuse
  exporter, JsonPath, JUnit + MockK + Ktor test-host. Versions for everything but the two pins:
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Layered architecture

```
HTTP API     Application.kt + its configureXxx files + routes (Ktor + bearer auth)
Services     AgentsService · AgentRunStarter · AgentSubRunStarter · ProcessManager · CostMeter · CooldownGate · MaintenanceModeService · StartupReconciliation · ArtifactService · LogTailService · LlmEventStreamService · ProcessEventBroadcaster · CronSchedulerService · WorkspacePurgeService · QuestionsService · RunMessagesService · DispatchService · ContinuationService · LangGraphInspectService · LangGraphRunStatusService · Authorizer
Execution    ProcessExecutor · AgentExecutor · CommandExecutorFactory · RunnerRegistry · RunnerClaimQueue · ExternalRunnerDomain · ExternalRunnerInstance · RunnerStatusReport · LockManager · ParallelRunsManager
Executors    CmdExecutor · ScriptExecutor · LlmHarnessExecutor (+ ClaudeHarness · CodexHarness · OpencodeHarness) · RunAgentExecutor · LangGraphExecutor · RunnerCmdExecutor
Infra        ShellExecutor · Workspace · FileOperations · ResultToEnvMapper · SysEnvResolver · TelemetryService · LangfuseClient
```

Types moved between rows by ADR-0083, each for what it is:

- `CostMeter` → Services: the spend-cap service over the durable cost ledger, not support code (it had no row, so its package put it in Infra).
- `CooldownGate`, `StartupReconciliation` → Services: stateful services over `ProcessRepository`
  (reconciliation also drives `QuestionsService`, `DispatchService` and `ContinuationService`), not
  Infra support code.
- `ExternalRunnerDomain`, `ExternalRunnerInstance` → Execution: an instance is a claim consumer of
  `RunnerClaimQueue` like `StaticRunnerConsumer`, and its domain registers and expires them (ADR-0066).
- `RunnerStatusReport` → Execution: the operator read view of `RunnerRegistry`, its claim queue and
  the external domains, the Execution state it reports.
- `LockManager`, `ParallelRunsManager` → Execution: the in-memory lock and slot gates `Process` holds while a run executes.
- `TelemetryService`, `LangfuseClient` → Infra: clients over the OpenTelemetry exporter and the Langfuse HTTP API.
- The support code that calls a service → Services: `McpToolFactory`, `QuestionsMcpToolFactory`,
  `McpRunTools`, `DispatchGates`, `AgentDefinitionWriteGate`, `AgentFilePathResolver`,
  `RunLiveness` and `ProcessGraphInspectRunner` (the runner `LangGraphInspectService` declares). Their
  package had put them in Infra while their same-package calls into services went unseen; they stayed in
  `services/` when the Infra code moved to `infra/`.
- `LlmSpanEmitter` → Services: it calls no service, but it is the span writer `LlmTelemetryService` was
  split into for size, built only by that service, so it stays beside it in `services/`.

### Directory layout

```
app/src/main/kotlin/homer/app/   Application.kt (main, configureServer, configureRouting, shutdown
                                 drain); ConfigValidation.kt (startup @SYSENV resolution, validate*/
                                 warnOn*); AuthWiring.kt (auth providers, configureAuth);
                                 HttpPlugins.kt (CORS, security headers, access log, StatusPages,
                                 apiJson); RateLimiting.kt (limiter keys, body limits); Redaction.kt ·
                                 ResponseMapping.kt (secret redaction; ProcessInfo, Dispatch,
                                 Continuation, Question → response DTO);
                                 routes/ (one file per resource, e.g. ProcessRoutes.kt, mounted in
                                 order by apiV1Routes in SecuredRoutes.kt; run-start handlers in
                                 RunHandlers.kt; ApiDeps); auth/ (session auth, CSRF);
                                 models/ (Agent · AppConfig · Command · *Response DTOs; the run's
                                 domain types ProcessInfo · ExecutionInfo · ProcessStatus in
                                 ProcessRun.kt); services/ (the Services layer: one file per service
                                 above, the support code that calls one, persistence/); infra/ (the
                                 Infra layer: the Infra row above, its implementations and the
                                 support code below it — workspace, redaction, path safety, LLM
                                 output helpers, agent loading and validation); runners/ (runner
                                 contract; local · ssh · kubernetes · queue · external — see
                                 runners/CLAUDE.md); executors/
                                 (orchestration: Process, ProcessExecutor, AgentExecutor, the lock
                                 and slot gates, the ports Execution declares; + one executor per
                                 command type); di/ · config/ ·
                                 exceptions/
app/src/test/kotlin/homer/app/   Mirrors the production packages; ApplicationTest.kt is the HTTP
                                 integration suite, fixtures/ the reusable builders
app/src/test/resources/          logback-test.xml (console-only, so tests never create api.log);
                                 claude/ and llm/<id>/ hold captured or documented CLI streams
```

Test files mirror the production package layout: `services/FooService.kt` gets
`services/FooServiceTest.kt` (root §6). Property-based tests use kotest-property inside a plain
JUnit test (`checkAll` in `runTest`, no Kotest engine), e.g. `models/CmdArgsTokenizerPropertyTest.kt`.
Fixtures are inline `"""…"""` strings, except `claude/`
(real CLI captures named for the CLI's `major.minor`, scrubbed to `test-*`) and `llm/<id>/` (one
directory per harness; Codex and opencode written from their documented JSON schemas, ADR-0070). A CLI bump is
verified by adding a capture, never by editing an inline string —
[ADR-0057](../docs/adr/0057-claude-output-modelled-only-where-read.md).

## Key service responsibilities

One line per service: what it owns, plus the ADR holding the reasoning. Only
the constraints an editor could break without noticing are spelled out, each
marked `Constraint:`.

- **`AgentsService`** — loads agent definitions from `agentsSearchPath`;
  `reload()` rescans it recursively and emits the `agent.reload` SSE diff. ADR-0068.
- **`AgentRunStarter`** — call-independent core of starting a run: span,
  invocation-chain checks, cost-cap preflight, create → execute; shared by the
  HTTP route, the MCP tools and `CronSchedulerService`. See ADR-0050,
  ADR-0008 and ADR-0007.
- **`ProcessManager`** — the `CREATED → WORKING → FINISHED/FAILED/KILLED`
  process FSM (plus `UNKNOWN` for restart-interrupted runs) over history and
  executions. **SQLite-backed via `ProcessRepository`**, terminal-first
  eviction; the live `Job` is the one non-durable field. See ADR-0020 and
  [`services/persistence/CLAUDE.md`](app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md).
- **`CostMeter`** — the soft spend caps (`costLimits`) over the durable `cost_entries` ledger:
  `checkCaps` before a run, `recordCost` after a priced LLM attempt, and the cost API's
  statistics. See ADR-0035 and ADR-0007.
- **`AgentSubRunStarter`** — the `SubRunStarter` port `RunAgentExecutor` starts a `!<agent>` hop
  through: the target from `AgentsService`, the hop gate and the child's flow root from
  `Authorizer`, the start from `AgentRunStarter` with the checks every entry point gets. ADR-0030.
- **`CooldownGate`** — the pre-flight cooldown refusal before a run is created; the bound itself
  is enforced inside `ProcessRepository.obtainProcessId`'s INSERT transaction, sharing
  `CooldownDecision` so the two cannot drift. ADR-0051.
- **`MaintenanceModeService`** — the admin maintenance flag and the bounded queue of starts
  parked behind it: `isEnabled()`, `setEnabled()` (persisted to `server_meta`), `queueDepth()`,
  `awaitRelease(owner)` and `gated(owner) { start }` — the park-and-release protocol every start
  path runs through, `AgentRunStarter.start` and the direct-build starts in
  `routes/RunHandlers.kt` alike; `CronSchedulerService` reads the flag to skip its tick. Nearly
  every entry point converges on `AgentRunStarter.start`, so this one gate covers HTTP, MCP,
  `!<agent>` hops, dispatches and continuations; retry, resume and debug build their process
  directly and take the gate in `respondStartedUnderRootSpan` (`routes/RunHandlers.kt`), so a
  fourth start path must not be added without one. See ADR-0092.
  - **Constraint:** the gate is called **before** `AgentRunStarter`'s preflight, so a released
    start re-runs the cycle, depth, cost-cap and cooldown checks and resolves its inputs **at
    dequeue, not at enqueue** — moving the call below them starts a run on a decision that was
    true minutes earlier, and creates its process row while it is still parked.
  - **Constraint:** the drain releases waiters **in its own loop** (take a release slot,
    complete the waiter's `released`, then await its run off the loop), never by handing each
    waiter a coroutine to race for a slot — arrival order within one drain is the point (rapid
    re-toggles overlap drains, and the order across drains is not guaranteed) — and a slot is
    held until the released start's run exists, which is what bounds the flood at the runners.
  - **Constraint:** the total, per-principal and wait bounds are `server.maintenance` config,
    not constants; a shed or expired start leaves no process row and names no input value.
- **`StartupReconciliation`** — runs once before traffic: interrupted runs to `UNKNOWN`, their
  questions expired, queued run messages of every ended run undelivered, purge times stamped,
  and dispatches and continuations left mid-fire settled
  (see [`services/persistence/CLAUDE.md`](app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md)).
- **`LockManager`** — the three `onCollision` strategies (`WAIT`,
  `SKIP_CURRENT`, `KILL_OLD_RUN_CURRENT`).
  - **Constraint:** `releaseLock` warns on unknown-lock and non-holder
    releases — don't silence that warning, it masks real bugs.
- **`ParallelRunsManager`** — per-agent slot limiter (`maxParallelRuns`,
  `WAIT` | `REJECT`); under `WAIT`, `maxQueuedRuns` bounds suspended waiters and
  a surplus run fails fast (the start still returns `200`; the run ends
  `FAILED`). See ADR-0029.
  - **Constraint:** `releaseSlot` takes the exact `Semaphore` returned by
    `acquireSlot`, and the waiter counter is captured, not re-looked-up — a
    concurrent `AgentsService.reload()` would otherwise lose the reference or
    misdirect the decrement.
- **`OtelTelemetryService`** — thin wrapper over OpenTelemetry. Exposes the
  `TelemetryService` interface — depend on that in DI.
- **`ArtifactService`** — streams files from the process artifacts directory;
  rejects `..` paths; tails files still being written by active processes.
- **`LogTailService`** — resolves and tails the stdout/stderr file for a
  `(processId, cmdIndex, stream)` until the process ends (`RunLiveness`, shared
  with `LlmEventStreamService`); refuses symlinked, hard-linked or non-regular
  files at every open (`NOFOLLOW_LINKS`).
  `GET /api/v1/stream/processes/{id}/logs`.
- **`LlmEventStreamService`** — per-subscriber tail of an LLM harness command's
  stdout, translated through the adapter's `translate` and redacted with the
  run's `SecretRedactor`; live and finished runs, cursor resume. Refuses linked
  or non-regular stdout files like `LogTailService`.
  `GET /api/v1/stream/processes/{id}/llm-events`. See ADR-0070, ADR-0052.
- **`QuestionsService`** — operator questions asked by running commands plus the
  per-process callback-token registry. Question state is **SQLite-backed via
  `QuestionsRepository`**; tokens, wait counts and the answer-wait deferreds
  stay in memory. See ADR-0019, ADR-0020.
- **`RunMessagesService`** — per-run inbox: operators send, the running command
  lists and takes. The run-end close goes through the `RunMessageExpiry` port, and
  `StartupReconciliation` sweeps leftovers on boot. ADR-0097.
- **`DispatchService`** — ask-and-dispatch: a question plus a target agent and
  parameters, fired (via `AgentRunStarter`) after the operator answers; PENDING
  dispatches cancel and TTL-sweep. **SQLite-backed via `DispatchRepository`**;
  dispatch-bound questions carry `dispatch_bound = 1`. See ADR-0021.
- **`ContinuationService`** — completion continuations: a target agent fired when
  a *watched* sub-process reaches a terminal state, carrying its result.
  **SQLite-backed via `ContinuationRepository`**; dispatch's claim-CAS / fire /
  sweep / boot-reconciliation model, with a distinct `EXPIRED` state; the fire
  itself (claim, resume payload, start, retry or fail) is `ContinuationFireService`. ADR-0023.
- **`QuestionsWorkspaceSetup`** — materializes the question tooling into each
  workspace before commands run (`Process.execute`): the `homer-ask` and `homer-inbox` helpers at
  `{projectRoot}/.homer/bin`, and a `.homer/mcp.json` when `server.mcp.enabled`.
  - **Constraint:** the callback token is read from the env at run time,
    never written to disk.
- **`ProcessEventBroadcaster`** — single in-process `MutableSharedFlow`
  (`DROP_OLDEST`, buffer 128) fanning `ProcessEvent`s out to SSE subscribers;
  single-node only. Consumers collect via `subscribe()`, not the raw flow:
  `DROP_OLDEST` makes `tryEmit` always return `true`, so the producer is blind
  to drops — a sequence id per event lets `subscribe()` spot the gaps.
- **`LangGraphInspectService`** — renders each `Command.LangGraph`'s topology
  (plus deprecated Mermaid, ADR-0073) via a host-local `homer_langgraph inspect
  --json` subprocess. Read-only; powers `GET /api/v1/agents/{name}/langgraph`,
  and takes one permit for the whole request (N graphs would otherwise queue N
  times). See ADR-0027.
- **`LangGraphRunStatusService`** — per-run LangGraph introspection: per-node
  status + topology (plus deprecated Mermaid, ADR-0073) from one
  `homer_langgraph state` subprocess, plus per-sub progress for fan-out nodes
  from continuations of **any** status. Powers
  `GET /api/v1/processes/{id}/langgraph`. See ADR-0044 (all three amendments).
  - **Constraint:** a shed `busy` result is **never cached** — caching it would
    serve `busy` to everyone for the whole TTL, turning a moment of load into a
    guaranteed outage.
  - **Constraint:** the "did I get a permit" flag is set *inside* the
    `withTimeoutOrNull` block, right after `acquire()` returns, and read
    afterwards — `withTimeoutOrNull` can return null even when the permit was
    taken (it cancels at the boundary), so trusting its return value alone leaks
    a permit permanently, and four leaks wedge the endpoint for good. Same rule
    in `LangGraphInspectService`. See ADR-0047 rules 4–5.
  - **Constraint:** the permit is taken **before** the second cache check, so a
    burst of polls for one thread collapses onto the runs already in flight
    instead of each queued caller spawning its own interpreter.
  - **Constraint:** the state cache stays a size-bounded LRU
    (`maxCachedStates`), not a TTL-only map — its key includes a thread id read
    from an agent-writable artifact, so key cardinality is untrusted.
  - **Constraint:** the 4 s cache TTL sits just under the console's 5 s poll, so
    one viewer reads fresh state per poll — retune the two together.
  - **Constraint:** `traversed` is re-filtered against the validated topology
    (and deduplicated) at the point of use; never pass the runtime's list
    through. See ADR-0075.
- **`Authorizer`** — grants-based authorization: the resolved config compiled
  into a principal → (isAdmin, grants) map, plus the owner stamped on
  `ProcessInfo`/`Dispatch`/`Continuation` rows and copied down the chain. See
  ADR-0037, ADR-0038 and ADR-0039.
  - **Constraint:** the gate is handler-level and explicit — each route handler
    calls `hasAction`/`anyGrant`/`readableAgents`/`canSee`/`canControl`/
    `canRetry`/`canDispatchHop` at its top. No path parsing, no implicit gate;
    a single-resource denial returns `404`, identical to not-found.

## Status precedence rules

Important invariant enforced by `ProcessExecutor`:

1. Once `ProcessManager` sets a process to `KILLED` (user kill, `KILL_OLD_RUN_CURRENT` lock
   collision), **no later write** — neither the happy-path `FINISHED` write nor any exception
   handler — may overwrite it. That includes the run's own start: the `CREATED` write in
   `ProcessExecutor` and the `WORKING` write in `Process` are CAS from `{CREATED}`, so a kill
   that lands before them wins (a refused `WORKING` CAS stops the run).
2. The happy path uses
   `ProcessManager.compareAndSetStatus(processId, {WORKING, CREATED}, FINISHED)`: if the CAS fails
   because a terminal status landed first, the executor reflects the actual final status in
   telemetry and logs a warning instead of forcing `FINISHED`.
3. A `CancellationException` after KILLED preserves KILLED and sets telemetry to KILLED.
4. `AnotherProcessStillActive` (from `SKIP_CURRENT` lock) or `MaxParallelRunsExceededException`
   (from `REJECT`) → FAILED (never ran).
5. Any other exception → FAILED.
6. **`UNKNOWN`-on-restart**: a process still `CREATED`/`WORKING` at boot is moved to `UNKNOWN` by
   `StartupReconciliation` (its coroutine is gone). `UNKNOWN` is terminal — in `ProcessRepository`'s
   terminal set, so such rows are evictable and never block — and distinct from
   `getProcessStatus` returning `UNKNOWN` for an *absent* id: a reconciled process has a real row
   with history. The run never reached `ProcessExecutor.schedulePurge`, so reconciliation stamps
   its purge times from the agent's current `workspace` (`PurgeSchedule`, defaults if the agent is
   gone) and `WorkspacePurgeService` sweeps `UNKNOWN` like the other terminal statuses (ADR-0048).

Rule 2's CAS is enforced atomically at the DB level by a `BEGIN IMMEDIATE` transaction, not the old
`Mutex`. Telemetry span status must always match the final `ProcessInfo` status — if you touch the
happy-path CAS or the exception branches, read the `ProcessExecutorTest` cases first.

## SQLite rules for every change that touches the database

Production runs at `maxProcesses`, up to 150 000 rows — a query that is fast on a test database
proves nothing.

1. **Name the index.** Every new or changed query on a table that grows with use (`processes`,
   `process_inputs`, `process_tags`, `cost_entries`, `questions`, `dispatches`, `continuations`)
   names the index or primary key that serves it, checked with `EXPLAIN QUERY PLAN` on a table
   seeded to 150 000 rows, with and without `sqlite_stat1` (statistics are gathered at startup and
   hourly; see "Planner statistics" in
   [`services/persistence/CLAUDE.md`](app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md)).
   A `SCAN` of such a table is never acceptable on a request, poll, stream, sweep or run-lifecycle
   path. A new index goes into `Tables.kt` plus a schema-version migration under the same name, its
   plan pinned in `DatabaseMigrationTest` and one new row in the schema-version table in
   [`services/persistence/CLAUDE.md`](app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md).
   The `query-plans` check (ADR-0082) plans every distinct statement production code runs during
   `:app:test` and reports each full scan of such a table, so a query on one needs a test that runs it.
2. **Know how often it runs.** Before adding a database call, state its trigger and rate: per
   request, per SSE event and subscriber, per poll tick, per step, per sweep. No query per row
   inside a loop; batch with `IN`, a join or `batchInsert`. A poll that only needs liveness should
   check in-memory state such as `ProcessJobRegistry` before it reads the database. The
   `query-repeats` check (ADR-0082) reports a statement production code runs three or more times in
   one transaction during `:app:test`.
3. **Keep the write lock short.** A `query { }` block does database work only: no HTTP call, file
   IO, sleep or large JSON decode inside it, and no coroutine `Mutex` held across it. One logical
   state change is one write transaction — do not spread one step's bookkeeping over several.
4. **Read only what you use.** On a hot path select the columns you need; never `selectAll()` a row
   with JSON columns to read its status. Keep large or write-once data out of rows that are updated
   often: an UPDATE that changes a row's size rewrites the whole row.
5. **Make writes safe to retry.** Exposed re-runs a failed transaction up to `maxAttempts` times,
   and a commit that throws counts as failed even when the data landed — so a write block must be
   idempotent, or run single-attempt. The writer handle
   ([`DatabaseService`](app/src/main/kotlin/homer/app/services/persistence/DatabaseService.kt)
   `query { }`) is single-attempt by construction, so its write block is not retried and need not be
   idempotent; the reader handle (`read { }`) keeps Exposed's default, safe because a read block is
   re-runnable. A `transaction(db.database)` outside `query { }` (e.g. `ServerMetaRepository`,
   startup-only) still runs on the writer handle and is single-attempt too; only a transaction on
   some other `Database` carries neither guarantee.
6. **Bound every growing table.** A table that gains rows per run, question or charge gets its
   retention path in the same change. A sweep selects due rows through an index and takes no write
   lock when nothing is due.

## Async execution boundary

Most `HomerException`s are thrown inside `ProcessExecutor.coroutineScope.launch { … try/catch … }`,
so they **never propagate back to the HTTP handler**: the response for async execution is always
`200 {processId}`, failure discovered by polling `/api/v1/status/{id}`. `StatusPages` (installed by
`configureStatusPages()`, see
[ADR-0002](../docs/adr/0002-unified-error-model-at-http-boundary.md)) therefore only maps exceptions
from the synchronous prelude (query-param parsing, process lookup). A new `HomerException` subclass
makes the sealed `when` in `homerExceptionStatus` (which `configureStatusPages` calls) non-exhaustive —
the build fails; add the mapping, don't add an `else` branch. A caller's request error goes into the
entry handing over to `requestErrorStatus`, mapped there unless it is a `400`; a transfer, capacity or
server-side one into the entry handing over to `transferCapacityOrServerStatus`, mapped there unless it
is a `500`. Those helpers' `else -> 400` and `else -> 500` are the only intended fallbacks: each only
ever sees the subclasses the top-level `when` hands it, and the top-level `when` itself stays exhaustive
with no `else`.

## System environment variables

The `HMR_*` variables every command receives are defined in `EnvConstants.kt` and documented for
agent authors in [`AGENT.md`](AGENT.md) § Workspace Environment Variables — add a new one to both in
the same commit. Implementation facts that doc does not carry:

- `HMR_PARENT_PROCESS_ID` and `HMR_ROOT_PROCESS_ID` (the flow's first run id) are **removed**, not
  set empty, at the root run, so an agent `env:` entry or an allowlisted host variable cannot pose
  as a parent or a flow root (ADR-0058).
- `HMR_AGENT_DIR` is injected by `AgentsService` into the loaded agent's `env`, so unlike the others
  it *is* available in workspace path / lock-key templates.

`@SYSENV:VAR_NAME` in `globalEnv`, bearer tokens and agent `env` references a host env var, resolved
fail-fast at startup (`SysEnvResolver`), and **declares the value secret**: `secretNamesFor` runs on
the *raw* map before resolution and the resulting name set rides on
`AppConfig.sysEnvBackedGlobalEnvNames` / `Agent.sysEnvBackedEnvNames` (both `@Transient`,
runtime-only) into `WorkspaceConfig.secretEnvNames`, so `SecretRedactor` masks those values in every
span. A literal `globalEnv` value is deliberately *not* masked — see SERVER.md's content-egress note.

That set holds **two kinds of name**, because `resolve` substitutes inside a string rather than
replacing the whole value: the map key, and every host variable the value referenced. For
`TOKEN: "@SYSENV:REPO_TOKEN"` the key alone is enough — the value *is* the credential; for
`HEADER: "Bearer @SYSENV:REPO_TOKEN"` it is not, since output holding the bare token would match no
needle. `SecretRedactor.from` resolves a non-env-key name against the host env
(`env[it] ?: hostEnv(it)`), turning the referenced host variable into a needle. Names travel, values
are resolved at redact time — don't "simplify" this.

## Logging

Streams, `HOMER_LOG_LEVEL` and `HOMER_LOG_DIR` are documented in [`SERVER.md`](SERVER.md) § Logging.
For backend work: the root logger writes to stdout (the console appender) and to the rolling
`app.log` file appender in `HOMER_LOG_DIR`; `entrypoint.sh` does not redirect stdout, so in
Docker/K8s it is the container log; access lines go to the `homer.access` logger with
`additivity=false`, so they land in `api.log` only, and `isNoisyAccessPath` in
`configureCallLogging` decides which paths are skipped; `agentdebug` excludes logback from its `:app`
dependency to avoid a double SLF4J binding; tests use
[`logback-test.xml`](app/src/test/resources/logback-test.xml) (console-only), so they never
create `app.log` or `api.log`.

## Adding a new command type

1. Add a subclass of the sealed `Command` class in
   [`models/Command.kt`](app/src/main/kotlin/homer/app/models/Command.kt) with the appropriate kaml tag
   (e.g. `@SerialName("myType")`).
2. Implement the executor in `executors/MyCmdExecutor.kt` satisfying the `CommandExecutor` contract,
   and register it in `CommandExecutorFactory`; add a test at `executors/MyCmdExecutorTest.kt`.
3. Update [`schemas/homer-agent.schema.json`](schemas/homer-agent.schema.json) with the new variant
   under the `commands[*]` `oneOf` (root §10); `SchemaValidationTest` backstops the sync.
4. Update [`AGENT.md`](AGENT.md) — per root §10 **all** of: a `### Command.MyCmd` section with a
   complete YAML example; the matching JSON form in a `<details>` block; the field table (type,
   required, default, description); and any "Complete Agent Examples" block it appears in.
   `AgentMdExamplesTest` backstops the structural part — every complete-agent YAML/JSON block is
   parsed and validated against the schema, so a missing kaml tag or a wrong field type fails the
   build.

## Adding a new field to `Agent` or a `Command` subclass

1. Add the field to the relevant model in
   [`models/Command.kt`](app/src/main/kotlin/homer/app/models/Command.kt) — the LLM commands' option
   types (`ClaudeCmdOptions`, `CodexCmdOptions`, `LlmRetry`, …) are in `models/LlmCommandOptions.kt`,
   `Param` in `models/Param.kt` — with the same default it should serialize to (kotlinx.serialization
   omits defaults unless `encodeDefaults = true`).
2. Update [`schemas/homer-agent.schema.json`](schemas/homer-agent.schema.json) under the right
   `$defs` / variant, and [`AGENT.md`](AGENT.md) per root §10 — the field table row plus an example
   using the field.
3. If user-visible, add a [`../CHANGELOG.md`](../CHANGELOG.md) entry (root §8).
4. Add a unit/integration test exercising the new field's behavior.

## Adding a new HTTP endpoint

1. Add the route in the matching per-resource `routes/` file (e.g. `ProcessRoutes.kt` for a secured
   `/api/v1` process endpoint; a new resource gets its own `Route.xxxRoutes(deps)` file, mounted from
   `apiV1Routes` in `SecuredRoutes.kt`), in the handleXxx pattern used by existing endpoints.
2. Throw a `ValidationException` (or a sibling such as `QueryParamValidationException`) for
   validation failures — don't `call.respond(400, ...)` inline; `configureStatusPages` maps it.
3. Call the `Authorizer` explicitly at the top of the handler (no implicit gate) and return `404`
   rather than `403` for a single-resource denial. If the handler can multiply a host resource — a
   subprocess, an unbounded read, a growing collection — bound it here, and bound the wait it
   creates. Root §12.
4. Add a `Test` in `ApplicationTest.kt` exercising success + a failure case, **including the denial
   path** for the gate added in step 3, then document the endpoint in [`README.md`](README.md) and
   add a [`../CHANGELOG.md`](../CHANGELOG.md) entry (root §8).

## Build commands

```bash
# From repo root, pinning JDK 25 per root §5
export JAVA_HOME=<path to a JDK 25>  # macOS: $(/usr/libexec/java_home -v 25)

app/gradlew -p app :app:test                       # tests only
app/gradlew -p app ktlintFormat :app:test          # pre-commit
app/gradlew -p app :app:shadowJar                  # fat jar → app/build/libs/homer-<version>-all.jar
app/gradlew -p app :agentdebug:shadowJar           # CLI debugger
app/gradlew -p app :homer-runner:shadowJar         # external runner binary
app/gradlew -p app :app:generateThirdPartyNotices  # regenerate NOTICE + THIRD-PARTY-LICENSES.md
app/gradlew -p app/buildSrc check                  # buildSrc's own ktlint + tests (not part of `-p app check`)
app/gradlew -p app detektMain -Dorg.gradle.jvmargs="-XX:MaxMetaspaceSize=1g"  # detekt (required in the gate), not in `check` (CI job homer-app-detekt); report in */build/reports/detekt/
app/gradlew -p app :app:crapReport                 # CRAP score per function → app/app/build/reports/crap/ (ADR-0081)
app/gradlew -p app :app:queryPlanReport            # query plan of the SQL :app:test ran → app/app/build/reports/query-plans/ (ADR-0082)
app/gradlew -p app :architecture:test              # Konsist architecture rules, required (ADR-0082)
app/gradlew -p app :architecture:architectureReportOnly  # pending (report-only) rules, none at present; never fails
app/gradlew -p app :app:pitest                     # mutation-testing pilot → app/app/build/reports/pitest/ (ADR-0082)
```

A Kotlin compiler warning fails the build (`allWarningsAsErrors` in the convention plugin, ADR-0082): opt in or
`@Suppress` at the narrowest scope; only `:app`'s tests are opted in to `ExperimentalCoroutinesApi` module-wide.

A cold or `--rerun-tasks` build that OOMs in codegen (`OutOfMemoryError: GC overhead limit exceeded`)
means the Kotlin daemon heap: check the live daemon's `-Xmx` (`ps | grep KotlinCompileDaemon`) before
blaming the test it names — a daemon already running with a **larger** heap gets reused
([`gradle.properties`](gradle.properties), `kotlin.daemon.jvmargs`). CI resolves
through the in-cluster Artifactory proxy and its remote build cache, on only when `ARTIFACTORY_URL`
(a bare `https://` origin; a path or credentials in it fail the build), `ARTIFACTORY_USER` and `ARTIFACTORY_TOKEN`
are set; inside a Docker build the three are BuildKit secrets, never an
`ARG`/`ENV` (root §12, rule 7), and the e2e runner keeps them as run-time environment —
`ArtifactorySecretExposureTest` guards both halves; the cache is pushed from protected refs only
([`../DOCKER.md`](../DOCKER.md) § Artifactory build secrets).

Two sibling modules: [`agentdebug/`](agentdebug/) is a Clikt CLI running agents locally without the
Ktor server via thin stubs ([`agentdebug/README.md`](agentdebug/README.md)) — `:app:test` does not
compile it, so build `:agentdebug:shadowJar` after changing an `:app` signature it uses; and
[`homer-runner/`](homer-runner/) is the reference pull-mode runner instance (ADR-0066), wrapped by
[`homer-runner.sh`](homer-runner.sh) with options in [`SERVER.md`](SERVER.md) § *External runners
(pull mode)*. It reuses the protocol DTOs, `TarStreams` and `ProcessGroupSupport` from `:app`.

## Architecture tests

[`architecture/`](architecture/) is a test-only module: Konsist rules over the `:app`, `agentdebug` and
`homer-runner` sources, one `@Test` per rule, each failure listing `VIOLATION <repo path>:<line> <text>`.
It runs in `check` and is a required check (ADR-0082): a violated rule fails the build. Add a rule
only for a convention a doc already states, and name that doc here. Rules read source text and imports,
not bytecode: ArchUnit was rejected because compiled Kotlin inlines `const val`s and inline functions,
hiding or inventing dependency edges, and a finding should point at the import line that adds one. The
route rule likewise reads routes with a small Kotlin text lexer and walk (`KotlinText`, `SecuredRoutes`)
instead of a PSI parser; its safety net is a fail-closed guard. Any handler-shaped call (`get`, `post`, …,
`connect`, `route`, `method`, `webSocket`, `sse`, `static*`) inside a `Route`/`Routing` extension, block
or expression body, outside the handler bodies, that the walk did not attribute is a finding (a handler
given as `::fn` is followed into `fn` when exactly one function in `routes/` has that name, else
unattributed; all overloads of a builder are walked). So is a call of one of those names on an explicit
receiver (`routing.get("/x", ::h)`, `x.staticResources(…)`), with or without a lambda, anywhere in
`routes/` except inside handler bodies and inside route-free top-level functions — ones whose signature
and body name no `Route`, `Routing` or `Application` type, no import alias or typealias of one, and no
`route`, `routing` or `application` identifier.

A new rule whose code still has findings is tagged `@Tag(REPORT_ONLY)` and is *pending*: `:architecture:test`
skips it, and `:architecture:architectureReportOnly` (also in `check`) runs it without ever failing; the
warn-mode check `architecture-backend-pending` reports its findings. Promote a rule by deleting its tag
once it reports 0, in the commit that fixes its last finding. The lane is empty once the last rule is
promoted and then writes no report, which the gate reads as missing: that commit also removes the check
from `tools/quality/checks.json` and its line from the `quality-gate` job. The lane has been empty since
the cycle rule became required (ADR-0083 step 3); a rule tagged again brings both back.

| Rule | Source |
| --- | --- |
| Test classes mirror the production package | root §6, "Directory layout" |
| Every service has a `*Test` in its package | root §6, "Layered architecture" (Services row, and the types moved out of it) |
| Every `executors/` `*Executor` has a `*Test` | root §6 |
| `CommandExecutor`s are `executors/<Name>Executor.kt` | "Adding a new command type" |
| No inline `respond(HttpStatusCode.BadRequest…)` | "Adding a new HTTP endpoint" |
| `Route.*` builders live in `routes/` | "Adding a new HTTP endpoint", "Directory layout" |
| Services, executors, runners, infra, models, config, exceptions don't import `routes`, `auth` or `io.ktor.server` | "Layered architecture" |
| `*Response` DTOs live in `models/` (`runners/external/` protocol excepted) | "Directory layout" |
| Declared types use `TelemetryService`, not `OtelTelemetryService` (outside `di/`) | "Key service responsibilities" |
| Tests mock with MockK, not Mockito | root §6 |
| Every handler under `apiV1Routes` makes a denying call — `authorizer.<method>(`, `deniedUnlessAdmin(`, or a `routes/` function every overload of which makes one; a bare `authorizer` argument does not count. A builder mounted under two prefixes is checked under both, an expression-body builder like a block one; a `::fn` handler is checked through `fn` when that name is unambiguous; a route shape the walk cannot read, or a registration-named call on an explicit receiver outside handlers and route-free helpers, is a finding; `GET /heartbeat` and `GET /schemas/agent` are open to any authenticated caller ([`README.md`](README.md)) | root §12 rule 3, "Adding a new HTTP endpoint" |
| Main code: a source file ≤ 600 lines, a top-level class or object ≤ 400 (KDoc and annotations excluded), a function body ≤ 60 (from the line opening it) — one rule each | root §2 |
| Each layer imports only itself, the layers below it and the kernel; the kernel only itself | "Layered architecture", "Directory layout" |
| No import cycle between the top-level packages `homer.app.(*)` (the root package is no slice) | "Layered architecture" |

The layering rule reads a file's layer from the diagram row naming a type the file declares or implements
(`Process` counts as Execution, after "Status precedence rules"), else from its package: the root package,
`routes/`, `auth/` and `di/` are HTTP; `services/` (with `persistence/`) is Services; `infra/` is Infra,
the support code the Infra row's own types are; `executors/` is Executors; `runners/queue/` is
Execution, the rest of `runners/` Infra; `models/`, `config/`, `exceptions/` and
`EnvConstants.kt` (the `HMR_*` names, "System environment variables") are the kernel. The cycle rule
reports, per cycle, the edges cheapest to cut: a kernel package's import of another package first, then
the edge with the fewest imports. Both read imports, not fully qualified names in code. The size limits
complement the CRAP check (ADR-0081), which misses long code that branches little; they were set where
the outliers showed (9 files, 16 classes, 67 functions at introduction) and became required once those
were split.

## Console DTO contract

`DtoContractTest` (in `:app:test`) writes the serial shape of every DTO the console mirrors in
`console/types/homer.ts` to `app/app/build/reports/dto-contract/backend-dtos.json`; the `dto-contract`
check compares it with the TS mirror (ADR-0082, [`console/CLAUDE.md`](../console/CLAUDE.md) § Types &
backend coupling). The roots are listed in `DtoContract.kt` next to it, and everything reachable from
them is exported. A new response DTO the console reads goes into that list, or the check reports
its TS mirror as having no backend DTO. A DTO's simple name is its key: two exported classes with one
name fail the test.

## Gotchas

- **A test that reads a repo file outside `:app` must declare it in `guardedRepoFiles`** in
  `app/app/build.gradle.kts` — otherwise the build cache replays a pass when only that file changes.
- **A new Gradle module needs an empty-dir stub in both Dockerfiles** — `app/Dockerfile` and
  `app/e2e/Dockerfile` copy only the modules they build, and Gradle refuses an included project
  whose directory is missing, so `settings.gradle.kts` and their `mkdir -p` lists move together.
- **ContentNegotiation is routing-scoped, not application-level** — installed at the routing root so
  `/mcp` can override it with `McpJson` (ADR-0018); installing it at application level again throws
  `DuplicatePluginException` at startup. StatusPages therefore responds on the application-level call
  where routing-scoped plugins don't apply, so its `ErrorResponse` is serialized explicitly via
  `apiJson` (`respondText`) — don't "simplify" it back to `call.respond(obj)`.
- **`withTimeout` does not bound blocking IO** — cancellation is only seen at a suspension point, and
  a thread blocked in a JVM read or write never reaches one, so a stalled peer outlives the deadline.
  Close the resource the thread is blocked on from a watchdog instead (`SftpTransferDeadline` behind
  `WorkspaceSync`, ADR-0064; `withTransferTimeout` in `RunnerProtocolRoutes`, which cancels the call's
  byte channel). A test of such a deadline must keep the peer stalled until after its assertion.
- **A catch-all in `suspend` code must not swallow cancellation** — catch `CancellationException`
  first and rethrow it (or call `ensureActive()` in the catch), and run cleanup that has to finish
  under `withContext(NonCancellable)`. detekt's `SuspendFunSwallowedCancellation` (ADR-0082) flags a
  violation; a deliberate exception carries `@Suppress` with a one-line reason.
- **Status precedence** — don't paper over telemetry / process-status mismatches by touching both;
  find the exception path that is setting them inconsistently.
- **OpenTelemetry** async export — test assertions on exported spans need to force flush the tracer
  provider.
- **`reloadAgents()` endpoint** — confirm it is still exposed before exposing new UI relying on it
  (currently used by console `useReloadAgents` mutation).
