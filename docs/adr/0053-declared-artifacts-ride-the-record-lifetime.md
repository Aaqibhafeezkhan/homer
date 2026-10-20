---
status: accepted
date: 2026-08-26
deciders: Lukáš Vlk
---
# ADR-0053: Declared artifacts ride the record's lifetime

## Context and Problem Statement

A factory migration asks for the artifacts a run produced
to still be downloadable after the workspace is gone, not just the fact that
the run happened. [ADR-0048](0048-split-record-retention-from-workspace-purge.md)
kept the process record alive past `purgeAfter`, and its own consequences
section already names the gap: "a record-only run degrades to *detail page
works, downloads don't* rather than to nothing." The files that answer *why
the bot did what it did* — a decision document, a generated summary, a diff —
still die with the workspace directory; only the metadata around them
survives.

The artifacts directory is already the curated output store: agents write
scratch and build output to the workdir and finished output to
`artifacts_dir`, and the existing routes (`/artifacts/list`, artifact reads)
serve from it by path. The problem this ADR solves is narrower than "add
persistent storage" — it is deciding whose lifetime a *declared subset* of an
already-existing store should follow.

## Decision Drivers

- A ticket link should keep resolving to working downloads, not just a status
  page, for as long as the record itself is retained.
- ADR-0048's size objection still holds for artifacts in general: a Claude
  run's `cmd_N.out` is megabytes of streamed JSON, and nothing should retain
  that by default.
- What a finished run retains must not change because someone edits the agent
  definition afterward — the same principle
  [ADR-0052](0052-redaction-metadata-is-captured-with-the-run.md) applies to
  secret-input flags.
- §12: the retained set is written by the agent process, so it is
  attacker-controlled and needs an explicit, operator-visible bound.

## Considered Options

- **Snapshot-copy declared files into a separate retained store.**
- **Keep the whole artifacts directory alive until record purge.**
- **Prune the artifacts directory in place, keeping only declared globs.**
- **Rely on Langfuse plus a linking comment (the migration's proposed fallback).**

## Decision Outcome

Chosen option: **prune the artifacts directory in place, keeping only
declared globs**, grouped with the record's own retention window into one
config block with one clock:

```yaml
workspace:
  path: "{{HMR_PROCESS_ID}}"
  cleanAfterFinish: true      # unchanged: scratch workdir dies at run end
  purgeAfter: 1h              # unchanged: workspace dies after 1h
  retain:                     # the ONE thing that outlives purgeAfter
    recordFor: 90d            # how long the run's record survives (was purgeRecordAfter)
    artifacts: ["decision.json", "summary.md"]   # globs kept alongside it (optional)
```

Mental model: *the workspace dies at `purgeAfter`; `retain` says what
outlives it — the record, plus optionally these named artifacts — and for how
long.* Omitting `retain` reproduces pre-1.17.0 behaviour; `retain` without
`artifacts` reproduces 1.17.0's record-only retention.

**BREAKING**: `workspace.purgeRecordAfter` (shipped yesterday, in 1.17.0) is
renamed to `retain.recordFor`. A config that still sets the old key fails
agent load with a migration hint pointing at the new field, rather than being
silently dropped. This has to be an explicit rejection: Homer's JSON agent
loading uses `ignoreUnknownKeys`, so an agent still on the old key would
otherwise load successfully with no `retain` block at all — silently losing
its record retention instead of failing loudly.

The other three options were rejected:

- **Snapshot-copy** duplicates the role the artifacts directory already
  plays as the curated output store, doubles disk usage for anything
  retained, and needs its own addressing scheme instead of reusing the paths
  the artifact routes already serve.
- **Keep the whole directory** re-opens ADR-0048's size objection outright —
  it is the "keep the artifacts too" option that ADR-0048 already rejected,
  for the same reason: a Claude run's raw stdout artifact is megabytes of
  streamed JSON per step, and nothing declares that worth keeping.
- **Langfuse + comment**, the migration's own fallback, only covers `!<claude>`
  steps — a `Cmd` or `Script` step producing a file has nothing to link to —
  and requires a second login to a system Homer callers may not already use
  (a constraint of the migration).

### Mechanics

**Prune in place, no copy, no new store.** At `purgeAfter` the sweep deletes
the workdir and every artifacts-directory file that does not match a
declared glob, leaving glob-matched files exactly where they are.
`artifacts_dir` stays set on the row — the existing artifact routes keep
reading from it, so a retained run serves the same URLs before and after the
sweep, with no 404-then-200 flap and no route change.

**The sweep marker moves.** `artifacts_dir IS NULL` currently doubles as
"this row's workspace has already been swept" (ADR-0048). A row with
retained artifacts keeps `artifacts_dir` set past `purgeAfter`, so that
marker can no longer mean "swept" for a retained row. A new
`workspace_purged_at` column takes over as the sweep marker; a failed prune
leaves it null and the row is retried on the next tick, matching today's
retry behaviour for a failed workspace delete.

**Globs are captured at run time, not read from the live agent.** The
`retain.artifacts` glob list is stored on the row at terminal status, next to
`purge_at`, the same moment ADR-0052 captures the secret-input snapshot. An
agent edited after a run finishes cannot change what that already-finished
run retains — editing the globs only affects runs that start afterward.

**Retained files ride the record's lifetime.** Record purge, and capacity
eviction at `maxProcesses`, deletes whatever is left of the workspace
directory and then the row — retained artifacts get no separate expiry of
their own.

**Shared workspaces are unaffected.** While another row still references the
same artifacts directory, the sweep keeps its existing behaviour: skip the
delete, clear `artifacts_dir`, and log a WARN. `retain.artifacts` is
therefore only effective for per-run workspaces; a shared workspace's row
that declares it gets the warning instead of the retention.

**Fixed workspace paths reject `retain.artifacts` at agent load.** A shared,
fixed `workspace.path` is reused by every run of the agent, so retained files
left in it would be served — and could be overwritten — by that directory's
next run through the same artifact routes, crossing owner boundaries. `Workspace.init` now requires `path` to contain
`{{HMR_PROCESS_ID}}` whenever `retain.artifacts` is non-empty — closing this
at definition time rather than relying on the sweep alone. The sweep's
shared-dir skip above remains as defense in depth for rows created before
this rule. The `..`-segment half of the original fix (a traversal segment
after the placeholder, e.g. `{{HMR_PROCESS_ID}}/..`, would collapse every
run back onto one shared directory) is no longer retain-specific: it now
rests on a general guarantee that any `workspace.path` resolves to a strict
subdirectory of the workspace root — never the root itself, and never via a
`..` segment — enforced both at agent load (`Workspace.init`) and at
resolve time (`WorkspaceConfigFactory`), so it also covers agents that never
set `retain.artifacts` at all. That strict-subdirectory guarantee also
forbids reaching the per-run path through a symlink component: `assertContained`
follows symlinks and only checks that the *resolved* target stays under the
root, which is not enough when process ids are monotonic — an earlier
same-UID run can plant a symlink at a predictable future per-run path, so two
string-distinct per-run paths alias to one directory across owner boundaries. `PathSafety.assertNoSymlinkComponentsBelow` lstat-walks the
resolved path's components below the root and rejects any that are a
symlink. The residual — a component turned into a symlink after this check
but before the workspace directory is materialized — is a same-UID race, not
a trust-boundary crossing, and is accepted per §12.

### Bounds and trust boundary (§12)

The retained set is written by the agent process, so it is
attacker-controlled content that now lives in an API-downloadable location
for up to `retain.recordFor`, well past the run itself. Four controls apply:

- Glob matching walks with `NOFOLLOW_LINKS` and matches regular files only,
  so an agent-written symlink cannot pull an arbitrary host file into the
  retained, API-downloadable set.
- Every delete the sweep performs stays containment-checked against
  `workspaceRootPath`, unchanged from today.
- A per-run byte cap, `server.maxRetainedArtifactBytes` (default 16 MiB),
  bounds how much a single run can retain — which makes the deployment-wide
  ceiling operator-computable as `maxProcesses × maxRetainedArtifactBytes`,
  the same shape ADR-0048 already established for `maxProcesses` itself. On
  overflow the sweep keeps what fits in declared-glob order and sets a
  `retainedTruncated` flag on the record, visible in the status API. The run
  itself never fails and the truncation is never silent.
- A per-run entry cap on the walk itself, `MAX_PRUNE_WALK_ENTRIES` (10,000),
  bounds how many paths the sweep materializes under the artifacts dir before
  matching even starts — closing a heap/CPU DoS a huge or deeply nested tree
  could otherwise cause inside the background sweep. Its
  overflow semantics differ from the byte cap's: there is no partial keep-what-fits.
  Over 10,000 raw entries, the sweep retains nothing, deletes the whole
  workspace, and still sets `retainedTruncated` on the record — fail closed,
  same visible flag, no silent truncation.

### Accepted risk: retained files stay writable at Homer's UID for the record window

`server.maxRetainedArtifactBytes` is measured once, at prune time, against
whatever the run left behind. After the sweep, retained files are ordinary
writable files owned by Homer's UID like any other workspace output — nothing
about retention makes them read-only. A process that outlives the run and
shares that UID can keep appending to them, growing the retained set past the
`maxProcesses × maxRetainedArtifactBytes` estimate the bound in the previous
section is meant to give an operator.

Not closing this is deliberate, on three grounds:

1. **§12 records same-UID access as not a trust boundary.** A writer at
   Homer's UID can already fill the workspace volume directly, with or
   without `retain.artifacts` in the picture; retained files grant that
   writer no capability it did not already have.
2. **No cheap fix at one UID is airtight.** A read-only `chmod` after the
   sweep is reversible by the same UID that set it, so it would add
   complexity without a real guarantee. Process-group reaping
   ([ADR-0041](0041-process-group-reaping-on-kill.md)) already bounds who can
   survive a run to append in the first place; that bound already does the
   load-bearing work available at this UID.
3. **The disk the retained set lives on is a deployment choice.** Same as
   ADR-0048's ground 3: [`DOCKER.md`](../../DOCKER.md) covers isolating the
   workspace volume for operators who want a hard quota underneath it,
   independent of anything this ADR does in-process.

What this changes is the retention an operator should reason about: a long
`recordFor` is also a long window in which the retained bytes are an
estimate, not a guarantee — the cap holds at the moment the sweep runs, not
for the rest of the record's lifetime.

Reconsider if the premise moves: if Homer ever runs agents under a different
UID than its own retained files, re-measuring — or copying retained files to
a Homer-owned location — at record-sweep time becomes the better trade.

### Consequences

- Good: a `/processes/{id}` link posted in a ticket keeps its downloads
  working, at the same URLs, for as long as the record survives — closing
  the gap ADR-0048 left open.
- Good: no new store, no new addressing scheme; the artifacts directory and
  its existing routes are reused as-is.
- Bad: retained bytes extend how long agent-controlled content sits on disk,
  bounded by `maxRetainedArtifactBytes` but not eliminated.
- Bad: the record sweep now touches disk (a prune, not just a directory
  delete) for rows with retained artifacts, where before it only had to
  delete or skip a directory.

## More Information

- Extends [ADR-0048](0048-split-record-retention-from-workspace-purge.md),
  whose own consequences section named the gap this ADR closes.
- Applies the same run-time-capture principle as
  [ADR-0052](0052-redaction-metadata-is-captured-with-the-run.md): a
  finished run's retained state is a fact about that run, not something
  re-derived from the agent's current definition.
