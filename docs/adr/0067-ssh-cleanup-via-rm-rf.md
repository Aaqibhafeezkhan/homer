---
status: accepted
date: 2026-09-21
deciders: Martin Štrambach
---
# ADR-0067: The ssh runner removes a finished workspace with `rm -rf` over the exec channel, SFTP walk as fallback

## Context and Problem Statement

ADR-0032 gave the `ssh` runner one transport for workspace state: SFTP.
`cleanWorkspace()` — the `cleanAfterFinish` sweep of the remote project
root — was implemented on it as a recursive walk, one `readDir`/`remove`
round trip per file, serialized on the session.

A workspace that a build tool has populated is not a few hundred files.
An iOS checkout after a SwiftPM resolution is tens of thousands, and on
one deployment's Mac runner the sweep took 14–18 minutes per run — with the
process still WORKING and its workspace lock still held for the whole of
it, since `Process` releases the lock only after `AgentExecutor` returns
(processes 697, 701 and 710 on 2026-09-16: 862 s, 1111 s and 871 s
between the last command's end and FINISHED; 701 waited 650 s on a shared
lock for 697's sweep). ADR-0064 also notes that the whole walk shares one
transfer-deadline window, so a deep tree shrinks the stall window of its
own later round trips.

## Decision Drivers

- The host already has a shell: every command of the run went through
  it. `rm -rf` on the host is seconds for the same tree.
- Not every ssh target is guaranteed to be POSIX, and a failed removal
  must never fail a run that otherwise succeeded (the sweep is
  best-effort, `AgentExecutor`).
- Exec and SFTP must not overlap on the session (ADR-0064's watchdog
  closes the whole session on an SFTP deadline).

## Considered Options

- **Keep the SFTP walk** — one transport, no host assumptions; minutes
  per build-sized workspace, lock held throughout.
- **Probe once for `rm` like ADR-0041 probes for `setsid -w`**, and
  choose the path per session.
- **Try `rm -rf` over the exec channel, fall back to the walk on
  failure** — no probe; the first real attempt is the probe.

## Decision Outcome

Chosen option: **"Try `rm -rf`, fall back to the walk"**.

`SshRunnerSession.cleanWorkspace()` runs `rm -rf -- <remoteProjectRoot>`
through the session's `ShellExecutor` with its own bound
(`CLEAN_TIMEOUT`, 10 minutes). The SFTP walk runs only when that command
exits non-zero, the exec channel fails, the command hits its timeout, or
the output cap aborts it.
A probe was rejected: a host without `rm` fails the command at once and
pays one round trip, which is what a probe would have cost anyway, and
there is no per-session state to keep in step.

The exec channel and the SFTP walk never run concurrently on the
session: the walk starts only after the command has returned or its
channel is closed, so the ADR-0064 invariant holds. (The command's
*process* may outlive its channel after a timeout — see the accepted gap
below.)

### The path that reaches `rm -rf` is shape-checked twice

The SFTP walk deleted whatever it was pointed at too, but `rm -rf` on an
operator string deserves an explicit guard (security_misconfiguration,
CWE-732): a blank, `/`, relative or `..`-carrying `remoteWorkspaceRoot`
would turn every `cleanAfterFinish` sweep into deletion of the host
directory, silently, because cleanup is best-effort.

- **At config load**, `remoteWorkspaceRoot` must be absolute, normalized
  (no empty, `.` or `..` segments) and not `/` — `RunnerConfig.Ssh
  .validateRemoteWorkspaceRoot`, alias-aware like the other runner
  checks, so a violation names the runner.
- **Before the command runs**, the composed remote project root
  (`<remoteWorkspaceRoot>/<relative workspace path>`) must pass the same
  rule at depth ≥ 2, must lie below `remoteWorkspaceRoot`, and every
  component below `remoteWorkspaceRoot` is `lstat`ed over SFTP: a symlink
  anywhere on that path refuses the sweep (path_traversal, CWE-59 — `rm`
  resolves its argument's components, so a link planted by the agent that
  just ran as this user, or by another tenant of the account, would steer
  the deletion at the link's target). Components above the workspace root
  belong to the operator and are not checked (`/tmp` is a link on macOS).
  A failure refuses the sweep entirely — neither `rm` nor the walk runs —
  and surfaces as the cleanup warning `AgentExecutor` already logs. Fail
  closed: a workspace left on the host is recoverable, a deleted host
  directory is not.
- **Accepted residual:** the `lstat`s and the `rm` are separate round
  trips, so a same-UID race can swap a component in between — the same
  residual `PathSafety.assertNoSymlinkComponentsBelow` documents for local
  workspaces, and not a trust boundary (§12): the agent already ran as
  that user.

### Consequences

- A finished ssh run releases its lock seconds after its last command,
  not minutes; the next queued run on the same lock starts then.
- `rm` on PATH is a Homer-side expectation of the target host, alongside
  the shell itself. Where it is missing the behaviour is exactly the
  previous one.
- **Accepted gap — reaping does not cover this command.** The command is
  launched through the ADR-0041 reaping wrapper, whose pgid marker lives
  under `<remoteProjectRoot>/.homer/reap`, inside the tree being removed.
  A successful `rm` deletes its own marker (harmless); an `rm` that hits
  `CLEAN_TIMEOUT` may already have deleted it, in which case the group
  kill finds no pgid and the command keeps running detached until it
  finishes. The fallback walk then races that detached `rm` over the
  same tree and may fail mid-way when an entry vanishes between
  `readDir` and `remove`; the walk is best-effort and the `rm` finishes
  the job. Its blast radius is the tree it was given, so this is
  accepted rather than worked around with a marker outside the root.
- `sftpTransferTimeoutSeconds` (ADR-0064) no longer bounds the common
  cleanup path; it bounds the fallback walk.
