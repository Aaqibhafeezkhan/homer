# Architecture Decision Records

This directory collects **Architecture Decision Records (ADRs)** for Homer —
short, dated notes that capture architectural choices and the reasoning
behind them. They exist so that a future contributor (or future-you) can
reconstruct *why* the system looks the way it does without having to
reverse-engineer it from commits.

See [ADR-0001](0001-record-architecture-decisions.md) for the rationale
behind using ADRs at all.

## When to write one

Write an ADR when a decision meets **any** of these:

- It constrains future work (e.g. "errors flow through a single HTTP
  handler", "agents are filesystem-only").
- It was non-obvious or contested — another reasonable option existed.
- It would otherwise only live in a PR description that nobody will find
  six months later.

Routine implementation choices, bug fixes, and refactors do **not** need
an ADR.

## Format

All ADRs use the [MADR](https://adr.github.io/madr/) template —
see [`0000-template.md`](0000-template.md). Copy that file to start a new
one.

### Filename

`NNNN-kebab-case-title.md`, where `NNNN` is the next unused four-digit
number. Numbers are never reused, even for deleted or superseded ADRs.

### Status

One of:

- `proposed` — under discussion, not yet adopted.
- `accepted` — active decision, currently in force.
- `superseded by ADR-NNNN` — replaced by a later ADR. **Do not edit the
  old ADR's decision in place**; write a new ADR and flip the status of
  the old one.
- `deprecated` — no longer applies, and nothing replaced it (rare).

## Adding a new ADR

1. Copy `0000-template.md` to `NNNN-your-title.md` (next unused number).
2. Fill in the front matter (`status: proposed`, today's date, deciders).
3. Write Context and Decision Outcome. Add Decision Drivers, Considered
   Options, and Pros and Cons when the decision had real alternatives.
   Consequences may be nested under Decision Outcome or its own top-level
   section.
4. Add a line to the [Index](#index) below.
5. Open a PR. Merging the PR is what flips `proposed` → `accepted`.

## Index

| # | Title | Status |
| --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | accepted |
| [0002](0002-unified-error-model-at-http-boundary.md) | Unified error model at the HTTP boundary | accepted |
| [0003](0003-runners-as-execution-backends.md) | Runners as pluggable execution backends | accepted |
| [0004](0004-agent-file-editing-api.md) | Agent file editing API | accepted |
| [0005](0005-rename-skills-to-agents.md) | Rename "skills" to "agents" throughout the codebase | accepted |
| [0006](0006-agentdebug-module.md) | Carve `agentdebug` out as a separate Gradle module | accepted |
| [0007](0007-cost-meter-as-in-memory-soft-throttle.md) | `CostMeter` as an in-memory soft throttle, enforced at the HTTP boundary | accepted |
| [0008](0008-recursion-guard-via-invocation-chain-header.md) | Bound `RunAgent` recursion via the `X-Homer-Invocation-Chain` header | accepted |
| [0009](0009-conditional-command-execution-via-bash-predicates.md) | Conditional command execution via bash predicates | accepted |
| [0010](0010-auth-schemes.md) | Allowed API auth schemes — Bearer / HMAC / Combined / Unsecured | accepted |
| [0011](0011-cookie-based-console-auth.md) | Cookie-based console authentication (username + password) | accepted |
| [0012](0012-webhook-payload-schema-validation.md) | Webhook payload schema validation | accepted |
| [0013](0013-stream-claude-events-ndjson-artifact.md) | Stream Claude Code events live and ship NDJSON as the canonical stdout artifact | accepted |
| [0014](0014-per-caller-rate-limiting.md) | Per-caller rate limiting on the secured API | accepted |
| [0015](0015-runtime-image-base-and-toolchain.md) | Runtime image base and toolchain — full alpine userland, distroless rejected | accepted |
| [0016](0016-cookie-auth-csrf-protection.md) | CSRF protection for cookie/console auth — pinned CORS + scoped custom header | accepted |
| [0017](0017-claude-skip-permissions-default.md) | `dangerouslySkipPermissions` defaults to `false` | accepted |
| [0018](0018-mcp-server-endpoint.md) | MCP server endpoint — stateless Streamable HTTP, opt-in agent tools | accepted |
| [0019](0019-human-in-the-loop-questions.md) | Human-in-the-loop questions — per-process callback tokens, in-memory lifecycle, MCP SSE response mode | accepted |
| [0020](0020-durable-state-in-sqlite.md) | Durable orchestrator state in SQLite (processes + questions) | accepted |
| [0021](0021-ask-and-dispatch.md) | ask-and-dispatch — deferred dispatch from a question answer | accepted |
| [0022](0022-script-driven-immediate-dispatch.md) | script-driven immediate dispatch — `homer-dispatch` | accepted |
| [0023](0023-completion-continuations.md) | Completion continuations — resume a parked workflow when a watched process ends | accepted |
| [0024](0024-langgraph-command-type.md) | `!<langgraph>` command type — thin Kotlin launcher for a Python runtime | accepted |
| [0025](0025-capability-token-result-read.md) | Capability-token read-by-reference for large continuation results | accepted |
| [0026](0026-join-continuations.md) | Join continuations — resume a parked workflow once after N watched processes end | accepted |
| [0027](0027-langgraph-graph-introspection.md) | Read-only LangGraph graph introspection | accepted |
| [0028](0028-cross-origin-cookie-auth-samesite.md) | Cross-origin cookie auth via configurable `SameSite` (CSRF stays closed) | accepted |
| [0029](0029-flow-scoped-lock-passthrough.md) | Flow-scoped lock pass-through (`acceptParentLock`) | accepted |
| [0030](0030-runagent-in-process-dispatch.md) | `RunAgent` dispatches in-process, not via an HTTP self-call | accepted |
| [0031](0031-typed-input-param-constraints.md) | Typed input-param constraints, validated by a dedicated `ParamValidator` | accepted |
| [0032](0032-ssh-runner-backend.md) | SSH as a runner backend | accepted |
| [0033](0033-runner-lifecycle-primitives.md) | Runner contract decomposed into lifecycle primitives | accepted |
| [0034](0034-runnercmd-command-type.md) | runnerCmd — run one command on another runner | accepted |
| [0035](0035-sqlite-backed-cost-ledger.md) | SQLite-backed cost ledger | accepted |
| [0036](0036-explicit-api-auth-opt-in.md) | `server.apiAuth` is required — unsecured is an explicit opt-in | accepted |
| [0037](0037-grants-authorization.md) | Grants-based authorization replaces path allowlists | accepted |
| [0038](0038-process-ownership.md) | Per-process ownership and the union visibility rule | accepted |
| [0039](0039-nested-dispatch-authority.md) | Nested-dispatch authority — gating `RunAgent`/dispatch/continuation hops | accepted |
| [0040](0040-inputschema-pattern-redos-load-time-lint.md) | Reject ReDoS-prone `inputSchema` patterns with a load-time lint | accepted |
| [0041](0041-process-group-reaping-on-kill.md) | Reap orphaned processes via the process group | accepted |
| [0042](0042-runner-extra-uploads.md) | Runner-level `extraUploads` — materialize host folders into remote workspaces | accepted |
| [0043](0043-k8s-sync-workspaces.md) | Sync-mode kubernetes workspaces — tar-over-exec replaces the shared RWX PVC | accepted |
| [0044](0044-langgraph-resume-and-run-introspection.md) | LangGraph resume and run introspection | accepted |
| [0045](0045-external-secret-delivery.md) | Secrets reach agents as files rendered by cluster tooling — Homer never resolves them | accepted |
| [0046](0046-owner-scoped-langgraph-checkpoints.md) | LangGraph checkpoint stores are scoped per owner | accepted |
| [0047](0047-security-by-design-and-threat-model.md) | Security is a design input, and these are the boundaries it defends | accepted |
| [0048](0048-split-record-retention-from-workspace-purge.md) | A run's record outlives its workspace | accepted |
| [0049](0049-agent-definitions-stay-self-contained.md) | Agent definitions stay self-contained — no includes or step templates | accepted |
| [0050](0050-per-agent-telemetry-block.md) | Per-agent telemetry is one block, and every start path goes through it | accepted |
| [0051](0051-cooldown-is-enforced-in-the-insert-transaction.md) | The cooldown is enforced in the process-INSERT transaction, on recorded timestamps | accepted |
| [0052](0052-redaction-metadata-is-captured-with-the-run.md) | Input-redaction metadata is captured with the run, not re-derived from the agent | accepted |
| [0053](0053-declared-artifacts-ride-the-record-lifetime.md) | Declared artifacts ride the record's lifetime | accepted |
| [0054](0054-kubernetes-workdir-uploads-without-its-tar-entry.md) | Kubernetes `workdir` uploads without its tar entry | accepted |
| [0055](0055-workdir-sync-down-is-a-mirror.md) | `workdir` sync-down is a mirror, not an overlay | accepted |
| [0056](0056-dependency-license-policy.md) | Dependency license policy and gating | accepted |
| [0057](0057-claude-output-modelled-only-where-read.md) | Claude CLI output is modelled only where Homer reads it | accepted |
| [0058](0058-parent-child-run-linkage.md) | Parent/child run linkage as a stored, caller-visible reference | accepted |
| [0059](0059-console-plugins-are-runtime-loaded-modules.md) | Console plugins are same-origin ES modules imported at runtime | accepted |
| [0060](0060-run-tags.md) | Run tags — subject tags stored per run and filterable through the API | accepted |
| [0061](0061-price-claude-runs-from-session-spend-endpoint.md) | Price Claude runs from an external session-spend endpoint, CLI figure as marked fallback | accepted |
| [0062](0062-cost-by-tag.md) | Cost by tag — aggregated at query time from live rows | accepted |
| [0063](0063-filter-claude-stdout-at-write-time.md) | Drop configured Claude `system` subtypes from stdout at write time | accepted |
| [0064](0064-sftp-watchdog-timeout.md) | SFTP blocking-I/O timeout via a watchdog coroutine | accepted |
| [0065](0065-remote-runner-env-composition.md) | Remote-runner env-composition contract | accepted |
| [0066](0066-external-pull-runners-and-unified-claim-queue.md) | External pull-mode runners and the unified claim queue | accepted |
| [0067](0067-ssh-cleanup-via-rm-rf.md) | The ssh runner removes a finished workspace with `rm -rf` over the exec channel, SFTP walk as fallback | accepted |
| [0068](0068-nested-agent-discovery.md) | Agent discovery walks the search path recursively; directory identity is the root-relative path | accepted |
| [0069](0069-console-themes-are-runtime-loaded-stylesheets.md) | Console themes are same-origin stylesheets loaded at runtime | accepted |
| [0070](0070-llm-harness-adapter-boundary.md) | LLM harness adapter boundary and canonical event model | accepted |
| [0071](0071-secret-input-defaults-are-dropped-from-the-agents-list.md) | Secret input defaults and allowed values are dropped from the API, not masked | accepted |
| [0072](0072-bodyless-post-only-get-stays-tight.md) | The bodyless relaxation is POST-only — GET execution stays requiring a body | accepted |
| [0073](0073-langgraph-topology-is-structured-data.md) | LangGraph topology is structured data, drawn by the console | accepted |
| [0074](0074-cost-of-an-attempt-that-ends-more-than-one-turn.md) | An attempt that ends more than one turn is charged once, at its highest cumulative cost | accepted |
| [0075](0075-langgraph-traversed-path-from-checkpoint-writes.md) | The traversed path comes from checkpoint routing writes | accepted |
| [0076](0076-dev-jdwp-is-an-opt-in-loopback-overlay.md) | The dev JDWP agent is an opt-in, loopback-only overlay | accepted |
| [0077](0077-runner-alias-is-admin-only-on-status.md) | The runner alias is admin-only on the status API | accepted |
| [0078](0078-runner-jvm-is-non-dumpable.md) | The runner JVM is non-dumpable, and refuses to start when it cannot be | accepted |
| [0079](0079-llm-steps-escalate-to-another-model-on-a-bad-outcome.md) | LLM steps escalate to another model on a bad outcome | accepted |
| [0080](0080-manual-cron-fire-endpoint.md) | The manual cron-fire endpoint is admin-only, body-less and silent about refusals | accepted |
| [0081](0081-crap-score-gate.md) | A CRAP score report for the backend and the console, gated as a warning first | proposed |
| [0082](0082-quality-reports-report-first.md) | Quality checks are reported first and made required one by one | proposed |
| [0083](0083-backend-layers-through-ports-and-packages.md) | Backend layers are enforced through kernel types, ports and one package per layer | proposed |
| [0084](0084-github-actions-ci-on-hosted-runners.md) | GitHub Actions CI on GitHub-hosted runners, without Artifactory | proposed |
| [0085](0085-one-way-github-mirror-during-migration.md) | A one-way GitHub mirror from a single initial commit during the migration | proposed |
| [0086](0086-sign-and-attest-release-images.md) | Sign release images with cosign and attest their SBOM and provenance | proposed |
| [0087](0087-versioned-documentation-site.md) | A versioned documentation site generated from the repository's Markdown | proposed |
| [0088](0088-dependency-check-on-the-nvd-mirror.md) | Check runtime dependencies against the dependency-check project's NVD mirror | proposed |
| [0089](0089-settle-llm-cost-in-the-background.md) | Settle an LLM step's session cost in the background | proposed |
| [0090](0090-accepted-issue-pull-request-gate.md) | A pull request merges only for an issue a maintainer accepted | proposed |
| [0091](0091-ai-review-of-github-pull-requests.md) | The AI review of GitHub pull requests, gated before any model runs | proposed |
| [0092](0092-maintenance-mode-queues-starts-and-skips-cron.md) | Maintenance mode queues new starts and skips cron, above the claim queue | proposed |
| [0093](0093-the-default-compose-stack-runs-published-images.md) | The default compose stack runs published release images | proposed |
| [0094](0094-slim-server-image-for-pull-runner-deployments.md) | A slim server image for deployments whose agents run on pull runners | proposed |
| [0095](0095-local-runner-can-be-turned-off.md) | The built-in local runner can be turned off | proposed |
| [0096](0096-unhealthy-runners-stop-claiming-and-idle-consumers-take-turns.md) | Unhealthy runners stop claiming, and idle consumers take turns | proposed |
| [0097](0097-per-run-inbox.md) | A per-run inbox carries operator messages to a running run | proposed |
