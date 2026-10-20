---
status: accepted
date: 2026-09-16
deciders: Lukáš Vlk
---
# ADR-0065: Remote-runner env-composition contract

## Context and Problem Statement

The backend JVM process maintains a `parentEnvAllowlist` (default `["PATH", "HOME", "LANG", "TZ"]`) that layers host environment variables into every agent's composed env. On `local` runner executions this is correct — the agent runs on the same host as Homer. On `ssh` and `kubernetes` remote runners, those variables describe *Homer's* host, not the remote machine, causing hard failures (e.g. `mkdir /home/homer` on a macOS host where `/home` is an autofs mount). A new per-runner `env` field must be layered into the env-composition stack, and the scope of `parentEnvAllowlist` must be made explicit.

## Decision Drivers

- macOS `HOME=/home/homer` forwarded to a macOS `ssh` runner via `parentEnvAllowlist` caused `glab` and other tools to fail on every run targeting that runner.
- `globalEnv` is shared across all runners and cannot carry per-host values; per-agent `env` is the only existing override point, which forced operators to pin `HOME` in every agent definition.
- Remote-ness must be a property of the `Runner` SPI (ADR-0003), not a type check in the dispatcher, so future runner backends inherit the correct behavior automatically.

## Considered Options

- **Per-runner allowlist override** — let operators override `parentEnvAllowlist` per runner. Rejected: the allowlist exists for the local runner only; leaking it per-remote-runner is an additive config burden with no safe default.
- **Document and keep forwarding** — accept forwarding and document workarounds. Rejected: the failure is silent and operator-invisible; every new macOS runner deployment would hit it.
- **Scope `parentEnvAllowlist` to the local runner; add per-runner `env`** — chosen.

## Decision Outcome

Chosen option: **"Scope `parentEnvAllowlist` to the local runner; add per-runner `env`"**.

`parentEnvAllowlist` keys from `System.getenv()` are layered into the composed env only when `runner.isRemote == false`. All remote runners (`ssh`, `kubernetes`) expose `isRemote = true` on the `Runner` SPI. A new optional `env: Map<String, String>` field on `RunnerConfig.Ssh` and `RunnerConfig.Kubernetes` is layered between `globalEnv` and `agent.env` in `WorkspaceConfigFactory.createWorkspaceConfig`, giving operators a per-host override point. Values may use the `@SYSENV:VAR` placeholder; resolved names are added to the redacted set (same semantics as `globalEnv`).

### How remote runners compose the environment

```
globalEnv → runner env → agent env → query params → HMR_* vars
```

`parentEnvAllowlist` host-env keys are absent from remote-runner envs unless the operator re-introduces them explicitly via `globalEnv` or the runner's `env`.

The same rule reaches the cross-runner path: a `runnerCmd` step targets a runner other than the agent's own, and inherits the requesting run's composed env — which, on a local agent, carries the host layer the local runner received. The executor therefore strips, for a remote target, exactly the allowlist keys that still carry the host value they were layered with (`WorkspaceConfig.parentEnvLayerNames`); a key the operator re-set via `globalEnv`, runner `env`, or agent `env` holds the operator's own value and survives. A local target keeps them — it runs on Homer's own host, where the values are correct.

### Consequences

- Good: remote runners no longer receive host values that describe the wrong machine.
- Good: operators have a clean, per-host override point (`runner.env`) with `@SYSENV:` redaction support.
- Good: future runner backends cannot accidentally inherit `parentEnvAllowlist` forwarding — `isRemote = true` is the safe default for non-local runners.
- Bad: **breaking change** — any remote-runner agent that silently depended on an allowlisted host variable (e.g. a `PATH` or `LANG` from the backend) changes behavior after upgrade without any config edit.

## More Information

- ADR-0003 — Runner SPI: `isRemote` and `runnerEnv` are added to the `Runner` interface.
- ADR-0032 — SSH runner env model: the per-runner `env` slots into the layering order ADR-0032 describes.
- ADR-0047 §12 rule 7: per-runner `env` carries the same `ps`-visibility risk as `agent.env`; SERVER.md repeats the multi-tenant caution.
