# One lock across several agents

A team that builds a mobile app runs its ticket pipeline on Homer: a
LangGraph orchestrator dispatches one worker agent per stage, and the iOS
stages run on a single macOS build host. Two agents ran there, one building
and verifying the app, one recording snapshot tests. Each declared
`maxParallelRuns: 1`, and the comment beside both said the Mac could take one
build at a time.

It could not. Two runs of the two agents, on two different tickets, overlapped
on the host for four and a half minutes. Their workspaces were separate, but
both runs used the same home directory on the Mac, so both resolved binary
dependencies into one shared package cache. The second writer found files the
first had just written, a dozen targets failed with "already exists in file
system", and project generation aborted. The second ticket ended red for a
reason that had nothing to do with it.

## The problem

Neither setting did what the comment claimed:

- **`maxParallelRuns` is per agent.** It caps how many runs of *one* agent
  execute at once ([Parallel-Run Limiting](../../app/AGENT.md#parallel-run-limiting)).
  Two runs of two different agents never contend for it.
- **A per-ticket lock key only collides on the same ticket.** Both agents
  held `ticket-{{TICKET_ID}}`. That key exists so that two runs on one ticket
  cannot race; runs on different tickets render different keys and run in
  parallel, by design.

The shared resource was the host, and nothing was keyed on the host.

## The setup

Homer's lock table is keyed on the rendered key string alone, and the keys are
global across agents, not namespaced per agent
([Security notes for input authors](../../app/AGENT.md#security-notes-for-input-authors)).
So a constant key that every agent touching the host declares makes those
agents mutually exclusive:

```yaml
# agents/ios-verify/agent.yaml (excerpt) — and the same block,
# verbatim, in every other agent that runs on the macOS host
runnerTags: [ios]
runnerClaimTimeoutSec: 900

lockStrategy:
  key: "build-host-macos"   # constant: one holder for the whole host
  onCollision: WAIT

maxParallelRuns: 1
```

Every other worker keeps its per-ticket key:

```yaml
# agents/scope/agent.yaml (excerpt) — a worker that does not use the Mac
inputs:
  - paramName: ticket_id
    envName: TICKET_ID
    type: string
    required: true

lockStrategy:
  key: "ticket-{{TICKET_ID}}"
  onCollision: SKIP_CURRENT
```

The orchestrator itself declares no `lockStrategy` at all (see below).

| Agent | Key | `onCollision` | Serializes |
| --- | --- | --- | --- |
| orchestrator (`!<langgraph>`) | none | — | nothing; every resume runs |
| Linux workers | `ticket-{{TICKET_ID}}` | `SKIP_CURRENT` | two stages of one ticket |
| macOS host workers | `build-host-macos` | `WAIT` | everything on the host |

## Why `WAIT` and not `SKIP_CURRENT`

With `SKIP_CURRENT`, the second run does not run. Homer records it as a run
that ended `FAILED` with "The process N is still active, stopping current".
For a worker dispatched by the orchestrator, that is a stage with no result.
The graph has no verification to read, and it ends the ticket failed: the
same innocent second ticket the lock exists to protect, now failing for a
different reason.

`WAIT` queues the second run until the first releases the key. The second
ticket pays one build's wall-clock (ten to thirty minutes on that host) and
nothing else. `SKIP_CURRENT` is right where a skipped run loses nothing,
like a cron tick that the next tick will repeat. `KILL_OLD_RUN_CURRENT`
would kill a build halfway, which is worse than either
([Lock Collision Strategies](../../app/AGENT.md#lock-collision-strategies)).

## The trap: a templated key on the orchestrator

The obvious way to stop two pipelines racing on one ticket is a templated key
on the orchestrator, `ticket-{{TICKET_ID}}`. On a LangGraph agent that
breaks.

A parked graph resumes as a new run of the same agent, re-run from the top
([Resume re-runs the whole agent](../../app/AGENT.md#resume-re-runs-the-whole-agent)).
That run carries the thread id (`HMR_LG_THREAD`), the resume payload and the
interrupt id, not the inputs the first run was started with. `TICKET_ID` is
therefore unset on every resume, and Homer leaves a placeholder whose variable
is unset exactly as written. Every resume of every ticket renders the same
key, and they all serialize behind one lock. With `SKIP_CURRENT`, most of them
would be dropped instead.

Both deployments behind these case studies hit the same question and came to
the same answer: no lock on the orchestrator. Per-ticket exclusion comes from
two other places:

1. **The entry check.** A fresh run refuses a ticket that is already marked
   as in progress, before it dispatches anything.
2. **The worker locks.** Workers are dispatched with the ticket id as a
   parameter, and a dispatched run receives a parameter only through an
   input it declares
   ([Ask and dispatch](../../app/AGENT.md#ask-and-dispatch)). So the ticket
   id is always set on a worker and the key renders correctly there.

The orchestrator's ticket input stays `required: false`: a resume carries no
caller inputs, so a required input would reject every resume. The fresh-run
step that validates it is gated with `when: '[ -z "$HMR_LG_THREAD" ]'`.

## Details that matter

- **Where the lock sits in a run's start.** A run takes its parallel-run slot
  first, then the lock, then waits in the
  [runner claim queue](../../app/SERVER.md#runner-claim-queue). A run
  waiting on the lock stays `CREATED`, so it shows up in
  `GET /api/v1/status/all?types=CREATED` and nowhere else.
- **A `WAIT` lock has no timeout of its own.** The run waits until the holder
  ends. The claim TTL (`runnerClaimTimeoutSec`) starts only after the lock is
  held, so a long queue on the lock does not eat into it. Bound the holder
  instead, with the `timeout` of its commands.
- **The lock lives in the Homer server, not on the host.** It serializes runs
  of this Homer on any runner: it held while the host was an `ssh` runner and
  still holds now that it is a pull-mode `homer-runner`. Runs started by a
  second Homer, or by anything outside Homer, are not covered.
- **Release is in a `finally`.** A run that fails or is killed releases the
  key, so a crash does not wedge the host. A server restart clears the table
  with everything else in memory.
- **Runner concurrency is the other tool.** A runner with one slot
  (`concurrency: 1`, or `HOMER_RUNNER_CONCURRENCY=1` on a pull-mode runner)
  also allows one run on the host. The difference is the wait: a run queued
  on a saturated runner fails at the claim TTL, while a run queued on a
  `WAIT` lock waits as long as it takes. The lock also keeps working when the
  host later gets a second slot for something that does not touch the cache.
- **A shared key across workers is not the same as a key per worker.** One
  of the two deployments keys each worker on its own name and the ticket
  (`plan-{{TICKET_ID}}`), which only stops a duplicate run of that one stage.
  The other gives every worker the same `ticket-{{TICKET_ID}}`, which also
  keeps two different stages of one ticket apart. Pick by what the stages
  share.
- **`acceptParentLock` does not help here.** It lets a sub-agent pass a lock
  its ancestor already holds, but only in a `RunAgent` flow. A LangGraph
  fan-out dispatches its workers on a fresh invocation chain
  ([Dispatching another agent](../../app/AGENT.md#dispatching-another-agent)).
- **Homer does not enforce the rule.** Any agent added to that host must take
  the host key, or the problem returns silently. It is a convention the
  agent authors keep, so write it next to the key.

## What it does not solve

- **Two tickets that need the host serialize.** A queue of two is fine; a
  queue that never empties means the pipeline has outgrown one build host.
  The answer then is a second host, not a weaker lock.
- **The lock does not repair a cache already broken** by an earlier
  collision. Stale entries have to be cleared on the host once.
- **A lock only covers runs that declare it.** A manual build on the host, or
  an agent whose author forgot the key, still writes into the same cache.
