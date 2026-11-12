---
status: proposed
date: 2026-10-27
deciders: Lukáš Vlk
consulted: ADR-0020, ADR-0047, ADR-0052, ADR-0058, ADR-0066, ADR-0077, ADR-0101
---

# ADR-0098: The stored pre-start failure reason is a fixed-vocabulary sentence

## Context and Problem Statement

A run that ends `FAILED` before any of its commands started could only explain
itself in the server log and the trace span, so an operator reading the console
could not tell a lost lock from a full queue (issue #84). The fix stores a
reason on the run record and serves it through the status API — a new
principal-visible field whose text is built from what the failure handler saw.
What that text may contain is a durable contract: every future pre-start
failure cause extends it, and every reader who can open the run sees it.

## Decision Drivers

- The reason is served to every caller who can see the run, and it names parts
  of the system a caller may not be allowed to see: a runner alias (admin-only
  on the status API, ADR-0077), the id of the run that blocked this one
  (ADR-0058), a lock key, a value read from the run's inputs.
- Agents write their own names, tags and input values — everything interpolated
  into the text is attacker-controlled (root CLAUDE.md §12 rule 2).
- The status API is polled, not pushed; a reader learns of the failure through
  the `FAILED` status like every other one (ADR-0002).

## Considered Options

- Store the exception message, redacted, whatever it says.
- Map each typed cause to a fixed-vocabulary sentence built from its typed
  fields, never the message.

## Decision Outcome

Chosen option: **"Map each typed cause to a fixed-vocabulary sentence built
from its typed fields, never the exception message."**

Exactly seven causes store a reason: the `SKIP_CURRENT` lock skip, the `REJECT`
parallel-run cap, the full parallel-run wait queue, the full lock wait queue,
the claim timeout, the full claim queue and an unresolved `lockStrategy.key`
placeholder (the amendment below).
*(Amended 2026-11-12: the full lock wait queue is the sixth — the bound a run
waiting for a subject lock is refused by, added by
[ADR-0104](0104-lock-before-slot-admission-order.md).)* Any other exception —
including one that failed a run mid-flight — stores no reason: its message may
carry anything, and the reader gains nothing from it that the run's own record
does not say (ADR-0047 rule 7: secrets travel never in error text).

The mapping's obligations, which every new cause must keep:

- **No aliases.** The claim timeout renders the runner filter as "pinned runner
  filter" and never names the alias. `RunnerClaimTimeoutException` carries both
  renderings as required constructor parameters, so a forgotten call site
  cannot default into storing the alias-bearing one — fail closed, like the
  status API itself (ADR-0077).
- **The holder id travels as its own field.** A lock-skip reason names the run
  that held the lock; the id is kept out of the stored sentence and sent to the
  API layer as `failure_reason_run_id`, which is appended as `(run #123)` only
  to a caller whose `canSee` opens that run, so the existence of another
  principal's run is not revealed (the same existence-hiding as the 404 —
  ADR-0058, root CLAUDE.md §12 rule 3).
- **Sanitize and bound at the mapping, bound again at the write.** Control
  characters are stripped and the text is cut to 500 characters with a visible
  marker; the repository write applies the cap again, so no caller can grow the
  column past it (ADR-0047 rule 4).
- **Redact with the run's own redactor.** The final string passes the
  `SecretRedactor` built from the run's inputs and `@SYSENV`-backed env names
  (ADR-0052), so a secret value a filter or tag carried never reaches the
  column or the response.

### Consequences

- Good: the reason is safe to store and to serve whatever an agent name, a tag
  or an input value holds; the vocabulary is testable per cause.
- Good: a reader who cannot see the blocking run learns nothing about it.
- Bad: a new cause means editing the mapping and its vocabulary — copying the
  exception message would have been automatic.
- Bad: the reason is stored in a second transaction after the `FAILED` status
  commits, and the write emits no event of its own. A console client refetching
  on the `FAILED` event can therefore read the row before the reason has landed
  and shows a bare `FAILED` badge until its next poll tick. That gap is
  accepted: keeping the event-emitting writes on one path is worth more than
  the tick of delay, and the reason write must never change the status a kill
  or a failure already committed.

## Amendment (2026-11-12): an unresolved lock key placeholder is the seventh cause

A `lockStrategy.key` whose `{{NAME}}` the run's environment cannot resolve used
to be left as literal text, so every run of that agent rendered the same key and
unrelated subjects collided: one caller's run was skipped, queued behind or
killed by another's. The run is now refused before it starts — before it takes a
lock, a parallel-run slot or a workspace — and stores a reason naming the
placeholder.

**The decision.** `UnresolvedLockKeyPlaceholderException` is the seventh mapped
cause, and its obligations are the ones this ADR already fixes, applied to a
field that is new here: the reason names the **placeholder names only** — never
the rendered key, which holds input values, and never any value. The names are
deduplicated, sorted and bounded (five) before they leave the exception, so the
sentence stays short and stable whatever a template holds; a key with more
unresolved names is still refused, just not fully named.

The alternative — leaving the placeholder literal and letting the collision
happen — was rejected because it is an availability defect rather than a
cosmetic one: `KILL_OLD_RUN_CURRENT` on such a key lets any caller kill an
unrelated run, and `WAIT` lets any caller queue behind one (ADR-0047).

**Implementation.** `renderTemplatedKey` in `WorkspaceConfigFactory` checks the
template's placeholder names against the run's env and throws; `Process.execute`
already calls it above the workspace, slot and lock gates, so the denial
precedes all three, the `acceptParentLock` pass-through included. A resumed
continuation segment that does not inherit the registering run's inputs has no
value for a subject input, so a key templated on one needs
`continuation.inheritInputs` (ADR-0101).

## More Information

Implemented in `PreStartFailureReason`, `ProcessExecutor`'s never-ran failure
branch and `ProcessRepository.setFailureReason` (schema v30 in the
schema-version history). The runner alias rule is ADR-0077; the holder-id
visibility rule is ADR-0058's linkage applied to a second principal-visible
field.
