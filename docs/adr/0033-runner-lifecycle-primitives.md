---
status: accepted
date: 2026-07-27
deciders: Lukáš Vlk
---
# ADR-0033: Runner contract decomposed into lifecycle primitives

## Context and Problem Statement

The `Runner` SPI (ADR-0003, ADR-0032) produces a `RunnerSession` whose
open/close calls bundle distinct lifecycle steps. For the SSH backend,
`openSession` = connect + mkdir remote root + full workspace upload +
env translation, and `close` = artifacts download + conditional workdir
download + conditional remote sweep + disconnect.

A planned feature — running a *single command* of an otherwise-local
agent on another runner, with environment checks executed **before**
any sync and **selective** upload/download lists — cannot be expressed
against that contract: sync scope and timing are welded into session
open/close inside each backend.

## Decision Drivers

- Unblock per-command remote execution (check → sync → execute → sync
  back) without a parallel SSH code path.
- One uniform orchestration sequence for all backends; a backend whose
  execution filesystem is the server's (local, k8s shared PVC) no-ops
  what does not apply. *(Amended 2026-10-09: kubernetes no longer shares
  a PVC; it syncs via tar-over-exec, see [ADR-0043](0043-k8s-sync-workspaces.md).)*
- Zero behavior change for existing runs.
- Remove the k8s-specific `podName` leak from the generic session
  interface.

## Considered Options

1. **Decompose the session contract into mechanism primitives; move
   policy to the orchestrator** (chosen).
2. Keep the contract; give the future command its own SSH machinery —
   duplicates connection/auth/sync logic and drifts from the runner.
3. Add feature-specific hooks (`openSessionWithoutSync`, …) — grows an
   ad-hoc contract per feature instead of a universal one.

## Decision Outcome

`Runner.openSession(params)` provisions only (connect/auth; create pod
and wait ready; nothing for local). `RunnerSession` exposes mechanism
primitives, each defaulting to no-op:

- `createWorkspace(): Map<String, String>` — create the backend-side
  workspace root; returns env overrides (e.g. `HMR_*` paths translated
  to the remote layout). For the ssh backend this also creates the
  remote workdir itself, alongside translating env — needed because a
  runner-command's per-command `check` expressions may run before any
  `upload`.
- `upload(local: Path, remoteRelative: String)` /
  `download(remoteRelative: String, local: Path, overlay: Boolean = true)`
  — copy between the server and the backend workspace; the remote side
  is always addressed relative to the backend's workspace root, so
  callers never learn the backend's path scheme. Directional methods
  (not a generic `copy(src, dst)`) keep direction type-safe. Downloads
  default to overlay (keep local files); `overlay = false` overwrites
  them with the remote content. *(Amended 2026-10-09: the flag is now
  `mode: DownloadMode = OVERLAY` with `OVERLAY` / `OVERWRITE` / `MIRROR`,
  see [ADR-0055](0055-workdir-sync-down-is-a-mirror.md).)*
- `cleanWorkspace()` — remove the backend-side workspace root.
- `metadata: Map<String, String>` — backend-resource facts (e.g.
  `pod.name`), stamped onto command-execution spans; replaces `podName`.
- `shell` / `close()` — unchanged (execute / destroy).

Full-run *policy* moves to `AgentExecutor`: upload workspace + agent
dir after `createWorkspace`; on finish (best-effort, NonCancellable)
download artifacts always, download workdir only when the workspace is
kept, sweep the remote workspace only when `clearAfterFinish`, then
close. Transfer *mechanism* (SFTP, `syncExcludes`) stays inside the
backend session.

## Consequences

- Good: the future runner-command composes the same primitives
  (checks via `shell` before any `upload`); no duplicated SSH stack.
- Good: new backends implement only what applies; no-op is a valid
  implementation for shared-filesystem backends.
- Neutral: local workspace dir lifecycle stays owned by `Workspace` /
  `WorkspacePurgeService`; `cleanWorkspace` concerns the backend side
  only.
- Trade-off: a server-shutdown backstop close of an SSH session no
  longer rescues artifacts (close = disconnect only). The rescue still
  happens for normal and cancelled runs — `AgentExecutor`'s finally
  block is `NonCancellable` and performs the downloads.
