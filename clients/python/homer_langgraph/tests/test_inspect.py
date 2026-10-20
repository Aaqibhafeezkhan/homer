"""Tests for the read-only `inspect` graph-introspection mode."""

from __future__ import annotations

import json

import pytest

from homer_langgraph import __main__, introspection, runner


def test_inspect_mermaid_contains_graph_structure():
    mermaid = introspection.inspect_mermaid(
        "homer_langgraph.tests.graph_fixtures:complete_graph"
    )
    assert mermaid.strip()
    assert "__start__" in mermaid


def test_inspect_mermaid_accepts_compiled_graph(monkeypatch):
    # complete_graph is an uncompiled StateGraph; verify an already-compiled
    # graph also works by compiling one and exposing it as a module attr.
    from homer_langgraph.tests import graph_fixtures

    compiled = graph_fixtures.complete_graph.compile()
    monkeypatch.setattr(graph_fixtures, "compiled_demo", compiled, raising=False)
    mermaid = introspection.inspect_mermaid(
        "homer_langgraph.tests.graph_fixtures:compiled_demo"
    )
    assert "__start__" in mermaid


def test_inspect_mermaid_bad_attr_raises():
    with pytest.raises(Exception):
        introspection.inspect_mermaid(
            "homer_langgraph.tests.graph_fixtures:does_not_exist"
        )


def test_graph_topology_lists_nodes_and_edges():
    result = introspection.inspect_graph("homer_langgraph.tests.graph_fixtures:complete_graph")
    topology = result["topology"]
    ids = [node["id"] for node in topology["nodes"]]
    assert "__start__" in ids and "__end__" in ids
    for edge in topology["edges"]:
        assert edge["source"] in ids and edge["target"] in ids
        assert isinstance(edge["conditional"], bool)
        assert edge["label"] is None or isinstance(edge["label"], str)
    assert set(topology["nodes"][0]) == {"id"}, "no runnable class paths leak out"
    assert "__start__" in result["mermaid"]


def test_graph_topology_marks_conditional_edges_with_their_route_label():
    from typing import TypedDict

    from langgraph.graph import END, START, StateGraph

    class S(TypedDict):
        x: int

    builder = StateGraph(S)
    builder.add_node("work", lambda s: s)
    builder.add_edge(START, "work")
    builder.add_conditional_edges("work", lambda s: "done", {"again": "work", "done": END})
    topology = introspection.graph_topology(builder.compile().get_graph())
    routed = {(e["source"], e["target"]): e for e in topology["edges"]}
    assert routed[("work", "__end__")] == {
        "source": "work", "target": "__end__", "label": "done", "conditional": True,
    }
    assert routed[("work", "work")]["label"] == "again"
    assert routed[("__start__", "work")]["conditional"] is False


def test_inspect_graph_survives_a_mermaid_draw_failure(failing_mermaid):
    result = introspection.inspect_graph("homer_langgraph.tests.graph_fixtures:complete_graph")
    assert result["mermaid"] is None
    assert result["topology"]["nodes"]


def test_cli_inspect_json_prints_one_json_object(capsys):
    assert __main__.main(
        ["inspect", "--module", "homer_langgraph.tests.graph_fixtures:complete_graph", "--json"]
    ) == 0
    payload = json.loads(capsys.readouterr().out)
    assert list(payload) == ["mermaid", "topology"]


def test_cli_inspect_failure_exits_1_with_message(capsys):
    assert __main__.main(
        ["inspect", "--module", "homer_langgraph.tests.graph_fixtures:does_not_exist"]
    ) == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err.startswith("homer_langgraph inspect failed: ")


def test_cli_inspect_without_json_still_prints_raw_mermaid(capsys):
    assert __main__.main(
        ["inspect", "--module", "homer_langgraph.tests.graph_fixtures:complete_graph"]
    ) == 0
    assert capsys.readouterr().out.lstrip().startswith(("---", "graph", "flowchart"))
