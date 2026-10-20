"""Example graph for the ``langgraph_demo`` agent.

Two checks run in parallel as one fan-out group (a single ``homer-join``
round-trip to the ``langgraph_demo_check`` sub-agent), their results are
summarized, then an operator approves or rejects on a human gate.
"""

from __future__ import annotations

from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from homer_langgraph.gates import homer_fanout, homer_interrupt

CHECKS = ("lint", "tests")


class State(TypedDict, total=False):
    # One slot per check: node bodies re-run on resume, so a grouped fan-out
    # must not write to an accumulating channel.
    lint: dict[str, Any]
    tests: dict[str, Any]
    summary: str
    decision: str
    outcome: str


def _check(name: str):
    def node(state: State) -> State:
        return {
            name: homer_fanout(
                "langgraph_demo_check",
                params={"check": name},
                result_jsonpath="$.ok",
                group="checks",
            )
        }

    return node


def _passed(payload: dict[str, Any]) -> bool:
    return payload.get("status") == "FINISHED" and str(payload.get("result")).lower() == "true"


def summarize(state: State) -> State:
    passed = sum(_passed(state.get(name) or {}) for name in CHECKS)
    return {"summary": f"{passed}/{len(CHECKS)} checks passed"}


def approve(state: State) -> State:
    answer = homer_interrupt(
        f"{state['summary']}. Ship it?", options=["approve", "reject"], ttl=3600
    )
    return {"decision": answer.strip().lower()}


def ship(state: State) -> State:
    return {"outcome": "shipped"}


def reject(state: State) -> State:
    return {"outcome": "rejected"}


builder = StateGraph(State)
for check in CHECKS:
    builder.add_node(check, _check(check))
    builder.add_edge(START, check)
builder.add_node("summarize", summarize)
builder.add_node("approve", approve)
builder.add_node("ship", ship)
builder.add_node("reject", reject)
builder.add_edge(list(CHECKS), "summarize")
builder.add_edge("summarize", "approve")
builder.add_conditional_edges(
    "approve",
    lambda state: "ship" if state["decision"] == "approve" else "reject",
    ["ship", "reject"],
)
builder.add_edge("ship", END)
builder.add_edge("reject", END)

# Uncompiled: the runtime compiles it with its durable checkpointer.
graph = builder
