---
status: accepted
date: 2026-06-24
deciders: Lukáš Vlk
---
# ADR-0029: Flow-scoped lock pass-through (`acceptParentLock`)

> **Amendment — transport superseded by [ADR-0030](0030-runagent-in-process-dispatch.md).**
> The decision below stands: `acceptParentLock` still lets a descendant skip a
> lock its ancestor holds, and the key set still rides on
> `WorkspaceConfig.ancestorLockKeys`. Only the *transport* changed. ADR-0030
> made `RunAgent` dispatch in-process, so there is no HTTP self-call, no
> `X-Homer-Ancestor-Lock-Keys` header, and no loopback token — the loopback
> machinery was removed entirely. **Decision point 3 ("Trusted only on
> loopback") therefore describes a mechanism that no longer exists.** The
> property it guaranteed is now structural rather than gated: ancestor keys are
> passed within the server process and are never read from an inbound request,
> so an external caller has no way to supply them at all.

## Context and Problem Statement

An agent acquires a lock for its entire run (`Process.execute`), keyed by the
rendered `lockStrategy.key` template. The lock serializes runs that compute the
same key: a second run `WAIT`s, is `SKIP_CURRENT`-rejected, or
`KILL_OLD_RUN_CURRENT`-kills the holder.

Operators want **one logical lock for a whole `RunAgent` flow** — agent A
invokes B invokes C, and the chain should be treated as a single unit on a
shared key (e.g. `order-{{ORDER_ID}}`). With the existing model, giving every
agent in the flow the same key makes the flow **collide with itself**: B
computes the same key A holds, so B waits/skips/kills against its own parent.

Two facts shape the solution:

- **`RunAgent` is fire-and-forget.** The parent HTTP-POSTs the child, gets back
  `200 {processId}`, and returns immediately — it does not wait for the child.
  So the parent's lock is released long before the leaf finishes; the runs'
  lifetimes do not nest. "One lock held continuously across the whole flow"
  cannot be built by simply holding the root's lock.
- **An invocation chain already flows parent→child**, carried by the
  `X-Homer-Invocation-Chain` header and trusted only on loopback self-calls
  (ADR-0008). It is the natural carrier for flow-scoped state.

The narrow, well-defined need is therefore: **stop a flow from self-colliding
on a shared key**, while leaving contention against *independent* flows
unchanged.

## Decision Drivers

- A `RunAgent` flow with a shared lock key must not block/skip/kill itself.
- Independent top-level invocations of the flow must still contend per
  `onCollision`.
- Parallel-run limits (`maxParallelRuns`) must still apply to every run.
- Must not let an external caller forge its way past a lock.
- Surgical: a config field plus reuse of the existing chain-propagation
  mechanism, not a new lock manager or a distributed primitive.

## Considered Options

- **Propagated ancestor lock keys + opt-in pass-through** (chosen) — each
  `RunAgent` hop forwards the lock keys held by ancestors; a descendant that
  opts in and whose own resolved key matches an ancestor's skips lock
  acquisition entirely.
- **Reentrant acquire with ref-counted release** — hold one lock across the
  whole flow, freed when the last run finishes. Rejected: fire-and-forget makes
  the ref count race (the parent can drop to zero before the async child
  registers), and the user did not need continuous holding — only no
  self-collision.
- **Ancestry-aware `LockManager`** — teach the lock manager about process
  ancestry and admit same-flow holders. Rejected: couples the lock manager to
  flow concepts and duplicates the chain mechanism that already exists.
- **Shared static key + `WAIT`, no code** — works for "serialize one at a time"
  but still self-waits at each hop and gives no pass-through. Rejected: does not
  meet the "single logical lock, no self-collision" intent.

## Decision Outcome

Chosen option: **opt-in flow-scoped pass-through driven by a propagated list of
ancestor lock keys.**

1. **New field `lockStrategy.acceptParentLock: Boolean`, default `false`.** When
   `true` and this run's resolved lock key is in the inherited ancestor-key
   list, `Process.execute` skips **both** `waitForLockOrThrow` and the matching
   `releaseLock`. The default preserves all existing behavior.
2. **Propagation mirrors the invocation chain.** `RunAgentExecutor` sends
   `X-Homer-Ancestor-Lock-Keys` = the inbound ancestor keys plus this run's own
   resolved `lockKey`, JSON-encoded (lock keys are arbitrary rendered strings
   and can contain commas, so the chain's comma-join is not reused). The keys
   ride on `WorkspaceConfig.ancestorLockKeys`, not the agent-visible env.
3. **Trusted only on loopback.** The inbound header is read **only** when the
   request carries the valid loopback token (same gate as the invocation
   chain). An external caller cannot forge ancestor keys to make an
   `acceptParentLock` agent skip its lock.
4. **Pass-through, not reentrant hold.** The descendant does not acquire or
   register as a holder; the decision is made against the *propagated key set*,
   not live lock state. This is stable regardless of whether the (fire-and-
   forget) ancestor still holds the lock — which is exactly the "just stop
   self-collision" semantics, and accepts that an independent flow may interleave
   once the ancestor has released.
5. **Parallel-run limits untouched.** `maxParallelRuns` / `onMaxParallelRuns`
   are a separate gate acquired before the lock in `Process.execute`; pass-
   through bypasses only the lock.

### Consequences

- Good: a `RunAgent` flow can share one lock key with no self-collision, opt-in
  per agent, with no new infrastructure.
- Good: independent flows still contend per `onCollision`; parallel-run limits
  still apply.
- Good: the forge-resistance of the invocation chain (loopback-gated) extends to
  the lock-key channel for free.
- Neutral: pass-through is not a continuously-held flow lock. Between an
  ancestor releasing and a descendant finishing, an independent flow may acquire
  the key. Continuous holding was explicitly out of scope.
- Bad: an agent author who sets a shared key *and* `acceptParentLock` without
  understanding fire-and-forget may expect stronger atomicity than pass-through
  provides; the field docs call this out.

## Amendment (2026-09-30): the parallel-run slot bounds, and why both references are captured

Decision point 5 above leaves `maxParallelRuns` untouched; this amendment
records the two constraints `DefaultParallelRunsManager` implements on top of
it, which until now lived only in `app/CLAUDE.md` and in that file's comments.

**`maxQueuedRuns` bounds the wait, not just the permits.** Under `WAIT`, an
optional `maxQueuedRuns` (default `null` = unbounded) caps how many runs may be
suspended waiting for a slot; a surplus run fails fast with
`MaxQueuedRunsExceededException` (429) rather than joining the queue. The bound
counts **currently-suspended waiters**, not permit holders: only a queued run
holds a parked, non-evictable process row, so the waiter count — not the holder
count — is the quantity that needs bounding (ADR-0047 rule 5).

**Both the semaphore and the waiter counter are captured, never re-looked-up.**
`releaseSlot` takes the exact `Semaphore` object `acquireSlot` returned, and the
waiter counter is captured the same way, because `AgentsService.reload()` can
drop the per-agent map entry between the two calls. A lookup at release time
would find a *new* semaphore (or a fresh counter) and release the wrong object,
leaking the slot the run actually holds — and `maxParallelRuns` slots leak
permanently, so a handful of concurrent reloads wedges the agent. Capturing the
reference makes acquire and release refer to the same object by construction,
whatever the map does in between.

## More Information

- [ADR-0008](0008-recursion-guard-via-invocation-chain-header.md) — invocation-chain
  header and the loopback-token trust model this reuses.
- [`app/AGENT.md`](../../app/AGENT.md) — `lockStrategy` field reference.
