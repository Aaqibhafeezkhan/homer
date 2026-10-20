# homer_langgraph

Python runtime for Homer's `!<langgraph>` command type. Homer's Kotlin
`LangGraphExecutor` is a thin launcher; this package owns the graph execution,
durable checkpointing, and all human-in-the-loop (HITL) side effects.
Agent-side fields (`module`, `python`, `inputEnv`) are documented in
[`app/AGENT.md`](../../../app/AGENT.md#commandlanggraph---langgraph-runtime-launcher).

## Durable checkpoint + dispatch-resume model

A LangGraph workflow runs in **segments**. Each `python -m homer_langgraph run
--module <m>` invocation runs (or resumes) the graph until it either completes
or parks on a pending `interrupt()`:

- **Complete** → the final state is printed as JSON to stdout; exit 0.
- **Parked** → the runtime performs the side effect the gate implied (ask the
  operator, or fan out to a sub-agent), then exits 0. That side effect later
  re-runs the *same agent* with a resume value in the environment, which starts
  the next segment.

State survives across segments via a SQLite checkpointer (`SqliteSaver`) opened
at `$HOMER_LG_STATE_DIR/checkpoints.sqlite`, keyed by the LangGraph thread id.

## The one rule: gates are pure, side effects are runtime-owned

Homer resumes a parked thread by **re-running the agent from the top**, carrying
the thread id in `HMR_LG_THREAD`. LangGraph then re-executes the interrupted
node from its start up to `interrupt()`.

Therefore the gate helpers in `gates.py` (`homer_interrupt`, `homer_fanout`)
**only** call `interrupt(...)` — they perform no ask/dispatch themselves. The
runtime performs the ask/fan-out exactly once, in `runner.py`, *after*
`invoke()` returns with the pending interrupt. If a gate did the side effect
inside the node, it would fire again on every resume.

`homer_dispatch` (in `dispatch.py`) is the exception: it is fire-and-forget and
shells out directly. It must **not** be placed before an `interrupt()` in the
same node, or it would dispatch twice on resume.

## Env contract

| Env | When | Meaning |
| --- | --- | --- |
| `HOMER_LG_STATE_DIR` | always | Durable dir for the SQLite checkpoint store. |
| `HMR_AGENT_NAME` | always | Current agent's name (used as the resume target). |
| `HMR_ARTIFACTS_DIR` | optional | Where the run-identity artifact is written; skipped when unset. |
| `<inputEnv>` | fresh run | JSON graph input, read from the var named by the `run --input-env` arg (the command's `inputEnv`; default `HMR_LG_INPUT`). |
| `HMR_LG_THREAD` | resume | LangGraph thread id to resume. |
| `HMR_LG_RESUME` | resume | Resume value: an answer string (human gate) or a JSON `{status, processId, result}` (fan-out). For a **join** resume it is a JSON map `{interrupt_id: per_sub_payload}`, where each `per_sub_payload` is the same single-fanout JSON string. |
| `HMR_LG_INTERRUPT` | resume (multi) | Stable id of the interrupt(s) this resume targets. One id → a single targeted interrupt; a **comma-joined** list of ids → a join resume (resolve the whole group at once). Present only when the parking segment chose a unit among several pending interrupts. Homer defines it too (`EnvConstants.LG_INTERRUPT`, reserved) and reads it from continuation params to attribute sub-agent runs to their node — do not rename it on one side only. |
| `HMR_LG_RECOVER` | recover | "1" re-enters the thread from its latest checkpoint (graph input None). Ignored when `HMR_LG_RESUME` is present. |

A run is a **resume** when `HMR_LG_THREAD` is non-empty *and* `HMR_LG_RESUME`
is present. It is a **recover** when `HMR_LG_THREAD` is non-empty, `HMR_LG_RECOVER`
is `"1"`, and no resume value is present; otherwise it is a fresh run.

## Run-identity artifact

Before every invoke, the runtime writes `$HMR_ARTIFACTS_DIR/langgraph.json`
(`{"threadId", "module", "segment": "fresh"|"resume"|"recover"}`), overwritten
per segment. Homer's resume/status endpoints read it from the process's
artifacts directory; it is written before graph code runs so a crash cannot
lose the thread id. Skipped when `HMR_ARTIFACTS_DIR` is unset (agentdebug).

## Wrapper invocations the runtime generates

- **Human gate** → `homer-ask ask-and-dispatch "<text>" --agent $HMR_AGENT_NAME
  --answer-param HMR_LG_RESUME --param HMR_LG_THREAD=<thread> --param
  HMR_LG_INTERRUPT=<id>` (+ `--option` per option, `--ttl` if set).
- **Fan-out** → `homer-dispatch --agent <sub> --continue-agent $HMR_AGENT_NAME
  --continue-thread <thread> --resume-param HMR_LG_RESUME --continue-param
  HMR_LG_INTERRUPT=<id>` (+ `--result-jsonpath`, `--ttl`, `--param k=v` from `params`).
  The interrupt id rides `--continue-param`, not `--param`: it targets the
  *resumed parent* (the continuation), so a `--param` would land on the sub and
  be dropped (the parent then re-parks forever on "multiple pending interrupts").
- **Fire-and-forget** (`homer_dispatch`) → `homer-dispatch --agent <sub>
  --param k=v ...` (no continuation flags).
- **Join** (a fan-out group: ≥2 `homer_fanout(..., group=g)` interrupts parked
  in one segment) → `homer-join` with the request body on **stdin** (a nested
  `subs` array). Starts all subs at once and registers ONE continuation that
  resumes the parent a single time after the last finishes. The comma-joined
  sorted interrupt ids ride `continueParams.HMR_LG_INTERRUPT`.

These wrappers live in `{projectRoot}/.homer/bin`, which Homer prepends to
`PATH` when the run's env defines `PATH` (the local runner's default
`parentEnvAllowlist` does; remote runners need `PATH` in their runner `env`).
The runtime invokes them by bare name, so a run without `PATH` cannot park.

## Large fan-out results (read-by-reference)

When a fan-out sub's result exceeds Homer's inline cap (64 KiB), the resume
payload arrives with `result: null` and a `resultRef` capability token instead.
`homer_fanout` detects this and calls `homer_fetch_result(ref)` (in
`results.py`), which shells out to the `homer-result` wrapper to GET
`/api/v1/callback/results/<token>` and splices the full (server-side
transformed) value back into `result` — so graph code always sees a normal
payload. The fetch is an idempotent GET and the token is reusable within its
TTL, so re-running the node on resume is safe. If the fetch fails for a sub
that did not `FINISHED`, `homer_fanout` keeps `result: null`, reports on
stderr and returns the payload — the graph's own status check (retry, park)
must get to run; a fetch failure for a `FINISHED` sub still raises. Above
4 MiB (the server-side transform cap) no `resultRef` is minted and `result`
stays `null`; a sub dispatched without `result_jsonpath` resumes with neither
a `result` nor a `resultRef`. See
[ADR-0025](../../../docs/adr/0025-capability-token-result-read.md).

## Multi-interrupt resolution: sequential and join modes

When a node parks on several interrupts at once (parallel branches), each
segment resolves exactly **one unit** — chosen so the segment has exactly one
resume-producing side effect. The unit is picked as follows:

- **Join (parallel) mode.** Group pending *fanout* interrupts by their non-None
  `group` key (set via `homer_fanout(..., group="…")`). A group with ≥2 members
  is *join-eligible*. If any eligible group exists, the runtime emits ONE
  `homer-join` for the eligible group with the smallest min interrupt id:
  dispatch all its subs at once, register one continuation, resume the thread a
  single time once the last sub ends. So a group of N costs **one round-trip**,
  wall-clock ≈ slowest sub. The resume carries a comma-joined
  `HMR_LG_INTERRUPT` and a `HMR_LG_RESUME` map `{id: per_sub_payload}`, decoded
  with `Command(resume=json.loads(...))` to resolve the whole group in one
  invoke. See [ADR-0026](../../../docs/adr/0026-join-continuations.md).
- **Sequential mode (default).** With no eligible group — human gates, ungrouped
  fanouts (no `group`), or a single interrupt — the runtime picks one interrupt
  deterministically (smallest stable `Interrupt.id`), threads that id back
  (`--param` for the human gate, `--continue-param` for fan-out so it reaches
  the continuation rather than the sub), and resumes via a per-interrupt map
  `Command(resume={id: value})`, leaving the others pending. **N interrupts cost
  N round-trips.** When `HMR_LG_INTERRUPT` is absent (single interrupt, or an
  older park), it falls back to the scalar `Command(resume=value)` path.

Modes mix freely within one graph: a park with a grouped fan-out plus an
ungrouped gate emits the join first, then resolves the gate in a later segment.
**Opt-in is per call** (`group=`), not whole-agent, so one graph can mix
parallel and sequential fan-outs; a call without `group` takes the sequential path, byte-for-byte.

**Caveat — node bodies re-run on resume.** With parallel fan-out plus *additive*
reducers this can double-apply (pre-existing, mode-independent). A grouped
fan-out node must keep its body idempotent — write the fan-out result to a
distinct per-branch slot, not an accumulating channel.

**Caveat — same-node multiple `interrupt()` calls:** the per-interrupt map
targets *distinct parallel* interrupts. Multiple `interrupt()` calls inside a
single node still terminate, but LangGraph replays them by scratchpad index
rather than by the single targeted id, so they are not individually addressed.
Supported scope is distinct parallel interrupts.

## Module spec

`--module` accepts `pkg.mod:attr` or `pkg.mod` (default attr `graph`). The
module must expose a compilable `StateGraph` (compiled here with the
checkpointer) or an already-compiled graph (then the module must attach its own
checkpointer).

## Read-only introspection (`inspect`)

`python -m homer_langgraph inspect --module <m>` imports and compiles the graph
**without a checkpointer** and prints its Mermaid source
(`compiled.get_graph().draw_mermaid()`) to stdout, exit 0. Import/compile errors
print to stderr, exit 1. No SQLite, no env contract, no side effects. This is
the legacy no-flag form, kept for direct use; Homer's `LangGraphInspectService`
calls `inspect --json` (below) to render the workflow on the console agent
detail page.

`python -m homer_langgraph inspect --module <m> --json` prints one JSON object
`{"mermaid": "..." | null, "topology": {"nodes": [{"id": "..."}], "edges":
[{"source": "...", "target": "...", "label": "..." | null, "conditional":
bool}]}}` instead — the graph's structured topology (nodes and edges) next to
its (deprecated) Mermaid source. `mermaid` is `null` only if drawing it failed;
`topology` is still produced from the same `get_graph()` call. Without `--json`,
plain `inspect` is unchanged. See [ADR-0073](../../../docs/adr/0073-langgraph-topology-is-structured-data.md).

`python -m homer_langgraph state --module <m> --thread <t>` opens the durable
checkpoint store read-only — and only when `checkpoints.sqlite` is a regular,
singly-linked file; a symlink, hard link or special file there reads as "no
checkpoint" — and prints per-node status JSON
(`{"threadId", "nodes": {name: failed|parked|pending|done|unreached},
"errors": {name: message}, "interrupts": {node: [pending interrupt ids]},
"interruptsSeen": {node: [recent interrupt ids]},
"interruptsSeenTruncated": [nodes], "mermaid": "...", "topology": {...},
"traversed": [{"source", "target"}] | null}`;
precedence failed > parked > pending > done > unreached;
`interrupts` lists sorted pending interrupt ids for parked nodes only, `{}`
when none; `interruptsSeen` adds the ids the node has parked on across
`get_state_history()` (sorted; the cap keeps the newest ids — see below), so Homer can still
attribute an already-resolved fan-out to its node; `mermaid` is the graph's Mermaid source, or null when drawing
fails — the graph is already compiled here, so including it saves Homer a
second `inspect` subprocess per status request). `topology` is the graph's
nodes and edges (see `inspect --json`), or null when building it failed;
`mermaid` is deprecated (ADR-0073). Errors print to stderr,
exit 1. Homer's
`GET /api/v1/processes/{id}/langgraph` shells out to this. See [ADR-0044](../../../docs/adr/0044-langgraph-resume-and-run-introspection.md).

`traversed` is the path the thread has taken so far: the topology edges its
tasks routed along, read from the routing writes in its checkpoint history
(including `__start__ → first` and `last → __end__`), deduplicated and sorted
by `(source, target)`. Only edges present in `topology` are kept, since the
store is agent-writable; a routing write whose target matches no topology edge
adds no pair, so the list can miss an edge but never invents one. The scan is
capped: at most `MAX_TRAVERSED_CHECKPOINTS` checkpoints and
`MAX_TRAVERSED_WRITES` writes are read, and an over-long routing channel or
`Send.node` is dropped before matching, so a pathological agent-written
history yields a partial list instead of an unbounded scan. `null` means not known (no topology, extraction
failed, or no routing write recognized); `[]` means nothing has been traversed yet. See [ADR-0075](../../../docs/adr/0075-langgraph-traversed-path-from-checkpoint-writes.md).

`done` and `interruptsSeen` are folds over the whole checkpoint history,
recomputed on every call. A node is `done` once one of its tasks produced a
result, including a task that failed and then succeeded on a recover (LangGraph
keeps the failed attempt's error on that task). **`state` writes nothing — do not add a cache here.**

**`interruptsSeen` is capped per node** at `MAX_INTERRUPTS_SEEN_PER_NODE` (100),
keeping the newest ids, and every clipped node is named in
`interruptsSeenTruncated` (Homer surfaces it as `subsTruncated`). A node that
loops through thousands of fan-outs would otherwise grow this JSON, Homer's
response, and the per-id continuation lookup behind every entry without adding
information. Ordering is load-bearing: the ids in `latest.tasks` are recorded
*before* the history walk, so a node's live park is never among the dropped —
the cap keeps a prefix of insertion order, and `get_state_history()` yields
newest-first. The *fold* stays unbounded on purpose: a node's `done` may only
be provable from the oldest snapshot, so an early exit would misreport status.

A per-thread file cache was tried and removed; see [ADR-0044](../../../docs/adr/0044-langgraph-resume-and-run-introspection.md)'s amendment of
2026-08-20 before proposing one again.

## Install

Requires Python ≥ 3.10. The backend Docker image already bundles the
runtime (`app/Dockerfile`); elsewhere, from the repo root:

```sh
pip install ./clients/python
```

## Tests

```sh
cd clients/python
python3 -m venv .venv
.venv/bin/pip install -e '.[test]'
.venv/bin/python -m pytest
```

Tests mock `subprocess.run` and use LangGraph's in-memory `MemorySaver`, so no
real Homer wrappers or network are required.
