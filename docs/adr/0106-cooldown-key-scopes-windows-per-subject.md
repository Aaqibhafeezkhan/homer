---
status: proposed
date: 2026-11-12
deciders: Lukáš Vlk
consulted: ADR-0020, ADR-0047, ADR-0051, ADR-0052, ADR-0083
---

# ADR-0106: `cooldown.key` counts the cooldown windows per rendered subject

## Context and Problem Statement

`cooldown` bounds how often an agent may start: `minInterval` paces every start,
`afterFailure` backs off an automated repeat of a failure (ADR-0051). Both windows
are counted **per agent**, over the agent's `processes` rows.

That is the wrong unit for the agents the bound exists for. A reviewer agent started
once per merge request — `homer review --mr 41`, then `--mr 42` — has one window for
all of them. A failure on MR 41 makes the next start for MR 42 wait out
`afterFailure`; a `minInterval` meant to be "at most one comment per MR per ten
minutes" is really "at most one comment per ten minutes, whichever MR", so MR 42
waits behind MR 41's comment. The agent's configuration cannot express the subject
it is actually being paced against, because the subject lives in the run's inputs,
which the cooldown never sees.

The ticket asks for an optional `cooldown.key`: a template, rendered per run, that
selects which earlier runs a window counts. Without a key, nothing changes.

## Decision Drivers

- The existing bound must not weaken. Whatever a key does, a start without one keeps
  counting every row of its agent (ADR-0051).
- The check has to stay inside `obtainProcessId`'s INSERT transaction. A pre-flight
  read is a TOCTOU, and ADR-0051 exists because of exactly that.
- The key is derived from the run's inputs, so it does not exist until the inputs are
  resolved — which happens after the other pre-flight checks.
- A key that cannot be rendered must fail the run, not widen the bound. A template
  handed on as a literal `{{MR}}` would give every subject the *same* key, i.e. the
  per-agent behaviour the feature was asked to replace, silently.
- §12 rule 4: the key is caller-sized text, so it needs a local bound. §12 rule 7: it
  is derived from inputs that may be secret, so it must not reach a message, a log or
  a span.
- The row's key must be a fact recorded when the run starts, not re-derived later —
  the same lesson ADR-0051 drew for `created_at` and `ended_at`.

## Considered Options

What the key is stored as:

- **A. A `cooldown_key` column on `processes`**, written by the INSERT, indexed
  together with `agent_name`.
- **B. No column**: render the key from the agent definition at read time, in the
  cooldown query.
- **C. A separate `agent_cooldown_subjects` table** holding the last start and last
  failure per `(agent, key)`.
- **D. Fold the key into the agent name** (`"reviewer#mr-41"`), so the existing
  per-agent reads work unchanged.

What an unrenderable key does:

- **A. Fail the start**, before any row exists.
- **B. Fall back to a shared key** so the run proceeds under the per-agent bound.
- **C. Fall back to no key**, so the run proceeds outside the bound entirely.

## Decision Outcome

Chosen option: **A** for storage, **A** for rendering failures.

**1. `cooldown.key` is an optional template on `Cooldown`.** It is rendered **once**,
at start, from `agent.env + resolved inputs`, and the result is stored on the process
row by the same INSERT that stores `created_at` — inside the transaction that also
evaluates the windows (schema v31, `processes.cooldown_key`, nullable). Re-deriving
it at read time (option B) would read a definition that may have changed since the
run, and option D would put a rendered input into `agent_name`, where it would leak
into `ProcessInfo`, the status API, spans, log lines and the console — every surface
that treats an agent name as an identifier. Option C adds a table and a write to hold
state the row already carries, and would need its own retention path.

**2. The keyed read adds one equality term.** With a key, both cooldown reads gain
`cooldown_key = ?`; without one they are byte-for-byte the query they were, counting
every row of the agent. Matching is Exposed `eq` — exact equality, no `LIKE`, so a key
containing `%` or `_` matches only itself.

**3. Null-key semantics.** A keyed agent's window counts only rows whose
`cooldown_key` equals its rendered key: rows from before the upgrade, and rows of
keyless starts, are ignored. A keyless agent's window counts every row of the agent,
keyed ones included — that is what "no key means unchanged" has to mean, and it is
the conservative direction: a keyless agent cannot be paced by a keyed one's absence.

**4. An unrenderable key fails the run.** A `{{VAR}}` that does not resolve from the
run's env or inputs, a blank rendered value, a value over 128 characters, or a value
outside `^[A-Za-z0-9._:/-]+$` refuses the start with `400` — before `create`, so no
process row exists to clean up. There is no shared fallback (option B): it would make
the silent widening above the *designed* behaviour. There is no no-key fallback
(option C) either: it would let a broken template start unbounded runs, which is worse
than the per-agent bound it replaced. The charset is a stored, indexed token's
charset: no whitespace to normalize, no quote, `%` or `_` for a later `LIKE` to widen,
and no brace that would let a placeholder-looking *value* pass as an unrendered
template.

**5. The template is validated at load.** `Cooldown`'s `init` rejects a blank key, a
key over 256 characters, and a `{{` that does not open a `\w+` name — an authoring
error should fail `reload()`, not every run. `Agent`'s `init` rejects a key naming an
input declared `secret: true`, and one naming an agent `env` entry whose raw value
referenced `@SYSENV:` (that value is a secret by construction): the key is stored in
plain text on the row and indexed, so naming a secret would put that value in the
database and in the index (§12 rule 7). The renderer repeats the check at the point of
use for replays of stored inputs (retry, resume, dispatch and continuation fires),
which carry the source run's snapshot of what was secret then — a key naming such an
input is refused before any row exists: a `400` on retry and resume, a failed fire
on dispatch and continuation (those two are background fires; `HomerException`
settles the row `FAILED` with no HTTP exchange).

**6. The refusal never carries the key.** The rendered value is derived from the run's
inputs, so `CooldownActiveException` and the `400` name the agent and the window, never
the key — and an unresolved-placeholder refusal names the *placeholder*, which is a
name the agent author wrote, never an env value.

**7. The two indexes are named for their reads.** `processes_agent_cooldown_key`
`(agent_name, cooldown_key)` serves the `minInterval` read;
`processes_agent_cooldown_status_ended` `(agent_name, cooldown_key, status, ended_at)`
serves the `afterFailure` read. The keyless shapes keep their existing indexes
(`processes_agent_name`, `processes_agent_status_ended`) — the two new ones are
strictly narrower, so a keyless query would not choose them anyway. The two keyed
plans are pinned at 150 000 rows with and without `sqlite_stat1` in
`DatabaseMigrationTest`; the keyless shapes stay pinned by the v24 plan test, which
plans them against a freshly migrated database, and the `optimize` test screens the
process shapes for table scans with statistics gathered (3 000 rows).

**8. The manual-recovery paths keep their ADR-0051 amendment.** Retry, resume and
debug still start through `withoutFailureBackoff()`, which keeps `key` when it keeps
`minInterval` — so a keyed `minInterval` paces them per subject, and a keyed
`afterFailure` still does not gate them. The key is rendered for those three paths
from the same inputs they already resolve.

**9. The key is not a read surface.** It is not added to `ProcessInfo`, the status
API or the console, and is not logged at INFO or above. The column exists for the
cooldown query.

## Consequences

- Good: an agent can be paced per merge request, ticket or repository without one
  subject's failure or comment blocking another.
- Good: the bound still holds under concurrent starts — the keyed reads happen inside
  the same `BEGIN IMMEDIATE` transaction as the INSERT, and a denial test asserts that
  20 concurrent same-key starts admit exactly one.
- Good: no backfill and no behaviour change for existing rows. A keyed agent treats
  pre-upgrade rows as "no subject", which reads as "never started for this subject"
  for at most one window.
- Neutral: schema v30 → v31, one nullable column and two indexes, additive.
- Bad: `AgentRunStarter`'s pre-flight order now differs between keyed and keyless
  agents — keyed agents run the cycle, depth and cost-cap checks, then resolve their
  inputs, then take the cooldown refusal. Accepted: the key does not exist earlier,
  and the authoritative refusal is in the transaction either way. The keyless order is
  untouched, so a start that is going to be refused still resolves nothing.
- Bad: a key that is wrong (an input that is empty for some subjects) is only visible
  as a refusal. Accepted — a silently shared key is the failure mode this ADR is
  written to prevent.

## More Information

- Amends ADR-0051 by reference: the enforcement point (the INSERT transaction), the
  single arithmetic copy (`CooldownDecision`) and the pre-flight gate all stay. The
  window's *scope* becomes a parameter of the reads.
- ADR-0051's 2026-09-26 amendment (retry, resume and debug skip `afterFailure`) still
  applies and is unchanged by this ADR.
- §12 rules 4 and 7; §12 rule 8 for the denial tests.
