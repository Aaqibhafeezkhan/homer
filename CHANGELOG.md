# Changelog

## [Unreleased]

### Console

- **Changed: a more compact process list** — Each run shows its total duration and a dot per executed command, status is an icon, and the Status Time and Purge columns are gone.

### Homer

- **Added: a `limits` example agent** — Demonstrates `maxParallelRuns`, `onMaxParallelRuns`, `maxQueuedRuns` and `cooldown` on a slow script.
- **Added: `!<opencode>` command type** — Runs the opencode CLI as a third LLM harness, with the same retry, escalation, cost accounting and conversation view. See [ADR-0070](docs/adr/0070-llm-harness-adapter-boundary.md).
- **Changed: the runner image pins the Codex and opencode CLI versions** — A build installs a chosen release of each CLI instead of whatever npm published that day; the `CODEX_VERSION` and `OPENCODE_VERSION` build arguments override the pins.

## [1.38.0] - 2026-10-28

### Homer

- **Added: `failureReason` on the process status response** — A run that ended `FAILED` before any command started (a claim timeout, a `SKIP_CURRENT` lock skip, a parallel-run cap or a full queue) carries a stored sentence saying which.
- **Added: run-message API and `server.messages` limits** — `POST` and `GET /api/v1/status/{processId}/messages` send and list a run's inbox, `POST /api/v1/callback/messages/take` hands the run its queued messages (as plain text, Claude-hook JSON or the `check_messages` MCP tool), `message.queued`/`delivered`/`undelivered` stream on `/stream/processes`, and the new `server.messages` config bounds message size and count. See [ADR-0097](docs/adr/0097-per-run-inbox.md).
- **Added: an optional `group` on the agent definition** — Labels an agent for the console; `GET /api/v1/agents` and the process API return it, read from the agent's current definition.
- **Fixed: code names split mid-word in documentation-site tables** — A code or type name in a table cell stays on one line, and a table too wide for the page scrolls sideways instead.

### Console

- **Added: why a run failed before it started** — The process list and the run's own page show the stored reason beside the `FAILED` status.
- **Added: per-run inbox** — Operators can send messages to a running run (bounded by `server.messages`), which reads them through the new `homer-inbox` helper, Claude hooks or the `check_messages` MCP tool; undelivered messages are marked when the run ends. See [ADR-0097](docs/adr/0097-per-run-inbox.md).
- **Added: message box on a running run** — Send a message to a running run from its page and see whether it was delivered.
- **Added: agent group tabs** — The Agents page shows an All tab plus one tab per agent group, and a run's agent group is shown on the process list and detail.

## [1.37.1] - 2026-10-27

### Homer

- **Fixed: an external runner dropped the end of a step's output** — A step on a pull runner now finishes sending its output before reporting its exit code, or gives up on a stalled drain with one logged warning that leaves the tail unreported; output a reported count shows as still missing fails the step instead of passing as truncated.
- **Fixed: runner-protocol bodies refused on unknown fields** — A body carrying a field this Homer does not know is decoded for what it knows instead of answered with a 400, so mixed-version deployments no longer lose an op result to a newer peer's extra field.
- **Fixed: homer-runner kept claiming runs it could not start** — Before registering and before each claim the runner checks that it can start a process and write under `--root`; when it cannot, it stops claiming, deregisters, lets in-flight runs finish and exits 3 for its supervisor to restart. See [ADR-0096](docs/adr/0096-unhealthy-runners-stop-claiming-and-idle-consumers-take-turns.md).
- **Fixed: one fast-failing runner instance took most runs** — A new run goes to the idle matching runner that claimed least recently, instead of to whichever waiting poll wins a race, so an instance that fails every run at once no longer takes every run. See [ADR-0096](docs/adr/0096-unhealthy-runners-stop-claiming-and-idle-consumers-take-turns.md).
- **Fixed: `agentdebug validate` exit status** — The command exits 1 when the agent is not found or schema validation fails, and 0 when it passes.
- **Fixed: moving the compose ports broke the console's API calls** — The example configs take the console origin from `@SYSENV:FRONTEND_PORT` and the console's API URL follows the backend's published `BACKEND_PORT`, so changing either port in `.env` is the only step. See [ADR-0093](docs/adr/0093-the-default-compose-stack-runs-published-images.md).
- **Added: a `tagged-run` example agent** — A bundled example shows run tags in use: subject tags resolved from the agent's inputs and a runtime tag declared mid-run with `homer-tag`.

### Console

- **Fixed: console sign-in on a first visit** — A reader who opens a console page without a session gets a neutral "Sign in to continue" prompt instead of "session expired", opens on the Token form when the deployment offers bearer sign-in, and returns to the page they asked for after signing in; a bearer session that really expired — even one already dead when the page loads — still says so, and a cookie session that expires mid-use does too.
- **Fixed: the Last Command column named a skipped command** — A run's last command is now the last one that actually ran, not a trailing step skipped by its `when:` condition; a run whose every command was skipped shows an empty cell.

## [1.37.0] - 2026-10-25

### Homer

- **Fixed: agentdebug param types** — `validate` and `show-commands` now print each param type as written in agent files (`string`, `integer`) instead of the internal enum name.
- **Added: `@SYSENV:` placeholders in `server.corsAllowedOrigins`** — An entry such as `http://localhost:@SYSENV:FRONTEND_PORT` is resolved at startup.
- **BREAKING: `server.corsAllowedOrigins` entries must be bare origins** — An entry with a path, query, fragment, credentials or port 0, previously accepted with that part ignored, now fails startup; use `http(s)://host[:port]` with a port of 1–65535.
- **Fixed: changing `FRONTEND_PORT` or `BACKEND_PORT` broke the compose console** — The example configs allow the console origin on `FRONTEND_PORT`, and the console's API URL follows `BACKEND_PORT`, so a port change in `.env` is the only step.
- **Fixed: bundled example agents use typed inputs** — The example agents no longer log deprecation warnings at startup; they use `inputs:` and typed params, and the `hello` example's `project_id` now accepts only letters, digits, `_` and `-`.
- **Added: slim `homer-app:<version>-slim` image** — A server image without the agent CLIs (Claude Code, Codex, Node.js, git), for deployments whose agents run on pull runners. See [ADR-0094](docs/adr/0094-slim-server-image-for-pull-runner-deployments.md).
- **Changed: a smaller `homer-app` image** — The image no longer ships npm's download cache or SQLite native libraries for other platforms, so the first pull is about 300 MB (a third) smaller.
- **Added: `server.localRunner.enabled` config** — `false` registers no local runner, so a run routed to `local` fails at once instead of executing in the server's container. See [ADR-0095](docs/adr/0095-local-runner-can-be-turned-off.md).
- **Fixed: quick-start console sign-in** — The Docker Compose quick start now enables console sign-in with the admin token, instead of leaving the console unauthenticated against a bearer-only backend. See [ADR-0011](docs/adr/0011-cookie-based-console-auth.md).
- **Added: linux/arm64 release images** — Every release image is published for `linux/amd64` and `linux/arm64`, so it runs natively on arm64 hosts such as Apple Silicon. See [ADR-0086](docs/adr/0086-sign-and-attest-release-images.md).

### Console

- **Fixed: an unreachable or rejecting API no longer leaves the console loading forever** — The process detail, process list and questions pages say whether the API rejected the request, cannot be reached (down or blocked by CORS), or has no such process.

## [1.36.0] - 2026-10-24

### Homer

- **Changed: `docker compose up` pulls the published release images** — The stack no longer builds locally; `HOMER_VERSION` picks the release tag, and building from source moves to the `docker-compose.build.yml` overlay. See [ADR-0093](docs/adr/0093-the-default-compose-stack-runs-published-images.md).
- **Added: a credentials-free `quickstart` example agent** — The Quick start now runs a script-only agent that asks a question in the console and finishes on the answer, so a first run needs no LLM credentials.
- **Added: admin maintenance mode** — An admin-only `GET /api/v1/maintenance` (read) and `POST /api/v1/maintenance` (toggle) queue new agent starts and skip cron fires until the mode is turned off, while runs already in progress finish; the queue is bounded (new `server.maintenance` config keys) and the mode survives a restart. See [ADR-0092](docs/adr/0092-maintenance-mode-queues-starts-and-skips-cron.md).

### Console

- **Security: the console's bundled DOMPurify and Next.js move past critical advisories** — A DOMPurify sanitiser bypass and an unauthenticated remote-code-execution path in Next.js image optimization no longer apply to the shipped bundle.
- **Added: a maintenance banner** — A red alert bar across every dashboard page while the backend's maintenance mode is on, so a run that seems to do nothing has an explanation on screen.
- **Security: `next` 16.4.2** — The console's shipped dependency graph leaves the Next.js 16.0.0–16.4.0 advisory range, so the `npm audit` gate passes again.

## [1.35.0] - 2026-10-20

### Homer

- **Security: the feature atlas builds its section links as DOM nodes** — A section heading's text is no longer inserted as HTML into the navigation rail (CWE-79), and the documentation site extracts the atlas's script whatever the tag's case.
- **Changed: the project moved to GitHub** — The plugin marketplace, the published images (now on GHCR), the feature atlas and private vulnerability reports live at `github.com/livesport/homer`. Release images are signed with cosign and carry SLSA provenance and a CycloneDX SBOM. See [ADR-0086](docs/adr/0086-sign-and-attest-release-images.md).
- **Security: regex transformers are evaluated under a time bound** — A catastrophic or uncompilable pattern now aborts and the value is omitted instead of pinning a CPU or failing the run; after one match is shed, later `regex` transformers of the same `queryParams` or `resultEnvParam` list are omitted without being evaluated.
- **Changed: an LLM step no longer waits for its session-spend lookup** — With `llmPrice.sessionSpend` configured, a finished `!<claude>` or `!<codex>` step is recorded at once on the CLI figure (`costSource: none` for Codex, which has none), marked `fallbackReason: session figure pending`, and the next step starts; the lookup settles in the background, charging the run and replacing the step's accounting when it answers, through a bounded hand-off (at most eight waiting at once, 45 s each) that sheds past a bound and leaves the trace on the pending figure. Up to 35 s less per LLM step. See [ADR-0089](docs/adr/0089-settle-llm-cost-in-the-background.md).
- **Fixed: the backend image build could hang on glab's update check** — The image's `glab` no longer checks its upstream for a newer release, at build time or when agents call it, so a build host or runner without that route no longer stalls.
- **Changed: fifteen more case studies on the documentation site** — Durable LangGraph pipelines, chat intake and operator questions, cross-agent locks, runner capacity, macOS build hosts, agent linting and cost through an LLM gateway. See [ADR-0087](docs/adr/0087-versioned-documentation-site.md).
- **Changed: `allowedTools` documented as pre-approval, not containment** — The agent format reference now says it restricts nothing once permissions are skipped; `disallowedTools` or `tools` is what contains a step.

## [1.34.1] - 2026-10-16

### Homer

- **Changed: the documentation site's feature atlas, tables and case studies** — The feature atlas is now a page of the site, with its navigation, search box, light and dark themes and fonts. Table columns of prose keep a readable width instead of being squeezed beside code names. A new *Case studies* section shows how Homer is run in practice: runs that hold no credentials, runner pools on Kubernetes, and agent changes without downtime. See [ADR-0087](docs/adr/0087-versioned-documentation-site.md).
- **Security: the documentation site build cannot read or write outside its trees** — An image path with a backslash, a published page, ADR, image or asset that is a symlink or not a regular file, and `site/pages/` as a symlink or one in it now fail the build instead of copying a file from outside the repository into the site or writing outside it. Repository images are served by the site itself. See [ADR-0087](docs/adr/0087-versioned-documentation-site.md).
- **Security: Netty, Apache HttpComponents and kotlin-reflect updated past high-severity advisories** — The backend now ships Netty 4.2.18 (from 4.2.13, under the HTTP server), httpclient5 5.6.4 and httpcore5 5.4.4 (TLS hostname verification and HTTP/2 resource exhaustion fixes, via the AWS SDK), and kotlin-reflect at the project's Kotlin 2.4.21.
- **Fixed: the bundled `langgraph_demo` example agent failed on import** — It now ships its own approval-flow graph instead of naming a module that does not exist.

## [1.34.0] - 2026-10-15

### Homer

- **Fixed: a Kubernetes command could hang its run after exiting** — When a pod child kept the command's output stream open, the pod never drained its stdin, or an output pump failed, the run waited forever or until its timeout; those waits are now bounded.
- **Fixed: a Kubernetes command cut off by the output cap or its timeout could lose the `[HOMER]` marker** — Stderr the pod still flushed after the kill overwrote the marker, so the run's stderr no longer said why it stopped.
- **BREAKING: `GET /api/v1/questions` is paged** — It takes `limit` (default 100, at most 1000) and `offset` and returns `total`, `offset` and `limit` next to `questions`; a caller that wants every question pages through them.
- **Fixed: `homer-runner` drain could skip deregistering** — A transport failure on the deregister call at shutdown is retried once, so the instance no longer lingers in Homer until its heartbeat expires.
- **Fixed: a killed run left its workspace behind** — With `cleanAfterFinish`, a killed run now deletes `workdir/` and `.homer/` like a finished one instead of leaving them for the purge sweep.
- **Security: workspace cleanup is time-bounded** — A run's `cleanAfterFinish` delete stops after two minutes and leaves the rest to the `purgeAfter` sweep, so a huge workspace can no longer hold the agent's lock and run slot. See [ADR-0048](docs/adr/0048-split-record-retention-from-workspace-purge.md).
- **Fixed: a run killed while waiting for a `WAIT` lock could wedge the lock** — The dead waiter could be handed the lock on release, blocking every later run on that key until a restart.
- **Fixed: a run killed during an LLM step's telemetry export kept going** — The killed step is no longer recorded as succeeded with the next step started after it.
- **Fixed: a disconnect or kill during a transfer past its deadline was reported as a transfer timeout** — Runner and SSH workspace transfers now end as a cancellation when their caller went away.
- **Fixed: shutdown logged a failure per pending cron fire and purge row** — The cron scheduler, the workspace purge and the dispatch and continuation sweeps now stop at once.
- **Changed: three log categories follow their classes' new packages** — `DefaultLockManager` and `ProcessExecutor` now log as `homer.app.executors.*` and `AgentTracing` as `homer.app.infra.AgentTracing`, instead of under `homer.app.services`.

### Console

- **Changed: the question lists show up to 100 questions** — The Questions inbox and a process's question panel list the oldest 100, with a note saying how many more are waiting; the sidebar badge counts all of them.
- **Fixed: Schedules Run dialog misdescribed lock and parallel-run refusals** — It said a lock, parallel-run or queued-runs refusal starts no run; such a fire starts a run that ends `FAILED`.

### Build / CI

- **Added: a versioned documentation site** — The repository's Markdown is published as one searchable site, with the atlas at `/atlas/` and the schemas at `/schemas/`. See [ADR-0087](docs/adr/0087-versioned-documentation-site.md).

## [1.33.0] - 2026-10-12

### Build / CI

- **Added: CRAP score report and gate** — Backend and console report each function's CRAP score (complexity × missing coverage) to the merge request's Code Quality widget; the `crap-gate` job warns on scores above 30 without blocking yet. See [ADR-0081](docs/adr/0081-crap-score-gate.md).

## [1.32.1] - 2026-10-10

### Homer

- **Security: the LangGraph `state` read refuses a linked checkpoint store** — A symlink, hard link or special file planted at the agent-writable `checkpoints.sqlite` now reads as "no checkpoint" instead of being opened by the server-host subprocess.
- **Security: agentdebug masks `@SYSENV:`-backed `globalEnv` values** — Credentials pulled from the host environment are now redacted in `--dry-run` output, `[WHEN]`/`[ESCALATE]` lines and exported spans, matching the server.
- **Fixed: a file write, delete or agent create racing a permission change returns 403** — A permission change between the writability probe and the filesystem call is mapped to `403` instead of an unhandled `500`, and a denied create takes the empty directory it just built back. See [ADR-0004](docs/adr/0004-agent-file-editing-api.md).

### Build / CI

- **Fixed: merge requests show the pipeline that runs the tests** — A branch with an open merge request now gets one merge-request pipeline carrying the test and E2E jobs, instead of a branch pipeline plus a merge-request pipeline that read "skipped".

## [1.32.0] - 2026-10-09

### Homer

- **Security: LangGraph inspect/state subprocess pipes are closed on every exit path** — The introspection runner closes the child's stdout, stderr and stdin once its outcome is decided instead of relying on the JVM's process-completion handler.
- **Security: LangGraph inspect/state output is capped while the subprocess runs** — The introspection runners bound the subprocess output in memory and kill it at the existing 2 MiB stdout cap instead of writing it uncapped to temp files. See [ADR-0073](docs/adr/0073-langgraph-topology-is-structured-data.md).
- **Security: a cancelled graph introspection call reaps its subprocess** — A caller that disconnects or shuts down while the introspection interpreter is starting now has it and its whole process group killed instead of left running until it exits on its own.

### Console

- **Added: an operator can replace the built-in themes with its own light/dark pair** — `CONSOLE_THEME_MODES` names one `CONSOLE_THEMES` entry as the light theme and one as the dark theme; the switcher then offers only those entries plus `System`, which follows the OS colour scheme. See [ADR-0069](docs/adr/0069-console-themes-are-runtime-loaded-stylesheets.md).

## [1.31.0] - 2026-10-08

### Homer

- **Added: `POST /api/v1/agents/{name}/cron-run`** — Fires an agent immediately with cron semantics (source `cron`, owner `system:cron`, no inputs), admin-only, behind the same `404` denial as an unknown agent.

### Console

- **Added: Run button on the Schedules screen** — An admin can fire a scheduled agent immediately through the cron path from `/schedules`, with a confirmation step and no parameter dialog.
- **Added: Collapsible sidebar with a pending-continuations badge** — A toggle at the bottom of the sidebar collapses it to icons only and the choice is remembered across visits; the Questions and Continuations badges stay visible on the icon, and Continuations gains a badge counting pending continuations.

## [1.30.0] - 2026-10-07

### Homer

- **Added: per-attempt pricing keys for `llmPrice.sessionSpend`** — New optional request-window keys price each resumed-session attempt from only its own requests and poll on those rows' count. See [ADR-0061](docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md).
- **Fixed: a `--continue`-resumed LLM session is charged per attempt, not the session total** — Each attempt is priced from only the requests inside its own window and its completeness poll counts those rows, so a session reused across runs no longer charges every run the running total of all earlier ones. See [ADR-0061](docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md).
- **Security: an unreadable request-window timestamp refuses the attempt's charge** — A spend-endpoint row whose start time cannot be read no longer vanishes silently from the attempt priced from that window; the whole window is refused and the run falls back to the CLI figure with a reason, so a charge behind `costLimits` is never settled Complete from an under-count. See [ADR-0061](docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md).
- **Security: a tar entry whose mode cannot be normalized is refused** — A runner transfer no longer continues with an extracted file keeping its pre-existing mode when the normalized `0755`/`0644` cannot be applied, whichever way the chmod fails; the target is removed and the transfer fails. A target without POSIX permissions still extracts bytes-only. See [ADR-0047](docs/adr/0047-security-by-design-and-threat-model.md).
- **Security: a tar entry written through a dangling symlink no longer leaves its destination behind** — When an extracted entry's mode cannot be normalized, the fail-closed cleanup now deletes the file the write actually created at the link's destination, not only the link name, so no attacker-supplied, unnormalized content survives.
- **Added: `escalate` on LLM steps** — A `!<claude>` or `!<codex>` step can rerun on stronger models when a `when` predicate judges the previous model's outcome bad; steps without it run exactly as before. See [ADR-0079](docs/adr/0079-llm-steps-escalate-to-another-model-on-a-bad-outcome.md).
- **Security: runs can no longer read the runner's domain token from its process** — Each external runner instance locks its own process against `/proc` inspection and ptrace at startup, and refuses to start when it cannot. See [ADR-0078](docs/adr/0078-runner-jvm-is-non-dumpable.md).
- **Fixed: a killed or turn-limited LLM step is no longer free in Langfuse** — An attempt with no successful `result` is priced on its last one, and an attempt killed before writing any gets one generation from its accounting, both marked as an error, so Langfuse's cost matches the ledger for every attempt. See [ADR-0074](docs/adr/0074-cost-of-an-attempt-that-ends-more-than-one-turn.md).
- **Fixed: a killed or failed LLM step keeps its tool and message spans in Langfuse** — The stream of a non-zero exit, a timeout or output-cap kill and a cancelled run is read leniently and within fixed bounds, so a truncated last line no longer drops every span of the step.
- **Security: end-of-run cleanup no longer deletes a workspace a live run still uses** — When runs share a fixed `workspace.path`, a finishing run's `cleanAfterFinish` cleanup now skips the `workdir/` and `.homer/` deletes while another run on the same path is still CREATED or WORKING, instead of removing helper binaries and `mcp.json` mid-execution. See [ADR-0048](docs/adr/0048-split-record-retention-from-workspace-purge.md).

### Console

- **Security: `sharp` 0.35.5** — Replaces 0.35.4, whose bundled librsvg carries a high-severity vulnerability flagged by `npm audit`.

## [1.29.0] - 2026-10-06

### Homer

- **Security: runs can no longer read the runner's domain token from its process** — Each external runner instance locks its own process against `/proc` inspection and ptrace at startup, and refuses to start when it cannot. See [ADR-0078](docs/adr/0078-runner-jvm-is-non-dumpable.md).
- **Security: the runner alias is admin-only on the status and health APIs** — `GET /api/v1/status/{id}` and `/status/all` return the generic backend `runner.type` to non-admin viewers, with `runner.alias` reserved for an `admin`; `GET /api/v1/health` withholds the configured runners' aliases and the sweep snapshot's `runnerAlias` from non-admins the same way (the built-in `local` row keeps its fixed alias). See [ADR-0077](docs/adr/0077-runner-alias-is-admin-only-on-status.md).
- **Fixed: a LangGraph node that recovered from a failure shows as done** — A node that failed and then succeeded on a resume was reported `unreached` in the run's workflow status, with its outgoing edge to the end missing from `traversed`.
- **Fixed: workspace sync keeps file permissions** — Files extracted from a runner tar transfer keep their executable bit, so `.homer/bin` helpers stay executable on external and Kubernetes runners.
- **Fixed: a finished run no longer leaves the `.homer/` tooling directory behind** — `cleanAfterFinish` now deletes `.homer/` together with `workdir/`, so a terminal run's workspace holds only `artifacts/` until `purgeAfter`.
- **Fixed: the Runner column is empty for non-Kubernetes runs** — A run now records the backend that served it (local, ssh, external or Kubernetes) once its workspace is created, so the status API returns `runner` for every run that got a session and created its workspace instead of only Kubernetes runs; the response carries the generic backend `type` to every viewer and the operator-chosen `alias` to admins only.
- **Security: end-of-run cleanup no longer deletes a workspace a live run still uses** — When runs share a fixed `workspace.path`, a finishing run's `cleanAfterFinish` cleanup now skips the `workdir/` and `.homer/` deletes while another run on the same path is still CREATED or WORKING, instead of removing helper binaries and `mcp.json` mid-execution. See [ADR-0048](docs/adr/0048-split-record-retention-from-workspace-purge.md).
- **Fixed: the Runner column is empty for non-Kubernetes runs** — A run now records the alias of the backend that served it (local, ssh, external or Kubernetes) once its workspace is created, so the status API returns `runner` for every run that got a session and created its workspace instead of only Kubernetes runs.
- **Added: `traversed` in `homer_langgraph state` output** — The workflow edges a thread has actually taken, read from its checkpoint history. See [ADR-0075](docs/adr/0075-langgraph-traversed-path-from-checkpoint-writes.md).
- **Added: `traversed` on the LangGraph run-status endpoint** — The workflow edges a run has taken, limited to edges of its `topology`. See [ADR-0075](docs/adr/0075-langgraph-traversed-path-from-checkpoint-writes.md).
- **Fixed: Langfuse no longer prices a Claude attempt once per `result`** — When a background subagent finishes after the first `result`, the CLI writes a second one, and each of the attempt's generations carried the whole attempt's cost. Each generation now carries its turn's share, so an attempt's generations sum to what it was charged. See [ADR-0074](docs/adr/0074-cost-of-an-attempt-that-ends-more-than-one-turn.md).
- **Fixed: the CLI cost fallback charges every turn of a Claude attempt** — When the session-spend endpoint did not answer, an attempt that wrote a second `result` was charged only up to its first one, under-counting the cost ledger behind `costLimits`; it is now charged the highest figure the CLI reported, and its `llm.usage.*` span attributes sum every turn.
- **Security: a lower or negative CLI cost line can no longer lower an attempt's charge** — A later `result` line in the agent's stdout reporting less than an earlier one, or a negative cost, is ignored rather than charged, so it cannot walk real spend past `costLimits` when the session-spend endpoint does not answer; a negative token count is neither summed into an attempt's usage nor shown on its generation, and the sum saturates rather than wraps. A failed or stopped turn end's cost share moves to a successful one, so it cannot keep an attempt's Langfuse generations below its charge. See [ADR-0074](docs/adr/0074-cost-of-an-attempt-that-ends-more-than-one-turn.md).
- **Fixed: a Codex step nothing priced is not priced at zero in Langfuse** — An attempt whose cost source is `none` now hands telemetry no cost instead of a zero, so its generation carries usage only, as an unpriced generation already does; a spend endpoint's own zero is still reported as zero. See [ADR-0074](docs/adr/0074-cost-of-an-attempt-that-ends-more-than-one-turn.md).
- **Fixed: a killed or turn-limited LLM step is no longer free in Langfuse** — An attempt with no successful `result` is priced on its last one, and an attempt killed before writing any gets one generation from its accounting, both marked as an error, so Langfuse's cost matches the ledger for every attempt. See [ADR-0074](docs/adr/0074-cost-of-an-attempt-that-ends-more-than-one-turn.md).
- **Fixed: a killed or failed LLM step keeps its tool and message spans in Langfuse** — The stream of a non-zero exit, a timeout or output-cap kill and a cancelled run is read leniently and within fixed bounds, so a truncated last line no longer drops every span of the step.
- **Security: the `traversed` checkpoint scan is bounded** — A forged or inflated checkpoint history can no longer make a LangGraph status poll consume unbounded CPU and memory; the scan stops at fixed checkpoint and write caps, and an oversized routing channel is dropped before it is matched. See [ADR-0075](docs/adr/0075-langgraph-traversed-path-from-checkpoint-writes.md).

### Console

- **Fixed: the Runner card is hidden for non-Kubernetes runs** — The process details page now shows a Runner card for every run that got a session and created its workspace, with the serving backend and, for a Kubernetes run, its pod details. An admin sees the backend alias; every other viewer sees the generic backend type.
- **Fixed: LangGraph self-loops are drawn on their node** — A node that can route back to itself shows a loop on its right side instead of a dangling arrow beside it, and the loop's label no longer hides under a neighbouring node.
- **Added: edge legend in LangGraph workflow graphs** — A legend under the graph explains fixed (solid) vs conditional (dashed) edges.
- **Added: taken path in LangGraph run graphs** — Edges a run has actually traversed are drawn green. See [ADR-0075](docs/adr/0075-langgraph-traversed-path-from-checkpoint-writes.md).

### Build / CI

- **Security: the dev compose stack starts no JDWP listener** — The unauthenticated debug agent moved behind the opt-in `docker-compose.debug.yml`, which binds container loopback and publishes nothing, so no peer on `homer-network` can reach it. See [ADR-0076](docs/adr/0076-dev-jdwp-is-an-opt-in-loopback-overlay.md).
- **Security: the compose port guard reads long syntax and ranges** — The CI check refusing a published container port 5005 now reads the `target:` key whichever order the mapping lists and flags any port range covering 5005.
- **Fixed: themes can set the body font** — `--font-family-sans` now applies to body text; it was always Inter, so `CONSOLE_THEMES` themes and the Terminal and Windows 95 themes did not get their own font.
- **Security: bump `source-map-js` to 1.2.2** — Clears the high-severity event-loop DoS advisory (GHSA-68fv-2mgg-jv7q) that was failing the CI audit gate.

## [1.28.0] - 2026-10-02

### Homer

- **Added: `homer_langgraph inspect --json`** — Prints the graph's structured `topology` (nodes and edges) with its Mermaid source; `state` output gains the same `topology` field. See [ADR-0073](docs/adr/0073-langgraph-topology-is-structured-data.md).
- **Security: LangGraph introspection output is bounded** — Homer reads at most 2 MiB of a graph `inspect`/`state` subprocess's output and 16 KiB of its error text. See [ADR-0073](docs/adr/0073-langgraph-topology-is-structured-data.md).
- **Added: `topology` on the LangGraph graph and run-status endpoints** — Each workflow graph's nodes and edges as structured data. See [ADR-0073](docs/adr/0073-langgraph-topology-is-structured-data.md).
- **Deprecated: `mermaid` on the LangGraph graph and run-status endpoints** — Superseded by `topology`.
- **Changed: agent graph introspection needs `homer_langgraph inspect --json`** — A command whose custom `python` carries an older runtime reports an error instead of a graph.
- **Fixed: an agent with an optional body input runs without a request body** — A bodyless or whitespace-only `POST /api/v1/agent/{name}` counts as an empty JSON object for an agent without `inputSchema`, so defaults apply and a missing required body input is reported by name; GET execution still requires POST. See [ADR-0072](docs/adr/0072-bodyless-post-only-get-stays-tight.md).
- **Fixed: a cron schedule that can never fire is refused at load** — An agent with a required input that has no default no longer registers a schedule; the reason is reported in the agent reload result.

### Console

- **Changed: LangGraph workflow graphs are drawn natively** — They follow the console theme, dark mode included, and Mermaid is no longer a dependency. See [ADR-0073](docs/adr/0073-langgraph-topology-is-structured-data.md).
- **Added: workflow nodes open their sub-agent runs** — Clicking a node in a LangGraph run's workflow graph lists the agents and runs it dispatched, with links.

## [1.27.0] - 2026-10-01

### Homer

- **Fixed: runs interrupted by a restart are purged** — A run left `UNKNOWN` by a server restart now has its workspace and record purged on its agent's `purgeAfter` / `retain` schedule, counted from the restart, instead of staying until `server.maxProcesses` evicts it. See [ADR-0048](docs/adr/0048-split-record-retention-from-workspace-purge.md).
- **Fixed: a run killed as it starts stays KILLED** — A kill landing before the run's first status write was overwritten and the run ended `FAILED`; it now stays `KILLED` and runs no command.
- **Fixed: LangGraph run status no longer writes to a run's checkpoint store** — The runtime's `state` command opens the store read-only, so a status read can no longer create tables or switch the journal mode in a store file the agent wrote.
- **Fixed: a refused callback run or join no longer leaves subs running** — When `POST /callback/joins` or `POST /callback/runs` with `continueAgent` fails after starting a sub (cost cap, `cooldown`, capacity, pending-continuation cap), every sub it started is killed, and invalid sub `params` are refused before any sub starts. See [ADR-0026](docs/adr/0026-join-continuations.md).
- **BREAKING: an input can no longer choose the Claude or Codex binary** — An agent whose `!<claude>` or `!<codex>` `cmd` references an input's `envName` fails to load; agent env, `globalEnv` and `@SYSENV` references still work.
- **Fixed: ssh uploads stop at directory-symlink cycles** — The ssh runner's workspace upload now stops at directory-symlink cycles and refuses a symlinked directory source as it already did a file, and on every runner a symlink to an already-uploaded directory is skipped while a real directory never is.
- **Security: LangGraph run status shows only the run owner's sub-runs** — A run whose identity artifact names another owner's thread no longer lists that owner's sub-runs in its status.
- **Added: `server.dispatch.settledRetentionSec`** — Settled dispatches and continuations older than the window (default 7 days) are deleted by the existing sweeps.
- **BREAKING: continuation and dispatch lists are paginated, newest first** — `GET /continuations` and `GET /dispatches` return at most `limit` rows (default 100, at most 1000) from `offset`, newest first, with `total`; a client that read the whole list must page.
- **Fixed: the shutdown WAL checkpoint now runs** — The checkpoint was issued inside a write transaction, where SQLite refuses it `SQLITE_LOCKED`, so the WAL was never truncated on shutdown; Homer now flushes the write-ahead log into the database file, so the file is self-contained for backup and inspection. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Fixed: a committed write is never re-run** — A transaction that failed after its commit had landed can no longer replay the block, so a run can no longer be charged twice, gain a duplicate step, or spawn a second process record. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Security: agentdebug dry run masks secret values** — A declared secret or `@SYSENV:` value resolved into a Claude or Codex option is printed as `***` in `--dry-run` output.
- **Security: log tail and artifact download refuse linked files** — The live stdout/stderr tail and the `/artifacts` download no longer follow a symlink or hard link an agent plants at its artifact name, so neither can serve another file.
- **Security: file API refuses a definition discovery could never load** — A definition written deeper than discovery's depth cap, or under a hidden directory, is refused instead of leaving a subtree the API can only answer `404` for. See [ADR-0068](docs/adr/0068-nested-agent-discovery.md).
- **Security: agent file listing omits symlinks that point outside the agent** — `GET /agents/{name}/files` no longer lists an escaping symlink with its target's size and modification time.
- **Fixed: writers no longer queue behind a committed transaction** — The write lock is released when a write transaction commits, not when Exposed closes its connection, so a second writer takes it at once instead of waiting out `busy_timeout`. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Changed: one SSE event per step boundary** — A step's record append and its current-command change commit in one transaction and emit one `process.cmd` event instead of two, halving the per-step console refetches.
- **Changed: process-list filters are indexed** — `agent_name`, `status`, `parent_process_id`, `root_process_id` and `owner` get indexes (schema v23), so a `GET /api/v1/status/all` page and its `total` no longer scan the whole table, for admin and non-admin callers alike.
- **Changed: LangGraph run status and continuation lists read by index** — A run's sub-run lookup uses a new continuation index (schema v27) and join watches load in batched reads, so both stay fast as continuations accumulate.
- **Fixed: API reads no longer fail runs under load** — Read-only queries no longer take the database write lock, so heavy console or API polling can no longer make a run's step bookkeeping fail with `SQLITE_BUSY`. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Changed: reads reuse SQLite connections** — Read-only queries use connections kept open between transactions instead of opening one each time, so a point read on a full process table costs about a hundredth of what it did. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Changed: sweep and lookup queries are indexed** — The shared-workspace check, the minute-by-minute workspace and record purge sweeps, and the `cooldown.afterFailure` check at run start no longer scan the process table, and question, dispatch and continuation lookups no longer scan theirs; an admin's `?tag=` filter no longer probes every run; SQLite planner statistics are gathered at startup and refreshed hourly.
- **Fixed: the log tail reads output in bounded 1 MiB chunks** — A `log.chunk` frame carries at most 1 MiB, cut on a whole UTF-8 character, so tailing a large output no longer holds the unread remainder in memory at once.
- **Fixed: step updates of a run with large inputs no longer rewrite megabytes** — A run's `input_params` moves out of the process row into a side table, so per-step bookkeeping writes kilobytes instead of megabytes of WAL for a run started with a multi-MiB input. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **BREAKING: LLM span attributes renamed to `llm.*`** — `claude.cost*`, `claude.session_id`, `codex.usage.*`, `telemetry.claude.*`, `claude.retry.attempt_N`/`codex.retry.attempt_N` and `codex.transient_error` are now `llm.cost*`, `llm.session_id`, `llm.usage.*`, `telemetry.llm.*`, `llm.retry.attempt_N` and `llm.transient_error` for every LLM harness command; `input.claudeCmd`/`input.codexCmd` became `input.argv`; the parent-span `metadata.input`, `metadata.permissionMode`, `metadata.mcp_servers` and the `codex.usage.parse_failed` attributes are removed. See [ADR-0070](docs/adr/0070-llm-harness-adapter-boundary.md).
- **Changed: Codex runs get generation and tool spans, usage and session-spend pricing, and count toward cost caps** — A `!<codex>` step is observed like a `!<claude>` step; a tokens-only run records cost source `none` and is never capped. See [ADR-0070](docs/adr/0070-llm-harness-adapter-boundary.md).
- **Added: `executions[].kind`, `currentCommandKind`, `currentCommandIndex` and `llmAccounting.usage` on the status API** — Each step reports its command tag and token usage, and the running command reports its tag and index.
- **BREAKING: `GET /stream/processes/{id}` replaced by `/stream/processes/{id}/llm-events`** — The raw Claude event broadcast is gone; the new stream carries canonical Claude and Codex events, replays from the start and resumes via a `cmdIndex:attemptIndex:lineOffset:eventIndex` `Last-Event-ID`; the known consumer, a Slack connector, falls back to step-level progress until it is released against `/llm-events`. See [ADR-0070](docs/adr/0070-llm-harness-adapter-boundary.md).
- **Fixed: cost by tag reports a truncated total** — `GET /costs/by-tag/{key}` now returns `truncated: true` when more runs carry the key than the newest 10,000 it sums, instead of silently under-reporting. See [ADR-0062](docs/adr/0062-cost-by-tag.md).
- **Security: ssh runner uploads skip hard-linked files** — Like the Kubernetes and external runners, a workspace upload over ssh skips a file with more than one hard link and refuses one named on its own, and a file swapped for a symlink during the upload is refused instead of streamed.
- **Fixed: nested agent discovery no longer drops agents silently** — Files, hidden entries and symlinks no longer use up the directory budget, the entries kept under a cap no longer depend on filesystem order, and a cap hit always logs a warning.
- **Security: runner polls and login no longer share the global rate-limit bucket** — A client draining the unauthenticated bucket can no longer stall run pickup or lock operators out; runner requests with a valid domain token use a per-domain bucket and logins an aggregate login bucket. See [ADR-0014](docs/adr/0014-per-caller-rate-limiting.md).
- **Security: a secret input's default and allowed values no longer leave the API** — They are dropped from the agents list and the MCP tool schema, and no validation error repeats a secret input's value or allowed set. See [ADR-0071](docs/adr/0071-secret-input-defaults-are-dropped-from-the-agents-list.md).
- **Changed: `app.log` is rotated** — Homer writes `app.log` itself as a rolling file (10MB × 3) beside `api.log`, and the container no longer redirects stdout into it, so `docker logs` / `kubectl logs` now show the application log.
- **Changed: the agent schema names the LLM retry block and command tags correctly** — The bundled `homer-agent.schema.json` renames the LLM retry definition that was still `ClaudeRetry` and lists every command kaml tag including `!<langgraph>` and `!<runnerCmd>`; validation is unchanged.

### Console

- **Security: bump locked `sharp` to 0.35.5 and `source-map-js` to 1.2.2** — Clears the high-severity librsvg advisory (GHSA-wq5f-xc86-pv6w) and the high-severity source-map event-loop DoS (GHSA-68fv-2mgg-jv7q) that failed the CI audit gate.
- **Security: bump `next` to 16.3.8 and patch `dompurify`** — Clears a critical Next.js and a high-severity DOMPurify advisory that failed the CI audit gate.
- **Changed: Processes page opens on all runs** — The flat list of every run is the default view; the per-flow roots view moved to the **Root runs** toggle (`?view=roots`).
- **Changed: process-event refetches are coalesced per frame** — A burst of process events invalidates each affected query once instead of once per event, so a busy board no longer multiplies refetches; a hidden tab applies them when it becomes visible.
- **Changed: continuations page says when rows are missing** — The pending and failed cards show the newest 100 continuations and note "Showing the newest N of total" when more exist.
- **Added: `CONSOLE_THEMES` runtime themes** — Operators load their own console themes from same-origin stylesheets under `public/themes/`, without a rebuild; they join the theme dropdown and are validated like plugin entries. See [ADR-0069](docs/adr/0069-console-themes-are-runtime-loaded-stylesheets.md).
- **Changed: retry, resume and debug honour `cooldown.minInterval` but not `afterFailure`** — A start on any of the three endpoints inside the `minInterval` window is refused with `429` and launches nothing, while a manual recovery or debug run is no longer blocked by the failure backoff. See [ADR-0051](docs/adr/0051-cooldown-is-enforced-in-the-insert-transaction.md).
- **Changed: conversation view for Claude and Codex steps** — The step output dialog renders one Conversation view for every LLM harness command from the new `/llm-events` stream; token usage is shown per step, the summary cost is the figure the step was charged, a step nothing priced reads "not reported" instead of $0, and an oversized or malformed event shows a note in its place instead of breaking the view.
- **Fixed: the cost-by-tag card says when its total is partial** — A note appears under the total when the backend summed only part of the runs.
- **Added: Run Agent form shows input defaults and allowed values** — Each non-secret input shows its declared default and allowed values, and typed inputs are checked client-side before submit.
- **Fixed: the Run Agent dialog scrolls its inputs** — An agent with more inputs than fit in the dialog no longer has the last ones clipped and unreachable.
- **Fixed: a plugin can no longer claim `/runners`** — A plugin declaring the `/runners` route is skipped with a logged reason instead of loading and never rendering.

### Build / CI

- **Fixed: the dev compose stack bundles `agentdebug`** — `docker-compose.dev.yml` set `HOMER_DEBUG=1` as a runtime variable, which never reached the image build; it is now a build argument.
- **Fixed: compose passes console settings from `.env`** — `docker-compose.yml` now reads `CONSOLE_HOMER_API_URL` (default `http://localhost:8080`), `CONSOLE_PLUGINS` and `CONSOLE_THEMES` from the environment or `.env` instead of ignoring them.
- **Fixed: dev compose JVM debug port accepts a debugger** — The dev `JAVA_OPTS` now starts the JDWP agent on `5005`, so an IDE can attach to `localhost:5005`.
- **Fixed: `docker compose up` builds the backend again** — The compose `backend` service built the external runner image, the Dockerfile's last stage, instead of the backend; it now targets the backend stage.

## [1.26.1] - 2026-09-29

### Homer

- **Changed: process-list filters are indexed** — `agent_name`, `status`, `parent_process_id`, `root_process_id` and `owner` get indexes (schema v23), so a `GET /api/v1/status/all` page and its `total` no longer scan the whole table, for admin and non-admin callers alike.
- **Fixed: API reads no longer fail runs under load** — Read-only queries no longer take the database write lock, so heavy console or API polling can no longer make a run's step bookkeeping fail with `SQLITE_BUSY`. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).

### Console

- **Fixed: the process list no longer refetches every flow summary on every process event** — Only the summaries an event is about are refreshed, so an open list page sends one request per event instead of twenty-one; each summary still polls every 30 s, even once its runs have all finished.

## [1.26.0] - 2026-09-25

### Homer

- **Security: definition-file checks apply to the file a write lands on** — A symlink inside an agent directory that aliases `agent.yaml`/`agent.json` no longer lets a write or delete reach the definition past the schema check and delete refusal.
- **Added: nested agent discovery** — `agentsSearchPath` is walked recursively, so an `agent.yaml`/`agent.json` at any depth (including inside another agent's directory) loads; names stay one flat namespace, a parent's file API and remote upload cannot reach a nested agent, and writing a definition that declares a new agent name needs edit on that name too. The walk's entry budget is per top-level agent directory with a whole-catalog ceiling, and file-API paths are depth-capped, so one agent's directory cannot starve the rest of the catalog. See [ADR-0068](docs/adr/0068-nested-agent-discovery.md).
- **Added: external pull-mode runners** — Machines outside Homer can register as runner instances and claim runs over HTTP; a domain can declare `extraUploads` like an ssh runner. See [ADR-0066](docs/adr/0066-external-pull-runners-and-unified-claim-queue.md).
- **Added: flow grouping in the process API** — `rootProcessId` on every process, `?rootProcessId=` / `?roots=true` filters on `GET /api/v1/status/all`, and `HMR_ROOT_PROCESS_ID` in the run's env. See [ADR-0058](docs/adr/0058-parent-child-run-linkage.md).
- **Added: external pull-mode runners** — Machines outside Homer can register as runner instances and claim runs over HTTP. See [ADR-0066](docs/adr/0066-external-pull-runners-and-unified-claim-queue.md).
- **Added: runner status endpoints** — `GET /api/v1/runners` and `GET /api/v1/runners/queue` show configured runners, live external instances and pending claims to admins. See [ADR-0066](docs/adr/0066-external-pull-runners-and-unified-claim-queue.md).
- **Changed: Kubernetes workspace sync-down is bounded at 1 GiB extracted** — A pod workspace or artifacts download that expands past the limit is refused and logged like any other sync-down failure. See [ADR-0066](docs/adr/0066-external-pull-runners-and-unified-claim-queue.md).
- **Security: hard-linked workspace files are skipped on upload** — A workspace file with more than one link is warned about and left out of the tar sent to a Kubernetes or external runner, and one named on its own for upload is refused, so a hard link to a file outside the workspace cannot be packed into it.
- **Security: symlinked workspace directories are uploaded from the checked target** — The upload walk descends into a symlinked directory through the path it validated, so a link repointed mid-upload cannot pull a tree from outside the workspace into the tar sent to a remote runner.
- **Security: sync-down never writes through a dangling symlink that leads outside the workspace** — A downloaded entry whose local path is a dangling link resolving outside the target is refused, so the write cannot create a file elsewhere on Homer's host.
- **Security: denials no longer reveal which agents exist** — Run and debug return the unknown-agent `404` to a caller holding no grant on the agent, and a dispatch hop that may not reach its target gets the same `404` (was `403`). See [ADR-0039](docs/adr/0039-nested-dispatch-authority.md).
- **Fixed: cron ticks no longer drift past a minute** — Each tick is scheduled to the next wall-clock minute boundary, so a tick whose work takes less than a minute can no longer skip a minute and its schedules.
- **Fixed: `runnerTags` agents load on the first boot of a fresh database** — A startup-ordering bug skipped the agent when a kubernetes runner was configured; a failed database migration now also aborts boot loudly instead of degrading silently. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Fixed: a timed-out `when` predicate now reports which step failed** — The step records an execution entry carrying the timeout message instead of vanishing from the run's status.
- **Security: liveness and readiness probes are exempt from the global rate limit** — Draining the unauthenticated global bucket can no longer make `/livez` answer `429` and get the pod restarted mid-run.
- **Security: step error text is redacted** — A credential placed in a `when` expression or a command line no longer reaches the run's status record or API through a failed or skipped step's error message.
- **Security: an external runner `homerApiUrl` may not carry embedded credentials** — A URL with userinfo is refused at startup, and the URL is redacted when the runner config is rendered as a string.
- **Security: a run's flow-root id is only served when the caller can see the root run** — Status responses null `rootProcessId` on rows whose flow root the caller holds no visibility on, a run linked through a parent whose flow root the caller cannot see is stamped rootless instead of inheriting that id, fired dispatch and continuation runs drop a root their owner cannot see, and a `?rootProcessId=` filter on an invisible root reads as an empty list, so a grant on one descendant can no longer reveal the existence of another principal's flow root.

### Console

- **Added: flow grouping on the Processes page** — The list defaults to one row per flow with its run count, total cost and aggregate state, with an **All runs** toggle; run pages show the flow and link to the root run.
- **Added: `/runners` page** — Admins see configured runners, registered pull-mode instances and the claim queue, refreshed every 5 s.
- **Fixed: stale process list and detail after a dropped SSE event** — Both views now poll every 30 s as a fallback; a poll stops once the process is terminal, or once the list holds runs and all of them are.
- **Fixed: Export Curl targeted `localhost:8080`** — The exported command now uses the active instance's backend URL.

### Build / CI

- **Added: `homer-runner` image** — CI now builds and publishes a small image of the external runner instance next to the server image.
- **Changed: image builds resolve through Artifactory too** — The Gradle steps inside the backend and E2E image builds now receive the Artifactory credentials as BuildKit build secrets instead of resolving from Maven Central.
- **Security: CI Gradle builds reach Artifactory over TLS** — Plugin, dependency and remote-build-cache resolution moved from `http://` to `https://`; the insecure-protocol allowance is gone, so an unverifiable certificate fails the build instead of downgrading.
- **Fixed: the license inventory generator no longer rejects a console checkout reached through a symlink** — The symlink containment check compares resolved paths, so a symlinked checkout location (e.g. macOS `/var` → `/private/var`) no longer makes every package read as escaping `node_modules`.

## [1.25.1] - 2026-09-21

### Build / CI

- **Changed: CI Gradle builds resolve through Artifactory** — With the `ARTIFACTORY_USER` / `ARTIFACTORY_TOKEN` CI/CD variables set, Gradle pulls plugins and dependencies via the in-cluster Artifactory proxy and uses its remote build cache, pushed from protected refs only.

## [1.25.0] - 2026-09-21

### Homer

- **Security: `remoteWorkspaceRoot` of an `ssh` runner must be an absolute, normalized path other than `/`** — Config load rejects a blank, relative, root or traversing value; before the cleanup `rm -rf` runs, the composed project root is re-checked and every component below `remoteWorkspaceRoot` is `lstat`ed, a symlink refusing the sweep. See [ADR-0067](docs/adr/0067-ssh-cleanup-via-rm-rf.md).
- **Fixed: the ssh runner tears a `cleanAfterFinish` workspace down in seconds, not minutes** — The remote project root is removed with one `rm -rf` over the exec channel; the per-file SFTP walk is now only the fallback when that command fails. See [ADR-0067](docs/adr/0067-ssh-cleanup-via-rm-rf.md).
- **Security: a failed SFTP client close now sheds the SSH session** — When closing an SFTP client fails outright, the whole SSH session is closed instead of being reused for later transfers, so a hostile peer cannot strand a channel per failed release while holding a run slot. See [ADR-0064](docs/adr/0064-sftp-watchdog-timeout.md).
- **Added: runner claim queue with per-runner `concurrency` and a claim TTL** — Every run is claimed from one queue by a matching runner; agents can select a runner by `runnerTags`, and the `local`, `ssh` and `kubernetes` runners can declare `tags` and a `concurrency` bound. See [ADR-0066](docs/adr/0066-external-pull-runners-and-unified-claim-queue.md).

### Console

- **Added: total row in the 24 h per-agent cost table** — The Costs page card now ends with a `Total` row summing the displayed per-agent 24 h spend, matching the monthly, all-time and by-tag cards.

## [1.24.0] - 2026-09-17

### Homer

- **BREAKING: remote runners no longer receive the backend's host env** — `parentEnvAllowlist` keys apply to the `local` runner only and are never forwarded to `ssh`/`kubernetes` runners.
- **Added: per-runner `env` for `ssh` and `kubernetes` runners** — Operator-declared environment variables layered between `globalEnv` and agent `env`, with `@SYSENV:` support and redaction like `globalEnv`. See [ADR-0065](docs/adr/0065-remote-runner-env-composition.md).
- **Added: `server.costTrackedTagKeys` config + `GET /api/v1/costs/by-tag/{key}`** — Breaks down Claude spend by the values of a configured run subject-tag key; values of input keys no longer in the agent definition are treated as secret (fail-closed). See [ADR-0062](docs/adr/0062-cost-by-tag.md).
- **Security: bounded SFTP transfer time on the ssh runner** — Each blocking SFTP round trip, including the client close, is capped by `sftpTransferTimeoutSeconds` (default 300 s, `null` disables) so a peer answering keepalives while stalling SFTP data cannot hold run slots and workspace locks indefinitely; a failed or timed-out close is logged and swallowed. See [ADR-0064](docs/adr/0064-sftp-watchdog-timeout.md).
- **Security: ssh runner download listings capped at 100 000 entries per directory** — A malicious SSH runner serving a directory with millions of entries can no longer exhaust the JVM heap; a listing over the cap fails the download.
- **Added: `server.claudeDropSystemSubtypes` config** — Drops the listed Claude `system` event subtypes (default `thinking_tokens`) from a Claude step's stdout before it is written. See [ADR-0063](docs/adr/0063-filter-claude-stdout-at-write-time.md).
- **Fixed: the ssh runner no longer kills a quiet command after 10 minutes** — The SSH idle timeout is disabled by default, with 30-second keepalives (`idleTimeoutSeconds` / `heartbeatIntervalSeconds`).
- **Security: cross-runner `runnerCmd` steps no longer leak host or target-runner credentials** — A `runnerCmd` targeting a remote runner does not receive the backend's `parentEnvAllowlist` host env (the same exclusion a remote run gets), and its telemetry spans redact the target runner's `@SYSENV:`-backed credentials, including when a runner `env` name collides with a same-named workspace credential. See [ADR-0065](docs/adr/0065-remote-runner-env-composition.md) and [ADR-0047](docs/adr/0047-security-by-design-and-threat-model.md).
- **Security: session-spend polling is bounded against inflated model-call counts** — A crafted Claude stdout can no longer drive the spend-endpoint poll schedule past 1 000 expected calls, so one run cannot tie up polling for the full schedule. See [ADR-0061](docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md).

### Console

- **Added: cost-by-tag breakdown on the Costs page** — Pick a configured tag key and see spend per value, next to the existing monthly/total cost cards.
- **Added: per-process cost on the Processes list** — A Cost column shows each run's spend, and the filter bar shows the total for the currently filtered/paginated page.

## [1.23.1] - 2026-09-11

### Homer

- **Added: `llmPrice.sessionSpend.callsPath` config** — Polls the spend endpoint at 0, 5, 15 and 30 s after a Claude attempt until it reports every call the attempt made, charging the highest partial figure (marked `incomplete`) when it never does. See [ADR-0061](docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md).
- **Fixed: a Claude attempt cancelled mid-charge is no longer charged twice** — The charge is uncancellable, so a cancellation during the spend lookup cannot leave a partial charge for the retry path to repeat.

### Console

- **Fixed: an incomplete session cost is no longer labelled a fallback** — A `proxy`-priced step charged before the endpoint finished ingesting shows its reason without the `fallback:` prefix.

## [1.23.0] - 2026-09-11

### Homer

- **Fixed: orphan-pod sweep `ranAt` reports epoch seconds** — It was emitting milliseconds, so the console rendered a garbled timestamp on the sweep banner; it now matches every other API timestamp.
- **Security: `?tag=` filter honours the same secret screen as the status response** — A tag value derived from a `secret` input is no longer matchable via `GET /api/v1/status/all?tag=`, so `total`/results cannot confirm a guessed secret. See [ADR-0060](docs/adr/0060-run-tags.md).
- **Security: denial-of-service bound on the screened `?tag=` read** — A tag-filtered status list fetches at most 10 000 matching rows in SQL and reads tags in batched queries chunked under SQLite's parameter limit, never per row. See [ADR-0060](docs/adr/0060-run-tags.md).
- **Added: `llmPrice.sessionSpend` config** — Prices each Claude attempt from an external session-spend endpoint (URL template, headers, JSONPath) and falls back to the CLI's `total_cost_usd`, recording the source on the run (`costSource`, per-command `llmAccounting`). See [ADR-0061](docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md).
- **Changed: every Claude attempt is cost-accounted** — Non-zero exits and timeout kills are now charged (from the spend endpoint when configured, else the CLI figure when a `result` event exists); previously they charged nothing.

### Console

- **Added: cost source on process detail** — The run's cost carries a `proxy` / `CLI estimate` / `mixed` label, and each Claude step shows its cost, source, fallback reason and CLI session id.
- **Changed: new app icon and favicon** — The header logo and the browser-tab icon use the new red-eye artwork.

## [1.22.0] - 2026-09-09

### Homer

- **Added: run tags — find runs by their subject** — A run carries `key:value` subject tags, from declared inputs (`tags.fromInputs`) and/or run-time declarations, and `GET /api/v1/status/all?tag=mr:15` filters on them (repeatable `tag` = AND, at most 10 per request, each value at most 193 characters; more is `400`). Tags are returned on every run's status. See [ADR-0060](docs/adr/0060-run-tags.md).
- **Added: `tags` agent-schema block** — Top-level `tags` with `fromInputs` (paramNames resolved to tags at start, each matching `^[A-Za-z0-9._-]{1,64}$`) and `runtime` (keys the run may declare mid-run via `homer-tag`).
- **Added: `POST /api/v1/callback/tags` endpoint** — Lets a running command declare a runtime tag on its own run via the `homer-tag` workspace helper, authenticated by the per-process callback token; a value containing one of the run's secret values is refused (`400`), and tags containing a value that is secret at read time are omitted from status responses.
- **Deprecated: `telemetry.traceTags`** — Superseded by `tags.fromInputs`, which does the same job and also tags the run itself; `traceTags` stays as an alias for one release and logs a WARN at agent load.
- **Security: enforce 1..255 range on `maxTurnsExitCode`** — An out-of-range value (e.g. `0`) is now rejected at agent-load time so a max-turns failure cannot masquerade as a successful exit.

### Console

- **Added: run tags as clickable chips** — Run tags appear on the process list and detail pages as chips; clicking one filters the process list to runs carrying that tag (multiple chips AND), with a removable filter badge.
- **Security: bump `next` to 16.3.4 and patch `sharp`** — Clears the critical Next.js advisory (GHSA-p293-qw3h-jr36) and high-severity sharp advisory (GHSA-rgj7-g3m4-5g8c) that were failing the CI audit gate.

### Build / CI

- **Security: license inventory generator rejects symlinked package directories** — `licenses:check` / `licenses:generate` now resolve each package directory's real path and refuse one that escapes `node_modules` via a symlink, closing a path-traversal read.
- **Security: NOTICE generator rejects symlinked legal files** — Symlinked `LICENSE`/`NOTICE` entries inside extracted dependency jars are now skipped so a crafted dependency cannot leak host files into the committed attributions.

## [1.21.0] - 2026-09-08

### Homer

- **Added: `maxTurnsExitCode` on `!<claude>` commands** — Opting in turns a `max_turns` stop into an ordinary exit code instead of a validation failure, so an agent's next step (a deterministic ship step, say) can keep the partial work and a parent graph can ask a human whether to continue. Unset keeps today's fail-closed behaviour.
- **Fixed: a fan-out parent no longer crashes on a sub whose result cannot be fetched** — When a sub-run ends without `FINISHED` and its over-cap `resultRef` cannot be redeemed, `homer_fanout` now resumes with `result: null` and reports on stderr instead of raising, so the graph's own status check (retry / park) runs. A fetch failure for a `FINISHED` sub still raises.
- **Fixed: no `resultRef` is minted for a transformed result the fetch endpoint refuses** — A continuation whose watched run's last output is over the 4 MiB transform cap (typically a failed Claude step's NDJSON stdout) resumes with `result: null` and no ref, rather than a token that `GET /api/v1/callback/results/{token}` would always answer with `413`.

### Console

- **Fixed: Export Curl leaked the auth token and omitted the request body** — The exported command now uses a `$HOMER_AUTH_TOKEN` shell placeholder and includes the JSON body from the form inputs.
- **Changed: searchable agent filter on the process page** — The agent filter is now a searchable combobox with type-to-filter instead of a plain dropdown.
- **Changed: agent name on the process page links to the agent** — The process-detail header renders the agent name as a link to its agent detail page.
- **Added: runtime console plugins** — `CONSOLE_PLUGINS` adds operator-supplied pages to the console without a rebuild, each contributing a sidebar entry and its own route, and each optionally gated with `@admin` or an Action. See [ADR-0059](docs/adr/0059-console-plugins-are-runtime-loaded-modules.md).

## [1.20.0] - 2026-09-05

### Homer

- **Added: parent/child run linkage** — Runs started by `RunAgent`, dispatch, or continuation record the run that caused them as `parentProcessId` on the status API, a retry or resume keeps the source run's parent, and `GET /api/v1/status/all?parentProcessId=<id>` lists a run's children. See [ADR-0058](docs/adr/0058-parent-child-run-linkage.md).
- **Added: `X-Homer-Parent-Process-Id` request header** — An external caller of `POST /api/v1/agent/{name}` can link the new run to a parent run it can see; an unknown or invisible parent is refused with `422`.
- **Added: `HMR_PARENT_PROCESS_ID` env var** — Set inside a run that has a parent, so an agent no longer needs to forward its own process id as an input.
- **Security: dependency upgrades for scanner-reported vulnerabilities** — Apache SSHD 2.19.0, Jackson 2.22.2 / 3.2.2, BouncyCastle 1.85, json-schema-validator 3.0.7 and langfuse-java 0.3.0 replace the versions flagged by Snyk.

### Console

- **Added: parent and child runs on the process page** — The run header links to the parent run, a **Child runs** card lists the runs it started, and the process list accepts a `parent=<id>` filter.

### Build / CI

- **Added: committed third-party notices** — `NOTICE` and `THIRD-PARTY-LICENSES.md` now carry the attributions and inventory for every redistributed backend and console package, generated and drift-checked in CI. See [ADR-0056](docs/adr/0056-dependency-license-policy.md).

## [1.19.0] - 2026-09-02

### Homer

- **Fixed: `keepPodOnFailure` pod was deleted anyway** — The keep-with-TTL patch was sent with a Content-Type the apiserver rejects (415), so the failure fallback deleted the pod it was meant to preserve.
- **Fixed: graceful shutdown failed in-flight remote-runner runs** — Runner sessions were closed before the shutdown drain, deleting kubernetes/ssh execution resources out from under still-running runs; sessions now close only after the drain window.
- **Fixed: Claude runs reported `model: unknown` in Langfuse** — The model is resolved independently of the rest of the CLI's init event, falling back to the run's own per-model accounting and then to the step's configured model. See [ADR-0057](docs/adr/0057-claude-output-modelled-only-where-read.md).
- **Fixed: a run with a denied tool call reported no cost or tokens** — Its result event failed to decode, so the priced Langfuse observation was never emitted at all. See [ADR-0057](docs/adr/0057-claude-output-modelled-only-where-read.md).
- **Security: `GET /api/v1/status/all` no longer scans the table for an out-of-range `offset`** — An offset at or past the total returns the empty page directly instead of walking and discarding every row.
- **Changed: a Claude generation is named after the step that produced it** — A `!<claude>` step's `label` names its Langfuse generation instead of the literal `Result`, and every command span carries that label as an attribute; a saved Langfuse filter on the old name stops matching new runs.

### Console

- **Added: URL-persisted list state and back navigation** — Process and agent list filters, search, and paging live in the URL (shareable, survive browser Back), and detail pages gained a back button that returns to the filtered list.
- **Changed: execution output shown inline** — Expanding a command on the process detail page shows stdout/stderr tabs directly (with download links), replacing the view-output dialog; start/end times moved next to the exit code.

### Build / CI

- **Changed: Slack release announcement is manual** — The tag pipeline no longer posts the release notes to Slack by itself; play the `announce-release` job to send them.
- **Added: dependency license gate and bundled third-party notices** — Gradle builds fail on non-allowlisted dependency licenses, and the shadow JARs embed a THIRD-PARTY-NOTICES file. See [ADR-0056](docs/adr/0056-dependency-license-policy.md).

## [1.18.1] - 2026-08-27

### Homer

- **Fixed: kept-workspace `workdir` sync-down misses updates and deletions** — The end-of-run `workdir` download from remote runners is now a mirror: modified files overwrite the host copy and files deleted remotely are removed. See [ADR-0055](docs/adr/0055-workdir-sync-down-is-a-mirror.md).

## [1.18.0] - 2026-08-26

### Homer

- **BREAKING: `workspace.purgeRecordAfter` is now `workspace.retain.recordFor`** — A config still using the old key fails agent load with a migration hint. See [ADR-0053](docs/adr/0053-declared-artifacts-ride-the-record-lifetime.md).
- **Added: `workspace.retain.artifacts`** — Declared artifact globs survive the workspace purge in place, served by the same artifact routes until the record dies; bounded per run by the new `server.maxRetainedArtifactBytes`; requires a per-run `workspace.path`; an artifacts dir over 10,000 entries retains nothing (flagged `retainedTruncated`). See [ADR-0053](docs/adr/0053-declared-artifacts-ride-the-record-lifetime.md).
- **Security: artifact reads open with O_NOFOLLOW** — The symlink re-check before opening an artifact was not atomic; a symlink swapped in mid-request can no longer redirect the read at host files.
- **Security: `workspace.path` must resolve to a strict subdirectory** — A `..` segment (rejected at load) or a template collapsing to the workspace root itself (rejected at run start) can no longer point a run — or a later purge — at the workspace root; recursive workspace deletes also refuse the root as defense in depth.
- **Security: artifact size/mtime stats don't follow symlinks** — `GET /artifacts` size pre-flight and the artifact listing now read link-local attributes, so a symlink swapped into the artifacts dir can no longer report a host file's size or existence, and the LangGraph run-status route's identity read now rejects a symlinked artifact.
- **Added: root-anchored `syncExcludes`** — A leading `/` scopes an exclude to the upload root instead of matching the entry name at any depth.
- **Fixed: kubernetes runner first upload under `runAsNonRoot`** — The workspace upload no longer emits a tar entry for the kubelet-owned `workdir` directory (its contents still upload), so GNU tar no longer fails restoring its mtime. See [ADR-0054](docs/adr/0054-kubernetes-workdir-uploads-without-its-tar-entry.md).
- **Security: per-run workspace paths reject symlink components** — A symlink planted at a predictable future per-run path (process ids are monotonic) could alias two runs onto one directory across owner boundaries; workspace path resolution now rejects a path that traverses a symbolic link.

## [1.17.0] - 2026-08-25

### Homer

- **Added: `artifactNameTemplate` agent field** — Templates the autogenerated per-command artifact file names; a template using `{{HMR_CMD_ATTEMPT}}` preserves each retry attempt's output instead of overwriting it.
- **Added: `workspace.purgeRecordAfter` and `server.maxProcesses`** — Keeps a run's record (status, steps, exit codes, cost) after `purgeAfter` deletes its workspace, so a `/processes/{id}` link posted in a ticket keeps resolving; `maxProcesses` makes the retention ceiling configurable. See [ADR-0048](docs/adr/0048-split-record-retention-from-workspace-purge.md).
- **Added: `cooldown` on an agent** — Refuses a start with `429` until `minInterval` since the last run, or `afterFailure` since the last failed one, has elapsed; survives a restart. Homer bounded concurrency and spend but not frequency.
- **Added: `telemetry` block on an agent** — `telemetry.export: OFF` drops the whole trace for that agent's runs (`openTelemetryExport` was previously the only switch and it is server-wide), and `telemetry.traceTags` names inputs whose values become `paramName:value` trace tags, so runs are findable by the ticket / MR / thread they are about. See [ADR-0050](docs/adr/0050-per-agent-telemetry-block.md).
- **Fixed: retry, resume, and debug traces were unnamed, untagged, and unmutable** — Those three handlers build their own root spans outside `AgentRunStarter` and so missed trace naming, subject tags, and `telemetry.export: OFF`; all three now go through the same helper.
- **Fixed: a failed trace export was near-invisible** — Exporter failures were reported only by the OTel SDK's own `java.util.logging` handler, which bypasses logback and names no endpoint; failed batches are now logged at WARN through Homer's logger, and the resolved export destination at startup.
- **Added: `langfuse.projectId` config and `langfuseTraceUrl` on the process status API** — Deep-links a run to its Langfuse trace; blank `projectId` (the default) means no link.
- **Fixed: a continuation could be lost to a momentary rejection** — A fire refused because the target was over its cost cap, or because the process table or run queue was full, ended `FAILED` without starting anything; such fires now retry with backoff, bounded by an attempt budget and the continuation's TTL. See [ADR-0023](docs/adr/0023-completion-continuations.md).
- **Security: dispatch and continuation parameters are masked in API responses** — `/api/v1/dispatches` and `/api/v1/continuations` returned each row's stored parameter values in full, including inputs declared `secret: true`.
- **Security: a value stays masked after its input's `secret` flag changes** — redaction uses the flags recorded when the run started, so editing an agent cannot expose values already collected under `secret: true`, and marking an input secret now also covers records already retained. See [ADR-0052](docs/adr/0052-redaction-metadata-is-captured-with-the-run.md).
- **Fixed: the Kubernetes runner's session-close span started its own trace** — `runner.session.close` was emitted without a parent, so it appeared detached from the run it belonged to.
- **Security: `@SYSENV:`-sourced env values and `HMR_CALLBACK_TOKEN` are masked in telemetry** — Span redaction was built only from `secret: true` inputs, so operator credentials forwarded through `globalEnv` / `agent.env` reached Langfuse in cleartext whenever a run echoed them.

### Console

- **Added: run cost and Langfuse trace link on process detail** — Cost now comes from the ledger via the status API instead of being parsed out of `cmd_N.out`, so it survives the artifact purge; the trace link appears when `langfuse.projectId` is configured.
- **Added: continuations that failed to fire are listed** — The continuations page showed only `PENDING` rows, so a follow-up agent that never started was visible only as a line in the server log; failures now appear with their reason.

### Build / CI

- **Added: feature-atlas publishing to Pages** — Every main-branch pipeline publishes `docs/homer-feature-atlas.html` to the project's Pages site, linked from the README.
- **Added: console image license gate** — `build-homer-console` scans the built image for LGPL-3.0 libvips binaries (`@img/*`, `sharp`) and fails before pushing if any are present.

## [1.16.0] - 2026-08-21

### Homer

- **BREAKING: LangGraph checkpoints are stored per owner** — `<stateDir>/<owner>/checkpoints.sqlite` replaces the single shared store, so a run's thread id can no longer address another principal's checkpoints. See [ADR-0046](docs/adr/0046-owner-scoped-langgraph-checkpoints.md).
- **Added: `annotations` on the kubernetes runner** — Sets operator-declared annotations on every pod the runner creates, enabling annotation-driven cluster tooling such as secret injectors and service meshes. See [ADR-0045](docs/adr/0045-external-secret-delivery.md).
- **Added: LangGraph run resume** — `POST /api/v1/processes/{id}/resume` restarts a failed LangGraph run from its latest checkpoint. See [ADR-0044](docs/adr/0044-langgraph-resume-and-run-introspection.md).
- **Added: LangGraph run status** — `GET /api/v1/processes/{id}/langgraph` reports a run's thread id and per-node workflow status, including per-sub progress (run status plus per-command progress) for parked fan-out nodes.
- **Fixed: LangGraph introspection caps its concurrent subprocesses** — `GET /api/v1/agents/{name}/langgraph` no longer starts one Python interpreter per concurrent request; requests past the limit wait briefly, then report `busy`.
- **Fixed: Langfuse priced Claude runs from its own guess** — Usage went out as flat `usage_details.*` span attributes and the CLI-reported `total_cost_usd` only as text inside `output`, neither of which Langfuse's OTLP mapper reads; a 9-day window showed $0.23 in Langfuse against $3.65 in Homer's own cost ledger. Claude spans now carry `langfuse.observation.usage_details` / `.cost_details` / `.type`, with per-message and per-tool children left as unpriced spans so a run is charged exactly once.
- **Fixed: every trace was named `agent_request`** — Traces now take the agent's name (`langfuse.trace.name`) and a tag with the request source, so Langfuse's traces API can tell runs of different agents apart instead of returning one undifferentiated stream.

### Console

- **Added: LangGraph workflow status + resume** — process detail shows per-node workflow status for LangGraph runs (in the command's execution detail and live on the running command) with per-sub progress for parallel fan-outs — fan-out nodes turn green individually and carry per-command indicator dots in the progress lines and graph nodes — and offers Resume for failed runs.

### Build / CI

- **Fixed: Kotlin compile daemon runs out of memory on a cold build** — `kotlin.daemon.jvmargs=-Xmx2g` replaces the 512m default, which could not compile the backend test sources in one pass.

## [1.15.0] - 2026-08-19

### Homer

- **Added: `extraUploads` on SSH runner config** — Uploads operator-declared host files/folders into each remote workspace and repoints an env var at the remote copy. See [ADR-0042](docs/adr/0042-runner-extra-uploads.md).
- **BREAKING: kubernetes runner drops the shared RWX PVC** — Workspaces now sync into a pod-local emptyDir over exec (gzip'd tar streams), so Homer can run outside the cluster and no RWX storage class is needed; `workspaceVolume` is removed and images must ship `tar`+`gzip`. See [ADR-0043](docs/adr/0043-k8s-sync-workspaces.md).
- **Added: `syncExcludes` and `extraUploads` on the kubernetes runner** — Same semantics as the SSH runner's fields.
- **Security: pod specs no longer carry the workspace env** — Agent secrets stop appearing in pod manifests; commands receive their env per-exec.
- **Fixed: `keepPodOnFailure` never triggered** — Failed runs now mark the session failed, so the pod is kept with its TTL for inspection. Startup failures honor it too.
- **Fixed: console requests blocked by CORS in the local Docker stack** — The example config now pins `server.corsAllowedOrigins` to the local console origin.
- **Fixed: startup-failure pods are cleaned up immediately** — A pod whose status polling fails during startup (e.g. an RBAC 403) is now deleted instead of lingering until the next boot sweep.
- **Fixed: exec-channel loss no longer reported as a command exit code** — The kubernetes client's no-exit-status sentinel (-1975219) now surfaces as an exec channel failure (with pod diagnostics for command execution, a clear channel error for workspace sync) instead of a bogus exit code.
- **Security: ssh sync-down is containment-checked against pre-existing symlinks** — A symlink already present in the local download destination can no longer redirect remote-named files outside the workspace; the transfer fails instead.
- **Security: ssh sync-up skips symlinks escaping the upload root** — A workspace or extraUploads symlink at a host path outside the uploaded folder is no longer dereferenced to the remote host; escaping links are skipped with a warning.

### Build / CI

- **Added: Slack release announcement on tag pipelines** — After a release's images publish, the pipeline posts the tag's CHANGELOG section to the Slack webhook configured in the `SLACK_RELEASE_WEBHOOK_URL` protected CI/CD variable.
- **Changed: branch image builds carry the commit hash** — Manual branch-pipeline image jobs now push an immutable `<branch>-<short-sha>` tag (also baked in as the image version) alongside the moving `<branch>` tag; release-tag builds are unchanged.
- **Fixed: dev compose console never started** — The development override now lifts the console's read-only root filesystem so `npm install` and the Next.js dev server can run.

## [1.14.0] - 2026-08-14

### Homer

- **Security: artifact reads reject non-regular files** — Reading a FIFO/device/directory artifact now returns not-found instead of blocking the request thread.
- **Security: `/stream/agents` reload events filtered by grant** — The SSE agent-reload diff no longer leaks agent names a principal has no grant on.
- **Security: `system:` reserved as a principal-name prefix** — Bearer token config keys can no longer start with `system:`; a token named `system:cron` previously collided with the synthetic cron run owner and gained owner-based visibility/control over all cron-created processes without a grant.
- **Security: dispatch fire re-checks `dispatch.enabled`** — A pending ask-and-dispatch now fails at fire time if its target agent was reloaded to `dispatch.enabled: false` (or replaced) after registration, instead of still launching the disabled target.
- **Security: continuation fire re-checks `dispatch.enabled`** — Completion continuations (single and JOIN) now fail at fire time if the target agent is no longer dispatch-enabled, closing the same stale-authorization gap as the dispatch fix.
- **Security: process retry re-validates stored inputs** — `POST /processes/{id}/retry` now re-runs the current per-input policy over the original run's stored inputs (rejecting since-tightened values with `400` and dropping values for removed inputs) instead of replaying them verbatim past `ParamValidator`.
- **BREAKING: HMAC path-binding required for multi-agent keys** — Startup now rejects an HMAC key whose `payloadTemplate` omits `{{path}}` when its grants reach more than one agent, including grants that name two or more distinct exact agents (previously only `role: admin` or a `*` agent pattern triggered this). Such a body-only signature was replayable across those agents; bind `{{path}}` or narrow the key to a single agent.
- **Security: HMAC replay cache no longer evicts live markers** — Under capacity pressure the replay cache now reclaims only expired entries and fails closed on a new signature, instead of evicting the soonest-to-expire live entry; an attacker can no longer flood the cache to drop a victim's still-fresh replay marker and replay their request.
- **Security: startup warning for unsigned query inputs** — Homer now logs a WARN when an HMAC key can run an agent that reads query-string inputs but its `payloadTemplate` omits `{{query}}`, flagging that a captured signature is replayable with altered query params. Query signing stays opt-in.
- **Security: `runnerCmd` sync paths are containment-checked against symlinks** — `syncToRemote`/`syncFromRemote` paths are now resolved and asserted to stay within the workspace via realpath (not just the lexical `..` check), so a symlink written into the workspace can no longer redirect a sync at arbitrary host files; an escaping path fails the run.
- **Security: Claude cost cap charges failed billable attempts** — A rejected result subtype (`error_max_turns`/`error_during_execution`) and any failed attempt before a later success now charge their reported `total_cost_usd` to the per-process and cap-window ledgers, closing a bypass where an attacker forced failures to spend past the cost cap uncharged.
- **Security: command stdin write no longer bypasses the timeout** — The local and kubernetes shell executors now write a command's stdin on a concurrent job, so a child that never drains a stdin larger than the OS pipe buffer can no longer block the executor thread forever; the TTL/output-cap monitor still fires and kills the process.
- **Security: output cap re-checked after a command exits** — The local, ssh, and kubernetes shell executors now re-check `maxOutputBytes` once a command finishes, so a fast-exiting command that overshoots the cap between the last poll and exit fails instead of leaving an oversized artifact accepted as a clean result.
- **Security: dispatch-bound questions deleted when the dispatch settles** — Dispatched/failed/cancelled/TTL-expired ask-and-dispatch questions are now deleted from SQLite instead of only being marked expired, so these rows no longer accumulate unbounded and exhaust storage over time.
- **Added: `agent.maxQueuedRuns` config** — Under `onMaxParallelRuns: WAIT`, bounds how many runs may queue for a parallel-run slot (default `null` = unbounded); the surplus run fails with `429` instead of parking indefinitely, so one agent's queued runs can no longer pin unbounded process rows and starve global capacity.
- **Security: Claude `--agents` JSON excluded from the second template pass** — Subagent fields are resolved once before JSON-encoding; the encoded `--agents` blob is no longer re-templated afterward, so a caller input that resolves to another `{{VAR}}` can no longer be re-substituted unescaped to break out of a subagent field and inject sibling keys.
- **Security: capacity eviction deletes the evicted run's workspace** — When a terminal process is evicted to free capacity, its on-disk workspace directory is now removed (with the same shared-directory guard as the purge sweep) instead of being orphaned, so repeated evictions can no longer exhaust disk.
- **Security: workspace cleanup is containment-checked before recursive delete** — The purge sweep and capacity eviction now assert the workspace directory resolves inside the configured `workspaceRootPath` (via realpath) before `deleteRecursively`, so a crafted or poisoned persisted `artifacts_dir` can no longer steer the delete at an arbitrary host directory.
- **Security: LangGraph result-fetch failure no longer leaks the capability token** — When `homer-result` exits non-zero, the `homer_langgraph` client now raises a sanitized error instead of an uncaught `CalledProcessError` whose message embedded the `resultRef` token from argv, so a live capability token can no longer reach Homer's process logs.
- **Security: live `claude.event` stream redacts declared-secret values** — The SSE `GET /api/v1/stream/processes/{id}` feed now masks agent-declared `secret` input values in each Claude/Codex event (and the tail's warn logs), matching the telemetry sink; the same NDJSON output was previously redacted for Langfuse but streamed to authorized subscribers in the clear.
- **Security: Kubernetes startup sweep scoped to this instance** — Homer stamps a stable per-instance id (persisted in SQLite, schema v9) as a `homer.app/instance` pod label and scopes the boot-time orphan sweep to it, so a Homer instance restarting can no longer force-delete another instance's running pods in a shared namespace. No configuration required.
- **Security: ReDoS-prone `inputSchema` patterns rejected at load** — An agent whose `inputSchema` carries a catastrophic-backtracking `pattern` (nested unbounded quantifiers or overlapping quantified alternations) is now skipped at load, so a crafted request body can no longer pin a CPU inside the schema validator. See [ADR-0040](docs/adr/0040-inputschema-pattern-redos-load-time-lint.md).
- **Security: artifact listing capped at 10,000 entries** — `GET /api/v1/artifacts/list` and the `list_artifacts` MCP tool now stop walking at a fixed cap and return a `truncated` flag, so a run that writes an enormous number of artifacts can no longer exhaust memory building the listing.
- **Fixed: local and ssh runners reap orphaned processes on kill/timeout** — Commands now launch in their own process group (local via `setsid`; ssh via a `setsid -w` wrapper killed out-of-band over a control channel) so a detached or reparented child no longer survives as an orphan; both fall back to prior behavior where `setsid` is absent. Kubernetes already reaps the whole pod. See [ADR-0041](docs/adr/0041-process-group-reaping-on-kill.md).

### Console

- **Security: question text no longer renders remote images** — Markdown images in agent-authored question text now render their alt text instead of an `<img>`, so opening the inbox can no longer beacon the operator's browser to an attacker URL.
- **Security: link-seeded login endpoint requires explicit trust confirmation** — A `?endpoint=` URL that isn't a saved instance now disables Login until the operator ticks a trust checkbox, so a shared `?endpoint=attacker` link can no longer silently redirect the credential/token POST to an attacker origin. Manually-typed endpoints and saved instances are unaffected.
- **Fixed: running command jumped from top to bottom of the Executions list** — Executions now always render in run order, with the in-flight command last.

### Build / CI

- **Security: image publishing restricted to protected semver release tags** — Tag pipelines build and push Docker images only for protected tags matching `X.Y.Z`; other tags get no build job, so an arbitrary tag can no longer mint or overwrite registry images.
- **Security: root-executed entrypoint and jar no longer homer-writable** — The container image now keeps `/app`, `/app/config`, `homer.jar`, and the root-run `entrypoint.sh` root-owned; only the dirs homer writes at runtime (`/app/workspace`, `/app/agents`, home) are handed to the unprivileged user, so an in-container agent process can no longer overwrite the entrypoint and gain root on the next restart.

## [1.13.0] - 2026-08-12

### Homer

- **BREAKING: `apiAuth` config replaces `endpoints` allowlists with grants** — Bearer tokens, HMAC keys, and password users now declare `role: admin` and/or `grants: [{agents, actions}]`; a config still using `endpoints` fails startup with a migration hint, and `GET /api/v1/auth/me` now returns `{username, role, grants}` instead of `{username, endpoints}`. See [ADR-0037](docs/adr/0037-grants-authorization.md).
- **BREAKING: agent and server-operation routes now enforce grants** — `run`/`debug`/`edit` actions (agent execution, debug, files, creation) and the `admin` role (reload, costs, server status) are now checked per request; a previously-authenticated principal with `grants`/`role` set could reach every route regardless of scope. Agent listing is filtered to agents the caller holds any grant on, and `GET /agents/{name}/langgraph` 404s for agents outside those grants. Process-scoped routes (status, kill, retry, artifacts, questions, dispatches, continuations) now enforce per-process visibility (admin ∪ owner ∪ `read` grant), with single-resource denials returning 404 and lists / the `/stream/processes` SSE filtered to visible rows; the MCP agent-tool list is filtered by `run` grant and MCP-started runs are owner-stamped. Nested-dispatch hops (`RunAgent`/dispatch/continuation/`dispatchNow`) are now gated by root grants ∪ `server.agentGrants` (denial: `RunAgent` exit `255`, dispatch/continuation `FAILED`, registration `403`), and MCP management tools plus `dispatches`/`continuations` `{id}` are now visibility-gated. See [ADR-0038](docs/adr/0038-process-ownership.md) and [ADR-0039](docs/adr/0039-nested-dispatch-authority.md).
- **BREAKING: `server.apiAuth` is now required** — Omitting it fails startup instead of silently running unsecured; running without auth needs the explicit `apiAuth: !<unsecured>` opt-in. See [ADR-0036](docs/adr/0036-explicit-api-auth-opt-in.md).
- **Security: `BASH_ENV` and `ENV` are now reserved env names** — Agents can no longer bind a caller input (or debug env override) to these; a caller-set value was sourced by the `bash -c` that evaluates `when` predicates, running code before the trusted expression.
- **BREAKING: HMAC `timestampHeader` now requires `{{timestamp}}` in `payloadTemplate`** — A key that freshness-checks a timestamp without MAC-binding it fails startup; the unsigned header let captured signatures be replayed with fresh timestamps.
- **Security: result capability token redacted from access/error logs** — `GET /api/v1/callback/results/{token}` carries the token in the URL path; it is now masked in `api.log` and the 5xx error log so a co-located process cannot harvest a live token.
- **Security: result capability token bound to its target agent** — `GET /api/v1/callback/results/{token}` now redeems only for a caller running the continuation's target agent, so a leaked token is useless to a different agent; same-agent cross-segment re-fetch still works. See [ADR-0025](docs/adr/0025-capability-token-result-read.md).
- **Added: `server.dispatch.maxJoinSubs` config** — Caps the sub-agents a single `/callback/joins` request may fan out to (default 32); an over-limit request is rejected with `400`, bounding synchronous subprocess fan-out.
- **Security: duplicate `agent.name` across directories fails deterministically** — Two agent directories declaring the same `agent.name` now both fail to load with an error, instead of one silently shadowing the other in unspecified filesystem-iteration order (a writable dir could shadow a trusted agent).
- **Security: expired dispatches/continuations can no longer fire** — `claim()` now rejects a row whose TTL has lapsed even before the periodic sweep flips it to CANCELLED/EXPIRED, closing the window where an expired dispatch (on answer) or continuation (on watched-process termination) still started its target run.
- **Security: command executors redact declared-secret values in telemetry** — `cmd`/`script`/`runnerCmd`/`langgraph`/`codex` span input/output and captured `output.env` now pass through `SecretRedactor` (previously only `claude` did), so a secret echoed into a command or its stdout no longer egresses to Langfuse in cleartext.
- **Security: session signing secret must be ≥32 characters** — `AuthConfig.Password.sessionSecret`/`sessionSecretPrevious` are rejected at startup if the resolved value is shorter; a short HMAC key made session cookies forgeable.
- **Security: HMAC replay dedup keyed on canonical signature bytes** — Re-cased hex or re-encoded base64 copies of a captured signature can no longer evade the replay cache within the freshness window.
- **Security: SFTP sync-down rejects traversal entry names** — Directory listings from an SSH runner can no longer write outside the local target directory via `../`, absolute, or multi-segment entry names; such names now fail the download.
- **Security: blank HMAC secrets rejected at startup** — A blank `apiAuth` HMAC key secret (literal or via `@SYSENV`) previously made that key's signatures forgeable by any client; config load now fails instead.
- **Security: blank bearer tokens rejected at startup** — A blank `apiAuth` bearer token (literal or via `@SYSENV`) previously let any client authenticate with an empty `Authorization: Bearer` header; config load now fails instead.
- **Security: JSON-escaped secrets now redacted from telemetry** — Secret values containing newlines, quotes, or backslashes were not masked in JSON-encoded Langfuse span attributes (result metadata/output, tool input/output).
- **Security: dispatch and continuation params can no longer set reserved env names** — Only the LangGraph control markers (`HMR_LG_THREAD`, `HMR_LG_RESUME`, `HMR_LG_INTERRUPT`) reach the target run's environment; `PATH`, `LD_*` and other reserved names are dropped, and an `answerParam`/`resumeParam` targeting one is rejected with `400`.
- **Fixed: agentdebug `validate` / `show-commands` ignored `inputs`** — Both commands read only the deprecated `queryParams` alias; agents defined with `inputs` now list their parameters.

### Console

- **Added: role-aware console UI** — The console reads grants from `GET /api/v1/auth/me` and hides controls a scoped user can't use (admin-only Costs/Schedules/Reload; per-agent Edit/Debug; per-process Kill/Retry). UX only — the backend remains authoritative.
- **Security: bump locked `nanoid` to 3.3.18** — Clears the high-severity infinite-loop advisory (GHSA-2v37-7h3g-55p8) that was failing the CI audit gate.

### Build / CI

- **Changed: tag pipelines build and push Docker images automatically** — `build-homer-app`/`build-homer-console` run once the test and e2e stages pass on a tag; branch pipelines keep them as manual, non-blocking jobs.

## [1.12.1] - 2026-08-04

### Homer

- **Fixed: workspace cleanup crash when the workspace dir is missing** — Cleanup treats an externally deleted or never-created workspace as empty instead of throwing.
- **Fixed: lock and parallel-run slot leak on workspace cleanup failure** — Run teardown now releases the agent lock and run slot even when workspace cleanup throws, so later runs no longer hang forever in CREATED.
- **Fixed: workspace purge deleting shared fixed-path workspaces in use** — Purging an expired run no longer deletes a workspace directory another run (including a live one) still references; only the process record is removed.

## [1.12.0] - 2026-07-29

### Homer

- **Added: Apache License 2.0** — The project now ships an explicit `LICENSE` file; previously the license was unstated (READMEs disagreed).
- **Added: `GET /api/v1/costs/monthly` and `GET /api/v1/costs/totals`** — Monthly (UTC) and all-time per-agent Claude cost statistics.
- **Changed: cost statistics persisted in SQLite** — Claude spend is recorded in a durable ledger, so cost-cap windows and statistics survive server restarts. See [ADR-0035](docs/adr/0035-sqlite-backed-cost-ledger.md).

### Console

- **Added: monthly and all-time cost views** — The Costs page shows per-agent costs for a selected UTC month and all-time per-agent totals from the persistent cost ledger.
- **Security: bump locked `postcss` to 8.5.23** — Clears the high-severity path-traversal advisory (GHSA-r28c-9q8g-f849) that was failing the CI audit gate.
- **Fixed: session expiry flooded the backend with requests** — The expired-session recovery re-triggered itself (cache clear → refetch → 401 → recovery) hundreds of times before the login page loaded; it now runs once.

## [1.11.0] - 2026-07-28

### Homer

- **Added: `runnerCmd` command type** — Runs a single command on any configured runner with pre-flight checks and selective file sync to/from the target.

## [1.10.0] - 2026-07-24

### Homer

- **Added: `server.loginRateLimit` config** — The `POST /api/v1/auth/login` rate limit (previously hardcoded at 5/min) is now configurable; raise it for proxied or multi-user deployments where the per-IP bucket collapses to a single shared bucket behind the reverse proxy.
- **Added: `codex` command type** — Runs the OpenAI Codex CLI (`codex exec`) with the same prompt/retry/output-extraction interface as the `claude` command; stdout is the Codex `--json` JSONL event stream. The Docker image now ships the `codex` binary.

### Console

- **Fixed: rate-limited login mislabeled as "invalid credentials"** — A 429 from the login limiter now shows a distinct "too many attempts, please wait" message and a cooldown on the submit button, instead of the credential-rejection error that led users to retry harder and prolong the lockout.
- **Fixed: Claude output rendered raw when hook events preceded init** — Streams from runs with SessionStart hooks start with `system/hook_*` events, which broke the Claude-view detection; it now looks for the init event anywhere in the stream.
- **Fixed: live Claude turn counter over-counted** — The live view counted streamed assistant events rather than messages, showing a turn count far above the final `num_turns`; it now dedupes by message id so the live count tracks the final value.
- **Security: pin patched `sharp` and bump `next`** — An `overrides` entry forces the `sharp` copy Next.js bundles to `^0.35.0` (patched libvips), and `next` is bumped to `16.2.11`, clearing the high-severity production advisories that were failing the CI audit gate.

## [1.9.0] - 2026-07-21

### Homer

- **Added: `ssh` runner backend** — Runs an entire agent on a remote host over SSH, syncing the workspace up before the run and pulling artifacts back after. See [ADR-0032](docs/adr/0032-ssh-runner-backend.md).
- **Changed: Claude `addDir`/`mcpConfig`/`pluginDir` accept a list of values** — a bare string still works; multiple values use the CLI's variadic/repeatable encoding.
- **Added: Claude `file` and `pluginUrl` options** — attach files to the prompt and add plugin marketplace URLs.
- **Added: Claude `includeHookEvents`, `includePartialMessages`, `inputFormat` options** — expose the matching stream-json CLI flags.
- **Fixed: agentdebug `--dry-run` showed an incomplete Claude command** — dry-run now renders the exact argv the executor runs (all options, correct `stream-json` output format) instead of a stale minimal reconstruction.

## [1.8.2] - 2026-07-14

### Homer

- **Fixed: `GET /api/v1/agents` omitted new-style `inputs`** — The agent-list response only carried the deprecated `queryParams`, so agents declaring parameters via `inputs:` returned `null` for both and the console's run dialog showed no parameters. The response now includes `inputs`.

## [1.8.1] - 2026-07-14

### Console

- **Fixed: header logo rendered full-size** — The 512px logo overflowed the header because Tailwind Preflight's `img { height: auto }` overrode next/image's height attribute; the size is now set via a `.h-12` utility class that wins on specificity.

## [1.8.0] - 2026-07-13

### Homer

- **BREAKING: result-capture `envName` is validated at agent load** — A command's `resultEnvParam` envName is rejected if it targets a reserved (`HMR_*`, `PATH`, `HOME`, `LD_*`, …) or malformed name, matching the existing input-`envName` rules; such agents are skipped at load.
- **Fixed: invalid cron no longer disables the scheduler** — An agent with a malformed cron `expression` or unknown `timezone` is rejected at load instead of crashing the scheduler tick (which silently stopped every schedule) and 500ing `GET /api/v1/agents`.
- **Added: `{{query}}` HMAC payload token** — HMAC `payloadTemplate` can bind the raw request query string so query params are covered by the signature. Opt-in: existing templates are unchanged; add `{{query}}` (and include the query string in what the sender signs) to enable it.
- **BREAKING: HMAC keys with a path-agnostic signature and wildcard endpoints are rejected at startup** — A key whose `payloadTemplate` omits `{{path}}` while its `endpoints` contain a wildcard (including the default `["*"]`) now fails boot, because such a signature is replayable across endpoints. **Required step:** for each affected key, either scope `endpoints` to exact paths (no `*`) or add `{{path}}` to `payloadTemplate` (e.g. `"{{method}}.{{path}}.{{body}}"`, updating the sender to match). Signature bytes are otherwise unchanged, so no other client change is needed.
- **Fixed: agent-definition file guard normalizes the path** — Writing or deleting `./agent.yaml` (or other non-canonical forms) is now correctly treated as the agent definition, closing a guard bypass on the agent-files API.
- **Fixed: 5xx error bodies no longer echo internal detail** — Server errors return a generic message (the detail is logged server-side); 4xx validation messages are unchanged.
- **Changed: artifact listing skips symlinks** — `GET /api/v1/artifacts` no longer lists symlinked entries (which would show their target's size/mtime); regular files are unaffected.
- **Changed: agent load warns when caller input feeds a Claude path/mode option** — Inputs templated into `settings`/`addDir`/`mcpConfig`/`pluginDir`/`permissionMode`/etc. now log a steering warning, matching the workspace/lock/RunAgent warnings.
- **Security: author-supplied regexes are evaluated with a time bound** — `resultEnvParam` regex transformers and input-`pattern` matches abort on catastrophic backtracking (ReDoS) instead of pinning a CPU; a timed-out result value is omitted and a timed-out input is rejected.
- **Security: `secret` input values are redacted from Langfuse telemetry spans** — The exported prompt, tool input/output, and result attributes now mask declared secret values (previously only the `input.value`/`input.claudeCmd` spans did).
- **Changed: startup warns on HMAC keys without a `timestampHeader`** — Such a key has no replay window (a captured signature replays indefinitely); the warning surfaces it instead of leaving it silent.
- **Security: HMAC signatures can't be replayed within the freshness window** — When a key sets `timestampHeader`, a bounded in-memory cache rejects a signature presented twice within ±`replayToleranceSec`. Retries must carry a fresh timestamp.
- **BREAKING: `langfuse.serverUrl` must not be plaintext `http://` to a remote host** — When `openTelemetryExport` is `LANGFUSE`, an `http://` URL to a non-loopback host fails startup (the export carries the Langfuse keys and prompt content). Use `https://`, or a `localhost`/`127.0.0.1` collector.
- **BREAKING: legacy `STRING` inputs reject control characters** — In addition to spaces, a `STRING` value containing a newline/tab/other control char is now rejected (guards against newline injection into `when` predicates). Migrate to typed `string` for free text. `ANY` remains unvalidated by design.
- **Security: endpoint allowlist canonicalizes the request path** — `.`, `..`, and empty (`//`) segments are collapsed before matching, so a non-canonical path can't match a prefix it doesn't resolve under.
- **Changed: concurrent SSE/long-poll streams are capped per caller** — A caller past the per-principal limit (100) gets `429` on new `/api/v1/stream/*` connections, bounding resource exhaustion from unbounded stream opens.

### Console

- **Security: pin patched `dompurify`** — An `overrides` entry forces the copy bundled transitively via Monaco to `^3.4.11`, closing the XSS/mXSS advisories affecting `<= 3.4.10`.
- **Security: login warns on a URL-seeded unknown endpoint** — When `?endpoint=` points at a host that is not a saved instance, the login form forces the endpoint field open and warns before credentials are entered (blocks credential-redirection from a link to the trusted origin).
- **Security: console sends hardening response headers** — Every route now returns `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, and a CSP of `frame-ancestors 'none'; object-src 'none'; base-uri 'self'`.
- **Security: runtime-config inline script is properly escaped** — The `window.__HOMER_CONFIG__` bootstrap now emits real `\uXXXX` escapes for `<`, `>`, and the U+2028/U+2029 line terminators; the previous escape was a no-op that never neutralized `</script>`.
- **Security: bearer login is opt-in (`CONSOLE_ALLOW_BEARER`)** — The console is cookie-only by default; the token-login toggle appears only when the operator sets `CONSOLE_ALLOW_BEARER=true` (bearer tokens in `localStorage` are XSS-exfiltratable, unlike the `HttpOnly` session cookie).
- **Security: strict per-route Content-Security-Policy** — Middleware emits a per-request nonce and a strict `script-src 'self' 'nonce-…' 'strict-dynamic'` on every route except the Monaco editor route (`/agents/*/edit`, which needs `'unsafe-eval'` + `blob:` workers). `connect-src` is pinned to `CONSOLE_ALLOWED_API_ORIGINS` when set, otherwise left open for free-form multi-instance login.
- **Security: login endpoint restricted to `CONSOLE_ALLOWED_API_ORIGINS`** — When the allowlist is set, the login form rejects an endpoint whose origin is not on it (reinforcing the URL-seeded-endpoint warning).

### Build / CI

- **Security: example deployment no longer ships a working API token** — The compose stack sources the admin bearer token from a required `HOMER_ADMIN_TOKEN` (startup fails if unset), publishes ports on `127.0.0.1` only, and health-checks the unauthenticated `/api/v1/livez`.
- **Security: dev compose binds the JVM debug port to loopback** — `docker-compose.dev.yml` publishes JDWP `5005` on `127.0.0.1` only (it is unauthenticated remote code execution).
- **Security: container hardening flags** — Both services set `no-new-privileges:true`; the console also drops all Linux capabilities (`cap_drop: [ALL]`) and runs on a read-only root filesystem (writable `/tmp` + `.next/cache` tmpfs only). The backend keeps capabilities and a writable root (its entrypoint needs `CHOWN`/`SETUID`/`SETGID` for the `su-exec` privilege drop, and it runs agents/Claude that write to unpredictable paths).
- **Security: backend entrypoint skips the workspace chown when already owned** — The root-run recursive `chown` now runs only on first setup (workspace not yet `homer`-owned), narrowing the root startup window on normal restarts.
- **Security: CI dependency scanning now gates** — `homer-console-tests` runs `npm audit --omit=dev --audit-level=high`, and the backend `dependency-check` job fails scheduled pipelines instead of being manual + `allow_failure` (still a non-blocking manual job on MRs).
- **Added: `CLAUDE_CODE_VERSION` build arg** — Pins the Claude Code CLI version installed into the backend image (default `latest`), for reproducible builds and matching `server.requireClaudeCodeVersion`.
- **Changed: slimmer console image (455 → ~291 MB)** — `COPY --chown` drops a duplicate app layer, and disabling `next/image` optimization (`images.unoptimized`) prunes the bundled sharp binaries.
- **Changed: slimmer backend image (1.67 → ~1.38 GB)** — `COPY --chown` on the fat jar drops a duplicate ~100 MB layer, and `.dockerignore` now excludes the Python client's local `.venv`/caches (~76 MB) from the build context.
- **Security: base images pinned by digest + Renovate** — All `FROM`/`image:` base images (temurin, node, alpine) are pinned to `tag@sha256:…`; a `renovate.json` keeps the digests bumped via PRs so pinning doesn't freeze security patches.

## [1.7.2] - 2026-07-12

### Homer

- **Added: `server.maxRequestBodyBytes` config** — Requests with bodies over the cap (default 10 MiB) are rejected with 413.
- **BREAKING: input `envName` must be a valid environment variable name** — `^[A-Za-z_][A-Za-z0-9_]*$`; duplicate envNames and duplicate (src, paramName) pairs are rejected at agent load.
- **Fixed: present-but-invalid optional inputs now fail with 400** — Previously they were silently dropped from the run's environment.
- **Fixed: MCP no longer exposes agents with required header inputs** — Their tool calls could never succeed; they are now skipped at exposure with a warning.
- **Changed: internal agent invocation can satisfy `body` inputs** — RunAgent/dispatch/continuation route forwarded params to the target's declared src and validate its `inputSchema`; dispatch and continuation registration now fails fast when a target's required inputs cannot be satisfied.
- **Changed: cron runs use the standard run starter** — Scheduled fires now get cost-cap preflight and telemetry like HTTP/MCP runs, and fail fast when the agent has required inputs.
- **Fixed: retry rejects runs whose stored inputs no longer satisfy the agent** — Previously a reloaded definition with new required inputs was retried with missing env vars.
- **Deprecated: `GET /api/v1/agent/{agentName}`** — Use POST; GET execution will be removed in a future release.
- **Fixed: debug endpoint rejects reserved env overrides** — `env` in the debug request can no longer target `HMR_*` or host-controlled names like `PATH`.
- **Added: `secret` input flag** — Secret input values are redacted in status responses and telemetry spans.
- **Fixed: SSE streams now flush response headers on connect** — Under Netty an idle `/api/v1/stream/*` subscription sent no headers until its first event, so clients could not tell they were subscribed.
- **Added: typed input-param constraints** — New lowercase param types (`string`, `integer`, `number`, `boolean`) with `values`, `pattern`, `min`/`max`, `minLength`/`maxLength`, and `default` constraint fields. See [ADR-0031](docs/adr/0031-typed-input-param-constraints.md).
- **Deprecated: uppercase param types and the `queryParams` alias** — `STRING`/`NUMBER`/`ANY` and `queryParams:` keep working unchanged but log a WARN at agent load; migrate to the typed lowercase params and `inputs:`.

### Console

- **Changed: agents sorted alphabetically** — Agent lists in the console (agents page, schedules, process filter) are now ordered by name.

### Build / CI

- **Added: Homer authoring plugin** — installable Claude Code skill + bundled JSON schema for writing agent definitions.
- **Added: dockerized black-box E2E test suite (`app/e2e`)** — builds the production image and exercises the HTTP contract against a live container.
- **Added: `homer-e2e` pipeline job** — runs the E2E suite automatically on every pipeline, gating the production Dockerfile build.

## [1.7.1] - 2026-06-26

### Homer

- **Changed: `langfuse` config is now optional** — The block and its `serverUrl`/`publicKey`/`secretKey` fields default to empty strings; omit it when not exporting to Langfuse.
- **Added: API access log split into `api.log`** — per-request access lines now go to a rolling `api.log` (10MB×3) separate from `app.log`; high-frequency health/heartbeat/livez and SSE stream endpoints are excluded. Log directory via `HOMER_LOG_DIR`, root level via `HOMER_LOG_LEVEL`.
- **Fixed: spurious ERROR log on every CORS preflight** — security headers are no longer appended to already-completed preflight responses, which logged an `UnsupportedOperationException` per `OPTIONS` request.

## [1.7.0] - 2026-06-25

### Homer

- **Fixed: `agent` command dispatch under session-cookie auth** — RunAgent now dispatches in-process instead of self-calling the HTTP API, so it works without a forwarded bearer token. See [ADR-0030](docs/adr/0030-runagent-in-process-dispatch.md).
- **BREAKING: removed `agent.cronAuthToken`** — In-process dispatch needs no forwarded bearer token, so the field is dead; remove it from agent definitions. See [ADR-0030](docs/adr/0030-runagent-in-process-dispatch.md).
- **BREAKING: removed `server.dispatch.authToken`** — In-process dispatch needs no forwarded bearer token, so the field is dead; remove it from server config. See [ADR-0030](docs/adr/0030-runagent-in-process-dispatch.md).

## [1.6.3]

### Homer

- **Added: `lockStrategy.acceptParentLock` agent field** — A sub-agent whose resolved lock key is already held by an ancestor in its `RunAgent` flow skips lock acquisition, so a flow cannot self-collide on a shared key. See [ADR-0029](docs/adr/0029-flow-scoped-lock-passthrough.md).

## [1.6.2]

### Homer

- **Added: `cookieSameSite` config for password auth** — Sets the session cookie's `SameSite` attribute (`lax` default, `none`/`strict`); `none` enables cross-origin cookie auth and requires `cookieSecure`. See [ADR-0028](docs/adr/0028-cross-origin-cookie-auth-samesite.md).

### Console

- **Added: multi-instance console** — Enter a backend endpoint and auth mode on the login screen and switch the active Homer instance from the header picker; bearer tokens are namespaced per instance. See [ADR-0028](docs/adr/0028-cross-origin-cookie-auth-samesite.md).

## [1.6.1] - 2026-06-22

### Homer

- **Added: `GET /api/v1/agents/{name}/langgraph` endpoint** — Returns each LangGraph command's workflow as Mermaid for console visualization. See [ADR-0027](docs/adr/0027-langgraph-graph-introspection.md).

### Console

- **Added: workflow-graph diagram on agent detail** — Renders each LangGraph command's graph as a Mermaid diagram.

### Build / CI

- **Build / CI: fix `build-homer-app` image build context** — Build from the repo root with `-f app/Dockerfile` so the `app/` and `clients/python` COPY sources resolve (matches `docker-compose.yml`).

## [1.6.0] - 2026-06-17

### Homer

- **BREAKING: `databasePath` config now required** — Points Homer at the SQLite database for durable process + question state; existing configs must add it. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Added: SQLite persistence for processes and questions** — Process records and operator questions survive restarts; SQLite is the authoritative store. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Added: startup reconciliation marks interrupted runs `UNKNOWN`** — Processes left `CREATED`/`WORKING` by a restart become terminal `UNKNOWN` and their open questions are expired. See [ADR-0020](docs/adr/0020-durable-state-in-sqlite.md).
- **Added: human-in-the-loop questions** — Running commands can ask the operator a question and wait for the answer (injected `homer-ask` shell helper, opt-in MCP tools, new question/answer API endpoints); new `server.questions` config block. See [ADR-0019](docs/adr/0019-human-in-the-loop-questions.md).
- **Added: ask-and-dispatch** — Running commands can register a question that starts a target agent when answered (`POST /api/v1/callback/dispatches`, operator `GET`/`DELETE /api/v1/dispatches` endpoints); answers fire the dispatch and question responses carry a `dispatch` annotation; target agents opt in via `agent.dispatch.enabled`. See [ADR-0021](docs/adr/0021-ask-and-dispatch.md).
- **Added: `server.dispatch` config** — Server-level token + caps/TTL for ask-and-dispatch dispatched runs.
- **Added: `server.langGraph.stateDir` config** — Durable directory for LangGraph SQLite checkpoints, kept outside the per-process workspace; defaults to a `langgraph/` subdirectory beside `databasePath`.
- **Added: `!<langgraph>` command type** — Thin launcher that runs the Python `homer_langgraph` runtime, injecting the resolved state dir; agents using it must set `dispatch.enabled: true`. See [ADR-0024](docs/adr/0024-langgraph-command-type.md).
- **Added: `homer_langgraph` Python runtime** — Durable LangGraph human-in-the-loop runtime with pure `homer_interrupt`/`homer_fanout`/`homer_dispatch` gate helpers; resumes parked threads via ask-and-dispatch and completion continuations, resolving parallel multi-interrupt parks one per resume cycle.
- **Added: `homer-dispatch` helper + `POST /api/v1/callback/runs`** — Running commands can start an opt-in agent immediately, fire-and-forget, with no operator question (fresh chain; caller recorded as referer). See [ADR-0022](docs/adr/0022-script-driven-immediate-dispatch.md).
- **Added: completion continuations** — `POST /api/v1/callback/runs` accepts `continueAgent`/`continueThread`/`continueParams`/`resumeParam`/`resultJsonPath`/`ttlSec` to start a target agent when the dispatched sub finishes, carrying its result (`continueParams` go to the continuation run, not the sub); operator `GET`/`DELETE /api/v1/continuations` endpoints. See [ADR-0023](docs/adr/0023-completion-continuations.md).
- **Added: read-by-reference for large continuation results** — Over-cap sub results resume with a `resultRef` capability token; `GET /api/v1/callback/results/{token}` (and the `homer-result` helper) fetch the full result, gated by `server.dispatch.resultTokenTtlSec`. See [ADR-0025](docs/adr/0025-capability-token-result-read.md).
- **Added: parallel fan-out join continuations** — `POST /api/v1/callback/joins` (and the `homer-join` helper) start N subs at once and resume the parent a single time once the last finishes, carrying all results keyed by interrupt id. See [ADR-0026](docs/adr/0026-join-continuations.md).
- **Added: `homer_fanout(group=)` parallel mode** — Fan-out interrupts sharing a `group` resolve as one join round-trip instead of one resume per sub; ungrouped fan-out keeps the sequential path. See [ADR-0026](docs/adr/0026-join-continuations.md).
- **Added: `homer-dispatch` continuation flags** — `--continue-agent`, `--continue-thread`, `--continue-param`, `--resume-param`, `--result-jsonpath`, and `--ttl` register a continuation that resumes when the dispatched sub reaches a terminal state (`--continue-param` targets the resumed parent, not the sub). See [ADR-0023](docs/adr/0023-completion-continuations.md).
- **Added: MCP server endpoint at `/mcp`** — Streamable HTTP MCP endpoint exposing opted-in agents as MCP tools plus `get_run_info` / `list_artifacts` / `get_artifact` / `cancel_run`; gated by `server.mcp.enabled` (default off). See [ADR-0018](docs/adr/0018-mcp-server-endpoint.md).
- **Added: `agent.mcp.enabled` opt-in flag** — Agents are exposed as MCP tools only when their definition sets `mcp: { enabled: true }`.
- **Added: `server.mcp` config block** — `enabled`, `maxWaitSec` (clamp for the agent tools' `_wait` argument), and `maxInlineArtifactBytes` (`get_artifact` inline cap).
- **BREAKING: `dangerouslySkipPermissions` now defaults to `false`** — Claude commands require explicit opt-in to run without the permission gate. See [ADR-0017](docs/adr/0017-claude-skip-permissions-default.md).
- **BREAKING: process kill is now `DELETE /api/v1/kill/{id}`** — was `GET`; destructive actions no longer over GET. See [ADR-0016](docs/adr/0016-cookie-auth-csrf-protection.md).
- **Security: CSRF protection for cookie/console auth** — session-cookie-authenticated requests require an `X-Homer-CSRF` header; token/HMAC webhook callers are unaffected. See [ADR-0016](docs/adr/0016-cookie-auth-csrf-protection.md).
- **Security: CORS no longer reflects arbitrary origins with credentials** — credentialed CORS requires explicit `corsAllowedOrigins`; a Password auth scheme without it now fails startup. See [ADR-0016](docs/adr/0016-cookie-auth-csrf-protection.md).
- **Security: workspace paths confined to the workspace root** — `workspace.path` templates (including values from query params) that resolve outside `workspaceRootPath` are now rejected.
- **Fixed: killed processes record a KILLED history entry** — Process history previously omitted the kill transition, so status timestamps for killed runs were stale.
- **Fixed: kill racing a failing command no longer reports FAILED** — A process killed while its failure was being recorded could end up with status FAILED instead of KILLED.
- **Fixed: agentdebug tail output for CRLF line endings** — CRLF-terminated command output no longer produces spurious blank lines.

### Console

- **Added: Questions inbox page** — Unified `/questions` page lists all open questions; dispatch-bound ones show the target agent and the started run after answering.
- **Added: cancel pending dispatch from the questions inbox** — Cancel button on open ask-and-dispatch questions expires the question and stops the target agent from starting.
- **Added: Continuations page** — `/continuations` lists pending completion continuations (watched run, target agent, origin) with a cancel action for pending ones.
- **Added: Q&A panel and waiting-for-input badges for agent questions** — Process detail shows agent questions (Markdown, optional answer buttons) and submits the operator's answer live; process rows and the sidebar show open-question badges.
- **Changed: console sends `X-Homer-CSRF` + kills via `DELETE`** — matches the backend CSRF/kill changes.
- **Fixed: execution-details rows are accessible disclosure buttons** — Expand/collapse now lives on the header row only; nested view/download buttons no longer sit inside a button role.

### Build / CI

- **Build / CI: added Exposed + sqlite-jdbc** — New `exposed-core`, `exposed-jdbc`, and `sqlite-jdbc` dependencies back the durable-state layer.
- **Build: bundle `homer_langgraph` Python runtime in the backend image** — enables the `!<langgraph>` command type; backend build context moved to the repo root.

## [1.5.7]

### Build / CI

- **Build / CI: slimmed the runtime image** — Removed interactive-only `vim` and `mc` from the backend image; the alpine toolchain agents rely on is unchanged. See [ADR-0015](docs/adr/0015-runtime-image-base-and-toolchain.md).

## [1.5.6]

### Homer

- **Added: `{{VAR}}` templating in Claude command options** — Option values resolve workspace env placeholders (e.g. `systemPromptFile: "{{HMR_AGENT_DIR}}/system.md"`), the same way the prompt does.

## [1.5.5]

### Homer

- **BREAKING: Claude `cmd_N.out` is now NDJSON, not a JSON array** — Tooling that reads the raw Claude artifact now gets one JSON event per line. See [ADR-0013](docs/adr/0013-stream-claude-events-ndjson-artifact.md).
  - **NOTE: `jsonPath` `resultEnvParam` against Claude stdout** — Homer re-assembles Claude's NDJSON stdout into a JSON event array, so `src: STD_OUT` `jsonPath` transformers work again. See [ADR-0013](docs/adr/0013-stream-claude-events-ndjson-artifact.md).
- **Added: live Claude event streaming** — Claude events are broadcast over `GET /api/v1/stream/processes/{id}` as `event: claude.event` while a Claude command runs. See [ADR-0013](docs/adr/0013-stream-claude-events-ndjson-artifact.md).
- **Added: `server.claudeStreamPollMs` config** — Polling interval (ms) for the live Claude event tail. Default 150.
- **Added: `server.perCallerRateLimit` config** — Per-caller token-bucket rate limit on authenticated API routes, keyed on auth identity; layered under the global limiter. See [ADR-0014](docs/adr/0014-per-caller-rate-limiting.md).
- **Added: `HMR_AGENT_DIR` system env var** — Every command receives the absolute path to its agent's own config directory, for reading files shipped alongside the agent definition.

### Console

- **Added: live Claude event view** — The output dialog renders Claude Code events live while a Claude command runs, and renders completed runs from the NDJSON artifact.

## [1.5.4]

### Homer

- **Added: `server.maxOutputBytes` cap on command stdout / stderr** — Each command's `cmd_N.out` / `cmd_N.err` is killed past the cap (default 10 MiB) with `[HOMER] Output cap exceeded` written to stderr; per-command `ExecutionInfo.outputTruncated` surfaces the kill reason.
- **Added: username + password authentication with `HttpOnly` session cookies** — New `AuthConfig.Password` scheme; cookies signed with `sessionSecret`, optional `sessionSecretPrevious` for zero-downtime rotation. New `./agentdebug.sh hash-password` Clikt subcommand and `/api/v1/auth/login` · `/auth/logout` · `/auth/me` routes. See [ADR-0011](docs/adr/0011-cookie-based-console-auth.md).
- **Added: `server.requireClaudeCodeVersion` config** — Pins Homer to a specific Claude CLI version; non-matching runs fail without retry.
- **Added: trace-context propagation to the Claude CLI** — Each Claude subprocess receives the active span as a W3C `OTEL_TRACE_PARENT` so its telemetry joins the same Langfuse trace.
- **Added: HMAC body-signature authentication** — New `AuthConfig.Hmac` and `AuthConfig.Combined` auth modes for webhook signature verification. See [ADR-0010](docs/adr/0010-auth-schemes.md).
- **Added: agent inputs from JSON body or HTTP headers** — Each `Param` gains `src: query | body | header`. `Agent.queryParams` renamed to `Agent.inputs` (old name accepted as a deprecated alias).
- **Added: per-agent `inputSchema` webhook payload validation** — An optional JSON Schema on an agent validates the JSON request body before a process is spawned; a violation returns 422. See [ADR-0012](docs/adr/0012-webhook-payload-schema-validation.md).
- **Added: `HOMER_LOG_LEVELS` env override for log levels** — Re-targets slf4j-simple per-logger levels at runtime (e.g. `homer.app.executors.ProcessExecutor=trace`) without editing the bundled properties file; a `-D` system property still wins.
- **Added: graceful shutdown drain** — On SIGTERM the server waits up to `server.shutdownDrainSec` (default 30s) for in-flight runs.
- **Added: response hardening headers on every reply** — `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `Content-Security-Policy`.
- **Added: loopback-token check on `X-Homer-Invocation-Chain`** — External callers can no longer spoof the recursion-chain header.
- **Added: reserved `envName` rejection on agent inputs** — Inputs targeting `HMR_*`, `PATH`, `LD_*`, and friends are rejected at agent load.
- **Added: `server.maxArtifactBytes` cap on artifact downloads** — Oversized files return `413`. Default 100 MiB.
- **Added: `server.maxConfigFileBytes` cap on agent / config files** — Oversized agent files skipped at load; write API returns `400`. Default 1 MiB.
- **BREAKING: liveness and readiness probes split** — New `GET /api/v1/livez` and `GET /api/v1/readyz` (unauthenticated) replace `/heartbeat` for k8s probes. `/health` remains as the rich JSON status endpoint.
- **BREAKING: child processes no longer inherit the full host env** — Only `PATH` / `HOME` / `LANG` / `TZ` forward by default; configurable via `parentEnvAllowlist`.
- **BREAKING: agent directory and `agent.name` reject spaces** — Allowed character set is `^[a-zA-Z0-9_-]+$`.
- **Changed: `AgentFilesService.resolveSafe` uses the shared `PathSafety` helper** — Same containment check as `ArtifactService`.
- **Changed: Claude retry backoff now adds full jitter** — The exponential delay gains a random `[0, baseDelayMs)` component so concurrent retriers don't reconverge on the same instant.
- **Changed: `agentdebug validate` now runs JSON-schema validation** — Checks the agent against the same bundled `homer-agent.schema.json` the console and test backstop use, so local validation matches the shipped contract.
- **Fixed: cost-cap overshoot is now detected and logged** — When the pre-flight cost check is raced and a window is pushed past its cap, the overshoot is counted and surfaced via a throttled WARN log. See [ADR-0007](docs/adr/0007-cost-meter-as-in-memory-soft-throttle.md).
- **Fixed: dropped SSE events are now detected and logged** — A slow stream consumer that falls behind the broadcaster buffer previously lost events silently; drops are now counted and surfaced via a throttled WARN log.
- **Fixed: killed processes can no longer be overwritten by a late `FINISHED` write** — Happy-path completion goes through a guarded CAS.
- **Fixed: `ShellExecutor` / `KubernetesShellExecutor` propagate cancellation** — TTL-killed and cancelled commands no longer look like success.

### Console

- **Added: a11y enforcement on every commit** — `eslint-plugin-jsx-a11y` at lint time and `vitest-axe` at test time catch missing accessible names and other axe-detectable WCAG AA violations.
- **Added: page-level error boundaries** — A render error in one dashboard page now shows a recoverable error card while the header/sidebar stay usable, with a last-resort boundary for the rest of the app.
- **Changed: agent file list is cached between visits** — The agent edit page no longer refetches its file listing on every navigation; it is cached like the agents list and refreshed on save/delete.
- **BREAKING: default `auth.mode` is now `cookie`** — Console posts to `POST /api/v1/auth/login` and sends every request with `credentials: 'include'`. Bearer-only deployments must set `CONSOLE_AUTH_MODE=bearer`. See [ADR-0011](docs/adr/0011-cookie-based-console-auth.md).
- **Added: run-agent dialog groups inputs by source** — Separate sections for Query / Body / Headers; curl export reflects the same shape.
- **Fixed: 401 responses log out instead of looping** — Both REST and SSE 401s clear the token and redirect to `/login?expired=1`.
- **Fixed: SSE streams reconnect with exponential backoff** — Up to 10 retries from 1s to 30s; 401 still triggers logout.
- **Security: cleared high-severity npm advisories** — Patched the Next.js / picomatch / postcss advisory chain; two non-reachable moderate `dompurify` (Monaco) advisories accepted as documented residual risk.

### Homer [1.5.3]

- **Added: optional `when` predicate on commands** — Bash-`test`-style expressions gate command execution at runtime. See [ADR-0009](docs/adr/0009-conditional-command-execution-via-bash-predicates.md).

### Console

- **Added: skipped-command rendering on process views** — Commands skipped by `when` predicates render with a greyed-out icon and skip reason.

### Homer [1.5.2]

- **Added: `GET /api/v1/costs` endpoint** — Returns the in-memory `CostMeter` snapshot. See [ADR-0007](docs/adr/0007-cost-meter-as-in-memory-soft-throttle.md).
- **Added: rolling-window cost caps (`server.costLimits`)** — `globalUsdPerMin` and `perAgentUsdPerDay` reject requests with `429` when exceeded. See [ADR-0007](docs/adr/0007-cost-meter-as-in-memory-soft-throttle.md).
- **Added: per-process Claude cost tracking on `/api/v1/process/{id}`** — `ProcessInfo.costUsd` sums `total_cost_usd` from each successful Claude command.
- **Added: per-agent retry policy on the `claude` command** — Optional `retry: { attempts, baseDelayMs, maxDelayMs }` with exponential backoff.
- **Added: fail-closed Claude output validation** — Unparseable stdout on exit 0 throws `MalformedLlmOutputException` (`502 Bad Gateway`).
- **Added: recursion guard for `RunAgent` chains** — `X-Homer-Invocation-Chain` and `server.maxAgentInvocationDepth` (default 5) detect cycles and bound depth. See [ADR-0008](docs/adr/0008-recursion-guard-via-invocation-chain-header.md).
- **Fixed: `ProcessManager` refuses new processes at capacity** — Returns `503` instead of silently exceeding the bound.
- **Fixed: bounded timeouts on the internal RunAgent HTTP client** — 30s request / 5s connect / 30s socket.
- **Fixed: per-call `timeout` on `RunAgent` clamped to the client ceiling** — Effective timeout exposed as `agent.timeoutMs` on telemetry.

### Console

- **Added: `/costs` page** — Global and per-agent Claude spend against configured caps. Polls every 5s.

## [1.5.1]

### Homer

- **Added: broader Claude CLI coverage on `ClaudeCmdOptions`** — Many new fields map directly to headless-relevant CLI flags. `--dangerously-skip-permissions` is suppressed when `permissionMode` is set.
- **BREAKING: `options.agents` is now a map of subagent definitions** — Was `List<String>` (a non-functional wire format). Rewrite agents using the new map shape.

## [1.5.0] - 2026-05-04

### Homer

- **Added: `writable` flag on agent metadata** — `GET /api/v1/agents` returns whether each agent's directory accepts writes.
- **Added: `GET /api/v1/schemas/agent`** — Serves the bundled agent JSON schema.
- **Added: agent file CRUD API** — `GET` / `PUT` / `DELETE /api/v1/agents/{name}/files/{path...}`, plus `POST /api/v1/agents` to create. Path-escape guarded; schema-validated on write. See [ADR-0004](docs/adr/0004-agent-file-editing-api.md).
- **Added: single-command debug runs** — `POST /api/v1/agents/{name}/debug` executes a selected command of an agent.
- **Fixed: `/stream/processes/{id}/logs` replays existing content on fresh connections** — Was starting from `path.length()`, hiding output from short-running commands.
- **Fixed: debug runs tolerate missing required query params** — Debug endpoint uses `enforceRequired = false`; live endpoint unchanged.

### Console

- **Added: agent editor page** — `/agents/[name]/edit` with file tree, Monaco editor (YAML/JSON schema validation), and per-command debug panel.
- **Added: YAML autocomplete in the agent editor** — `monaco-yaml` wired into the existing JSON-schema pipeline.
- **Added: new-agent flow** — Dialog on the agents list creates a directory with a minimal template.
- **Added: edit affordance gated on writability** — Edit button shown only when `agent.writable === true`.
- **Added: single-command debug panel** — Per-command Run rows in the editor, with live stdout/stderr streaming.
- **Added: debug panel recalls last params** — Re-run a command with the previous debug run's env and query params.

## [1.4.1] - 2026-04-28

### Homer

- **Added: `securityContext` on the kubernetes runner** — Pod- and container-level security context, required for PSA `restricted` namespaces.

## [1.4.0] - 2026-04-24

### Homer

- **Added: configurable runners (`local`, `kubernetes`)** — Commands can run in ephemeral k8s pods sharing a workspace PVC. See [ADR-0003](docs/adr/0003-runners-as-execution-backends.md).

### Console

- **Added: runner state on process views** — Process detail shows alias, pod name, phase, and timeline; list shows a phase-colored runner column.

## [1.3.0]

### Homer

- **Added: artifact listing endpoint** — `GET /api/v1/artifacts/list?processId=N` returns the per-process artifact manifest.
- **Added: streaming API (Server-Sent Events)** — `GET /api/v1/stream/processes`, `/stream/agents`, and `/stream/processes/{id}/logs` replace polling. Single-instance only.
- **Added: CORS allowlist via `server.corsAllowedOrigins`** — Empty value preserves prior `anyHost()` behavior.
- **Changed: `cmd` field accepts a list of arguments** — Scalar string form still works; tokenized with POSIX quote handling.
- **Added: bounded in-memory process map** — Soft cap (10 000) with terminal-first eviction.
- **Added: retry a past process** — `POST /api/v1/processes/{id}/retry`; `inputParams` added to `ProcessInfo`; `agentName` filter added to `/status/all`.
- **Changed: Claude telemetry uses typed event handling; non-success results now exported** — Raw JSON preserved end-to-end for span attributes.
- **Fixed: process status history preserved in telemetry spans** — Was overwritten on every transition.
- **Fixed: non-blocking delay in Claude telemetry** — Replaced `Thread.sleep` with `kotlinx.coroutines.delay`.
- **Fixed: reload race in `ParallelRunsManager`** — `releaseSlot` takes the semaphore from `acquireSlot` rather than looking it up by name.
- **Fixed: `cleanAfterFinish` had no effect when a run produced no artifacts** — `workdir/` is now always deleted when the flag is set.
- **Fixed: template placeholder order sensitivity in `TextCompiler`** — Single-pass `Regex.replace` resolves all placeholders simultaneously.
- **Changed: `LockManager` warns on unknown-lock and non-holder releases** — Was silent.
- **Changed: cron schedules parsed once per unique expression** — Cached `ExecutionTime`.
- **Security: `dangerouslySkipPermissions` defaults to `true`** — Documented in `AGENT.md`. Use isolated environments.

### Console

- **Added: artifact picker replaces blind-path input** — "Download Artifact" lists every file under the process artifacts dir.
- **Added: realtime updates via SSE** — Process list, detail, and agents pages update via SSE invalidation.
- **Added: view live output for the running command** — "View" button on the in-flight command attaches the SSE log tail.
- **Added: retry button on processes** — On terminal rows in the list and detail header.
- **Added: per-agent execution history** — New `/agents/[name]` route with run history and a Run button.
- **Added: pragmatic a11y and responsive pass** — `aria-label`s, `DialogTitle` / `DialogDescription`, `<main>` landmark, stacked-card fallback below `md`.
- **Added: multiple UI themes** — Light, Dark, System, Terminal (phosphor-green), Windows 95.

### Build / CI

- **Added: quality gates (ktlint + Kover) wired into the build** — `check` runs `ktlintCheck`; `koverVerify` enforces a coverage threshold. CI runs `check koverVerify`.
- **Added: OWASP dependency vulnerability scanning** — Pipeline fails on known CVEs.
- **Changed: Docker image slimmed to Java 25 only** — JDKs 17 and 21 removed from the base image.

## [1.2.4] - 2026-04-15

### Homer

- **Added: agent hot reload** — `POST /api/v1/agents/reload` rescans `agentsSearchPath` and returns the diff. CREATED processes for updated/removed agents are killed; WORKING processes left running.
- **Added: Langfuse prompt versioning** — Claude commands can pin a `version` or `label` on a Langfuse prompt.
- **Changed: `WAIT` lock strategy uses event-based wake-up** — `CompletableDeferred` queue replaces the 1s poll.
- **Changed: `RunAgent` applies the command `timeout` to the outbound HTTP request** — Previously only governed the executor coroutine.
- **Fixed: `STD_ERR` result mapping** — Was reading from stdout.

### Console

- **Added: reload button on Agents page** — Calls the reload endpoint and shows the diff inline.

## [1.2.3] - 2026-04-14

### Homer

- **Added: `finishExitCode` on commands** — Exit with this code marks the entire job `FINISHED` and skips remaining commands.

## [1.2.2] - 2026-04-14

### Homer

- **Added: per-endpoint bearer token authorization** — Tokens can be scoped to endpoint patterns (`*`, prefix, exact). Old flat-string format still supported.
- **Added: global API rate limiting** — Configurable via `server.rateLimit`. Defaults: 1000 req / 60s.
- **Changed: TTL timeout logged to stderr** — `[HOMER]` diagnostic line appended to `cmd_N.err` on timeout; user kills produce no message.

## [1.2.1] - 2026-04-11

### Homer

- **Fixed: kubernetes compatibility** — Entrypoint detects when already running as a non-root user (via `securityContext.runAsUser`) and skips the `su-exec` privilege drop.

## [1.2.0] - 2026-04-10

> **Breaking changes** — existing deployments must update config, agent files, and API clients. See migration table below.

### Homer

- **BREAKING: "skill" → "agent" renamed throughout** — Affects config keys, env vars, API routes, agent definition filenames, and the debug CLI. See [ADR-0005](docs/adr/0005-rename-skills-to-agents.md).

#### Migration

| What | Before | After |
|------|--------|-------|
| Config key | `skillsSearchPath` | `agentsSearchPath` |
| Env var injected into commands | `HMR_SKILL_NAME` | `HMR_AGENT_NAME` |
| API — list agents | `GET /api/v1/skills` | `GET /api/v1/agents` |
| API — execute agent | `GET/POST /api/v1/skill/{name}` | `GET/POST /api/v1/agent/{name}` |
| Agent definition filename | `skill.json` / `skill.yaml` | `agent.json` / `agent.yaml` |
| Debug CLI wrapper | `skilldebug.sh` | `agentdebug.sh` |
| Debug CLI Gradle module | `:skilldebug` | `:agentdebug` |

To migrate agent definition files:
```bash
find example/agents -name "skill.json" -exec sh -c 'mv "$1" "$(dirname "$1")/agent.json"' _ {} \;
find example/agents -name "skill.yaml" -exec sh -c 'mv "$1" "$(dirname "$1")/agent.yaml"' _ {} \;
```

### Console

- **Changed: agents page** — `/agents` (previously `/skills`); all related UI renamed.

## [1.1.2] - 2026-04-01

### Homer

- **Added: workspace auto-purge** — `purgeAfter` deletes the workspace and removes the process record after a duration from terminal state. Default 5d.

### Console

- **Added: purge countdown in process table** — Live countdown column on the processes list.
- **Added: purge time in process detail** — Shown in the Status Timeline card.

## [1.1.0] - 2026-03-30

### Homer

- **Added: show currently running command** — Process status reports the active command and when it started.
- **Added: agentdebug CLI** — Standalone tool (`./agentdebug.sh`) with `list`, `validate`, `show-commands`, and `run` (`--dry-run` supported). See [ADR-0006](docs/adr/0006-agentdebug-module.md).
- **Added: CORS support** — API accepts cross-origin requests.
- **Added: Langfuse prompt templates** — Claude commands can reference Langfuse templates in agent config.

### Console

- **Added: structured Claude output view** — Purpose-built view for Claude command stdout: summary bar (model, status, cost, tokens, duration, turns), collapsible conversation timeline, and Claude / JSON / Raw tabs.
