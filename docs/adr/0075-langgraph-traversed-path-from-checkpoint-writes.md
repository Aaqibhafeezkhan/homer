---
status: accepted
date: 2026-10-03
deciders: Lukáš Vlk
---
# ADR-0075: The traversed path comes from checkpoint routing writes

## Context and Problem Statement

ADR-0044's run-status overlay colors nodes by status (`done`, `failed`,
`parked`, `pending`, `unreached`), and ADR-0073 ships the topology as
structured data for the console to draw. Neither tells a viewer which
*edges* a run actually took. Through routers, loops, and alternative
branches, a user looking at the drawn graph cannot tell whether a run went
`plan → gate → implement` or `plan → implement` directly, nor which
iteration of a loop-back edge fired for a given run.

## Decision Drivers

- Must reflect what the run's checkpoint history actually recorded, not a
  guess derived from which nodes happen to have run or when.
- Must handle routers (conditional edges), joins (multiple branches into one
  node), fan-out (`Send`), and loops — not just linear chains.
- The field is a display aid: a wrong, partial, or missing value must never
  become a correctness input elsewhere.
- Every bound a caller can multiply must be local to Homer (root CLAUDE.md
  §12 rule 4) — Homer cannot rely on the runtime's own output already being
  bounded to the drawn topology.

## Considered Options

- Console-only heuristic: an edge is "taken" when both its endpoints were
  reached by the run.
- Superstep adjacency: a node that ran in step `k` and a successor that ran
  in step `k + 1` are joined by a taken edge.
- Checkpoint routing writes: read each checkpoint's pending writes and
  recover which routing channel(s) a task actually wrote, mapping
  LangGraph's own channel-naming conventions back to edges (chosen).

## Decision Outcome

Chosen option: checkpoint routing writes, because it is the only one that
reflects what LangGraph's own routing mechanism recorded, rather than
inferring it from timing or from which nodes were merely reached.

The runtime's `state` command emits a new `traversed` field: a list of
`{"source": "<node id>", "target": "<node id>"}` pairs read from the
thread's checkpoint writes. Which tasks ran and whether each completed comes
from the `get_state_history()` fold ADR-0044 already uses for per-node
status; the writes themselves come from a second read, `saver.list(...)` over
the thread's root-namespace checkpoint tuples, whose `pending_writes` hold
the raw channel writes. For each checkpoint tuple, its pending writes are
inspected:

- A task writing a `branch:to:<target>` channel routed to `<target>` — this
  is how LangGraph records a conditional edge's chosen destination.
- A task writing a `join:<sources>:<target>` channel feeds the join at
  `<target>` — the writing task's own node is the source of that edge.
- A `Send` packet written to the tasks channel (`__pregel_tasks`) targets
  `Send.node` — this is how fan-out destinations are recorded, independent
  of any static edge.
- A task that completed (has a result) in that checkpoint and
  wrote none of the above routing channels ended its branch:
  `<node> → __end__`. *(Amended 2026-10-09: the original "no error" condition
  was dropped in commit `209604e5`, so a node recovered after a failure still
  ends its branch.)*

These channel names and the `__pregel_tasks` packet shape were verified
empirically against the installed runtime (langgraph 1.2.5); they are
LangGraph-internal implementation detail, not a documented public contract.
`langgraph` is not version-pinned in `clients/python/pyproject.toml`, so this
coupling must be re-verified whenever the installed LangGraph is bumped.

When the history has completed tasks but not a single recognized routing
write, `traversed` is `null` (unknown) rather than a guess. That cannot happen
with today's channel names — `__start__` always writes `branch:to:<first
node>` — so it only fires after a rename, where the `→ __end__` rule above
would otherwise show every completed node, including a router with a
conditional edge to `__end__`, taking an edge it never took.

Pairs are kept only when they are edges of the drawn topology (ADR-0073);
deduplicated and sorted by `(source, target)` before being returned. An
empty list means the fold completed and found no qualifying routing writes
yet (a run with no completed step, say) — not a failure. Homer's backend
filters the field again against that same, already-bounded topology before
it reaches the console — the bound is local to Homer, not inherited from
the runtime's output (§12 rule 4). The console colors only edges that are
both in the topology it draws and present in `traversed`.

### Consequences

- Good: the colored edges match what the run's own checkpoint recorded as
  taken, correct through routers, joins, fan-out, and loops.
- Good: filtering twice (runtime against topology, Homer against topology)
  means a channel-name coincidence or a forged write can at most highlight
  a real edge of that run's own graph — it can never introduce a node or
  edge the topology doesn't have.
- Bad: depends on LangGraph-internal channel-naming conventions
  (`branch:to:`, `join:...:`, `__pregel_tasks`) that carry no public-API
  stability guarantee, and `langgraph` is unpinned. A future LangGraph
  version that renames them leaves no recognized routing write, and
  `traversed` degrades to `null` rather than breaking the `state` call or
  showing wrong edges. An exception during extraction also yields
  `traversed: null`; either way the rest of the status response is
  unaffected, because the field is a display aid, never a correctness
  input. A partial rename (only some channels) can still drop pairs, so
  re-verify on every LangGraph bump.
- Bad: one extra read of the thread's checkpoint tuples (`saver.list`) per
  (cached) `state` call, inside the same subprocess — no new subprocess.
- Neutral — residual per-checkpoint cost: the scan's write cap counts each
  write only once the store has already materialized the containing
  checkpoint tuple, so one forged checkpoint with a huge `pending_writes`
  list still costs its full deserialization on every poll before the cap
  can trip (`saver.list` is called with `limit=MAX_TRAVERSED_CHECKPOINTS`
  to bound the tuple count at the SQL side, but the writes inside each
  returned tuple are deserialized by the store, not by the scan). A bound
  enforced after another component built the data is not a local bound on
  that data (§12 rule 4); capping it would mean forking the store, which
  the display-aid payoff does not justify — the accepted residual is the
  writes of the up-to-`MAX_TRAVERSED_CHECKPOINTS` returned tuples per poll,
  not the whole history.
- Neutral — trust boundary: the checkpoint store is agent-writable (root
  CLAUDE.md §12 rule 2). A forged history can only color edges that are
  already real edges of that run's own graph, for viewers who can already
  see the run (the same exposure model ADR-0044 already uses for node
  status); nothing crosses a principal boundary.
- Neutral — the scan is bounded locally (§12 rule 4): `traversed_edges`
  consumes at most `MAX_TRAVERSED_CHECKPOINTS` checkpoint tuples and
  `MAX_TRAVERSED_WRITES` pending writes, and skips any `channel` or
  `Send.node` longer than a bound derived from the topology's own node ids
  before matching it — the bound covers every `join:` whose sources are
  distinct node ids plus a self-join's longest id on both sides
  (`branch:to:` with the longest id as target, `join:` with all node ids
  plus separators). A source repeated within one waiting edge
  (`add_edge([a, a, a], b)` writes `join:a+a+a:b`) can overflow it and drop
  that edge. An inflated history therefore
  costs a fixed amount of
  work per poll instead of one proportional to its size. Truncation can drop
  edges, but the topology intersection still runs last, so it can never
  invent one; because the field is a display aid, a partial list degrades
  silently rather than raising. CWE-770. The two caps are
  deliberately fixed constants, generous against the largest topology Homer
  serves (`LangGraphTopologyLimits`' 200 nodes), not derived from the
  current graph's own node count: a per-graph bound would make the same
  history legal on a small graph and truncated on a large one, so a fixed
  worst-case cost per poll is the simpler contract.
- Neutral — the `state` poll has other history-sized folds: the
  `get_state_history()` walk feeding `done`/`seen`/`tasks` runs before the
  capped scan and stays open-ended, because a node's `done` may only be
  provable from the oldest snapshot (ADR-0044's fold, which
  `MAX_INTERRUPTS_SEEN_PER_NODE` already caps only in payload, not in
  iteration). The caps in this ADR therefore bound the `traversed` scan,
  not the whole poll; the walk itself is the same one ADR-0044's status
  overlay always ran, unchanged by this decision.
- Neutral — silent truncation is accepted for this display aid. A history
  over the caps yields a partial `traversed` with no observable flag,
  by the same choice as `interruptsSeen`, which reports a capped prefix
  plus `interruptsSeenTruncated` rather than an error: the field is a
  display aid (never a correctness input), so the cost of a
  `traversedTruncated` flag on the output contract and its consumers is
  not paid here. An operator seeing tail edges that never light up should
  check the run's history size against the caps.

## Pros and Cons of the Options

### Console-only heuristic (both endpoints reached)

- Good: no runtime change; pure console-side derivation from node status
  the console already has.
- Bad: colors edges a run never took — e.g. `plan → implement` lights up
  when the run actually went `plan → gate → implement`, because both
  `plan` and `implement` were reached.
- Bad: cannot distinguish a loop-back edge that fired from one that
  didn't, once both the loop's source and target have been reached.

### Superstep adjacency (node in step k, successor in step k+1)

- Good: derivable from existing per-step state, no new channel-name
  knowledge needed.
- Bad: wrong when two nodes run in the same superstep but only one of them
  routes onward — both look like they fed the next step.
- Bad: misses joins whose branches differ in length — the branch that
  finishes first lands in an earlier superstep than the join, breaking the
  `k, k + 1` adjacency the heuristic relies on.

### Highlighting only conditional edges (variant of the chosen option)

- Good: green would mark only router decisions, never restate `add_edge`
  topology.
- Bad: the highlighted set stops being a path — fixed edges between two
  router decisions drop out, so the viewer has to reconnect the gaps, which
  is the question the feature exists to answer. Every taken edge is
  highlighted, fixed or conditional; the dash already tells them apart.

## More Information

- [ADR-0044](0044-langgraph-resume-and-run-introspection.md) — the
  run-status overlay this extends.
- [ADR-0073](0073-langgraph-topology-is-structured-data.md) — topology as
  structured data; `traversed` is filtered against it on both the runtime
  and Homer sides.
- [ADR-0047](0047-security-by-design-and-threat-model.md) — security
  model: checkpoint store as agent-writable data (§12 rule 2), bounds kept
  local (§12 rule 4).
