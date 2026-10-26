# runners/CLAUDE.md

Dev guide for the runner subsystem (`homer.app.runners`). Project-wide rules
live in the root [`CLAUDE.md`](../../../../../../../../CLAUDE.md) and apply here too; the
cross-cutting backend guide is [`app/CLAUDE.md`](../../../../../../../CLAUDE.md). This file is
canonical for the runner contract, the claim queue and the external
pull-runner model (root §9). It is backend-agnostic: it describes the
primitives every runner implements, with ssh / kubernetes / external as
examples under one contract.

## Runner contract (lifecycle primitives)

`Runner.openSession` provisions only (connect/auth for ssh, pod
create for kubernetes, nothing for local). `RunnerSession` exposes
mechanism primitives with no-op defaults — `createWorkspace()`
(returns env overrides, e.g. `HMR_*` paths translated to the remote
layout), `upload(local, remoteRelative, excludes = [])`,
`download(remoteRelative, local, mode)`, `cleanWorkspace()`, `shell`,
`close()`. An `upload` override must honour `excludes` — the agent-dir
upload passes the nested-agent excludes (ADR-0068); the local runner's
no-op default ignores them because it copies nothing. `download`'s
`mode` is a `DownloadMode` (`OVERLAY`/`OVERWRITE`/`MIRROR`) — `MIRROR`
extracts then prunes local extras, but only after a fully confirmed
successful download (ADR-0055). A tar-backed `download` applies each
entry's executable bit as `0755`, everything else `0644` (git-style,
never the raw bits — a tar is attacker-controlled); an `OVERLAY`-skipped
existing file keeps its own mode, and an entry whose mode cannot be
normalized is refused (both the link and, for a write-through dangling
link, the destination the write landed on are deleted, the extract
failed) rather than left with its pre-existing mode — a non-POSIX
target, which has no mode to normalize, still extracts bytes-only. Only the
local runner keeps all of those no-ops; ssh implements the transfer primitives
(`upload`/`download`) over SFTP (ADR-0032) and `cleanWorkspace` as one
`rm -rf` over the exec channel with the SFTP walk as fallback
(ADR-0067), kubernetes the transfer primitives over tar-over-exec into a
pod-local emptyDir and keeps the `cleanWorkspace` no-op (the emptyDir dies
with the pod) (ADR-0043). Full-run sync
*policy* (upload workspace + agent dir, artifacts-overlay /
workdir-mirror-if-kept download, `clearAfterFinish` sweep, best-effort
semantics) lives in `AgentExecutor`, not in runners. Runner-level
`extraUploads` (ADR-0042, adopted unchanged by the kubernetes runner
per ADR-0043 and by `!<external>` domains, which have no `syncExcludes`)
ride the same split: the session exposes the
specs and repoints each entry's `env` in `createWorkspace()`;
`AgentExecutor` uploads them with the workspace + agent dir,
`RunnerCmdExecutor` right after `createWorkspace()` and before checks.
The agent-dir upload itself passes root-anchored excludes for nested agent
directories, computed by an exact, uncapped walk of the parent at upload time
(`AgentDirs.nestedAgentDirsUnder` — never the capped discovery walk or the
catalog; symlinked directories are excluded outright) — a parent's upload
carries none of its children (ADR-0068, an
ADR-0047 boundary enforcement; an `upload` override must honour them).
`session.metadata`
(e.g. `pod.name`) is stamped onto command-execution spans. See
ADR-0033.

Every run reaches a runner through the claim queue (ADR-0066):
`RunnerRegistry.forAgent(agent)` returns a `QueuedRunner` whose
`openSession` enqueues a `ClaimRequest` (filter = alias or tag set) into
`RunnerClaimQueue` and awaits it under the claim TTL. The one exception:
with `server.localRunner.enabled: false` no local consumer exists and
`forAlias("local")` throws a `ConfigurationException` before anything is
queued, rather than letting the claim wait out its TTL (ADR-0095). One
`StaticRunnerConsumer` per configured runner (started once from
`Application` on the application scope, and stopped via
`RunnerRegistry.stop()` right after the handler flips `isDraining`,
before the drain) takes the oldest matching request
when it holds a free permit (`concurrency`; null = unbounded), opens the
real session in a child coroutine, and hands it back wrapped in
`PermitReleasingSession` so the permit returns on `close()`. Never call a
backend's `openSession` directly from a run path; `RunnerRegistry.get`
is for consumers and diagnostics only. Tests that construct a
`RunnerRegistry` and resolve runners must call `start(backgroundScope)`
(or a real scope under `runBlocking`), otherwise `openSession` waits for
the full claim TTL.

An `!<external>` domain (`ExternalRunnerDomain`) is a `Runner` only for the
facets `QueuedRunner` reads; its sessions are created by the claim route
(`RunnerProtocolRoutes`) when an instance's long-poll takes a request. An
`ExternalRunnerSession` turns each primitive into one `RunnerOp` in its
`OpMailbox`; the instance drains ops through `next` and answers with
`OpResult`s, tars ride the `tar` routes through `TarStreams`, and command
output arrives as ordered chunks through `OutputSink` into the usual
`cmd_N.out`/`cmd_N.err` files, so nothing above the session changes. A `cmd`
result also reports one past the last `seq` each stream posted or gave up posting (a
chunk abandoned after its retries is counted, so a lost one reads as a short count,
never as a complete one), and the instance finishes posting before it reports its
exit — or gives up on a stalled drain once the bounded wait expires, logging one
warning naming the session, op, stream and count, and such a chunk reaches no count
at all. A count ahead of what
`OutputSink` received is waited on once, briefly, and then fails the step as
*output incomplete* rather than passing truncated output off as a clean exit; a
count the drain gave up on matches what `OutputSink` received, so the instance's
warning is the only trace of the missing tail.
The routes are mounted outside `authenticate(...)` next to `callbackRoutes`: a
domain token is a separate principal class (§12). The registry runs a
heartbeat sweeper that drops silent instances and fails their runs.
