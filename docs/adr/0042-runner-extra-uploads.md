---
status: accepted
date: 2026-08-15
deciders: Lukáš Vlk
---
# ADR-0042: Runner-level `extraUploads` — materialize host folders into remote workspaces

## Context and Problem Statement

Agents routinely depend on host resources that live *outside* the two
things the full-run sync policy (ADR-0033) transfers — the run's
workspace and the agent's own directory. The canonical example is a
deployment-shared tooling folder: `globalEnv` sets
`SCRIPTS_DIR=/app/scripts`, docker-compose mounts it read-only, and
agents `source "$SCRIPTS_DIR/git.sh"`. On the local runner this works
because host and execution filesystem are one world. On any remote
runner (ssh today, a future synced kubernetes mode) the path simply
does not exist, and env translation only rewrites the `HMR_*`
variables — so every such agent silently breaks the moment it is
pointed at a remote runner.

The same gap exists for sibling-of-agent-dir references
(`{{HMR_AGENT_DIR}}/../shared/...`): only the agent dir itself is
uploaded (to `.homer/agent`), so `../` escapes land on paths that were
never transferred.

Who owns the fix, and at what level of the config?

## Decision Drivers

- The *local* path is deployment knowledge (server config, compose
  mounts), not agent knowledge — agent definitions must stay free of
  host-absolute paths and portable across runners.
- The transfer mechanism and path translation already live per-runner
  (`syncExcludes`, `SshPathTranslator`); the fix should sit in the same
  place.
- Implicit host-layout contracts should become declared, validated
  dependencies — fail at config load, not mid-run.
- ADR-0033's split must hold: transfer *mechanism* in the session,
  sync *policy* in the executors.

## Considered Options

1. **Runner-level `extraUploads` config** (chosen) — operator declares
   `{local, remote, env?}` entries per runner; sessions expose them,
   executors upload them, `createWorkspace()` repoints the env var.
2. **Agent-level declaration** — precise per-agent transfer, but forces
   host paths or layout conventions into `agent.yaml`, exactly the
   coupling that made `{{HMR_AGENT_DIR}}/../` fragile. Deferred, not
   rejected — see Future work.
3. **Bake resources into the runner image / mount via `volumes:`** —
   works for kubernetes, does nothing for ssh, and version-skews
   against the repo. Remains available as a complement, not the
   general mechanism.
4. **Do nothing (document the limitation)** — leaves the first real
   remote-runner adopter unable to migrate existing agents.

## Decision Outcome

`extraUploads: List<ExtraUploadSpec>` on `RunnerConfig.Ssh` (v1; a
future sync-mode kubernetes runner adopts the same field):

```yaml
runners:
  build-box:
    type: ssh
    ...
    extraUploads:
      - local: /app/scripts      # host file or directory, must exist at startup
        remote: .homer/scripts   # relative to the remote projectRoot
        env: SCRIPTS_DIR         # optional: repoint this workspace env var
```

Key semantics:

- **Upload timing = workspace provisioning.** In full runs,
  `AgentExecutor` uploads the entries alongside the workspace + agent
  dir (policy stays in the executor per ADR-0033, via specs the
  session exposes). In `runnerCmd`, `RunnerCmdExecutor` uploads them
  immediately after `createWorkspace()`, *before* checks — a deliberate
  exception to ADR-0034's "no transfer before checks", because checks
  themselves may source the uploaded tooling and the env override
  below must be truthful whenever the session env is used. Entries are
  expected to be small; the trade-off is recorded here.
- **`env` rewrite is the load-bearing part.** `createWorkspace()`
  returns `env → <remoteProjectRoot>/<remote>` alongside the existing
  `HMR_*` overrides. Without it agents still see the host path.
- **Everything lands under `projectRoot`** — outside `workdir/` and
  `artifacts/`, so nothing is downloaded back, and `cleanWorkspace()`
  sweeps it for free.
- **Per-run re-upload is an accepted cost.** No caching layer;
  entries are static tooling, not data. Revisit only if a real
  workload proves it wrong. `syncExcludes` applies (the session's
  `upload` primitive always honors it).
- **Fail-fast validation at config load:** `local` must exist and be
  readable; `remote` must be relative, contain no `..` segment, and
  not resolve under the reserved paths `workdir`, `artifacts`,
  `.homer/agent`, `.homer/bin`, or `.homer/mcp.json`; `env` (when set)
  must be non-blank, not an `HMR_*` name, and unique across the
  runner's entries; `remote` must be unique across the runner's
  entries.

## Security considerations

- **Reserved paths protect the question tooling.** Extras upload
  *after* the workspace upload, so an entry targeting `.homer/bin` or
  `.homer/mcp.json` would overwrite `homer-ask` or the MCP config that
  `HMR_MCP_CONFIG` points the claude CLI at — and a replaced
  `mcp.json` redirects the CLI (callback token included, injected via
  `${VAR}` at run time) to an arbitrary endpoint. Operator-level v1
  makes this a foot-gun; agent-level would make it an attack. Both
  paths are reserved from day one.
- **Uploads follow symlinks** (`WorkspaceSync` dispatches on
  `isDirectory`/`isRegularFile`, which resolve links), so a symlink
  inside the declared folder uploads its *target's* content — e.g. a
  malicious symlink committed to a repo that an operator declares as
  an `extraUploads` source discloses host files to the remote. v1
  documents this in `SERVER.md` (declare only curated folders);
  link-skipping hardening is required before the agent-level field
  ships, where the source tree stops being operator-curated. (Landed
  2026-08-18: both runners' directory uploads now skip symlinks whose
  realpath escapes the uploaded folder.)
- **Extras are replicated to remote hosts with best-effort deletion**
  (`cleanWorkspace()` is `runCatching`; kept workspaces persist
  deliberately) and with local POSIX modes copied. Uploaded scripts
  are `source`d by agents, so on a shared remote host,
  writable-by-others extras or a writable `remoteWorkspaceRoot` mean
  code injection into every run. `SERVER.md` guidance: no secrets in
  `extraUploads`, keep `remoteWorkspaceRoot` `0700`, keep script modes
  owner-writable only.
- **`env` is deliberately not blocklisted beyond `HMR_*`.** An
  operator entry can rewrite `PATH` or `LD_PRELOAD`; in v1 the same
  operator already owns `globalEnv`, so a blocklist adds no boundary.
  Revisit when agent-level entries (author-controlled) arrive.

## Future work (recorded, not implemented)

- **Agent-level `extraUploads`**, merged with the runner's list:
  union keyed by `remote`, agent entry wins on collision; two entries
  rewriting the same `env` from different `remote`s is a load-time
  error. Agent-level `local` should allow templates
  (`{{HMR_AGENT_DIR}}`, env vars) so host layout stays out of agent
  files. Agent-level entries are agent-author-controlled reads of host
  files — today agent authors already run arbitrary host commands via
  the local runner, so this does not widen the trust model, but that
  assumption must be revisited if agent authoring ever becomes less
  trusted than server operation. **Binding constraint on that future
  work:** template expansion in agent-level `local` must resolve
  against server-side config env only (`globalEnv` + agent `env`),
  never request-supplied input params — otherwise any API caller with
  a run grant gets `local: {{param}}` as an arbitrary host-file-read
  primitive, escalating "agent authors are trusted" to "every caller
  is trusted", which the grants model (ADR-0037/0039) does not assume.
  Symlink-skipping on upload (see Security considerations) is likewise
  a prerequisite, not an option, at agent level.
- **Sync-mode kubernetes runner** picks up the same field unchanged.

## Consequences

- Good: implicit host-path contracts become declared, validated,
  per-runner dependencies; a factory deployment's
  `SCRIPTS_DIR` pattern works on remote runners without touching any
  agent definition.
- Good: agent definitions stay host-path-free and runner-portable.
- Good: sibling-of-agent-dir references get a clean migration path —
  give the shared resource its own env var and `extraUploads` entry
  instead of `{{HMR_AGENT_DIR}}/../` escapes.
- Neutral: the local runner ignores the mechanism entirely (no config
  field, no behavior change).
- Trade-off: `runnerCmd` sessions pay the extras upload before checks
  run (see Decision Outcome); operators who want check-first fail-fast
  on a runner must keep that runner's `extraUploads` empty.
- Trade-off: static content is re-uploaded every run — accepted for
  small tooling folders, no delta transfer (ADR-0032 already defers
  rsync-style sync).

## Amendment (2026-09-24): external pull-mode domains adopt the field

An `!<external>` domain ([ADR-0066](0066-external-pull-runners-and-unified-claim-queue.md))
declares `extraUploads` with this ADR's shape rules, reserved paths and boot-time
readability check; each entry's `env` is repointed at the copy under the claiming
instance's session root. The one difference is that a domain has no
`syncExcludes`, so an entry is always uploaded in full.

The recipient differs from an ssh host in kind: not an operator-declared machine
but any holder of the domain token that registers and announces a matching tag
(ADR-0066). That is the principal which already receives the workspace, the agent
directory and the run env, so the uploads add no new recipient — but the
"no secrets in declared folders" contract above is what keeps it that way, and it
applies to these domains unchanged.

## Amendment (2026-09-30): upload walks bound symlink aliases

`TarStreams.addDirContents` (Kubernetes / external runners) and
`WorkspaceSync.uploadDirInto` (ssh) apply the identical rule:

- a symlinked upload source is refused;
- every directory is resolved to its realpath, and the walk descends on
  that realpath;
- on the by-name walk a real directory is always descended;
- a symlinked directory whose realpath was already visited is skipped;
- inside a walk through a link, everything already visited is skipped;
- each directory is therefore walked at most once by name plus at most
  once through a link;
- entries are ordered non-links first, then by name, so the result is
  deterministic;
- the walk is capped at 64 levels (`MAX_UPLOAD_DEPTH`);
- an entry's name relative to the upload root is built by concatenating
  names on the way down, never by `relativize`: below a link the current
  directory is a realpath whose absolute form can differ from the root's,
  so `relativize` would yield a wrong (not failing) path and silently
  break anchored excludes.

**Why:** ADR-0047 §12 rule 4 (bound the walk) without dropping real
content. The previous global visited set dropped `packages/pkg` when
`node_modules/pkg -> ../packages/pkg` was listed first.

**Consequence:** an alias name (e.g. a venv's `lib64 -> lib`) can be
absent or partial on the remote. Content under its real name is never
lost.

**Rejected: recreate aliases as remote symlinks.** The tar extract path
deliberately skips link entries (ADR-0047), so the two runners would
diverge, and a remote link reopens link-following on sync-down.

**Rejected: one shared walker for both.** TarStreams packs into a stream
while ssh issues SFTP calls per entry, so sharing the walk needs a
visitor seam over both. It was deferred: parity is held by mirrored
tests in `TarStreamsTest` and `WorkspaceSyncTest`, and a rule change
must edit both walkers.
