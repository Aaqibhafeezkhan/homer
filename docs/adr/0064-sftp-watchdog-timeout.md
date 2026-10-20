---
status: accepted
date: 2026-09-16
deciders: Lukáš Vlk
---
# ADR-0064: SFTP blocking-I/O timeout via a watchdog coroutine

## Context and Problem Statement

The SSH runner syncs workspaces over SFTP. A stalling peer — one that
answers SSH keepalives but withholds SFTP data — would hold a
`Dispatchers.IO` worker, the workspace lock, and a run slot indefinitely
(CWE-400). MINA's keepalive mechanism only disconnects a peer that stops
answering entirely; it provides no bound on SFTP data flow.

## Decision Drivers

- `kotlinx.coroutines.withTimeout` does not interrupt a JVM thread
  blocked on I/O: the cancellation only fires when the blocked thread
  next suspends, which a blocked SFTP read never does.
- The Apache MINA SSHD client exposes no per-channel or per-operation
  I/O deadline (as of MINA SSHD 2.18.0 used in this project).
- Every SFTP round trip that could stall must be bounded: file reads and
  writes, directory listings, stat calls, mkdir calls, the SFTP
  subsystem handshake itself, and the client close.

## Considered Options

- **`withTimeout` around each blocking call** — cancelled by the
  standard kotlinx-coroutines mechanism.
- **MINA per-channel timeout** — a timeout at the SFTP channel level
  inside the MINA SSHD client library.
- **Watchdog coroutine that closes the session on deadline** — a
  background coroutine on `Dispatchers.Default` that closes the whole
  `ClientSession` after the deadline elapses, unblocking whatever SFTP
  call is in progress.

## Decision Outcome

Chosen option: **"Watchdog coroutine that closes the session"**, because
`withTimeout` cannot interrupt a blocked JVM read (the cancellation fires
only when the coroutine next yields), and MINA exposes no SFTP-level
timeout API in its 2.18.0 client surface.

The watchdog runs on `Dispatchers.Default` (not the caller's
`Dispatchers.IO` context), so it fires even when the caller's event loop
is saturated with blocked I/O threads. On expiry it calls
`session.close(true)`, which unblocks every blocked SFTP operation on
that session by tearing down the underlying socket.

The deadline is applied per SFTP round trip (per `withTransferTimeout`
call), not per whole-transfer: a legitimate large transfer that spans
many round trips gets the configured deadline per call, not for the
cumulative transfer time.

**Exception — `removeRecursively`:** the entire `removeRecursivelyIn`
recursion runs inside a single `withTransferTimeout` call, so all of
its `readDir`, `remove`, and `rmdir` calls share one deadline window.
A deep directory tree therefore shrinks the effective stall window for
later round trips, rather than each getting a fresh deadline.
This is an intentional trade-off: `removeRecursivelyIn` is a blocking
(non-suspend) function; wrapping each individual call would require
restructuring it as a suspend function, adding complexity. The
per-recursion bound still prevents an indefinite stall; the
configuration note is that operators expecting the per-round-trip
guarantee on removal of deep trees should account for this when
choosing `sftpTransferTimeoutSeconds`.

### No-overlap invariant

Closing the session unblocks the SFTP call but also terminates any other
channel on that session (exec channels). The watchdog is only safe
because the SSH runner opens a new session per run and uses it
**exclusively for SFTP during sync**: syncs run before commands start and
after commands finish, so no exec channel is ever alive on the session at
the same time as an SFTP transfer. A future change that pipelines syncs
and commands on one session (e.g. concurrent artifact streaming while a
command runs) would break this invariant and require a different
mechanism — a per-channel close rather than a whole-session close.

This invariant is **not enforced mechanically** — it lives in the runner
lifecycle (`SshRunner.openSession`) and must be preserved by anyone who
touches the SSH session lifecycle.

### Rejected alternative: `withTimeout`

`withTimeout` wraps the coroutine's continuation in a cancellation job.
Cancellation fires when the coroutine yields to the scheduler next. A
thread blocked inside `sftp.read()` / `sftp.readDir()` / etc. never
yields — it never returns to the coroutine dispatcher at all — so
`withTimeout` would never fire. This is a documented limitation of
structured concurrency with JVM blocking I/O.

### Rejected alternative: MINA per-channel timeout

MINA SSHD's `SftpClient` does not expose a per-read or per-operation
timeout in its public API (2.18.0). Channel-level timeouts exist in some
configurations but apply to the entire channel lifetime, not to
individual SFTP requests. The watchdog pattern is self-contained and does
not depend on any MINA internal.

### Consequences

- Good: every SFTP round trip is bounded, including the SFTP subsystem
  handshake, stat, readDir, mkdir, file reads/writes, and the client
  close (`withSftp` releases the client under the same deadline; a
  failed or timed-out release is swallowed and WARN-logged so it cannot
  mask the operation's own outcome, and the session is shed either way
  — closed by the watchdog on a deadline expiry, closed by the release
  path itself on a close that fails outright — so a later `withSftp`
  never reuses a session whose state is unknown). Amended after a security review finding.
- Good: the mechanism is self-contained in `WorkspaceSync` — no MINA
  internals needed.
- Good: the watchdog runs on its own dispatcher so a saturated
  `Dispatchers.IO` pool cannot delay it.
- Bad: the whole session is closed on deadline, not just the stalled
  channel. This is safe only while the no-overlap invariant holds.
- Bad: the invariant is not enforced mechanically — it relies on runner
  lifecycle discipline.

### Known gap: KubernetesWorkspaceSync tar stream

`KubernetesWorkspaceSync` transfers workspaces via tar-over-exec rather
than SFTP. Its `probe` and `runToExit` helpers are bounded by a 60-second
exit-status timeout (`EXIT_STATUS_TIMEOUT_SECONDS`), but the actual tar
stream (the `TarArchiveOutputStream` write loop inside `upload` and the
`TarArchiveInputStream` read loop inside `download`) has no per-operation
deadline: a stalling pod that holds the exec channel open without sending
tar data would block those loops indefinitely. Fixing this requires a
different mechanism — a thread that calls `proc.destroyForcibly()` after a
deadline, analogous to how this watchdog calls `session.close(true)` — and
is tracked as a separate follow-up.

## More Information

- Config reference: `sftpTransferTimeoutSeconds` in
  [`app/SERVER.md`](../../app/SERVER.md#ssh-runner); `null` disables
  the bound.
- Implementation: `WorkspaceSync.withTransferTimeout`
  (`app/app/src/main/kotlin/homer/app/runners/ssh/WorkspaceSync.kt`).
- Related: [ADR-0032](0032-ssh-runner-backend.md) — SSH runner backend,
  Transport timing section.
- Related: [ADR-0047](0047-security-by-design-and-threat-model.md) —
  security design rules (§12.4: bound every resource a caller can
  multiply; §12.8: prove each constraint with a denial-path test).
