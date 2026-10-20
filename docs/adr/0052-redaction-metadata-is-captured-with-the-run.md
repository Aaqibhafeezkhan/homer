---
status: accepted
date: 2026-08-24
deciders: Lukáš Vlk
consulted: SAST (MR !6)
---
# ADR-0052: Input-redaction metadata is captured with the run, not re-derived from the agent

## Context and Problem Statement

A run's `input_params` are stored in the process row and returned by the process
list and detail APIs, with secret values replaced by `***`. Which values counted as
secret was decided at *response* time, by reading the agent's **current**
definition: every input currently flagged `secret: true`.

That works only while the definition does not change. An input collected under
`secret: true` and later edited to `secret: false` — same `envName` — starts being
served in the clear from every retained record that holds it. Renaming an input
already fails closed (the stored key falls out of the current definition's env
names and is redacted as unprovable), and a deleted agent fails closed too; the
flip is the one case that opens.

ADR-0048 made this materially worse rather than merely theoretical. Records now
deliberately outlive their workspaces so that a `/processes/{id}` link pasted into
a ticket keeps resolving, which is exactly the retention window over which an agent
definition is likely to be edited.

## Decision Drivers

- Whether a value was secret is a fact about **the run**, not about the definition
  as it stands today.
- Stored input values cannot simply be redacted at write time: `handleRetry` reads
  them back to rebuild a retry.
- An upgrade must not silently change what existing records show.
- §12 rule 2: validate at the point of use, and do not let a later edit reopen a
  closed door.

## Considered Options

- **A. Persist the secret key set with the row** and redact from it.
- **B. Redact every input of any record whose workspace has been purged.**
- **C. Leave it, and document that flipping `secret` exposes history.**

## Decision Outcome

Chosen option: **"A"**.

`processes.secret_input_keys` (schema v15) stores the `envName`s the agent declared
`secret: true` at the moment the run started, as a JSON array. `redactInputParams`
prefers it over the current definition.

Redaction uses the **union** of the snapshot and the agent's current flags, not the
snapshot alone. Both directions are real and neither covers the other:

- the **snapshot** keeps a value redacted after the agent is edited to
  `secret: false` — the door cannot be reopened by an edit;
- the **current flags** mean marking an input `secret: true` today also protects
  records already retained — which is the whole point of the flag when an operator
  *discovers* a value was sensitive, and is the behaviour that existed before this
  ADR.

An earlier revision of this decision preferred the snapshot outright, reasoning that
tightening should not retroactively hide a value the record always showed. That was
wrong: it prioritised display consistency over remediation, and silently removed a
protection operators already had. Corrected on the same day it shipped, before
release.

The column is **nullable, and the three states are distinct**:

- a **set of keys** — captured at run time;
- an **empty set** — the agent had no secret inputs at run time. Meaningful, not
  missing;
- **null** — no snapshot was captured (a row from before v15). The union then
  reduces to the current definition, precisely what those rows have always used.

That last state is a deliberate refusal to fail closed. Failing closed would blank
the inputs of every record in the database on upgrade, to close a window those rows
were never protected from anyway — a visible regression traded for no real gain.

The capture parameter is `Set<String>?` defaulting to `null`, not `emptySet()`.
The distinction matters: a default of `emptySet()` would mean a caller that forgot
to pass a snapshot silently asserts "nothing was secret" and disables redaction
entirely. A test caught exactly that during implementation.

Option B was rejected because retry needs the stored values. Option C was rejected
because the exposure is silent and the record can outlive the decision by months.

### The snapshot governs every consumer of a replayed input, not just the API

Storing the snapshot is half the decision; the other half is that **every** path
reading a stored input must consult it. That was not true when this ADR was first
written: API redaction used it and trace-tag construction did not, so a retry or
resume of a drifted definition exported the historical secret to Langfuse as a
cleartext `paramName:value` tag — a worse sink than the API, since it leaves the
trust boundary entirely.

Two consumers now derive from the snapshot on a replay path:

- **Trace tags.** `langfuseTraceTags` takes the secret set as a **required**
  parameter with no default. Agent load rejects tagging an input that is secret
  *now*, which is a different question from "was it secret when these values were
  collected", and a defaulted parameter is exactly how a future call site would
  silently reintroduce the leak. Omitting it is a compile error.
- **The replay's own record.** `processFactory.create` takes
  `extraSecretInputKeys`, so a retry stores the union of the source run's snapshot
  and the current flags. Without it the new row would hold the historical secret
  next to a snapshot claiming it was never secret, reopening the API leak this ADR
  closed — one process later.

`extraSecretInputKeys` **is** defaulted, unlike the tag parameter. The asymmetry is
deliberate: omitting it yields the current definition's flags, which is correct for
every fresh-input path, so the default is the right answer rather than a silent
wrong one.

**Dispatch and continuation fires replay stored parameters too**, and were left
without a snapshot in the first revision of this decision, recorded as a known gap.
Closed in schema v16: `dispatches.secret_input_keys` and
`continuations.secret_input_keys` capture the target agent's flags at *registration*,
and `AgentRunStarter.start` takes an `extraSecretInputKeys` set that feeds the tags
and the fired run's own snapshot. Rows registered before v16 hold `NULL` and fall
back to the flags in force at fire time — what they already did.

That gap lasted two commits before the scanner found it independently, which is the
argument for closing this kind of thing when it is noticed rather than filing it:
the deferral reasoning ("narrower case, schema change") was about cost, and the cost
turned out to be one column on two tables.

The same snapshot governs those rows' **API responses**, not only their fires:
`/api/v1/dispatches` and `/api/v1/continuations` returned `params` verbatim to
anyone who could see the row. `redactStoredParams` applies the union policy there
too, and borrows `redactInputParams`' rule of masking keys absent from the current
definition **only when there is no snapshot**.

With a snapshot, unknown keys stay readable: these maps legitimately carry control
parameters that were never declared inputs. Without one — a row registered before
v16 — that does not hold, and the first revision of this rule wrongly claimed it did:
a formerly-secret input renamed since matches neither check and would come back in
cleartext. Those rows now fail closed on unknown keys and age out with their TTL.

A second, independent mistake shipped in the same revision: a dispatch or
continuation row's `params` is stored keyed by **paramName** — the caller-supplied
request key, persisted verbatim by `DispatchService.create`/`ContinuationService.create`
— never by envName; `AgentInputResolver.resolveFromParams` converts to envName only at
fire time. `redactStoredParams` compared the stored (paramName) keys directly against
envName values, so an input whose `paramName` differs from its `envName` fell to
`else -> v` and came back unredacted.

The first fix translated the secret-envName set — and the unknown-key check above —
to paramNames via the *current* agent definition, and let that translation **replace**
the raw envName comparison instead of adding to it. That regressed two cases the raw
comparison had always covered, because it needs a *current* input to translate
through: a secret input **deleted** since registration (snapshot `{"token"}`, no
current input left to translate `"token"` from), and one **re-pointed to a different
envName** with its `secret` flag flipped off (the snapshot's envName no longer matches
any current input's). Both came back in cleartext under a snapshot that named them
secret.

Corrected to **union** the two checks: a stored key is redacted if it translates to a
secret paramName *or* if it literally equals a registered secret envName — the latter
covers deletion and re-pointing, and has no legitimate readable case (envName defaults
to paramName absent an explicit override, so the common shape is a literal match).
With the union in place, the residual gap is exactly the one the snapshot always had:
it covers a since-renamed secret input only while its `paramName` is unchanged, since
the snapshot records envNames, not paramNames. Recording paramNames too would close
that narrower gap but needs a schema change, not warranted for a case that only arises
when an agent author explicitly sets a `paramName` different from `envName` *and* that
input is later renamed again.

The security scanner's MR scan of the fix branch confirmed the residual and recommended the schema
change: closed in schema v17. `dispatches.secret_param_names` and
`continuations.secret_param_names` capture the target agent's secret **paramNames**
at registration, alongside the pre-existing envName-keyed `secret_input_keys` —
mirroring exactly how v16 added the envName snapshot (same migration style, same
additive nullable column, same JSON-array serialization, captured at both
`DispatchService.create`/`ContinuationService.create` call sites, including
`createJoin`). Since it is already in the params map's own key space,
`redactStoredParams` checks it directly, with no current-definition translation to
lose when an input is deleted or renamed — closing the gap the v16-pattern union fix
could not: a `paramName`/`envName` split whose input is deleted or re-pointed to a
different envName since registration. Rows registered before v17 hold `NULL` for the
new column and keep exactly the coverage the envName-only union already provided —
the same accepted-residual framing as pre-v16 rows above, bounded by the same TTL.

**Still open:** `dispatchNow` fires immediately with caller-supplied parameters, so
it has no stored history to honour and needs no snapshot.

### Consequences

- Good: redaction no longer depends on a mutable definition; the door cannot be
  reopened by an edit.
- Neutral: schema v14 → v15, one nullable column, additive; the only production
  caller (`ProcessExecutor`, via `Process.secretInputKeys`) always captures.
- Bad: rows created before v15 keep the old behaviour indefinitely. Accepted, and
  bounded — those rows are purged on the normal retention schedule.

## More Information

- Same underlying mistake as [ADR-0051](0051-cooldown-is-enforced-in-the-insert-transaction.md):
  a historical fact re-derived from current state instead of recorded when it
  happened. Different surface, so a separate ADR.
- Reported by the security scanner on MR !6.
