---
status: accepted
date: 2026-07-27
deciders: Lukáš Vlk
---
# ADR-0034: runnerCmd — run one command on another runner

## Context and Problem Statement

ADR-0033 decomposed the runner contract into lifecycle primitives so that
a single command of an otherwise-local agent could execute on another
backend. This ADR records the command type built on those primitives:
`!<runnerCmd>`, with `check → syncToRemote → execute → syncFromRemote`
phases — pre-flight environment checks, selective upload, remote
execution, selective download.

## Decision Drivers

- Mixed local/remote agents (one step on a build box or GPU host) without
  running the whole agent remotely.
- Reuse the runner registry and session primitives — no parallel SSH/pod
  machinery, all backends (ssh, kubernetes, local) supported uniformly.
- Standard `Command` semantics (`continueIfFail`, `finishExitCode`,
  `resultEnvParam`, `when`) must keep working unchanged.

## Considered Options

1. **New Command subtype composing the ADR-0033 primitives** (chosen).
2. SSH-specific command with its own connection/sync code — duplicates
   the runner stack and drifts from it.
3. Agent-level runner only (status quo) — cannot express mixed pipelines
   or selective transfer.

## Decision Outcome

`Command.RunnerCmd` (kaml tag `!<runnerCmd>`, JSON `"type": "runnerCmd"`):
`runner` (alias), `check` (list of shell expressions, each must exit 0),
`syncToRemote` / `syncFromRemote` (workdir-relative paths, files or
directories), `execute` (script piped to `<shell> -s -`), `shell`
(default `sh`), plus the standard Command fields.

Key semantics:

- **Phase order** `openSession → createWorkspace → check → upload →
  execute → download → cleanWorkspace → close`. Checks run before any
  transfer (fail fast, no wasted sync) but after workspace creation (the
  ssh shell cd's into the remote workdir).
- **Check failure returns the check's exit code as the command result**
  instead of throwing, so `continueIfFail`/`finishExitCode` apply
  uniformly; transport/config failures (connect, upload, missing
  sync-back path) throw.
- **Env isolation:** the translated env overrides apply to a local copy
  used only for the target's check/execute calls — merging them into the
  agent env would repoint `HMR_*` paths for subsequent local commands.
- **Sync-back overwrites** (`overlay = false`): a file synced up,
  modified remotely, and pulled back must replace the local copy. The
  executor verifies each requested path exists locally afterwards — the
  download primitive silently no-ops on missing remote paths.
- **Remote workspace is per-command scratch**: always swept (best-effort)
  after the command, success or failure. Nothing tracks remote leftovers,
  so keep-on-failure would accumulate litter.
- **v1 restriction:** agents containing a `runnerCmd` must themselves run
  on `runner: local`, enforced at agent load — for non-local runners the
  server's workspace copy is stale mid-run, so sync sources would be
  wrong. Unknown target aliases and absolute/`..` sync paths are also
  load-time errors (agent skipped).

## Consequences

- Good: all three backends work through one executor; `local` targets
  make runnerCmd agents testable without a remote (and E2E-coverable).
- Good: two backward-compatible primitive extensions suffice
  (`download(overlay)`, ssh `createWorkspace` creating the workdir).
- Neutral: `HMR_AGENT_DIR` on the target points at a directory that
  exists only if the author syncs something there (agent dir is not
  auto-uploaded).
- Trade-off: checks and execute share the command's stdout/stderr files;
  a failing check's output is what remains in them. A check exit code
  that collides with `finishExitCode` is treated as finish — documented,
  accepted for v1.
