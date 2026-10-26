---
status: proposed
date: 2026-10-26
deciders: Lukáš Vlk
consulted: ADR-0047, ADR-0066
---

# ADR-0096: Unhealthy runners stop claiming, and idle consumers take turns

## Context and Problem Statement

A pull-runner instance (ADR-0066) whose JVM could no longer start processes
(`Failed to exec spawn helper … signal: 9`) stayed registered and kept
claiming runs. It claimed first and failed each run within a second, so the
queue drained into one broken instance while healthy instances sat idle
(issue #73). Restarting the JVM fixes it. Nothing on the server can tell
"this host is broken" from "this agent's argv is bad".

## Decision Drivers

- The restart is the fix, and only the runner host can tell it is needed.
- The server has no classification of a failed run as broken host versus bad
  argv, and must not guess one.
- ADR-0066 forbids re-enqueueing a claimed run.
- Availability is in scope (root CLAUDE.md §12): one broken same-UID host must
  not take work from healthy ones.

## Considered Options

- Quarantine an instance on the server after N failed runs
- Restart runners periodically
- Retry or re-enqueue a failed run on another instance
- A check inside the session on a start failure (the ticket's second step)
- Runner self-check before every claim, plus a fair handoff among idle
  consumers (a wake-all race, the oldest waiter, or the least recently claimed)

## Decision Outcome

Chosen option: **a runner self-check, plus a least-recently-claimed handoff**.

1. **Part 1, the runner.** Before registering and before every claim,
   `homer-runner` starts `/bin/sh -c "exit 0"` with an empty environment
   (the token never reaches a child, §12 rule 7), waiting at most 5 s, and
   creates and deletes a file under `--root`. When either fails it stops
   claiming, deregisters, lets in-flight runs finish within `--drain-timeout`
   and exits `3` for its supervisor to restart. The probe's exit value is
   ignored: the check is about the spawn mechanism.
2. **Part 2, the claim queue.** A run that arrives while several consumers are
   idle goes to the one that claimed least recently, instead of to whichever
   woke first; ties go to the oldest waiter. A run handed to a parked poll
   counts as that consumer's claim at once, so an instance with several
   parked polls does not take a whole burst. Idle instances take turns, so a
   broken one cannot win every race.

### Consequences

- Good: a broken instance is out within one claim.
- Bad: a host without `/bin/sh`, or with a full disk, crash-loops under its
  supervisor instead of staying up and failing runs.
- Good: static runners (local, ssh, kubernetes) whose tags match one run now
  take turns too.
- Bad: least-recently-claimed hands a broken instance about 1/(N+1) of runs
  while N healthy instances are idle, whatever its concurrency, and every run
  that arrives while they are busy. It limits the damage; Part 1 removes the
  cause.
- Bad: a poll that is handed a run and then cancelled before it claims still
  marks its consumer as recently claimed; the run itself goes to the next
  parked match.

## Pros and Cons of the Options

### Server quarantine after N failures

- Bad: cannot tell a broken host from a bad argv; quarantines healthy hosts.

### Periodic restarts

- Bad: leaves a window of failed runs and restarts healthy runners.

### Retry or re-enqueue

- Bad: violates ADR-0066 (a claimed run is never re-enqueued).

### In-session check on a start failure

- Bad: subsumed by the pre-claim check, which also catches the failure before
  a run is lost.

### Wake-all race or oldest waiter

- Bad: a wake-all race favours the fastest, which is the instance that fails
  fastest. The oldest waiter is no fairer: a waiter's age resets on every 30 s
  long-poll, so poll timing decides the order, not who last got work.

## More Information

- Issue #73. Threat model: [ADR-0047](0047-security-by-design-and-threat-model.md).
  Claim queue and pull runners: [ADR-0066](0066-external-pull-runners-and-unified-claim-queue.md).
