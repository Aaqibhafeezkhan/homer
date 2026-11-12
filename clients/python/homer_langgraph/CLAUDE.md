# homer_langgraph CLAUDE.md

Dev guide for the Python runtime behind `!<langgraph>`. Project-wide rules live
in the root [`CLAUDE.md`](../../../CLAUDE.md) and apply here too. The package
reference (segment model, env contract, wrapper invocations, `inspect` / `state`
output) is [`README.md`](README.md) — read it before changing the runtime, and
update it in the same commit when a contract it describes changes.

## Invariants that bite

- **Gates are pure.** `homer_interrupt` / `homer_fanout` only call
  `interrupt(...)`; the ask / fan-out side effect runs once, in `runner.py`,
  after `invoke()` returns. Homer resumes by re-running the node from its
  start, so a side effect inside a gate fires again on every resume.
- **`homer_dispatch` never precedes an `interrupt()` in the same node** — it
  shells out directly and would dispatch twice on resume.
- **The interrupt id of a fan-out rides `--continue-param`, not `--param`** — it
  targets the resumed parent; on the sub it is dropped and the parent re-parks
  forever.
- **`state` writes nothing — do not add a cache.** A file cache in the
  agent-writable state dir was tried and removed after four findings; see
  [ADR-0044](../../../docs/adr/0044-langgraph-resume-and-run-introspection.md)'s
  2026-08-20 amendment. Polling is bounded in Homer's `LangGraphRunStatusService`.
- **`interruptsSeen` ordering is load-bearing.** Record `latest.tasks` ids before
  the history walk so the cap never drops a live park; keep the `done` fold
  unbounded, since `done` may only be provable from the oldest snapshot.
- **`traversed` depends on LangGraph-internal channel names** (`branch:to:`,
  `join:`, `__pregel_tasks`); re-verify on a langgraph bump. See
  [ADR-0075](../../../docs/adr/0075-langgraph-traversed-path-from-checkpoint-writes.md).

## Tests

```sh
python3 -m venv .venv
.venv/bin/pip install -e '.[test]' langgraph-checkpoint-sqlite   # from clients/python
.venv/bin/python -m pytest                                        # from clients/python
```

Tests mock `subprocess.run` and use LangGraph's in-memory `MemorySaver`, so no
real Homer wrappers or network are required.

`langgraph` is unpinned, so a local run only verifies the line the package
index resolves: CI installs the newest, while an index missing the newest
wheels backtracks to an older one — a green run there has been mistaken for a
fresh-install green. The `homer-python-tests` CI job is the fresh-install check
(it installs from `pyproject.toml` on a full index and resolves the newest line),
so read its result before treating a local green as covering that line. Check
what resolved (`.venv/bin/pip show langgraph`); when
touching LangGraph-internals behavior, verify on the newest line explicitly,
e.g. `.venv/bin/pip install 'langgraph==<newest>' 'langgraph-checkpoint==<newest>'
'langgraph-checkpoint-sqlite==<newest>'`.
