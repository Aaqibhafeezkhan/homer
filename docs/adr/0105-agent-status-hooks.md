---
status: proposed
date: 2026-11-12
deciders: Lukáš Vlk
consulted: ADR-0008, ADR-0020, ADR-0021, ADR-0023, ADR-0037, ADR-0039, ADR-0047, ADR-0050, ADR-0051, ADR-0052, ADR-0058, ADR-0060, ADR-0092, ADR-0098, ADR-0102
---

# ADR-0105: Agent-declared status hooks fire a target agent on every run status

## Context and Problem Statement

Homer can already react to a run's progress, but only from the outside or from a caller that
is still connected:

- **Completion continuations** (ADR-0023) fire a target agent when a *watched* sub-process
  reaches a terminal state. They are registered by the run that starts the sub, through the
  callback API, so they exist only where an agent asked for one — and only for terminal
  states of a process the registering run is watching.
- **Dispatches** (ADR-0021) need an operator to answer a question first.
- **Cron** starts a run on a schedule, not on a status.

What is missing is the reverse of a continuation: an agent declaring, in its own definition,
"when a run of *mine* reaches status X, start agent Y". The cases that want it are operator
workflows that must not depend on the run itself doing anything: notify a human when a run
fails, collect artifacts when a run finishes, clean up when a run is killed. Every one of them
has to work for a run started from *any* entry point — cron, HTTP, MCP, a console retry, a
`!agent` hop — because the declaring agent cannot know which one it will be.

The naive shape — the run's own last command calling back to start the target — fails on all
three counts: a killed run executes nothing, a failed run's commands did not all run, and a
crash loses the call. The delivery has to be recorded by Homer, with the status it rides.

## Decision Drivers

- **Every start path, one mechanism.** Five entry points converge on `AgentRunStarter.start`,
  but the *status* of a run is written in exactly one place: `ProcessManager` →
  `ProcessRepository`. Reacting to a status therefore belongs there, not in five callers.
- **A status transition must never fail because of a hook.** The transition is the durable
  truth about a run; a hook is a consequence of it. A missing target, a full queue or a
  refused start may not turn a `FINISHED` run into an error.
- **Exactly-once across a restart** (ADR-0023's model, ADR-0020's storage): the row is written
  in the same transaction as the status it rides, claimed before the target run starts, and
  replayed at boot.
- **The hook run is the triggering run's owner's work.** A hook must not be a way to make
  Homer start an agent as somebody else — that would be a privilege escalation across
  principals (ADR-0037, ADR-0039, ADR-0047).
- **Bounded, locally and explicitly** (ADR-0047 rule 4): a run's status can be reached once,
  but N runs of one agent fan out to N × (hooks per agent) rows and starts, and each of those
  starts is a run that can itself hook. Every multiplier gets a bound and a denial test
  (rule 8).
- **Never copy run data.** A hook's payload must not become a channel for a secret or for
  agent-written text to cross into another agent's env (rule 7, ADR-0052, ADR-0060).

## Considered Options

How a hook is declared:

- **A. On the agent definition, as a `hooks` list of one-key `{STATUS: agent}` entries** — the
  declaration is a property of the agent, so every run of it, from every entry point, carries
  the same behaviour, and the whole graph is visible at load time (which is what makes cycle
  rejection possible).
- **B. Per start, as a parameter of the run request** — more flexible, but the caller has to
  repeat it on every entry point, a cron or console-retry start would carry none, and the graph
  cannot be validated before it runs.
- **C. As a continuation registered by the run's own commands** — the status quo for
  completion continuations; fails exactly where hooks are needed (a killed or crashed run
  executes nothing).

Which statuses may be hooked:

- **A. All six `ProcessStatus` values, one shape** — the status a workflow cares about is not
  predictable (a cleanup hook wants `KILLED`; a triage hook wants `FAILED`), and the FSM is a
  closed set, so validation is a name check.
- **B. Terminal statuses only** — narrower, and it cannot express the two cases that need a
  hook most (`CREATED`, for a companion run that must start alongside; `WORKING`, for a
  watcher).
- **C. A separate vocabulary** (`onSuccess`, `onFailure`, …) — a second name set that must be
  mapped onto `ProcessStatus` at every use, and that cannot express `UNKNOWN`.

Where the hook row is written:

- **A. In the same transaction as the status write** (`ProcessRepository`) — a restart between
  the status commit and the fire loses nothing, which is the whole durability argument
  (ADR-0020's co-transactional rule).
- **B. In the service, after the transition returns** — simpler to place, but a crash in the
  window between the two writes loses the hook with no trace.

How the target is started:

- **A. `AgentRunStarter.start` with the triggering run's owner, `serverDerivedParent = true`
  and the declaring agent as the invocation chain** — one start path, so cost caps, cooldown,
  locks, parallel-run limits, the maintenance/drain gate (ADR-0092, ADR-0102) and the
  cycle/depth preflight (ADR-0008) apply exactly as they do to any other start.
- **B. A direct `ProcessExecutor.execute`** — bypasses every bound above.

How a hook chain is bounded:

- **A. The depth the run carries, read back from the run's own stored inputs** —
  `HMR_HOOK_DEPTH` is written into the started run's `input_params`, so the run *is* the depth:
  a fire reads the triggering run's row, takes its stored `HMR_HOOK_DEPTH` plus one, and drops
  the hook past `maxChainDepth`. Survives a restart, because the depth is the run's durable
  state, and it costs the status transition neither a column nor a read.
- **B. The invocation chain alone** (`invocationChain.size`) — the chain a fire builds is one
  entry, so it cannot express hook depth without inventing a chain of repeated names; and it is
  per-call state that a restart resets.
- **C. Load-time cycle rejection alone** — necessary, but it cannot see a hook target reloaded
  after the declaring agent loaded, so it is not a runtime bound.

## Decision Outcome

Chosen: **A** (declared on the agent), **A** (all six statuses), **A** (row in the transition's
transaction), **A** (`AgentRunStarter`), **A** (the depth read back from the triggering run).

- **Declaration.** `Agent.hooks: List<AgentHook>` — at most 16 entries, each a one-key mapping
  from a `ProcessStatus` name to a target agent name. The custom serializer rejects an entry
  with zero or several keys, a non-string value and a key that is not a status; `Agent.init`
  rejects a malformed target name and a self-target. A hook may name the same status more than
  once, which is how one status fans out to several agents.
- **Load-time graph validation.** `AgentHookValidation` runs over the scanned agent map after
  the walk: it rejects a hook whose target is not a loaded agent, and any agent that can reach
  itself through hooks (direct or transitive). It repeats until stable, because rejecting one
  agent can orphan another's target, and it rejects the whole *declaring* agent rather than
  dropping the offending entry — a partially loaded hook set would silently change what the
  agent does. The errors join `AgentScan.errors`, so `reload()` reports them.
- **Recording.** Every status write — the `CREATED` INSERT, `transitionStatus`, `killTransition`
  and `markInterruptedAsUnknown` — takes a hook lookup resolved from the current agent catalog
  at call time, and INSERTs one `pending_hooks` row per matching entry **inside its own
  transaction**, returning the row ids. A guard-rejected CAS (a kill that won the status race)
  records nothing: the run never reached that status. Neither does a write that does not move
  the status — a run's own start re-asserts `CREATED` right after the INSERT as its kill guard,
  and that is the same one reach of `CREATED`, so recording it again would fire the `CREATED`
  hooks twice for one run. The lookup reads no database, so it is
  safe inside the write lock, and resolving it per transition is what makes an agent reload
  take effect for runs already in flight.
- **Delivery.** After the commit, the ids go to `HookService.onRowsRecorded`, which loads each
  row and hands it to `HookFireService`. Every call into that service is isolated per row: a
  failure anywhere in a fire — the claim, the settle, the start — is caught and logged, never
  propagated into the status write that triggered it or into the rows behind it in the sweep,
  and the row it left unsettled is picked up by the retry sweep or the next boot (ADR-0023). A
  fire claims the row (PENDING→DISPATCHING CAS), so a
  live transition, the boot replay and the retry sweep cannot double-fire one hook. It then
  re-checks the target agent and the owner's grant **at fire time**, because a durable row can
  be replayed long after it was written and either may have changed since; a target that is
  gone, or a `canDispatchHop(owner, declaring, target)` denial, settles the row `FAILED` and
  starts nothing. The start carries the triggering run's owner, the triggering run as the
  parent (`serverDerivedParent = true`), the declaring agent as the invocation chain, and the
  flow root derived through `visibleOriginRootProcessId` for **that owner** — so a root the
  owner cannot see is dropped rather than inherited. §12.
- **Payload.** `HMR_HOOK_PROCESS_ID`, `HMR_HOOK_AGENT_NAME`, `HMR_HOOK_STATUS`,
  `HMR_HOOK_DEPTH`, and — when the run has them — `HMR_HOOK_FAILURE_REASON` (the stored
  pre-start failure sentence, already fixed-vocabulary and redacted, ADR-0098) and
  `HMR_HOOK_TAGS` (the run's stored tags, control characters stripped, capped at 1024
  characters, a tag that does not fit left out rather than cut). They are merged over the
  target's resolved declared inputs through a dedicated allowlist
  (`hookControlParams`), not by re-admitting the reserved `HMR_*` set: these names are
  server-minted, and a general allowlist on this path would let a hook set `PATH` or spoof an
  identity in the target's env. **No input value, artifact or command output is ever copied**,
  and the target does not inherit the triggering run's inputs — a hook target must be
  self-contained, so an unresolvable `required` input fails the hook rather than silently
  starting the target with the triggering run's data.
- **Bounds.**
  - At most 16 hook entries per agent, enforced at load.
  - `server.hooks.maxPending` (200): a global cap on PENDING rows, checked once per fan-out
    inside the transition's transaction. Rows over it are dropped with a log line — the
    transition they ride still commits.
  - `server.hooks.maxChainDepth` (8): a fire whose triggering run is already that deep is settled
    `DROPPED` before any attempt, since the next attempt would be refused for the same reason. The
    depth is derived at fire time from that run's stored `HMR_HOOK_DEPTH` — the run's own durable
    state, so a restart between a hook's start and its own transition cannot reset the bound, and
    a run whose row is gone (evicted, record-purged) reads as depth 0 and fires.
  - Transient start refusals (cooldown, cost cap, capacity, a full run queue, a full lock-wait
    queue, the shutdown drain) are released for a retry on `ContinuationFireService`'s doubling
    back-off, bounded by its `MAX_FIRE_ATTEMPTS` (8) — the same constants, not copies. Any other
    refusal fails on the first attempt.
  - `server.hooks.settledRetentionSec` (7 days): settled rows are pruned by the existing
    continuation sweep tick, in batches of 500, behind a read pre-check.
- **Boot.** `StartupReconciliation` runs the same two-step recovery as dispatches and
  continuations: settle rows left `DISPATCHING` by the restart (so the crash window between a
  start and its `markDispatched` cannot double-fire), fire the rows the `UNKNOWN` writes
  recorded, then replay every remaining PENDING row. A second boot finds nothing PENDING and
  fires nothing.
- **Failure isolation.** A lookup failure, a start failure or a fire failure is caught, logged
  and settled on the row; it never propagates into the status write (ADR-0023's rule for
  continuations, applied to the same shape). A `CancellationException` is rethrown so a
  cancelled transition is not reported as a hook failure.

**Why not the others:**

- **B (per start)** puts the declaration where the entry point is, so the same agent behaves
  differently depending on who started it, and the graph can only be validated after it is
  running.
- **C (continuation-shaped)** is the shape that cannot cover `KILLED` or a crash.
- **Terminal statuses only** cannot express a companion run or a watcher, which is half the
  reason to have hooks at all.
- **A separate status vocabulary** is a second name set for a closed FSM.
- **Recording after the transition** trades durability for a simpler call site — the one thing
  this feature is for.
- **A direct `ProcessExecutor.execute`** would skip the cost cap, cooldown, locks and the
  maintenance/drain gate, i.e. every bound Homer has on starting a run.
- **Bounding the chain with the invocation chain** is not expressible (the fire builds a
  one-entry chain) and is not durable; **load-time rejection alone** cannot see a target
  reloaded later.

### Confirmation

Each constraint has a test that asserts the denial path (ADR-0047 rule 8):

- a hook naming an agent the triggering run's owner may not start is refused at fire time: no
  run is created, the row settles `FAILED`, and nothing about the target's existence is
  returned to a caller;
- an owner whose grant is revoked between recording and delivery is denied at delivery;
- a hook run's owner is the triggering run's owner — never the target agent's own owner, and
  never wider;
- load rejects an unknown status key, an unknown target, a self-target, a direct cycle, a
  transitive cycle, an entry with zero or several keys, and more than 16 entries; a cascading
  rejection repeats until the map is stable, and a valid DAG loads;
- a burst past `maxPending` sheds the excess with a log line and the row count stays at the cap;
- a cooldown, `maxParallelRuns` or cost-cap refusal retries on the back-off and then fails after
  `MAX_FIRE_ATTEMPTS`; a refusal past `maxChainDepth` is dropped without an attempt;
- a restart between a transition and its fire fires the hook exactly once, and a second boot
  fires nothing;
- a secret input's value is absent from the hook's params, the row, the logs and any error text;
- oversized and control-character-laden tags and failure reasons are stripped or left out
  before they become `HMR_HOOK_TAGS` / `HMR_HOOK_FAILURE_REASON`;
- each transition records only the entries matching its status, and a guard-rejected CAS records
  none.

### Consequences

- Good: an agent declares its own status reactions once, and they hold for every entry point —
  cron, HTTP, MCP, console retry and `!agent` hops alike — because they ride the status write
  rather than a caller.
- Good: `KILLED` and `UNKNOWN` are expressible, so cleanup and restart triage stop needing a
  command that may never run.
- Good: the delivery guarantees are the ones continuations already have, so there is one model
  to reason about, and `FiredRecords` serves the claim CAS, the pending cap and the prune for
  both.
- Bad: **a run's status write can now start other runs**, so one busy agent's hooks can occupy
  its targets. The bounds above cap the queue and the chain; an operator still has to size
  `maxParallelRuns` and the cooldown of a hook target for the fan-out they declared.
- Bad: **a hook target cannot inherit the triggering run's inputs**, so a target that needs
  run-specific data must be reachable through `HMR_HOOK_*` metadata or fetch it itself. Input
  inheritance for hooks is a possible follow-up, deliberately not taken here.
- Bad: the `pending_hooks` table is a new growing table (schema v31) with its own retention
  path, and hook rows ride the process status transactions, which are the hottest write path in
  the server — the insert is one bounded `INSERT` per matching entry, cap-checked with a single
  `COUNT` per fan-out.
