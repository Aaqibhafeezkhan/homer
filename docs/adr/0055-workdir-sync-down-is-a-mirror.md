---
status: accepted
date: 2026-08-26
deciders: Lukáš Vlk
---
# ADR-0055: `workdir` sync-down is a mirror, not an overlay

## Context and Problem Statement

A kept workspace's (`cleanAfterFinish: false`) end-of-run `workdir` download from
a remote runner had two defects. `RunnerSession.download`'s only reconciliation
modes were "keep existing" (overlay) and "always overwrite" (see
[ADR-0033](0033-runner-lifecycle-primitives.md)), so a file the agent modified
remotely never overwrote the host's existing copy, and the download was purely
additive — a file the agent deleted remotely stayed on the host forever. Both
remote runners (kubernetes — [ADR-0043](0043-k8s-sync-workspaces.md); ssh —
[ADR-0032](0032-ssh-runner-backend.md)) share the same `download` primitive and
therefore the same bug. The local runner is immune by construction: its commands
operate on the host `workdir` directly, so there is no download step to get wrong.

[ADR-0054](0054-kubernetes-workdir-uploads-without-its-tar-entry.md)'s
rework established kept-workspace `workdir` retention and runner uniformity: a
kept workspace's `workdir` round-trips between runs the same way on every
runner, and sync-up seeds the next run's cwd from whatever sync-down left on the
host. That contract only holds if sync-down leaves the host copy converged to
the remote's actual end state; overlay-plus-additive instead leaves it a strict
superset that only ever grows.

## Decision Drivers

- ADR-0054's runner-uniformity contract requires the host `workdir` to reflect
  what the remote run actually did, not what it did minus deletions and updates.
- Sync-down is deliberately best-effort (see `app/SERVER.md`) — a network blip
  or a pod torn down mid-run must never destroy a host copy that would otherwise
  still be intact.
- §12: `workdir` content is attacker-controlled (an agent-writable directory);
  the reconciliation mechanism itself must not open a new way to lose or corrupt
  host data on a partial transfer.

## Considered Options

- **Add a mirror mode: extract, then prune local extras once the download is
  confirmed fully successful** (chosen).
- **Keep additive overlay** (today's behavior).
- **Wipe the local target, then extract.**
- **Extract into a temp directory, then swap it into place.**

## Decision Outcome

Chosen option: **a third `DownloadMode`, `MIRROR`**, alongside the existing
`OVERLAY` and `OVERWRITE` on the `RunnerSession.download` contract. `MIRROR`
extracts like `OVERWRITE` — remote content replaces existing local files — and
then, only after the download is confirmed **fully** successful, deletes local
paths under the target that the download did not touch. "Fully successful" is
backend-specific: a zero exit from the `tar` process on kubernetes
(`KubernetesWorkspaceSync.download`), a completed SFTP walk with no exception on
ssh (`WorkspaceSync.downloadDir`). A missing remote directory stays the existing
silent no-op — it triggers no prune, so a vanished pod or an unreachable remote
path never wipes a host copy that is still intact. The prune walk is local-only,
bottom-up (deepest paths first, so a directory empties before its own removal),
does not follow symlinks, and stays on lexical paths throughout to agree with
the set of paths the extraction step recorded as materialized. Single-file
downloads have no "extras" scope, so `MIRROR` behaves like `OVERWRITE` there.

`AgentExecutor`'s workdir sync-down now passes `MIRROR`; its artifacts sync-down
passes `OVERLAY` explicitly (unchanged behavior, now explicit); `RunnerCmdExecutor
.syncFromRemote` keeps `OVERWRITE` (unchanged — it targets one caller-declared
path, not a directory of accumulated agent state).

The other three options are rejected:

- **Keep additive overlay** does not satisfy ADR-0054's convergence contract —
  updates and deletions stay permanently invisible to the host copy, and every
  kept-workspace run only ever grows what it first seeded.
- **Wipe-then-extract** breaks the best-effort contract sync-down already has: a
  stream that fails partway (network blip, killed pod) would leave the host copy
  gone rather than merely stale, turning an infrastructure hiccup into data loss.
- **Temp-dir-and-swap** avoids that failure mode too, but doubles disk usage for
  the duration of every kept-workspace sync-down and adds a second on-disk
  layout (a temp root, then an atomic move) next to the existing `artifacts/`
  handling — a materially more complex mechanism for no failure-mode improvement
  over extract-then-prune, which already only prunes after a confirmed-complete
  transfer.

### Consequences

- Good: a kept workspace's `workdir` converges to the remote's actual end state
  on both remote runners — modified files overwrite, deleted files disappear —
  closing the gap ADR-0054's runner-uniformity contract assumed was already
  closed.
- Good: sync-down stays best-effort. A missing remote path is still a pure
  no-op, but extraction is in-place, so a partial or failed transfer may
  already have overwritten or added files before it aborted — the guarantee
  is possibly partially *updated*, never pruned; an abort mid-prune (an
  `IOException` partway through the delete walk) leaves it at worst
  partially *pruned*, never wiped wholesale.
- Bad (accepted, and intentional): a host file added directly to `workdir` while
  a remote run is in flight is pruned at the next sync-down, because it was
  never part of the remote's materialized set. Nothing in Homer's own flow
  writes to a kept workspace's `workdir` while a run is active, so this only
  bites a human or script editing the host directory mid-run — already an
  unsupported thing to do.
- Bad (accepted): a local intra-`workdir` symlink — creatable only by a
  local-runner run or a human on a shared kept workspace — whose name appears
  in the remote listing is kept as a link, while content written through it
  lands at its realpath target and may be pruned; mixing runners on one kept
  workspace with intra-`workdir` symlinks is unsupported.
- Neutral: `artifacts/` is deliberately left an additive overlay, not mirrored —
  it protects the `cmd_<i>.out`/`.err` files Homer itself streams to disk live
  during the run; mirroring it would let a remote-side deletion of an artifact
  delete the host's own live-streamed record of the run.
- Neutral: the local runner is unaffected — it has no download step, so `MIRROR`
  never applies to it.

## More Information

- Closes the remaining gap in
  [ADR-0054](0054-kubernetes-workdir-uploads-without-its-tar-entry.md)'s
  kept-workspace runner-uniformity contract.
- `DownloadMode` sits on the `RunnerSession` contract established by
  [ADR-0033](0033-runner-lifecycle-primitives.md).
- Backend mechanics: [ADR-0043](0043-k8s-sync-workspaces.md) (kubernetes,
  tar-over-exec) and [ADR-0032](0032-ssh-runner-backend.md) (ssh, SFTP).
