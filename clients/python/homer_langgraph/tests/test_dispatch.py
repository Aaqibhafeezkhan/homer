"""Fire-and-forget dispatch test: mock subprocess, assert argv and parse."""

from __future__ import annotations

import subprocess
from types import SimpleNamespace

from homer_langgraph import dispatch


def test_homer_dispatch_argv_and_pid(monkeypatch):
    calls = {}

    def fake_run(argv, check, capture_output, text):
        calls["argv"] = argv
        calls["check"] = check
        return SimpleNamespace(stdout="123\n")

    monkeypatch.setattr(subprocess, "run", fake_run)

    pid = dispatch.homer_dispatch("worker", params={"a": "1", "b": "2"})

    assert pid == 123
    assert calls["check"] is True
    assert calls["argv"] == [
        "homer-dispatch",
        "--agent",
        "worker",
        "--param",
        "a=1",
        "--param",
        "b=2",
    ]
    # No continuation flags on a fire-and-forget dispatch.
    for flag in ("--continue-agent", "--continue-thread", "--resume-param"):
        assert flag not in calls["argv"]


def test_homer_dispatch_no_params(monkeypatch):
    calls = {}
    def fake_run(argv, **kw):
        calls["argv"] = argv
        return SimpleNamespace(stdout="9")

    monkeypatch.setattr(subprocess, "run", fake_run)

    assert dispatch.homer_dispatch("worker") == 9
    assert calls["argv"] == ["homer-dispatch", "--agent", "worker"]
