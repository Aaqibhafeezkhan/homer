---
status: accepted
date: 2026-04-30
deciders: Lukáš Vlk
---
# ADR-0004: Agent file editing API

## Context and Problem Statement

Agents have been read-only from Homer's API: the console can list,
view, and run them, but editing an `agent.yaml` / `agent.json` required
SSH-ing into the host, opening `$agentsSearchPath` in an editor, and
calling `POST /api/v1/agents/reload` to pick up changes.
`agentdebug` covers per-command runs locally but not remote editing.

The 1.5.0 console editor feature needs a write surface: list files in
an agent's directory, read them, save edits (with schema validation
for `agent.yaml` / `agent.json`), delete, create new agents, and run a
single command with custom env / query params while tailing logs. Every
choice along that path is a structural commitment — once a write API
exists, it constrains how multi-tenant isolation, schema validation,
and debug execution work in future.

## Decision Drivers

- The server must refuse writes when the filesystem doesn't allow
  them, without an extra config knob that can drift from reality.
- An invalid `agent.yaml` must never be persisted and reloaded, because
  `AgentsService.reload()` would then either skip the agent silently or
  put the process in a broken state.
- Path handling must make directory traversal impossible; a single
  missed check is a host-compromise bug.
- Debug runs must behave identically to real runs — locks, parallel-run
  limits, telemetry, SSE log streaming, process history — so operators
  don't build mental models around a second execution model.

## Considered Options

- **Read-only detection**
  - A. Filesystem probe (`Files.isWritable(agentDir)`) evaluated per
    request or at scan time.
  - B. New `readOnly: Boolean` config flag on `agentsSearchPath` or a
    new wrapper struct.
- **Schema validation on write**
  - A. Reuse the same kaml / kotlinx.serialization parser the loader
    uses — decode into `Agent`, return the deserialization error.
  - B. Add a dedicated JSON-Schema validator dependency and validate
    against `app/schemas/homer-agent.schema.json` at write time.
- **Path-traversal guard**
  - A. `resolved.toRealPath().startsWith(agentDir.toRealPath())`
    containment check after resolving the user-supplied path.
  - B. String-level allow-list on path components.
- **Single-command debug runs**
  - A. Reuse the real `ProcessExecutor` / `AgentExecutor` pipeline,
    restricted to one command index via an optional `commandIndices`
    parameter.
  - B. New "simulator" path that runs the command outside the normal
    lifecycle (no lock, no parallel-run slot, no process record).

## Decision Outcome

Chosen: **A on all four axes.**

### Read-only detection → `Files.isWritable`

`Agent` gains a `@Transient val writable: Boolean` set at scan time by
`AgentsService.scanAgents()`. Serialization skips it (the on-disk
schema doesn't change), but the API response exposes it via an
explicit field in `GET /api/v1/agents`. The console uses it to show or
hide the "Edit" button.

The probe runs once per scan, not per request — it rescans when
`AgentsService.reload()` is called, which is also called after any
successful write. So the flag reflects the state of the directory the
last time we looked at it. A config flag would have drifted: an
operator chmods a directory, `readOnly: false` in config keeps lying,
and the console happily offers "Edit" that 500s on `PUT`. The probe
can lie too (racy chmod mid-write), but in that case the write itself
fails fast with `AccessDeniedException`, which we map to 403.

### Schema validation on write → reuse the loader parser

`AgentFilesService.write()` for files named `agent.yaml` / `agent.json`
decodes the content through the same `Yaml.default.decodeFromString<Agent>`
/ `Json.decodeFromString<Agent>` path as the loader. On failure it
returns `FileOpResult.SchemaInvalid(msg, errors)` → HTTP 422.

The alternative (adding a JSON Schema validator) would duplicate the
validation surface: the loader's deserializer already rejects unknown
fields, wrong types, missing required fields, and invalid enum values
via kotlinx.serialization. Running a separate schema validator would
give *different* error messages for the same failures depending on
which path you hit, and it would drift from the schema file — exactly
the problem `SchemaValidationTest` exists to catch. One parser, one
source of truth.

### Path-traversal guard → `toRealPath()` containment

Every path handler resolves the user-supplied relative path against
the agent directory, calls `.toRealPath()` on the result, and rejects
anything whose real path does not start with the agent's real path.
Symlinks inside the agent dir that point outside it fail the same
check. String-level component checks (rejecting `..`) are fragile —
they miss symlink escapes, miss `%2e%2e`-style decoded input, and
couple the guard to the transport layer.

### Debug runs → reuse `ProcessExecutor`

`POST /api/v1/agents/{name}/debug` creates a `Process` exactly like
the regular execute endpoint, but threads an optional
`commandIndices: List<Int>?` through `ProcessFactory.create()` →
`Process` → `AgentExecutor`. When non-null, `AgentExecutor`'s command
loop skips indices not in the set; everything else — workspace
creation, lock acquisition, parallel-run slot, ProcessManager
recording, artifact streaming, SSE events — is unchanged.

A separate simulator path would have been less code in
`AgentExecutor` but a much larger maintenance liability: every future
change to process lifecycle (status precedence rules, telemetry
attributes, artifact layout, kill semantics) would need to be made in
two places and tested twice. Debug runs also benefit from showing up
in the same `/processes` history the console already renders — one
mental model.

## Consequences

- Good: the console edit button never lies about writability in steady
  state; races fail fast with 403, not silent corruption.
- Good: schema errors on write use the exact same messages operators
  see when the loader rejects a file at startup.
- Good: debug runs are indistinguishable from real runs in logs,
  telemetry, and process history — no second code path to audit.
- Good: introducing a new runner or lifecycle step (ADR-0003) applies
  to debug runs automatically.
- Bad: decoding through `Agent.serializer()` on every write is slower
  than a cached JSON-Schema validator — negligible at human editing
  cadence, but measurable if someone scripts thousands of writes per
  second. Not a concern for 1.5.0.
- Accepted risk: the race mapping covers the mutating calls only. The
  read side has no writability probe to race with, so `read()` and
  `list()` can still let `AccessDeniedException` escape as an unhandled
  `500` when a file or directory is chmod'd unreadable mid-request (or
  between a listing's stat calls). Accepted because reads are
  idempotent — a denied read leaves no partial state to undo, cannot
  wedge an agent name the way a leftover directory would, and a retry
  after the operator restores the permission succeeds. A read-side
  catch returning `403` is the fix if this ever shows up in practice.
- Bad: `Files.isWritable` is POSIX-semantics on Linux/macOS and ACL-
  semantics on Windows; behavior on exotic filesystems (NFS, bind
  mounts with stacked permissions) may be surprising. Documented in
  `app/README.md`.
- Bad: every debug run consumes a `maxParallelRuns` slot and acquires
  the agent's lock, same as a real run — debugging a `WAIT`-lock agent
  mid-production will block on the live run. This is correct (a debug
  run is a real run) but worth knowing.

## More Information

- Related: [ADR-0002](0002-unified-error-model-at-http-boundary.md) —
  `AgentFilesService` returns a `FileOpResult` sealed class mapped to
  HTTP at the route layer, following the same single-boundary error
  model.
- Related: [ADR-0003](0003-runners-as-execution-backends.md) — debug
  runs go through the same runner session as real runs, so a
  Kubernetes-runner agent debugs inside a pod too.
- Schema backstop: `SchemaValidationTest` still guards the on-disk
  format; the write path uses the loader's deserializer, not the
  schema file directly.
