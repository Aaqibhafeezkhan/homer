---
status: accepted
date: 2026-04-24
deciders: Lukáš Vlk
---
# ADR-0003: Runners as pluggable execution backends

## Context and Problem Statement

Every agent command historically ran on Homer's host via `ShellExecutor`
(a `ProcessBuilder` wrapper). Operators want to run commands inside
short-lived Kubernetes pods — one pod per agent run, with operator-
chosen image, resources, and volume mounts — while keeping the existing
local-host path working unchanged for agents that don't opt in.

We needed a place to put "where a command runs" that was not bolted onto
one executor and not a per-command `type: k8s` hack. The choice shapes
how workspaces, env vars, artifacts, and lifecycle cross the host/pod
boundary for every command type Homer already supports (`Cmd`, `Script`,
`Claude`, `RunAgent`).

## Decision Drivers

- One pod per **agent run**, not per command — agents chain commands
  that share filesystem state; per-command pods would force copy-in /
  copy-out or a sidecar.
- Existing executors (`Cmd`, `Script`, `Claude`, `RunAgent`) must work
  unchanged in the new backend without per-type special casing.
- Artifact tailing (live stdout/stderr streaming) must keep working with
  no new plumbing.
- Homer remains the source of truth for workspace state; pods are
  ephemeral consumers of a shared view.
- Operators already run Homer in Kubernetes and want command isolation
  without giving up the local-host path for dev / tests.

## Considered Options

- **One pod per command.** Spawn a pod for every `Cmd` / `Script` /
  `Claude` inside an agent. Reuse nothing between commands.
- **Kubernetes as a new command type.** Add `type: k8s` alongside
  `cmd`/`script`, leaving other commands on the host.
- **Runner abstraction (one pod per agent run).** New pluggable
  backend interface with a `local` built-in and a `kubernetes`
  implementation; agents pick a runner by alias.

## Decision Outcome

Chosen option: **"Runner abstraction (one pod per agent run)"**, because
it is the only option that satisfies all four drivers simultaneously.
Per-command pods make filesystem sharing between `git clone` and
`./build.sh` prohibitive; a `type: k8s` command forces per-type
rewrites of every executor. A Runner with one session per agent run
lets every existing executor call `session.shell` without knowing
whether it's a host or an in-pod shell.

The session's shell is threaded through `ExecutionContext.shell` rather
than mutated onto `Workspace` — `Workspace` stays shared host state, the
session-scoped shell travels with the call.

Workspaces are shared via a single RWX PVC mounted at the **same
absolute path** inside the pod as on Homer, using `subPath` to scope to
the per-run workspace. No copy in, no copy out; `HMR_WORK_DIR` and
`HMR_ARTIFACTS_DIR` pass through unchanged. *(Superseded in part by
[ADR-0043](0043-k8s-sync-workspaces.md), 2026-10-09 note: kubernetes
workspaces are now synced by tar-over-exec; the shared RWX PVC is gone.)*

`HMR_API_URL` is contributed by the runner session and merged into the
workspace env before commands run, so `RunAgent` resolves Homer's
in-cluster Service inside the pod and the host fallback
(`http://localhost:<port>`) applies only to the local runner.
*(Amended 2026-10-09: `RunAgent` dispatches in-process since
[ADR-0030](0030-runagent-in-process-dispatch.md) and no longer uses
`HMR_API_URL`; the runner session still contributes it for in-run callbacks.)*

### Consequences

- Good: every executor is backend-agnostic — `session.shell` is the only
  seam.
- Good: live tailing keeps working because pod stdout/stderr are
  streamed to files on the shared PVC, which `ArtifactService` already
  reads. *(Superseded in part by [ADR-0043](0043-k8s-sync-workspaces.md),
  2026-10-09 note: there is no shared PVC any more.)*
- Good: the local runner preserves byte-identical behavior for agents
  that don't set `runner:`.
- Good: pod lifecycle surfaces on `/api/v1/process/{id}` and in the
  console without per-command instrumentation.
- Bad: v1 is single-replica — `lockKey`-based serialization is
  in-memory and the orphan-pod sweep assumes one Homer. Multi-replica
  HA needs distributed locks and per-replica ownership labels.
- Bad: v1 `homerApiUrl` is plain HTTP; TLS / mTLS between pod and Homer
  is future work.
- Bad: the image must ship whatever binaries a `Claude` / `RunAgent`
  command relies on (`claude` CLI, `curl`), documented in
  `app/SERVER.md`.

## Pros and Cons of the Options

### Option A — One pod per command

- Good: strongest isolation between commands.
- Bad: breaks filesystem sharing between chained commands unless every
  pod mounts the same PVC and runs a setup container — more moving
  parts than one pod per run, for no real gain over per-run pods.
- Bad: pod-create latency is paid per command, not per agent run.

### Option B — Kubernetes as a new command type

- Good: smallest surface area — one new `Command` subclass.
- Bad: every other command type (`Cmd`, `Script`, `Claude`, `RunAgent`)
  stays stuck on the host; operators can't run *agents* in Kubernetes,
  only individual commands.
- Bad: two execution models to maintain inside one agent.

### Option C — Runner abstraction

- Good: every existing executor becomes backend-agnostic.
- Good: new backends (Fargate, Nomad, Firecracker, …) slot in behind
  the same interface.
- Neutral: adds a module and a config key (`AppConfig.runners`) plus
  an agent field (`runner:`).
- Bad: more up-front structure than Option B.

## More Information

- Related: [ADR-0002](0002-unified-error-model-at-http-boundary.md) —
  runner startup failures surface as `PodStartupException` mapped at
  the HTTP boundary the same way as other `HomerException` subclasses.
