---
status: accepted
date: 2026-08-21
deciders: Lukáš Vlk
---
# ADR-0047: Security is a design input, and these are the boundaries it defends

## Context and Problem Statement

Homer's security posture was real but unwritten. Individual decisions were
recorded — grants ([ADR-0037](0037-grants-authorization.md)), process
ownership ([ADR-0038](0038-process-ownership.md)), existence-hiding denials
([ADR-0039](0039-nested-dispatch-authority.md)), secret delivery
([ADR-0045](0045-external-secret-delivery.md)), per-owner checkpoint stores
([ADR-0046](0046-owner-scoped-langgraph-checkpoints.md)) — but the *model*
those decisions serve was not. Two things follow from that gap.

**Triage had no written yardstick.** Whether a finding crosses a boundary was
decided case by case, from a disposition that lived only in a maintainer's
notes. A reviewer with no access to those notes cannot tell a 7.4 that matters
from a 7.4 that does not, and so either fixes everything or argues about
severity scores.

**Features were hardened after the fact rather than designed.** The LangGraph
run-introspection work is the worked example. Across eleven review rounds on one
feature, nine findings were on code written to fix the previous finding: a
derived cache placed in an agent-writable directory produced a symlink-followed
write, then a symlink-followed read through the parent, then a TOCTOU race in
its temp-then-rename commit, then a hard-link overwrite once `O_EXCL` was
dropped to close the race. Deleting the cache ended that chain — and measurement
showed it had been saving about 3% of a status call. Its in-memory replacement
then produced an unbounded-map finding, whose fix (a semaphore) produced an
unbounded-queue finding, whose fix (a bounded wait) finally terminated.

None of those were subtle once stated. All of them were cheaper to prevent at
design time than to discover, fix, re-scan and re-resolve.

## Decision Drivers

- A reviewer must be able to decide, without tribal knowledge, whether a
  finding crosses a boundary that matters.
- The always-loaded instruction file ([`/CLAUDE.md`](../../CLAUDE.md)) should
  carry the rules, not the reasoning — every session pays for its length.
- Rules must be derived from defects this codebase actually shipped. A generic
  checklist gets skimmed; a rule with a scar gets followed.

## Considered Options

- **A — Rely on SAST plus review.** No written model; each finding argued on its
  own merits.
- **B — Write the full model into `/CLAUDE.md`.** Maximum visibility.
- **C — Terse rules in `/CLAUDE.md` §12, model and rationale in this ADR.**

## Decision Outcome

**Option C.** [`/CLAUDE.md`](../../CLAUDE.md) §12 states the principle, the
three boundaries and the design rules as a checklist, and points here. This ADR
holds the threat model and the reasoning.

Option A is what produced the eleven-round loop. Option B was tried first and
reverted in the same session: the section reached 82 lines, the longest in a
396-line file, most of it rationale — which §9's own "comments vs docs" rule
says belongs in an ADR.

### The threat model

Homer is an orchestrator that runs operator-authored commands, scripts, Claude
sessions and LangGraph graphs. It holds API tokens and cloud credentials,
spawns subprocesses on the server host, mounts and syncs workspaces, and
reaches ssh hosts and Kubernetes clusters — all on behalf of **distinct callers
holding distinct grants**. Agent definitions are authored by operators, but the
code they invoke is untrusted by construction: an agent's commands run as the
`homer` user with that user's full reach.

Three boundaries follow.

**Principals are a boundary.** Grants, ownership and visibility separate callers
who must not reach each other's runs, artifacts, checkpoints, questions or
results. This is the boundary Homer exists to enforce; crossing it is a defect
whatever its severity score. ADR-0037, ADR-0038 and ADR-0039 are its
implementation, ADR-0046 an instance of repairing it.

**Same-UID access is not a boundary.** Homer is single-node and every agent
shares one OS user. An attack whose prerequisite is same-UID write or same-UID
code execution grants no privilege the actor lacks, because that actor can
already read the database, the workspaces and the credentials directly. Such
findings are fixed when cheap and accepted with recorded reasoning when not.
Note what this does *not* excuse: making **Homer act** on the attacker's behalf
is an escalation even when the underlying data was already readable — the
distinction that made ADR-0046 worth building.

**Availability is in scope.** Homer's job is to run other people's work, so
degrading the orchestrator degrades every caller. Resource exhaustion counts
even when reachable only by an authenticated reader with no special grant, and
even when the immediate blast radius looks confined to one endpoint — the
LangGraph status endpoint blocked `Dispatchers.IO` threads, which is the pool
every SQLite transaction uses, so a read-only status poll could starve the
database.

### Why each rule exists

The rules in §12 are numbered here with the defect that produced them.

1. **Name the trust boundary the feature crosses.** ADR-0046's bypass existed
   because a thread identity crossed from an agent-writable artifact into a
   store-selection decision, and nobody had written down that it did.
2. **Treat everything an agent can write as attacker-controlled.** Workspace
   files, run artifacts, agent-declared env, checkpoint stores, and command
   output parsed into env are all agent-reachable. Validate at the point of
   use: the forged `langgraph.json` was well-formed JSON, so entry-point
   validation would have passed it.
3. **Authorize in the handler, fail closed, hide existence.** There is no
   implicit gate in the routing tree — ADR-0039 makes each handler call the
   `Authorizer` and return the same `404` for denial as for not-found, so a
   probe cannot enumerate what exists.
4. **Bound every resource the caller can multiply.** The in-memory state cache
   had no size cap; its key included a thread id read from an agent-writable
   artifact, so cardinality was attacker-influenced. Its only bound was the
   per-caller rate limiter — a limit defined in another component, which is the
   kind of invariant that breaks silently when someone retunes it. Make bounds
   local and explicit.
5. **Every bound introduces a queue, and the queue needs its own bound.**
   Capping concurrent subprocesses converted excess demand into suspended
   callers holding connections; that was flagged in turn. Cap the wait as well,
   and shed rather than accumulate. Two traps found while doing so: a shed
   response must never be cached (caching "busy" serves it for the whole TTL,
   turning a moment of load into a self-inflicted outage), and a timed
   acquisition must record success *inside* the timeout scope, because
   `withTimeoutOrNull` can report failure after the permit was taken and
   leaking permits wedges the endpoint permanently.
6. **Never write derived data into a directory another principal can write.**
   The four-finding chain above. Doing it safely needs `O_EXCL` on an
   unpredictable name plus directory-descriptor-relative operations, and
   `os.replace` takes no `dir_fd` on macOS, so there is no portable version —
   which is why the rule is "don't", with removal preferred over defence.
7. **Secrets travel by reference, never by manifest.** Pod specs used to carry
   the whole workspace env, exposing agent secrets to anyone who could read a
   manifest; ADR-0043 moved env to per-exec delivery and ADR-0045 keeps Homer
   out of secret resolution entirely.
8. **Prove the constraint with a test.** Symmetric-case blindness is the
   recurring cause of repeat findings: the symlink write got a test, its twin on
   the read path did not, and the read path became the next round. A cap or
   authorization rule without a test asserting the *denial* path is undefended.

### Fixing findings

A fix introduces a mechanism, and the mechanism is new attack surface. Three
consecutive rounds in the LangGraph loop were each flagged on the previous
round's fix, so re-examine what a fix adds before calling the issue closed, and
cover the symmetric cases — {read, write} × {file, parent} — plus any sibling
service sharing the pattern.

Prefer removing surface to defending it. When a component has produced repeated
findings and its measured benefit is small, delete it and solve the original
problem elsewhere. Measure before deciding: the cache at the centre of the loop
was assumed valuable and was worth about 3% of the call it optimized.

### Consequences

- Reviewers gain a written yardstick for "does this cross a boundary", and
  accepted risks must state their reasoning rather than being silently dropped.
- §12 binds the other sections of `/CLAUDE.md` and overrides them on conflict,
  including §2's minimalism — a bound, a gate or a denial test is not
  speculative work.
- New endpoints cost more up front: authorize, bound, bound the queue, and test
  the denial path. The eleven-round loop is the alternative.
- This ADR is the place to extend the model. A new boundary, or a rule earned by
  a new defect, is amended here and reflected as one line in §12.

## Amendment (2026-09-23): the tar pack walk's ancestor-swap residual is accepted

`TarStreams.addDirContents` resolves a directory entry and checks it against the
upload root, then descends on that resolved path; a regular file reaches
`TarStreams.addFile`, which opens it by name with `NOFOLLOW_LINKS`. That option
binds only the final path component, so an ancestor directory replaced by a
symlink *between* the containment check and the open is followed, and a file
readable by the runner's UID can enter a packed archive. A security-review finding (CWE-59 / CWE-367) reports exactly this on the tar pack path, reachable through
an external runner's `Download` op and Homer's own pack for instance uploads.

**Accepted, not fixed**, under this ADR's same-UID boundary. The prerequisite is
a concurrent writer with the runner's UID, and such an actor already reads every
file the race could disclose — it crosses no principal, so it is fixed when
cheap and recorded when not. Closing it is not cheap: it needs an
`openat`-style walk holding a descriptor on each validated ancestor plus an
`fstat` on the opened handle to confirm it is the inode the check saw, and
`java.nio` exposes neither portably — the same conclusion rule 6 reaches below
for `os.replace` and `dir_fd`. The walk already skips symlinks that resolve
outside the root outright, descends on the realpath it validated rather than the
entry's name (commit `8a7c7a4`), skips hard-linked files, and confines the
impact to confidentiality at the actor's own UID.

The residual was recorded in code at the point of use before this amendment
(`addFile`'s KDoc) and in `8a7c7a4`'s message; what was missing was the
repository-level decision. The disposition is **Accepted Risk** in the security scanner with
this ADR cited, and `SECURITY.md`'s accepted-risks table carries the
corresponding row.

2026-09-30: the ssh runner's SFTP upload walk (`WorkspaceSync.uploadDirInto` and
`uploadFile`) opens each file once with `NOFOLLOW_LINKS` as `addFile` does, and
its remaining ancestor-swap residual is covered by the same decision.

## Amendment (2026-09-30): the tar extract walk's ancestor-swap residual is accepted too

The 2026-09-23 amendment accepted the pack direction; this is its write-direction
counterpart. On the directory branch of `TarStreams.extractEntry`, each entry name is
contained lexically, `PathSafety.assertContained` checks the realpath and
`PathSafety.assertDanglingLinkContained` checks a dangling link, and only then is the
target created or opened by name. That open must follow a contained symlink — a live
workspace legitimately holds them (`node_modules/.bin`) — so a target or ancestor
swapped for a symlink leading out of the workspace *between* the checks and the open
is followed, and extracted bytes can land on a file writable by Homer's UID. A security-review finding (CWE-59 / CWE-367) reports exactly this, reachable through Kubernetes
sync-down and the external runner's tar download.

**Accepted, not fixed**, for the same reasons. The tar stream cannot plant the link:
link and device entries are skipped. The swap needs a concurrent local writer holding
Homer's UID, and that actor can already write every file the race could reach, so it
crosses no principal. The only close is the same `openat`-style descriptor walk plus
`fstat` that `java.nio` does not expose portably. The proposed re-check at the point of use
was considered and rejected: it narrows the window without closing it
(the open still resolves by name after the re-check), adds a realpath walk per
extracted entry on every sync-down, and needs a timing-based race test. The
single-file branch already writes with `NOFOLLOW_LINKS` and lstat-refuses symlink
components, so the residual is confined to the directory branch and to integrity at
Homer's own UID.

The disposition is **Accepted Risk** in the security scanner with this ADR cited, and `SECURITY.md`'s
accepted-risks table carries the corresponding row.

## Amendment (2026-10-06): a failed tar-entry mode normalization fails closed

The tar-entry mode rule (apply only the normalized `0755`/`0644`, never the raw bits;
recorded in the runner contract in
[`runners/CLAUDE.md`](../../app/app/src/main/kotlin/homer/app/runners/CLAUDE.md);
2026-10-09 note: it was misreferenced as "above") had a fail-open residual:
`extractEntry` wrote the entry first and only then normalized, and a
chmod that failed was logged and swallowed — leaving the just-truncated, attacker-supplied
content in place with its pre-existing, possibly permissive mode (CWE-732). Unlike the two amendments above, this is **fixed, not accepted**: the entry is
refused — the just-written target is deleted and a `SecurityException` fails the extract —
so no unnormalized content survives. A target without a POSIX view has no mode to
normalize and stays a bytes-only write (a deliberate portability degradation).
A chmod failure on a filesystem the UID can write but not `chmod` remains possible, but it
now denies the transfer instead of being the vulnerability. The residual delete-failure
case (both chmod and unlink fail) is surfaced in the denial message rather than accepted:
the extract still fails, and the caller sees that content was left behind.

**Amended (2026-10-06):** the refusal originally fired only for an `IOException` from the
chmod, so a `SecurityException` or a provider-specific `RuntimeException` skipped it and
left the just-written content with its pre-existing mode (CWE-732): the
same fail-open gap as the original finding. The catch now covers any failure of the mode
normalization, so the fail-closed scope is every chmod failure, not only an I/O one. No
reachable trigger was demonstrated on the default provider (Homer installs no
`SecurityManager` and no custom `FileSystemProvider`), so this is defense-in-depth on an
explicit control rather than a fix for a reachable defect.

## Amendment (2026-10-06): the fail-closed cleanup covers a dangling link's destination

The amendment above deletes `target` when the chmod fails, but on the directory branch
`target` is the entry's *name*, and the write deliberately follows a contained dangling
symlink (`node_modules/.bin` shape, pinned by `TarStreamsTest`). When the name is a link,
the bytes land at the link's destination, so deleting only the name unlinks the link and
leaves the attacker-supplied file at its destination with the unnormalized mode the
fail-closed rule exists to prevent (CWE-59). **Fixed, not accepted**: the
cleanup now resolves the write's landing path with `PathSafety.resolveDanglingLinkTarget`
(already the companion of `assertDanglingLinkContained`) and deletes it before the link
name, folding a failed delete into the same denial message. Containment is unchanged — the
existing guards still reject a link that leads outside the root; only the cleanup widened.
The single-file branch writes with `NOFOLLOW_LINKS` and is untouched, as is the non-POSIX
bytes-only path.

## Amendment (2026-10-16): findings are cited by CWE, not by a tool

Security asked that the repository name no third-party security tool. Code
comments, test names, ADRs and `SECURITY.md` cite a finding by its CWE and by
the decision record that settles it, never by a scanner's name or its finding
id. A test that proves a fix (rule 8) names the CWE in its name or comment, at
security's request.

- A finding raised again is matched to its record by CWE and location:
  `SECURITY.md`'s accepted-risks table names both, in its *Finding* and
  *Where* columns, and links the ADR that argues the decision.
- Accepted residual: two findings that share a CWE and a location are told
  apart by their description alone, because no id is recorded any more.

## Pros and Cons of the Options

### Option A — SAST plus review, no written model

- Good: nothing to maintain.
- Bad: severity scores substitute for judgment; the single-UID disposition stays
  tribal knowledge; features get hardened reactively, one finding at a time.

### Option B — full model in `/CLAUDE.md`

- Good: maximum visibility; impossible to miss.
- Bad: 82 lines of mostly rationale in a file loaded into every session, for
  content that matters only when designing a security-sensitive feature.
  Contradicts §9's own rule that rationale belongs in an ADR.

### Option C — terse rules in §12, model here (chosen)

- Good: the always-loaded file carries only what must be applied; the reasoning
  stays findable and citable; extending the model is a normal ADR amendment.
- Bad: two files to keep aligned — a rule added to §12 without its entry here
  loses its justification, and nothing automated catches that.

## More Information

Implementation of the boundaries: ADR-0037 (grants), ADR-0038 (ownership),
ADR-0039 (nested dispatch authority and existence-hiding denials), ADR-0043
(per-exec env), ADR-0045 (external secret delivery), ADR-0046 (per-owner
checkpoint stores), ADR-0068 (nested agent discovery — a parent's file API and
`HMR_AGENT_DIR` upload are denied at the parent/child boundary).
