---
status: accepted
date: 2026-08-20
deciders: Lukáš Vlk
---
# ADR-0046: LangGraph checkpoint stores are scoped per owner

## Context and Problem Statement

[ADR-0044](0044-langgraph-resume-and-run-introspection.md) gave a run a
**thread identity** so status and resume could find its checkpoints: the
Python runtime writes `langgraph.json` (`threadId`, `module`, `segment`)
into the run's artifacts directory before every invoke, and
`LangGraphRunStatusService.identity()` reads it back.

That artifact lives in `HMR_ARTIFACTS_DIR` — a directory the run's own
commands are *meant* to write. Meanwhile every LangGraph run opened the
**same** checkpoint database: `resolveLangGraphStateDir()` returned one
server-wide path, so `checkpoints.sqlite` held every principal's threads.
Nothing bound a thread id to the process or owner that created it.

A run that rewrites its own `langgraph.json` with a foreign thread id
therefore makes `GET /processes/{id}/langgraph` report another
principal's node statuses and errors, and makes
`POST /processes/{id}/resume` **recover another principal's checkpoint**
into a new run — an IDOR-style authorization bypass across the boundary
[ADR-0038](0038-process-ownership.md) and
[ADR-0039](0039-nested-dispatch-authority.md) established. Found by SAST
(7.4, CWE-639/345).

Two properties of Homer bound the problem. Writing the artifact requires
running code as the `homer` user, and that same capability already allows
opening the shared `checkpoints.sqlite` directly — `LangGraphExecutor`
passes `HOMER_LG_STATE_DIR` to every LangGraph command — so the
*disclosure* half of the attack crosses no boundary that a same-UID
actor had not already crossed. What it genuinely adds is **making Homer
act**: resume starts a run that continues another principal's workflow,
with that workflow's accumulated state and side effects. Direct database
reads do not grant that.

## Decision Drivers

- The authorization boundary in Homer is the **principal** (owner ∪
  grants ∪ admin), not the OS user; single-UID reasoning must not be
  allowed to excuse a cross-principal bypass.
- A thread id originating in agent-writable space cannot be made
  trustworthy by validating it — only by making a forged value
  unusable, or by not taking it from there at all.
- Fixes must not change the runtime's env contract. `HMR_LG_THREAD` is
  the documented fresh-vs-resume signal agents gate on
  (`when: '[ -z "$HMR_LG_THREAD" ]'`, see [`app/AGENT.md`](../../app/AGENT.md)).
- Resume chains must keep working: continuations — including join
  continuations that target a *different* agent — must still reach the
  thread their origin run created.
- Proportionality. The exploit needs same-UID code execution, so an
  expensive fix is not warranted if a cheap one closes the boundary.

## Considered Options

- **A — Scope the checkpoint store per owner.** A forged thread id is
  simply absent from the database that gets opened.
- **B — Server-minted thread ids.** Homer generates the id, persists it,
  and ignores the artifact.
- **C — Validate the artifact.** Record the first thread id observed for
  a process and reject later changes.
- **D — Accept the risk.** Document it as same-UID, out of the threat
  model.

## Decision Outcome

Chosen option: **"A — Scope the checkpoint store per owner"**.
`resolveLangGraphStateDir(owner)` now returns
`<root>/<owner-segment>`, and both call sites pass the owner of the
process they act for — `LangGraphExecutor` from `ExecutionContext.owner`,
`LangGraphRunStatusService` from `ProcessInfo.owner`. Unowned runs
(unsecured mode, `agentdebug`) share an `_unowned` bucket.

Because the directory name *is* the boundary, the segment rule has to be
injective: two distinct principals must never produce one segment. A name
is used verbatim only when it is lowercase, at most 64 characters, drawn
from `[a-z0-9._-]`, and does not start with `_` or `.`; everything else
becomes `_h-<128-bit hash>`. Each clause closes a collision — reserving
the leading `_` puts `_unowned` and the hashes out of reach of any
principal name, lowercase-only keeps case-variant names apart on a
case-insensitive filesystem, and the character set plus leading-`.` rule
stops `..` or an embedded separator from escaping the root. The first
implementation got this wrong twice (a principal could be named
`_unowned`, and unprefixed hashes shared the verbatim namespace), both
caught by rescan — the rule is stated here in full because "sanitize the
name" is not a strong enough specification to implement safely.

This closes the boundary rather than patching the symptom. The artifact
stays untrusted — and stays *harmless*, because the worst a forged id can
now reach is another of the forging principal's own threads, which that
principal can already see and resume through the owning process's own
endpoints.

Option B was rejected as disproportionate here, not wrong: it would need
a new env var (reusing `HMR_LG_THREAD` would break the documented
fresh-run gate), a Python-side change, and somewhere durable to persist
the id — and after option A its remaining value is integrity against a
run corrupting *its own* identity, which is self-inflicted. Option C
cannot work: the id originates in agent-controlled space, so the first
value the server observes may already be forged.

### Consequences

- Good: a forged thread id cannot address another principal's
  checkpoints through either endpoint. The cross-principal bypass is
  closed at the storage layer, so it stays closed for any future reader
  of the store.
- Good: it also narrows the pre-existing direct-read exposure the SAST
  finding did not mention — a LangGraph command now receives a
  `HOMER_LG_STATE_DIR` containing only its own owner's threads, so
  same-UID code can no longer enumerate every principal's checkpoints.
- Good: no runtime protocol change, no schema change, no new
  configuration. `stateDir` keeps its meaning as the root.
- Good: owner is copied down run chains (ADR-0038), so resume,
  continuations, and cross-agent join continuations resolve to the same
  directory as the run that created the thread.
- Bad: **breaking on upgrade.** Checkpoints already written to
  `<stateDir>/checkpoints.sqlite` are no longer read, so threads parked
  across the upgrade become unresumable until an operator moves that file
  into the appropriate owner directory. Documented in
  [`app/SERVER.md`](../../app/SERVER.md).
- Bad: a principal renamed in config loses access to its old threads —
  the directory is keyed by principal name, not a stable id.
- Bad: threads are not addressable across owners even when that would be
  legitimate; no feature needs it today, and adding one would require
  revisiting this ADR rather than widening the scope quietly.
- Neutral: the artifact remains untrusted input. Any future feature that
  derives authority from it — rather than merely locating data inside an
  already-scoped store — reopens this class of bug.

## Pros and Cons of the Options

### Option A — Per-owner store (chosen)

- Good: closes the boundary at the storage layer; two call-site changes
  and one path helper; no protocol, schema, or config change.
- Good: fixes the unmentioned direct-read exposure as a side effect.
- Bad: breaking for in-flight threads; principal renames orphan
  directories.

### Option B — Server-minted thread ids

- Good: the strongest integrity story — Homer would never read identity
  from agent-writable space at all.
- Neutral: composes with option A rather than competing with it; worth
  revisiting if the artifact ever carries more than a locator.
- Bad: needs a new env var, because `HMR_LG_THREAD` already means
  "re-enter an existing thread" and agents gate fresh-run steps on its
  absence.
- Bad: needs a Python-side change and durable server-side storage for
  the id (a schema bump), for a residual that is self-inflicted once the
  store is scoped.

### Option C — Validate the artifact

- Good: no layout change, no migration.
- Bad: does not work. The runtime mints the id inside the run, so the
  first value the server sees is already agent-controlled; there is
  nothing authentic to compare against.

### Option D — Accept the risk

- Good: free.
- Bad: leaves a cross-*principal* bypass justified only by same-UID
  reasoning, which does not apply — the grants model treats principals
  as the boundary, and `POST /resume` grants an actor something direct
  database access does not: Homer executing another principal's workflow.

## More Information

- SAST finding "Trusted Artifact Thread ID Spoofing" (7.4,
  CWE-639 / CWE-345).
- [ADR-0044](0044-langgraph-resume-and-run-introspection.md) — the run
  identity artifact and the resume/status endpoints this constrains.
- [ADR-0038](0038-process-ownership.md) — process ownership and the union
  visibility rule that defines the boundary being enforced here.
- [ADR-0024](0024-langgraph-command-type.md) — the thin-launcher split
  that puts checkpointing in the Python runtime.
- Layout, sanitization, and upgrade steps: [`app/SERVER.md`](../../app/SERVER.md)
  § *Checkpoint store layout (per owner)*.
