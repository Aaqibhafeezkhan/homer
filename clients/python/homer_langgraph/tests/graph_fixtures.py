"""Tiny LangGraph graphs used by the runner branch tests."""

from __future__ import annotations

import operator
from typing import Annotated, Any, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, Send

from homer_langgraph.gates import homer_fanout, homer_interrupt


class State(TypedDict, total=False):
    value: Any
    answer: Any


def _human_node(state: State) -> State:
    answer = homer_interrupt("Proceed?", options=["yes", "no"], ttl=120)
    return {"answer": answer}


def _fanout_node(state: State) -> State:
    result = homer_fanout("worker", params={"topic": "pricing"}, result_jsonpath="$.summary")
    return {"answer": result}


def _passthrough_node(state: State) -> State:
    return {"answer": "done", "value": state.get("value")}


def _ok_node(state: State) -> State:
    return {"value": state.get("value")}


def _boom_node(state: State) -> State:
    raise RuntimeError("boom")


def _human_node_a(state: State) -> State:
    answer = homer_interrupt("Proceed A?")
    return {"answer": answer}


def _human_node_b(state: State) -> State:
    answer = homer_interrupt("Proceed B?")
    return {"answer": answer}


class MultiState(TypedDict, total=False):
    """Distinct per-branch keys so parallel nodes can complete without a
    last_value channel conflict (each branch writes its own slot)."""

    a: Any
    b: Any
    c: Any


def _branch_human(label: str, key: str):
    def node(state: MultiState) -> MultiState:
        return {key: homer_interrupt(f"Proceed {label}?")}

    return node


def _branch_fanout(label: str, key: str, group: str | None = None):
    def node(state: MultiState) -> MultiState:
        return {key: homer_fanout(f"worker_{label}", group=group)}

    return node


def _build_parallel(nodes: dict[str, Any]) -> StateGraph:
    """Build a graph whose nodes all run in parallel off START and join at END,
    each writing a distinct state key."""
    builder = StateGraph(MultiState)
    for name, node in nodes.items():
        builder.add_node(name, node)
        builder.add_edge(START, name)
        builder.add_edge(name, END)
    return builder


def _build(node) -> StateGraph:
    builder = StateGraph(State)
    builder.add_node("n", node)
    builder.add_edge(START, "n")
    builder.add_edge("n", END)
    return builder


def _looping_human(state: State) -> State:
    round_no = state.get("value") or 0
    answer = homer_interrupt(f"Round {round_no}?")
    return {"value": round_no + 1, "answer": answer}


def _build_loop(rounds: int) -> StateGraph:
    """One node that parks on every pass, looping ``rounds`` times.

    Each pass mints a fresh interrupt id at the *same* node, so the thread's
    history accumulates several ids for one node — which is what the
    ``interruptsSeen`` per-node cap bounds.
    """
    builder = StateGraph(State)
    builder.add_node("loop", _looping_human)
    builder.add_edge(START, "loop")
    builder.add_conditional_edges(
        "loop", lambda state: "loop" if (state.get("value") or 0) < rounds else END
    )
    return builder


def _build_multi() -> StateGraph:
    """Two parallel nodes both park, yielding >1 pending interrupt at once."""
    builder = StateGraph(State)
    builder.add_node("a", _human_node_a)
    builder.add_node("b", _human_node_b)
    builder.add_edge(START, "a")
    builder.add_edge(START, "b")
    builder.add_edge("a", END)
    builder.add_edge("b", END)
    return builder


def _build_failing() -> StateGraph:
    """A predecessor that completes, then a node that always raises."""
    builder = StateGraph(State)
    builder.add_node("ok", _ok_node)
    builder.add_node("boom", _boom_node)
    builder.add_edge(START, "ok")
    builder.add_edge("ok", "boom")
    builder.add_edge("boom", END)
    return builder


# How many more times ``flaky`` raises before it succeeds; tests set it.
FLAKY_FAILURES = {"left": 0}


def _flaky_node(state: State) -> State:
    if FLAKY_FAILURES["left"] > 0:
        FLAKY_FAILURES["left"] -= 1
        raise RuntimeError("flaky")
    return {"answer": "recovered"}


def _build_flaky() -> StateGraph:
    """A predecessor that completes, then a node that fails until recovered."""
    builder = StateGraph(State)
    builder.add_node("ok", _ok_node)
    builder.add_node("flaky", _flaky_node)
    builder.add_edge(START, "ok")
    builder.add_edge("ok", "flaky")
    builder.add_edge("flaky", END)
    return builder


# Uncompiled builders — the runner compiles them with its checkpointer.
human_graph = _build(_human_node)
fanout_graph = _build(_fanout_node)
complete_graph = _build(_passthrough_node)
multi_interrupt_graph = _build_multi()
failing_graph = _build_failing()
flaky_graph = _build_flaky()
loop_human_graph = _build_loop(3)

# Parallel multi-interrupt graphs whose branches write distinct keys, so they
# can be driven to completion across segments without a channel conflict.
two_human_graph = _build_parallel(
    {"a": _branch_human("A", "a"), "b": _branch_human("B", "b")}
)
three_human_graph = _build_parallel(
    {
        "a": _branch_human("A", "a"),
        "b": _branch_human("B", "b"),
        "c": _branch_human("C", "c"),
    }
)
two_fanout_graph = _build_parallel(
    {"a": _branch_fanout("A", "a"), "b": _branch_fanout("B", "b")}
)
# Three parallel fanouts sharing one group -> resolved as a single join round-trip.
three_parallel_fanout_graph = _build_parallel(
    {
        "a": _branch_fanout("A", "a", "g"),
        "b": _branch_fanout("B", "b", "g"),
        "c": _branch_fanout("C", "c", "g"),
    }
)


# Graphs for the `traversed` path in `state` output (ADR-0075).


def _set_value(value: Any):
    def node(state: State) -> State:
        return {"value": value}

    return node


def _build_linear() -> StateGraph:
    builder = StateGraph(State)
    builder.add_node("first", _set_value("first"))
    builder.add_node("second", _set_value("second"))
    builder.add_edge(START, "first")
    builder.add_edge("first", "second")
    builder.add_edge("second", END)
    return builder


def _build_router() -> StateGraph:
    """``route`` sends the input ``value`` to the node of that name."""
    builder = StateGraph(State)
    builder.add_node("route", lambda state: {"answer": "routed"})
    builder.add_node("left", _set_value("left"))
    builder.add_node("right", _set_value("right"))
    builder.add_edge(START, "route")
    builder.add_conditional_edges("route", lambda state: state["value"], ["left", "right"])
    builder.add_edge("left", END)
    builder.add_edge("right", END)
    return builder


def _plan(state: State) -> State:
    return {"value": (state.get("value") or 0) + 1}


def _build_self_loop() -> StateGraph:
    """``plan`` routes back to itself until it has run three times, then on."""
    builder = StateGraph(State)
    builder.add_node("plan", _plan)
    builder.add_node("finish", _set_value("finish"))
    builder.add_edge(START, "plan")
    builder.add_conditional_edges(
        "plan", lambda state: "plan" if state["value"] < 3 else "finish", ["plan", "finish"]
    )
    builder.add_edge("finish", END)
    return builder


def _build_join() -> StateGraph:
    """``enter`` fans out to ``a`` and ``b``, which both feed ``join``."""
    builder = StateGraph(MultiState)
    builder.add_node("enter", lambda state: {"c": "enter"})
    builder.add_node("a", lambda state: {"a": "a"})
    builder.add_node("b", lambda state: {"b": "b"})
    builder.add_node("join", lambda state: {"c": "join"})
    builder.add_edge(START, "enter")
    builder.add_edge("enter", "a")
    builder.add_edge("enter", "b")
    builder.add_edge(["a", "b"], "join")
    builder.add_edge("join", END)
    return builder


def _build_gated() -> StateGraph:
    """``gate`` parks between ``prep`` and ``finish``."""
    builder = StateGraph(State)
    builder.add_node("prep", _set_value("prep"))
    builder.add_node("gate", _human_node)
    builder.add_node("finish", _set_value("finish"))
    builder.add_edge(START, "prep")
    builder.add_edge("prep", "gate")
    builder.add_edge("gate", "finish")
    builder.add_edge("finish", END)
    return builder


class SendState(TypedDict, total=False):
    items: Annotated[list, operator.add]


def _build_send() -> StateGraph:
    """``fan`` dispatches two ``Send`` packets to ``work``."""
    builder = StateGraph(SendState)
    builder.add_node("fan", lambda state: {"items": []})
    builder.add_node("work", lambda arg: {"items": [arg["n"]]})
    builder.add_edge(START, "fan")
    builder.add_conditional_edges(
        "fan", lambda state: [Send("work", {"n": n}) for n in range(2)], ["work"]
    )
    builder.add_edge("work", END)
    return builder


def _build_command() -> StateGraph:
    """``pick`` routes with ``Command(goto=...)`` to the node named by ``value``."""
    builder = StateGraph(State)
    builder.add_node(
        "pick",
        lambda state: Command(goto=state["value"], update={"answer": "picked"}),
        destinations=("left", "right"),
    )
    builder.add_node("left", _set_value("left"))
    builder.add_node("right", _set_value("right"))
    builder.add_edge(START, "pick")
    builder.add_edge("left", END)
    builder.add_edge("right", END)
    return builder


linear_graph = _build_linear()
router_graph = _build_router()
self_loop_graph = _build_self_loop()
join_graph = _build_join()
gated_graph = _build_gated()
send_graph = _build_send()
command_graph = _build_command()
