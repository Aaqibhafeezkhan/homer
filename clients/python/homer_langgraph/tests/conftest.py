"""Shared fixtures for the homer_langgraph test suite."""

from __future__ import annotations

import pytest
from langgraph.checkpoint.memory import MemorySaver

from homer_langgraph import introspection, runner


@pytest.fixture
def env(monkeypatch, tmp_path):
    """Base env: state dir + agent name, no thread/resume/recover (fresh run)."""
    monkeypatch.setenv(runner.ENV_STATE_DIR, str(tmp_path))
    monkeypatch.setenv(runner.ENV_AGENT_NAME, "planner")
    for var in (
        runner.ENV_THREAD,
        runner.ENV_RESUME,
        runner.ENV_INPUT,
        runner.ENV_INTERRUPT,
        runner.ENV_RECOVER,
        runner.ENV_ARTIFACTS_DIR,
    ):
        monkeypatch.delenv(var, raising=False)


@pytest.fixture
def failing_mermaid(monkeypatch):
    """Make every Mermaid draw raise."""
    from langchain_core.runnables.graph import Graph

    def boom(self, *args, **kwargs):
        raise ValueError("draw failed")

    monkeypatch.setattr(Graph, "draw_mermaid", boom)


@pytest.fixture
def shared_saver(monkeypatch):
    """One MemorySaver shared across SqliteSaver() calls (durable across runs)."""
    saver = MemorySaver()
    # `run` (runner) and `state` (introspection) each open their own saver.
    monkeypatch.setattr(runner, "SqliteSaver", lambda conn: saver)
    monkeypatch.setattr(introspection, "SqliteSaver", lambda conn: saver)
    return saver


@pytest.fixture
def captured_runs(monkeypatch):
    runs = []
    monkeypatch.setattr(
        runner.subprocess, "run", lambda argv, **kw: runs.append(argv) or None
    )
    return runs
