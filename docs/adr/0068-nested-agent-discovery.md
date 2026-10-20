---
status: accepted
date: 2026-09-24
deciders: Martin Simon
---
# ADR-0068: Agent discovery walks the search path recursively; directory identity is the root-relative path

## Context and Problem Statement

`AgentsService.scanAgents` read exactly one level of `agentsSearchPath`, and
everything downstream keyed on a directory's **leaf name**
(`sourceDirs[name] = dir.fileName`, `presentDirNames`, `removedAgentNames`).
That was the documented behaviour ("immediate subdirectories", `AGENT.md`), and
an `agent.yaml` one level deeper was silently invisible — a scan reported a
clean `{"added": [], "updated": [], "removed": [], "errors": []}`.

The cost is the shape of a multi-agent pipeline. A LangGraph flow is one
orchestrator plus N workers, and under a flat rule it needs N+1 top-level
directories: seven of them holding a single ~50-line `agent.yaml` and nothing
else, living apart from the graph, prompts and schemas they belong to. Three
more pipelines of the same shape were planned, and existing factory agent
repositories (one with 17 `factory*` directories, another with 30 under
`agents/`) already show the sprawl.

## Decision Drivers

- Layout is the author's concern; the loader should read the tree the author
  writes, not dictate that every agent be a sibling.
- Nesting must not change any *semantic*: names, grants and dispatch targets
  stay one flat global namespace, or the change reaches every consumer of
  `Agent` for no gain.
- A parent agent's definition directory is not a trust boundary a child can be
  reached through (ADR-0047): the file API's `edit` grant on a parent must not
  become a write to a nested agent's `agent.yaml`.
- Remote runners upload the whole `HMR_AGENT_DIR` to `.homer/agent`
  (ADR-0032, ADR-0043, ADR-0066). A parent's upload carrying a child would put
  two agents' definitions in one workspace under one agent's identity.
- The walk runs on every `reload()`; an agent directory can hold a whole code
  tree.

## Considered Options

- **Keep the flat rule** — no change; pipelines stay N flat directories, and
  the loader silently ignores a definition it can see on disk.
- **An explicit manifest** — a `includes:`/`agents:` list naming child
  directories. Rejected: it is a new contract surface, and it invents a way for
  one definition to reference another's location, which ADR-0049 deliberately
  keeps closed.
- **Recursive walk, directory identity = root-relative path** — chosen.

## Decision Outcome

Chosen option: **"Recursive walk, identity = root-relative path"**.

`AgentDirs` is the single implementation of the rule: every directory under
`agentsSearchPath` holding `agent.json`/`agent.yaml` is an agent at **any**
depth, including inside another agent's directory. The walk descends at most
`MAX_DEPTH` (8) levels and visits at most `MAX_ENTRIES` (1000) directories
**within any one top-level directory's subtree**, with `MAX_TOTAL_ENTRIES`
(10 000) as an outer ceiling across the whole catalog. It skips hidden
directories and never follows a symlinked directory. Each hit goes through the
unchanged per-directory `parseAgentDir`, so a parent that fails to parse or
validate cannot take its children down.

Directory **identity** becomes the path relative to `agentsSearchPath`
(`AgentsService.sourceDirs`, `presentDirNames`, `removedAgentNames`): two
nested `analysis/` directories no longer collide, and moving a directory deeper
with the same `agent.name` reads as `updated` rather than `removed` + `added`.

Nesting is **layout only**, and stays inside ADR-0049: no definition gains a
way to reference, include or read another's steps or files. Names remain one
flat global namespace and the existing duplicate-name rule is unchanged — a
`name` claimed by two directories at any depth fails **all** claimants.

### Consequences

- Good: a pipeline (orchestrator + workers) lives in one tree, next to the
  graph, prompts and schemas it belongs to.
- Good: one walk, one parse path — `agentdebug` and every runner inherit the
  change without a per-runner or per-client branch.
- Good: the ADR-0047 reach is designed in with a denial, not patched after
  review: `AgentFilesService.resolveSafe` denies any path at or below a nested
  agent directory as `404`, and `list` omits the subtree.
- Bad: the walk costs more than one `listDirectoryEntries` per reload. The
  depth/entry caps, the hidden-directory skip and the symlink refusal bound it,
  and a directory that is itself an agent contributes no descent of its own
  beyond one level.
- Bad: a parent's remote upload now computes and applies root-anchored
  excludes, so `RunnerSession.upload` carries a new parameter. The excludes
  come from an exact walk of the parent at upload time — no depth or entry
  cap, hidden directories included — so they agree with the file API's deny
  predicate on every directory the pack would otherwise carry. A symlinked
  directory is excluded outright rather than followed: both upload paths
  follow a link that resolves inside the root under the link's own name,
  where no real-path exclude would match, so the agent-dir upload never
  carries one (the rule discovery already applies). Rejected sources: the capped discovery walk, or
  the catalog it produces, because a truncated walk ships a child's subtree
  under the parent's identity, and failing the upload on truncation would
  stop every parent with a large tree from running remotely. The pack walks
  the same tree, so the exact walk adds no cost class of its own.
- Neutral: a walk truncated by the entry cap makes the agents past the cap
  vanish from one reload and (once the tree shrinks or the cap is raised)
  reappear on the next — catalog churn an operator cannot distinguish from a
  real deletion. Treating a truncated walk as a **failed reload** (dropping
  nothing, failing the scan) was considered and rejected: the cap exists to
  shed load, and failing the whole catalog on truncation turns the shed into
  an outage of every agent. The truncation is instead loud — the walk logs a
  warning naming the cap — and the reload completes with the prefix it saw.
  Operators bound the trees they author; the warning is the signal they did
  not.
- Neutral: a **local** run's `HMR_AGENT_DIR` stays the parent's real
  directory, nested children included — the excludes apply only to backends
  that copy. Accepted under ADR-0047's same-UID boundary: a local command runs
  as the `homer` user and already reaches the whole search path (on the flat
  layout too, `$HMR_AGENT_DIR/../<any>/agent.yaml`), so a sanitized per-run copy
  would close nothing while copying the parent's whole tree on every run.
- Neutral: the console's *create agent* stays top-level; nesting is created on
  disk. `AgentFilesService.create` keeps checking leaf-name existence under the
  search-path root and reports `Directory '<name>' already exists` even when the
  only holder of that leaf name is a nested agent directory the caller cannot
  see through the file API — create stays leaf-name-unique tree-wide, so a name
  taken at any depth is refused rather than shadowed. `delete` of a nested
  child's definition file is unreachable through the parent (the `resolveSafe`
  denial above answers first) and its `agent.yaml` guard continues to speak
  only for the agent's own root definition.
- Neutral: two hardening decisions taken while implementing the reach and the
  walk, recorded here so the inline comments can stay terse:
  - **A definition write anywhere needs the create-agent grant on the name it
    declares** (in addition to `edit` on the parent, denied as `404`). Rejected
    alternative: allow a definition write on the parent's `edit` alone — that
    would let a parent's holder bootstrap a new agent into the flat global
    namespace, which is `POST /agents` territory granted on the declared name.
  - **A definition-named file whose body does not parse is denied as `404`**
    (the parent's own root definition excepted — the schema validation reports
    its parse errors, and a root write cannot create an agent). Rejected
    alternative: treat "does not declare a name" as "nothing to authorize" —
    that fails open on malformed input and lets an `edit` holder plant the
    boundary directory on disk, a subtree the file API then denies as `404` and
    no grant can remove. For the same reason a definition that does parse is
    schema-validated at any depth, not only at the agent's own root.
  - **The walk's entry budget is per top-level directory, with a whole-catalog
    outer ceiling** (`MAX_TOTAL_ENTRIES`, 10 000), and a file-API path is capped
    at `MAX_PATH_SEGMENTS` (10) segments before any disk touch. Rejected
    alternative: one shared budget for the whole catalog walk — an `edit` holder
    of one agent could fill their own agent's directory with directories (a
    deep `PUT` plants a real chain via `createDirectories`), exhaust the shared
    budget, and drop every agent walked after theirs: other principals' agents
    reloading as removed. The per-subtree budget isolates that blast radius;
    the outer ceiling still bounds the total walk; the segment cap keeps the
    chain from being planted at all. **A definition landing is bounded further,
    to the walk's own reach**: the segment cap is deliberately looser than
    `MAX_DEPTH`, so on its own it let a definition land two segments past where
    discovery stops — still a nested-agent boundary to `isBelowNestedAgentDir`,
    but never loaded, leaving a subtree the file API only answers `404` for and
    no grant can remove (CWE-400). The file API therefore also
    refuses a definition deeper than `MAX_DEPTH` below the search path, or under
    a dot-prefixed directory, via `AgentDirs.isUnreachableByDiscovery`. Rejected
    alternative: lower `MAX_PATH_SEGMENTS` to `MAX_DEPTH` — it would tighten
    every non-definition path too, and does nothing for hidden names. The reach
    check's depth root is the **resolved** search path, keyed on the agent
    directory's real path like the rest of `resolveSafe`, and an agent
    directory outside that root fails loudly (`require`) rather than being
    handed a made-up depth. Rejected alternative: derive the depth from the
    lexical `dir`, or hide a divergence with an `else 1` fallback — the gate's
    depth budget would then be computed from the wrong root and drift from the
    walk, with the fallback passing an impossible input as a normal depth.
  - **A write is gated on where it lands, not on the path it names**: a
    non-existent candidate follows a dangling symlink — including one in an
    intermediate component — before the write, so the landing path is checked
    for containment and the nested-agent boundary like any resolved path, and
    the definition-file checks (create grant, schema, the delete refusal) key
    on that landing path too, so an alias like `notes.txt -> agent.yaml` is
    the definition. Rejected alternative: gate the lexical candidate only — a
    link stats as its own name, and `assertContained` cannot see a chain that
    does not resolve.
  - **The per-directory listing is capped before it is sorted** (at the walk's
    remaining entry budget). Rejected alternative: sort the whole directory and
    cap afterwards — deterministic truncation requires the sorted order, but
    sorting in full is exactly the unbounded allocation the cap exists to
    prevent; capping the input keeps the truncation deterministic at bounded
    cost.

## Pros and Cons of the Options

### Keep the flat rule

- Good: nothing changes.
- Bad: the loader ignores a definition it can see; the workaround is 8+ flat
  directories per pipeline, and the ticket's own repository notes call that
  "Homer's shape rather than a preference".

### An explicit manifest

- Good: the set of agents is declared, not inferred.
- Bad: new contract surface in `homer-agent.schema.json` and `AGENT.md`.
- Bad: a manifest is a way for one agent's definition to point at another's
  directory — the thing ADR-0049 exists to prevent.

### Recursive walk

- Good: no model or schema change; discovery is the only behaviour that moves.
- Good: bounded and deterministic, so an entry-cap truncation picks the same
  subset on every reload.
- Bad: identity is now a path, so anything that assumed a leaf name must be
  re-derived from it — done in this change for `sourceDirs`,
  `presentDirNames`, `removedAgentNames` and the log/error text.

## Amendment (2026-09-30): each listing is filtered and sorted before the budget applies

The walk capped every directory listing with `stream.limit(budget)` on the
**raw** entries, before the hidden-name, symlink and is-a-directory filters
and before the sort. Three consequences contradicted the decision above:
files, hidden entries and symlinks spent the budget that is documented as
counting directories; which entries survived the cut depended on the
filesystem's listing order (hashed on APFS and ext4 htree, newest-first on
tmpfs), so the same tree loaded different agents on different hosts; and a
cut that removed only non-directories set no truncation flag, so an agent
behind it vanished **without the warning**. The "capping the input keeps the
truncation deterministic" claim in the hardening decisions, and "an
entry-cap truncation picks the same subset on every reload" in the pros and
cons, were wrong: the sort ran after the cut, so it ordered an arbitrary
subset.

Now:

- **The budget counts directories after filtering.** A listing keeps only
  entries that are not hidden and are directories under `NOFOLLOW_LINKS` (a
  symlink never qualifies) — the same filters as before, applied first.
- **Listings are sorted by name before the cut**, so the kept directories are
  the first by name, the same set on every filesystem. The listing holds at
  most `budget + 1` entries (a bounded max-heap), not the whole directory.
- **A cut always sets the truncation flag and logs the warning**, including a
  directory listed with no budget left.
- **Raw-read budgets, charged for every entry read from any listing before
  filtering, in the same two levels as the directory budget.** Filtering first
  means every entry is read and `lstat`-ed, so without them a planted
  directory of millions of files would make every reload read all of them
  (CWE-400). `MAX_SUBTREE_RAW_ENTRIES` (200 000) is fresh for each top-level
  directory's subtree; running it out truncates that subtree only, and the
  warning names it, so one principal's files cannot starve another's agents.
  `MAX_TOTAL_RAW_ENTRIES` (2 000 000) bounds the whole walk, the search-path
  listing included, and running it out stops the walk. Either one is a cap hit
  (flag + warning). The entries read before a raw budget runs out are
  filesystem-ordered, so a tree that hits one is the one case whose kept set
  is not name-determined. Rejected alternative: a raw bound per listing only,
  which a caller multiplies. Each listing can stay under it while the subtree
  holds thousands of such listings, putting the worst case near 2x10^9 reads
  per reload. The two budgets hold it at 2x10^6 (the old raw-capped walk
  allowed about 10^7). 200 000 is twice a measured large tree: a reload over
  one directory of 100 000 plain files plus nested agents took about 195 ms
  after the change, against about 4 ms before, when the walk read 1 000
  entries and silently dropped every nested agent past them. That is about
  2 µs per entry: about 0.4 s for a subtree that spends its whole budget and
  about 4 s for a walk that spends the catalog's. Rejected alternative: keep
  capping the raw input and only sort what survives. It is cheap, but it is
  exactly the silent, host-dependent drop this amendment removes.

## More Information

- [ADR-0049](0049-agent-definitions-stay-self-contained.md) — definitions stay
  self-contained; nesting is layout, not inclusion.
- [ADR-0047](0047-security-by-design-and-threat-model.md) — the parent/child
  file-API and upload boundary.
- [ADR-0032](0032-ssh-runner-backend.md),
  [ADR-0043](0043-k8s-sync-workspaces.md),
  [ADR-0066](0066-external-pull-runners-and-unified-claim-queue.md) — the
  `HMR_AGENT_DIR` → `.homer/agent` upload that now excludes nested children.
