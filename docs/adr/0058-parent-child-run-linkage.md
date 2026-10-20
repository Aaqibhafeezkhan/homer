---
status: accepted
date: 2026-09-04
deciders: Lukáš Vlk
---
# ADR-0058: Parent/child run linkage as a stored, caller-visible reference

## Context and Problem Statement

A run can cause another run in several ways: a `RunAgent` command, `homer-dispatch`
or ask-and-dispatch, a completion continuation, or a retry/resume of an earlier run.
Until now nothing durable connected the two. `HMR_INVOCATION_CHAIN` (ADR-0008,
ADR-0030) carries agent *names* for the recursion guard and is neither persisted nor
served; the dispatch referer lives only on a span; each run opens its own trace. An
operator debugging a flow in the console had to open the child, read an input the
parent happened to forward, and search for it by hand — and only if the agent author
had thought to forward `HMR_PROCESS_ID` in the first place.

The question is how to record "run B was caused by run A" so that it is visible on
the API and the console, without adding a trust path that lets a caller forge links
to runs it should not know about.

## Decision Drivers

- **Debuggability in the console.** A run's page should link to its parent and list
  its children with one click each.
- **No agent-author cooperation required.** The link must exist whether or not the
  parent forwards anything.
- **External callers must be able to link too.** Orchestration outside Homer (a
  script that starts several runs) has the same debugging need as `RunAgent`.
- **§12: principals are a boundary; hide existence.** A caller must not be able to
  probe process ids through the linkage, nor attach its run to a run it cannot see.
- **Records outlive workspaces (ADR-0048) and rows are evicted at capacity.** A parent
  may be gone before its child; the child's record must still say what caused it.

## Considered Options

- **Option A — A nullable `parent_process_id` column on the process row**, written
  once at INSERT by every internal path that knows its origin, settable by external
  callers via a request header validated against the caller's visibility, served as
  `parentProcessId`, filterable with `?parentProcessId=` on the list endpoint, and
  exported to the run as `HMR_PARENT_PROCESS_ID`.
- **Option B — Derive linkage from existing telemetry** (dispatch referer attributes,
  continuation/dispatch rows, invocation chain) at read time.
- **Option C — A separate `process_links` table** modelling arbitrary relations
  (parent, retry-of, continuation-of, …) with a link kind.
- **Option D — Children as an inline `childProcessIds` array on the process
  response.**

## Decision Outcome

Chosen option: **"Option A — a nullable `parent_process_id` column"**, because it is
the smallest durable form that satisfies every driver: one integer per row, one
filter on a list endpoint that already paginates and applies the visibility union
(ADR-0037/0038), and one env var.

Specifics:

- **Who sets it.** `AgentRunStarter.start` takes an optional `parentProcessId` and
  stores it in the same transaction as the row INSERT. Callers supply it from the
  identity they already hold: `RunAgentExecutor` from the Homer-injected
  `HMR_PROCESS_ID` of the running workspace (a reserved name, so never
  caller-controlled); dispatch and continuation fires from their `originProcessId`;
  retry and resume from the source run's own parent, because a replay is still part
  of the flow that caused the source run. Cron, MCP agent tools, and debug runs have
  no parent.
- **Continuation parent is the registrant, not the watched run.** A completion
  continuation's fired run links to the run that *registered* it (`originProcessId`),
  so in the console it appears as a sibling of the watched run and the watched run
  lists no children. The alternative — the watched run as parent — was rejected
  because the watched run did not ask for the follow-up and may belong to a
  different principal; the registrant is the run whose author decided a follow-up
  should happen, which is what "caused" means everywhere else in this ADR. The
  watched run stays discoverable through the continuation record itself.
- **External callers.** `POST /api/v1/agent/{name}` reads
  `X-Homer-Parent-Process-Id`. The route resolves it **in the handler** before the
  run starts: the parent must exist *and* satisfy `Authorizer.canSee` for the caller,
  otherwise the request is refused with one and the same `422 Parent process N not
  found`. A malformed value is a plain `400`. The link is metadata only — it does
  not join the invocation chain, inherit lock keys, or change ownership.
- **Reading.** `parentProcessId` is a field on every process response.
  `GET /api/v1/status/all?parentProcessId=N` lists the direct children; the filter is
  ANDed with the existing visibility condition, so a child the caller cannot see is
  simply absent. The console shows the parent link and a children list on the run
  page.
- **Not a foreign key.** The parent row may be evicted or record-purged first; the
  child keeps the id as "caused by #N" and the console renders it as a link that may
  404. Deleting the link on parent deletion would erase the one fact the operator
  wanted.
- **Inside the run.** `HMR_PARENT_PROCESS_ID` is injected by `WorkspaceConfigFactory`
  in the same `HMR_*` layer as `HMR_PROCESS_ID`, above every caller-supplied input,
  so it cannot be spoofed through query params. It is unset (not empty) at the root.

Trust boundary touched: **principals**. The only new input is the header, and it is
authorised in the handler, fails closed, and hides existence (§12 rule 3). It adds no
new resource a caller can multiply: one integer column per run, and the children
query reuses the bounded, paginated list endpoint (rule 4).

### Consequences

- Good: a run page links both ways with no agent-author cooperation; the flow is
  reconstructible after workspaces are purged.
- Good: internal and external callers use one field with one meaning; no path is
  special-cased at read time.
- Good: the header cannot be used to probe ids (unknown and invisible parents are
  identical), and a caller can only attach to runs it could already open.
- Neutral: a retry keeps the source run's parent rather than pointing at the source
  run. "Retry of #N" remains a span attribute (`retry.of_process_id`); modelling it as
  a link kind would be Option C.
- Bad: a dangling `parentProcessId` after eviction renders as a link to a `404`. Judged
  preferable to losing the reference.
- Neutral: a visible child exposes its parent's integer id to a viewer who cannot open
  the parent. Ids are sequential and carry no other information; the parent's
  existence is already implied by the child's `HMR_INVOCATION_CHAIN` and inputs.
- Bad: once the parent row is evicted at capacity or record-purged, the header check
  fails closed and an external orchestrator that keeps sending
  `X-Homer-Parent-Process-Id` is refused with `422` until it drops the header.
  Accepting a missing row would leak existence, so callers should treat this `422`
  as "drop the header and retry".
- Bad: only the *direct* parent is stored. A full ancestry needs one request per hop;
  `HMR_INVOCATION_CHAIN` still gives the agent-name path in one read.

## Pros and Cons of the Options

### Option A — Nullable column on the process row

- Good: one integer, one filter, one env var; reuses list pagination and visibility.
- Good: written atomically with the row, so it exists from the child's first status.
- Bad: direct parent only; no link kinds.

### Option B — Derive from existing telemetry at read time

- Good: no schema change.
- Bad: `RunAgent` children leave no server-side row to join on at all (the chain is
  names, not ids); spans live in Langfuse, not the database; a run with telemetry
  `OFF` would have no parent. Fails the "no author cooperation" and "durable" drivers.

### Option C — Separate `process_links` table with kinds

- Good: models retry-of, continuation-of, and parent distinctly; N:M ready.
- Bad: a join and a second sweep for every list and detail read, plus link-row
  lifecycle on eviction, to answer a question that today has one parent per run.
  Revisit if a second link kind is actually needed.

### Option D — Inline `childProcessIds` on the process response

- Good: one request for the run page.
- Bad: unbounded array on a response that is also returned per row by the list
  endpoint; would need its own visibility filtering and cap. The list filter already
  has both.

## Amendment (2026-09-24): the flow root is stored too, as `root_process_id`

A flow is a root run plus everything it spawned, and the console and the API had no
way to ask for one as a unit: `?parentProcessId=` gives one level, so opening a flow
meant walking it hop by hop, and the process list showed a long run as unrelated rows
with no grouping. The direct parent is enough to rebuild the tree but not to answer
"show me this flow".

This amendment records a denormalized `processes.root_process_id` column (schema v22)
and the read surface built on it. It **supersedes this ADR's "only the *direct*
parent is stored" consequence**: the direct parent stays the authoritative edge, and
the root is a second, derived fact carried on the same row so the flow is one read.

- **What is stored.** `root_process_id` is written once at INSERT as
  `parent.rootProcessId ?: parent.id` on every path that sets `parent_process_id` —
  `RunAgent`, an ask-and-dispatch fire, `dispatchNow`, a completion-continuation fire
  or join, and `X-Homer-Parent-Process-Id` on `POST /api/v1/agent`. The root's own
  value is `NULL` (the run is its own root; storing its own id would make a
  self-reference the API has to special-case). Retry and resume copy the value from
  the source run, exactly as they copy the parent — but only when the caller can see
  the root row; otherwise the new run is stamped rootless. A new run appended to an
  existing flow therefore inherits the flow's root without the caller naming it.
  The internal fire paths apply the same gate from the other end: a fired run is
  attributed to the dispatch/continuation owner, so the root its origin row points
  at is inherited only when that owner can see the root row — the same check the
  external-header path applies to the linking caller.
- **Not a foreign key**, for the same reason as the parent: the root row may be
  evicted at capacity or record-purged before its descendants, so a stored id can
  point at a row that no longer exists. It is a plain reference.
- **Existing rows are left `NULL`, not backfilled.** A pre-v22 parent that itself has
  no stored root is indistinguishable from a root run — the column was never written,
  so there is nothing to walk up to — and an evicted or record-purged parent cannot
  be resolved at all. A backfill would therefore have to invent an answer on exactly
  the rows where it is wrong. `NULL` reads as "flow root unknown", and the code treats
  it as such rather than guessing.
- **Reading.** `?rootProcessId=N` lists exactly the visible descendants of run N, at
  all depths, in one paginated read; `?roots=true` lists only rows with no parent
  (`parent_process_id IS NULL`), i.e. one row per flow, and composes with the existing
  `types` / `agent` / `tag` filters. Both are ANDed inside the existing owner ∪
  visible-agents union (ADR-0037/0038), so a descendant the caller cannot see is
  absent rather than refused. `ProcessInfoResponse` gained `rootProcessId: Int?`,
  `null` at a root run. The `?rootProcessId=` filter itself is gated on the requested
  root's visibility: a root the caller cannot see reads exactly like an empty flow,
  never like "no filter" — an id filtered on is a claim the caller may only guess, and
  honouring the invisible ones would make the filter an oracle for guessed ids.
- **Inside the run.** `HMR_ROOT_PROCESS_ID` carries the flow's first run id, injected
  by `WorkspaceConfigFactory` in the same reserved `HMR_*` layer as
  `HMR_PARENT_PROCESS_ID`. It is **removed, not set empty, at the root run**, so an
  agent `env:` entry or an allowlisted host variable cannot pose as it. It is
  likewise unset on a run stamped rootless because its parent's flow root is not
  visible to the caller that linked it.
- **Console.** The Processes page defaults to the roots view, each root row showing
  its flow's run count, total cost and aggregate state (running / waiting on a
  question / failed / finished), with an **All runs** toggle (`?view=all`). A root's
  detail page shows a flat *Flow* section listing every visible descendant in id order
  (agent, status, cost, parent), with a toggle that hides the orchestrator's own
  resume segments (same agent as the root) — this replaces the capped direct-children
  list for roots. A descendant's page links to its root run (`Root run #N`) beside the
  existing `Parent run #N`.

Trust boundary touched: **principals**, unchanged in direction. The column is
server-derived — no caller-supplied value reaches it, and no new input surface is
added — and both new filters reuse the bounded, paginated list endpoint under the
existing visibility union, so a caller can no more probe ids through `rootProcessId`
than through `parentProcessId`. Aggregate state and totals on the roots view are
computed over the visible rows only. Every write and read path resolves the stored
or served root through the same visibility union: a run linked through a parent
whose flow root the caller cannot see is stamped rootless rather than inheriting
that id, the internal fire paths drop a derived root the fired run's owner cannot
see, retry and resume copy the source's root only when the caller can see the
root row, the `?rootProcessId=` filter answers an invisible root with an empty
page, and the status responses withhold the id when the root row is gone or
invisible — so a run's row, its `HMR_ROOT_PROCESS_ID` env and its spans never
publish a flow-root existence the caller could not have read itself.

Accepted, recorded so the omission reads as a decision: `root_process_id` carries **no
index**, unlike `cost_entries`' filter columns. The roots list is one page
(`pagination.defaultLimit`) and only the first 20 visible roots fetch a summary (one
`root_process_id` read per visible root row), so the
cost is bounded by a scan over `processes` at most `pagination.defaultLimit` times per
page — the
same per-row shape `parent_process_id` already has, without a default-page-load
multiplier of its own. Indexing a low-cardinality integer column on a table bounded by
eviction buys little; if a flow-dense deployment measures the scans, an
`index(false, rootProcessId)` beside the `ProcessTags` index is the first change to make.
*(Amended 2026-10-09: schema v23, commit `6e33603b`, indexes both `parent_process_id`
and `root_process_id` along with the other `GET /status/all` list filters.)*

Accepted for the console too, so the shape is a decision and not an oversight: the roots
view computes each root row's summary (run count, total cost, aggregate state) with one
`?rootProcessId=` read per visible root row, capped at 20 rows, riding the page's 30 s
poll and the existing SSE invalidation. Bounded at ~21 list reads per render against a
server with 20 active flows; the alternative (per-root aggregates via `GROUP BY
root_process_id` on the list endpoint) is a backend contract change this amendment does
not make — if the fan-out shows up in practice, that is the change to reach for.

## More Information

- Write path: `AgentRunStarter.start(parentProcessId)`, `ProcessFactory.create`,
  `ProcessRepository.obtainProcessId`; header resolution in
  `SecuredRoutes.resolveParentProcessHeader`.
- Read path: `ProcessRepository.getAll(parentProcessId)`, `ProcessInfoResponse.parentProcessId`.
- Env: `EnvConstants.PARENT_PROCESS_ID`, `EnvConstants.PARENT_PROCESS_HEADER`.
- Root (amendment above): same write/read paths carrying `rootProcessId` /
  `root_process_id`; filters `?rootProcessId=` and `?roots=`; env
  `EnvConstants.ROOT_PROCESS_ID` (`HMR_ROOT_PROCESS_ID`).
- Related: [ADR-0008](0008-recursion-guard-via-invocation-chain-header.md) and
  [ADR-0030](0030-runagent-in-process-dispatch.md) (invocation chain, in-process
  dispatch); [ADR-0022](0022-script-driven-immediate-dispatch.md) (dispatch referer);
  [ADR-0037](0037-grants-authorization.md) / [ADR-0038](0038-process-ownership.md)
  (visibility union); [ADR-0047](0047-security-by-design-and-threat-model.md) (§12).
