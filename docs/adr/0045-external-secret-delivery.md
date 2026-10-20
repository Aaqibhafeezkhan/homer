---
status: accepted
date: 2026-08-20
deciders: Lukáš Vlk
---
# ADR-0045: Secrets reach agents as files rendered by cluster tooling — Homer never resolves them

## Context and Problem Statement

Every secret an agent uses today arrives the same way: as an entry in the
merged command env. Operators declare it in `globalEnv`, agent `env`, a
`secret` input, or via `@SYSENV:VAR` (resolved fail-fast at startup by
`SysEnvResolver`), and Homer hands the merged map to the runner. The
common property is that **Homer holds the plaintext value** — it is in
the server process, and it travels through whatever channel the runner
uses.

For the local and ssh runners that is bounded by host access. For the
kubernetes runner it was worse: pod specs used to carry agent env inline,
so anyone with `pods get` in the namespace could read it.
[ADR-0043](0043-k8s-sync-workspaces.md) closed that — the main container
starts as `sleep infinity` with no env, and each command's env travels
per-exec as `env KEY=VAL … argv` over the exec websocket. Two surfaces
remain, both documented in [`app/SERVER.md`](../../app/SERVER.md): the
exec request URI is recorded by any cluster auditing `pods/exec` at the
`Metadata` level, and `pods/exec` RBAC is the boundary that matters.

That leaves a class of secret for which *where it is stored* is not the
question — **Homer holding it at all** is the objection. Store-managed
credentials (short-lived database creds, per-run cloud tokens) exist so
that no long-lived value sits in an orchestrator's config. Supporting
them needs a decision, because the obvious-looking implementation is the
wrong one: teaching Homer to read from HashiCorp Vault and inject the
results as env would keep Homer as the value-holder, add store
credentials to its trust surface, and push *more* secrets through the
exec URI that ADR-0043 just narrowed.

## Decision Drivers

- Homer must not become a secret broker: no store credentials in its
  config, no store values in its process, no new disclosure surface.
- ADR-0043's gain must not be undone. The per-exec env is the residual
  audit surface; routing new secrets through it is a regression.
- Vendor neutrality. Vault is the driver today; Secrets Store CSI,
  External Secrets, and cloud secret managers are the same shape.
- Blast radius should be scoped per runner, not per Homer instance.
- Rotation, leases, and TTL are the store's domain. Homer is a
  single-node orchestrator and should not grow a credential cache and
  renewal loop.
- Minimum Homer code, runner-shaped rather than command-shaped.

## Considered Options

- **A — Homer-side resolver.** A `@VAULT:path#key` prefix symmetric with
  `@SYSENV:`, resolved by Homer and merged into the command env.
- **B — Pod-spec hook.** Homer emits operator-declared pod annotations;
  cluster tooling renders secrets into the pod at runtime; agents read
  the rendered file. Homer never sees a value.
- **C — Per-run Kubernetes `Secret`.** Homer writes a `Secret` object per
  run and references it from the pod via `secretKeyRef`.
- **D — Status quo.** `@SYSENV` only; no store integration.

## Decision Outcome

Chosen option: **"B — Pod-spec hook"**, because it is the only option
that satisfies the first driver at all. Homer emits annotations and
knows nothing about the store: the injector's init container and sidecar
authenticate as the pod's ServiceAccount, render the secret into a
tmpfs mount, and the agent's command reads it there. No store credential
enters Homer's config, and no store value enters its process, logs,
spans, or exec requests.

The hook is a generic `annotations: Map<String, String>` on the
kubernetes runner config, passed through verbatim, with `volumes`,
`serviceAccountName`, and `imagePullSecrets` already in place beside it.
Homer sets no annotations of its own and encodes no vendor's keys.

Two rules make the model hold, and both are constraints on future work:

1. **Homer never resolves a secret value.** Any future feature that
   reads from a store and produces an env entry re-creates the exposure
   this ADR exists to avoid. If a store integration is ever added, it
   must shape the pod, not the env.
2. **Prefer file delivery over a CLI read.** An agent that shells out to
   `vault read` puts the value on that command's stdout; hoisting it into
   a later command with `resultEnvParam` then routes it straight back
   into the per-exec env. Read the rendered file in the command that
   consumes it.

### Consequences

- Good: compromise of Homer yields no store access. The store's policy,
  bound to the runner's ServiceAccount, decides what a run can read — so
  the blast radius is per runner alias.
- Good: ordering is already guaranteed for init-rendered secrets.
  `KubernetesRunner.isReady()` requires every init container to have
  terminated with exit 0, so an injector's pre-populate init container
  must finish rendering before Homer runs the first command. This needed
  no change; keep the injector's pre-populate behavior enabled.
- Good: a long-running injected sidecar cannot deadlock a run —
  `isReady()` matches the main container by name rather than requiring
  every container ready. The flip side is that secrets which arrive
  *only* from the sidecar (continuously refreshed dynamic creds) have no
  ordering guarantee beyond that first init render.
- Good: rotation and lease renewal belong to the sidecar. Homer has no
  cache to invalidate and no expiry to track.
- Good: the same field serves service meshes, scrape config, and cost
  allocation. One generic knob, no vendor coupling.
- Good: an injector failure (bad role, policy denial) leaves the pod
  un-ready, so the run fails on the `podStartTimeoutSeconds` path with
  `keepPodOnFailure` and `PodDiagnostics` events available for triage.
- Bad: kubernetes-only. Local and ssh runners keep `@SYSENV`; using a
  store there means provisioning the host (a vault-agent rendering an
  env file or systemd credential) with no support from Homer. Homer's
  own config secrets — `apiAuth` tokens, Langfuse keys — are the same
  deployment-side story.
- Bad: agents must read a path instead of an env var, which is a porting
  cost for existing agents and couples the agent to a mount path chosen
  in runner config.
- Bad: rule 2 is unenforced. Nothing stops an agent from `cat`-ing the
  rendered file into `resultEnvParam` and undoing the benefit; today
  that is documentation, not a backstop.
- Bad: Homer's `securityContext` config does not cover webhook-injected
  containers, so a namespace under Pod Security Admission `restricted`
  needs the injector configured to comply independently.

## Pros and Cons of the Options

### Option A — Homer-side resolver (`@VAULT:`)

- Good: uniform across every runner, including local and ssh.
- Good: agents keep reading env vars — no agent changes at all.
- Neutral: mirrors an existing, well-understood pattern (`@SYSENV` and
  `SysEnvResolver`).
- Bad: Homer needs a store credential in its own config — itself a
  long-lived secret requiring the same protection, and a single
  compromise yields everything its policy can read rather than what one
  run needs.
- Bad: every resolved value lands in the merged command env, hence in
  the exec request URI for the kubernetes runner. It widens the exact
  surface ADR-0043 narrowed, and pulls in secrets that previously never
  left the store.
- Bad: startup-time resolution cannot express leases or TTL; dynamic
  credentials would require a cache, renewal loop, and invalidation
  inside Homer.
- Bad: the syntax encodes one vendor; a second store means a second
  prefix.

### Option B — Pod-spec hook (chosen)

- Good: Homer holds no credential and sees no value; per-runner blast
  radius; vendor-neutral; rotation is the store's problem.
- Neutral: requires the operator to run an injector or CSI driver in the
  cluster — true of every store-managed-secret deployment anyway.
- Bad: kubernetes-only, agents read files, and the discipline rests on
  documentation (see Consequences).

### Option C — Per-run Kubernetes `Secret`

- Good: keeps values out of the exec request URI.
- Bad: Homer must hold the values in order to write the object, so the
  broker objection stands unaddressed.
- Bad: exposure only narrows from `pods get` to `secrets get` in the same
  namespace.
- Bad: adds per-run object lifecycle — creation, deletion, and a leak
  path on crash needing its own startup sweep.
- Bad: fully superseded by option B for anyone who has a store; shipping
  both is dead weight.

### Option D — Status quo

- Good: nothing to build.
- Bad: no answer for store-managed credentials, and it blocks the
  deployments that require them.

## More Information

- Implemented by commit `2343429` (the `annotations` field). The
  operator-facing pattern, with a worked Vault example, is in
  [`app/SERVER.md`](../../app/SERVER.md) § *Injecting secrets from an
  external store*.
- [ADR-0043](0043-k8s-sync-workspaces.md) — removed pod-spec env and
  documented the residual exec-audit surface this ADR builds on.
- [ADR-0003](0003-runners-as-execution-backends.md) and
  [ADR-0033](0033-runner-lifecycle-primitives.md) — why secret delivery
  is shaped per runner rather than per command.
- [ADR-0037](0037-grants-authorization.md) — the authorization model
  agents run under; grants gate *who may start* a run, while this ADR
  governs what that run's environment may reach.
