---
status: proposed
date: 2026-11-12
deciders: Lukáš Vlk
consulted: ADR-0041, ADR-0043, ADR-0047, ADR-0100
---

# ADR-0103: A lost exec channel kills the leftover command and fails the step — there is no reattach

## Context and Problem Statement

The Kubernetes runner runs every command over one exec websocket into the session's pod. The
channel can close without a `v1.Status` — an API server or proxy restart, an idle timeout — while
the pod and the command in it are still running. Homer then cannot see the command any more: it
cannot learn its exit status, wait it out, or bound what it does next. An issue proposal listed
three responses: report the loss, stop the leftover command, and optionally recover by reattaching
to files the command writes under `.homer/`. Reporting and stopping shipped; reattach was listed as
deferred or opt-in. Left unstated, that deferral reads as a pause — as if a channel loss left the
command alive for a reattach to come back to. This ADR records that it does not, and why the
rejected option stays rejected for now.

## Decision Drivers

- A channel death leaves the command unobservable: no Homer mechanism can read its exit status,
  apply its timeout, or bound what it does from then on. Leaving it running is unbounded work in
  the pod, with no owner left to stop it.
- The step already failed from the caller's point of view; a caller recovers by retrying, and a
  retry that re-runs the command is safe only if the previous incarnation is gone.
- Killing the command pod-wide reuses the abort path's existing kill (ADR-0041); any reattach
  mechanism would have to introduce per-command state in the pod (ADR-0043's exec-only transport
  is the only channel there is).
- Reattach changes every Kubernetes step — the process tree, signal delivery, disk use in the pod,
  streaming latency — and must not change what the command itself sees; the issue's compatibility
  notes make it opt-in at best.

## Considered Options

- **Kill and fail** — kill the leftover command pod-wide and fail the step with a reason naming
  the loss.
- **Wait for the channel to come back** — leave the command running, retry the exec, stream what
  remains.
- **Reattach via files** — each command writes its exit status and an output copy under `.homer/`
  in the pod; on a loss, a fresh exec waits for the exit file, bounded, and streams the remaining
  output from the file.

## Decision Outcome

Chosen option: **kill and fail**. On a channel lost while the pod still runs, the leftover command
is killed pod-wide (the same `kill -KILL -1` the abort path uses, ADR-0041) before the step fails,
and the step and the run's `runner.termination` carry the reason `ExecChannelLost` with the cause.
**There is no reattach: a command still running when its channel dies is killed, never waited
for.** This is a decision, not a pause — no reattach is planned, and a channel loss never leaves a
command alive on the chance of one.

Homer reports the loss instead of the command's outcome because it cannot know the outcome: a
command that really exited just before the channel died reads as `ExecChannelLost` too. That is
accepted — the alternative, waiting or reattaching, is the rejected option below.

The reattach option is **rejected for now, not abandoned**: if it is ever built, it ships
separately and opt-in via a runner setting (the issue's own compatibility notes require it), and it
must then consciously undo this ADR's kill-first behaviour and the `ExecChannelLost` failure
semantics for the steps it opts in.

### Consequences

- Good: no run leaves an unobservable command running in the pod; every step's end is Homer's own
  doing, and a retry cannot overlap the command it retries.
- Good: the failure is reported with its cause and the pod's last event, so an operator needs no
  server log to tell a lost channel from a failed command.
- Bad: a command that finished just before the loss reports `ExecChannelLost` instead of its exit
  code; the caller retries a command that may have half-done its work. Callers whose steps are not
  idempotent get no recovery here.
- Bad: reattach, when someone wants it, must undo a documented decision and its tests, not extend
  a deferred one.

## Pros and Cons of the Options

### Wait for the channel to come back

- Good: no kill of a command that may have been about to exit; the outcome could still surface.
- Bad: unbounded — a proxy restart may not heal within any bound Homer could wait, and the command
  runs unsupervised meanwhile, past its own timeout's enforcement point.

### Reattach via `.homer/` exit-status and output files

- Good: a long-running command survives a channel loss and reports its real exit code and full
  output; retries shrink to genuine failures.
- Bad: it wraps every command in the pod, changing the process tree and signal delivery (timeouts,
  graceful stops), adding disk use an agent-writable directory can inflate, and adding streaming
  latency; it must survive the exec-only transport and stay opt-in (ADR-0043, ADR-0047 rule 2 for
  the agent-writable files it would read). A change to every step is not a fix for a reporting bug.

## More Information

- [Issue #146](https://github.com/livesport/homer/issues/146) — the lost-channel issue whose item 3
  this defers.
- [ADR-0041](0041-process-group-reaping-on-kill.md) — the pod-wide kill path reused here.
- [ADR-0043](0043-k8s-sync-workspaces.md) — the exec-only transport reality any reattach must fit.
- [ADR-0100](0100-kubernetes-job-workload-and-pod-run-limit.md) § Security — the two provenance
  classes of `runner.termination`, covering `ExecChannelLost`'s executor-built message.
