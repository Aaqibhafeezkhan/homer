---
status: accepted
date: 2026-10-03
deciders: Lukáš Vlk
---
# ADR-0078: The runner JVM is non-dumpable, and refuses to start when it cannot be

## Context and Problem Statement

On an external pull runner (ADR-0066) every run executes as the same uid as the
`homer-runner` JVM. `ProcessRunner` clears each command's environment, but the
JVM's own initial environment keeps `HOMER_RUNNER_TOKEN`, and a same-uid process
can read it from `/proc/<pid>/environ` or attach with ptrace. A run steered by
hostile input could therefore steal the domain token and register a rogue runner
instance — which lets Homer *act*: the rogue instance claims runs from the queue,
receives their workspaces, inputs and per-run Homer API tokens, and can return
forged results. That is an escalation past the same-uid line ADR-0047 records as
"not a boundary": the principal crossing here is not the run reading its own
host's state, it is the run answering for runs it was never assigned.

## Decision Drivers

- The domain token must not reach run processes (ADR-0045, ADR-0047 rule 7).
- The hardening must work under the cluster pod policy: dropping all
  capabilities, `NoNewPrivs=1`, seccomp `RuntimeDefault` — so a per-run uid,
  a setuid helper and unprivileged user namespaces are all unavailable.
- Runs must behave exactly as before: `execve` resets the dumpable flag, so a
  spawned command is unaffected.
- Fail closed: a runner that cannot apply the hardening must not read the token.

## Considered Options

- Per-run uid (a uid pool per slot) — needs `CAP_SETUID`/`CAP_SETGID`, dropped
  by admission; a setuid helper is neutralised by `NoNewPrivs`; `unshare -U` is
  blocked by seccomp. Not viable under the current policy.
- Deliver the token by inherited fd or a `/run/secrets`-style credential file
  instead of the environment, so `/proc/<pid>/environ` never holds it — deeper
  defence, but it relocates a secret into a file or manifest (ADR-0045) and
  does not close the ptrace path on its own.
- Filter `HOMER_RUNNER_TOKEN` out of the JVM's in-process environment copy —
  `System.getenv` returns an unmodifiable view, so this needs unsafe hacks and
  still leaves `/proc` holding the original.
- One pod per run (the old kubernetes-runner model) — sustainable, but a
  deployment redesign explicitly out of scope for this ticket.
- `prctl(PR_SET_DUMPABLE, 0)` at JVM startup, before the token read.

## Decision Outcome

Chosen option: **"`prctl(PR_SET_DUMPABLE, 0)` at JVM startup, before the token
read"**, because it needs no privilege the pod policy grants, denies both the
`/proc/<pid>/environ` read and ptrace for every same-uid process, and leaves
spawned commands untouched (`execve` resets the flag for the child).

- The runner JVM calls `prctl(PR_SET_DUMPABLE, 0)` via an FFM downcall
  (`--enable-native-access=ALL-UNNAMED` is already granted by the image
  entrypoint and the test JVM) and verifies the call took effect through
  `PR_GET_DUMPABLE`, not the kernel's `/proc/self/status` `Dumpable:` field
  (absent before Linux 6.2 and masked in some containers).
- **Fail closed, exit `1`:** on Linux, a call that fails, throws or does not
  take effect stops the startup before the token is read. A non-dumpable runner
  that says nothing is the exposure this closes; an operator must never get a
  silent exception to the control.
- **Non-Linux hosts skip, with a warning.** `prctl` is Linux-only, and developer
  machines must not crash. This is the one accepted gap: on macOS/BSD the JVM
  stays dumpable and holds the token. It is accepted because external runners
  run on Linux in production, and closing it properly means the token-by-file
  option rejected above (ADR-0045).
- This is a refinement of ADR-0047, not a change to its boundaries: same-uid
  access stays "not a boundary" in general, but a same-uid run reading the
  runner's *domain token* is treated as an escalation because the token lets the
  run make Homer act (ADR-0046's clause), not merely read its own host.

### Consequences

- Good: the runner's token is unreachable to every run on the instance; the
  startup log line `homer-runner JVM is non-dumpable` proves the hardening.
- Good: no new image capability, base-image change or protocol change.
- Bad: a same-uid operator debugging tool (gdb, `jstack`, `/proc/<pid>/mem`)
  no longer works against a running runner JVM; restart with a debugger-
  friendly setup instead.
- Bad: a non-Linux runner keeps the token in a dumpable JVM (accepted risk,
  recorded above).

## More Information

ADR-0047 (security by design, rule 8 — the denial-path test),
ADR-0045 (external secret delivery), ADR-0066 (external pull runners).
