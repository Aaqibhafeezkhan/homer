"""The bundled example agent's graph imports and runs end to end.

``example/agents/langgraph_demo`` ships its graph next to its ``agent.yaml``
and puts that directory on ``PYTHONPATH``; this drives it through the runtime
the same way: one join over the checks, then the approval gate.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from homer_langgraph import runner

DEMO_DIR = Path(__file__).resolve().parents[4] / "example" / "agents" / "langgraph_demo"


@pytest.fixture
def demo_path(monkeypatch):
    monkeypatch.syspath_prepend(str(DEMO_DIR))


@pytest.fixture
def calls(monkeypatch):
    recorded = []
    monkeypatch.setattr(
        runner.subprocess, "run", lambda argv, **kw: recorded.append((argv, kw)) or None
    )
    return recorded


def _park_on_approval(calls, monkeypatch) -> list[str]:
    runner.run(module="approval_flow")
    [(argv, kw)] = calls
    assert argv == ["homer-join"]
    body = json.loads(kw["input"])
    assert {sub["agent"] for sub in body["subs"]} == {"langgraph_demo_check"}
    ids = body["continueParams"]["HMR_LG_INTERRUPT"]
    resume = {
        iid: json.dumps({"status": "FINISHED", "processId": n, "result": "true"})
        for n, iid in enumerate(ids.split(","))
    }
    calls.clear()
    monkeypatch.setenv(runner.ENV_THREAD, body["continueThread"])
    monkeypatch.setenv(runner.ENV_INTERRUPT, ids)
    monkeypatch.setenv(runner.ENV_RESUME, json.dumps(resume))
    runner.run(module="approval_flow")
    [(argv, _)] = calls
    calls.clear()
    return argv


@pytest.mark.parametrize("answer, outcome", [("approve", "shipped"), ("reject", "rejected")])
def test_demo_graph_joins_checks_then_routes_on_the_answer(
    env, shared_saver, demo_path, calls, capsys, monkeypatch, answer, outcome
):
    ask = _park_on_approval(calls, monkeypatch)
    assert ask[:2] == ["homer-ask", "ask-and-dispatch"]
    assert ask[2] == "2/2 checks passed. Ship it?"

    params = dict(ask[i + 1].split("=", 1) for i, a in enumerate(ask) if a == "--param")
    monkeypatch.setenv(runner.ENV_INTERRUPT, params["HMR_LG_INTERRUPT"])
    monkeypatch.setenv(runner.ENV_RESUME, answer)
    capsys.readouterr()

    assert runner.run(module="approval_flow") == 0
    assert calls == []
    assert json.loads(capsys.readouterr().out)["outcome"] == outcome
