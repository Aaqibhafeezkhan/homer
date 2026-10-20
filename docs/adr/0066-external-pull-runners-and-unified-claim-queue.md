---
status: accepted
date: 2026-09-18
deciders: Lukáš Vlk
---
# ADR-0066: External pull-mode runners and the unified claim queue

## Context and Problem Statement

Runners are push-mode: Homer connects out to an ssh host or a Kubernetes API that
the operator declared under `runners:`, so adding an execution machine means
editing server configuration and restarting Homer, while agents already reload at
runtime. There is also no per-runner concurrency bound; only `maxParallelRuns`
per agent exists. We want CI-runner-shaped pull mode (a machine registers
itself and takes work) without letting a runner widen what it may receive, and
without a second execution path for the runners we already have.

## Decision Drivers

- Add and remove execution machines without restarting Homer.
- A runner must not be able to route to itself runs it was not trusted with
  (§12: principals are a boundary; the run env carries secrets).
- One execution path for every runner, so `concurrency` is a real bound on all
  of them and the code is tested once.
- Every wait is bounded (§12 rules 4 and 5).
- `AgentExecutor`, sync policy and the runner implementations stay untouched.

## Considered Options

- A: Thin runner; Homer stays the orchestrator and drives `RunnerSession`
  primitives over an HTTP long-poll; every run goes through one claim queue.
- B: Fat runner that executes a whole agent definition itself (a copy of
  `AgentExecutor` in the binary).
- C: Push-mode only, with hot-reload of the `runners:` config section.

## Decision Outcome

Chosen option: **A**.

- **Trust is config, routing is runtime.** An `!<external>` entry under
  `runners:` is a trust domain holding the token instances authenticate with and
  the limits that bound them. Adding a domain needs a restart, like adding a
  bearer grant. Tags are announced by the instance at registration and requested
  by the agent through `runnerTags`; adding a tag never needs a restart.
- **Tags are not a security boundary.** An instance may announce a tag it cannot
  honour; the consequence is a failed run inside a domain the operator already
  trusted (availability, not confidentiality). Accepted risk. `allowedTags` on a
  domain is a typo guard, not a gate.
- **One queue for every run.** `RunnerRegistry.forAgent` always returns a
  `QueuedRunner`; its `openSession` places a `ClaimRequest` with a filter
  (`Alias` or `Tags`) into `RunnerClaimQueue`. Consumers are the configured
  runners (one `StaticRunnerConsumer` each, `concurrency` permits, unbounded by
  default) and, in a later increment, registered external instances. A consumer
  takes the oldest request it matches only when it holds a free permit.
- **One claim TTL** (`server.runnerClaimTimeoutSec`, per-agent override) covers
  both waiting for a permit and waiting for an instance. At expiry the run fails
  with the reason observed then (`runner 'x' at concurrency 2/2`, `no registered
  runner matches tags [...]`). The reason travels in the failure exception (span
  status, log, process failure): no command has started, so there is no stderr
  file to append to. The per-agent override applies to the agent's own claim
  only; a `runnerCmd` step claims its target runner under the server default.
  Letting the step inherit the agent's override was rejected: the step
  targets a different runner than the agent, and a TTL sized for one runner's
  saturation says nothing about another's.
- **The queue is bounded locally** by `server.maxPendingRunnerClaims`; a request
  at the bound fails immediately.
- **No re-enqueue.** Agent commands are not idempotent; the manual retry
  endpoint remains the tool.
- **Env layering differs by filter.** An alias-filtered run knows its runner up
  front, so per-runner `env` keeps its layer between `globalEnv` and agent `env`
  and `isRemote` is the runner's. A tag-filtered run learns its runner at claim
  time: `isRemote` is true if any candidate is remote, per-runner `env` reaches
  the run through `RunnerSession.env` (above agent `env`).
- **Implementation deviations from the design spec.** (1) `maxTransferBytes`
  bounds each tar an instance posts; the spec bounded transfers by time only,
  and a byte bound is §12 rule 4 made local. (2) The `homer-runner` binary
  runs commands with its own streaming process runner rather than
  `LocalShellExecutor`, because the executor writes to files while the
  protocol streams chunks; it reuses `ProcessGroupSupport` for group kills
  and `TarStreams` for transfers. (3) A domain token must differ from every
  `apiAuth` credential — bearer token, HMAC key secret, and current or
  rotated-out session signing secret — checked at startup, so the two principal
  classes can never satisfy each other by value; it must also be at least 32
  characters after `@SYSENV` resolution, the `sessionSecret` floor. (4) `--drain-timeout` takes
  bare seconds, not the spec's `600s` duration string. (5) A SIGTERM'd
  `homer-runner` exits `143`, the JVM's own signal exit code: the drain runs
  from a shutdown hook, and a hook cannot turn the status into `0`.
  (6) Kubernetes sync-down extraction, unbounded under
  [ADR-0055](0055-workdir-sync-down-is-a-mirror.md), now spends the shared
  `TarStreams.DEFAULT_MAX_EXTRACTED_BYTES` (1 GiB), because the pod's `tar` is
  attacker-replaceable and that path has no configurable ceiling of its own.
  Making it configurable was rejected: the bound exists to refuse a bomb, and
  reusing the `maxTransferBytes` default keeps one refusal size wherever a tar
  is extracted. (7) The per-request `transferTimeoutSec` deadline on the `tar`
  and `output` routes is a watchdog that cancels the call's byte channel, the
  [ADR-0064](0064-sftp-watchdog-timeout.md) shape: the copy blocks a
  `Dispatchers.IO` thread on the peer's socket, and `withTimeout` alone never
  fires while a peer stalls. (8) Each transfer is served one request at a
  time (per op for a tar, per op and stream for output) and a concurrent
  duplicate is `429`, so the transfers in flight follow the ops Homer issued
  rather than the caller. A per-domain semaphore was rejected: a fixed share
  either sheds legitimate transfers of a busy domain or is too large to bound
  anything. `homer-runner` retries a shed output chunk, so a shed never
  desyncs its per-stream sequence. (9) The load-time servability check that
  warns about an agent whose `runnerTags` no runner serves is warn-only
  without exception: a throw from the tag-set provider (a registry read that
  outruns the schema, say) is caught, logged as a warning, and the agent still
  loads. Skipping the agent over a check whose only product is a warning would
  turn the availability-only tag semantics above into a load failure.

### Consequences

- Good: existing configurations behave exactly as before (unbounded consumer,
  immediate claim).
- Good: `concurrency` becomes a real per-runner bound; a saturated runner queues
  and fails at the TTL instead of overcommitting.
- Good: the external runner is only a `RunnerSession` whose primitives run on
  the far side of a long-poll; nothing above the session changes.
- Bad: a run addressed to a saturated static runner with `concurrency` set can
  now fail at the claim TTL, a state that could not occur before.
- Bad: tag-filtered runs layer per-runner `env` above agent `env`, unlike
  alias-filtered runs.
- Bad: a run holds its runner's permit for its whole duration, so a `RunAgent`
  child or `runnerCmd` step that targets a runner its own parent already holds
  a permit on can wait for a permit that will never free and fail at the claim
  TTL.
- Bad: `server.maxPendingRunnerClaims` is one global bound with no per-agent
  share, so one agent flooding a saturated runner can fill the queue and fail
  unrelated agents' runs immediately.
- Bad: `heartbeatTimeoutSec` must be at least the long-poll window plus 5 s
  slack; a shorter TTL drops healthy idle instances mid-poll.

## Pros and Cons of the Options

### Option A — thin runner, one queue

- Good: smallest change surface; one code path; bounds are local.
- Bad: every workspace tar crosses Homer; protocol is chatty.

### Option B — fat runner

- Good: fewer round trips.
- Bad: duplicates `AgentExecutor`, retries, cost accounting, questions and
  dispatch in a second binary.

### Option C — hot-reload `runners:`

- Good: no new protocol.
- Bad: reloading credentials and live sessions is fragile, and it still does not
  let a machine appear on its own.

## More Information

- The `!<external>` domain, its HTTP protocol (`RunnerProtocolRoutes`) and the
  `homer-runner` binary shipped in the second merge request; the admin runner
  status/queue endpoints and the console `/runners` page shipped in the third.
- Related: ADR-0003 (runners as backends), ADR-0033 (session primitives),
  ADR-0047 (threat model), ADR-0055 (mirror sync-down), ADR-0065 (remote env).
