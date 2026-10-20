"""Tests for the read-only `state` subcommand (per-node thread status)."""

from __future__ import annotations

import json
import os
import sqlite3
from types import SimpleNamespace

import pytest

from langgraph.types import Send

from homer_langgraph import __main__, introspection, runner
from homer_langgraph.tests import graph_fixtures

HUMAN = "homer_langgraph.tests.graph_fixtures:human_graph"
COMPLETE = "homer_langgraph.tests.graph_fixtures:complete_graph"
FAILING = "homer_langgraph.tests.graph_fixtures:failing_graph"
FLAKY = "homer_langgraph.tests.graph_fixtures:flaky_graph"
LOOP = "homer_langgraph.tests.graph_fixtures:loop_human_graph"


def _thread_id(tmp_path):
    return json.loads(
        (tmp_path / "artifacts" / runner.ARTIFACT_FILENAME).read_text()
    )["threadId"]


def _run_complete(monkeypatch, tmp_path) -> str:
    """Run COMPLETE to the end and return its thread id."""
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))
    assert runner.run(COMPLETE) == 0
    return _thread_id(tmp_path)


@pytest.fixture
def env(env, monkeypatch, tmp_path):
    """The base env plus an artifacts dir, so each run's thread id is readable."""
    monkeypatch.setenv(runner.ENV_ARTIFACTS_DIR, str(tmp_path / "artifacts"))


def test_completed_thread_all_done(env, shared_saver, monkeypatch, tmp_path):
    thread_id = _run_complete(monkeypatch, tmp_path)
    result = introspection.thread_state(COMPLETE, thread_id)
    assert set(result["nodes"].values()) == {"done"}
    assert result["errors"] == {}
    # Mermaid rides along so Homer needs no separate inspect subprocess.
    assert "graph TD" in result["mermaid"] or "flowchart" in result["mermaid"]
    ids = {node["id"] for node in result["topology"]["nodes"]}
    assert set(result["nodes"]) <= ids


def test_draw_failure_degrades_to_null_mermaid(
    env, shared_saver, failing_mermaid, monkeypatch, tmp_path
):
    thread_id = _run_complete(monkeypatch, tmp_path)
    result = introspection.thread_state(COMPLETE, thread_id)
    assert result["mermaid"] is None
    assert set(result["nodes"].values()) == {"done"}


def test_parked_thread_reports_parked_node(env, shared_saver, captured_runs, monkeypatch, tmp_path):
    assert runner.run(HUMAN) == 0
    result = introspection.thread_state(HUMAN, _thread_id(tmp_path))
    assert "parked" in result["nodes"].values()


def test_parked_node_reports_interrupt_ids(env, shared_saver, captured_runs, monkeypatch, tmp_path):
    assert runner.run(HUMAN) == 0
    result = introspection.thread_state(HUMAN, _thread_id(tmp_path))
    parked = [n for n, s in result["nodes"].items() if s == "parked"]
    assert parked
    for node in parked:
        assert result["interrupts"][node], "parked node must list its pending interrupt ids"
    # only parked nodes appear
    assert set(result["interrupts"]) == set(parked)


def test_interrupts_seen_keeps_resolved_interrupt_ids(
    env, shared_saver, captured_runs, monkeypatch, tmp_path
):
    """History survives the resume: `interrupts` empties, `interruptsSeen` does not."""
    assert runner.run(HUMAN) == 0
    thread = _thread_id(tmp_path)
    parked_before = introspection.thread_state(HUMAN, thread)
    node = next(n for n, s in parked_before["nodes"].items() if s == "parked")
    resolved_ids = parked_before["interrupts"][node]
    assert resolved_ids
    assert parked_before["interruptsSeen"][node] == resolved_ids

    # Resume that interrupt so the node completes and the park is gone.
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_RESUME, "yes")
    assert runner.run(HUMAN) == 0

    after = introspection.thread_state(HUMAN, thread)
    assert after["interrupts"] == {}, "no node is parked any more"
    assert after["interruptsSeen"][node] == resolved_ids, (
        "a resolved node must still report the interrupt ids it parked on"
    )


def _park_ids_over_loop(monkeypatch, tmp_path, resumes: int) -> tuple[str, list[str]]:
    """Drive the looping graph through ``resumes`` parks, oldest id first."""
    assert runner.run(LOOP) == 0
    thread = _thread_id(tmp_path)
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    ids: list[str] = []
    for _ in range(resumes):
        parked = introspection.thread_state(LOOP, thread)["interrupts"]["loop"]
        assert len(parked) == 1
        ids.append(parked[0])
        monkeypatch.setenv(runner.ENV_RESUME, "yes")
        assert runner.run(LOOP) == 0
    return thread, ids


def test_interrupts_seen_keeps_the_newest_ids_and_flags_the_node(
    env, shared_saver, captured_runs, monkeypatch, tmp_path
):
    """The per-node cap drops the oldest history, not the newest, and says so."""
    monkeypatch.setattr(introspection, "MAX_INTERRUPTS_SEEN_PER_NODE", 2)
    thread, ids = _park_ids_over_loop(monkeypatch, tmp_path, resumes=3)
    assert len(set(ids)) == 3, "each pass must mint its own interrupt id"

    result = introspection.thread_state(LOOP, thread)

    assert result["interruptsSeen"]["loop"] == sorted(ids[1:])
    assert ids[0] not in result["interruptsSeen"]["loop"], "oldest id must be the one dropped"
    assert result["interruptsSeenTruncated"] == ["loop"]


def test_interrupts_seen_never_clips_the_live_park(
    env, shared_saver, captured_runs, monkeypatch, tmp_path
):
    """A node parked right now reports that id even when its history overflows."""
    monkeypatch.setattr(introspection, "MAX_INTERRUPTS_SEEN_PER_NODE", 1)
    thread, ids = _park_ids_over_loop(monkeypatch, tmp_path, resumes=2)

    result = introspection.thread_state(LOOP, thread)

    live = result["interrupts"]["loop"]
    assert len(live) == 1 and live[0] not in ids, "the thread is parked on a third id"
    assert result["interruptsSeen"]["loop"] == live
    assert result["interruptsSeenTruncated"] == ["loop"]


def test_interrupts_seen_truncated_is_empty_under_the_cap(
    env, shared_saver, captured_runs, tmp_path
):
    assert runner.run(HUMAN) == 0
    result = introspection.thread_state(HUMAN, _thread_id(tmp_path))

    assert result["interruptsSeen"], "the parked node still reports its id"
    assert result["interruptsSeenTruncated"] == []


def test_state_writes_no_cache_or_temp_files(env, shared_saver, captured_runs, tmp_path):
    """`state` is read-only.

    A derived cache used to live in the state directory and produced four
    consecutive symlink / hard-link / TOCTOU findings, because that directory is
    also writable by agent code. Repeated polling is bounded on the Homer side
    now, so nothing here may create files again.
    """
    assert runner.run(HUMAN) == 0
    thread = _thread_id(tmp_path)

    introspection.thread_state(HUMAN, thread)
    introspection.thread_state(HUMAN, thread)

    assert not (tmp_path / "status_cache").exists(), "the file cache is back"
    assert not list(tmp_path.rglob("*.tmp")), "state left a temp file behind"


def test_completed_thread_has_empty_interrupts(env, shared_saver, monkeypatch, tmp_path):
    thread_id = _run_complete(monkeypatch, tmp_path)
    result = introspection.thread_state(COMPLETE, thread_id)
    assert result["interrupts"] == {}


def test_failed_node_reports_failed_and_predecessor_done(env, shared_saver, monkeypatch, tmp_path):
    # runner.run propagates graph exceptions (per its docstring); the errored
    # task's checkpoint survives in the shared saver for thread_state to read.
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))
    with pytest.raises(RuntimeError, match="boom"):
        runner.run(FAILING)
    result = introspection.thread_state(FAILING, _thread_id(tmp_path))
    assert result["nodes"]["boom"] == "failed"
    assert result["nodes"]["ok"] == "done"
    assert "boom" in result["errors"]


def test_failed_node_has_no_end_edge(env, shared_saver, monkeypatch, tmp_path):
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))
    with pytest.raises(RuntimeError, match="boom"):
        runner.run(FAILING)
    result = introspection.thread_state(FAILING, _thread_id(tmp_path))
    assert _pairs(result) == [("__start__", "ok"), ("ok", "boom")]


def test_recovered_node_reports_done_and_its_end_edge(env, shared_saver, monkeypatch, tmp_path):
    # LangGraph keeps the failed attempt's error on the task after a recover
    # succeeds, so the task carries both an error and a result.
    monkeypatch.setitem(graph_fixtures.FLAKY_FAILURES, "left", 1)
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))
    with pytest.raises(RuntimeError, match="flaky"):
        runner.run(FLAKY)
    monkeypatch.setenv(runner.ENV_THREAD, _thread_id(tmp_path))
    monkeypatch.setenv(runner.ENV_RECOVER, "1")
    assert runner.run(FLAKY) == 0

    result = introspection.thread_state(FLAKY, _thread_id(tmp_path))

    assert result["nodes"] == {"ok": "done", "flaky": "done"}
    assert result["errors"] == {}
    assert _pairs(result) == [("__start__", "ok"), ("flaky", "__end__"), ("ok", "flaky")]


def test_unknown_thread_raises(env, shared_saver):
    with pytest.raises(ValueError, match="no checkpoint"):
        introspection.thread_state(COMPLETE, "deadbeef")


def test_cli_state_prints_json(env, shared_saver, monkeypatch, tmp_path, capsys):
    thread_id = _run_complete(monkeypatch, tmp_path)
    capsys.readouterr()
    assert __main__.main(["state", "--module", COMPLETE, "--thread", thread_id]) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["threadId"] == thread_id
    # Key order is part of the output contract Homer parses; pin it.
    assert list(payload) == [
        "threadId",
        "nodes",
        "errors",
        "interrupts",
        "interruptsSeen",
        "interruptsSeenTruncated",
        "mermaid",
        "topology",
        "traversed",
    ]


def test_cli_state_unknown_thread_exit_1(env, shared_saver, capsys):
    assert __main__.main(["state", "--module", COMPLETE, "--thread", "nope"]) == 1
    assert "state failed" in capsys.readouterr().err


# The tests below use the real SqliteSaver (no `shared_saver`): the store lives in
# the agent-writable state dir, so `state` must never write into it.


def _store_snapshot(db_path):
    return db_path.read_bytes(), db_path.stat().st_mtime_ns


@pytest.fixture
def odd_state_dir(monkeypatch, tmp_path):
    """A state dir whose name needs URI escaping (`?`, `#`, `%`, space)."""
    state_dir = tmp_path / "state ?mode=rw#%41"
    state_dir.mkdir()
    monkeypatch.setenv(runner.ENV_STATE_DIR, str(state_dir))
    return state_dir


def test_state_leaves_a_real_store_unchanged(env, odd_state_dir, monkeypatch, tmp_path):
    thread_id = _run_complete(monkeypatch, tmp_path)
    db_path = odd_state_dir / "checkpoints.sqlite"
    before = _store_snapshot(db_path)

    result = introspection.thread_state(COMPLETE, thread_id)

    assert set(result["nodes"].values()) == {"done"}
    assert result["traversed"] == [
        {"source": "__start__", "target": "n"},
        {"source": "n", "target": "__end__"},
    ]
    # Read-only readers may leave empty -wal/-shm sidecars; only the store matters.
    assert _store_snapshot(db_path) == before


def test_state_reads_send_fanout_back_from_a_real_store(env, odd_state_dir, monkeypatch, tmp_path):
    """`Send` packets deserialize as `Send` from the on-disk store, not only in memory."""
    module = "homer_langgraph.tests.graph_fixtures:send_graph"
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({}))
    assert runner.run(module) == 0

    result = introspection.thread_state(module, _thread_id(tmp_path))

    assert result["traversed"] == [
        {"source": "__start__", "target": "fan"},
        {"source": "fan", "target": "work"},
        {"source": "work", "target": "__end__"},
    ]


def test_state_refuses_to_write_a_store_without_checkpoint_tables(env, tmp_path):
    """Denial path: a planted store gets no tables and keeps its journal mode."""
    db_path = tmp_path / "checkpoints.sqlite"
    conn = sqlite3.connect(db_path)
    conn.execute("CREATE TABLE other (x)")
    conn.commit()
    conn.close()
    before = db_path.read_bytes()

    with pytest.raises(ValueError, match="no checkpoint"):
        introspection.thread_state(COMPLETE, "deadbeef")

    assert db_path.read_bytes() == before
    conn = sqlite3.connect(f"{db_path.as_uri()}?mode=ro", uri=True)
    try:
        tables = [r[0] for r in conn.execute("SELECT name FROM sqlite_master")]
        journal_mode = conn.execute("PRAGMA journal_mode").fetchone()[0]
    finally:
        conn.close()
    assert tables == ["other"]
    assert journal_mode == "delete"


@pytest.fixture
def real_store_elsewhere(env, monkeypatch, tmp_path):
    """A real checkpoint store of a completed thread, in a dir other than the state dir.

    Returns (store path, thread id, fresh empty state dir now in HOMER_LG_STATE_DIR).
    """
    origin = tmp_path / "origin"
    origin.mkdir()
    monkeypatch.setenv(runner.ENV_STATE_DIR, str(origin))
    thread_id = _run_complete(monkeypatch, tmp_path)
    state_dir = tmp_path / "planted"
    state_dir.mkdir()
    monkeypatch.setenv(runner.ENV_STATE_DIR, str(state_dir))
    return origin / runner.CHECKPOINT_FILENAME, thread_id, state_dir


def test_state_refuses_a_symlinked_store(real_store_elsewhere):
    """CWE-59 denial path: a link planted at the store's name is not followed."""
    store, thread_id, state_dir = real_store_elsewhere
    (state_dir / runner.CHECKPOINT_FILENAME).symlink_to(store)
    with pytest.raises(ValueError, match=f"no checkpoint for thread {thread_id}"):
        introspection.thread_state(COMPLETE, thread_id)


def test_state_refuses_a_dangling_symlinked_store(env, tmp_path):
    (tmp_path / runner.CHECKPOINT_FILENAME).symlink_to(tmp_path / "missing.sqlite")
    with pytest.raises(ValueError, match="no checkpoint for thread deadbeef"):
        introspection.thread_state(COMPLETE, "deadbeef")
    assert not (tmp_path / "missing.sqlite").exists()


def test_state_refuses_a_hard_linked_store(real_store_elsewhere):
    """CWE-59 denial path: a second name for another run's store is refused too."""
    store, thread_id, state_dir = real_store_elsewhere
    os.link(store, state_dir / runner.CHECKPOINT_FILENAME)
    with pytest.raises(ValueError, match=f"no checkpoint for thread {thread_id}"):
        introspection.thread_state(COMPLETE, thread_id)


def test_state_refuses_a_fifo_store_without_blocking(env, tmp_path):
    """A FIFO at the store's name is refused before any open, which would block."""
    if not hasattr(os, "mkfifo"):
        pytest.skip("no mkfifo on this platform")
    os.mkfifo(tmp_path / runner.CHECKPOINT_FILENAME)
    with pytest.raises(ValueError, match="no checkpoint for thread deadbeef"):
        introspection.thread_state(COMPLETE, "deadbeef")


def test_state_still_reads_a_regular_store_after_the_link_checks(real_store_elsewhere, monkeypatch):
    store, thread_id, _ = real_store_elsewhere
    monkeypatch.setenv(runner.ENV_STATE_DIR, str(store.parent))
    assert set(introspection.thread_state(COMPLETE, thread_id)["nodes"].values()) == {"done"}


def test_state_on_a_missing_real_store_raises_and_creates_nothing(env, tmp_path):
    with pytest.raises(ValueError, match="no checkpoint for thread deadbeef"):
        introspection.thread_state(COMPLETE, "deadbeef")
    assert list(tmp_path.iterdir()) == []


def test_topology_failure_degrades_to_null_topology(env, shared_saver, monkeypatch, tmp_path):
    thread_id = _run_complete(monkeypatch, tmp_path)

    def boom(drawn):
        raise ValueError("topology failed")

    monkeypatch.setattr(introspection, "graph_topology", boom)
    result = introspection.thread_state(COMPLETE, thread_id)
    assert result["topology"] is None
    assert result["traversed"] is None
    assert set(result["nodes"].values()) == {"done"}


# `traversed`: the topology edges a thread actually took (ADR-0075).

FIXTURES = "homer_langgraph.tests.graph_fixtures"


def _traversed_state(monkeypatch, tmp_path, graph: str, value=None) -> dict:
    module = f"{FIXTURES}:{graph}"
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": value}))
    assert runner.run(module) == 0
    return introspection.thread_state(module, _thread_id(tmp_path))


def _pairs(result: dict) -> list[tuple[str, str]]:
    pairs = [(e["source"], e["target"]) for e in result["traversed"]]
    edges = {(e["source"], e["target"]) for e in result["topology"]["edges"]}
    assert set(pairs) <= edges, "traversed must only name topology edges"
    assert pairs == sorted(set(pairs)), "traversed must be deduplicated and sorted"
    return pairs


def test_traversed_linear_thread_covers_start_to_end(env, shared_saver, monkeypatch, tmp_path):
    result = _traversed_state(monkeypatch, tmp_path, "linear_graph")
    assert _pairs(result) == [
        ("__start__", "first"),
        ("first", "second"),
        ("second", "__end__"),
    ]


def test_traversed_router_has_only_the_chosen_branch(env, shared_saver, monkeypatch, tmp_path):
    result = _traversed_state(monkeypatch, tmp_path, "router_graph", value="left")
    assert _pairs(result) == [
        ("__start__", "route"),
        ("left", "__end__"),
        ("route", "left"),
    ]


def test_traversed_command_goto_has_only_the_chosen_target(
    env, shared_saver, monkeypatch, tmp_path
):
    result = _traversed_state(monkeypatch, tmp_path, "command_graph", value="right")
    pairs = _pairs(result)
    assert ("pick", "right") in pairs
    assert ("pick", "left") not in pairs
    assert ("right", "__end__") in pairs


def test_traversed_self_loop_keeps_the_self_edge(env, shared_saver, monkeypatch, tmp_path):
    result = _traversed_state(monkeypatch, tmp_path, "self_loop_graph", value=0)
    assert _pairs(result) == [
        ("__start__", "plan"),
        ("finish", "__end__"),
        ("plan", "finish"),
        ("plan", "plan"),
    ]


def test_traversed_join_has_both_branches_and_both_join_edges(
    env, shared_saver, monkeypatch, tmp_path
):
    result = _traversed_state(monkeypatch, tmp_path, "join_graph")
    assert _pairs(result) == [
        ("__start__", "enter"),
        ("a", "join"),
        ("b", "join"),
        ("enter", "a"),
        ("enter", "b"),
        ("join", "__end__"),
    ]


def test_traversed_send_fanout_reaches_the_send_target(env, shared_saver, monkeypatch, tmp_path):
    result = _traversed_state(monkeypatch, tmp_path, "send_graph")
    assert _pairs(result) == [
        ("__start__", "fan"),
        ("fan", "work"),
        ("work", "__end__"),
    ]


def test_traversed_stops_at_a_parked_node(
    env, shared_saver, captured_runs, monkeypatch, tmp_path
):
    result = _traversed_state(monkeypatch, tmp_path, "gated_graph")
    assert result["nodes"]["gate"] == "parked"
    assert _pairs(result) == [("__start__", "prep"), ("prep", "gate")]


def test_traversed_continues_past_a_resumed_node(
    env, shared_saver, captured_runs, monkeypatch, tmp_path
):
    module = f"{FIXTURES}:gated_graph"
    _traversed_state(monkeypatch, tmp_path, "gated_graph")
    thread = _thread_id(tmp_path)
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_RESUME, "yes")
    assert runner.run(module) == 0

    assert _pairs(introspection.thread_state(module, thread)) == [
        ("__start__", "prep"),
        ("finish", "__end__"),
        ("gate", "finish"),
        ("prep", "gate"),
    ]


def test_traversed_failure_degrades_to_null(env, shared_saver, monkeypatch, tmp_path):
    thread_id = _run_complete(monkeypatch, tmp_path)

    def boom(*args, **kwargs):
        raise ValueError("traversed failed")

    monkeypatch.setattr(introspection, "traversed_edges", boom)
    result = introspection.thread_state(COMPLETE, thread_id)
    assert result["traversed"] is None
    assert result["topology"] is not None
    assert set(result["nodes"].values()) == {"done"}


def test_traversed_is_null_when_no_routing_channel_is_recognized(
    env, shared_saver, monkeypatch, tmp_path
):
    """A renamed routing channel must read as unknown, not as every node ending at END."""
    original = introspection.traversed_edges

    def renamed(topology, tasks, checkpoints):
        return original(
            topology,
            tasks,
            [
                SimpleNamespace(
                    pending_writes=[
                        (task_id, channel.replace("branch:to:", "edge:to:"), value)
                        for task_id, channel, value in checkpoint.pending_writes or ()
                    ]
                )
                for checkpoint in checkpoints
            ],
        )

    monkeypatch.setattr(introspection, "traversed_edges", renamed)
    result = _traversed_state(monkeypatch, tmp_path, "router_graph", value="left")
    assert result["topology"] is not None
    assert result["traversed"] is None


# Direct `traversed_edges` cases: the checkpoint store is agent-writable, so a
# forged write may only ever name a real topology edge (root CLAUDE.md §12).


def _topology(edges: list[tuple[str, str]]) -> dict:
    ids = sorted({node for edge in edges for node in edge})
    return {
        "nodes": [{"id": node} for node in ids],
        "edges": [{"source": s, "target": t} for s, t in edges],
    }


def _traversed(edges, tasks, *writes) -> list[tuple[str, str]]:
    checkpoints = [SimpleNamespace(pending_writes=list(writes))]
    return [
        (e["source"], e["target"])
        for e in introspection.traversed_edges(_topology(edges), tasks, checkpoints)
    ]


ROUTE_EDGES = [("__start__", "a"), ("a", "b"), ("b", "__end__")]
ROUTER = {"t1": ("a", False)}


@pytest.mark.parametrize(
    "write",
    [
        pytest.param(("t1", "branch:to:ghost", None), id="branch-to-non-node"),
        pytest.param(("t1", "branch:to:__end__", None), id="branch-to-non-edge"),
        pytest.param(("t1", "__pregel_tasks", Send("ghost", {})), id="send-to-non-node"),
        pytest.param(("t1", "__pregel_tasks", Send("constructor", {})), id="send-constructor"),
        pytest.param(("t1", "__pregel_tasks", Send("__proto__", {})), id="send-proto"),
        pytest.param(("t1", "__pregel_tasks", Send(["b"], {})), id="send-non-str-node"),
        pytest.param(("t1", b"branch:to:b", None), id="non-str-channel"),
        pytest.param(("t9", "branch:to:b", None), id="unknown-task-id"),
        pytest.param(("t1", "join:a:ghost", None), id="join-matching-no-node"),
        pytest.param(("t1", "branch:to:" + "z" * 300, None), id="channel-too-long"),
        pytest.param(
            ("t1", "__pregel_tasks", Send("z" * 300, {})), id="send-node-too-long"
        ),
    ],
)
def test_traversed_edges_drops_writes_that_are_not_topology_edges(write):
    assert _traversed(ROUTE_EDGES, ROUTER, write) == []


def test_traversed_edges_stops_at_a_giant_history():
    """A forged checkpoint history is scanned only up to the caps.

    The store is agent-writable, so the scan must consume a bounded prefix and
    return rather than walk a history sized to exhaust CPU/memory.
    """
    total_checkpoints = 10_000_000
    consumed = 0

    def giant():
        nonlocal consumed
        for _ in range(total_checkpoints):
            consumed += 1
            yield SimpleNamespace(
                pending_writes=[("t1", "branch:to:b", None)] * introspection.MAX_TRAVERSED_WRITES
            )

    result = introspection.traversed_edges(_topology(ROUTE_EDGES), ROUTER, giant())

    assert result == [{"source": "a", "target": "b"}]
    # Bounded prefix only: far below the supplied history, and within the caps.
    assert consumed < total_checkpoints
    assert consumed <= introspection.MAX_TRAVERSED_CHECKPOINTS


def test_traversed_edges_stops_at_write_less_checkpoints():
    """Write-less checkpoints are bounded by the checkpoint cap.

    A history whose checkpoints carry no pending writes must not be walked in
    full just because the write counter never advances.
    """
    total_checkpoints = 10_000_000
    consumed = 0

    def empty():
        nonlocal consumed
        for _ in range(total_checkpoints):
            consumed += 1
            yield SimpleNamespace(pending_writes=[])

    result = introspection.traversed_edges(
        _topology(ROUTE_EDGES), {"t1": ("a", True)}, empty()
    )

    # No routing write is recognized, so the field reads as unknown.
    assert result is None
    # The cap is tested before the pull, so the store is never asked past it.
    assert consumed == introspection.MAX_TRAVERSED_CHECKPOINTS
    assert consumed < total_checkpoints


def test_traversed_edges_stops_at_write_cap():
    """The write cap is its own bound, not a side effect of the checkpoint cap.

    Each checkpoint carries well under the cap in writes, so the loop can only
    stop early because the write counter tripped: reverting it would leave this
    red while the checkpoint-cap tests stay green.
    """
    checkpoints_per_batch = 100
    total_checkpoints = 10_000_000
    writes_consumed = 0

    def counting():
        nonlocal writes_consumed
        for _ in range(total_checkpoints):
            writes = [("t1", "branch:to:b", None)] * (
                introspection.MAX_TRAVERSED_WRITES // checkpoints_per_batch
            )
            writes_consumed += len(writes)
            yield SimpleNamespace(pending_writes=writes)

    result = introspection.traversed_edges(_topology(ROUTE_EDGES), ROUTER, counting())

    assert result == [{"source": "a", "target": "b"}]
    assert writes_consumed <= introspection.MAX_TRAVERSED_WRITES + (
        introspection.MAX_TRAVERSED_WRITES // checkpoints_per_batch
    )
    assert writes_consumed < total_checkpoints * (
        introspection.MAX_TRAVERSED_WRITES // checkpoints_per_batch
    )


def test_traversed_edges_limits_the_saver_list_read(
    env, shared_saver, monkeypatch, tmp_path
):
    """The store read is bounded at the SQL side, not only inside the scan.

    A cap enforced only after the saver has materialized a tuple still pays its
    deserialization cost per poll, so the `saver.list` call must pass the
    checkpoint cap as its own `limit` (§12 rule 4).
    """
    thread_id = _run_complete(monkeypatch, tmp_path)

    seen_limits = []
    real_list = shared_saver.list

    def recording_list(config, *, filter=None, before=None, limit=None):
        seen_limits.append(limit)
        return real_list(config, filter=filter, before=before, limit=limit)

    monkeypatch.setattr(shared_saver, "list", recording_list)
    result = introspection.thread_state(COMPLETE, thread_id)

    assert result["traversed"] == [
        {"source": "__start__", "target": "n"},
        {"source": "n", "target": "__end__"},
    ]
    # The `traversed` read passes the cap; the unbounded `get_state_history`
    # fold (ADR-0044) is the other caller, with no limit.
    assert seen_limits.count(introspection.MAX_TRAVERSED_CHECKPOINTS) == 1


def test_traversed_edges_join_at_bound_is_kept():
    """A legitimate self-join writes the longest id on both sides of the bound.

    A join edge whose target is also a source (`add_edge([big, "s"], big)`)
    produces exactly this channel; a bound sized only for `branch:to:` drops
    it, and the branch then reads as ended.
    """
    long_id = "s" * 24
    edges = [("s", long_id), (long_id, long_id)]
    tasks = {"t1": (long_id, True), "t2": ("s", True)}
    join = f"join:{long_id}+s:{long_id}"
    assert _traversed(edges, tasks, ("t1", join, None), ("t2", join, None)) == [
        ("s", long_id),
        (long_id, long_id),
    ]


def test_traversed_edges_join_over_bound_is_dropped():
    """Over the bound, a `join:` channel can only be forged, so it is dropped."""
    long_id = "s" * 24
    edges = [("s", long_id), (long_id, long_id)]
    tasks = {"t1": (long_id, False)}
    join = f"join:{'z' * 40}+{long_id}:{long_id}"
    assert _traversed(edges, tasks, ("t1", join, None)) == []


def test_traversed_edges_resolves_join_targets_with_colon_and_plus():
    edges = [("a", "x:y+z"), ("w+v", "x:y+z")]
    tasks = {"t1": ("a", True), "t2": ("w+v", True)}
    assert _traversed(
        edges,
        tasks,
        ("t1", "join:a+w+v:x:y+z", None),
        ("t2", "join:a+w+v:x:y+z", None),
    ) == [("a", "x:y+z"), ("w+v", "x:y+z")]


def test_traversed_edges_join_target_must_be_an_edge_of_the_writer():
    """`join:a+p:q:join` ends with both `:join` and `:q:join`; `a` only feeds `join`."""
    edges = [("a", "join"), ("p:q", "join"), ("x", "q:join")]
    tasks = {"t1": ("a", True), "t2": ("p:q", True)}
    assert _traversed(
        edges,
        tasks,
        ("t1", "join:a+p:q:join", None),
        ("t2", "join:a+p:q:join", None),
    ) == [("a", "join"), ("p:q", "join")]


def test_traversed_edges_is_none_when_no_routing_write_is_recognized():
    """Without a single recognized write, every completed task would look like it ended at END."""
    edges = [("__start__", "x"), ("x", "b"), ("x", "__end__")]
    tasks = {"t0": ("__start__", True), "t1": ("x", True)}
    checkpoints = [
        SimpleNamespace(pending_writes=[("t0", "edge:to:x", None), ("t1", "edge:to:b", None)])
    ]
    assert introspection.traversed_edges(_topology(edges), tasks, checkpoints) is None
