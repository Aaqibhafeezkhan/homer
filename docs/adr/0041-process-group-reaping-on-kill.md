---
status: accepted
date: 2026-08-13
deciders: Lukáš Vlk
---
# ADR-0041: Reap orphaned processes via the process group

## Context and Problem Statement

On kill or timeout, `LocalShellExecutor` tore down a command by calling
`process.destroyForcibly()` and then `destroyForcibly()` on each entry of
`process.descendants()`. That snapshot misses two cases: a child spawned in the
window between snapshotting descendants and killing them, and a
double-fork/`setsid`-detached or reparented child that is no longer in the
process tree. Such children survive as orphans, leaking CPU/memory on the host
until they exit on their own. `SshShellExecutor` and `KubernetesShellExecutor`
share the same shape of concern.

## Decision Drivers

- Reaping must not change the exit code, stdout/stderr, signal, or stdin
  semantics of any command that runs today.
- It must degrade gracefully where the mechanism is unavailable (e.g. macOS dev
  has no `setsid`).
- It must never target the JVM's own process group.

## Considered Options

- **Local**: launch each command under `setsid` so it leads its own process
  group; kill the negative pgid to reap the whole group.
- **SSH**: rewrite the remote command (a `setsid`/`trap`+`kill -- -$$` wrapper)
  so a backgrounded remote child is reaped when the channel closes.
- **Kubernetes**: rely on the existing in-pod reaping.

## Decision Outcome

**Local — adopted.** Commands are launched as `setsid <argv>` when `setsid` is
on `PATH`. A non-forking `setsid` (util-linux and BusyBox both, for a child that
is not already a group leader — the JVM's children never are) `exec`s the
command in place, so the command keeps its own PID, exit code, redirected
stdout/stderr, and stdin pipe; only the session/controlling-terminal changes,
which is inert for these non-interactive, file-redirected commands. The command
becomes its own group leader (`pgid == pid`). On kill/timeout the executor sends
`kill -KILL -<pgid>` before the existing `destroyForcibly()` + descendants
sweep. `ProcessGroupSupport` returns a pgid to kill **only** after confirming
`pgid == pid` via `/proc/<pid>/stat`, so a group kill can never hit the JVM's
own group. Where `setsid` is absent, the launch is unchanged and the executor
falls back to the descendants() kill (prior behavior). Validated on Alpine
(production base) and Debian: the reparented grandchild (ppid 1, missed by
descendants()) stays in the leader's group and is reaped by the group kill.

**Kubernetes — no change, confirmed fine.** On kill, `KubernetesShellExecutor`
already runs `kill -KILL -1` inside the pod's PID namespace, and pod deletion
tears down the whole namespace regardless. Orphans cannot escape the pod.

**SSH — adopted via an out-of-band control-channel group kill.** MINA exposes no
client-side signal API, so `channel.close(true)` is the only lever on the exec
channel itself, and a backgrounded remote child can outlive it. The rejected
in-band wrappers (`set -m` + backgrounded `wait`, or `trap`+`kill -- -$$`) all
drop the `exec env … argv` form and background the command — breaking stdin
delivery (background jobs get stdin from `/dev/null`) and turning signal-death
into exit `128+n`. Those are correctness hazards, not just a compatibility
concern, so backgrounding is out regardless. Instead the command is launched
foreground as its own remote process group — `setsid -w` runs it as a new
session leader and waits, so exit code and signal propagate unchanged, and an
inner `sh` records the leader's pid (== the group id) to a workspace side-file
before `exec`ing the real argv in place. On kill/timeout the executor reads that
pgid over a fresh exec channel and `kill -KILL -<pgid>`s the whole group before
closing the command channel, so a detached child is reaped rather than orphaned.
This is gated by a one-shot capability probe at `openSession` (`setsid -w true`);
where `setsid -w` is absent the launch is unchanged and teardown degrades to the
previous `close(true)`-only behavior, mirroring the local runner's graceful
fallback. Reaping is unconditional where supported — there is no config flag.

### Consequences

- Good: on the local and SSH runners, detached/late-spawned/reparented children
  are reaped on kill and timeout instead of leaking; k8s already reaps the pod.
- Good: no behavioral change for any command that completes normally; the local
  `/proc` pgid check and SSH's foreground `setsid -w` keep exit-code/stdin/signal
  semantics identical to before.
- Bad: both improvements are Linux/`setsid`-effective only — where `setsid`
  (local) or `setsid -w` (SSH) is missing, teardown silently falls back to the
  prior behavior.
- Bad (SSH, minor): a command killed in the sub-millisecond window before its
  `sh` writes the pgid side-file still can't be group-reaped; the timeout/cap
  kills that motivate this fire long after the command has started, so the file
  exists in practice.

## Pros and Cons of the Options

### SSH in-band reaping wrapper (rejected)

- Good: would reap backgrounded remote children on channel close.
- Bad: `set -m`+`wait` and `trap`+`kill -- -$$` both background the command and
  drop `exec`, breaking stdin delivery and mangling exit-code/signal propagation
  across remote login shells. Superseded by the out-of-band control-channel kill
  above, which keeps the command foreground.

## More Information

`ProcessGroupSupport`, `LocalShellExecutor`, `KubernetesShellExecutor.killInsidePod`,
`RemoteCommandBuilder.buildReaping`, `SshShellExecutor`, `SshRunner.canReapProcessGroup`.
