"""Runner branch tests: fresh vs resume, human/fanout/complete paths.

Uses LangGraph's in-memory ``MemorySaver`` (patched in for ``SqliteSaver``) and
the tiny graphs in ``graph_fixtures``. ``subprocess.run`` is mocked so wrapper
argv can be asserted without real Homer wrappers.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest

from homer_langgraph import __main__, runner
from homer_langgraph.tests import graph_fixtures

HUMAN = "homer_langgraph.tests.graph_fixtures:human_graph"
FANOUT = "homer_langgraph.tests.graph_fixtures:fanout_graph"
COMPLETE = "homer_langgraph.tests.graph_fixtures:complete_graph"
MULTI = "homer_langgraph.tests.graph_fixtures:multi_interrupt_graph"
TWO_HUMAN = "homer_langgraph.tests.graph_fixtures:two_human_graph"
THREE_HUMAN = "homer_langgraph.tests.graph_fixtures:three_human_graph"
TWO_FANOUT = "homer_langgraph.tests.graph_fixtures:two_fanout_graph"
THREE_FANOUT_GROUPED = "homer_langgraph.tests.graph_fixtures:three_parallel_fanout_graph"
RETRY_FANOUT = "homer_langgraph.tests.graph_fixtures:retry_fanout_graph"
MIXED_RETRY_FANOUT = "homer_langgraph.tests.graph_fixtures:mixed_retry_fanout_graph"
STATEFUL_RETRY_FANOUT = (
    "homer_langgraph.tests.graph_fixtures:stateful_retry_fanout_graph"
)
THREE_RETRY_FANOUT = "homer_langgraph.tests.graph_fixtures:three_retry_fanout_graph"
THREE_RETRY_FANOUT_GROUPED = (
    "homer_langgraph.tests.graph_fixtures:three_retry_fanout_grouped_graph"
)
FANOUT_CHECK = "homer_langgraph.tests.graph_fixtures:fanout_check_graph"


def _flag_pairs(argv, flag):
    """Parse all ``<flag> k=v`` pairs from a wrapper argv into a dict."""
    return dict(
        argv[i + 1].split("=", 1)
        for i, a in enumerate(argv)
        if a == flag and "=" in argv[i + 1]
    )


def _params(argv):
    """Parse all ``--param k=v`` pairs from a wrapper argv into a dict."""
    return _flag_pairs(argv, "--param")


def _thread_and_iid(argv):
    """Extract (thread_id, interrupt_id) from a captured human/fanout argv.

    The interrupt id must reach the resumed agent: for the human gate that is
    the dispatch target (``--param``); for fan-out it is the continuation
    (``--continue-param``), so a plain ``--param`` would be lost on the sub.
    """
    if argv[0] == "homer-ask":
        params = _params(argv)
        return params["HMR_LG_THREAD"], params["HMR_LG_INTERRUPT"]
    # homer-dispatch: thread + interrupt id ride the continuation channel.
    iid = _flag_pairs(argv, "--continue-param")["HMR_LG_INTERRUPT"]
    return argv[argv.index("--continue-thread") + 1], iid


class _StubGraph:
    """Compiled-graph stand-in that records its exact invoke input and completes.

    ``get_state`` reports a pending checkpoint, so recover's ``graph_input=None``
    contract can be asserted directly (a real-graph re-park cannot distinguish
    None from a stale fresh input landing on the same thread — both re-park and
    exit 0).
    """

    def __init__(self):
        self.inputs = []

    def get_state(self, config):
        return SimpleNamespace(created_at="t", next=("n",), values={}, tasks=())

    def invoke(self, graph_input, config):
        self.inputs.append(graph_input)
        return {}


@pytest.fixture
def stub_graph(monkeypatch):
    """Patch the runner to load, compile and checkpoint into one ``_StubGraph``."""
    stub = _StubGraph()
    monkeypatch.setattr(runner, "SqliteSaver", lambda conn: object())
    monkeypatch.setattr(runner, "_compile", lambda graph, saver: stub)
    monkeypatch.setattr(runner, "_load_graph", lambda module: object())
    return stub


@pytest.fixture
def captured_calls(monkeypatch):
    """Like captured_runs, but records (argv, kwargs) so a join's stdin body
    (passed via input=...) can be asserted, not just the argv."""
    calls = []
    monkeypatch.setattr(
        runner.subprocess, "run", lambda argv, **kw: calls.append((argv, kw)) or None
    )
    return calls


def test_fresh_run_human_gate(env, shared_saver, captured_runs, monkeypatch):
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))

    rc = runner.run(module=HUMAN)

    assert rc == 0
    assert len(captured_runs) == 1
    argv = captured_runs[0]
    assert argv[:3] == ["homer-ask", "ask-and-dispatch", "Proceed?"]
    assert "--agent" in argv and argv[argv.index("--agent") + 1] == "planner"
    assert argv[argv.index("--answer-param") + 1] == "HMR_LG_RESUME"
    # Thread id carried through as a --param HMR_LG_THREAD=<id>.
    thread_param = argv[argv.index("--param") + 1]
    assert thread_param.startswith("HMR_LG_THREAD=")
    assert "--option" in argv and "--ttl" in argv
    assert argv[argv.index("--ttl") + 1] == "120"


def test_fresh_run_fanout_gate(env, shared_saver, captured_runs):
    rc = runner.run(module=FANOUT)

    assert rc == 0
    argv = captured_runs[0]
    assert argv[0] == "homer-dispatch"
    assert argv[argv.index("--agent") + 1] == "worker"
    assert argv[argv.index("--continue-agent") + 1] == "planner"
    assert argv[argv.index("--resume-param") + 1] == "HMR_LG_RESUME"
    assert argv[argv.index("--result-jsonpath") + 1] == "$.summary"
    # The sub's --param carries the fan-out topic, NOT the interrupt id.
    sub_params = [argv[i + 1] for i, a in enumerate(argv) if a == "--param"]
    assert "topic=pricing" in sub_params
    assert not any(p.startswith("HMR_LG_INTERRUPT=") for p in sub_params)
    # The interrupt id rides the continuation channel so it reaches the resumed parent.
    assert _flag_pairs(argv, "--continue-param")["HMR_LG_INTERRUPT"]
    # continue-thread present and matches a real thread id (non-empty).
    assert argv[argv.index("--continue-thread") + 1]


def test_fanout_interrupt_id_rides_continuation_channel_not_param(
    env, shared_saver, captured_runs
):
    # Regression: the interrupt id must reach the RESUMED PARENT (the continuation),
    # not the sub. A plain --param lands on the sub and is dropped, so on resume the
    # parent has no HMR_LG_INTERRUPT and LangGraph rejects a scalar resume while
    # several interrupts are still pending ("multiple pending interrupts").
    runner.run(module=FANOUT)
    argv = captured_runs[0]
    assert argv[0] == "homer-dispatch"
    assert "HMR_LG_INTERRUPT" not in _params(argv)
    assert _flag_pairs(argv, "--continue-param")["HMR_LG_INTERRUPT"]


def test_completed_graph_prints_and_shells_nothing(
    env, shared_saver, captured_runs, capsys
):
    rc = runner.run(module=COMPLETE)

    assert rc == 0
    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert out["answer"] == "done"


def test_fresh_run_reads_default_input_env(env, shared_saver, captured_runs, capsys, monkeypatch):
    # With no --input-env, the fresh-run input is read from HMR_LG_INPUT.
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 42}))

    rc = runner.run(module=COMPLETE)

    assert rc == 0
    out = json.loads(capsys.readouterr().out)
    assert out["value"] == 42


def test_fresh_run_reads_custom_input_env(env, shared_saver, captured_runs, capsys, monkeypatch):
    # A custom --input-env makes the runner read the graph input from that var;
    # the reserved HMR_LG_INPUT is ignored.
    monkeypatch.setenv("GRAPH_INPUT", json.dumps({"value": 99}))
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))

    rc = runner.run(module=COMPLETE, input_env="GRAPH_INPUT")

    assert rc == 0
    out = json.loads(capsys.readouterr().out)
    assert out["value"] == 99


def test_resume_human_gate_with_command(env, shared_saver, captured_runs, monkeypatch):
    # Segment 1: fresh run parks on the human gate, capturing the thread id.
    runner.run(module=HUMAN)
    thread_param = captured_runs[0][captured_runs[0].index("--param") + 1]
    thread_id = thread_param.split("=", 1)[1]

    # Segment 2: resume the same thread with the operator's answer.
    captured_runs.clear()
    monkeypatch.setenv(runner.ENV_THREAD, thread_id)
    monkeypatch.setenv(runner.ENV_RESUME, "yes")

    rc = runner.run(module=HUMAN)

    assert rc == 0
    # Graph completed on resume → no further wrapper call, final state printed.
    assert captured_runs == []


def test_parked_path_keeps_stdout_clean(env, shared_saver, captured_runs, capsys):
    # On the parked path, stdout must stay empty (a result-env mapping over this
    # segment would otherwise see prose); the status line goes to stderr.
    rc = runner.run(module=HUMAN)

    assert rc == 0
    captured = capsys.readouterr()
    assert captured.out == ""
    assert "parked thread" in captured.err


def test_multiple_interrupts_resolves_one_and_reports(
    env, shared_saver, captured_runs, capsys
):
    rc = runner.run(module=MULTI)

    assert rc == 0
    # Resolves exactly one pending interrupt this segment.
    assert len(captured_runs) == 1
    assert captured_runs[0][0] == "homer-ask"
    err = capsys.readouterr().err
    assert "resolving interrupt" in err
    assert "(1 of 2)" in err


def test_compile_rejects_non_graph(env, captured_runs, monkeypatch):
    # A --module pointing at something that is neither a StateGraph nor a
    # compiled graph fails early with a clear TypeError, not deep inside invoke.
    monkeypatch.setattr(runner, "SqliteSaver", lambda conn: object())
    monkeypatch.setattr(runner, "_load_graph", lambda module: object())

    with pytest.raises(TypeError, match="not a StateGraph or compiled graph"):
        runner.run(module="anything")


def test_cli_passes_input_env_to_runner(monkeypatch):
    # The run subcommand forwards --input-env to runner.run; default is HMR_LG_INPUT.
    calls = []
    monkeypatch.setattr(__main__.runner, "run", lambda **kw: calls.append(kw) or 0)

    __main__.main(["run", "--module", "g"])
    __main__.main(["run", "--module", "g", "--input-env", "GRAPH_INPUT"])

    assert calls[0] == {"module": "g", "input_env": "HMR_LG_INPUT"}
    assert calls[1] == {"module": "g", "input_env": "GRAPH_INPUT"}


def test_human_gate_argv_carries_interrupt_id(env, shared_saver, captured_runs):
    runner.run(module=HUMAN)
    params = _params(captured_runs[0])
    assert params["HMR_LG_INTERRUPT"]
    # Both the thread and the interrupt id ride along as params.
    assert params["HMR_LG_THREAD"]


def test_two_interrupts_resolve_sequentially(
    env, shared_saver, captured_runs, monkeypatch
):
    # Segment 1: fresh run parks; one interrupt resolved, the other left pending.
    runner.run(module=TWO_HUMAN)
    assert len(captured_runs) == 1
    thread, iid1 = _thread_and_iid(captured_runs[0])

    # Segment 2: resume that interrupt -> graph re-parks on the OTHER one.
    captured_runs.clear()
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_INTERRUPT, iid1)
    monkeypatch.setenv(runner.ENV_RESUME, "ans1")
    runner.run(module=TWO_HUMAN)
    assert len(captured_runs) == 1
    thread2, iid2 = _thread_and_iid(captured_runs[0])
    assert thread2 == thread
    assert iid2 != iid1  # the still-pending interrupt, not the resolved one

    # Segment 3: resume the second -> graph completes, no further park.
    captured_runs.clear()
    monkeypatch.setenv(runner.ENV_INTERRUPT, iid2)
    monkeypatch.setenv(runner.ENV_RESUME, "ans2")
    rc = runner.run(module=TWO_HUMAN)
    assert rc == 0
    assert captured_runs == []


def test_three_interrupt_lifecycle_one_gate_per_segment(
    env, shared_saver, captured_runs, monkeypatch
):
    seen_ids = []
    thread = None
    # Three parking segments: exactly one gate registered each, ids all distinct.
    for i in range(3):
        rc = runner.run(module=THREE_HUMAN)
        assert rc == 0
        assert len(captured_runs) == 1  # no double-registration
        thread, iid = _thread_and_iid(captured_runs[0])
        seen_ids.append(iid)
        captured_runs.clear()
        monkeypatch.setenv(runner.ENV_THREAD, thread)
        monkeypatch.setenv(runner.ENV_INTERRUPT, iid)
        monkeypatch.setenv(runner.ENV_RESUME, f"ans{i}")

    assert len(set(seen_ids)) == 3  # each segment targeted a different interrupt

    # Fourth segment resumes the last -> graph completes.
    rc = runner.run(module=THREE_HUMAN)
    assert rc == 0
    assert captured_runs == []


def test_resume_builds_map_when_interrupt_id_set(env, stub_graph, monkeypatch):
    monkeypatch.setenv(runner.ENV_THREAD, "t1")
    monkeypatch.setenv(runner.ENV_INTERRUPT, "iid-9")
    monkeypatch.setenv(runner.ENV_RESUME, "the-answer")

    rc = runner.run(module="x")

    assert rc == 0
    cmd = stub_graph.inputs[0]
    assert isinstance(cmd, runner.Command)
    assert cmd.resume == {"iid-9": "the-answer"}


def test_resume_scalar_form_when_interrupt_id_unset(env, stub_graph, monkeypatch):
    monkeypatch.setenv(runner.ENV_THREAD, "t1")
    monkeypatch.setenv(runner.ENV_RESUME, "the-answer")
    # No HMR_LG_INTERRUPT -> back-compat scalar resume.

    rc = runner.run(module="x")

    assert rc == 0
    cmd = stub_graph.inputs[0]
    assert isinstance(cmd, runner.Command)
    assert cmd.resume == "the-answer"


def test_interrupt_selection_picks_smallest_id_independent_of_order():
    # Selection is a pure function of the interrupt ids — smallest id wins,
    # regardless of insertion order or which thread parked them. (We can't assert
    # "same branch across fresh runs": LangGraph assigns non-deterministic ids per
    # fresh run, so the branch with the smallest id varies. The invariant that
    # matters is determinism given a fixed id set, which is what we test here.)
    a = SimpleNamespace(id="aaa", value={"type": "human"})
    b = SimpleNamespace(id="bbb", value={"type": "human"})
    c = SimpleNamespace(id="ccc", value={"type": "human"})

    assert runner._select_interrupt([b, a, c]) is a
    assert runner._select_interrupt([c, b, a]) is a
    assert runner._select_interrupt([a]) is a


def test_fanout_resume_decodes_map_value(
    env, shared_saver, captured_runs, capsys, monkeypatch
):
    # Drive two parallel fan-outs to completion; each branch's decoded resume
    # payload (a JSON object delivered as the map value) lands in state.
    runner.run(module=TWO_FANOUT)
    assert captured_runs[0][0] == "homer-dispatch"
    thread, iid1 = _thread_and_iid(captured_runs[0])

    captured_runs.clear()
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_INTERRUPT, iid1)
    monkeypatch.setenv(
        runner.ENV_RESUME,
        json.dumps({"status": "COMPLETED", "processId": 1, "result": "R1"}),
    )
    runner.run(module=TWO_FANOUT)
    _, iid2 = _thread_and_iid(captured_runs[0])
    assert iid2 != iid1

    captured_runs.clear()
    monkeypatch.setenv(runner.ENV_INTERRUPT, iid2)
    monkeypatch.setenv(
        runner.ENV_RESUME,
        json.dumps({"status": "COMPLETED", "processId": 2, "result": "R2"}),
    )
    rc = runner.run(module=TWO_FANOUT)

    assert rc == 0
    out = json.loads(capsys.readouterr().out)
    # Both branches decoded their map-value JSON into dicts in final state.
    results = {out["a"]["result"], out["b"]["result"]}
    assert results == {"R1", "R2"}


def test_grouped_fanout_emits_single_join(env, shared_saver, captured_calls):
    rc = runner.run(module=THREE_FANOUT_GROUPED)

    assert rc == 0
    # One side effect for the whole group: a single homer-join call, not 3 dispatches.
    assert len(captured_calls) == 1
    argv, kw = captured_calls[0]
    assert argv == ["homer-join"]
    body = json.loads(kw["input"])
    assert len(body["subs"]) == 3
    assert body["continueAgent"] == "planner"
    assert body["continueThread"]
    assert body["resumeParam"] == "HMR_LG_RESUME"
    # Each sub carries its agent and the interrupt id its result is keyed under.
    for sub in body["subs"]:
        assert sub["agent"].startswith("worker_")
        assert sub["interruptId"]
    # The comma-joined sorted interrupt ids ride continueParams as the join signal.
    ids = body["continueParams"]["HMR_LG_INTERRUPT"].split(",")
    assert len(ids) == 3
    assert ids == sorted(ids)
    assert set(ids) == {sub["interruptId"] for sub in body["subs"]}


def test_grouped_fanout_completes_in_one_join_round_trip(
    env, shared_saver, captured_calls, capsys, monkeypatch
):
    # Segment 1: fresh run parks; the group becomes ONE join over all 3 subs.
    runner.run(module=THREE_FANOUT_GROUPED)
    assert len(captured_calls) == 1
    _, kw = captured_calls[0]
    body = json.loads(kw["input"])
    thread = body["continueThread"]
    joined_ids = body["continueParams"]["HMR_LG_INTERRUPT"]
    id_list = joined_ids.split(",")

    # Segment 2: resume ONCE with the full {id: per_sub_payload_string} map. Each
    # per-sub payload is the byte-for-byte single-fanout JSON, kept as a string.
    captured_calls.clear()
    resume_map = {
        iid: json.dumps({"status": "COMPLETED", "processId": n, "result": f"R{n}"})
        for n, iid in enumerate(id_list)
    }
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_INTERRUPT, joined_ids)  # comma -> join resume
    monkeypatch.setenv(runner.ENV_RESUME, json.dumps(resume_map))

    rc = runner.run(module=THREE_FANOUT_GROUPED)

    assert rc == 0
    # All three interrupts resolved in this single invoke: graph completed, no
    # further wrapper call.
    assert captured_calls == []
    out = json.loads(capsys.readouterr().out)
    results = {out["a"]["result"], out["b"]["result"], out["c"]["result"]}
    assert results == {"R0", "R1", "R2"}


def test_join_resume_decodes_full_map(env, stub_graph, monkeypatch):
    # A comma-joined HMR_LG_INTERRUPT signals a join resume: HMR_LG_RESUME is the
    # full map, passed straight to Command(resume=...).
    monkeypatch.setenv(runner.ENV_THREAD, "t1")
    monkeypatch.setenv(runner.ENV_INTERRUPT, "int-a,int-b")
    monkeypatch.setenv(runner.ENV_RESUME, json.dumps({"int-a": "v1", "int-b": "v2"}))

    rc = runner.run(module="x")

    assert rc == 0
    cmd = stub_graph.inputs[0]
    assert isinstance(cmd, runner.Command)
    assert cmd.resume == {"int-a": "v1", "int-b": "v2"}


def test_ungrouped_parallel_fanouts_stay_sequential(env, shared_saver, captured_calls):
    # Regression: without a group, parallel fanouts still resolve one-per-segment
    # via homer-dispatch (today's path), never a join.
    rc = runner.run(module=TWO_FANOUT)

    assert rc == 0
    assert len(captured_calls) == 1
    argv, _ = captured_calls[0]
    assert argv[0] == "homer-dispatch"


def test_real_sqlite_saver_writes_checkpoint(env, captured_runs, capsys, tmp_path):
    # NO shared_saver here: this exercises the real SqliteSaver, which requires
    # check_same_thread=False because LangGraph writes the checkpoint from a
    # worker thread. Dropping that flag would crash here while every mocked
    # test still passed.
    rc = runner.run(module=HUMAN)

    assert rc == 0
    assert len(captured_runs) == 1
    assert (tmp_path / "checkpoints.sqlite").exists()


def test_fresh_run_writes_identity_artifact(env, shared_saver, monkeypatch, tmp_path):
    artifacts = tmp_path / "artifacts"
    monkeypatch.setenv(runner.ENV_ARTIFACTS_DIR, str(artifacts))
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))
    assert runner.run(COMPLETE) == 0
    data = json.loads((artifacts / runner.ARTIFACT_FILENAME).read_text())
    assert list(data) == ["threadId", "module", "segment"]
    assert data["module"] == COMPLETE
    assert data["segment"] == "fresh"
    assert data["threadId"]


def test_artifact_skipped_without_artifacts_dir(env, shared_saver, monkeypatch):
    monkeypatch.delenv(runner.ENV_ARTIFACTS_DIR, raising=False)
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))
    assert runner.run(COMPLETE) == 0  # must not raise


def test_resume_segment_marks_artifact_resume(
    env, shared_saver, monkeypatch, tmp_path, captured_runs
):
    # Park a human-gate graph, then resume it; the resume segment overwrites
    # the artifact with segment="resume" and the SAME thread id.
    artifacts = tmp_path / "artifacts"
    monkeypatch.setenv(runner.ENV_ARTIFACTS_DIR, str(artifacts))
    assert runner.run(HUMAN) == 0
    first = json.loads((artifacts / runner.ARTIFACT_FILENAME).read_text())
    thread_id, _ = _thread_and_iid(captured_runs[0])
    monkeypatch.setenv(runner.ENV_THREAD, thread_id)
    monkeypatch.setenv(runner.ENV_RESUME, "yes")
    assert runner.run(HUMAN) == 0
    second = json.loads((artifacts / runner.ARTIFACT_FILENAME).read_text())
    assert second["segment"] == "resume"
    assert second["threadId"] == first["threadId"] == thread_id


def test_recover_invokes_with_none(env, stub_graph, monkeypatch):
    monkeypatch.setenv(runner.ENV_THREAD, "t1")
    monkeypatch.setenv(runner.ENV_RECOVER, "1")
    monkeypatch.delenv(runner.ENV_RESUME, raising=False)

    rc = runner.run(module="x")

    assert rc == 0
    assert stub_graph.inputs == [None]


def test_recover_unknown_thread_exits_1(env, shared_saver, monkeypatch, capsys):
    monkeypatch.setenv(runner.ENV_THREAD, "deadbeef")
    monkeypatch.setenv(runner.ENV_RECOVER, "1")
    assert runner.run(COMPLETE) == 1
    assert "no checkpoint" in capsys.readouterr().err


def test_recover_completed_thread_idempotent(
    env, shared_saver, monkeypatch, capsys, tmp_path
):
    monkeypatch.setenv(runner.ENV_ARTIFACTS_DIR, str(tmp_path / "a"))
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 7}))
    assert runner.run(COMPLETE) == 0
    thread_id = json.loads(
        (Path(os.environ[runner.ENV_ARTIFACTS_DIR]) / runner.ARTIFACT_FILENAME).read_text()
    )["threadId"]
    capsys.readouterr()  # drop first segment's stdout
    monkeypatch.setenv(runner.ENV_THREAD, thread_id)
    monkeypatch.setenv(runner.ENV_RECOVER, "1")
    assert runner.run(COMPLETE) == 0
    out = json.loads(capsys.readouterr().out)
    assert out  # final state printed again, no re-execution error


def _agent_of(argv):
    """The sub-agent a captured ``homer-dispatch`` argv targets."""
    return argv[argv.index("--agent") + 1]


def _park_retry_fanout(module, captured_runs):
    """Fresh segment of a retrying fan-out graph; returns (thread, interrupt id)."""
    assert runner.run(module=module) == 0
    assert captured_runs[-1][0] == "homer-dispatch"
    return _thread_and_iid(captured_runs[-1])


def _resume(monkeypatch, thread, iid, payload):
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_INTERRUPT, iid)
    monkeypatch.setenv(runner.ENV_RESUME, payload)


def test_fanout_retries_a_failed_sub_and_continues(
    env, shared_saver, captured_runs, capsys, monkeypatch
):
    # Segment 1: fresh run parks on the fan-out.
    thread, iid = _park_retry_fanout(RETRY_FANOUT, captured_runs)

    # Segment 2: the sub comes back FAILED -> the node re-parks and the runtime
    # dispatches the SAME sub again, under the same interrupt id.
    captured_runs.clear()
    _resume(monkeypatch, thread, iid, json.dumps({"status": "FAILED", "processId": 1, "result": None}))
    assert runner.run(module=RETRY_FANOUT) == 0
    assert len(captured_runs) == 1
    retry_argv = captured_runs[0]
    assert _agent_of(retry_argv) == "worker"
    assert _flag_pairs(retry_argv, "--continue-param")["HMR_LG_INTERRUPT"] == iid
    assert "homer_fanout: retrying worker attempt 1/2 after FAILED" in capsys.readouterr().err

    # Segment 3: the retried sub finishes -> the graph continues with its result.
    captured_runs.clear()
    _resume(monkeypatch, thread, iid, json.dumps({"status": "FINISHED", "processId": 2, "result": "R2"}))
    assert runner.run(module=RETRY_FANOUT) == 0
    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert out["answer"] == {"status": "FINISHED", "processId": 2, "result": "R2"}


def test_fanout_replayed_failure_stops_at_the_cap(
    env, shared_saver, captured_runs, capsys, monkeypatch
):
    # The ticket's shape: the SAME resume value is replayed (what a manual resume
    # does). Each replay re-parks and re-dispatches, up to `retries`; the third
    # replay hands the failure to the graph and no fourth dispatch happens.
    failed = json.dumps({"status": "FAILED", "processId": 1, "result": None})
    thread, iid = _park_retry_fanout(RETRY_FANOUT, captured_runs)
    captured_runs.clear()
    dispatches = 1

    for attempt in (1, 2):
        _resume(monkeypatch, thread, iid, failed)
        assert runner.run(module=RETRY_FANOUT) == 0
        assert len(captured_runs) == 1
        assert f"homer_fanout: retrying worker attempt {attempt}/2 after FAILED" in (
            capsys.readouterr().err
        )
        captured_runs.clear()
        dispatches += 1

    _resume(monkeypatch, thread, iid, failed)
    assert runner.run(module=RETRY_FANOUT) == 0
    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert out["answer"] == {"status": "FAILED", "processId": 1, "result": None}
    assert dispatches == 3  # initial dispatch + 2 retries, never a 4th


def test_fanout_retry_cap_is_not_taken_from_graph_state(
    env, shared_saver, captured_runs, capsys, monkeypatch
):
    # The node resets its own state-held counter on every run — the tampered state
    # value a retry budget must never be read from. The budget lives in the call's
    # validated arguments, so the cap still holds: one initial dispatch, one retry,
    # then the failure reaches the graph.
    thread, iid = _park_retry_fanout(STATEFUL_RETRY_FANOUT, captured_runs)
    failed = json.dumps({"status": "FAILED", "processId": 1, "result": None})

    captured_runs.clear()
    _resume(monkeypatch, thread, iid, failed)
    assert runner.run(module=STATEFUL_RETRY_FANOUT) == 0
    assert len(captured_runs) == 1
    capsys.readouterr()

    captured_runs.clear()
    _resume(monkeypatch, thread, iid, failed)
    assert runner.run(module=STATEFUL_RETRY_FANOUT) == 0
    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert out["answer"] == {"status": "FAILED", "processId": 1, "result": None}
    assert out["value"] == 0  # the counter the node reset, never consulted


def test_fanout_infra_and_failed_budgets_are_separate(
    env, shared_saver, captured_runs, capsys, monkeypatch
):
    # mixed_retry_fanout_graph: retries=1, infra_retries=1.
    thread, iid = _park_retry_fanout(MIXED_RETRY_FANOUT, captured_runs)

    # An UNKNOWN outcome spends the infrastructure budget only.
    captured_runs.clear()
    _resume(monkeypatch, thread, iid, json.dumps({"status": "UNKNOWN", "processId": 1, "result": None}))
    assert runner.run(module=MIXED_RETRY_FANOUT) == 0
    assert len(captured_runs) == 1
    assert "attempt 1/1 after UNKNOWN" in capsys.readouterr().err

    # The failure that follows still has its own, untouched budget.
    captured_runs.clear()
    _resume(monkeypatch, thread, iid, json.dumps({"status": "FAILED", "processId": 1, "result": None}))
    assert runner.run(module=MIXED_RETRY_FANOUT) == 0
    assert len(captured_runs) == 1
    assert "attempt 1/1 after FAILED" in capsys.readouterr().err

    captured_runs.clear()
    _resume(monkeypatch, thread, iid, json.dumps({"status": "FINISHED", "processId": 3, "result": "R3"}))
    assert runner.run(module=MIXED_RETRY_FANOUT) == 0
    assert captured_runs == []
    assert json.loads(capsys.readouterr().out)["answer"]["result"] == "R3"


def test_fanout_malformed_resume_stops_at_the_infra_cap(
    env, shared_saver, captured_runs, capsys, monkeypatch
):
    thread, iid = _park_retry_fanout(MIXED_RETRY_FANOUT, captured_runs)
    captured_runs.clear()
    _resume(monkeypatch, thread, iid, "{not json")

    # infra_retries=1 -> one re-dispatch, then the undecodable payload raises.
    assert runner.run(module=MIXED_RETRY_FANOUT) == 0
    assert len(captured_runs) == 1
    assert "attempt 1/1 after malformed payload" in capsys.readouterr().err

    captured_runs.clear()
    with pytest.raises(ValueError):
        runner.run(module=MIXED_RETRY_FANOUT)
    assert captured_runs == []  # the cap holds: no third dispatch


def test_ungrouped_retry_redispatches_only_the_failed_branch(
    env, shared_saver, captured_runs, capsys, monkeypatch
):
    # Three parallel fan-outs park; the runtime resolves one interrupt per segment.
    assert runner.run(module=THREE_RETRY_FANOUT) == 0
    assert len(captured_runs) == 1
    thread, iid = _thread_and_iid(captured_runs[0])
    first_agent = _agent_of(captured_runs[0])

    # That branch's sub fails: only it re-parks, the other two stay pending.
    captured_runs.clear()
    _resume(monkeypatch, thread, iid, json.dumps({"status": "FAILED", "processId": 1, "result": None}))
    assert runner.run(module=THREE_RETRY_FANOUT) == 0
    assert len(captured_runs) == 1
    assert _agent_of(captured_runs[0]) == first_agent

    # The retried sub finishes -> that branch completes and the runtime moves on to
    # another pending branch instead of retrying again.
    captured_runs.clear()
    _resume(monkeypatch, thread, iid, json.dumps({"status": "FINISHED", "processId": 2, "result": "R2"}))
    assert runner.run(module=THREE_RETRY_FANOUT) == 0
    assert len(captured_runs) == 1
    assert _agent_of(captured_runs[0]) != first_agent


def test_grouped_retry_redispatches_only_the_failed_member(
    env, shared_saver, captured_calls, capsys, monkeypatch
):
    # Segment 1: the three grouped fan-outs become ONE join.
    assert runner.run(module=THREE_RETRY_FANOUT_GROUPED) == 0
    argv, kw = captured_calls[0]
    assert argv == ["homer-join"]
    body = json.loads(kw["input"])
    thread = body["continueThread"]
    joined_ids = body["continueParams"]["HMR_LG_INTERRUPT"]
    failed_iid = next(s["interruptId"] for s in body["subs"] if s["agent"] == "worker_B")

    # Segment 2: one member comes back FAILED. The other two keep their results;
    # only the failed member re-parks, so the runtime dispatches that one sub.
    captured_calls.clear()
    resume_map = {
        s["interruptId"]: json.dumps(
            {
                "status": "FAILED" if s["interruptId"] == failed_iid else "FINISHED",
                "processId": 1,
                "result": s["agent"],
            }
        )
        for s in body["subs"]
    }
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_INTERRUPT, joined_ids)
    monkeypatch.setenv(runner.ENV_RESUME, json.dumps(resume_map))
    assert runner.run(module=THREE_RETRY_FANOUT_GROUPED) == 0
    assert len(captured_calls) == 1
    retry_argv, _ = captured_calls[0]
    assert retry_argv[0] == "homer-dispatch"
    assert _agent_of(retry_argv) == "worker_B"

    # Segment 3: the retried member finishes; the graph continues with all three.
    captured_calls.clear()
    retry_iid = _flag_pairs(retry_argv, "--continue-param")["HMR_LG_INTERRUPT"]
    monkeypatch.setenv(runner.ENV_INTERRUPT, retry_iid)
    monkeypatch.setenv(
        runner.ENV_RESUME, json.dumps({"status": "FINISHED", "processId": 9, "result": "B2"})
    )
    assert runner.run(module=THREE_RETRY_FANOUT_GROUPED) == 0
    assert captured_calls == []
    out = json.loads(capsys.readouterr().out)
    assert out["a"]["result"] == "worker_A"
    assert out["b"]["result"] == "B2"
    assert out["c"]["result"] == "worker_C"


def test_recover_reenters_a_thread_whose_resumed_node_raised(
    env, shared_saver, captured_runs, capsys, monkeypatch
):
    # Ticket implementation note: a node that raises after a resumed fan-out leaves
    # the thread with no `next` task, so recover must not read it as completed.
    assert runner.run(module=FANOUT_CHECK) == 0
    thread, iid = _thread_and_iid(captured_runs[0])

    graph_fixtures.FANOUT_CHECK_FAILURES["left"] = 1
    _resume(monkeypatch, thread, iid, json.dumps({"status": "FAILED", "processId": 1, "result": None}))
    with pytest.raises(RuntimeError, match="graph check rejected the sub"):
        runner.run(module=FANOUT_CHECK)

    # Recover re-runs the failed task: the node's check passes this time and the
    # thread completes — instead of printing an empty state and exiting 0.
    captured_runs.clear()
    monkeypatch.delenv(runner.ENV_RESUME)
    monkeypatch.setenv(runner.ENV_RECOVER, "1")
    assert runner.run(module=FANOUT_CHECK) == 0
    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert out["answer"] == {"status": "FAILED", "processId": 1, "result": None}
