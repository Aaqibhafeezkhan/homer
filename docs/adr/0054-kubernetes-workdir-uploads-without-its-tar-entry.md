---
status: accepted
date: 2026-08-26
deciders: Lukáš Vlk
---
# ADR-0054: Kubernetes `workdir` uploads without its tar entry

## Context and Problem Statement

The kubernetes runner's first upload fails under `runAsNonRoot` with
`tar: workdir: Cannot utime: Operation not permitted`. The chain:

- Host side, `Workspace.create()` creates `workdir/` inside `projectRoot`;
  `AgentExecutor` uploads the whole project root, so `workdir/` is a tar
  entry.
- `PodSpecBuilder.workspaceMounts()` mounts the workspace volume a second
  time at the container's working directory with `subPath("workdir")` so the
  container has a cwd at start.
- The kubelet creates the subPath dir **after** applying `fsGroup` to the
  volume root ([kubernetes/kubernetes#81089](https://github.com/kubernetes/kubernetes/issues/81089)),
  owned `root:<fsGroup>`. Verified against a real pod: its mode is cloned
  from the fsGroup-applied volume root — observed `2777` (setgid,
  group/world-writable) — so the pod's non-root user *can* write into it.
  Only `utime`/`chmod` **on the directory entry itself** requires the owner
  uid.
- GNU tar restores directory mtimes after extraction, so a tar stream
  carrying a `workdir/` directory entry hits that `utime` and fails:
  `EPERM` → tar exit 2 → `upload to '/homer/workspace' failed (exit 2): tar:
  workdir: Cannot utime: Operation not permitted`. The same content without
  that directory entry — files and nested dirs alike — extracts cleanly
  (exit 0): tar never touches the pre-created directory, only writes into
  it.
- busybox tar (Alpine images) attempts the same restore but silently ignores
  the `EPERM` — verified empirically against `ubuntu:24.04` (GNU tar, exit
  2) and `curlimages/curl:8.21.0` (busybox tar 1.37.0, exit 0), both on the
  real cluster and reproduced in plain docker — so Alpine-based pod images
  mask the bug entirely, which made it look cluster-dependent rather than
  image-dependent.

## Decision Drivers

- The fix must work on any `runAsNonRoot` cluster, not depend on the pod
  image's tar implementation happening to swallow the error.
- Kept workspaces (`cleanAfterFinish: false`) exist to retain `workdir`
  between runs, and the kubernetes runner must sync `workdir` the same way
  the ssh and local runners do — a caller shouldn't see different
  round-trip behavior depending on which runner an agent happens to run on.
- §12: this is an availability defect — any principal running agents on a
  non-root kubernetes runner cannot get a first command to run.

## Considered Options

- **Omit only `workdir`'s own tar directory entry; upload its contents
  normally** (chosen) — tar never issues `utime`/`chmod` against the
  pre-created directory, but everything inside it uploads like any other
  workspace content.
- **Unconditionally exclude `workdir` from the project-root upload** — the
  originally shipped fix (see Decision Outcome).
- **`fsGroup` on the pod's `securityContext`** — let the kubelet's
  group-ownership pass cover the subPath dir too.
- **`tar -m` (`--touch`, skip mtime restore) on extraction.**
- **Document `syncExcludes: [workdir]` as an operator setting.**

## Decision Outcome

Chosen option: **omit `workdir`'s own tar directory entry from the
project-root upload; upload its contents as normal tar entries**, scoped to
the kubernetes runner.

`KubernetesWorkspaceSync.upload` gains an `omitDirEntries: Set<String>`
parameter, threaded into `addDirContents`: a directory whose root-relative
entry name is in the set skips `putArchiveEntry`/`closeArchiveEntry` for
itself but still recurses into its contents, so files and nested
directories underneath it upload unaffected. `KubernetesRunnerSession`
passes `omitDirEntries = {"workdir"}` for the project-root upload (root-
anchored by construction — it matches only the top-level entry name, so a
workspace-authored nested directory also named `workdir` still gets its own
entry). `syncExcludes` now passes through unchanged for every upload;
`workdir` no longer needs to be added to it.

This is a revision of the ADR's original decision, which unconditionally
excluded `workdir` from the project-root upload (root-anchored
`syncExcludes: ["/workdir"]` at the session, reusing the root-anchored
exclude matching added immediately before this ADR). That shipped first and
is now the rejected alternative below.

The other four options are rejected:

- **Unconditionally excluding `workdir`** breaks the guarantee that a kept
  workspace's `workdir` round-trips between runs on kubernetes the same way
  it does on ssh/local, and makes the kubernetes runner behave differently
  from the others for the same `cleanAfterFinish: false` setting — rejected
  by the repo owner on 2026-08-26. It was shipped believing `workdir`'s
  entire content was unshippable by the reserved-path model that
  `RunnerConfig.RESERVED_REMOTE_PATHS` uses for `extraUploads` targets; that
  reasoning does not hold — that list guards where an operator may point an
  *extra* upload, and says nothing about whether the workspace's own
  `workdir` content may be synced as part of the normal upload.
- **`fsGroup` cannot work at all**, not just "cannot work reliably": the
  failure is an ownership mismatch on the directory entry's `utime`/`chmod`
  (owner uid required), not a group-permission gap — the directory is
  already group/world-writable (`2777`) for content, and
  kubernetes/kubernetes#81089 means the kubelet creates the subPath
  directory *after* applying `fsGroup` to the volume, so there is no
  ordering fix available from the pod spec side.
- **`tar -m` on extraction** hides the symptom but drops mtime fidelity for
  every extracted file on every upload, not just the one directory entry
  that actually fails — a strictly worse trade for a problem that has a
  targeted fix.
- **Operator `syncExcludes: [workdir]`** is unanchored: it matches the entry
  name at every directory level, so it would also silently drop any nested
  directory a repo happens to carry that is named `workdir`. It also leaves
  the reserved path uploadable by default, requiring every operator on a
  non-root cluster to discover and set it themselves — and, like
  unconditional exclusion, it would still break kept-workspace round-tripping.

### Consequences

- Good: the kubernetes runner's first upload succeeds under `runAsNonRoot`
  on any tar implementation, GNU or busybox — the fix no longer depends on
  which one silently swallowed the bug.
- Good: a kept workspace (`cleanAfterFinish: false`) round-trips `workdir`
  on the kubernetes runner exactly like it does on ssh/local — sync-up
  uploads the accumulated content back into the next pod's cwd, matching
  runner parity.
- Bad (accepted): the pod's `workdir` is no longer guaranteed empty of host
  content on first upload — it now carries whatever the host `workdir/`
  contains, same as every other runner. This supersedes the original request's
  stricter acceptance criterion ("pod workdir stays empty of host content");
  the repo owner ruled runner parity and kept-workspace seeding take
  priority (2026-08-26).
- Neutral: the ssh runner is deliberately unchanged. Its remote workdir is a
  plain directory owned by the ssh user — there is no kubelet subPath
  pre-creation and no ownership collision, so the failure this ADR fixes
  does not exist there.

## More Information

- Builds on the root-anchored `syncExcludes` matching added immediately
  before this ADR's first version; `omitDirEntries` is a separate,
  independent mechanism from `syncExcludes` (the latter drops content
  entirely, the former only skips one directory's own tar entry).
- Extends [ADR-0043](0043-k8s-sync-workspaces.md) (sync-mode kubernetes
  workspaces).
