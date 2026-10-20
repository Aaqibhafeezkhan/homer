---
status: accepted
date: 2026-07-21
deciders: Lukáš Vlk
---
# ADR-0032: SSH as a runner backend

## Context and Problem Statement

Homer already runs an entire agent on an alternate backend via the
`Runner` abstraction (ADR-0003): the built-in `local` runner and the
`kubernetes` runner, selected per-agent through `agent.runner`. Some
operators want to run agents on a specific remote host they already
manage — a build box, a licensed toolchain machine, a GPU box — without
standing up a Kubernetes cluster just for that one host. We needed a
third backend on the same axis: run the whole agent over SSH.

Unlike Kubernetes, which gets a shared filesystem for free (a PVC
mounted at the same absolute path as the server), a remote SSH host has
no shared filesystem. The runner must sync the workspace up before the
run, translate absolute local paths for the remote side, and sync
results back after.

## Decision Drivers

- Reuse the existing, compiler-enforced `Runner`/`RunnerSession`/
  `ShellExecutor` SPI — no changes to `Process`, `AgentExecutor`, or
  any command executor.
- No shared filesystem with the remote host, unlike the PVC-backed
  kubernetes runner — workspace state must cross the machine boundary
  explicitly.
- Secure-by-default connection handling: no silent trust-on-first-use
  of an unknown host key.
- The remote host is operator-managed and may be a bare machine with no
  special provisioning beyond what the agent's commands require.

## Considered Options

- **SSH as a new `Command` type** (`type: ssh` alongside `cmd`/`script`),
  leaving other commands on the host.
- **SSH as a new `Runner` backend**, a sibling of `kubernetes`, running
  the whole agent remotely.

## Decision Outcome

Chosen option: **"SSH as a new `Runner` backend"**, for the same reason
ADR-0003 chose the runner abstraction over a per-command Kubernetes
type: agents chain commands that share filesystem state, and a
per-command SSH type would force every command to re-establish that
state (or leave some commands stuck on the host mid-agent). A runner
backend keeps every existing executor unchanged — `session.shell` is
the only seam — and slots SSH in beside `local` and `kubernetes` behind
the same interface.

### Sync and translation model

Since SSH gives no shared filesystem, the runner does explicitly what
Kubernetes gets from the PVC:

- **Transport is SFTP** over the same authenticated SSH connection
  (Apache MINA SSHD) — one connection handles auth, exec, and file
  transfer, so no extra binary is needed in Homer's own image and no
  delta-sync dependency (rsync) is pulled in for v1.
- **Transport timing: idle timeout disabled by default** (changed from
  MINA's stock 10-minute idle timeout). A remote command that produces
  no output for a long while — a compile, `git clone`, a long tool
  call — is a normal state, not an idle session; the command's own
  `timeout` is the intended bound. This decision was left implicit
  here at first, so the stock MINA posture applied and disconnected
  every silent command after ten minutes.
  `idleTimeoutSeconds` / `heartbeatIntervalSeconds`
  (SERVER.md, "ssh runner") make both tunable: idle timeout is
  `null` = disabled by default; 30 s keepalives are on by default,
  keeping NAT/proxy idle timers on the path alive and letting the
  client detect a dead link while the idle timeout is off.
  The alternative — keeping MINA's 600 s idle timeout and documenting
  it — was rejected because the cap it imposes on silent commands is
  the exact defect it causes; any chosen value either kills legitimate
  runs or must be raised until it is meaningless.
- **SFTP per-operation deadline via a watchdog coroutine.** Keepalives
  only disconnect a peer that stops answering; a peer that answers
  keepalives while withholding SFTP data still stalls the sync
  indefinitely. Every SFTP round trip (subsystem handshake, stat,
  readDir, mkdir, and file reads/writes) is bounded by a configurable
  per-operation deadline (`sftpTransferTimeoutSeconds`, default 300 s).
  The deadline is enforced by a watchdog coroutine that closes the
  whole session — not just the SFTP channel — to unblock the blocked
  JVM thread (`withTimeout` cannot interrupt blocked I/O). This is safe
  because syncs and exec commands never share a session concurrently;
  the mechanism and its invariants are recorded in
  [ADR-0064](0064-sftp-watchdog-timeout.md).
- **Remote path model is a configurable root + env-only translation.**
  The operator sets `remoteWorkspaceRoot`; the runner computes the
  remote project root from it plus the run's relative workspace path,
  and returns only the *changed* env keys (`HMR_WORK_DIR`,
  `HMR_ARTIFACTS_DIR`, `HMR_MCP_CONFIG`, `PATH`'s `.homer/bin` segment,
  `HMR_AGENT_DIR`, `HMR_API_URL`) with the local prefix swapped for the
  remote one. No file contents are rewritten — the question-tooling
  scripts and `mcp.json` embed no absolute local paths, so translation
  is purely an env concern.
- **Sync-down is artifacts-always, workdir-if-kept.** `artifacts/` is
  the only thing Homer serves post-run, so it is always pulled back
  (additive overlay — it never clobbers the `cmd_<i>.out`/`.err` files
  the shell pump already streamed locally). `workdir/` is pulled back
  only when `cleanAfterFinish: false`, matching why an operator would
  keep a workspace at all: human inspection. Sync-down is best-effort —
  a failure logs loudly but never flips an otherwise-successful run to
  failed, matching how cost-tracking failures are treated elsewhere.
- **`HMR_AGENT_DIR` is synced upload-only**, copied once to
  `<remote-project-root>/.homer/agent` so the agent's own definition
  directory is valid on the remote side too, at negligible cost (it
  reuses the same directory-sync helper).

### Shell-mediated exec and argv escaping

An SSH "exec" request always runs through the remote login shell,
unlike a Kubernetes exec which runs argv directly against the
container's entrypoint. To preserve Homer's argv semantics (each
`Cmd`/`Script` token as its own literal argument, not shell-interpreted
text), every command is sent as a single escaped string:

```
cd <remoteWorkdir> && exec env <K=V…> <argv…>
```

with every token POSIX single-quoted. `exec` replaces the shell so
signals and exit codes come straight from the command; the `env K=V…`
prefix sets the environment because many sshd configurations reject
`SendEnv`/`AcceptEnv`. This is the one real behavioral divergence from
the kubernetes runner and the highest-risk piece of the implementation
— it is covered by dedicated escaping unit tests (`RemoteCommandBuilder`).

### Security posture

- **Strict host-key checking is the default** and requires
  `knownHostsPath` — a runner configured with
  `strictHostKeyChecking: true` (the default) and no `knownHostsPath`
  fails fast rather than silently accepting an unverified key.
  Accept-any-key (`strictHostKeyChecking: false`) is an explicit,
  documented opt-in.
- **Env values are visible in the remote process list.** Because the
  environment is set via an `env K=V…` prefix rather than a private
  channel, any secret passed through `env` is visible to other users on
  the remote host via `ps` for the life of the command — the same
  property the kubernetes runner's exec-based env injection has.
  Documented in `SERVER.md` as a caution against secrets in `env` on
  multi-tenant remote hosts.
- **The remote host's toolchain is the operator's responsibility.**
  Homer does not provision the remote machine; it must already have
  whatever binaries the agent's commands need (e.g. the `claude` CLI),
  the same responsibility already documented for the kubernetes
  runner's image.

### One deviation from the design spec: a client per alias, not shared

The design spec described a single MINA `SshClient`, started once and
shared across every `ssh` runner alias. Implementation found that
`SshClient.serverKeyVerifier` is set per-connection at connect time
(`defaultConnect`), keyed off each alias's own
`strictHostKeyChecking`/`knownHostsPath`. A shared client would race:
two aliases with different host-key policies connecting concurrently
could apply the wrong verifier to the wrong connection. `AppModule`
therefore builds one `SshClient.setUpDefaultClient()` per configured
`ssh` alias instead.

### Consequences

- Good: every command executor (`Cmd`/`Script`/`Claude`/`RunAgent`)
  works against the SSH runner unchanged — `session.shell` is the only
  seam, exactly as for the kubernetes runner.
- Good: no shared-filesystem assumption — the runner works against any
  reachable SSH host the operator already controls, no cluster
  required.
- Good: secure-by-default host-key handling; unknown-key acceptance is
  opt-in and logged.
- Bad: v1 does full-tree sync on every run (no rsync/delta transport),
  so large workspaces pay a full-transfer cost each time.
- Bad: no connection pooling — one SSH connection per agent run, one
  exec channel per command; a chatty agent with many short commands
  pays repeated exec-channel setup cost.
- Bad: `auth: !<agent>` (ssh-agent forwarding) is implemented but
  **unverified** — the current code registers no explicit identity and
  relies on MINA SSHD's automatic agent-socket discovery, which has not
  been confirmed against a real running `ssh-agent`. Documented in
  `SERVER.md` as experimental; `key` auth is the supported path.
  Follow-up: verify or replace this path against a real agent socket.
- Bad: `defaultConnect` (connect + host-key verify + auth) is exercised
  in this codebase only against MINA's own embedded-server integration
  tests, not yet against a real independent `sshd`. A wrong assumption
  about MINA SSHD 2.18.0's client API would surface as a connect/auth
  failure in production before it surfaces in CI. Follow-up: an
  end-to-end smoke test against a real `sshd` (e.g. in a container)
  would close this gap.

## Pros and Cons of the Options

### Option A — SSH as a new `Command` type

- Good: smallest surface area — one new `Command` subclass, no runner
  plumbing.
- Bad: every other command type stays on the host; operators can't run
  an entire *agent* remotely, only individual commands, and chained
  commands lose shared remote state between them (no persistent remote
  workdir across commands without inventing one).
- Bad: duplicates most of what the runner abstraction already solved
  for Kubernetes.

### Option B — SSH as a new `Runner` backend

- Good: every existing executor is backend-agnostic, matching the
  precedent set by the kubernetes runner.
- Good: the workspace-sync problem (unique to SSH, since there's no
  shared PVC) is solved once, in the runner, not per command.
- Neutral: adds a runner module (`runners/ssh/`) and config variant
  (`RunnerConfig.Ssh`), mirroring `runners/kubernetes/`.
- Bad: more up-front structure (sync-up, path translation, sync-down)
  than a single command type would need.

## More Information

- Related: [ADR-0003](0003-runners-as-execution-backends.md) — the
  runner abstraction this backend implements against.
- Config reference: [`app/SERVER.md`](../../app/SERVER.md#ssh-runner).
