---
status: accepted
date: 2026-10-03
deciders: Lukáš Vlk
consulted: ADR-0037, ADR-0047
---

# ADR-0077: The runner alias is admin-only on the status API

## Context and Problem Statement

A run records which backend served it (`RunnerStateResponse.runner`). The
runner's **alias** is an operator-chosen name (`local`, `gpu-box`,
`k8s-prod-eu`, an `external` domain) that can double as infrastructure
topology: an ssh host, a Kubernetes pool, a tenant or environment marker
(CWE-200). The status APIs are readable by any principal who can see the
run, so emitting the raw alias to every such caller disclosed
operator-chosen naming to read-granted non-admins. The generic backend
**kind** (`local` / `ssh` / `kubernetes` / `external` / `unknown`) carries
no operator naming.

## Decision Drivers

- §12: principals are a trust boundary; an information disclosure across
  one is a defect whatever its severity.
- A read-granted non-admin legitimately needs to know *what kind* of
  backend served a run (the console's Runner card renders it), but has no
  need to know *which* configured runner it was.
- ADR-0047 rule 3: fail closed; ADR-0047 rule 8: prove the denial path
  with a test.

## Considered Options

- Gate the whole status response on admin — overkill: non-admins need the
  run fields.
- Withhold the alias in the serializer unconditionally — loses the admin
  view, which has a legitimate use for it.
- Pseudonymize or hash the alias — stable pseudonyms still leak equality
  and correlation, and add a mapping surface for no reader need.
- Gate by grant instead of role — a READ grant on an agent is exactly
  what makes the run visible; granting the alias with it re-opens the
  disclosure.

## Decision Outcome

Chosen option: **"the raw alias is emitted only to an admin (or when
authentication is disabled); every other viewer gets the generic backend
`type` instead"** — an `aliasVisible` flag resolved once per request in
the route handler (`authorizer.isAdmin(principal)`), passed down to the
serializer, failing closed (`aliasVisible = false` by default, so a
forgotten call site withholds the alias rather than leaking it).

The same classification governs future fields on `RunnerStateResponse`:

- **alias** — operator-named backend metadata → admin-only.
- **type** — generic backend kind → emitted to every viewer.
- **pod fields** (`podName`, `podPhase`, timestamps, `startupFailure`) —
  carrier metadata, no operator naming → emitted to every viewer.

`GET /api/v1/health` applies the same gate with the same per-request
admin check: a non-admin gets `alias: null` (with `type`) for every
configured runner, and `runnerAlias: null` for each pod in the sweep
snapshot. The one exception is the built-in `local` row, which always
carries the alias `local`: Homer assigns that name, not the operator, so it
names no host, tenant or environment.

`GET /api/v1/runners` stays admin-only (it exists for operators and lists
every configured alias), so `kind` there is the admin-side twin of the
status response's `type`.

### Consequences

- Good: a non-admin viewer of a run can no longer enumerate runner
  aliases (ssh hosts, tenant names, pools).
- Good: the console Runner card degrades to the backend kind with a
  matching label instead of an empty card.
- Bad: a non-admin operator debugging a misrouted run cannot tell which
  of several same-kind runners served it; they ask an admin.
- Bad: a non-admin inspecting a sweep can no longer see which runner
  owned a deleted pod; they ask an admin.

## Pros and Cons of the Options

### Pseudonymize or hash the alias

- Good: keeps a per-run stable label non-admins can group by.
- Bad: equality still correlates runs across views; a low-entropy name
  space (few runners) makes the raw value guessable.
- Bad: a mapping layer for a reader need that `type` already satisfies.

### Gate by grant instead of role

- Good: consistent with the process-visibility union rule (ADR-0037).
- Bad: a READ grant on an agent is what makes the run visible in the
  first place, so the alias would ship to exactly the non-admins the
  finding is about.

## More Information

Implemented in the same change that added `runner.type` to the status
API. See ADR-0037 (grants model), ADR-0047 (security
by design and threat model).
