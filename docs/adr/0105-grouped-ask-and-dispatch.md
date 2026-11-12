---
status: proposed
date: 2026-11-12
deciders: Lukáš Vlk
consulted: ADR-0021, ADR-0026, ADR-0052, ADR-0101
---
# ADR-0105: Grouped ask-and-dispatch — N questions, one fire

## Context and Problem Statement

Ask-and-dispatch (ADR-0021) pairs **one** question with **one** target run. An
asking command that needs several answers from the operator before it can hand
work over has only two ways out: register one dispatch per question, which starts
the target once per answer and gives it each answer in isolation, or block on
`ask-and-wait` per question, which pins a process slot and a workspace for the
whole human-think time — the cost ADR-0021 exists to avoid. Neither expresses
"ask these three things, then start the agent once with all three". We need the
grouped form: N questions answered independently, one run started after the last
answer, carrying every answer.

## Decision Drivers

- The target must start **exactly once**, after the **last** answer — including
  when the final two answers land concurrently, and across a server restart.
- Each question must stay an ordinary question: independently answerable,
  independently visible in the operator inbox, and usable through the existing
  answer route with no new endpoint for the operator.
- The group is the unit of every lifecycle decision: TTL expiry, cancel, boot
  re-fire and the settled-row prune settle all members together, never partially.
- A group multiplies questions against a per-process cap and against the global
  PENDING cap, so both bounds must be checked **before** any row exists, and the
  group's size needs its own config bound.
- Crash safety must be the same two-step model as a single dispatch: a group left
  mid-fire settles FAILED rather than firing twice.
- No new secret surface: a group row holds no params its members' answers do not
  already hold, and answers must not reach logs, spans or argv beyond what a
  single dispatch already does (ADR-0052).

## Considered Options

- **One parent `dispatches` row (`kind = GROUP`) plus an ordered child table** —
  the ADR-0026 join shape: the group row carries the target, params, TTL, owner
  and status; `dispatch_group_questions` owns the ordered member set.
- **N independent SINGLE dispatches sharing a group id**, fired by the last one
  to settle.
- **A new `dispatch_groups` table with its own lifecycle**, beside `dispatches`.
- **Client-side sequencing** — the asker registers a single dispatch per question
  and passes a correlation id it uses to rejoin the answers.
- **One question whose answer is a multi-field payload** the operator fills in
  through the console.

## Decision Outcome

Chosen option: **"One parent `dispatches` row plus an ordered child table"**.

The group **is** a dispatch, so everything ADR-0021 built around a dispatch row
is reused unchanged: the claim CAS that makes a fire exactly-once, the PENDING
cap, the TTL sweep, the boot re-fire, the settled-row prune, the owner and secret
snapshots, the hop authorization, and the whole `/api/v1/dispatches` surface.
`kind = GROUP` selects the two places where a group differs — how its question
set is resolved and when it is allowed to fire — and nothing else branches.

The N independent dispatches were rejected because each one carries its own
claim, TTL and status: "the target starts once after the last answer" would
become a coordination problem between N rows, with N ways to half-succeed — one
answer firing early, one row expiring while its siblings are alive, and the
prune deleting a row another row's fire still needs. A separate table was
rejected for the mirror-image reason: it duplicates the status machine, the caps,
the sweep and the whole read API to gain nothing the `kind` column does not
already give.

A GROUP row's own `question_id` is a **synthetic sentinel** (`group:` + a UUID),
because `dispatches.question_id` is uniquely indexed and a group names no single
question; the ordered set is the child table's, keyed by `(dispatch_id,
position)` with `question_id` indexed for the member → group lookup. Every member
question is an ordinary `questions` row with `dispatch_bound = 1`, so it survives
its asker exactly as a single dispatch's question does (ADR-0021), and
`findByQuestionId` resolving **through** the member table is what lets the
existing answer route fire a group with no change to `QuestionRoutes.kt`: the
member's answer is the trigger, the group's own gate decides whether it fires.

The fire gate is `DispatchGroupService.aggregateAnswers`: null while any member is
still OPEN, otherwise the answers as a JSON array in `position` order, delivered
under the dispatch's `answerParam`. `DispatchService.fire` claims the group only
after that gate passes, and the **claim CAS remains the gate** — the gate read is
a pre-check, so the last two answers landing concurrently, or a boot re-fire
racing a live answer, still start exactly one run. An aggregate over
`MAX_ANSWER_BYTES` (256 KiB, well above `maxGroupSize` × `maxTextLength`) settles
the group FAILED rather than leaving it PENDING on answers no start can carry;
that throw is caught inside the fire path because the same path runs at boot,
where it would otherwise abort startup reconciliation.

`server.dispatch.maxGroupSize` (default 8) bounds a group's size, checked before
anything else and before any row is written, so a refused group is never
observably partial. The group counts against the global PENDING cap and against
the origin process's open-question cap — the latter is the bound on this call's
own multiplication, N questions at once.

### Consequences

- Good: the operator answers N ordinary questions; the console inbox annotates
  each member with its group through the dispatch summary it already renders, so
  no console change is needed.
- Good: one row carries the lifecycle, so cancel, TTL, boot re-fire and prune
  settle a group as a whole by construction rather than by coordination.
- Good: crash safety, caps, authorization and redaction are inherited from
  ADR-0021 unchanged; the new code is one service, one child table and the
  `kind` branches.
- Bad: a GROUP row's `question_id` is a value no question has, so any future
  reader of that column must branch on `kind` — the sentinel is the price of
  keeping one table and one unique index.
- Bad: member rows outlive their member questions (cancel, expire and orphan-fail
  delete the questions but keep the ordered set), so a settled group still reports
  the question ids it owned; the prune deletes them with the dispatch. A reader
  that assumes "member row ⇒ live question" is wrong.
- Neutral: the answers arrive as a JSON array under one param. A target that
  wants them separately must parse them; the alternative — N env vars — would
  make the target's input schema depend on the group's size.

## Pros and Cons of the Options

### N independent SINGLE dispatches sharing a group id

- Good: no schema change; each answer is a normal dispatch.
- Bad: "start once after the last answer" becomes cross-row coordination — the
  last answer must claim a group-wide flag that is not the row's own status.
- Bad: TTL, cancel and prune each have to treat the N rows as one unit anyway,
  reimplementing the group without the row to hang it on.

### A separate `dispatch_groups` table

- Good: the `dispatches` table stays single-question.
- Bad: a second status machine, second cap check, second sweep, second prune and
  a second read API, all parallel to the first.

### Client-side sequencing

- Good: no server change.
- Bad: the client would hold the "did everyone answer" state that the server
  already owns durably, and a client crash loses the group.

## More Information

- ADR-0021 — ask-and-dispatch, the row and lifecycle this extends.
- ADR-0026 — join continuations, the parent-row + ordered-child-table shape
  reused here.
- ADR-0052 — the secret snapshots a GROUP row carries like any other dispatch.
- ADR-0101 — input inheritance, which a group to the asking agent gets for free.
