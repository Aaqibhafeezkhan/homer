"""START -> plan -> review, then review routes back to plan ("again") or to END ("done")."""

from typing import TypedDict

from langgraph.graph import END, START, StateGraph


class State(TypedDict, total=False):
    rounds: int


def plan(state: State) -> State:
    return {"rounds": state.get("rounds", 0) + 1}


def review(state: State) -> State:
    return {}


def route(state: State) -> str:
    return "done" if state.get("rounds", 0) >= 2 else "again"


graph = StateGraph(State)
graph.add_node("plan", plan)
graph.add_node("review", review)
graph.add_edge(START, "plan")
graph.add_edge("plan", "review")
graph.add_conditional_edges("review", route, {"again": "plan", "done": END})
