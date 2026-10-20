---
status: accepted
date: 2026-08-17
deciders: Lukáš Vlk
---
# ADR-0043: Sync-mode kubernetes workspaces — tar-over-exec replaces the shared RWX PVC

## Context and Problem Statement

ADR-0003 gave the kubernetes runner a **shared RWX PVC** workspace model:
Homer and every agent pod mount the same claim, and pods see the
workspace at the identical absolute path Homer uses. That model has no
users today and two hard blockers for the deployments actually wanted:

- **Homer outside the cluster** (a VM/host bursting runs into k8s via
  the API server) — PVC sharing is impossible there, period.
- **In-cluster without RWX** — many storage classes only support RWO;
  requiring ReadWriteMany gates the runner on storage topology.

Meanwhile the SSH runner already ships the alternative: ADR-0033's
lifecycle primitives (`createWorkspace` / `upload` / `download` /
`cleanWorkspace`), driven unconditionally by `AgentExecutor` and
`RunnerCmdExecutor`, with the kubernetes session today inheriting the
no-op defaults. Implementing the primitives on the kubernetes session
makes the executors' sync policy work there with **zero executor
changes**. ADR-0042 already reserved `extraUploads` adoption for
exactly this "sync-mode kubernetes runner."

The first real workload driving this is **Android/KMP Gradle builds** —
very large numbers of small files. That profile rules out per-file
transfer; a single streamed tar archive per direction is the baseline
transport requirement.

## Decision Drivers

- Kubernetes runner must work with **no shared storage**: Homer reaches
  the pod only through the k8s API (exec over websocket), identically
  from inside or outside the cluster.
- **Replace** the PVC model, don't add a second mode — no dead code, no
  users to migrate incrementally.
- Same operator semantics as the SSH runner: `syncExcludes`,
  `extraUploads`, env path translation, artifacts-always /
  workdir-if-kept download policy.
- Stop baking the workspace env (secrets included) into the pod spec.
- `runnerCmd` must work on kubernetes runners (closes the deferred
  "sync-source story" from ADR-0034).
- `keepPodOnFailure` must become genuinely useful (kept pod retains the
  workspace) — this also means fixing the never-set `failed` flag.

## Considered Options

- **Keep dual mode** — leave the PVC path in place alongside a new sync
  path, selectable per runner.
- **Kubernetes client-java `Copy` API** — the library's built-in
  tar-over-exec copy helper.
- **rsync / sync sidecar** — a long-lived sidecar container doing
  delta sync against the workspace.
- **Tar-over-exec, implemented in-repo, replacing the PVC path**
  (chosen).

## Decision Outcome

Chosen option: **"Tar-over-exec, implemented in-repo, replacing the PVC
path,"** because it is the only option that removes the RWX
requirement while reusing existing, already-testable plumbing.

- **Replace, not add a mode.** `workspaceVolume` is removed from config
  and schema (BREAKING; no users). One model, no dead code.
- **In-pod workspace**: a pod-local `emptyDir` mounted at the fixed
  path `/homer/workspace`, mirroring the host `projectRoot` layout
  (`workdir/`, `artifacts/`, `.homer/agent`, `.homer/bin`,
  `.homer/mcp.json`). No configurable root — pod-per-run means no path
  collisions.
- **Transport**: tar-over-exec via the existing `KubernetesExecClient`
  seam (the same mechanism `kubectl cp` uses) — upload feeds a
  host-built tar stream into a pod-side `tar -xzf -`; download runs
  pod-side `tar -czf -` and extracts host-side. Gzip always; no
  compression knob (CPU is trivial next to network transfer,
  especially with Homer outside the cluster). One stream carries any
  number of small files — the best case for the Gradle profile, and it
  works identically from inside or outside the cluster.
- **Client `Copy` API rejected as the implementation vehicle**: it is
  the same tar-over-exec mechanism underneath, minus exclude filtering,
  overlay semantics, and a test seam — adopting it would mean
  re-implementing the parts that matter on top of it anyway. Tar
  framing is implemented in-repo instead (host-side via
  commons-compress).
- **Pod spec stops carrying the workspace env.** Commands already
  receive the full merged env per-exec (`env KEY=VAL … argv`); the
  baked copy's only real effects were leaking agent secrets into
  `kubectl get pod -o yaml` and serving manual `kubectl exec` shells.
  Removing it deletes the "Secret exposure in the pod spec" surface
  SERVER.md documents.
- **`syncExcludes` and `extraUploads` adopted on the kubernetes runner**
  with the same shape and semantics as the SSH runner (ADR-0042:
  "sync-mode kubernetes runner picks up the same field unchanged").
- **`cleanWorkspace()` stays a no-op** on kubernetes — the `emptyDir`
  dies with the pod. A kept-for-debug pod deliberately retains its
  workspace; that retention is the debugging payoff sync mode buys.
- **`markFailed()` added to `RunnerSession`** (default no-op;
  `AgentExecutor` calls it on failure paths before close) so
  `KubernetesRunnerSession.markFailed()` can set the `failed` flag that
  `keepPodOnFailure` has always depended on but that was never set —
  the knob was previously inert.
- **Image requirement**: `tar` + `gzip` (busybox suffices) — the same
  requirement `kubectl cp` already imposes, effectively universal in
  build images.

### Consequences

- Good: kubernetes runner works from outside the cluster and in-cluster
  without RWX storage — the two blockers this ADR exists to remove.
- Good: `runnerCmd` becomes functional on kubernetes runners with no
  special-casing — `RunnerCmdExecutor` drives the primitives generically.
- Good: `keepPodOnFailure` becomes genuinely useful once `markFailed()`
  wires the flag it always relied on.
- Good: build caches (e.g. Gradle home) remain expressible via the
  existing generic `volumes` field — no new caching feature needed.
- Bad: **BREAKING config change** — `workspaceVolume` is removed;
  migration is to delete the field from runner config.
- Bad: live artifact tailing mid-run is gone for kubernetes runners
  (artifacts now appear host-side only at download) — this brings
  kubernetes to parity with the SSH runner, which never had it.

## Pros and Cons of the Options

### Keep dual mode (rejected)

- Good: zero migration for any hypothetical existing PVC-mode user.
- Bad: the PVC path has no users today — keeping it is dead code with
  an ongoing maintenance and test-matrix cost for no benefit.

### Kubernetes client-java `Copy` API (rejected)

- Good: less code to own; library-maintained.
- Bad: no exclude filtering, no overlay control, no test seam — the
  parts that make sync match SSH semantics would be re-implemented on
  top of it regardless, leaving two tar code paths instead of one.

### rsync / sync sidecar (rejected)

- Good: would give incremental/delta sync on subsequent syncs.
- Bad: pods are per-run and start empty — there is nothing to delta
  against. All the cost of a sidecar (extra container, lifecycle, RBAC)
  for no benefit in this lifecycle.

## Security considerations

- Pod spec no longer carries agent secrets — `kubectl get pod` in the
  runner namespace stops being a secret-disclosure channel, and neither
  does etcd or any informer watching pod objects. Per-exec env still
  travels over the API-server websocket session (TLS), not into a
  stored object. The residual surface: the exec client passes the full
  merged env as repeated `env KEY=VAL …` arguments in the exec
  request's `command=` query parameters, which are part of the request
  URI — and Kubernetes audit events record `requestURI` at every audit
  level, including the common `Metadata` default, so any cluster
  auditing `pods/exec` at all captures the full per-exec env, not only
  at `RequestResponse`. Operators should scope the audit policy for
  `pods/exec` accordingly. RBAC on `pods/exec` remains the boundary
  that matters.
- Workspace content now transits the API server instead of a storage
  backend — no new trust party (the API server already brokers exec).
- Kept-for-debug pods retain workspace content, including any secrets
  agents materialized into files; the existing `keepPodTtlSeconds`
  bound is unchanged.

## Relationship to ADR-0003

This ADR supersedes the **workspace-sharing portion** of ADR-0003 —
specifically its "Workspaces are shared via a single RWX PVC..." decision
and the consequence "live tailing keeps working because pod stdout/stderr
are streamed to files on the shared PVC." The rest of ADR-0003 (the
Runner abstraction itself: one pod per agent run, `session.shell` as the
backend-agnostic seam, `HMR_API_URL` contributed by the runner session)
is unaffected and stays in force; ADR-0003's status is left `accepted`
rather than flipped to superseded, since only that one sub-decision is
replaced.

## More Information

- Related: [ADR-0003](0003-runners-as-execution-backends.md) (runner
  abstraction; workspace-sharing portion superseded here),
  [ADR-0033](0033-runner-lifecycle-primitives.md) (lifecycle
  primitives this ADR implements on the kubernetes session),
  [ADR-0034](0034-runnercmd-command-type.md) (`runnerCmd`, whose
  kubernetes sync-source story this ADR closes),
  [ADR-0042](0042-runner-extra-uploads.md) (`extraUploads`, adopted
  unchanged by the kubernetes runner here).
